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
package dev.elide.intellij.execution.build

import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.ui.ConsoleViewContentType

/**
 * Output type carried by the lines of the CLI's own progress log.
 *
 * The CLI writes that log on standard error, and both consoles a run can end up in draw standard error in the red
 * they keep for a program that went wrong: the Run console reads the type each line arrives with, and the build
 * view's console reads whether the type is standard output. Basing this one on standard output is what makes both
 * of them draw the log as ordinary text, and registering it keeps the Run console from falling back to the system
 * colour — and logging a warning — for every line of an unknown type.
 *
 * A type of its own, rather than standard output itself, is what still tells the CLI's account of the build apart
 * from what the program under `elide run` prints.
 */
internal val ELIDE_PROGRESS_OUTPUT: ProcessOutputType =
  ProcessOutputType("Elide progress", ProcessOutputType.STDOUT).also {
    ConsoleViewContentType.registerNewConsoleViewType(it, ConsoleViewContentType.NORMAL_OUTPUT)
  }
