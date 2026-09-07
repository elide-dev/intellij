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
package dev.elide.intellij.wizard

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.service.project.manage.ExternalProjectsManagerImpl
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.platform.ide.progress.withBackgroundProgress
import dev.elide.intellij.Constants
import dev.elide.intellij.ElidePluginException
import dev.elide.intellij.cli.ElideCommandLine
import dev.elide.intellij.cli.init
import dev.elide.intellij.settings.ElideProjectSettings
import dev.elide.intellij.ui.ElideNotifications
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Project service generating a new project's files with `elide init` and linking the result as an Elide project.
 *
 * This runs after the project window is open, so the CLI's progress and any failure it reports have somewhere to go;
 * the project starts out empty and gains its modules from the import that follows generation.
 */
@Service(Service.Level.PROJECT)
class ElideProjectGenerator(private val project: Project, private val scope: CoroutineScope) {
  /**
   * Generate [templateId] into [projectDir] using the Elide distribution at [elideHome], answering the template's
   * questionnaire with [answers], then link the generated project with [settings].
   */
  fun generate(
    projectDir: Path,
    elideHome: Path,
    templateId: String,
    answers: Map<String, String>,
    settings: ElideProjectSettings,
  ): Job = scope.launch {
    try {
      withBackgroundProgress(project, Constants.Strings["wizard.progress.generating", templateId]) {
        ElideCommandLine.at(elideHome, workDir = projectDir).init(templateId, answers)
      }
    } catch (failure: ElidePluginException) {
      ElideNotifications.notifyProjectGenerationFailed(project, failure.message.orEmpty())
      return@launch
    }

    // the manifest has to be visible to the VFS before the import looks for it
    VfsUtil.markDirtyAndRefresh(false, true, true, projectDir.toFile())

    withContext(Dispatchers.EDT) {
      ExternalProjectsManagerImpl.getInstance(project).runWhenInitialized {
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
  }
}
