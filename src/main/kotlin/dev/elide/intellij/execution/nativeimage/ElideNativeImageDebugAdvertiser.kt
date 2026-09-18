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
package dev.elide.intellij.execution.nativeimage

import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.AsyncProgramRunner
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.RunContentDescriptor
import dev.elide.intellij.ui.ElideNotifications
import org.jetbrains.concurrency.Promise
import org.jetbrains.concurrency.resolvedPromise

/**
 * Answers the Debug executor for a Native Image run when the debugger backend is missing, and offers to install it.
 *
 * The IDE only shows a "Debug" action where some runner claims the executor, so without this the action would simply
 * be absent on an image built for debugging and nothing would explain why. Claiming it here keeps the action in
 * place and turns it into the one thing that can be done about it.
 *
 * The real session lives in the optional module that loads with Native Debugging Support, whose runner is registered
 * ahead of this one; this one takes itself out of the way as soon as the plugin is there.
 */
class ElideNativeImageDebugAdvertiser : AsyncProgramRunner<RunnerSettings>() {
  override fun getRunnerId(): String = RUNNER_ID

  override fun canRun(executorId: String, profile: RunProfile): Boolean {
    if (DefaultDebugExecutor.EXECUTOR_ID != executorId) return false
    if (profile !is ElideNativeImageRunConfiguration) return false

    return !ElideNativeDebugger.hasDebugRunner()
  }

  override fun execute(environment: ExecutionEnvironment, state: RunProfileState): Promise<RunContentDescriptor?> {
    ElideNotifications.notifyNativeDebuggerMissing(environment.project)

    return resolvedPromise(null)
  }

  internal companion object {
    /** Runner ID of the advertiser. */
    internal const val RUNNER_ID = "ElideNativeImageDebugAdvertiser"
  }
}
