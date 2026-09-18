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

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins where a Native Image build leaves its output.
 *
 * `elide build --inspect` reports neither the artifact's type nor the file it writes, so the plugin reconstructs the
 * path the CLI uses; a run that starts the wrong file is a run that reports a build as missing.
 */
class ElideNativeImagesTest {
  private val root = Path.of("/workspace/app")
  private val outputs = root.resolve(".dev/artifacts/native-image")

  @Test fun `the artifact name names the binary`() {
    assertEquals(
      outputs.resolve("custom-bin"),
      ElideNativeImages.binary(root, outputName = "custom-bin", projectName = "app-name", windows = false),
    )
  }

  @Test fun `an unnamed artifact falls back to the project name`() {
    assertEquals(
      outputs.resolve("app-name"),
      ElideNativeImages.binary(root, outputName = null, projectName = "app-name", windows = false),
    )
  }

  @Test fun `an unnamed project falls back to the project directory`() {
    assertEquals(
      outputs.resolve("app"),
      ElideNativeImages.binary(root, outputName = null, projectName = null, windows = false),
    )
  }

  @Test fun `blank names count as absent`() {
    // the CLI discards empty names before falling back, so a manifest declaring `name = ""` must not produce a
    // binary path ending in the artifacts directory itself
    assertEquals(
      outputs.resolve("app"),
      ElideNativeImages.binary(root, outputName = "  ", projectName = "", windows = false),
    )
  }

  @Test fun `windows images carry the executable extension`() {
    assertEquals(
      outputs.resolve("app-name.exe"),
      ElideNativeImages.binary(root, outputName = null, projectName = "app-name", windows = true),
    )
  }

  @Test fun `debug info sits next to the binary`() {
    val binary = ElideNativeImages.binary(root, outputName = "bin", windows = false)

    assertEquals(outputs.resolve("sources"), ElideNativeImages.sources(binary))
    assertEquals(outputs.resolve("gdb-debughelpers.py"), ElideNativeImages.gdbHelpers(binary))
  }
}
