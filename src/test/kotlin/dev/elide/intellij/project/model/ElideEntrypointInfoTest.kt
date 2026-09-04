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

/** Pins the entrypoint naming and command lines used by run configurations and completion. */
class ElideEntrypointInfoTest {
  @Test fun `script entrypoints run by name`() {
    val script = ElideEntrypointInfo.script("hello")

    assertEquals(ElideEntrypointInfo.Kind.Script, script.kind)
    assertEquals("hello", script.displayName)
    assertEquals("hello", script.value)
    assertEquals("run hello", script.fullCommandLine)
  }

  @Test fun `jvm entrypoints run the project default`() {
    val main = ElideEntrypointInfo.jvmMain("fixture.MainKt")

    assertEquals(ElideEntrypointInfo.Kind.JvmMainClass, main.kind)
    assertEquals("MainKt", main.displayName)
    assertEquals("fixture.MainKt", main.value)
    assertEquals("run", main.fullCommandLine)
  }

  @Test fun `generic entrypoints run their path`() {
    val generic = ElideEntrypointInfo.generic("src/main/kotlin/Main.kt")

    assertEquals(ElideEntrypointInfo.Kind.Generic, generic.kind)
    assertEquals("Main.kt", generic.displayName)
    assertEquals("run src/main/kotlin/Main.kt", generic.fullCommandLine)
  }

  @Test fun `entrypoint paths with spaces stay one argument`() {
    val generic = ElideEntrypointInfo.generic("src/my scripts/main.kt")

    assertEquals("""run "src/my scripts/main.kt"""", generic.fullCommandLine)
  }

  @Test fun `the jvm main is only offered when a bare run reaches it`() {
    // regression: the CLI resolves the manifest's `entrypoint` before `jvm.main`, so with both declared the bare
    // `run` command line of a JVM main entry would have started the other program
    val shadowed = ElideProjectInfo.from(
      ElideProjectData(entrypoints = listOf("src/main.js"), jvmMainClass = "fixture.MainKt"),
    )

    assertEquals(listOf("run src/main.js"), shadowed.entrypoints.map { it.fullCommandLine })

    val jvmOnly = ElideProjectInfo.from(ElideProjectData(jvmMainClass = "fixture.MainKt"))

    assertEquals(listOf("run"), jvmOnly.entrypoints.map { it.fullCommandLine })
  }

  @Test fun `scripts are offered ahead of the project entrypoint`() {
    val info = ElideProjectInfo.from(
      ElideProjectData(entrypoints = listOf("src/main.js"), scripts = listOf("hello")),
    )

    assertEquals(listOf("run hello", "run src/main.js"), info.entrypoints.map { it.fullCommandLine })
  }

  @Test fun `kinds are matched by name for tolerant deserialization`() {
    // regression: `Kind.valueOf` threw for configurations written by a newer plugin version
    assertEquals(ElideEntrypointInfo.Kind.Script, ElideEntrypointInfo.Kind.entries.find { it.name == "Script" })
    assertEquals(null, ElideEntrypointInfo.Kind.entries.find { it.name == "SomeFutureKind" })
  }
}
