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

import com.intellij.debugger.ui.breakpoints.JavaLineBreakpointType
import com.intellij.execution.ExecutionException
import com.intellij.execution.filters.TextConsoleBuilder
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.breakpoints.XBreakpointHandler
import com.jetbrains.cidr.execution.RunParameters
import com.jetbrains.cidr.execution.debugger.CidrLocalDebugProcess
import com.jetbrains.cidr.execution.debugger.backend.DebuggerCommandException
import com.jetbrains.cidr.execution.debugger.backend.DebuggerDriver
import com.jetbrains.cidr.execution.debugger.backend.gdb.GDBDriver
import com.jetbrains.cidr.execution.debugger.breakpoints.CidrBreakpointHandler
import dev.elide.intellij.Constants.Strings
import dev.elide.intellij.execution.nativeimage.ElideNativeImageLaunch
import org.jetbrains.kotlin.idea.debugger.breakpoints.KotlinLineBreakpointType
import java.nio.file.Files

/**
 * Debug session over a Native Image binary, running on the GDB or LLDB backend of Native Debugging Support.
 *
 * Two things make the session about the program rather than about the machine code it was compiled to. Ordinary Java
 * and Kotlin line breakpoints are registered in the backend, so the gutter a user already clicks in keeps working
 * and hits map back to the source; and, under GDB, the debug info GraalVM wrote next to the image is loaded — the
 * source tree frames resolve against, and the script that renders JVM values instead of raw memory.
 */
class ElideNativeImageDebugProcess(
  parameters: RunParameters,
  session: XDebugSession,
  consoleBuilder: TextConsoleBuilder,
  private val launch: ElideNativeImageLaunch,
) : CidrLocalDebugProcess(parameters, session, consoleBuilder) {
  /**
   * Handlers registering JVM line breakpoints with the backend.
   *
   * A Native Image is compiled JVM code, so the breakpoints of interest sit in `.kt` and `.java` files, whose line
   * breakpoint types belong to the Java and Kotlin debuggers. Handing them to the backend's own handler is what
   * turns a click in the gutter into a `break file:line` and keeps a single kind of breakpoint in the UI.
   */
  private val jvmBreakpoints: List<CidrBreakpointHandler> = listOf(
    CidrBreakpointHandler(this, JavaLineBreakpointType::class.java),
    CidrBreakpointHandler(this, KotlinLineBreakpointType::class.java),
  )

  override fun getBreakpointHandlers(): Array<XBreakpointHandler<*>> {
    return super.getBreakpointHandlers() + jvmBreakpoints
  }

  /**
   * Resolves a stop against the JVM handlers before the backend's own.
   *
   * The base implementation only knows the four handlers it creates itself — line, address, exception and symbolic —
   * and would treat a hit on a Kotlin or Java breakpoint as an unknown stop.
   */
  override fun handleBreakpoint(stopPlace: DebuggerDriver.StopPlace, id: Int) {
    val breakpoint = jvmBreakpoints.firstNotNullOfOrNull { it.getXBreakpoint(id) }
    if (breakpoint != null) return handleCodepoint(stopPlace, breakpoint)

    super.handleBreakpoint(stopPlace, id)
  }

  override fun doLoadTarget(driver: DebuggerDriver): DebuggerDriver.Inferior {
    val inferior = super.doLoadTarget(driver)
    if (driver !is GDBDriver) return inferior

    // GraalVM writes paths into the DWARF relative to the image directory and expects the debugger to run there,
    // which the IDE does not; naming the source tree is what lets frames resolve to files
    if (Files.isDirectory(launch.sources)) {
      execute(driver, "set directories ${quote(launch.sources.toString())}")
    }

    // the script teaches GDB to render JVM values — a String as its characters, an object as its fields — instead of
    // the raw memory a native frame would otherwise show
    if (Files.isRegularFile(launch.gdbHelpers)) {
      execute(driver, "source ${quote(launch.gdbHelpers.toString())}")
    }

    return inferior
  }

  /**
   * Runs [command] on [driver], reporting a rejection in the session's console instead of failing the launch.
   *
   * Neither command is required for the session to run: without them it is a machine-level one, which is what a
   * build carrying no debug info gives anyway.
   */
  private fun execute(driver: GDBDriver, command: String) {
    try {
      driver.executeInterpreterCommand(command)
    } catch (e: DebuggerCommandException) {
      reportSetupFailure(command, e)
    } catch (e: ExecutionException) {
      reportSetupFailure(command, e)
    }
  }

  private fun reportSetupFailure(command: String, cause: Exception) {
    printlnToConsole(Strings["execution.nativeImage.debug.setupFailed", command, cause.message.orEmpty()])
  }

  private fun quote(path: String): String = "\"$path\""
}
