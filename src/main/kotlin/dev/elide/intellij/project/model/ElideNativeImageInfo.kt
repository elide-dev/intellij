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
import java.io.Serializable

/**
 * A Native Image artifact `elide build` turns into a runnable binary.
 *
 * Only binary images are described: a library image produces a shared object the IDE has nothing to launch, so the
 * resolver leaves it out entirely and the presence of an entry is what marks an artifact as runnable.
 *
 * Instances travel both in the resolved project node ([ElideProjectData], Java serialization) and in the persisted
 * project index ([ElideProjectInfo], XML), hence both the [Serializable] marker and the XML annotations.
 */
data class ElideNativeImageInfo(
  /** Key the artifact is declared under in the manifest, which is what `elide build` takes as a target. */
  @Attribute val artifact: String = "",
  /** The `name` the artifact declares, which names the binary; `null` leaves the name to the project. */
  @Attribute val outputName: String? = null,
) : Serializable {
  private companion object {
    private const val serialVersionUID: Long = 1L
  }
}
