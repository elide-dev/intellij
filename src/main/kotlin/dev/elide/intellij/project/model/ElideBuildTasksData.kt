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
package dev.elide.intellij.project.model

import com.intellij.openapi.externalSystem.model.Key
import java.io.Serializable

/**
 * Payload of a node holding build tasks in the resolved model.
 *
 * The tasks are [TaskData][com.intellij.openapi.externalSystem.model.task.TaskData] children of this node rather
 * than of the project node itself. The platform's own view contributor claims every `ProjectKeys.TASK` node it is
 * handed and files the tasks under a group node named after their task group — a level of nesting Elide's build
 * graph, which declares no groups, has nothing to put in. Hanging the tasks off this node keeps them out of that
 * contributor's reach and leaves the list to
 * [ElideTasksNode][dev.elide.intellij.ui.ElideTasksNode], while their own key stays `ProjectKeys.TASK`, where the
 * platform's keymap and task activation look for them.
 *
 * [project] names the project whose tasks hang off this node, and is `null` for the list itself. A workspace builds
 * one graph out of several projects, and the CLI qualifies every member's task with the project declaring it, so
 * the list holds one node per project — each carrying that project's name — instead of the tasks directly.
 */
class ElideBuildTasksData(val project: String? = null) : Serializable {
  // value semantics: the tree merges a re-synced node with the one it shows instead of replacing it, and it keys
  // that merge on the node's data
  override fun equals(other: Any?): Boolean = other is ElideBuildTasksData && other.project == project

  override fun hashCode(): Int = project?.hashCode() ?: javaClass.hashCode()

  companion object {
    private const val serialVersionUID: Long = 1L

    /** Key of the build task list in a resolved project node. */
    @JvmField val KEY: Key<ElideBuildTasksData> = Key.create(ElideBuildTasksData::class.java, 100)
  }
}
