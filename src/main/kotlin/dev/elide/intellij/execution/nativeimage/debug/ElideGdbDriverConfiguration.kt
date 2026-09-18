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

import com.jetbrains.cidr.execution.debugger.backend.gdb.GDBDriverConfiguration
import java.io.File

/**
 * Runs a debug session on the GDB at [gdb].
 *
 * The executable has to be named: the base configuration falls back to the GDB bundled with a CIDR IDE, and Native
 * Debugging Support ships none — its `bin/gdb` directory holds only the pretty-printers CLion's backend loads.
 */
class ElideGdbDriverConfiguration(private val gdb: File) : GDBDriverConfiguration() {
  override fun getDriverName(): String = DRIVER_NAME
  override fun getGDBExecutablePath(): String = gdb.absolutePath

  private companion object {
    /** Name the Debug window's backend console is labelled with. */
    private const val DRIVER_NAME = "Elide GDB"
  }
}
