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

import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.extensions.PluginId

/**
 * The JetBrains "Native Debugging Support" plugin, which brings the GDB and LLDB backends a Native Image session
 * runs on.
 *
 * It is a marketplace plugin licensed for IntelliJ IDEA Ultimate rather than part of the IDE, so the debug side of
 * this plugin is an optional module that only loads with it.
 */
object ElideNativeDebugger {
  /** ID of the Native Debugging Support plugin, which the advertiser offers to install. */
  @JvmField val PLUGIN_ID: PluginId = PluginId.getId("com.intellij.nativeDebug")

  /**
   * Runner ID of the session the optional module registers; declared here so the probe below needs no class of that
   * module, which only exists once the backend is loaded.
   */
  const val DEBUG_RUNNER_ID: String = "ElideNativeImageDebugRunner"

  /**
   * Whether a real Native Image debug session can be started.
   *
   * This asks whether the runner of the optional module is registered rather than whether the backend plugin is
   * installed: the plugin declares `com.intellij.modules.nativeDebug-plugin-capable`, so an IDE that cannot satisfy
   * that module has it installed and unloaded, and a session claimed on its behalf would never start.
   */
  @JvmStatic fun hasDebugRunner(): Boolean {
    return ProgramRunner.PROGRAM_RUNNER_EP.extensionList.any { it.runnerId == DEBUG_RUNNER_ID }
  }
}
