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
package dev.elide.intellij.execution

import dev.elide.intellij.project.model.ElideEntrypointInfo.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pins the command line and the entrypoints the IDE's "Debug" action applies to. */
class ElideRunConfigurationDebugTest {
  @Test fun `debugger flag precedes the entrypoint and its arguments`() {
    // the flag belongs to `run`, so it must land before the entrypoint and before the `--` separator, where the CLI
    // would hand it to the guest program instead
    assertEquals(
      listOf("run", "--debugger", "src/main/kotlin/Main.kt", "--", "--verbose"),
      ElideRunConfiguration.debuggerCommandLine(listOf("run", "src/main/kotlin/Main.kt", "--", "--verbose")),
    )
    assertEquals(
      listOf("run", "--debugger"),
      ElideRunConfiguration.debuggerCommandLine(listOf("run")),
    )
  }

  @Test fun `debugger flag is not repeated`() {
    val commandLine = listOf("run", "--debugger", "hello")

    assertEquals(commandLine, ElideRunConfiguration.debuggerCommandLine(commandLine))
  }

  @Test fun `only run commands are debuggable`() {
    assertTrue(ElideRunConfiguration.supportsDebugger(listOf("--verbose", "run"), Kind.JvmMainClass, "app.MainKt"))
    assertFalse(ElideRunConfiguration.supportsDebugger(listOf("build"), null, null))
    assertFalse(ElideRunConfiguration.supportsDebugger(listOf("install", "--with=sources"), null, null))
    assertFalse(ElideRunConfiguration.supportsDebugger(emptyList(), null, null))
  }

  @Test fun `only jvm entrypoints are debuggable`() {
    // manifest scripts are shell commands, and guest languages get a CDP/DAP debugger the IDE cannot attach to
    assertFalse(ElideRunConfiguration.supportsDebugger(listOf("run", "hello"), Kind.Script, "hello"))
    assertFalse(ElideRunConfiguration.supportsDebugger(listOf("run", "src/main.js"), Kind.Generic, "src/main.js"))
    assertTrue(ElideRunConfiguration.supportsDebugger(listOf("run", "src/Main.kt"), Kind.Generic, "src/Main.kt"))

    // hand-written command lines carry no entrypoint metadata: the CLI resolves the manifest entrypoint
    assertTrue(ElideRunConfiguration.supportsDebugger(listOf("run"), null, null))

    // `elide test` does not yet support --debugger
    assertFalse(ElideRunConfiguration.supportsDebugger(listOf("test", "-t", "x"), Kind.JvmTest, "a.B#c"))
  }
}
