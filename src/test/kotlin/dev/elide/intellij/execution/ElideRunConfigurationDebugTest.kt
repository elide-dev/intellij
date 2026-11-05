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

import dev.elide.intellij.project.model.ElideBuildTaskInfo
import dev.elide.intellij.project.model.ElideEntrypointInfo.Kind
import dev.elide.intellij.ui.ElideDebugTaskAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
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

  @Test fun `debugger flag lands after the test command rather than before it`() {
    // `test` declares the flag itself; in leading position the root command parses it, and the test run is left
    // with no debugger at all while the IDE waits for a JDWP server that never opens
    assertEquals(
      listOf("test", "--debugger", "-t", "MyTest"),
      ElideRunConfiguration.debuggerCommandLine(listOf("test", "-t", "MyTest")),
    )
    assertEquals(
      listOf("-p", "./app", "test", "--debugger", "src/api"),
      ElideRunConfiguration.debuggerCommandLine(listOf("-p", "./app", "test", "src/api")),
    )
  }

  @Test fun `a command line naming no command is debugged in leading position`() {
    // no command token means the implicit root `run`, which takes the flag before the entrypoint
    assertEquals(
      listOf("--debugger", "src/main/kotlin/Main.kt"),
      ElideRunConfiguration.debuggerCommandLine(listOf("src/main/kotlin/Main.kt")),
    )
  }

  @Test fun `debugger flag is not repeated`() {
    val commandLine = listOf("run", "--debugger", "hello")

    assertEquals(commandLine, ElideRunConfiguration.debuggerCommandLine(commandLine))
  }

  @Test fun `a debugged test run is still rendered as a test tree`() {
    val commandLine = ElideRunConfiguration.debugCommandLine(listOf("test", "-t", "MyTest"))

    assertEquals(listOf("test", "--debugger", "--reporter=tap", "-t", "MyTest"), commandLine)
    assertTrue(ElideRunConfiguration.emitsTap(commandLine))
  }

  @Test fun `a debugged run keeps the command line the user typed`() {
    // only the debugger flag is added: coverage belongs to the coverage executor's own runner, and there is no
    // reporter to select for a command that is not a test run
    assertEquals(
      listOf("run", "--debugger", "src/main/kotlin/Main.kt"),
      ElideRunConfiguration.debugCommandLine(listOf("run", "src/main/kotlin/Main.kt")),
    )
  }

  @Test fun `run test and explicitly targeted build commands are debuggable`() {
    assertTrue(ElideRunConfiguration.supportsDebugger(listOf("--verbose", "run"), Kind.JvmMainClass, "app.MainKt"))
    // a global flag's value is not a command name
    assertTrue(ElideRunConfiguration.supportsDebugger(listOf("-p", "./app", "run"), Kind.JvmMainClass, "app.MainKt"))
    assertFalse(ElideRunConfiguration.supportsDebugger(listOf("build"), null, null))
    assertFalse(ElideRunConfiguration.supportsDebugger(listOf("install", "--with=sources"), null, null))
    // `fmt` is an alias of `format`, not of `run`
    assertFalse(ElideRunConfiguration.supportsDebugger(listOf("fmt"), null, null))
    assertFalse(ElideRunConfiguration.supportsDebugger(emptyList(), null, null))
  }

  @Test fun `only jvm entrypoints are debuggable`() {
    // manifest scripts are shell commands, and guest languages get a CDP/DAP debugger the IDE cannot attach to
    assertFalse(ElideRunConfiguration.supportsDebugger(listOf("run", "hello"), Kind.Script, "hello"))
    assertFalse(ElideRunConfiguration.supportsDebugger(listOf("run", "src/main.js"), Kind.Generic, "src/main.js"))
    assertTrue(ElideRunConfiguration.supportsDebugger(listOf("run", "src/Main.kt"), Kind.Generic, "src/Main.kt"))

    // hand-written command lines carry no entrypoint metadata: the CLI resolves the manifest entrypoint
    assertTrue(ElideRunConfiguration.supportsDebugger(listOf("run"), null, null))
  }

  @Test fun `jvm test runs are debuggable`() {
    assertTrue(ElideRunConfiguration.supportsDebugger(listOf("test", "-t", "x"), Kind.JvmTest, "a.B#c"))
    assertTrue(ElideRunConfiguration.supportsDebugger(listOf("test"), null, null))

    // Explicit build targets are resolved by the build graph, independently of run entrypoint metadata.
    assertTrue(ElideRunConfiguration.supportsDebugger(listOf("build", "test"), Kind.JvmTest, "a.B#c"))
  }

  @Test fun `build debugging requires a target rather than an option value or passthrough argument`() {
    for (args in listOf(
      listOf("build"),
      listOf("build", "--project", "app"),
      listOf("build", "-f", "run"),
      listOf("build", "--workers=2", "--", "run"),
    )) {
      assertFalse(ElideRunConfiguration.supportsDebugger(args, null, null))
      assertFailsWith<IllegalArgumentException> { ElideRunConfiguration.debugCommandLine(args) }
    }
  }

  @Test fun `build debugger options follow explicit targets and precede passthrough`() {
    assertEquals(
      listOf("build", "run", "jvm-test", "--debugger", "--", "guest-argument"),
      ElideRunConfiguration.debugCommandLine(listOf("build", "run", "jvm-test", "--", "guest-argument")),
    )
    assertEquals(
      listOf("build", "core:run", "--debugger"),
      ElideRunConfiguration.debugCommandLine(listOf(":core:run")),
    )
    assertTrue(ElideRunConfiguration.supportsDebugger(listOf(":core:run"), null, null))
    val addressed = listOf("build", "run", "--debugger=127.0.0.1:5023")
    assertEquals(addressed, ElideRunConfiguration.debugCommandLine(addressed))
  }

  @Test fun `a debugger flag belonging to the guest does not suppress the agent`() {
    assertEquals(
      listOf("run", "--debugger", "--", "--debugger"),
      ElideRunConfiguration.debugCommandLine(listOf("run", "--", "--debugger")),
    )
    assertEquals(
      listOf("run", "--debugger", "--snippet", "--debugger"),
      ElideRunConfiguration.debugCommandLine(listOf("run", "--snippet", "--debugger")),
    )
  }

  @Test fun `android debug targets retain their target-owned debugger invocation`() {
    val command = listOf("build", "app:android-debug-apk", "--device=emulator-5554")
    assertTrue(ElideRunConfiguration.supportsDebugger(command, null, null))
    assertEquals(command, ElideRunConfiguration.debugCommandLine(command))
  }

  @Test fun `task debug capability uses exact workspace names and advertised options`() {
    val tasks = listOf(
      ElideBuildTaskInfo("core:run", options = listOf(ElideBuildTaskInfo.Option("--debugger"))),
      ElideBuildTaskInfo("other:run"),
      ElideBuildTaskInfo("app:android-debug-apk"),
    )
    assertTrue(ElideDebugTaskAction.supportsTask(":core:run", tasks))
    assertTrue(ElideDebugTaskAction.supportsTask(":app:android-debug-apk", tasks))
    assertFalse(ElideDebugTaskAction.supportsTask(":other:run", tasks))
    assertFalse(ElideDebugTaskAction.supportsTask(":run", tasks))
    assertFalse(ElideDebugTaskAction.supportsTask(":unknown", tasks))
  }
}
