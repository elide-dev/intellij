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
package dev.elide.intellij.execution.test

/** Terminal state of a test, as a TAP test point reports it. */
internal enum class ElideTestOutcome {
  /** `ok`, with no directive. */
  PASSED,

  /** `not ok`. */
  FAILED,

  /** `ok … # SKIP`: the test declared itself skipped, or the run's name filter rejected it. */
  SKIPPED,

  /** `ok … # TODO`: the test is known to be unfinished. */
  TODO,
}

/** One event decoded from the TAP 13 stream `elide test --reporter=tap` writes on standard output. */
internal sealed interface ElideTapEvent {
  /**
   * A test has begun, as announced by a `# start <id>: <label>` comment.
   *
   * @param id Start number tagging the test's [Output] lines. It is a sequence of its own, unrelated to the test
   *   point numbers on result lines, and a test that never reports a result keeps its number forever.
   * @param label The label the test's result line will carry, which is what binds the two.
   */
  data class Started(val id: Int, val label: String) : ElideTapEvent

  /** One line the test with start number [id] wrote, from a `# out <id>: <text>` comment. */
  data class Output(val id: Int, val text: String) : ElideTapEvent

  /**
   * A settled test point and the YAMLish diagnostic block that followed it.
   *
   * @param label The test's full display label, suite chain included, unescaped.
   * @param reason Reason given by a `SKIP` or `TODO` directive, or `null` when the directive carried none.
   * @param message The failure's message, or `null` when the record carried no diagnostic block.
   * @param detail The failure's diagnostic lines (assertion rendering, stack frames) as the CLI wrote them, with
   *   only the block's own indentation removed.
   */
  data class Result(
    val label: String,
    val outcome: ElideTestOutcome,
    val reason: String?,
    val message: String?,
    val detail: List<String>,
  ) : ElideTapEvent

  /** The run's plan, `1..N`, which Elide writes once every test has settled. */
  data class Plan(val count: Int) : ElideTapEvent

  /** A line that is not a test point: unowned commentary, or another tool's output on the same stream. */
  data class Unowned(val text: String) : ElideTapEvent
}

/**
 * Incremental decoder for the TAP 13 stream `elide test --reporter=tap` produces.
 *
 * Lines are fed in as they arrive and decoded in place, so a run is reported while it is still going. Only the
 * framing Elide's own writer produces is recognised; anything else on the stream is passed through as
 * [ElideTapEvent.Unowned] rather than dropped, because the CLI shares standard output with whatever a test prints
 * outside a test point.
 *
 * Not thread safe: callers hold their own lock, since the events have to reach the test tree in stream order.
 */
internal class ElideTapParser {
  /** Bytes of a line whose newline has not arrived yet. */
  private val partial = StringBuilder()

  /** A result line whose diagnostic block may still follow; the next line decides. */
  private var pending: PendingResult? = null

  /** A result line held back until its diagnostic block is known to be complete, or known not to exist. */
  private class PendingResult(
    val label: String,
    val outcome: ElideTestOutcome,
    val reason: String?,
  ) {
    var inBlock: Boolean = false
    var inDetail: Boolean = false
    var message: String? = null
    val detail: MutableList<String> = mutableListOf()

    fun toEvent(): ElideTapEvent.Result =
      ElideTapEvent.Result(label, outcome, reason, message, detail.toList())
  }

  /** Decode every complete line in [chunk], emitting the events it yields to [emit] in stream order. */
  fun feed(chunk: String, emit: (ElideTapEvent) -> Unit) {
    var start = 0
    while (true) {
      val newline = chunk.indexOf('\n', start)
      if (newline < 0) break

      partial.append(chunk, start, newline)
      line(partial.toString().removeSuffix("\r"), emit)
      partial.setLength(0)
      start = newline + 1
    }
    partial.append(chunk, start, chunk.length)
  }

  /**
   * Decode whatever is left over: a final line the stream ended without terminating, and a result whose diagnostic
   * block was cut short.
   */
  fun flush(emit: (ElideTapEvent) -> Unit) {
    if (partial.isNotEmpty()) {
      line(partial.toString(), emit)
      partial.setLength(0)
    }
    flushPending(emit)
  }

  private fun flushPending(emit: (ElideTapEvent) -> Unit) {
    val result = pending ?: return
    pending = null
    emit(result.toEvent())
  }

  private fun line(text: String, emit: (ElideTapEvent) -> Unit) {
    if (!consumedByBlock(text, emit)) dispatch(text, emit)
  }

  /**
   * Consume [text] as part of a held-back result's diagnostic block, reporting whether it belonged to one.
   *
   * The writer emits a result and its block as one contiguous run, so the line right after a result line is the
   * only place a block can open, and every line inside one is indented. A block the stream cut short therefore
   * ends at the first line that is not, rather than swallowing everything that follows it.
   */
  private fun consumedByBlock(text: String, emit: (ElideTapEvent) -> Unit): Boolean {
    val result = pending ?: return false

    if (!result.inBlock) {
      if (text != BLOCK_START) {
        flushPending(emit)
        return false
      }
      result.inBlock = true
      return true
    }

    if (text == BLOCK_END) {
      flushPending(emit)
      return true
    }
    if (!text.startsWith(FIELD_INDENT)) {
      flushPending(emit)
      return false
    }

    when {
      result.inDetail && text.startsWith(FIELD_VALUE_INDENT) -> result.detail += text.removePrefix(FIELD_VALUE_INDENT)
      text == DETAIL_FIELD -> result.inDetail = true
      text.startsWith(MESSAGE_FIELD) -> {
        result.inDetail = false
        result.message = yamlScalar(text.removePrefix(MESSAGE_FIELD))
      }
      // `severity` carries the runner's own error code; the tree shows the message and the detail lines instead
      else -> result.inDetail = false
    }
    return true
  }

  private fun dispatch(text: String, emit: (ElideTapEvent) -> Unit) {
    if (VERSION.matches(text)) return

    PLAN.matchEntire(text)?.let { return emit(ElideTapEvent.Plan(it.groupValues[1].toInt())) }

    TEST_POINT.matchEntire(text)?.let { point ->
      pending = pendingResult(notOk = point.groupValues[1] == NOT_OK, description = point.groupValues[3])
      return
    }

    START_COMMENT.matchEntire(text)?.let {
      return emit(ElideTapEvent.Started(it.groupValues[1].toInt(), it.groupValues[2]))
    }

    OUTPUT_COMMENT.matchEntire(text)?.let {
      return emit(ElideTapEvent.Output(it.groupValues[1].toInt(), it.groupValues[2]))
    }

    // a comment is how the writer frames a line no test owns, so the framing comes back off before the line is
    // handed on as the plain text it was
    emit(ElideTapEvent.Unowned(text.removePrefix(COMMENT_PREFIX)))
  }

  /**
   * Split a test point's description into its label and its directive, and read the outcome off both.
   *
   * A `not ok` line is always a failure: TAP would read `not ok … # TODO` as an expected failure, but Elide's writer
   * only ever puts a directive on an `ok` line.
   */
  private fun pendingResult(notOk: Boolean, description: String): PendingResult {
    val directive = directiveOf(description)
    val label = unescape(labelOf(description))

    val outcome = when {
      notOk -> ElideTestOutcome.FAILED
      directive == null -> ElideTestOutcome.PASSED
      directive.startsWith(SKIP_DIRECTIVE, ignoreCase = true) -> ElideTestOutcome.SKIPPED
      directive.startsWith(TODO_DIRECTIVE, ignoreCase = true) -> ElideTestOutcome.TODO
      else -> ElideTestOutcome.PASSED
    }

    val reason = when (outcome) {
      ElideTestOutcome.SKIPPED -> directive?.drop(SKIP_DIRECTIVE.length)
      ElideTestOutcome.TODO -> directive?.drop(TODO_DIRECTIVE.length)
      else -> null
    }

    return PendingResult(label, outcome, reason?.trim()?.ifEmpty { null })
  }

  private companion object {
    /** The version line, which opens the stream. */
    private val VERSION = Regex("""TAP version \d+""")

    /** The plan line, stating how many test points the stream carries. */
    private val PLAN = Regex("""1\.\.(\d+)\s*""")

    /**
     * A test point: its outcome, its number, and its description.
     *
     * The number is optional and unused: it is the settle order TAP requires to run `1..N`, which says nothing the
     * label does not, and a `# SKIP` result Elide reports without a matching `# start` still carries one.
     */
    private val TEST_POINT = Regex("""(not ok|ok)\b\s*(\d+)?\s*(?:-\s*)?(.*)""")

    /** The comment announcing a test's start, and the start number its output lines are tagged with. */
    private val START_COMMENT = Regex("""# start (\d+): (.*)""")

    /** The comment carrying one line a test wrote, tagged with the writer's start number. */
    private val OUTPUT_COMMENT = Regex("""# out (\d+): (.*)""")

    private const val NOT_OK = "not ok"
    private const val COMMENT_PREFIX = "# "
    private const val BLOCK_START = "  ---"
    private const val BLOCK_END = "  ..."
    private const val MESSAGE_FIELD = "  message: "
    private const val DETAIL_FIELD = "  detail: |"
    private const val FIELD_INDENT = "  "
    private const val FIELD_VALUE_INDENT = "    "
    private const val SKIP_DIRECTIVE = "SKIP"
    private const val TODO_DIRECTIVE = "TODO"

    /**
     * The part of a test point's description before its directive.
     *
     * TAP reads a description from the first *unescaped* `#` onward as a directive, so a `#` in a test's own name,
     * which the writer escapes, must not end the label here.
     */
    private fun labelOf(description: String): String {
      val hash = directiveStart(description)
      return (if (hash < 0) description else description.take(hash)).trimEnd()
    }

    /** The text of a test point's directive, without its `#`, or `null` when the description carries none. */
    private fun directiveOf(description: String): String? {
      val hash = directiveStart(description)
      return if (hash < 0) null else description.substring(hash + 1).trim()
    }

    /** Index of the first unescaped `#` in [description], or `-1` when it has none. */
    private fun directiveStart(description: String): Int {
      var index = 0
      while (index < description.length) {
        when (description[index]) {
          '\\' -> index++
          '#' -> return index
        }
        index++
      }
      return -1
    }

    /** Undo the escaping a test point's description carries: a backslash quotes the character after it. */
    private fun unescape(value: String): String {
      if ('\\' !in value) return value

      return buildString(value.length) {
        var index = 0
        while (index < value.length) {
          val char = value[index]
          if (char == '\\' && index + 1 < value.length) index++
          append(value[index])
          index++
        }
      }
    }

    /** Decode a YAML double-quoted scalar, the form the writer gives a diagnostic block's fields. */
    private fun yamlScalar(value: String): String {
      val quoted = value.trim()
      if (!quoted.startsWith('"') || !quoted.endsWith('"') || quoted.length < 2) return quoted

      val body = quoted.substring(1, quoted.length - 1)
      return buildString(body.length) {
        var index = 0
        while (index < body.length) {
          val char = body[index]
          if (char != '\\' || index + 1 >= body.length) {
            append(char)
            index++
            continue
          }

          when (val escaped = body[index + 1]) {
            'n' -> append('\n')
            't' -> append('\t')
            'r' -> append('\r')
            else -> append(escaped)
          }
          index += 2
        }
      }
    }
  }
}
