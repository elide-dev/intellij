/*
 *  Copyright (c) 2024-2025 Elide Technologies, Inc.
 *
 *  Licensed under the MIT license (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at
 *
 *    https://opensource.org/license/mit/
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations under the License.
 */

package dev.elide.intellij.project.model

/**
 * Classpath usages accepted by `elide classpath [<source-set>:]<usage>`.
 *
 * There is no `test` usage: test dependencies are resolved by requesting the `compile` usage of the `test` source
 * set, and the IDE dependency scope is derived from the source set type instead.
 */
enum class ElideClasspathUsage {
  TOOLCHAIN,
  PROCESSOR,
  COMPILE,
  MODULES,
  RUNTIME,
  PROVIDED,
}

/** Resolved classpath entries for a source set, as reported by `elide classpath <set>:<usage>`. */
data class ElideClasspath(
  val usage: ElideClasspathUsage,
  val entries: List<String>,
)
