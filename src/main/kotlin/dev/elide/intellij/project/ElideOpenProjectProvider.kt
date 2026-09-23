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
package dev.elide.intellij.project

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.externalSystem.importing.AbstractOpenProjectProvider
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.service.project.trusted.ExternalSystemTrustedProjectDialog
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.toCanonicalPath
import com.intellij.openapi.vfs.VirtualFile
import dev.elide.intellij.Constants
import dev.elide.intellij.cli.ElideCommandLine
import dev.elide.intellij.cli.manifest
import dev.elide.intellij.service.ElideDistributionResolver
import dev.elide.intellij.settings.ElideProjectSettings
import dev.elide.intellij.ui.ElideNotifications
import dev.elide.project.manifest.workspaceMembers
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlin.io.path.isRegularFile

/**
 * Service used to link an Elide project with the IDE, enabling auto-import, sync, and other features.
 *
 * The base class is experimental across the whole supported build range, and is the only entry point for linking an
 * external system project; see `docs/PLATFORM_APIS.md`.
 */
@Suppress("UnstableApiUsage") class ElideOpenProjectProvider : AbstractOpenProjectProvider() {
  override val systemId: ProjectSystemId = Constants.SYSTEM_ID

  override fun isProjectFile(file: VirtualFile): Boolean = !file.isDirectory && file.name == Constants.MANIFEST_NAME

  override suspend fun linkProject(projectFile: VirtualFile, project: Project) {
    // the directory is derived here rather than through `AbstractOpenProjectProvider.getProjectDirectory`, which is
    // internal API
    val projectDir = if (projectFile.isDirectory) projectFile else projectFile.parent ?: return
    val projectPath = projectDir.toNioPath()

    if (!ExternalSystemTrustedProjectDialog.confirmLinkingUntrustedProjectAsync(
        project = project,
        systemId = Constants.SYSTEM_ID,
        projectRoot = projectPath,
      )
    ) return

    val settings = ElideProjectSettings()
    settings.externalProjectPath = projectPath.toCanonicalPath()

    ExternalSystemUtil.linkExternalProject(
      /* projectSettings = */ settings,
      /* importSpec = */ ImportSpecBuilder(project, Constants.SYSTEM_ID)
        .use(ProgressExecutionMode.IN_BACKGROUND_ASYNC),
    )

    // linking a member on its own imports a project Elide would never build on its own; the sync is still started,
    // since it produces something usable, but the workspace above it is worth pointing at
    val elideHome = ElideDistributionResolver.getElideHome(project, projectPath.toCanonicalPath())

    enclosingWorkspaceRoot(elideHome, projectPath)?.let { workspaceRoot ->
      ElideNotifications.notifyWorkspaceMember(project, projectPath, workspaceRoot)
    }
  }
}

private val LOG = Logger.getInstance(ElideOpenProjectProvider::class.java)

/**
 * Returns the root of the Elide workspace claiming [projectPath] as one of its members, or `null` when no directory
 * above it does.
 *
 * The manifests are read through the CLI at [elideHome] rather than parsed here: a `workspace.members` entry is the
 * result of evaluating Pkl, which can compute the list, and the CLI is the same evaluator the build uses. Only an
 * ancestor that actually holds a manifest is inspected, so the walk costs a handful of file lookups for the usual
 * project that has no workspace above it.
 *
 * Both spellings of the member's path are compared, because a manifest resolves its members against the root's own
 * directory while the IDE hands over the path the user opened, and the two differ whenever a symlink is on the way
 * (`/tmp` is `/private/tmp` on macOS).
 */
internal suspend fun enclosingWorkspaceRoot(elideHome: Path, projectPath: Path): Path? {
  val member = projectPath.toAbsolutePath().normalize()
  val spellings = setOf(member, runCatching { member.toRealPath() }.getOrDefault(member))

  var candidate = member.parent
  while (candidate != null) {
    val ancestor = candidate
    candidate = ancestor.parent

    if (!ancestor.resolve(Constants.MANIFEST_NAME).isRegularFile()) continue

    val manifest = try {
      ElideCommandLine.at(elideHome, ancestor).manifest()
    } catch (cause: CancellationException) {
      throw cause
    } catch (cause: Exception) {
      // a manifest the CLI cannot read describes no workspace this project is part of, as far as anything here can
      // tell; the sync of the project actually being linked reports its own failures
      LOG.debug("Failed to read the manifest of '$ancestor' while looking for a workspace", cause)
      continue
    }

    if (manifest.workspaceMembers.any { ancestor.resolve(it).normalize() in spellings }) return ancestor
  }

  return null
}
