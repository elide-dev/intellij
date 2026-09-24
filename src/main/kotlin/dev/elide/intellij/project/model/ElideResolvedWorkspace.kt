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

import dev.elide.project.manifest.projectName
import dev.elide.tooling.manifest.project.ProjectModule
import java.nio.file.Path

/**
 * One Elide project a sync resolved: the manifest found at [root], and the classpath of each of its source sets.
 *
 * [name] is the name Elide knows the project by — the one its manifest declares, falling back to its directory. It is
 * the name a sibling's `project(…)` reference resolves against and the scope the CLI qualifies the project's build
 * tasks with, so it is carried here rather than re-derived at each use.
 */
data class ElideResolvedProject(
  val name: String,
  val root: Path,
  val manifest: ProjectModule,
  val classpaths: Map<String, ElideClasspath> = emptyMap(),
) {
  companion object {
    /** Returns the project the [manifest] at [root] describes, named the way Elide names it. */
    @JvmStatic fun of(
      root: Path,
      manifest: ProjectModule,
      classpaths: Map<String, ElideClasspath> = emptyMap(),
    ): ElideResolvedProject = ElideResolvedProject(
      name = manifest.projectName(root.fileName?.toString() ?: root.toString()),
      root = root,
      manifest = manifest,
      classpaths = classpaths,
    )
  }
}

/**
 * The projects one sync resolved: the linked project, plus the members its manifest declares.
 *
 * A standalone project is a workspace of one, so the whole model is built from this regardless of whether the linked
 * manifest declares members. Elide workspaces are exactly two layers deep — a member declares no members of its own —
 * which is why [members] is a flat list rather than a tree.
 */
data class ElideResolvedWorkspace(
  val root: ElideResolvedProject,
  val members: List<ElideResolvedProject> = emptyList(),
) {
  /** Every project of the workspace, the root first and the members in declaration order. */
  val projects: List<ElideResolvedProject> get() = listOf(root) + members

  /** Whether the linked manifest declares members, and this is therefore a multi-project workspace. */
  val isWorkspace: Boolean get() = members.isNotEmpty()

  /** Returns the project Elide knows by [name], root included, or `null` when the workspace holds none. */
  fun project(name: String): ElideResolvedProject? = projects.find { it.name == name }

  companion object {
    /** Returns a workspace of one: the [manifest] at [root], declaring no members. */
    @JvmStatic fun of(
      root: Path,
      manifest: ProjectModule,
      classpaths: Map<String, ElideClasspath> = emptyMap(),
    ): ElideResolvedWorkspace = ElideResolvedWorkspace(ElideResolvedProject.of(root, manifest, classpaths))
  }
}
