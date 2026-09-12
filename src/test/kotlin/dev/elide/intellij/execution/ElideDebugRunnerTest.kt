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

import com.intellij.debugger.engine.DelayedRemoteConnection
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import dev.elide.intellij.Constants
import dev.elide.intellij.project.model.ElideEntrypointInfo.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Covers which Elide configurations the IDE's "Debug" action is offered for, and the state it runs them through.
 *
 * Which runner the platform picks is what decides whether the action appears at all: the toolbar and the gutter only
 * offer "Debug" when some runner claims the configuration, and the platform's own `ExternalSystemTaskDebugRunner`
 * claims every external system configuration while expecting the opposite handshake, where the build tool dials back
 * into a socket the IDE listens on.
 */
@TestApplication
class ElideDebugRunnerTest {
  private val projectFixture = projectFixture()

  private val executor = DefaultDebugExecutor.getDebugExecutorInstance()

  private fun configuration(commandLine: String, kind: Kind?, value: String?): ElideRunConfiguration {
    val factory = ElideExternalTaskConfigurationType.configurationFactory

    return ElideRunConfiguration(projectFixture.get(), factory, "elide").apply {
      rawCommandLine = commandLine
      entrypointKind = kind
      entrypointValue = value
    }
  }

  /** The state the "Debug" action runs [configuration] through, after the platform has resolved its runner. */
  private fun debugState(configuration: ElideRunConfiguration): Any? {
    val runner = assertIs<ElideDebugRunner>(ProgramRunner.getRunner(executor.id, configuration))
    val environment = ExecutionEnvironmentBuilder(projectFixture.get(), executor)
      .runProfile(configuration)
      .runner(runner)
      .build()

    return configuration.getState(executor, environment)
  }

  @Test fun `a jvm test configuration is debugged by the elide runner`() {
    // the state carrying the client-mode connection the CLI's own JDWP server expects, rather than the one the
    // platform's runner builds, which waits for the build tool to dial back in
    assertIs<ElideDebugRunnableState>(debugState(configuration("test -t ^a\\.B$", Kind.JvmTest, "a.B")))
  }

  @Test fun `an entrypoint configuration is debugged by the elide runner`() {
    assertIs<ElideDebugRunnableState>(debugState(configuration("run src/Main.kt", Kind.JvmMainClass, "app.MainKt")))
  }

  @Test fun `the debugger is not dialled before the run announces its port`() {
    // the CLI debugs on a fixed port, so a connection dialled at launch reaches whatever already listens there: a
    // debuggee left suspended by an earlier run answers instead, and this run is never debugged. A delayed
    // connection is what holds the attach back until this run's own agent announces the port it bound
    val state = assertIs<ElideDebugRunnableState>(debugState(configuration("test", Kind.JvmTest, "a.B")))
    val environment = ExecutionEnvironmentBuilder(projectFixture.get(), executor)
      .runProfile(configuration("test", Kind.JvmTest, "a.B"))
      .build()

    val connection = state.createRemoteConnection(environment)

    assertIs<DelayedRemoteConnection>(connection)
    assertFalse(state.isPollConnection)
    assertEquals(Constants.DEBUGGER_HOST, connection.debuggerHostName)
    assertEquals(Constants.DEBUGGER_PORT.toString(), connection.debuggerAddress)
  }

  @Test fun `a run the cli cannot debug is left to the platform`() {
    // `elide build` opens no JDWP server, and a guest entrypoint answers with CDP/DAP, which the Java debugger does
    // not speak: this runner must decline both, or the action it offers cannot be delivered
    val build = configuration("build app", Kind.Artifact, "app")
    val guest = configuration("run src/main.js", Kind.Generic, "src/main.js")

    assertNull(ProgramRunner.getRunner(executor.id, build) as? ElideDebugRunner)
    assertNull(ProgramRunner.getRunner(executor.id, guest) as? ElideDebugRunner)
  }
}
