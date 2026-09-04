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

import com.intellij.openapi.externalSystem.model.Key
import dev.elide.project.manifest.argValue
import dev.elide.project.manifest.collect
import dev.elide.project.manifest.explicitOrNull
import dev.elide.tooling.manifest.project.ProjectModule
import java.io.Serializable

/**
 * Manifest facts the post-import [data service][dev.elide.intellij.project.ElideProjectDataService] needs, attached
 * to the resolved project node.
 *
 * This is deliberately a flat, [Serializable] DTO of plain types rather than the generated manifest model: external
 * system [DataNode][com.intellij.openapi.externalSystem.model.DataNode] payloads are serialized by the platform (for
 * out-of-process resolution and the import cache), and the generated model classes are not serializable.
 */
data class ElideProjectData(
  val kotlin: KotlinFacetData? = null,
  val entrypoints: List<String> = emptyList(),
  val jvmMainClass: String? = null,
  val scripts: List<String> = emptyList(),
) : Serializable {
  /** Kotlin facet configuration derived from the manifest's `kotlin` block. */
  data class KotlinFacetData(
    val apiLevel: String? = null,
    val languageLevel: String? = null,
    val compilerArguments: List<String> = emptyList(),
  ) : Serializable

  companion object {
    private const val serialVersionUID: Long = 1L

    /** Key used to store [ElideProjectData] in a project node during resolution. */
    @JvmField val PROJECT_KEY: Key<ElideProjectData> = Key.create(ElideProjectData::class.java, 100)

    /** Collect the manifest facts needed after import into a serializable payload. */
    @JvmStatic fun from(manifest: ProjectModule): ElideProjectData = ElideProjectData(
      kotlin = manifest.kotlin?.let { kotlin ->
        KotlinFacetData(
          apiLevel = kotlin.apiLevel.explicitOrNull()?.argValue,
          languageLevel = kotlin.languageLevel.explicitOrNull()?.argValue,
          compilerArguments = kotlin.compilerOptions.collect().toList(),
        )
      },
      entrypoints = manifest.entrypoint.orEmpty(),
      jvmMainClass = manifest.jvm?.main,
      scripts = manifest.scripts.keys.toList(),
    )
  }
}
