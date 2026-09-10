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

import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationEvent
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.model.task.event.ExternalSystemBuildEvent
import com.intellij.openapi.externalSystem.task.ExternalSystemTaskManager
import com.intellij.openapi.progress.runBlockingCancellable
import dev.elide.intellij.Constants
import dev.elide.intellij.InvalidElideHomeException
import dev.elide.intellij.cli.ElideCommandLine
import dev.elide.intellij.execution.ElideRunConfiguration
import dev.elide.intellij.execution.build.ELIDE_PROGRESS_OUTPUT
import dev.elide.intellij.execution.build.ElideBuildEventPublisher
import dev.elide.intellij.project.model.buildCommandLine
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
        val workDir = Path(projectPath)
        val elide = ElideCommandLine.at(settings.elideHome, workDir)

        // `taskNames` is either the argument vector of a *single* Elide invocation ("run", "src/main.kt"), the same
        // shape `ElideRunConfiguration.rawCommandLine` parses and joins — running each element on its own would turn
        // one command line into several bogus commands — or the build targets the external system itself asks for by
        // name: the tool window's "Run", task activation and keymap shortcuts all execute a task by the name it
        // carries in the project model, and those reach the CLI through `build`
        val tasks = settings.tasks.filter { it.isNotBlank() }
        val arguments = buildCommandLine(tasks) ?: tasks
        if (arguments.isEmpty()) return@runBlockingCancellable

        listener.onStatusChange(
          ExternalSystemTaskNotificationEvent(id, Constants.Strings["tasks.executing", arguments.joinToString(" ")]),
        )

        // the CLI reports every step of the build it runs on standard error, which becomes a node of the build
        // tree; standard output carries what the program under `run` prints, and is left alone
        val progress = ElideBuildEventPublisher(id, workDir, System.currentTimeMillis()) { event ->
          listener.onStatusChange(ExternalSystemBuildEvent(id, event))
        }

        // a TAP run is the exception: `ElideTestsExecutionConsoleManager` tells the run's TAP stream from the CLI's
        // log by the stream each arrived on, which is all the build event dispatcher passes on, so its log keeps
        // the type of the stream it was written to
        val tap = ElideRunConfiguration.emitsTap(arguments)

        try {
          elide(args = arguments.toTypedArray(), environment = settings.env) { line, stderr ->
            val claimed = stderr && progress.accept(line)
            listener.onTaskOutput(id, line, outputType(stderr, progress = claimed && !tap))
          }
        } finally {
          // a failed or cancelled run reports the reason on its way out, and that reason is the last thing the log
          // holds: publishing it is what puts a build that never got as far as a step on the tree
          progress.flush()
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

  /**
   * Type one line of output is reported with: the CLI's own log carries a type of its own, so the consoles draw it
   * as ordinary text rather than as the error output the stream it arrives on would otherwise make it.
   */
  private fun outputType(stderr: Boolean, progress: Boolean): ProcessOutputType = when {
    progress -> ELIDE_PROGRESS_OUTPUT
    stderr -> ProcessOutputType.STDERR
    else -> ProcessOutputType.STDOUT
  }
}
