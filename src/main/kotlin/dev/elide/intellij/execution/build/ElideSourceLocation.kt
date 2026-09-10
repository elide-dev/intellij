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

import java.net.URI
import java.nio.file.Path

/**
 * A place in a source file, as one of the tools the CLI drives named it.
 *
 * @param path The file, as a path: relative to the directory the CLI ran in, or absolute.
 * @param line 1-based line number, or `null` when the location named the file alone.
 * @param column 1-based column number, or `null` when the location named none.
 * @param range Where in the text it was found, which is what the console hyperlinks and the parser cuts away.
 */
internal data class ElideSourceLocation(
  val path: String,
  val line: Int?,
  val column: Int?,
  val range: IntRange,
)

/**
 * Reader for the source locations the CLI's tools print, in each of the forms they use.
 *
 * `kotlinc` writes a URI and a position (`file:///home/me/app/src/App.kt:11:16`), `javac` an absolute path on its
 * own, and the CLI's own `In file:` line a path relative to the directory it ran in. A bare file name counts as a
 * location only when a line number follows it, so a qualified name — the class of an exception, say — is not read
 * as one.
 *
 * The same reader serves the parser, which cuts the location out of a message and reports it as a position, and
 * the console filter, which turns it back into a link.
 */
internal object ElideSourceLocations {
  /**
   * The location a message opens with, together with the separator before the rest of it.
   *
   * @return the location and the offset the message resumes at, or `null` when [text] does not open with one.
   */
  fun leading(text: String): Pair<ElideSourceLocation, Int>? {
    val match = LEADING.find(text) ?: return null

    return location(match) to match.range.last + 1
  }

  /** Every location [text] names, in the order they appear. */
  fun findAll(text: String): Sequence<ElideSourceLocation> = ANYWHERE.findAll(text).map(::location)

  private fun location(match: MatchResult): ElideSourceLocation {
    val path = checkNotNull(match.groups["path"]) { "location matched without a path: ${match.value}" }

    return ElideSourceLocation(
      path = filePath(path.value),
      line = match.groups["line"]?.value?.toIntOrNull(),
      column = match.groups["column"]?.value?.toIntOrNull(),
      range = path.range.first..match.range.last,
    )
  }

  /** [raw] as a filesystem path, which is what a tool that named a file by URI leaves to be undone. */
  private fun filePath(raw: String): String = when {
    raw.startsWith("file:") -> runCatching { Path.of(URI(raw)).toString() }.getOrDefault(raw)
    else -> raw
  }

  /**
   * A file named as a URI, as a path with a separator in it, or as a bare name a line number follows, with the
   * line and column that may come after it.
   *
   * A path holds no colon: that is what ends it and starts the position, in every form the tools print.
   */
  private const val LOCATION =
    """(?<path>file://[^\s:]*|[^\s:]+(?:/[^\s:]+)+|[^\s:]+\.[A-Za-z][A-Za-z0-9]*(?=:\d))""" +
      """(?::(?<line>\d+))?(?::(?<column>\d+))?"""

  /** A location a message opens with, and the colon or space that separates it from the message. */
  private val LEADING = Regex("^$LOCATION[:\\s]\\s*")

  /** A location anywhere in a line, which is what the console links. */
  private val ANYWHERE = Regex(LOCATION)
}
