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
package dev.elide.intellij.tasks

import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationEvent
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.task.ExternalSystemTaskManager
import com.intellij.openapi.progress.runBlockingCancellable
import dev.elide.intellij.Constants
import dev.elide.intellij.InvalidElideHomeException
import dev.elide.intellij.cli.ElideCommandLine
import dev.elide.intellij.settings.ElideExecutionSettings
import dev.elide.intellij.ui.ElideNotifications
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlin.io.path.Path

/** Background task manager for long-running operations, such as dependency sync and project builds. */
class ElideTaskManager : ExternalSystemTaskManager<ElideExecutionSettings> {
  private val runningTasks = ConcurrentHashMap<ExternalSystemTaskId, Job>()

  override fun executeTasks(
    projectPath: String,
    id: ExternalSystemTaskId,
    settings: ElideExecutionSettings,
    listener: ExternalSystemTaskNotificationListener
  ) {
    // lifecycle events (start/success/failure/end) are emitted by the platform task wrapper; emitting them here as
    // well duplicates every build tool window event
    runBlockingCancellable {
      runningTasks[id] = coroutineContext.job
      try {
        val elide = ElideCommandLine.at(settings.elideHome, Path(projectPath))

        // `taskNames` is the argument vector of a *single* Elide invocation ("run", "src/main.kt"), the same shape
        // `ElideRunConfiguration.rawCommandLine` parses and joins; running each element on its own would turn one
        // command line into several bogus commands
        val arguments = settings.tasks.filter { it.isNotBlank() }
        if (arguments.isEmpty()) return@runBlockingCancellable

        listener.onStatusChange(
          ExternalSystemTaskNotificationEvent(id, Constants.Strings["tasks.executing", arguments.joinToString(" ")]),
        )

        // NOTE: the `ProcessOutputType` overload only exists from build 253 onward
        @Suppress("DEPRECATION")
        elide(args = arguments.toTypedArray(), environment = settings.env) { line, stderr ->
          listener.onTaskOutput(id, line, !stderr)
        }
      } catch (cause: InvalidElideHomeException) {
        ElideNotifications.notifyInvalidElideHome(id.findProject())
        throw cause
      } finally {
        runningTasks.remove(id)
      }
    }
  }

  override fun cancelTask(taskId: ExternalSystemTaskId, listener: ExternalSystemTaskNotificationListener): Boolean {
    val job = runningTasks.remove(taskId) ?: return false

    // cancelling the job unwinds `ElideCommandLine.invoke`, which destroys the CLI process it owns
    job.cancel()

    return true
  }
}
