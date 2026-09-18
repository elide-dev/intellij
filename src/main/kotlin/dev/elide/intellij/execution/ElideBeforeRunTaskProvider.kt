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

import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemBeforeRunTask
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemBeforeRunTaskProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import dev.elide.intellij.Constants
import dev.elide.intellij.cli.ElideCli
import dev.elide.intellij.execution.nativeimage.ElideNativeDebugger
import dev.elide.intellij.execution.nativeimage.ElideNativeImageRunConfiguration
import dev.elide.intellij.ui.ElideNotifications
import javax.swing.Icon

/**
 * Lets any run configuration run an Elide task before it launches, and is how a Native Image run builds its artifact.
 *
 * The platform executes the task through the external system, so it lands in the Build window with the diagnostics
 * of the build attached, exactly as a "Build" invoked by hand would, and a failing build aborts the launch.
 */
class ElideBeforeRunTaskProvider(project: Project) :
  ExternalSystemBeforeRunTaskProvider(Constants.SYSTEM_ID, project, ID) {
  override fun getIcon(): Icon = Constants.Icons.ELIDE

  /** A task the user still has to point at a project and a target; [buildTask] makes the ready-to-run one. */
  override fun createTask(runConfiguration: RunConfiguration): ExternalSystemBeforeRunTask {
    return ExternalSystemBeforeRunTask(ID, Constants.SYSTEM_ID)
  }

  /**
   * Refuses a build that only feeds a debug session the IDE cannot start, and offers the missing backend instead.
   *
   * Before-run tasks run ahead of the program runner, so without this a "Debug" on a machine without Native
   * Debugging Support would spend a full Native Image build before the runner could say it has nothing to debug
   * with.
   */
  override fun executeTask(
    context: DataContext,
    configuration: RunConfiguration,
    environment: ExecutionEnvironment,
    task: ExternalSystemBeforeRunTask,
  ): Boolean {
    if (configuration is ElideNativeImageRunConfiguration &&
      DefaultDebugExecutor.EXECUTOR_ID == environment.executor.id &&
      !ElideNativeDebugger.hasDebugRunner()
    ) {
      ElideNotifications.notifyNativeDebuggerMissing(environment.project)
      return false
    }

    return super.executeTask(context, configuration, environment, task)
  }

  companion object {
    /** Key identifying Elide before-run tasks; the platform resolves the provider of a task through it. */
    @JvmField val ID: Key<ExternalSystemBeforeRunTask> = Key.create("Elide.BeforeRunTask")

    /** Returns an enabled task building [artifact] in the Elide project at [externalProjectPath]. */
    @JvmStatic fun buildTask(externalProjectPath: String, artifact: String): ExternalSystemBeforeRunTask {
      return ExternalSystemBeforeRunTask(ID, Constants.SYSTEM_ID).apply {
        isEnabled = true
        taskExecutionSettings.externalSystemIdString = Constants.SYSTEM_ID.id
        taskExecutionSettings.externalProjectPath = externalProjectPath
        taskExecutionSettings.taskNames = listOf(ElideCli.BUILD.name, artifact)
      }
    }
  }
}
