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
package dev.elide.intellij.project.model

import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.XCollection
import java.io.Serializable

/**
 * A task in a project's build graph, as listed by `elide build --inspect`.
 *
 * A task's [name] is exactly what `elide build` accepts as a positional target. Only some tasks come from the
 * manifest verbatim (an `artifacts` entry is a task named after itself); the rest are derived by the CLI from source
 * sets, dependencies and entrypoints (`compile-kotlin-main`, `maven-dependencies`, `run-app`, …), so the list is read
 * from the CLI rather than computed from the manifest.
 *
 * Instances travel both in the resolved project node ([ElideProjectData], Java serialization) and in the persisted
 * project index ([ElideProjectInfo], XML), hence both the [Serializable] marker and the XML annotations.
 */
data class ElideBuildTaskInfo(
  @Attribute val name: String = "",
  @Attribute val description: String = "",
  /** Options the task declares; the CLI accepts them once the task is named as a target. */
  @XCollection val options: List<Option> = emptyList(),
) : Serializable {
  /**
   * An option a task declares.
   *
   * The listing prints the option exactly as it is written on the command line, dashes included, and says nothing
   * about whether it takes a value, so [option] is the whole of what the CLI reveals about its shape.
   */
  data class Option(
    @Attribute val option: String = "",
    @Attribute val description: String = "",
  ) : Serializable {
    private companion object {
      private const val serialVersionUID: Long = 1L
    }
  }

  private companion object {
    private const val serialVersionUID: Long = 1L
  }
}
