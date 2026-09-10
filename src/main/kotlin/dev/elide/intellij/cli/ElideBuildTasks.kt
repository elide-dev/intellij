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
package dev.elide.intellij.cli

import dev.elide.intellij.project.model.ElideBuildTaskInfo

/**
 * Decoding for the build task listing a distribution prints for a project.
 *
 * `elide build --inspect` has no machine-readable form, so its table is parsed here. The listing is a section of
 * two-space indented `name   description` rows, opened by a `N tasks available:` header and closed by the next
 * unindented line (`Global options:`); every task row is followed by four-space indented rows naming the options
 * that task accepts, or by a `no options declared` row, which declares none.
 */
object ElideBuildTasks {
  /** Header opening the task table; a listing that does not carry it holds no tasks to read. */
  private val HEADER = Regex("""^\d+ tasks? available:$""")

  /** A task row: exactly two spaces of indent, the task name, then its description, if it declares one. */
  private val TASK = Regex("""^ {2}(\S+)(?: {2,}(.*))?$""")

  /**
   * An option row of the task above it: four spaces of indent and an option, dashes included.
   *
   * The leading dash is what separates an option from the `no options declared` row that stands in for an empty
   * option list.
   */
  private val OPTION = Regex("""^ {4}(-\S+)(?: {2,}(.*))?$""")

  /** Parse the tasks, and the options each of them declares, out of the `output` of `elide build --inspect`. */
  @JvmStatic fun parse(output: String): List<ElideBuildTaskInfo> {
    val tasks = mutableListOf<ElideBuildTaskInfo>()

    val rows = output.lineSequence()
      .map { it.trimEnd() }
      .dropWhile { !HEADER.matches(it) }
      .drop(1)
      .takeWhile { it.isEmpty() || it.startsWith(" ") }

    for (row in rows) {
      val task = TASK.matchEntire(row)
      if (task != null) {
        tasks += ElideBuildTaskInfo(name = task.groupValues[1], description = task.groupValues[2].trim())
        continue
      }

      // an option row belongs to the task above it, so a listing that opens with one is malformed and skipped
      val option = OPTION.matchEntire(row) ?: continue
      val declaring = tasks.removeLastOrNull() ?: continue

      tasks += declaring.copy(
        options = declaring.options + ElideBuildTaskInfo.Option(
          option = option.groupValues[1],
          description = option.groupValues[2].trim(),
        ),
      )
    }

    return tasks
  }
}
