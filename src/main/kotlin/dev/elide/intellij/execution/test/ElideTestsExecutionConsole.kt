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
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.testframework.sm.runner.GeneralIdBasedToSMTRunnerEventsConvertor
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerUIActionsHandler
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import dev.elide.intellij.android.testing.ElideAndroidTestSession
import dev.elide.intellij.execution.build.ElideBuildProgressRecord

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
  private var testSession: ElideTestOutputSession? = null
  private var attached = false
  private var androidOutput = false
  private val structuredOutput = StringBuilder()

  /** Protocol session for the running test command. */
  internal val session: ElideTestOutputSession? get() = testSession

  /**
   * Build this console's UI and the event pipeline behind its test tree, and return the session that feeds it.
   *
   * Called once, by [ElideTestsExecutionConsoleManager]: the results form the tree's events are published into only
   * exists once the UI has been built, and the root node has to know which execution it belongs to before the first
   * test arrives, or the Run window cannot tell this run's results from the previous one's.
   */
  internal fun startSession(executionId: Long, androidTargets: List<String> = emptyList()): ElideTestOutputSession {
    androidOutput = androidTargets.isNotEmpty()
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

    return (if (androidOutput) ElideAndroidTestSession(processor, androidTargets) else ElideTestSession(processor)).also {
      testSession = it
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
    if (androidOutput) {
      // Attaching the inner console directly would bypass print() and expose raw progress JSON.
      processHandler.addProcessListener(object : ProcessAdapter() {
        override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
          print(event.text, ConsoleViewContentType.getConsoleViewType(outputType))
        }
        override fun processTerminated(event: ProcessEvent) = finishOutput()
      })
    } else {
      super.attachToProcess(processHandler)
    }
  }

  /** Feed the protocol original JSON, but keep the user-facing stderr console human-readable. */
  @Synchronized override fun print(text: String, contentType: ConsoleViewContentType) {
    if (!androidOutput || contentType != ConsoleViewContentType.ERROR_OUTPUT) {
      super.print(text, contentType)
      return
    }
    structuredOutput.append(text)
    var end = structuredOutput.indexOf("\n")
    while (end >= 0) {
      printRecord(structuredOutput.substring(0, end).trimEnd('\r'))
      structuredOutput.delete(0, end + 1)
      end = structuredOutput.indexOf("\n")
    }
  }

  @Synchronized internal fun finishOutput() {
    if (structuredOutput.isNotEmpty()) printRecord(structuredOutput.toString())
    structuredOutput.setLength(0)
  }

  private fun printRecord(line: String) {
    val record = ElideBuildProgressRecord.parse(line)
    val rendered = if (record != null) record.console() else line + "\n"
    if (rendered != null) super.print(rendered, ConsoleViewContentType.NORMAL_OUTPUT)
  }

  /**
   * Hides the build view's own tree, so the Run window shows this test tree instead. Build events reach the Build
   * window, which [ElideTestsExecutionConsoleManager] forwards them to.
   */
  override fun isExecutionViewHidden(): Boolean = true

  override fun dispose() {
    testSession = null
    super.dispose()
  }
}
