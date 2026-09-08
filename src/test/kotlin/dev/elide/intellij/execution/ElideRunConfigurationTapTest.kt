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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins which runs the IDE renders as a test tree, and the command line it runs them with.
 *
 * `ElideTestsExecutionConsoleManager` claims a run exactly when [ElideRunConfiguration.emitsTap] answers for its
 * argument vector, so the two have to agree: a run claimed without TAP on standard output shows an empty tree, and
 * an unclaimed TAP run dumps the raw stream into the console.
 */
class ElideRunConfigurationTapTest {
  @Test fun `the reporter flag lands right after the test command`() {
    // the flag belongs to `test`, and a path narrowing the run is a bare token: the flag must precede neither the
    // command nor the `--` separator
    assertEquals(
      listOf("test", "--reporter=tap", "src/api", "--", "--verbose"),
      ElideRunConfiguration.tapCommandLine(listOf("test", "src/api", "--", "--verbose")),
    )
    assertEquals(
      listOf("-p", "./app", "test", "--reporter=tap", "-t", "MyTest"),
      ElideRunConfiguration.tapCommandLine(listOf("-p", "./app", "test", "-t", "MyTest")),
    )
  }

  @Test fun `only test runs are taken over`() {
    assertNull(ElideRunConfiguration.tapCommandLine(listOf("run", "src/main.kt")))
    assertNull(ElideRunConfiguration.tapCommandLine(listOf("build", "test")))
    assertNull(ElideRunConfiguration.tapCommandLine(emptyList()))

    assertFalse(ElideRunConfiguration.emitsTap(listOf("run")))
    assertFalse(ElideRunConfiguration.emitsTap(listOf("build", "test")))
  }

  @Test fun `a reporter the user chose is left alone`() {
    // both forms the CLI accepts, and both directions: another reporter keeps the CLI's own output, and `tap` typed
    // by hand reaches the test tree without being added twice
    assertNull(ElideRunConfiguration.tapCommandLine(listOf("test", "--reporter=junit")))
    assertNull(ElideRunConfiguration.tapCommandLine(listOf("test", "--reporter", "junit")))
    assertNull(ElideRunConfiguration.tapCommandLine(listOf("test", "--reporter=tap")))

    assertFalse(ElideRunConfiguration.emitsTap(listOf("test", "--reporter=junit")))
    assertFalse(ElideRunConfiguration.emitsTap(listOf("test", "--reporter", "console")))
    assertTrue(ElideRunConfiguration.emitsTap(listOf("test", "--reporter", "tap")))
  }

  @Test fun `a reporter with no value is still one the user typed`() {
    // the CLI rejects both of these, but the flag is on the line: injecting a second one would show the user a
    // `--reporter` twice over, and neither run streams TAP for the console manager to claim
    assertNull(ElideRunConfiguration.tapCommandLine(listOf("test", "--reporter")))
    assertNull(ElideRunConfiguration.tapCommandLine(listOf("test", "--reporter=")))

    assertFalse(ElideRunConfiguration.emitsTap(listOf("test", "--reporter")))
    assertFalse(ElideRunConfiguration.emitsTap(listOf("test", "--reporter=")))
  }

  @Test fun `a reporter meant for the test runner is not read as the CLI's own`() {
    // past `--` the CLI forwards arguments to the test runner, so the run still uses the default console reporter:
    // claiming it would show an empty tree and swallow the console output the Build window would otherwise get
    assertFalse(ElideRunConfiguration.emitsTap(listOf("test", "--", "--reporter=tap")))
    // and the run is still one the IDE takes over, with the flag inserted where the CLI reads it
    assertEquals(
      listOf("test", "--reporter=tap", "--", "--reporter=junit"),
      ElideRunConfiguration.tapCommandLine(listOf("test", "--", "--reporter=junit")),
    )
  }

  @Test fun `a reporter standing as another flag's value is not read as the reporter`() {
    // `-t` consumes the following token, so this run filters by a pattern and reports through the console
    assertFalse(ElideRunConfiguration.emitsTap(listOf("test", "-t", "--reporter=tap")))
    assertEquals(
      listOf("test", "--reporter=tap", "-t", "--reporter=tap"),
      ElideRunConfiguration.tapCommandLine(listOf("test", "-t", "--reporter=tap")),
    )
  }

  @Test fun `the injected command line is one the console manager claims`() {
    val commandLine = checkNotNull(ElideRunConfiguration.tapCommandLine(listOf("test", "-t", "MyTest")))

    assertTrue(ElideRunConfiguration.emitsTap(commandLine))
  }
}
