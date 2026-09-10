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
import kotlin.test.assertNull

/**
 * Pins how a build task travels through the external system: the name it carries in the project model, and the
 * command line the task manager runs it with.
 */
class ElideBuildTaskInfoTest {
  @Test fun `tasks are named after their target`() {
    assertEquals(":app", ElideBuildTaskInfo("app").taskName)
    assertEquals("app", buildTargetName(":app"))
  }

  @Test fun `targets are built by name`() {
    assertEquals(listOf("build", "app"), buildCommandLine(listOf(":app")))
    assertEquals(
      listOf("build", "app", "compile-kotlin-main"),
      buildCommandLine(listOf(":app", ":compile-kotlin-main")),
    )
  }

  @Test fun `options behind the targets are passed through`() {
    // `elide build [TARGET...] [--OPTION...]`: a configuration created for a task of the tool window has to keep
    // working once options are typed into its command line
    assertEquals(
      listOf("build", "maven-dependencies", "--fresh"),
      buildCommandLine(listOf(":maven-dependencies", "--fresh")),
    )
  }

  @Test fun `command lines are passed through untouched`() {
    // the same settings carry the argument vector of a run configuration, which is an Elide invocation already: a
    // `build` in front of it would run `elide build run src/main.kt`
    assertNull(buildCommandLine(listOf("run", "src/main.kt")))
    assertNull(buildCommandLine(listOf("test", "--reporter=tap")))
    assertNull(buildCommandLine(listOf("build", "app")))
    assertNull(buildCommandLine(emptyList()))
  }

  @Test fun `a bare colon names no target`() {
    assertNull(buildTargetName(":"))
    assertNull(buildTargetName("app"))
    assertNull(buildCommandLine(listOf(":")))
  }
}
