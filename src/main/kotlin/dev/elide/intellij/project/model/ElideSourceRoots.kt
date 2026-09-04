/*
 *  Copyright (c) 2024-2025 Elide Technologies, Inc.
 *
 *  Licensed under the MIT license (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at
 *
 *    https://opensource.org/license/mit/
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations under the License.
 */

package dev.elide.intellij.project.model

import java.nio.file.Path
import kotlin.io.path.absolutePathString
/** Returns `true` when [this] normalized path is the same as, or nested under, the normalized [other] path. */
internal fun String.isPathUnder(other: String): Boolean {
  if (this == other) return true
  if (other == "/") return startsWith("/")

  return length > other.length && this[other.length] == '/' && startsWith(other)
}

/**
 * Translates the path globs declared by manifest source sets into the content roots and source folders the IDE
 * project model expects.
 *
 * All comparisons are made on normalized absolute paths (forward slashes, no trailing separator) and respect path
 * boundaries, so `src/a` is never considered a parent of `src/ab`.
 */
internal object ElideSourceRoots {
  private val DRIVE_LETTER = Regex("""^[A-Za-z]:/""")

  /**
   * Group the given source [patterns] into content roots.
   *
   * The returned map is keyed by content root, with the set of source folders (the static prefix of each pattern)
   * contained by that root as values. Content roots never nest: a folder that would be covered by another candidate
   * root is merged into it, and the project root acts as the upper bound for the search.
   */
  fun collect(projectRoot: Path, patterns: List<String>): Map<String, Set<String>> {
    if (patterns.isEmpty()) return emptyMap()

    val root = normalize(projectRoot.absolutePathString())
    val sourceFolders = patterns.map { staticPrefix(absolutePath(it, projectRoot)) }.distinct()

    // the natural content root for a source folder is its parent directory, clamped to the project root
    val candidates = sourceFolders.map { contentRootFor(it, root) }.distinct()

    // a content root may not contain another content root: keep only the outermost candidates
    val contentRoots = candidates.filter { candidate ->
      candidates.none { other -> other != candidate && candidate.isPathUnder(other) }
    }

    return buildMap<String, MutableSet<String>> {
      for (folder in sourceFolders) {
        // attach the folder to the innermost content root containing it
        val owner = contentRoots.filter { folder.isPathUnder(it) }.maxByOrNull { it.length }
          ?: contentRootFor(folder, root)

        getOrPut(owner) { linkedSetOf() }.add(folder)
      }
    }
  }

  /** Returns the longest directory prefix of [pattern] that contains no glob wildcards. */
  fun staticPrefix(pattern: String): String {
    val normalized = normalize(pattern)

    val wildcardIndex = normalized.indexOfFirst { it == '*' || it == '?' || it == '[' || it == '{' }
    if (wildcardIndex == -1) return normalized

    val prefix = normalized.substring(0, wildcardIndex)
    val lastSlash = prefix.lastIndexOf('/')

    return if (lastSlash > 0) prefix.substring(0, lastSlash) else "/"
  }

  /**
   * Resolve [pattern] against [projectRoot] unless it is already absolute.
   *
   * POSIX paths, Windows drive letters (`C:\…`) and UNC paths (`\\host\share`) all count as absolute; treating only
   * `/`-prefixed paths as absolute would rewrite Windows paths into nonsense.
   */
  fun absolutePath(pattern: String, projectRoot: Path): String {
    val normalized = normalize(pattern)
    if (isAbsolute(normalized)) return normalized

    val root = normalize(projectRoot.absolutePathString())
    return "$root/${normalized.removePrefix("./")}"
  }

  private fun isAbsolute(path: String): Boolean {
    return path.startsWith('/') || DRIVE_LETTER.containsMatchIn(path)
  }

  private fun contentRootFor(sourceFolder: String, projectRoot: String): String {
    if (sourceFolder == projectRoot || !sourceFolder.isPathUnder(projectRoot)) return sourceFolder

    val parent = sourceFolder.substringBeforeLast('/', missingDelimiterValue = "")

    // a top-level source folder owns itself: taking the project root as a module content root would enclose every
    // other module's roots (and `.dev`/`.idea`), which the IDE does not allow
    if (parent.isEmpty() || parent == projectRoot || !parent.isPathUnder(projectRoot)) return sourceFolder

    return parent
  }

  private fun normalize(path: String): String {
    val forwardSlashes = path.trim().replace('\\', '/')
    return if (forwardSlashes.length > 1) forwardSlashes.trimEnd('/') else forwardSlashes
  }
}
