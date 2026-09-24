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

import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.externalSystem.importing.ProjectResolverPolicy
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationEvent
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.service.project.ExternalSystemProjectResolver
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.util.io.toCanonicalPath
import dev.elide.intellij.Constants
import dev.elide.intellij.DuplicateProjectNameException
import dev.elide.intellij.InvalidElideHomeException
import dev.elide.intellij.MissingManifestException
import dev.elide.intellij.WorkspaceMemberIsRootException
import dev.elide.intellij.cli.ElideCommandLine
import dev.elide.intellij.cli.buildTasks
import dev.elide.intellij.cli.classpath
import dev.elide.intellij.cli.install
import dev.elide.intellij.cli.manifest
import dev.elide.intellij.project.model.ElideClasspath
import dev.elide.intellij.project.model.ElideClasspathUsage
import dev.elide.intellij.project.model.ElideProjectModel
import dev.elide.intellij.project.model.ElideResolvedProject
import dev.elide.intellij.project.model.ElideResolvedWorkspace
import dev.elide.intellij.service.ElideDistributionResolver
import dev.elide.intellij.settings.ElideExecutionSettings
import dev.elide.intellij.ui.ElideNotifications
import dev.elide.project.manifest.effectiveType
import dev.elide.project.manifest.workspaceMembers
import dev.elide.tooling.manifest.project.ProjectModule
import dev.elide.tooling.manifest.sources.SourceSetType
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
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

  private fun progressMessage(
    @PropertyKey(resourceBundle = "i18n.Strings") key: String,
    vararg params: Any,
  ): String {
    return Constants.Strings["resolve.progress", Constants.Strings.get(key, *params)]
  }

  override fun cancelTask(id: ExternalSystemTaskId, listener: ExternalSystemTaskNotificationListener): Boolean {
    val job = runningSyncs.remove(id) ?: return false
    job.cancel()

    return true
  }

  // `ProjectResolverPolicy` and this overload of `resolveProjectInfo` are experimental, and there is no stable way
  // to implement an external system resolver; see `docs/PLATFORM_APIS.md`
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
      val manifest = cli.manifest { out, err -> if (err) listener.onTaskOutput(id, out, ProcessOutputType.STDERR) }

      // a manifest declaring members is the root of a workspace: its members are resolved as part of this sync,
      // because Elide resolves and builds the whole workspace from the root as one graph
      val members = resolveMembers(id, elideHome, projectRoot, manifest, listener)

      // the CLI owns the project's build graph, so its task list is read from it rather than derived from the
      // manifest; the listing is only used for completion, and a distribution that fails to produce one (or has no
      // `--inspect` at all) must not fail the sync
      val buildTasks = try {
        cli.buildTasks()
      } catch (cause: CancellationException) {
        throw cause
      } catch (cause: Exception) {
        LOG.warn("Failed to list build tasks of project at '$projectPath'", cause)
        emptyList()
      }

      // install dependencies only when the lockfile no longer reflects the manifests it was resolved from; a
      // workspace resolves once, into the root's repository, so one install covers every member
      val manifests = listOf(manifestPath) + members.map { it.root.resolve(Constants.MANIFEST_NAME) }
      if (!isLockfileCurrent(projectRoot, manifests)) {
        listener.onStep(id, progressMessage("resolve.steps.sync"))

        cli.install { line, err ->
          listener.onTaskOutput(id, line, if (err) ProcessOutputType.STDERR else ProcessOutputType.STDOUT)
        }
      } else {
        LOG.debug("Lockfile is up to date, skipping dependency installation")
      }

      // resolve the compile classpath of every source set of every project
      val workspace = ElideResolvedWorkspace(
        root = ElideResolvedProject.of(projectRoot, manifest, resolveClasspaths(elideHome, projectRoot, manifest)),
        members = members.map { member ->
          member.copy(classpaths = resolveClasspaths(elideHome, member.root, member.manifest))
        },
      )

      // build the project model from the manifests and classpaths
      listener.onStep(id, progressMessage("resolve.steps.buildModel"))
      ElideProjectModel.buildModel(workspace, buildTasks)
    } catch (cause: InvalidElideHomeException) {
      // the platform reports the failure itself (see AbstractExternalSystemTask); only the notification is ours
      ElideNotifications.notifyInvalidElideHome(id.findProject())
      throw cause
    } finally {
      runningSyncs.remove(id)
    }
  }

  /**
   * Resolve the manifest of every member [manifest] declares, in declaration order.
   *
   * Each entry names a directory, relative to the workspace root, holding a manifest of its own; the project name
   * Elide knows a member by is the one that manifest declares, which is what a sibling's `project(…)` reference and
   * the CLI's task scopes are written against. Two members resolving to the same name would collide there as well as
   * on the modules built from them, so the sync fails the way the CLI's own resolution does.
   */
  private suspend fun resolveMembers(
    id: ExternalSystemTaskId,
    elideHome: Path,
    projectRoot: Path,
    manifest: ProjectModule,
    listener: ExternalSystemTaskNotificationListener,
  ): List<ElideResolvedProject> {
    val members = manifest.workspaceMembers
    if (members.isEmpty()) return emptyList()

    val root = ElideResolvedProject.of(projectRoot, manifest)
    val resolved = mutableListOf<ElideResolvedProject>()
    val names = mutableMapOf(root.name to projectRoot)

    for (member in members) {
      val memberRoot = projectRoot.resolve(member).normalize()

      if (memberRoot == projectRoot) {
        throw WorkspaceMemberIsRootException(member)
      }
      if (memberRoot.resolve(Constants.MANIFEST_NAME).notExists()) {
        throw MissingManifestException(memberRoot.toCanonicalPath())
      }

      listener.onStep(id, progressMessage("resolve.steps.inspectMember", member))
      val memberCli = ElideCommandLine.at(elideHome, memberRoot)
      val memberManifest = memberCli.manifest { out, err ->
        if (err) listener.onTaskOutput(id, out, ProcessOutputType.STDERR)
      }

      val project = ElideResolvedProject.of(memberRoot, memberManifest)
      names.put(project.name, memberRoot)?.let { clash ->
        throw DuplicateProjectNameException(project.name, clash, memberRoot)
      }

      resolved += project
    }

    return resolved
  }

  /**
   * Resolve the classpath of every source set in the manifest of the project rooted at [projectRoot].
   *
   * Source sets which the IDE does not compile ([SourceSetType.Other]) are skipped; everything else is resolved with
   * the `compile` usage, which is the only usage the CLI accepts for a source set (dependency *scope* is derived from
   * the source set type when the model is built).
   *
   * The CLI is invoked in the project's own directory, which is what makes it the project in focus: a member's
   * classpath is the member's, resolved against the workspace it belongs to.
   */
  private suspend fun resolveClasspaths(
    elideHome: Path,
    projectRoot: Path,
    manifest: ProjectModule,
  ): Map<String, ElideClasspath> = buildMap {
    val cli = ElideCommandLine.at(elideHome, projectRoot)

    for ((name, sourceSet) in manifest.sources) {
      if (sourceSet.effectiveType(name) == SourceSetType.Other) continue
      put(name, cli.classpath(name, ElideClasspathUsage.COMPILE))
    }
  }

  /**
   * Returns `true` when the installed dependency tree can be trusted without running `elide install`.
   *
   * The lockfile is considered current when it exists, is at least as recent as every manifest it was resolved from,
   * and the dependency root it describes is present on disk. A workspace resolves into the root's repository and
   * records one lockfile there, so a member's manifest counts among [manifests] even though it has a build directory
   * of its own.
   */
  private fun isLockfileCurrent(projectRoot: Path, manifests: List<Path>): Boolean {
    val outputDir = projectRoot.resolve(Constants.OUTPUT_DIR)
    if (!outputDir.resolve(Constants.DEPENDENCIES_DIR).isDirectory()) return false

    val lockfile = runCatching {
      outputDir.listDirectoryEntries()
        .filter { it.isRegularFile() && Constants.isLockfileName(it.fileName.toString()) }
        .maxByOrNull { it.getLastModifiedTime() }
    }.getOrNull() ?: return false

    return runCatching {
      val newest = manifests.maxOf { it.getLastModifiedTime() }
      lockfile.getLastModifiedTime() >= newest
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
