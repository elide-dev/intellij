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

import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.Key
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.model.task.TaskData
import com.intellij.openapi.externalSystem.view.ExternalProjectsView
import com.intellij.openapi.externalSystem.view.ExternalSystemNode
import com.intellij.openapi.externalSystem.view.ExternalSystemViewContributor
import com.intellij.util.containers.MultiMap
import dev.elide.intellij.Constants
import dev.elide.intellij.project.model.ElideBuildTasksData
import dev.elide.intellij.project.model.buildTargetName
import dev.elide.intellij.project.model.unqualifiedTaskName

/**
 * Contributes the task list of the Elide tool window's tree, and names the nodes below it.
 *
 * A task carries the build target prefix in its name so that every path the platform runs it through — "Run" in the
 * tool window, task activation, a keymap shortcut — reaches it as an `elide build` target, and inside a workspace it
 * carries the project declaring it as well. In a tree that lists nothing but build targets, each under the project
 * owning it, both are noise, so the label drops them while the node keeps the full name; Gradle's view contributor
 * shortens `:module:task` to `task` the same way.
 */
class ElideViewContributor : ExternalSystemViewContributor() {
  override fun getSystemId(): ProjectSystemId = Constants.SYSTEM_ID

  override fun getKeys(): List<Key<*>> = listOf(ElideBuildTasksData.KEY)

  override fun createNodes(
    externalProjectsView: ExternalProjectsView,
    dataNodes: MultiMap<Key<*>, DataNode<*>>,
  ): List<ExternalSystemNode<*>> {
    return dataNodes[ElideBuildTasksData.KEY].filter { it.data is ElideBuildTasksData }.map { node ->
      @Suppress("UNCHECKED_CAST")
      ElideTasksNode(externalProjectsView, node as DataNode<ElideBuildTasksData>)
    }
  }

  override fun getDisplayName(node: DataNode<*>): String? {
    val task = node.data as? TaskData ?: return null

    return buildTargetName(task.name)?.let(::unqualifiedTaskName)
  }
}
