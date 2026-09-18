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

import com.intellij.execution.Executor
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionUtil
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.externalSystem.action.ExternalSystemNodeAction
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.model.task.TaskData
import com.intellij.openapi.project.Project
import dev.elide.intellij.Constants
import dev.elide.intellij.execution.nativeimage.ElideNativeImageRunConfigurations
import dev.elide.intellij.project.model.ElideNativeImageInfo
import dev.elide.intellij.project.model.buildTargetName
import dev.elide.intellij.project.model.nativeImage
import dev.elide.intellij.service.elideProjectIndex

/**
 * Starts the binary a Native Image task of the tool window's tree produces.
 *
 * The tree lists every target of the build graph, most of which only assemble something; the few that produce a
 * runnable image are the ones the synced model reports, and these actions are offered on those alone.
 */
abstract class ElideNativeImageAction(private val executor: () -> Executor) :
  ExternalSystemNodeAction<TaskData>(TaskData::class.java) {
  override fun isEnabled(e: AnActionEvent): Boolean {
    if (!super.isEnabled(e)) return false
    if (getSystemId(e) != Constants.SYSTEM_ID) return false

    val project = getProject(e) ?: return false
    val task = getExternalData(e, TaskData::class.java) ?: return false

    return nativeImageOf(project, task) != null
  }

  /**
   * Hidden where it does not apply in a popup, and merely disabled on the toolbar.
   *
   * A context menu entry on a task that assembles a jar would be noise; a toolbar button that disappears when the
   * selection changes would move every other button under the pointer.
   */
  override fun isVisible(e: AnActionEvent): Boolean {
    if (getSystemId(e) != Constants.SYSTEM_ID) return false

    return !e.isFromContextMenu || isEnabled(e)
  }

  override fun perform(project: Project, systemId: ProjectSystemId, task: TaskData, e: AnActionEvent) {
    val artifact = buildTargetName(task.name) ?: return
    val settings = ElideNativeImageRunConfigurations.findOrCreate(project, task.linkedExternalProjectPath, artifact)

    ExecutionUtil.runConfiguration(settings, executor())
  }
}

/** Runs the binary of the selected Native Image task. */
class ElideNativeImageRunAction : ElideNativeImageAction(DefaultRunExecutor::getRunExecutorInstance)

/** Debugs the binary of the selected Native Image task. */
class ElideNativeImageDebugAction : ElideNativeImageAction(DefaultDebugExecutor::getDebugExecutorInstance)

/** The Native Image [task] produces, as the sync reported it; `null` for every other build target. */
internal fun nativeImageOf(project: Project, task: TaskData): ElideNativeImageInfo? {
  val artifact = buildTargetName(task.name) ?: return null

  return project.elideProjectIndex[task.linkedExternalProjectPath]?.nativeImage(artifact)
}
