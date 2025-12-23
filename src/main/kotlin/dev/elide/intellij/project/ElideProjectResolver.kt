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
import com.intellij.openapi.externalSystem.importing.ProjectResolverPolicy
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationEvent
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.service.project.ExternalSystemProjectResolver
import com.intellij.openapi.progress.runBlockingCancellable
import dev.elide.intellij.Constants
import dev.elide.intellij.InvalidElideHomeException
import dev.elide.intellij.cli.ElideCommandLine
import dev.elide.intellij.cli.classpath
import dev.elide.intellij.cli.install
import dev.elide.intellij.cli.manifest
import dev.elide.intellij.project.model.ElideClasspathUsage
import dev.elide.intellij.project.model.ElideProjectModel
import dev.elide.intellij.service.ElideDistributionResolver
import dev.elide.intellij.settings.ElideExecutionSettings
import dev.elide.intellij.ui.ElideNotifications
import org.jetbrains.annotations.PropertyKey
import kotlin.io.path.Path
import kotlin.io.path.notExists

/**
 * A service capable of using the Elide manifest and lockfile to build a project model that can be understood by the
 * IDE. Generally, the model can be built without calling the Elide CLI; however, in cases where the lockfile is out of
 * date, or dependencies are not installed, a command invocation will take place in a background task.
 */
class ElideProjectResolver : ExternalSystemProjectResolver<ElideExecutionSettings> {
  private fun ExternalSystemTaskNotificationListener.onStep(taskId: ExternalSystemTaskId, text: String) {
    onStatusChange(ExternalSystemTaskNotificationEvent(taskId, text))
  }

  private fun progressMessage(@PropertyKey(resourceBundle = "i18n.Strings") key: String): String {
    return Constants.Strings["resolve.progress", Constants.Strings[key]]
  }

  override fun cancelTask(id: ExternalSystemTaskId, listener: ExternalSystemTaskNotificationListener): Boolean {
    return true
  }

  @Suppress("UnstableApiUsage")
  override fun resolveProjectInfo(
    id: ExternalSystemTaskId,
    projectPath: String,
    isPreviewMode: Boolean,
    settings: ElideExecutionSettings?,
    resolverPolicy: ProjectResolverPolicy?,
    listener: ExternalSystemTaskNotificationListener
  ): DataNode<ProjectData> = runBlockingCancellable {
    LOG.debug("Resolving project at '$projectPath'")

    val projectModel = runCatching {
      // find a manifest in the project directory
      listener.onStep(id, progressMessage("resolve.steps.discovery"))
      val projectRoot = Path(projectPath)
      val manifestPath = projectRoot.resolve(Constants.MANIFEST_NAME)

      if (manifestPath.notExists()) error("No Elide manifest found under $projectPath")

      val elideHome = settings?.elideHome ?: ElideDistributionResolver.defaultDistributionPath()
      val cli = ElideCommandLine.at(elideHome, projectRoot)

      // call the CLI to inspect the project manifest
      listener.onStep(id, progressMessage("resolve.steps.inspect"))
      val manifest = cli.manifest { out, err -> if (err) listener.onTaskOutput(id, out, false) }

      // install dependencies and resolve classpath
      listener.onStep(id, progressMessage("resolve.steps.sync"))
      cli.install(
        withSources = settings?.downloadSources == true,
        withDocs = settings?.downloadDocs == true,
        onOutput = { line, err -> listener.onTaskOutput(id, line, !err) },
      )

      val classpaths = ElideClasspathUsage.entries.associateWith { cli.classpath(it) }

      // build the project model from the manifest and classpaths
      listener.onStep(id, progressMessage("resolve.steps.buildModel"))
      ElideProjectModel.buildModel(projectRoot, classpaths, manifest)
    }.onSuccess {
      listener.onSuccess(projectPath, id)
    }.onFailure { cause ->
      if (cause is InvalidElideHomeException) ElideNotifications.notifyInvalidElideHome()
      listener.onFailure(projectPath, id, RuntimeException("Failed to load Elide project", cause))
    }

    listener.onEnd(projectPath, id)
    projectModel.getOrThrow()
  }

  companion object {
    @JvmStatic private val LOG = Logger.getInstance(ElideProjectResolver::class.java)
  }
}
