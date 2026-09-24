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
package dev.elide.intellij.action

import com.intellij.ide.actions.runAnything.RunAnythingAction.EXECUTOR_KEY
import com.intellij.ide.actions.runAnything.RunAnythingContext
import com.intellij.ide.actions.runAnything.RunAnythingUtil
import com.intellij.ide.actions.runAnything.activity.RunAnythingCommandLineProvider
import com.intellij.ide.actions.runAnything.getPath
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import dev.elide.intellij.Constants
import dev.elide.intellij.cli.ElideCliCompletion
import dev.elide.intellij.project.ElideWorkspaces
import dev.elide.intellij.service.ElideExecutionService
import dev.elide.intellij.service.elideProjectIndex
import dev.elide.intellij.settings.ElideSettings
import java.nio.file.Path
import javax.swing.Icon

/**
 * Extension used to add Elide to the "run anything" feature, including some basic completion for user input.
 */
class ElideRunAnythingProvider : RunAnythingCommandLineProvider() {
  override fun getIcon(value: String): Icon = Constants.Icons.ELIDE
  override fun getHelpIcon(): Icon = Constants.Icons.ELIDE

  override fun getCompletionGroupTitle(): String = Constants.Strings["actions.runAnything.completionGroup"]
  override fun getHelpCommandPlaceholder(): String = Constants.Strings["actions.runAnything.helpPlaceholder"]
  override fun getHelpCommand(): String = Constants.Strings["actions.runAnything.helpCommand"]
  override fun getHelpGroupTitle(): String {
    return Constants.SYSTEM_ID.readableName
  }

  override fun suggestCompletionVariants(dataContext: DataContext, commandLine: CommandLine): Sequence<String> {
    val project = RunAnythingUtil.fetchProject(dataContext)
    val projectPath = (dataContext.getData(EXECUTING_CONTEXT) ?: RunAnythingContext.ProjectContext(project))
      .workingDirectory(dataContext)

    val info = projectPath?.let { project.elideProjectIndex[it] }

    // the popup prefixes every variant with the parameters already completed, so only the next token is suggested
    val typed = commandLine.completedParameters
    val tasks = ElideCliCompletion.tasks(typed, info)
    val flags = ElideCliCompletion.flags(typed, info, includeShort = false)

    return (tasks + flags).asSequence().map { it.text }
  }

  override fun run(
    dataContext: DataContext,
    commandLine: CommandLine
  ): Boolean {
    val project = RunAnythingUtil.fetchProject(dataContext)
    val context = dataContext.getData(EXECUTING_CONTEXT) ?: RunAnythingContext.ProjectContext(project)
    val workDirectory = context.workingDirectory(dataContext) ?: return false

    project.getService(ElideExecutionService::class.java).execute(
      fullCommandLine = commandLine.command,
      externalProjectPath = workDirectory,
      executor = EXECUTOR_KEY.getData(dataContext),
    )

    return true
  }

  private fun RunAnythingContext.workingDirectory(dataContext: DataContext): String? {
    return when (this) {
      is RunAnythingContext.ProjectContext -> getLinkedProjectPath(dataContext) ?: getPath()
      is RunAnythingContext.ModuleContext -> getLinkedModulePath() ?: getPath()
      else -> getPath()
    }
  }

  /**
   * Returns the Elide project the generic "Project" context runs in: the one owning the file the user is looking at,
   * falling back to the first linked project.
   *
   * A workspace links its root alone, so the linked project is the whole workspace no matter which member the user
   * is working in. The CLI resolves the same workspace from a member's directory and narrows what it does to that
   * member — `elide test` there runs the member's tests and nothing else — which is what a command typed while
   * editing a member's file means; the member is also the project whose task listing the completion belongs to.
   */
  private fun RunAnythingContext.ProjectContext.getLinkedProjectPath(dataContext: DataContext): String? {
    val owner = contextFile(project, dataContext)?.let { ElideWorkspaces.owningProject(project, it) }

    return owner ?: ElideSettings.getSettings(project)
      .linkedProjectsSettings
      .firstOrNull()
      ?.let { ExternalSystemApiUtil.findProjectNode(project, Constants.SYSTEM_ID, it.externalProjectPath) }
      ?.data
      ?.linkedExternalProjectPath
  }

  /**
   * Returns the path of the file the popup was invoked over, falling back to the one the editor has selected: the
   * action is reachable from places carrying no file of their own, where the open editor is what the user is on.
   *
   * A file the local file system does not back — one inside a JAR, or a scratch of another backend — names no
   * directory a build could run in, and is left out.
   */
  private fun contextFile(project: Project, dataContext: DataContext): Path? {
    val file = CommonDataKeys.VIRTUAL_FILE.getData(dataContext)
      ?: FileEditorManager.getInstance(project).selectedFiles.firstOrNull()

    return file?.let { runCatching { it.toNioPath() }.getOrNull() }
  }

  private fun RunAnythingContext.ModuleContext.getLinkedModulePath(): String? {
    return ExternalSystemApiUtil.getExternalProjectPath(module)
  }
}
