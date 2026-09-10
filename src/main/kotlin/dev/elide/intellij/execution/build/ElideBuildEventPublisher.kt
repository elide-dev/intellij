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

import com.intellij.build.FileNavigatable
import com.intellij.build.FilePosition
import com.intellij.build.events.BuildEvent
import com.intellij.build.events.EventResult
import com.intellij.build.events.MessageEvent
import com.intellij.build.events.impl.FailureResultImpl
import com.intellij.build.events.impl.FinishEventImpl
import com.intellij.build.events.impl.MessageEventImpl
import com.intellij.build.events.impl.OutputBuildEventImpl
import com.intellij.build.events.impl.SkippedResultImpl
import com.intellij.build.events.impl.StartEventImpl
import com.intellij.build.events.impl.SuccessResultImpl
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.project.Project
import com.intellij.pom.Navigatable
import dev.elide.intellij.Constants
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString

/**
 * Turns the progress log of a running Elide command into the build events the IDE renders as a tree.
 *
 * Each step the CLI reports becomes a node under the run's root node, carrying the outcome the CLI gave it and the
 * diagnostics it printed on the way; the Build window shows that tree for a build, and the Run window's build view
 * for everything else. Nodes are opened and closed in one go, because the log announces a step only once it is
 * over, and their times are taken from the CLI's own clock rather than from when the line was read, so the
 * durations shown are the ones the CLI measured.
 *
 * Only standard error is fed in: that is where the CLI writes this log, and it keeps a program that prints a
 * lookalike line on standard output from being mistaken for one.
 *
 * Not thread safe: [accept] follows the single reader of that stream, and [flush] runs once it has ended.
 *
 * @param taskId The run this log belongs to, which is also its root node.
 * @param projectPath Directory the CLI ran in, against which the paths its diagnostics name are resolved.
 * @param startedAt Wall clock time the run started, which the log's elapsed times are counted from.
 * @param publish Sink for the events, ordinarily the task listener of the running external system task.
 */
internal class ElideBuildEventPublisher(
  private val taskId: ExternalSystemTaskId,
  private val projectPath: Path,
  private val startedAt: Long,
  private val publish: (BuildEvent) -> Unit,
) {
  private val parser = ElideBuildProgressParser()

  /**
   * The project directory with every symbolic link along it resolved, which is what the paths the diagnostics name
   * are made relative to.
   *
   * The CLI prints some of those paths absolute, and an absolute path it obtained from the filesystem names the
   * real directory: on a machine where the project is reached through a link — `/tmp` on macOS is one — the printed
   * path shares no prefix with the directory the IDE knows the project by, and relativising it against that one
   * would leave the whole path on display.
   */
  private val projectRoot: Path by lazy(LazyThreadSafetyMode.NONE) {
    runCatching { projectPath.toRealPath() }.getOrDefault(projectPath.normalize())
  }

  /**
   * Decodes one line of the CLI's standard error, publishing the events it completes, and reports whether the line
   * was the CLI's own rather than something the program it ran wrote on the same stream.
   */
  fun accept(line: String): Boolean {
    val decoded = parser.line(line)
    decoded.step?.let(::publishStep)

    return decoded.claimed
  }

  /**
   * Publishes what the end of the log leaves behind: diagnostics no step ever claimed.
   *
   * A build that fails while loading the manifest reports the reason and stops without running a step, so these
   * are attached to the run's root node, where they are the only account of the failure.
   */
  fun flush() {
    parser.flush().forEach { publishDiagnostic(it, taskId) }
  }

  private fun publishStep(step: ElideBuildStep) {
    val nodeId = Any()
    val finishedAt = startedAt + (step.elapsedMillis ?: (System.currentTimeMillis() - startedAt))
    val stepStartedAt = finishedAt - (step.durationMillis ?: 0L)

    publish(
      StartEventImpl(
        /* eventId = */ nodeId,
        /* parentId = */ taskId,
        /* eventTime = */ stepStartedAt,
        /* message = */ step.message,
        /* hint = */ null,
        /* description = */ null,
      ),
    )
    step.diagnostics.forEach { publishDiagnostic(it, nodeId) }
    publish(
      FinishEventImpl(
        /* eventId = */ nodeId,
        /* parentId = */ taskId,
        /* eventTime = */ finishedAt,
        /* message = */ step.message,
        // the tree derives the duration from the times above; a reason ("Up to date") is the CLI's own and has
        // nowhere else to go
        /* hint = */ step.detail.takeIf { step.durationMillis == null },
        /* description = */ null,
        /* result = */ result(step),
      ),
    )
  }

  private fun result(step: ElideBuildStep): EventResult = when (step.status) {
    ElideBuildStepStatus.SUCCEEDED -> SuccessResultImpl()
    ElideBuildStepStatus.SKIPPED -> SkippedResultImpl()
    // deliberately without the step's diagnostics as failures: the tree adds a child node per failure of a result,
    // and they are already published as message events, which are the navigable ones and the ones the error count
    // in the build's own summary is taken from
    ElideBuildStepStatus.FAILED -> FailureResultImpl()
  }

  /**
   * Publishes [diagnostic] as a node under [parentId], with the block the CLI printed as that node's console.
   *
   * The node itself is a plain message event, even for a diagnostic that names a file: the tree draws a file event
   * as a node per file, named by the path, with the message underneath, which buries the one line of the pair worth
   * reading. A message event carrying the same navigatable keeps the double click and "jump to source" of the file
   * form while leaving the node to read as what the tool said.
   *
   * The block goes to the console as output rather than as the node's description, because a message event's
   * description is printed as an error whatever its severity and without decoding the colour in it, while output is
   * drawn in the colour its escapes ask for. The step that produced the diagnostic gets a copy, so selecting it
   * shows everything its tools reported; the run's root node does not, since its console is the CLI's own log,
   * which already holds every line of this.
   */
  private fun publishDiagnostic(diagnostic: ElideBuildDiagnostic, parentId: Any) {
    val messageId = Any()
    val time = diagnostic.elapsedMillis?.let { startedAt + it } ?: System.currentTimeMillis()

    publish(message(diagnostic, messageId, parentId, time))

    val rendered = render(diagnostic)
    publish(output(rendered, messageId, time))
    if (parentId !== taskId) publish(output(rendered, parentId, time))
  }

  /** Builds the node for [diagnostic], navigable when it named a source position the IDE can resolve. */
  private fun message(diagnostic: ElideBuildDiagnostic, messageId: Any, parentId: Any, time: Long): BuildEvent {
    val kind = when (diagnostic.severity) {
      ElideBuildSeverity.ERROR -> MessageEvent.Kind.ERROR
      ElideBuildSeverity.WARNING -> MessageEvent.Kind.WARNING
      ElideBuildSeverity.INFO -> MessageEvent.Kind.INFO
    }

    return ElideDiagnosticEvent(
      /* eventId = */ messageId,
      /* parentId = */ parentId,
      /* eventTime = */ time,
      /* message = */ diagnostic.message,
      // the file and line the diagnostic names, without the directories leading to it: the node says which of the
      // files a step compiled this is, and the console beside it carries the path in full
      /* hint = */ hint(diagnostic),
      /* kind = */ kind,
      /* group = */ diagnostic.tool ?: Constants.Strings["execution.build.diagnosticGroup"],
      /* position = */ filePosition(diagnostic),
    )
  }

  /** Wraps [text] as console output of the node [parentId] stands for. */
  private fun output(text: String, parentId: Any, time: Long): BuildEvent = OutputBuildEventImpl(
    /* eventId = */ Any(),
    /* parentId = */ parentId,
    /* eventTime = */ time,
    /* message = */ text,
    /* hint = */ null,
    /* description = */ null,
    // the CLI's log is not the output of a program that went wrong, and the colour in the block below is the only
    // colour it should be drawn in
    /* outputType = */ ProcessOutputType.STDOUT,
  )

  /**
   * Renders [diagnostic] the way the CLI drew it, coloured for the console: the position it named, the message, and
   * the source excerpt with its gutter dimmed and the line it points at picked out in the severity's own colour.
   *
   * The position is written relative to the project, which is shorter than the absolute path the CLI prints for
   * some tools and is the form the rest of the IDE names a file in.
   */
  private fun render(diagnostic: ElideBuildDiagnostic): String = buildString {
    val severity = when (diagnostic.severity) {
      ElideBuildSeverity.ERROR -> RED
      ElideBuildSeverity.WARNING -> YELLOW
      ElideBuildSeverity.INFO -> null
    }

    location(diagnostic)?.let { appendLine(GREY(it)) }
    appendLine(severity?.invoke(diagnostic.message) ?: diagnostic.message)
    diagnostic.notes.forEach(::appendLine)

    if (diagnostic.excerpt.isEmpty()) return@buildString

    appendLine()
    diagnostic.excerpt.forEach { line ->
      // `  6 │ fun main() {`, or `→ 8 │   boom()` for the line the diagnostic is about: the gutter is the CLI's own
      // frame around the source, and the arrow is how it marks that line
      val gutter = line.indexOf(GUTTER)
      if (gutter < 0) {
        appendLine(line)
        return@forEach
      }

      val marked = line.substringBefore(GUTTER).contains(MARKER)
      val code = line.substring(gutter + 1)

      appendLine(GREY(line.take(gutter + 1)) + (severity?.takeIf { marked }?.invoke(code) ?: code))
    }
  }

  /** The position [diagnostic] named, relative to the project when it lies inside it, or `null` when it named none. */
  private fun location(diagnostic: ElideBuildDiagnostic): String? {
    val path = resolve(diagnostic.file ?: return null) ?: return null
    val real = runCatching { path.toRealPath() }.getOrDefault(path)
    val relative = runCatching { projectRoot.relativize(real) }.getOrNull()
    val display = relative?.takeUnless { it.startsWith("..") } ?: path

    // `/` whatever the platform's own separator is, which is the form the build tree names a file in everywhere else
    return listOfNotNull(
      display.invariantSeparatorsPathString,
      diagnostic.line?.toString(),
      diagnostic.column?.toString(),
    ).joinToString(":")
  }

  /** The file and line [diagnostic] named, without the path to it, or `null` when it named no file. */
  private fun hint(diagnostic: ElideBuildDiagnostic): String? {
    val name = resolve(diagnostic.file ?: return null)?.fileName?.toString() ?: return null

    return listOfNotNull(name, diagnostic.line?.toString()).joinToString(":")
  }

  /**
   * Resolves the source position [diagnostic] names, or `null` when it named no file.
   *
   * Paths are printed relative to the directory the CLI ran in for some tools and absolute for others, and the line
   * and column the CLI counts from one, while [FilePosition] counts from zero. A diagnostic about a file as a whole
   * names no line, and opens it at the top.
   */
  private fun filePosition(diagnostic: ElideBuildDiagnostic): FilePosition? {
    val path = resolve(diagnostic.file ?: return null) ?: return null

    return FilePosition(path.toFile(), (diagnostic.line ?: 1) - 1, (diagnostic.column ?: 1) - 1)
  }

  /** Resolves [file] as the CLI wrote it — relative to the directory it ran in, or absolute — or `null` if invalid. */
  private fun resolve(file: String): Path? = try {
    projectPath.resolve(file).normalize()
  } catch (_: InvalidPathException) {
    null
  }

  private companion object {
    /** Column the CLI frames a source excerpt with, which separates its line numbers from the source. */
    private const val GUTTER = '\u2502'

    /** Arrow the CLI marks the line a diagnostic is about with. */
    private const val MARKER = '\u2192'

    /** Wraps text in an ANSI colour the console's own scheme resolves, so the block follows the IDE's theme. */
    private fun color(code: Int): (String) -> String = { "\u001B[${code}m$it\u001B[0m" }

    private val RED = color(31)
    private val YELLOW = color(33)
    private val GREY = color(90)
  }
}

/**
 * A diagnostic node of the build tree, navigable when the CLI named a source position for it.
 *
 * The platform's own event of this shape is `FileMessageEventImpl`, which navigates exactly this way. It is
 * deliberately not used: the tree renders a file event as a node per file, named by the path to it and holding the
 * message underneath, which puts the path where the message belongs and buries the message a level down.
 *
 * The description stays empty, because the console beside the node is written as output instead: a description is
 * printed as an error whatever the node's severity, and with the colour in it left as text.
 *
 * @param position Source position the node opens, or `null` for a diagnostic that named no file.
 */
private class ElideDiagnosticEvent(
  eventId: Any,
  parentId: Any,
  eventTime: Long,
  message: String,
  hint: String?,
  kind: MessageEvent.Kind,
  group: String,
  private val position: FilePosition?,
) : MessageEventImpl(eventId, parentId, eventTime, message, hint, null, kind, group, null) {
  override fun getNavigatable(project: Project): Navigatable? = position?.let { FileNavigatable(project, it) }
}
