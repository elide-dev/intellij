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

import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import dev.elide.intellij.execution.build.ElideSourceLinkFilter
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Starts the binary of a Native Image artifact and renders its output in the Run window.
 *
 * The image is looked up when the process starts rather than when the state is built: the before-launch task that
 * runs `elide build` has finished by then, so this sees the binary the build just produced.
 */
class ElideNativeImageRunState(
  environment: ExecutionEnvironment,
  private val configuration: ElideNativeImageRunConfiguration,
) : CommandLineState(environment) {
  init {
    // a native program still prints source locations — a stack trace of the image, a message of its own — and they
    // are resolved against the project the image was built from, like every other Elide run's
    projectRoot()?.let { root -> consoleBuilder.addFilter(ElideSourceLinkFilter(environment.project, root)) }
  }

  override fun startProcess(): ProcessHandler {
    val launch = configuration.resolveLaunch()

    return KillableColoredProcessHandler(launch.commandLine).also { ProcessTerminatedListener.attach(it) }
  }

  private fun projectRoot(): Path? = try {
    configuration.externalProjectPath?.takeUnless { it.isBlank() }?.let(Path::of)
  } catch (_: InvalidPathException) {
    null
  }
}
