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
package dev.elide.intellij.execution.coverage

import com.intellij.build.BuildView
import com.intellij.coverage.CoverageExecutor
import com.intellij.coverage.CoverageRunnerData
import com.intellij.execution.configurations.ConfigurationInfoProvider
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.AsyncProgramRunner
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.RunContentBuilder
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunnableState
import dev.elide.intellij.execution.ElideRunConfiguration
import org.jetbrains.concurrency.Promise
import org.jetbrains.concurrency.resolvedPromise

/**
 * Program runner backing the IDE's "Run with Coverage" action for [ElideRunConfiguration].
 *
 * The run itself is the ordinary one: [ElideRunConfiguration.getState] adds `--coverage` to the command line when
 * this runner is the one executing it, and the test tree renders the run as usual. What this runner adds is the
 * step after it, where the reports the CLI wrote are merged and attached as a coverage suite.
 *
 * The execution below mirrors the platform's `ExternalSystemTaskRunner`, which claims only the "Run" executor: an
 * external system run publishes its own build view, and the descriptor it produces is hidden unless the console is
 * that view, so the run does not show up twice in the Run window.
 */
class ElideCoverageProgramRunner : AsyncProgramRunner<RunnerSettings>() {
  override fun getRunnerId(): String = RUNNER_ID

  override fun canRun(executorId: String, profile: RunProfile): Boolean {
    if (CoverageExecutor.EXECUTOR_ID != executorId) return false
    val configuration = profile as? ElideRunConfiguration ?: return false

    return ElideRunConfiguration.supportsCoverage(configuration.settings.taskNames)
  }

  /**
   * Marks the run as one collecting coverage.
   *
   * `CoverageDataManager.processGatheredCoverage` ignores a run whose runner settings are not these, which is how
   * the platform tells a coverage run from an ordinary one.
   */
  override fun createConfigurationData(settingsProvider: ConfigurationInfoProvider): RunnerSettings = CoverageRunnerData()

  override fun execute(environment: ExecutionEnvironment, state: RunProfileState): Promise<RunContentDescriptor?> {
    val configuration = environment.runProfile as? ElideRunConfiguration ?: return resolvedPromise(null)
    if (state !is ExternalSystemRunnableState) return resolvedPromise(null)

    val service = ElideCoverageService.getInstance(environment.project)
    val startedAt = System.currentTimeMillis()
    service.runStarted(configuration.settings.externalProjectPath)

    val result = state.execute(environment.executor, this) ?: return resolvedPromise(null)
    val descriptor = RunContentBuilder(result, environment).showRunContent(environment.contentToReuse)
      ?: return resolvedPromise(null)

    state.setContentDescriptor(descriptor)

    // the reports are complete only once the CLI has exited: the guest engine writes its LCOV as its task finishes,
    // and the JaCoCo execution file is flushed when the test JVM shuts down
    descriptor.processHandler?.addProcessListener(object : ProcessListener {
      override fun processTerminated(event: ProcessEvent) {
        service.attachRunReports(configuration, startedAt)
      }
    })

    return resolvedPromise(hideOutsideBuildView(descriptor, result.executionConsole))
  }

  /**
   * Returns [descriptor], or a copy of it whose content the Run window does not show a second time.
   *
   * An external system run that renders itself — the test tree, here — has already published its content; the
   * descriptor is still needed for the process handler and the run's toolbar actions.
   */
  private fun hideOutsideBuildView(
    descriptor: RunContentDescriptor,
    console: ExecutionConsole?,
  ): RunContentDescriptor {
    if (console is BuildView) return descriptor

    val hidden = object : RunContentDescriptor(
      descriptor.executionConsole,
      descriptor.processHandler,
      descriptor.component,
      descriptor.displayName,
      descriptor.icon,
      descriptor.activationCallback,
      descriptor.restartActions,
    ) {
      override fun isHiddenContent(): Boolean = true
    }

    hidden.runnerLayoutUi = descriptor.runnerLayoutUi
    return hidden
  }

  internal companion object {
    /** Runner ID, used by [ElideRunConfiguration] to recognize a run that must collect coverage. */
    internal const val RUNNER_ID = "ElideCoverageRunner"
  }
}
