/*
 * Copyright (c) 2024-2025 Elide Technologies, Inc.
 *
 * Licensed under the MIT license (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *   https://opensource.org/license/mit/
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under the License.
 */
package dev.elide.intellij.execution.build


/** Outcome a progress line reports for one step of a build. */
internal enum class ElideBuildStepStatus {
  /** `✓`: the step ran and produced its output. */
  SUCCEEDED,

  /** `⇥`: the step had nothing to do, because its output was up to date, cached, or had no input. */
  SKIPPED,

  /** `✗`: the step failed, and with it the build. */
  FAILED,
}

/** Severity of a diagnostic the CLI printed while building. */
internal enum class ElideBuildSeverity { ERROR, WARNING, INFO }

/**
 * A diagnostic the CLI printed, as one message plus the block of text it was printed with.
 *
 * @param severity The severity the CLI named the diagnostic with.
 * @param tool Tool that reported it (`kotlinc`, `javac`), or `null` when the CLI reported it itself.
 * @param message The diagnostic's first line, which is what the tree node shows.
 * @param notes Further lines of the message, which some tools print under it, or an empty list when it has none.
 * @param excerpt Lines of the source excerpt the CLI rendered under the position, or an empty list when it drew none.
 * @param file Path named by the `In file:` line, as printed: relative to the project, or absolute.
 * @param line 1-based line number named by the `In file:` line, or `null` when it named none.
 * @param column 1-based column number named by the `In file:` line, or `null` when it named none.
 * @param elapsedMillis Time from the start of the build to the line that opened it, from the CLI's own clock.
 */
internal data class ElideBuildDiagnostic(
  val severity: ElideBuildSeverity,
  val tool: String?,
  val message: String,
  val notes: List<String> = emptyList(),
  val excerpt: List<String> = emptyList(),
  val file: String? = null,
  val line: Int? = null,
  val column: Int? = null,
  val elapsedMillis: Long? = null,
)

/**
 * One step of a build the CLI has reported the completion of.
 *
 * @param message The step as the CLI describes it, with the trailing parenthetical removed.
 * @param detail Text of that parenthetical: a duration for a step that ran, a reason for one that did not.
 * @param status Outcome the line's glyph reports.
 * @param elapsedMillis Time from the start of the build to this line, from the clock the CLI prints on every line.
 * @param durationMillis How long the step itself took, when [detail] is a duration rather than a reason.
 * @param diagnostics Diagnostics the CLI printed before this line, which are the ones this step produced.
 */
internal data class ElideBuildStep(
  val message: String,
  val detail: String?,
  val status: ElideBuildStepStatus,
  val elapsedMillis: Long?,
  val durationMillis: Long?,
  val diagnostics: List<ElideBuildDiagnostic>,
)

/**
 * What one line of the CLI's output turned out to be.
 *
 * @param claimed Whether the line belongs to the CLI's own progress log rather than to the program it ran, which is
 *   what lets the caller show the two differently.
 * @param step The step the line reported the completion of, when it reported one.
 */
internal data class ElideBuildLine(
  val claimed: Boolean,
  val step: ElideBuildStep? = null,
)

/**
 * Incremental decoder for the progress log `elide build`, `elide run` and `elide test` write on standard error.
 *
 * Every line of that log is stamped with the time elapsed since the build started and, for the lines that report a
 * step, prefixed with a glyph naming the step's outcome:
 *
 * ```
 * [ 61ms] ✓ Resolved 19 Maven dependencies (39ms)
 * [169ms] error: kotlinc: Unresolved reference 'boom'.
 * In file: src/main/com/example/Hello.kt:5:3
 * [170ms] ✗ Kotlin main compilation failed (108ms)
 * ```
 *
 * The CLI writes this shape whenever standard error is not a terminal, which is always the case for a run started
 * from the IDE; `CI=1` asks for it explicitly. Steps complete out of order, since the build runs them in parallel,
 * so each line stands on its own: there is no start marker to pair it with, and every line carries the duration of
 * the step it reports.
 *
 * Diagnostics are held until the next step line claims them, because the CLI prints them immediately before the
 * step whose work produced them. Only the first diagnostic a tool reported in one go carries a stamp; the rest
 * arrive unstamped, with the tool named again on each, and are read as diagnostics of their own:
 *
 * ```
 * [ 72ms] warning: javac: location of system modules is not set in conjunction with -source 21
 *   not setting the location of system modules may lead to class files that cannot run on JDK 21
 * javac: /home/me/app/src/main/java/com/example/App.java uses or overrides a deprecated API.
 * In file: /home/me/app/src/main/java/com/example/App.java
 * ```
 *
 * Where a tool names the file it is about is the tool's own business: some print an `In file:` line under the
 * message, with a source excerpt below it, and others open the message with the position instead, as a path or as
 * a URI — `error: kotlinc: file:///home/me/app/src/App.kt:11:16 Unresolved reference 'Strin'.`. Both are read as
 * the diagnostic's position, so neither leaves a path in the message.
 *
 * Anything the decoder does not recognise is left alone: the caller keeps writing every line to the console, and
 * only the lines decoded here also become nodes of the build tree.
 *
 * Not thread safe: the caller feeds it a single stream, in order.
 */
internal class ElideBuildProgressParser {
  /** Diagnostics printed since the last step line, which the next one takes with it. */
  private val diagnostics = mutableListOf<ElideBuildDiagnostic>()

  /** The diagnostic being read, whose `In file:` line and source excerpt may still follow. */
  private var pending: PendingDiagnostic? = null

  /** A diagnostic held open while the lines that belong to it keep arriving. */
  private class PendingDiagnostic(
    val severity: ElideBuildSeverity,
    val tool: String?,
    text: String,
    val elapsedMillis: Long?,
  ) {
    /** Message lines that arrived before the position, which is where the tools that print notes put them. */
    val notes: MutableList<String> = mutableListOf()

    /** Lines of the source excerpt, which the CLI draws after the position. */
    val excerpt: MutableList<String> = mutableListOf()

    val message: String
    var file: String? = null
    var line: Int? = null
    var column: Int? = null

    init {
      // most tools open the message with the position they are about, and the CLI passes that through: `kotlinc`
      // writes `file:///home/me/app/src/App.kt:11:16 Unresolved reference 'Strin'.` and `javac`
      // `/home/me/app/src/App.java uses or overrides a deprecated API.`. Read as a position it is a node the tree
      // can name the file of and open; left in the message it is a path where the message should be
      val leading = ElideSourceLocations.leading(text)
      val rest = leading?.let { (_, start) -> text.substring(start) }

      if (leading == null || rest.isNullOrBlank()) {
        message = text
      } else {
        message = rest
        file = leading.first.path
        line = leading.first.line
        column = leading.first.column
      }
    }

    /** Whether the position has been read, which is what tells a note from a line of the excerpt. */
    val positioned: Boolean get() = file != null

    fun toDiagnostic(): ElideBuildDiagnostic = ElideBuildDiagnostic(
      severity = severity,
      tool = tool,
      message = message,
      notes = notes.dropLastWhile(String::isBlank),
      excerpt = excerpt.dropWhile(String::isBlank).dropLastWhile(String::isBlank),
      file = file,
      line = line,
      column = column,
      elapsedMillis = elapsedMillis,
    )
  }

  /**
   * Decode one line of the log, reporting whether it belongs to the CLI and the step it completed, if any.
   *
   * The line is taken as the CLI wrote it: the trailing newline, a carriage return before it and any colour the
   * CLI was asked to emit are removed here rather than by the caller.
   */
  fun line(text: String): ElideBuildLine {
    val line = ANSI.replace(text, "").trimEnd('\n', '\r')
    val stamped = STAMPED.matchEntire(line)

    // an unstamped line continues the diagnostic above it: its `In file:` line, or a line of source excerpt. With no
    // diagnostic open it is not the CLI's line at all, but something the program it ran wrote on the same stream
    if (stamped == null) return ElideBuildLine(claimed = continueDiagnostic(line))

    // a stamped line always ends the diagnostic above it, whether it reports a step or opens another diagnostic
    closeDiagnostic()

    val elapsed = parseDuration(stamped.groupValues[1])
    val body = stamped.groupValues[2]

    val status = STATUS_GLYPHS[body.firstOrNull()]
    if (status == null) {
      openDiagnostic(body, elapsed)
      return ElideBuildLine(claimed = true)
    }

    return ElideBuildLine(claimed = true, step = step(body.drop(1).trim(), status, elapsed))
  }

  /**
   * Decode the diagnostic left open by a log that ended on one, which is the shape a build cut short leaves.
   *
   * Diagnostics no step claimed are returned here too: a build that fails before any step runs prints the reason
   * and stops, and that reason is the only report of the failure there is.
   */
  fun flush(): List<ElideBuildDiagnostic> {
    closeDiagnostic()

    val orphans = diagnostics.toList()
    diagnostics.clear()

    return orphans
  }

  /** Builds the step [message] reports, or `null` when the line is the build's own result rather than a step. */
  private fun step(message: String, status: ElideBuildStepStatus, elapsedMillis: Long?): ElideBuildStep? {
    // the closing line reports the build itself, which the platform's own root node already stands for: a node of
    // its own would nest the build inside itself. Diagnostics it has not claimed are left where they are, since a
    // build that failed before running a step prints its reason right before this line
    if (BUILD_RESULT.containsMatchIn(message)) return null

    val parenthetical = PARENTHETICAL.matchEntire(message)
    val detail = parenthetical?.groupValues?.get(2)
    val claimed = diagnostics.toList()
    diagnostics.clear()

    return ElideBuildStep(
      message = parenthetical?.groupValues?.get(1) ?: message,
      detail = detail,
      status = status,
      elapsedMillis = elapsedMillis,
      durationMillis = detail?.let(::parseDuration),
      diagnostics = claimed,
    )
  }

  /** Opens a diagnostic when [body] names a severity, or drops the line when it is ordinary log output. */
  private fun openDiagnostic(body: String, elapsedMillis: Long?) {
    val diagnostic = DIAGNOSTIC.matchEntire(body) ?: return
    val severity = SEVERITIES[diagnostic.groupValues[1]] ?: return

    // the CLI repeats the severity in brackets on some diagnostics ("warning: [warning] kotlinc: …"); the message
    // reads better without the repetition, and the severity is already known from the prefix that carried it
    val text = diagnostic.groupValues[2].removePrefix("[${diagnostic.groupValues[1]}]").trimStart()
    val tool = TOOL.matchEntire(text)

    pending = PendingDiagnostic(
      severity = severity,
      tool = tool?.groupValues?.get(1),
      text = tool?.groupValues?.get(2) ?: text,
      elapsedMillis = elapsedMillis,
    )
  }

  /**
   * Adds [line] to the diagnostic being read — its file position, the next diagnostic of the same batch, a note
   * under its message, or a line of the source excerpt it printed — reporting whether there was one to add it to.
   *
   * The position is not kept as text: it is the one part of the block the tree and the console render themselves,
   * and against a path of their own, since the CLI writes it relative to the directory it ran in for some tools and
   * absolute for others.
   */
  private fun continueDiagnostic(line: String): Boolean {
    val diagnostic = pending ?: return false

    // the CLI stamps only the first diagnostic a tool reported in one go, and names the tool again on each of the
    // rest: `javac: Recompile with -Xlint:deprecation for details.`. They are diagnostics of their own, and folding
    // them into the first one as text would leave the tree with a single node holding all of them
    if (diagnostic.tool != null && line.startsWith("${diagnostic.tool}: ")) {
      closeDiagnostic()
      pending = PendingDiagnostic(
        severity = diagnostic.severity,
        tool = diagnostic.tool,
        text = line.removePrefix("${diagnostic.tool}: ").trimStart(),
        elapsedMillis = diagnostic.elapsedMillis,
      )

      return true
    }

    val position = IN_FILE.matchEntire(line)
      ?.let { ElideSourceLocations.findAll(it.groupValues[1]).firstOrNull() }

    when {
      position != null -> {
        diagnostic.file = position.path
        diagnostic.line = position.line ?: diagnostic.line
        diagnostic.column = position.column ?: diagnostic.column
      }

      diagnostic.positioned -> diagnostic.excerpt += line
      else -> diagnostic.notes += line
    }

    return true
  }

  private fun closeDiagnostic() {
    val diagnostic = pending ?: return
    pending = null
    diagnostics += diagnostic.toDiagnostic()
  }

  internal companion object {
    /** Escape sequences a CLI forced to emit colour would wrap its output in. */
    private val ANSI = Regex("\u001B\\[[0-9;]*[A-Za-z]")

    /** A log line and the elapsed time every one of them opens with, e.g. `[ 61ms] ✓ Compiled …`. */
    private val STAMPED = Regex("""\[\s*([^]]+)]\s?(.*)""")

    /** Trailing parenthetical of a step line: its duration, or the reason it did not run. */
    private val PARENTHETICAL = Regex("""(.*?)\s*\(([^()]*)\)""")

    /** The closing line of a build, which reports the build itself rather than a step of it. */
    private val BUILD_RESULT = Regex("""^Build (successful|failed|cancelled)\b""")

    /** A diagnostic's opening line, e.g. `error: kotlinc: Unresolved reference 'boom'.`. */
    private val DIAGNOSTIC = Regex("""(error|warning|info):\s+(.*)""", RegexOption.IGNORE_CASE)

    /** The tool a diagnostic names before its message, e.g. `kotlinc: `. */
    private val TOOL = Regex("""([\w.+-]+):\s+(.*)""")

    /** The source position a diagnostic prints under its message, in the forms [ElideSourceLocations] reads. */
    private val IN_FILE = Regex("""In file:\s+(\S.*)""")

    /** One `<number><unit>` term of a duration, as the CLI formats it. */
    private val DURATION_TERM = Regex("""(\d+(?:\.\d+)?)\s*(ms|s|m|h)""")

    /** Outcome each glyph the CLI prefixes a step line with reports. */
    private val STATUS_GLYPHS: Map<Char, ElideBuildStepStatus> = mapOf(
      '\u2713' to ElideBuildStepStatus.SUCCEEDED,
      '\u2717' to ElideBuildStepStatus.FAILED,
      '\u21E5' to ElideBuildStepStatus.SKIPPED,
    )

    private val SEVERITIES: Map<String, ElideBuildSeverity> = mapOf(
      "error" to ElideBuildSeverity.ERROR,
      "warning" to ElideBuildSeverity.WARNING,
      "info" to ElideBuildSeverity.INFO,
    )

    private val UNIT_MILLIS: Map<String, Long> = mapOf(
      "ms" to 1L,
      "s" to 1_000L,
      "m" to 60_000L,
      "h" to 3_600_000L,
    )

    /**
     * Reads a duration the CLI printed, e.g. `39ms`, `3.6s` or `1m 20s`, as milliseconds.
     *
     * Text holding no duration at all — the reason a skipped step carries, such as `Up to date` — reads as `null`,
     * which is what tells the two apart.
     */
    fun parseDuration(text: String): Long? {
      val terms = DURATION_TERM.findAll(text).toList()
      if (terms.isEmpty()) return null

      // a reason that happens to contain a number is not a duration: only text made up entirely of terms is
      val matched = terms.sumOf { it.value.filterNot(Char::isWhitespace).length }
      if (matched != text.filterNot(Char::isWhitespace).length) return null

      return terms.sumOf { term ->
        val unit = UNIT_MILLIS.getValue(term.groupValues[2])
        (term.groupValues[1].toDouble() * unit).toLong()
      }
    }
  }
}
