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
package dev.elide.intellij.execution.nativeimage

import com.intellij.execution.configurations.GeneralCommandLine
import dev.elide.intellij.project.model.ElideNativeImages
import java.nio.file.Files
import java.nio.file.Path

/**
 * Everything a start of a Native Image binary needs, resolved from a configuration once the artifact has been built.
 *
 * Both the run and the debug path go through this: the run only needs the [commandLine], while a debug session also
 * needs what GraalVM wrote next to the image — the [sources] tree and the [gdbHelpers] script — to make a backend
 * session show anything but machine code.
 *
 * @param root Directory of the Elide project the image belongs to.
 * @param binary Path of the image `elide build` produced.
 * @param commandLine Command line starting [binary] with the configuration's arguments, directory and environment.
 */
data class ElideNativeImageLaunch(
  val root: Path,
  val binary: Path,
  val commandLine: GeneralCommandLine,
) {
  /** Sources GraalVM emitted for the image, which GDB resolves frames against. */
  val sources: Path get() = ElideNativeImages.sources(binary)

  /** Pretty-printer script GraalVM emitted for the image, which teaches GDB to render JVM values. */
  val gdbHelpers: Path get() = ElideNativeImages.gdbHelpers(binary)

  /**
   * Whether the image carries debug info.
   *
   * GraalVM writes the source tree exactly when it emitted debug info, which it only does with `-g` and, as of
   * GraalVM 25, only on Linux; without it a backend session still runs, but never leaves machine level.
   */
  val hasDebugInfo: Boolean get() = Files.isDirectory(sources)
}
