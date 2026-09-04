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
import dev.elide.intellij.MissingManifestException
import dev.elide.intellij.cli.ElideCommandLine
import dev.elide.intellij.cli.classpath
import dev.elide.intellij.cli.install
import dev.elide.intellij.cli.manifest
import dev.elide.intellij.project.model.ElideClasspath
import dev.elide.intellij.project.model.ElideClasspathUsage
import dev.elide.intellij.project.model.ElideProjectModel
import dev.elide.intellij.service.ElideDistributionResolver
import dev.elide.intellij.settings.ElideExecutionSettings
import dev.elide.intellij.ui.ElideNotifications
import dev.elide.project.manifest.effectiveType
import dev.elide.tooling.manifest.project.ProjectModule
import dev.elide.tooling.manifest.sources.SourceSetType
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import org.jetbrains.annotations.PropertyKey
import kotlin.io.path.Path
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.notExists

/**
 * A service capable of using the Elide manifest and lockfile to build a project model that can be understood by the
 * IDE. The CLI is invoked to inspect the manifest and resolve classpaths; dependency installation only runs when the
 * lockfile is missing or older than the manifest.
 */
class ElideProjectResolver : ExternalSystemProjectResolver<ElideExecutionSettings> {
  /** Sync jobs currently in flight, so [cancelTask] can actually stop them (and the CLI processes they own). */
  private val runningSyncs = ConcurrentHashMap<ExternalSystemTaskId, Job>()

  private fun ExternalSystemTaskNotificationListener.onStep(taskId: ExternalSystemTaskId, text: String) {
    onStatusChange(ExternalSystemTaskNotificationEvent(taskId, text))
  }

  private fun progressMessage(@PropertyKey(resourceBundle = "i18n.Strings") key: String): String {
    return Constants.Strings["resolve.progress", Constants.Strings[key]]
  }

  override fun cancelTask(id: ExternalSystemTaskId, listener: ExternalSystemTaskNotificationListener): Boolean {
    val job = runningSyncs.remove(id) ?: return false
    job.cancel()

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
    runningSyncs[id] = coroutineContext.job

    try {
      // find a manifest in the project directory
      listener.onStep(id, progressMessage("resolve.steps.discovery"))
      val projectRoot = Path(projectPath)
      val manifestPath = projectRoot.resolve(Constants.MANIFEST_NAME)

      if (manifestPath.notExists()) throw MissingManifestException(projectPath)

      val elideHome = settings?.elideHome ?: resolveElideHome(id, projectPath)
      val cli = ElideCommandLine.at(elideHome, projectRoot)

      // call the CLI to inspect the project manifest
      listener.onStep(id, progressMessage("resolve.steps.inspect"))
      // NOTE: the `ProcessOutputType` overload of `onTaskOutput` only exists from build 253 onward
      @Suppress("DEPRECATION")
      val manifest = cli.manifest { out, err -> if (err) listener.onTaskOutput(id, out, false) }

      // install dependencies only when the lockfile no longer reflects the manifest
      if (!isLockfileCurrent(projectRoot, manifestPath)) {
        listener.onStep(id, progressMessage("resolve.steps.sync"))

        @Suppress("DEPRECATION")
        cli.install { line, err -> listener.onTaskOutput(id, line, !err) }
      } else {
        LOG.debug("Lockfile is up to date, skipping dependency installation")
      }

      // resolve the compile classpath of every source set declared by the manifest
      val classpaths = resolveClasspaths(cli, manifest)

      // build the project model from the manifest and classpaths
      listener.onStep(id, progressMessage("resolve.steps.buildModel"))
      ElideProjectModel.buildModel(projectRoot, classpaths, manifest)
    } catch (cause: InvalidElideHomeException) {
      // the platform reports the failure itself (see AbstractExternalSystemTask); only the notification is ours
      ElideNotifications.notifyInvalidElideHome(id.findProject())
      throw cause
    } finally {
      runningSyncs.remove(id)
    }
  }

  /**
   * Resolve the classpath of every source set in the manifest.
   *
   * Source sets which the IDE does not compile ([SourceSetType.Other]) are skipped; everything else is resolved with
   * the `compile` usage, which is the only usage the CLI accepts for a source set (dependency *scope* is derived from
   * the source set type when the model is built).
   */
  private suspend fun resolveClasspaths(
    cli: ElideCommandLine,
    manifest: ProjectModule,
  ): Map<String, ElideClasspath> = buildMap {
    for ((name, sourceSet) in manifest.sources) {
      if (sourceSet.effectiveType(name) == SourceSetType.Other) continue
      put(name, cli.classpath(name, ElideClasspathUsage.COMPILE))
    }
  }

  /**
   * Returns `true` when the installed dependency tree can be trusted without running `elide install`.
   *
   * The lockfile is considered current when it exists, is at least as recent as the manifest, and the dependency
   * root it describes is present on disk.
   */
  private fun isLockfileCurrent(projectRoot: Path, manifestPath: Path): Boolean {
    val outputDir = projectRoot.resolve(Constants.OUTPUT_DIR)
    if (!outputDir.resolve(Constants.DEPENDENCIES_DIR).isDirectory()) return false

    val lockfile = runCatching {
      outputDir.listDirectoryEntries()
        .filter { it.isRegularFile() && Constants.isLockfileName(it.fileName.toString()) }
        .maxByOrNull { it.getLastModifiedTime() }
    }.getOrNull() ?: return false

    return runCatching {
      lockfile.getLastModifiedTime() >= manifestPath.getLastModifiedTime()
    }.getOrDefault(false)
  }

  private fun resolveElideHome(id: ExternalSystemTaskId, projectPath: String): Path {
    val project = id.findProject() ?: return ElideDistributionResolver.defaultDistributionPath()
    return ElideDistributionResolver.getElideHome(project, projectPath)
  }

  companion object {
    @JvmStatic private val LOG = Logger.getInstance(ElideProjectResolver::class.java)
  }
}
