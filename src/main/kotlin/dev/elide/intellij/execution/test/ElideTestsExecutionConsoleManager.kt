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
package dev.elide.intellij.execution.test

import com.intellij.build.BuildContentManager
import com.intellij.build.BuildProgressListener
import com.intellij.build.BuildViewManager
import com.intellij.build.DefaultBuildDescriptor
import com.intellij.build.events.BuildEvent
import com.intellij.build.events.FailureResult
import com.intellij.build.events.FinishBuildEvent
import com.intellij.build.events.OutputBuildEvent
import com.intellij.build.events.StartBuildEvent
import com.intellij.build.events.impl.StartBuildEventImpl
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.ui.SMRootTestProxyFormatter
import com.intellij.execution.testframework.sm.runner.ui.TestTreeRenderer
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.externalSystem.execution.ExternalSystemExecutionConsoleManager
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTask
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfigurationViewManager
import com.intellij.openapi.externalSystem.service.internal.ExternalSystemExecuteTaskTask
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.ui.SimpleTextAttributes
import dev.elide.intellij.Constants
import dev.elide.intellij.execution.ElideRunConfiguration

/**
 * Replaces the plain console of an `elide test` run with the IDE's test tree, decoding the TAP 13 stream the CLI
 * writes under `--reporter=tap`.
 *
 * The reporter is selected by [ElideRunConfiguration.tapCommandLine] when the configuration is executed, so this
 * only claims runs whose output actually is a TAP stream: a command line naming another reporter, or a task started
 * outside a run configuration, keeps the CLI's own console output.
 */
class ElideTestsExecutionConsoleManager :
  ExternalSystemExecutionConsoleManager<ElideTestsExecutionConsole, ProcessHandler> {
  override fun getExternalSystemId(): ProjectSystemId = Constants.SYSTEM_ID

  override fun isApplicableFor(task: ExternalSystemTask): Boolean {
    val executeTask = task as? ExternalSystemExecuteTaskTask ?: return false
    if (executeTask.externalSystemId != Constants.SYSTEM_ID) return false

    return ElideRunConfiguration.emitsTap(executeTask.tasksToExecute)
  }

  override fun attachExecutionConsole(
    project: Project,
    task: ExternalSystemTask,
    env: ExecutionEnvironment?,
    processHandler: ProcessHandler?,
  ): ElideTestsExecutionConsole? {
    val environment = env ?: return null
    val configuration = environment.runnerAndConfigurationSettings?.configuration as? ExternalSystemRunConfiguration
      ?: return null

    val console = ElideTestsExecutionConsole(ElideTestConsoleProperties(configuration, environment.executor))
    val session = console.startSession(environment.executionId)
    val testsRoot = console.resultsViewer.testsRootNode

    explainEmptyTree(console)

    processHandler?.let { handler ->
      testsRoot.setHandler(handler)
      console.attachToProcess(handler)
      handler.addProcessListener(object : ProcessListener {
        override fun processTerminated(event: ProcessEvent) = session.finish()
      })
    }

    forwardBuildEvents(project, task.id, testsRoot)

    return console
  }

  override fun onOutput(
    executionConsole: ElideTestsExecutionConsole,
    processHandler: ProcessHandler,
    text: String,
    processOutputType: Key<*>,
  ) {
    executionConsole.session?.output(text, stdout = !ProcessOutputType.isStderr(processOutputType))
  }

  override fun getRestartActions(consoleView: ElideTestsExecutionConsole): Array<AnAction> = AnAction.EMPTY_ARRAY

  /**
   * Says so when a finished run reported no tests at all, instead of leaving an empty tree with no explanation.
   */
  private fun explainEmptyTree(console: ElideTestsExecutionConsole) {
    val renderer = console.resultsViewer.treeView?.cellRenderer as? TestTreeRenderer ?: return

    renderer.setAdditionalRootFormatter(object : SMRootTestProxyFormatter {
      override fun format(testProxy: SMTestProxy.SMRootTestProxy, renderer: TestTreeRenderer) {
        if (testProxy.isInProgress || !testProxy.isEmptySuite) return

        renderer.clear()
        renderer.append(Constants.Strings["execution.test.noTests"], SimpleTextAttributes.REGULAR_ATTRIBUTES)
      }
    })
  }

  /**
   * Sends this run's build events on to the Build window.
   *
   * The test tree takes over the Run window, which leaves the run's build view without a tree of its own: the
   * compiler diagnostics and progress events it would have shown have nowhere else to go. The Build window is kept
   * from raising itself when the run fails, because a failing test fails the build here and the failures are
   * already on screen, and is raised explicitly for the failures that are not test results at all.
   *
   * Standard output is not forwarded: under `--reporter=tap` it carries nothing but the event stream this console
   * already renders as a tree, and the Build window has no test nodes to attribute it to, so every line of it would
   * pile up, raw, under that window's root node. The CLI's own log, which is what that window is for, and the
   * compiler diagnostics that come with it, are on standard error.
   *
   * [buildWindow] receives everything this run does forward, and is the Build window's own view manager.
   */
  internal fun forwardBuildEvents(
    project: Project,
    taskId: ExternalSystemTaskId,
    testsRoot: SMTestProxy.SMRootTestProxy,
    buildWindow: BuildProgressListener = project.getService(BuildViewManager::class.java),
  ) {
    val disposable = Disposer.newDisposable(project, DISPOSABLE_NAME)

    project.getService(ExternalSystemRunConfigurationViewManager::class.java).addListener({ buildId, event ->
      if (buildId == taskId) {
        if (!carriesTapStream(event)) buildWindow.onEvent(buildId, quietened(event))
        if (event is FinishBuildEvent) {
          raiseBuildWindowOnBuildFailure(project, event, testsRoot)
          Disposer.dispose(disposable)
        }
      }
    }, disposable)
  }

  /** Whether [event] carries a line of the run's TAP stream, which the test tree owns. */
  private fun carriesTapStream(event: BuildEvent): Boolean = event is OutputBuildEvent && event.outputType.isStdout

  /** A start event whose descriptor no longer raises the Build window when the run fails. */
  private fun quietened(event: BuildEvent): BuildEvent {
    if (event !is StartBuildEvent) return event

    val descriptor = event.buildDescriptor
    val quietened = DefaultBuildDescriptor(
      descriptor.id,
      descriptor.title,
      descriptor.workingDir,
      descriptor.startTime,
    ).apply { isActivateToolWindowWhenFailed = false }

    // deliberately built without the run's view settings: those are what hide the execution console's tree, and the
    // Build window is the one place this run's build events do get a tree of their own. `StartBuildEventImpl` rather
    // than `StartBuildEvent.builder`, which the 253 floor of the supported range does not declare
    return StartBuildEventImpl(quietened, event.message)
  }

  /**
   * Raises the Build window for a failure the test tree cannot show: the run never got as far as reporting a test,
   * so the tree is empty and the reason for it is in the build log.
   */
  private fun raiseBuildWindowOnBuildFailure(
    project: Project,
    event: FinishBuildEvent,
    testsRoot: SMTestProxy.SMRootTestProxy,
  ) {
    if (event.result !is FailureResult) return

    ApplicationManager.getApplication().invokeLater(
      { if (!testsRoot.isInProgress && testsRoot.isEmptySuite) showBuildWindow(project) },
      ModalityState.nonModal(),
      project.disposed,
    )
  }

  private fun showBuildWindow(project: Project) {
    val toolWindow = BuildContentManager.getInstance(project).orCreateToolWindow
    if (toolWindow.isAvailable && !toolWindow.isVisible) toolWindow.show(null)
  }

  private companion object {
    private const val DISPOSABLE_NAME = "Elide test run build event forwarding"
  }
}
