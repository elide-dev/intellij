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
package dev.elide.intellij.execution.nativeimage.debug

import com.intellij.execution.configurations.GeneralCommandLine
import com.jetbrains.cidr.execution.Installer
import com.jetbrains.cidr.execution.RunParameters
import com.jetbrains.cidr.execution.TrivialInstaller
import com.jetbrains.cidr.execution.debugger.backend.DebuggerDriverConfiguration

/**
 * What the CIDR debugger needs to start a session on a Native Image binary: the command line to launch and the
 * backend to launch it under.
 *
 * The image is already on disk by the time this is built — the before-launch task assembled it — so there is nothing
 * to deploy and the installer is the trivial one.
 */
class ElideNativeImageRunParameters(
  private val commandLine: GeneralCommandLine,
  private val driver: DebuggerDriverConfiguration,
) : RunParameters() {
  override fun getInstaller(): Installer = TrivialInstaller(commandLine)
  override fun getDebuggerDriverConfiguration(): DebuggerDriverConfiguration = driver

  /** The image is built for the host, so the backend's own default architecture is the right one. */
  override fun getArchitectureId(): String? = null
}
