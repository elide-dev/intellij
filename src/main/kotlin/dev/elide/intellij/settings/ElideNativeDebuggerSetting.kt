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
package dev.elide.intellij.settings

/**
 * Describes the backend a debug session of a Native Image binary runs on.
 *
 * Only GDB reads the debug info GraalVM emits: the DWARF it writes, and the pretty-printer script that renders JVM
 * values, are both GDB's. LLDB still attaches and steps through machine code, which is all either backend can do on
 * a platform where GraalVM emits no debug info at all.
 */
enum class ElideNativeDebuggerSetting {
  /** Use GDB when one is configured or on `PATH`, and LLDB otherwise. */
  Auto,

  /** Always use GDB, failing the session when none can be found. */
  Gdb,

  /** Always use LLDB: the one the IDE distribution carries, or the system's when it carries none. */
  Lldb,
}
