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

import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.LazyFileHyperlinkInfo
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTask
import com.intellij.openapi.externalSystem.service.internal.ExternalSystemExecuteTaskTask
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Turns the source locations an Elide run prints into links the console opens the file from.
 *
 * Every console of the run is filtered with this: the log the CLI writes, where a location arrives in whichever
 * form the tool that reported it uses, and the console of a diagnostic's node, where the position is written
 * relative to the project. Both then behave the way the rest of the IDE does, where a file and line in console
 * output is something to click.
 *
 * A location only becomes a link once it names a file that exists, which is what keeps a stack frame, a URL or any
 * other text that reads like a path from being underlined for a file that is not there.
 *
 * Dumb aware: matching a path needs no index, and a filter that is not would be skipped for every line printed
 * while the IDE is indexing — which is most of a build that follows a sync.
 *
 * @param project Project whose editors the links open in.
 * @param workDir Directory the CLI ran in, which the paths it printed relative are resolved against.
 */
internal class ElideSourceLinkFilter(
  private val project: Project,
  private val workDir: Path,
) : Filter, DumbAware {
  override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
    // the offset the console counts links from is the end of everything printed so far, and the line it hands over
    // is the last of it
    val lineStart = entireLength - line.length

    val items = ElideSourceLocations.findAll(line).mapNotNull { location ->
      val file = resolve(location.path) ?: return@mapNotNull null

      Filter.ResultItem(
        /* highlightStartOffset = */ lineStart + location.range.first,
        /* highlightEndOffset = */ lineStart + location.range.last + 1,
        // the file is opened when the link is followed rather than looked up here: this runs off the event thread,
        // on every line the consoles of a run print, and the file may not be in the IDE's own view of the disk yet
        /* hyperlinkInfo = */ LazyFileHyperlinkInfo(
          project,
          file.toString(),
          (location.line ?: 1) - 1,
          (location.column ?: 1) - 1,
        ),
      )
    }.toList()

    return items.ifEmpty { null }?.let(Filter::Result)
  }

  /** The file [path] names, or `null` when it names none of this project's: only a real file becomes a link. */
  private fun resolve(path: String): Path? = try {
    workDir.resolve(path).normalize().takeIf(Files::isRegularFile)
  } catch (_: InvalidPathException) {
    null
  }
}

/**
 * Console filters for a run of [task], which are the links its output's source locations become.
 *
 * The paths the CLI prints relative are relative to the directory it ran in, which is the project the task belongs
 * to; a task that names no directory gets no filter, since there would be nothing to resolve them against.
 */
internal fun elideSourceFilters(project: Project, task: ExternalSystemTask): Array<Filter> {
  val workDir = (task as? ExternalSystemExecuteTaskTask)?.externalProjectPath ?: return Filter.EMPTY_ARRAY
  val path = try {
    Path.of(workDir)
  } catch (_: InvalidPathException) {
    return Filter.EMPTY_ARRAY
  }

  return arrayOf(ElideSourceLinkFilter(project, path))
}
