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
package dev.elide.intellij.execution.nativeimage.debug

import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.AsyncProgramRunner
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugProcessStarter
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import dev.elide.intellij.execution.build.ElideSourceLinkFilter
import dev.elide.intellij.execution.nativeimage.ElideNativeDebugger
import dev.elide.intellij.execution.nativeimage.ElideNativeImageRunConfiguration
import dev.elide.intellij.ui.ElideNotifications
import org.jetbrains.concurrency.Promise
import org.jetbrains.concurrency.resolvedPromise

/**
 * Debugs the binary a Native Image artifact produces, under the GDB or LLDB backend of Native Debugging Support.
 *
 * Registered `order="first"` so it wins over the advertiser answering the same executor while the backend plugin is
 * not installed; this runner only exists in the module that loads with it.
 */
class ElideNativeImageDebugRunner : AsyncProgramRunner<RunnerSettings>() {
  override fun getRunnerId(): String = RUNNER_ID

  override fun canRun(executorId: String, profile: RunProfile): Boolean {
    return DefaultDebugExecutor.EXECUTOR_ID == executorId && profile is ElideNativeImageRunConfiguration
  }

  // `XDebuggerManager.startSession` and `XDebugSession.getRunContentDescriptor` are deprecated on 261 in favour of
  // `newSessionBuilder`, which 253 does not declare (see `docs/PLATFORM_APIS.md`)
  @Suppress("DEPRECATION")
  override fun execute(environment: ExecutionEnvironment, state: RunProfileState): Promise<RunContentDescriptor?> {
    val project = environment.project
    val configuration = environment.runProfile as ElideNativeImageRunConfiguration

    // both throw ExecutionException, which the platform turns into the error balloon of a failed start
    val launch = configuration.resolveLaunch()
    val driver = ElideNativeDebuggers.driver(project, checkNotNull(configuration.externalProjectPath))

    // the session still runs without debug info — machine-level stepping works — so this reports rather than aborts
    if (!launch.hasDebugInfo) {
      ElideNotifications.notifyNativeImageWithoutDebugInfo(
        project,
        configuration.artifact ?: configuration.name,
        launch,
      )
    }

    val parameters = ElideNativeImageRunParameters(launch.commandLine, driver)
    val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).apply {
      addFilter(ElideSourceLinkFilter(project, launch.root))
    }

    val session = XDebuggerManager.getInstance(project).startSession(
      environment,
      object : XDebugProcessStarter() {
        override fun start(session: XDebugSession): XDebugProcess {
          return ElideNativeImageDebugProcess(parameters, session, console, launch).also { process ->
            ProcessTerminatedListener.attach(process.processHandler, project)
            process.start()
          }
        }
      },
    )

    return resolvedPromise(session.runContentDescriptor)
  }

  internal companion object {
    /** Runner ID of the Native Image debug runner; the advertiser probes for exactly this registration. */
    internal const val RUNNER_ID = ElideNativeDebugger.DEBUG_RUNNER_ID
  }
}
