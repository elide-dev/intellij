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

import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RemoteConnection
import com.intellij.execution.configurations.RemoteConnectionCreator
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunnableState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import dev.elide.intellij.Constants

/**
 * Task state used for debug runs of an [ElideRunConfiguration], driven by [ElideDebugRunner].
 *
 * The command line the state executes carries [Constants.FLAG_DEBUGGER], which makes the CLI start the JVM entrypoint
 * behind a suspended JDWP server. Because the debuggee owns the socket, the connection handed to the debugger is a
 * client-mode one, and the debugger polls it until the CLI is done installing dependencies and compiling sources.
 */
internal class ElideDebugRunnableState(
  settings: ExternalSystemTaskExecutionSettings,
  project: Project,
  configuration: ElideRunConfiguration,
  environment: ExecutionEnvironment,
) : ExternalSystemRunnableState(
  /* settings = */ settings,
  /* project = */ project,
  /* debug = */ false,
  /* configuration = */ configuration,
  /* env = */ environment,
),
  RemoteConnectionCreator {
  private val connection = RemoteConnection(
    /* useSockets = */ true,
    /* hostName = */ Constants.DEBUGGER_HOST,
    /* address = */ Constants.DEBUGGER_PORT.toString(),
    /* serverMode = */ false,
  )

  override fun createRemoteConnection(environment: ExecutionEnvironment): RemoteConnection = connection

  override fun isPollConnection(): Boolean = true

  override fun execute(executor: Executor?, runner: ProgramRunner<*>): ExecutionResult? {
    val result = super.execute(executor, runner) ?: return null

    // the port is only known for certain once the JDWP agent announces it; until then the connection points at the
    // CLI's default, which the poll loop re-reads on every attach attempt
    result.processHandler.addProcessListener(JdwpAddressListener())

    return result
  }

  /** Retargets the debugger connection at the port the JDWP agent reports in the CLI output. */
  private inner class JdwpAddressListener : ProcessListener {
    @Volatile private var resolved = false

    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
      if (resolved || TRANSPORT_NAME !in event.text) return
      val port = JDWP_BANNER.find(event.text)?.groupValues?.get(1) ?: return

      resolved = true
      connection.debuggerAddress = port
      connection.applicationAddress = port
    }
  }

  private companion object {
    /** Transport the JDWP agent names in its banner; used to keep the regex off every other output line. */
    private const val TRANSPORT_NAME = "dt_socket"

    /** Banner the JDWP agent prints once its transport is listening, carrying the port it bound. */
    private val JDWP_BANNER = Regex("""Listening for transport dt_socket at address:\s*(\d+)""")
  }
}
