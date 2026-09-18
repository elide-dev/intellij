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
package dev.elide.intellij.project.model

import com.intellij.openapi.util.SystemInfo
import dev.elide.intellij.Constants
import java.nio.file.Path

/**
 * Where `elide build` leaves the output of a Native Image artifact, and what else it writes next to it.
 *
 * The CLI names the image after the artifact's `name`, falling back to the project's `name` and finally to the
 * directory the manifest lives in; the Native Image driver appends the platform's executable extension. None of that
 * is reported by `elide build --inspect`, so the path is reconstructed here from the manifest facts the sync already
 * collected.
 */
object ElideNativeImages {
  /** Name of the directory under `.dev/artifacts` holding the images of a project. */
  const val OUTPUT_DIR = "native-image"

  /** Name of the directory next to an image holding the sources GraalVM emitted for it with `-g`. */
  const val SOURCES_DIR = "sources"

  /** Name of the GDB pretty-printer script GraalVM emits next to an image built with `-g`. */
  const val GDB_HELPERS = "gdb-debughelpers.py"

  /**
   * Returns the path of the binary an image artifact produces in the project rooted at [root].
   *
   * [outputName] is the artifact's declared name and [projectName] the manifest's; both are blank-tolerant, matching
   * the CLI, which discards empty names before falling back to the project directory's own name.
   */
  @JvmStatic fun binary(
    root: Path,
    outputName: String? = null,
    projectName: String? = null,
    windows: Boolean = SystemInfo.isWindows,
  ): Path {
    val image = outputName?.takeUnless { it.isBlank() }
      ?: projectName?.takeUnless { it.isBlank() }
      ?: root.fileName.toString()

    return outputs(root).resolve(if (windows) "$image.exe" else image)
  }

  /** Returns the directory holding every image of the project rooted at [root]. */
  @JvmStatic fun outputs(root: Path): Path {
    return root.resolve(Constants.OUTPUT_DIR).resolve(Constants.ARTIFACTS_DIR).resolve(OUTPUT_DIR)
  }

  /** Returns the source tree GraalVM emitted for [binary], which GDB needs on its source path. */
  @JvmStatic fun sources(binary: Path): Path = binary.resolveSibling(SOURCES_DIR)

  /** Returns the GDB pretty-printer script GraalVM emitted for [binary]. */
  @JvmStatic fun gdbHelpers(binary: Path): Path = binary.resolveSibling(GDB_HELPERS)
}
