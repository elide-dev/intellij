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
import com.intellij.openapi.util.io.toCanonicalPath
import dev.elide.project.manifest.argValue
import dev.elide.project.manifest.collect
import dev.elide.project.manifest.explicitOrNull
import dev.elide.tooling.manifest.nativeimage.ImageType
import dev.elide.tooling.manifest.nativeimage.NativeImage
import java.io.Serializable

/**
 * Manifest facts the post-import [data service][dev.elide.intellij.project.ElideProjectDataService] needs, attached
 * to the resolved project node — one node per project a sync resolved, so a workspace attaches one for its root and
 * one for every member.
 *
 * This is deliberately a flat, [Serializable] DTO of plain types rather than the generated manifest model: external
 * system [DataNode][com.intellij.openapi.externalSystem.model.DataNode] payloads are serialized by the platform (for
 * out-of-process resolution and the import cache), and the generated model classes are not serializable.
 */
data class ElideProjectData(
  /** Directory of the project this data describes, which is what indexes and keys it. */
  val projectPath: String = "",
  /** Name the manifest declares for the project; part of how build outputs are named. */
  val name: String? = null,
  val kotlin: KotlinFacetData? = null,
  val entrypoints: List<String> = emptyList(),
  val jvmMainClass: String? = null,
  val scripts: List<String> = emptyList(),
  /** Tasks the CLI lists for the project's build graph; see [ElideBuildTaskInfo]. */
  val buildTasks: List<ElideBuildTaskInfo> = emptyList(),
  /** Artifacts producing a runnable Native Image binary; see [ElideNativeImageInfo]. */
  val nativeImages: List<ElideNativeImageInfo> = emptyList(),
  /** Directory of the workspace root this project is a member of, or `null` when it is a member of none. */
  val workspaceRoot: String? = null,
  /** Directories of the members this project is the workspace root of, empty when it is the root of none. */
  val members: List<String> = emptyList(),
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

    /**
     * Collect the manifest facts of one [project] of [workspace] into a serializable payload.
     *
     * [buildTasks] cannot be derived from the manifest: the CLI owns the build graph, so the resolver reads the task
     * list from it and passes the whole listing through here. The workspace root takes it verbatim, since a build
     * started there accepts every project's targets; a member takes its own targets, unqualified, which is how the
     * CLI accepts them when it is the project in focus.
     */
    @JvmStatic fun from(
      project: ElideResolvedProject,
      workspace: ElideResolvedWorkspace,
      buildTasks: List<ElideBuildTaskInfo> = emptyList(),
    ): ElideProjectData {
      val manifest = project.manifest
      val isRoot = project.root == workspace.root.root

      return ElideProjectData(
        projectPath = project.root.toCanonicalPath(),
        name = manifest.name,
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
        buildTasks = if (isRoot) buildTasks else buildTasksOf(project.name, buildTasks),
        nativeImages = manifest.artifacts.mapNotNull { (key, artifact) ->
          (artifact as? NativeImage)?.takeIf { it.type == ImageType.Binary }?.let { image ->
            ElideNativeImageInfo(artifact = key, outputName = image.name)
          }
        },
        workspaceRoot = if (isRoot) null else workspace.root.root.toCanonicalPath(),
        members = if (isRoot) workspace.members.map { it.root.toCanonicalPath() } else emptyList(),
      )
    }
  }
}
