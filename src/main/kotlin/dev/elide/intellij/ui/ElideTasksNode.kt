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
package dev.elide.intellij.ui

import com.intellij.icons.AllIcons
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.ide.projectView.PresentationData
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.ExternalSystemDataKeys
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.externalSystem.util.Order
import com.intellij.openapi.externalSystem.view.ExternalProjectsView
import com.intellij.openapi.externalSystem.view.ExternalSystemNode
import com.intellij.openapi.externalSystem.view.TaskNode
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.util.execution.ParametersListUtil
import dev.elide.intellij.Constants
import dev.elide.intellij.project.model.ElideBuildTasksData
import dev.elide.intellij.project.model.ElideBuildTaskInfo
import dev.elide.intellij.project.model.buildTargetName
import dev.elide.intellij.service.ElideExecutionService
import dev.elide.intellij.service.elideProjectIndex

/**
 * The "Tasks" node of the Elide tool window, and the per-project nodes below it.
 *
 * The task nodes below it are the platform's own [TaskNode]s, so everything the platform offers for a task — "Run",
 * task activation, a keymap shortcut — works on them; only the list around them is ours, because the platform's
 * equivalent node buckets tasks by task group, which an Elide build graph does not have.
 *
 * A workspace builds one graph out of several projects, so the list holds a node per project — the same class,
 * carrying that project's name — and the tasks hang off those instead. A standalone project has one project's worth
 * of tasks and lists them directly.
 */
// same position the platform's own tasks node takes, ahead of dependencies and modules
@Order(10)
class ElideTasksNode(
  externalProjectsView: ExternalProjectsView,
  private val tasksNode: DataNode<ElideBuildTasksData>,
) : ExternalSystemNode<ElideBuildTasksData>(externalProjectsView, null, tasksNode) {
  /** Project whose tasks this node holds, or `null` when it is the list itself. */
  private val project: String? get() = tasksNode.data.project

  override fun getName(): String = project ?: Constants.Strings["toolwindow.tasks"]

  override fun update(presentation: PresentationData) {
    super.update(presentation)
    // a project's tasks are shown under the icon the tree gives that project's module, the list under a folder
    presentation.setIcon(if (project != null) uiAware.projectIcon else AllIcons.Nodes.ConfigFolder)
  }

  override fun isVisible(): Boolean = super.isVisible() && hasChildren()

  override fun doBuildChildren(): List<ExternalSystemNode<*>> {
    val projects = ExternalSystemApiUtil.getChildren(tasksNode, ElideBuildTasksData.KEY)
      .map { ElideTasksNode(externalProjectsView, it) }

    return projects + ExternalSystemApiUtil.getChildren(tasksNode, ProjectKeys.TASK)
      .map { node ->
        object : TaskNode(externalProjectsView, node) {
          override fun getMenuId(): String = "Elide.TaskMenu"
        }
      }
  }
}

/** Debug is only offered for targets the inspected build graph advertises as debugger-capable. */
class ElideDebugTaskAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val project = e.project
    val task = selectedTask(e)
    e.presentation.isEnabledAndVisible = project != null && task != null && supportsTask(project, task)
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val task = selectedTask(e) ?: return
    if (!supportsTask(project, task)) return
    val data = task.data ?: return
    project.service<ElideExecutionService>().execute(
      ParametersListUtil.join(listOf(data.name)),
      data.linkedExternalProjectPath,
      DefaultDebugExecutor.getDebugExecutorInstance(),
    )
  }

  private fun selectedTask(e: AnActionEvent): TaskNode? =
    e.getData(ExternalSystemDataKeys.SELECTED_NODES)?.singleOrNull() as? TaskNode

  private fun supportsTask(project: Project, task: TaskNode): Boolean {
    val data = task.data ?: return false
    return data.owner == Constants.SYSTEM_ID && supportsTask(
      data.name,
      project.elideProjectIndex[data.linkedExternalProjectPath]?.buildTasks.orEmpty(),
    )
  }

  internal companion object {
    internal fun supportsTask(taskName: String, tasks: List<ElideBuildTaskInfo>): Boolean {
      val target = buildTargetName(taskName) ?: return false
      return tasks.any { task ->
        task.name == target && (
          task.options.any { it.option.substringBefore('=') == "--debugger" } ||
            task.name.substringAfterLast(':').startsWith("android-debug-")
          )
      }
    }
  }
}
