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
import dev.elide.intellij.cli.classpaths
import dev.elide.intellij.cli.install
import dev.elide.intellij.cli.manifest
import dev.elide.intellij.project.model.ElideClasspath
import dev.elide.intellij.project.model.ElideBuildTaskInfo
import dev.elide.intellij.project.model.taskScope
import dev.elide.intellij.project.model.androidResourceArtifactsToPrepare
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
import dev.elide.tooling.manifest.android.AndroidTarget
import dev.elide.tooling.manifest.sources.SourceSetType
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import org.jetbrains.annotations.PropertyKey
import kotlin.io.path.Path
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.exists
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
      val flags = settings?.buildFlags.orEmpty()
      val cli = ElideCommandLine.at(elideHome, projectRoot, flags)

      // call the CLI to inspect the project manifest
      listener.onStep(id, progressMessage("resolve.steps.inspect"))
      val manifest = cli.manifest { out, err -> if (err) listener.onTaskOutput(id, out, ProcessOutputType.STDERR) }

      // a manifest declaring members is the root of a workspace: its members are resolved as part of this sync,
      // because Elide resolves and builds the whole workspace from the root as one graph
      val members = resolveMembers(id, elideHome, projectRoot, manifest, flags, listener)

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
      val classifiers = settings?.installClassifiers ?: listOf("sources")
      if (!isLockfileCurrent(projectRoot, manifests) || !cli.isInstallationCurrent(classifiers)) {
        listener.onStep(id, progressMessage("resolve.steps.sync"))

        cli.install(classifiers = classifiers) { line, err ->
          listener.onTaskOutput(id, line, if (err) ProcessOutputType.STDERR else ProcessOutputType.STDOUT)
        }
      } else {
        LOG.debug("Lockfile is up to date, skipping dependency installation")
      }

      val resolved = resolveClasspaths(
        id, elideHome, cli, listOf(ElideResolvedProject.of(projectRoot, manifest)) + members,
        flags, buildTasks, listener,
      )
      val workspace = ElideResolvedWorkspace(root = resolved.first(), members = resolved.drop(1))

      // build the project model from the manifests and classpaths
      listener.onStep(id, progressMessage("resolve.steps.buildModel"))
      ElideProjectModel.buildModel(workspace, buildTasks, settings?.jdkHome.orEmpty())
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
    flags: List<String>,
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
      val memberCli = ElideCommandLine.at(elideHome, memberRoot, flags)
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

  /** Resolve all classpaths before building shared workspace targets; builds never overlap. */
  private suspend fun resolveClasspaths(
    id: ExternalSystemTaskId,
    elideHome: Path,
    workspaceCli: ElideCommandLine,
    projects: List<ElideResolvedProject>,
    flags: List<String>,
    buildTasks: List<ElideBuildTaskInfo>,
    listener: ExternalSystemTaskNotificationListener,
  ): List<ElideResolvedProject> {
    val output: (String, Boolean) -> Unit = { line, err ->
      listener.onTaskOutput(id, line, if (err) ProcessOutputType.STDERR else ProcessOutputType.STDOUT)
    }
    fun warning(message: String, cause: Exception) {
      LOG.warn(message, cause)
      output("$message: ${cause.message}\n", true)
    }
    suspend fun prepare(targets: List<String>, label: String) {
      if (targets.isEmpty()) return
      listener.onStep(id, label)
      try {
        workspaceCli(args = (listOf("build") + targets.distinct()).toTypedArray(), onOutput = output)
      } catch (cause: CancellationException) {
        throw cause
      } catch (cause: Exception) {
        warning(label, cause)
      }
    }

    data class Pending(
      val project: ElideResolvedProject,
      val cli: ElideCommandLine,
      val sets: List<String>,
      val provided: Map<Pair<String, ElideClasspathUsage>, ElideClasspath>?,
    )
    val pending = mutableListOf<Pending>()
    val libraries = mutableListOf<String>()
    val resources = mutableListOf<String>()
    val processors = mutableSetOf<String>()

    for (project in projects) {
      val cli = ElideCommandLine.at(elideHome, project.root, flags)
      val sets = project.manifest.sources.filter { (name, set) -> set.effectiveType(name) != SourceSetType.Other }.keys.toList()
      val android = project.manifest.android != null
      val targets = project.manifest.artifacts.filterValues { it is AndroidTarget }.keys
      val missingR = if (android) androidResourceArtifactsToPrepare(project.root, targets) else emptyList()
      val compileQueries = sets.map { it to ElideClasspathUsage.COMPILE }
      val providedQueries = if (android) sets.map { it to ElideClasspathUsage.PROVIDED } else emptyList()
      val early = if (missingR.isEmpty()) providedQueries else emptyList()
      var providedFailed = false
      val answers = try {
        cli.classpaths(compileQueries + early, output)
      } catch (cause: CancellationException) {
        throw cause
      } catch (cause: Exception) {
        if (early.isEmpty()) throw cause
        providedFailed = true
        cli.classpaths(compileQueries, output)
      }
      val compile = sets.associateWith { answers.getValue(it to ElideClasspathUsage.COMPILE) }
      fun scoped(target: String): String = if (projects.size > 1) "${project.name}:$target" else target
      if (compile.values.any { cp ->
          cp.entries.any { ElideProjectModel.explodedLibraryCoordinate(it) != null && !project.root.resolve(it).exists() }
        }) libraries += scoped("android-libraries")
      val reusable = early.isNotEmpty() && !providedFailed
      val unrecorded = if (reusable) androidResourceArtifactsToPrepare(
        project.root, targets, providedQueries.flatMap { answers[it]?.entries.orEmpty() },
      ) else emptyList()
      resources += (missingR + unrecorded).distinct().map { scoped("android-r-$it") }
      if (project.manifest.dependencies.maven.processors.isNotEmpty() || project.manifest.android?.hilt == true) {
        val wanted = sets.map { "compile-java-$it" }.toSet()
        val advertised = if (buildTasks.isNotEmpty()) buildTasks else try {
          workspaceCli.buildTasks(output)
        } catch (cause: CancellationException) {
          throw cause
        } catch (cause: Exception) {
          warning("Could not inspect annotation processor compilation targets", cause)
          emptyList()
        }
        processors += advertised.filter {
          (it.taskScope == project.name || (it.taskScope == null && project == projects.first())) &&
            it.name.substringAfterLast(':') in wanted
        }.map { it.name }
      }
      pending += Pending(
        project.copy(classpaths = compile), cli, sets,
        answers.takeIf { reusable && unrecorded.isEmpty() },
      )
    }

    prepare(libraries, "Unpacking Android libraries")
    prepare(resources, "Generating Android resource identifiers")
    prepare(processors.toList(), "Generating annotation processor sources")
    return pending.map { (project, cli, sets, reused) ->
      if (project.manifest.android == null) return@map project
      val queries = sets.map { it to ElideClasspathUsage.PROVIDED }
      val batch = reused ?: try {
        cli.classpaths(queries, output)
      } catch (cause: CancellationException) {
        throw cause
      } catch (cause: Exception) {
        null
      }
      val provided = buildMap {
        for (name in sets) {
          try {
            put(name, batch?.get(name to ElideClasspathUsage.PROVIDED) ?: cli.classpath(name, ElideClasspathUsage.PROVIDED, output))
          } catch (cause: CancellationException) {
            throw cause
          } catch (cause: Exception) {
            warning("Could not resolve Android platform for ${project.name}:$name; android.* and R may be unresolved", cause)
          }
        }
      }
      project.copy(providedClasspaths = provided)
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
