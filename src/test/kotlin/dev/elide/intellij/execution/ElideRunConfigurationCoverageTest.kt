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
 * Pins the command line a run started under the coverage executor executes.
 *
 * The reports the IDE attaches are the ones the CLI writes, so a coverage run that does not reach the CLI with
 * `--coverage` attaches nothing at all, and one that loses `--reporter=tap` on the way trades the test tree for a
 * raw stream in the console.
 */
class ElideRunConfigurationCoverageTest {
  @Test fun `the coverage flag lands right after the test command`() {
    // the flag is global, but a path narrowing the run is a bare token and everything past `--` belongs to the test
    // runner: only the position right after the command is safe for both
    assertEquals(
      listOf("test", "--coverage", "src/api", "--", "--verbose"),
      ElideRunConfiguration.coverageCommandLine(listOf("test", "src/api", "--", "--verbose")),
    )
    assertEquals(
      listOf("-p", "./app", "test", "--coverage", "-t", "MyTest"),
      ElideRunConfiguration.coverageCommandLine(listOf("-p", "./app", "test", "-t", "MyTest")),
    )
  }

  @Test fun `a coverage flag the user typed is left alone`() {
    // `--coverage=false` turns collection off: a configuration written that way asks for a run without coverage,
    // and adding a second flag would leave the CLI with two readings of the same option
    for (typed in listOf("--coverage", "--coverage=auto", "--coverage=false")) {
      assertEquals(
        listOf("test", typed),
        ElideRunConfiguration.coverageCommandLine(listOf("test", typed)),
      )
    }
  }

  @Test fun `a coverage flag meant for the test runner is not read as the CLI's own`() {
    // past `--` the flag reaches the test runner, so the CLI itself still collects nothing
    assertEquals(
      listOf("test", "--coverage", "--", "--coverage"),
      ElideRunConfiguration.coverageCommandLine(listOf("test", "--", "--coverage")),
    )
  }

  @Test fun `only test runs can be run with coverage`() {
    // `elide run --coverage` prints a summary table and writes no report file, so there is nothing to attach
    assertTrue(ElideRunConfiguration.supportsCoverage(listOf("test", "-t", "MyTest")))
    assertFalse(ElideRunConfiguration.supportsCoverage(listOf("run", "src/main.kt")))
    assertFalse(ElideRunConfiguration.supportsCoverage(listOf("build", "test")))
    assertFalse(ElideRunConfiguration.supportsCoverage(emptyList()))
  }

  @Test fun `a coverage run is still rendered as a test tree`() {
    val commandLine = checkNotNull(ElideRunConfiguration.executionCommandLine(listOf("test"), coverage = true))

    assertEquals(listOf("test", "--reporter=tap", "--coverage"), commandLine)
    assertTrue(ElideRunConfiguration.emitsTap(commandLine))
  }

  @Test fun `a run needing neither flag keeps the command line the user typed`() {
    // `null` hands the run back to the platform's own state, which runs the persisted command line unchanged
    assertNull(ElideRunConfiguration.executionCommandLine(listOf("run", "src/main.kt"), coverage = false))
    assertNull(ElideRunConfiguration.executionCommandLine(listOf("test", "--reporter=junit"), coverage = false))
  }

  @Test fun `a coverage run of a command line naming its own reporter keeps that reporter`() {
    assertEquals(
      listOf("test", "--coverage", "--reporter=junit"),
      ElideRunConfiguration.executionCommandLine(listOf("test", "--reporter=junit"), coverage = true),
    )
  }
}
