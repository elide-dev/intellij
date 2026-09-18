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

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.jetbrains.cidr.execution.debugger.backend.DebuggerDriverConfiguration
import com.jetbrains.cidr.execution.debugger.backend.lldb.LLDBDriverConfiguration
import dev.elide.intellij.Constants.Strings
import dev.elide.intellij.settings.ElideNativeDebuggerSetting
import dev.elide.intellij.settings.ElideProjectSettings
import dev.elide.intellij.settings.ElideSettings
import java.io.File

/** Picks the backend a Native Image debug session runs on, following the project's settings. */
object ElideNativeDebuggers {
  /**
   * Returns the backend configured for the Elide project at [externalProjectPath] of [project].
   *
   * @throws ExecutionException When the backend the project's settings select cannot be run on this machine.
   */
  @JvmStatic fun driver(project: Project, externalProjectPath: String): DebuggerDriverConfiguration {
    return driver(settings(project, externalProjectPath))
  }

  /**
   * Returns the backend [settings] select.
   *
   * `Auto` prefers GDB because it is the only backend that reads what GraalVM emits — the DWARF of a `-g` build and
   * the pretty-printers rendering JVM values are GDB's — and falls back to LLDB, which still attaches and steps
   * through machine code.
   *
   * @throws ExecutionException When the backend the settings select cannot be run on this machine.
   */
  @JvmStatic fun driver(settings: ElideProjectSettings): DebuggerDriverConfiguration {
    // the GDB path is not consulted for an LLDB session, and the settings UI hides the field there: a stale value
    // left behind by an earlier GDB run must not fail a session that never looks at it
    if (settings.nativeDebugger == ElideNativeDebuggerSetting.Lldb) {
      return lldb() ?: throw ExecutionException(Strings["execution.nativeImage.error.noLldb"])
    }

    val configured = settings.gdbPath.takeUnless { it.isBlank() }?.let(::File)

    // a path the user typed is never quietly ignored: a typo would otherwise read as "no gdb on PATH", which names
    // neither the setting nor the file that is wrong
    if (configured != null && !configured.canExecute()) {
      throw ExecutionException(Strings["execution.nativeImage.error.badGdb", configured.path])
    }

    val gdb = configured ?: PathEnvironmentVariableUtil.findExecutableInPathOnAnyOS(GDB_EXECUTABLE)

    return when (settings.nativeDebugger) {
      ElideNativeDebuggerSetting.Gdb -> ElideGdbDriverConfiguration(
        gdb ?: throw ExecutionException(Strings["execution.nativeImage.error.noGdb"]),
      )
      else -> gdb?.let(::ElideGdbDriverConfiguration)
        ?: lldb()
        ?: throw ExecutionException(Strings["execution.nativeImage.error.noDebugger"])
    }
  }

  /**
   * Returns an LLDB backend, or `null` when this machine has none the IDE can drive.
   *
   * Native Debugging Support ships only the `LLDBFrontend` the IDE talks to; the LLDB behind it comes from the IDE
   * distribution, and only a CIDR IDE — CLion, RustRover — carries one. On IntelliJ IDEA the session therefore runs
   * on the system LLDB, named here as the backend's custom path. Which library that resolves to is the backend's own
   * business, and deliberately left to it: it asks `xcode-select` for the LLDB of the selected Xcode or of the
   * Command Line Tools on macOS, and finds the `liblldb` next to the executable on Linux, both when the session
   * starts rather than here, where a program runner is called on the UI thread.
   *
   * Windows is left out: the frontend rejects a custom LLDB there, so an IDE bundling none has no backend at all.
   */
  private fun lldb(): LLDBDriverConfiguration? {
    if (LLDBDriverConfiguration.hasBundledLLDB()) return LLDBDriverConfiguration()
    if (SystemInfo.isWindows) return null

    val lldb = systemLldb() ?: return null

    return LLDBDriverConfiguration().apply { setCustomLLDBPath(lldb.absolutePath) }
  }

  /** The system LLDB, preferring the well-known location the backend resolves an installation through. */
  private fun systemLldb(): File? {
    val shim = File(LLDB_PATH)

    return shim.takeIf { it.canExecute() }
      ?: PathEnvironmentVariableUtil.findExecutableInPathOnAnyOS(LLDB_EXECUTABLE)
  }

  /** Settings of the linked project at [externalProjectPath], or the defaults when it is not linked. */
  private fun settings(project: Project, externalProjectPath: String): ElideProjectSettings {
    return ElideSettings.getSettings(project).getLinkedProjectSettings(externalProjectPath) ?: ElideProjectSettings()
  }

  /** Name of the GDB executable looked up on `PATH`. */
  private const val GDB_EXECUTABLE = "gdb"

  /** Name of the LLDB executable looked up on `PATH` when the well-known location holds none. */
  private const val LLDB_EXECUTABLE = "lldb"

  /**
   * Location of the system LLDB.
   *
   * This is the path macOS carries whenever developer tools are installed, and the one the backend answers with the
   * LLDB of the selected Xcode for; on Linux it is where a distribution's own package puts it.
   */
  private const val LLDB_PATH = "/usr/bin/lldb"
}
