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

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers Maven coordinate recovery from classpath entries.
 *
 * The CLI prints absolute paths, so the repository root has to be found inside the entry rather than at its start.
 */
class ElideProjectModelTest {
  private val defaultRoot = ".dev/dependencies/m2/"

  @Test fun `parses coordinates from absolute repository paths`() {
    assertEquals(
      "com.google.guava:guava:32.1.3-jre",
      ElideProjectModel.parseLibraryName(
        "/projects/demo/.dev/dependencies/m2/com/google/guava/guava/32.1.3-jre/guava-32.1.3-jre.jar",
        defaultRoot,
      ),
    )
  }

  @Test fun `parses coordinates from relative repository paths`() {
    assertEquals(
      "org.slf4j:slf4j-api:2.0.13",
      ElideProjectModel.parseLibraryName(
        ".dev/dependencies/m2/org/slf4j/slf4j-api/2.0.13/slf4j-api-2.0.13.jar",
        defaultRoot,
      ),
    )
  }

  @Test fun `honors a custom local repository with or without a trailing slash`() {
    val entry = "/projects/demo/custom/m2/org/slf4j/slf4j-api/2.0.13/slf4j-api-2.0.13.jar"

    assertEquals("org.slf4j:slf4j-api:2.0.13", ElideProjectModel.parseLibraryName(entry, "custom/m2/"))
    assertEquals("org.slf4j:slf4j-api:2.0.13", ElideProjectModel.parseLibraryName(entry, "custom/m2"))
  }

  @Test fun `parses coordinates from windows style paths`() {
    assertEquals(
      "org.slf4j:slf4j-api:2.0.13",
      ElideProjectModel.parseLibraryName(
        """C:\projects\demo\.dev\dependencies\m2\org\slf4j\slf4j-api\2.0.13\slf4j-api-2.0.13.jar""",
        defaultRoot,
      ),
    )
  }

  @Test fun `falls back to the file name outside a repository layout`() {
    assertEquals("unknown:custom-lib:unknown", ElideProjectModel.parseLibraryName("/opt/libs/custom-lib.jar", defaultRoot))
    assertEquals("unknown:library:unknown", ElideProjectModel.generateFallbackLibraryName("/opt/libs/"))
  }

  @Test fun `falls back for malformed repository paths`() {
    // no group segment before the artifact
    assertEquals(
      "unknown:guava-32.1.3-jre:unknown",
      ElideProjectModel.parseLibraryName(".dev/dependencies/m2/guava/32.1.3-jre/guava-32.1.3-jre.jar", defaultRoot),
    )
  }
}
