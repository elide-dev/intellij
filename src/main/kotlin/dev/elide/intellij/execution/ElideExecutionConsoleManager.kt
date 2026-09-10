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

import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.externalSystem.execution.ExternalSystemExecutionConsoleManager
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTask
import com.intellij.openapi.externalSystem.service.internal.ExternalSystemExecuteTaskTask
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import dev.elide.intellij.Constants
import dev.elide.intellij.execution.build.elideSourceFilters

/**
 * Console of an Elide run that is not a test run, which is the platform's own plus the links this plugin adds.
 *
 * The platform gives an external system without a console manager a plain text console and no filters at all, so a
 * file and line the CLI printed is left as text in every console of the run: the log itself, and the console the
 * build tree shows beside a diagnostic. Both are filtered here.
 *
 * `elide test` runs have a console manager of their own, which owns the test tree;
 * [dev.elide.intellij.execution.test.ElideTestsExecutionConsoleManager] claims them and this one leaves them alone,
 * so which of the two applies does not depend on the order they are registered in.
 */
class ElideExecutionConsoleManager : ExternalSystemExecutionConsoleManager<ConsoleView, ProcessHandler> {
  override fun getExternalSystemId(): ProjectSystemId = Constants.SYSTEM_ID

  override fun isApplicableFor(task: ExternalSystemTask): Boolean {
    val executeTask = task as? ExternalSystemExecuteTaskTask ?: return false
    if (executeTask.externalSystemId != Constants.SYSTEM_ID) return false

    return !ElideRunConfiguration.emitsTap(executeTask.tasksToExecute)
  }

  override fun attachExecutionConsole(
    project: Project,
    task: ExternalSystemTask,
    env: ExecutionEnvironment?,
    processHandler: ProcessHandler?,
  ): ConsoleView {
    // the platform's own console for an external system run, with the run's filters installed: the builder is what
    // brings the predefined ones (stack traces, URLs) that a console of this kind has everywhere else
    val builder = TextConsoleBuilderFactory.getInstance().createBuilder(project)
    getCustomExecutionFilters(project, task, env).forEach(builder::addFilter)

    return builder.console.also { console -> processHandler?.let(console::attachToProcess) }
  }

  override fun onOutput(
    executionConsole: ConsoleView,
    processHandler: ProcessHandler,
    text: String,
    processOutputType: Key<*>,
  ) {
    processHandler.notifyTextAvailable(text, processOutputType)
  }

  override fun getCustomExecutionFilters(
    project: Project,
    task: ExternalSystemTask,
    env: ExecutionEnvironment?,
  ): Array<Filter> = elideSourceFilters(project, task)

  override fun getRestartActions(consoleView: ConsoleView): Array<AnAction> = AnAction.EMPTY_ARRAY
}
