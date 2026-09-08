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

import com.intellij.build.BuildViewSettingsProvider
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.testframework.sm.runner.GeneralIdBasedToSMTRunnerEventsConvertor
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerUIActionsHandler
import com.intellij.openapi.util.Disposer

/**
 * Test tree and output pane shown in the Run window for an `elide test` run, fed by [ElideTestSession].
 *
 * Public because it is the console type `ElideTestsExecutionConsoleManager` declares to the platform.
 */
class ElideTestsExecutionConsole internal constructor(
  private val consoleProperties: ElideTestConsoleProperties,
) : SMTRunnerConsoleView(
  consoleProperties,
  SMTestRunnerConnectionUtil.getSplitterPropertyName(ElideTestConsoleProperties.FRAMEWORK_NAME),
),
  BuildViewSettingsProvider {
  private var tapSession: ElideTestSession? = null
  private var attached = false

  /** The session decoding the run's TAP stream, or `null` before [startSession] has run. */
  internal val session: ElideTestSession? get() = tapSession

  /**
   * Build this console's UI and the event pipeline behind its test tree, and return the session that feeds it.
   *
   * Called once, by [ElideTestsExecutionConsoleManager]: the results form the tree's events are published into only
   * exists once the UI has been built, and the root node has to know which execution it belongs to before the first
   * test arrives, or the Run window cannot tell this run's results from the previous one's.
   */
  internal fun startSession(executionId: Long): ElideTestSession {
    initUI()

    val resultsViewer = resultsViewer
    resultsViewer.addEventsListener(SMTRunnerUIActionsHandler(consoleProperties))
    resultsViewer.testsRootNode.setExecutionId(executionId)

    // id based rather than name based: `ElideTestSession` addresses every node by the number TAP tags it with, so a
    // run that interleaves several tests' starts and output still lands each event on the node that owns it
    val processor = GeneralIdBasedToSMTRunnerEventsConvertor(
      consoleProperties.project,
      resultsViewer.testsRootNode,
      ElideTestConsoleProperties.FRAMEWORK_NAME,
    )
    processor.setLocator(consoleProperties.testLocator)
    processor.addEventsListener(resultsViewer)
    Disposer.register(this, processor)

    return ElideTestSession(processor).also {
      tapSession = it
      it.start()
    }
  }

  /**
   * Attach the run's process, once.
   *
   * The build view attaches its execution console to the process itself, on top of the attach this console is set
   * up with, and every attach replays the listeners registered on it. Nothing here builds a second event pipeline
   * on a repeat attach, but the process' own output would be printed to the console once per attach.
   */
  override fun attachToProcess(processHandler: ProcessHandler) {
    if (attached) return
    attached = true
    super.attachToProcess(processHandler)
  }

  /**
   * Hides the build view's own tree, so the Run window shows this test tree instead. Build events reach the Build
   * window, which [ElideTestsExecutionConsoleManager] forwards them to.
   */
  override fun isExecutionViewHidden(): Boolean = true

  override fun dispose() {
    tapSession = null
    super.dispose()
  }
}
