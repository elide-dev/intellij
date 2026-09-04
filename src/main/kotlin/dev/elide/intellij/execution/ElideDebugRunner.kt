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

import com.intellij.debugger.impl.GenericDebuggerRunner
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.RunContentDescriptor

/**
 * Program runner backing the IDE's "Debug" action for [ElideRunConfiguration].
 *
 * The CLI starts JVM entrypoints under a suspended JDWP server when it is passed `--debugger`
 * ([dev.elide.intellij.cli.ElideCli.DEBUGGER], added to the command line by
 * [ElideRunConfiguration.getState]), so all this runner does is point the IDE's Java debugger at that server;
 * [ElideDebugRunnableState] carries the connection.
 *
 * The registration in `plugin.xml` is ordered first on purpose: the platform's own
 * `ExternalSystemTaskDebugRunner` accepts every external system run configuration, and expects the opposite
 * handshake, where the build tool dials back into a socket the IDE listens on.
 */
class ElideDebugRunner : GenericDebuggerRunner() {
  override fun getRunnerId(): String = RUNNER_ID

  override fun canRun(executorId: String, profile: RunProfile): Boolean {
    return DefaultDebugExecutor.EXECUTOR_ID == executorId &&
      profile is ElideRunConfiguration &&
      profile.supportsDebugger
  }

  override fun createContentDescriptor(
    state: RunProfileState,
    environment: ExecutionEnvironment
  ): RunContentDescriptor? {
    val connection = (state as? ElideDebugRunnableState)?.createRemoteConnection(environment)
      ?: return super.createContentDescriptor(state, environment)

    // the CLI installs dependencies and compiles sources before the entrypoint opens its JDWP port, so the attach
    // window has to outlast a cold build instead of the platform's 30 second default
    return attachVirtualMachine(state, environment, connection, ATTACH_TIMEOUT_MILLIS)
  }

  internal companion object {
    /** Runner ID, used by [ElideRunConfiguration] to recognize a debug run of its own. */
    internal const val RUNNER_ID = "ElideDebugRunner"

    /** Time the debugger keeps retrying the connection while the CLI builds the project. */
    private const val ATTACH_TIMEOUT_MILLIS = 120_000L
  }
}
