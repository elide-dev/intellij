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

import com.intellij.openapi.externalSystem.importing.AbstractOpenProjectProvider
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.service.project.trusted.ExternalSystemTrustedProjectDialog
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.toCanonicalPath
import com.intellij.openapi.vfs.VirtualFile
import dev.elide.intellij.Constants
import dev.elide.intellij.settings.ElideProjectSettings

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
    // the directory is derived here rather than through `AbstractOpenProjectProvider.getProjectDirectory`, whose
    // suspending form only exists from build 253; 251 and 252 declare a blocking one
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

    // NOTE: the `ImportSpec` overload of `linkExternalProject` only exists from build 252
    @Suppress("DEPRECATION")
    ExternalSystemUtil.linkExternalProject(
      /* externalSystemId = */ Constants.SYSTEM_ID,
      /* projectSettings = */ settings,
      /* project = */ project,
      /* importResultCallback = */ { },
      /* isPreviewMode = */ false,
      /* progressExecutionMode = */ ProgressExecutionMode.IN_BACKGROUND_ASYNC,
    )
  }
}
