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
import com.intellij.ide.projectView.PresentationData
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.externalSystem.util.Order
import com.intellij.openapi.externalSystem.view.ExternalProjectsView
import com.intellij.openapi.externalSystem.view.ExternalSystemNode
import com.intellij.openapi.externalSystem.view.TaskNode
import dev.elide.intellij.Constants
import dev.elide.intellij.project.model.ElideBuildTasksData

/**
 * The "Tasks" node of the Elide tool window: the build targets the CLI reported for the project, as a flat list.
 *
 * The task nodes below it are the platform's own [TaskNode]s, so everything the platform offers for a task — "Run",
 * task activation, a keymap shortcut — works on them; only the list around them is ours, because the platform's
 * equivalent node buckets tasks by task group, which an Elide build graph does not have.
 */
// same position the platform's own tasks node takes, ahead of dependencies and modules
@Order(10)
class ElideTasksNode(
  externalProjectsView: ExternalProjectsView,
  private val tasksNode: DataNode<ElideBuildTasksData>,
) : ExternalSystemNode<ElideBuildTasksData>(externalProjectsView, null, tasksNode) {
  override fun getName(): String = Constants.Strings["toolwindow.tasks"]

  override fun update(presentation: PresentationData) {
    super.update(presentation)
    presentation.setIcon(AllIcons.Nodes.ConfigFolder)
  }

  override fun isVisible(): Boolean = super.isVisible() && hasChildren()

  override fun doBuildChildren(): List<ExternalSystemNode<*>> {
    return ExternalSystemApiUtil.getChildren(tasksNode, ProjectKeys.TASK).map { TaskNode(externalProjectsView, it) }
  }
}
