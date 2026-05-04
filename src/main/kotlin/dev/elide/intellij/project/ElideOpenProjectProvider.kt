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
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.service.project.trusted.ExternalSystemTrustedProjectDialog
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.toCanonicalPath
import com.intellij.openapi.vfs.VirtualFile
import dev.elide.intellij.Constants
import dev.elide.intellij.settings.ElideProjectSettings

/** Service used to link an Elide project with the IDE, enabling auto-import, sync, and other features. */
@Suppress("UnstableApiUsage") class ElideOpenProjectProvider : AbstractOpenProjectProvider() {
  override val systemId: ProjectSystemId = Constants.SYSTEM_ID

  override fun isProjectFile(file: VirtualFile): Boolean = !file.isDirectory && file.name == Constants.MANIFEST_NAME

  override suspend fun linkProject(projectFile: VirtualFile, project: Project) {
    val projectPath = getProjectDirectory(projectFile).toNioPath()
    if (!ExternalSystemTrustedProjectDialog.confirmLinkingUntrustedProjectAsync(
        project = project,
        systemId = Constants.SYSTEM_ID,
        projectRoot = projectPath,
      )
    ) return

    val settings = ElideProjectSettings()
    settings.externalProjectPath = projectPath.toCanonicalPath()

    val importSpec = ImportSpecBuilder(project, systemId)
      .withPreviewMode(false)
      .use(ProgressExecutionMode.IN_BACKGROUND_ASYNC)
      .build()
    
    ExternalSystemUtil.linkExternalProject(settings, importSpec)
  }
}
