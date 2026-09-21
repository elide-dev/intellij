/*
 *  Copyright (c) 2024-2025 Elide Technologies, Inc.
 *
 *  Licensed under the MIT license (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at
 *
 *    https://opensource.org/license/mit/
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations under the License.
 */

package dev.elide.intellij.project.model

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.project.*
import com.intellij.openapi.externalSystem.model.task.TaskData
import com.intellij.openapi.roots.DependencyScope
import com.intellij.openapi.util.io.toCanonicalPath
import dev.elide.intellij.Constants
import dev.elide.project.manifest.effectiveType
import dev.elide.project.manifest.paths
import dev.elide.project.manifest.projectReferences
import dev.elide.project.manifest.referencedSourceSets
import dev.elide.project.manifest.resources
import dev.elide.project.manifest.sourceSetsForArtifactOutput
import dev.elide.tooling.manifest.project.ProjectModule
import dev.elide.tooling.manifest.sources.SourceSet
import dev.elide.tooling.manifest.sources.SourceSetType
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.pathString

object ElideProjectModel {
  private val LOG = Logger.getInstance(ElideProjectModel::class.java)

  private const val SUFFIX_JAR = "jar"
  private const val SUFFIX_JAVADOC = "-javadoc.$SUFFIX_JAR"
  private const val SUFFIX_SOURCES = "-sources.$SUFFIX_JAR"
  private const val DEFAULT_LIBRARIES_ROOT = ".dev/dependencies/m2/"

  private data class SourceSetModel(
    val project: ElideResolvedProject,
    val name: String,
    val sourceSet: SourceSet,
    val module: DataNode<ModuleData>,
    val contentRoots: MutableList<ContentRootData>,
  )

  /**
   * Build the IDE's model of the projects [workspace] holds, from their manifests and resolved classpaths.
   *
   * The linked project is the workspace root; a workspace's members are modelled inside it rather than as linked
   * projects of their own, because Elide builds and resolves the whole workspace as one graph from that root.
   *
   * [buildTasks] is the task listing the CLI printed for the linked project, which the manifest alone does not
   * describe; inside a workspace it covers every project, with a member's tasks qualified by its name.
   */
  fun buildModel(
    workspace: ElideResolvedWorkspace,
    buildTasks: List<ElideBuildTaskInfo> = emptyList(),
  ): DataNode<ProjectData> {
    val projectPath = workspace.root.root
    val projectData = ProjectData(
      /* owner = */ Constants.SYSTEM_ID,
      /* externalName = */ workspace.root.manifest.name ?: projectPath.nameWithoutExtension,
      /* ideProjectFileDirectoryPath = */ projectPath.resolve(".idea").pathString,
      /* linkedExternalProjectPath = */ projectPath.pathString,
    )
    val projectNode = DataNode(ProjectKeys.PROJECT, projectData, null)

    // artifact outputs of the workspace: a dependency on a sibling's artifact is printed on the consumer's
    // classpath as the path that sibling's build will write. It is a build output, not a library — it may not exist
    // yet — so it is dropped from the libraries here and re-expressed as a module dependency further down, against
    // the project this index resolves the entry to
    val artifacts = ArtifactIndex(workspace.projects)

    // the JDK is selected per project and applied to its own modules; contributors must not re-run it
    val jdks = workspace.projects.associate { it.name to ElideJdkSelector.selectJdk(it.manifest) }

    // a member resolves its dependencies into the workspace root's repository, so the layout the library names are
    // parsed against is the one the manifest that owns that repository declares
    val librariesRoot = librariesRoot(workspace.root.manifest)

    val libraries = mutableListOf<LibraryData>()
    val modules = mutableListOf<SourceSetModel>()

    // the sibling artifacts each source set of each project actually resolved, keyed by project name and source set
    val artifactRefs = mutableMapOf<String, Map<String, List<ArtifactOutput>>>()

    for (project in workspace.projects) {
      val classified = project.classpaths.mapValues { (_, classpath) ->
        classpath.entries.distinct().map { entry -> entry to artifacts.resolve(entry) }
      }

      val projectLibraries = classified.mapValues { (_, entries) ->
        entries.filter { (_, output) -> output == null }
          .map { (entry, _) -> buildLibraryData(entry, project.root, librariesRoot) }
      }

      artifactRefs[project.name] = classified.mapValues { (_, entries) ->
        entries.mapNotNull { (_, output) -> output }.distinct()
      }

      libraries += projectLibraries.values.flatten()

      val container = projectNode.createChild(ProjectKeys.MODULE, containerModule(workspace, project, projectData))
      container.createChild(ModuleSdkData.KEY, ModuleSdkData(jdks[project.name]))

      val projectModules = project.manifest.sources.entries.map { (name, sourceSet) ->
        buildSourceSetModule(projectNode, workspace, project, projectLibraries, name, sourceSet, jdks[project.name])
      }

      configureProjectRoot(project, container, projectModules)
      modules += projectModules
    }

    // libraries are project-level nodes, and two projects of a workspace resolving the same coordinate name the same
    // JAR, so the whole workspace contributes one set of them
    libraries.distinctBy { it.getPaths(LibraryPathType.BINARY) }.forEach {
      projectNode.createChild(ProjectKeys.LIBRARY, it)
    }

    // add module dependencies based on source set types
    configureModuleDependencies(modules)

    // wire every artifact one project of the workspace consumes from another
    configureProjectDependencies(workspace, modules, artifactRefs)

    // add resources roots from JVM source sets
    for (module in modules) {
      configureResourceRoots(module.project.root, module)
    }

    projectNode.createChild(ProjectSdkData.KEY, ProjectSdkData(jdks[workspace.root.name]))

    // attached additional data so we can finish the import after the project is resolved: one node per project, so
    // the post-import step can index every member and apply each manifest's settings to its own modules
    for (project in workspace.projects) {
      projectNode.createChild(
        ElideProjectData.PROJECT_KEY,
        ElideProjectData.from(
          project = project,
          workspace = workspace,
          buildTasks = buildTasks,
        ),
      )
    }

    // Elide's build graph belongs to the workspace rather than to any one module, so its tasks hang off the project
    // node, under the list the tool window shows them in; running one from there goes through the same task manager
    // as a run configuration
    if (buildTasks.isNotEmpty()) {
      val tasksNode = projectNode.createChild(ElideBuildTasksData.KEY, ElideBuildTasksData())
      configureBuildTasks(workspace, tasksNode, buildTasks)
    }

    // invoke registered contributors
    invokeContributors(projectNode, projectPath, workspace.root.manifest)

    return projectNode
  }

  /**
   * Hang the build tasks of [workspace] off [tasksNode], grouped by the project declaring them.
   *
   * Inside a workspace the CLI qualifies a member's task with that member's name (`core:jar`) and leaves the root's
   * bare, so the listing of one build graph covers several projects; the tree shows each project's own tasks under a
   * node of its own rather than as one flat list of qualified names. A scope naming no project of the workspace is
   * left with the root, which is the project a build launched from the linked directory resolves it against.
   *
   * The task names themselves keep their scope: they are what `elide build` is handed, from the workspace root, by
   * every path the platform runs a task through.
   */
  private fun configureBuildTasks(
    workspace: ElideResolvedWorkspace,
    tasksNode: DataNode<ElideBuildTasksData>,
    buildTasks: List<ElideBuildTaskInfo>,
  ) {
    val projectPath = workspace.root.root.pathString

    fun addTasks(parent: DataNode<ElideBuildTasksData>, tasks: List<ElideBuildTaskInfo>) {
      for (task in tasks) {
        parent.createChild(
          ProjectKeys.TASK,
          TaskData(
            /* owner = */ Constants.SYSTEM_ID,
            /* name = */ task.taskName,
            /* linkedExternalProjectPath = */ projectPath,
            /* description = */ task.description.ifEmpty { null },
          ),
        )
      }
    }

    if (!workspace.isWorkspace) {
      addTasks(tasksNode, buildTasks)
      return
    }

    val names = workspace.projects.map { it.name }.toSet()
    val grouped = buildTasks.groupBy { task -> task.taskScope?.takeIf { it in names } ?: workspace.root.name }

    for (project in workspace.projects) {
      val tasks = grouped[project.name].orEmpty()
      if (tasks.isEmpty()) continue

      addTasks(tasksNode.createChild(ElideBuildTasksData.KEY, ElideBuildTasksData(project.name)), tasks)
    }
  }

  /** An artifact a project of the workspace builds, as a classpath entry resolves to it. */
  private data class ArtifactOutput(
    /** Name Elide knows the project writing the artifact by. */
    val project: String,
    /** Name the artifact is written under, or `null` when the entry named no artifact directory of its own. */
    val name: String?,
  )

  /**
   * Resolves classpath entries against what the projects of a workspace build.
   *
   * Both the lexical and the resolved form of every project root are indexed: the CLI prints a sibling's JAR as a
   * path relative to the *consumer* (`…/parser/../model/.dev/artifacts/…`), and resolves symlinks on the way (`/tmp`
   * is `/private/tmp` on macOS), so neither form alone matches what a classpath entry carries.
   */
  private class ArtifactIndex(projects: List<ElideResolvedProject>) {
    private val roots: List<Pair<Path, String>> = projects.flatMap { project ->
      val lexical = project.root.toAbsolutePath().normalize()
      val real = runCatching { project.root.toRealPath() }.getOrDefault(lexical)

      setOf(lexical, real).map { root ->
        root.resolve(Constants.OUTPUT_DIR).resolve(Constants.ARTIFACTS_DIR) to project.name
      }
    }

    /**
     * Returns the artifact [entry] names, or `null` when it names a library rather than something a project of this
     * workspace builds.
     *
     * Elide writes an artifact to `<kind>/<name>/` under the project's artifact directory, naming the directory
     * after the artifact, which is how the entry is related back to the source sets packaged into it.
     */
    fun resolve(entry: String): ArtifactOutput? {
      val path = runCatching { Path.of(entry).toAbsolutePath().normalize() }.getOrNull() ?: return null
      val (dir, project) = roots.firstOrNull { (dir, _) -> path.startsWith(dir) } ?: return null
      val relative = dir.relativize(path)

      return ArtifactOutput(project, relative.takeIf { it.nameCount >= 2 }?.getName(1)?.toString())
    }
  }

  /** Repository root the manifest resolves Maven dependencies into, as a path prefix of every library in it. */
  private fun librariesRoot(manifest: ProjectModule): String {
    return manifest.dependencies.maven.localRepository?.let { customPath ->
      if (customPath.endsWith("/")) customPath else "$customPath/"
    } ?: DEFAULT_LIBRARIES_ROOT
  }

  /**
   * The module standing for a whole project: the node its source-set modules are listed beside, rooted at the
   * directory holding the manifest it was built from (see [configureProjectRoot]).
   *
   * The linked project keeps the identity of the external project itself, so a standalone project's model is
   * unchanged by workspaces; a member is named after the project Elide knows it by.
   */
  private fun containerModule(
    workspace: ElideResolvedWorkspace,
    project: ElideResolvedProject,
    projectData: ProjectData,
  ): ModuleData {
    val isRoot = project.root == workspace.root.root

    return ModuleData(
      /* id = */ if (isRoot) projectData.id else project.name,
      /* owner = */ Constants.SYSTEM_ID,
      /* moduleTypeId = */ "JAVA_MODULE",
      /* externalName = */ if (isRoot) projectData.externalName else project.name,
      /* moduleFileDirectoryPath = */ project.root.resolve(".idea").toCanonicalPath(),
      /* linkedExternalProjectPath = */ project.root.toCanonicalPath(),
    )
  }

  /**
   * Give the module standing for [project] a content root at the project's own directory.
   *
   * Without one, only the source folders the manifest declares belong to a module, and everything else the project
   * directory holds — the manifest itself first among them — is outside the IDE's model of the project: unindexed,
   * and, in a workspace, leaving the member directories of the tree with no module at all.
   *
   * A source set whose patterns reach the whole project already claims that directory, and the same directory may
   * not be the content root of two modules, so the root is only created when no source-set module of [modules] owns
   * it. Either way it is the root that excludes Elide's output directory, which holds installed dependencies and
   * build outputs: the IDE reads those as libraries and artifacts, and indexing them as project content would walk
   * the whole dependency tree on every sync.
   */
  private fun configureProjectRoot(
    project: ElideResolvedProject,
    container: DataNode<ModuleData>,
    modules: List<SourceSetModel>,
  ) {
    val rootPath = ElideSourceRoots.contentRoot(project.root)

    val root = modules.asSequence()
      .flatMap { it.contentRoots }
      .firstOrNull { it.rootPath == rootPath }
      ?: ContentRootData(Constants.SYSTEM_ID, rootPath).also {
        container.createChild(ProjectKeys.CONTENT_ROOT, it)
      }

    root.storePath(ExternalSystemSourceType.EXCLUDED, project.root.resolve(Constants.OUTPUT_DIR).toCanonicalPath())
  }

  private fun invokeContributors(
    projectNode: DataNode<ProjectData>,
    projectPath: Path,
    manifest: ProjectModule,
  ) {
    ElideProjectModelContributor.EP_NAME.extensionList.forEach { contributor ->
      try {
        contributor.contribute(projectNode, projectPath, manifest)
      } catch (e: Exception) {
        LOG.warn("Failed to invoke project model contributor: ${contributor.javaClass.name}", e)
      }
    }
  }

  /**
   * The IDE dependency scope a source set of this type resolves its dependencies at, or `null` for a source set the
   * IDE does not compile and therefore gives no dependencies at all.
   */
  private fun dependencyScope(type: SourceSetType): DependencyScope? = when (type) {
    SourceSetType.Source, SourceSetType.Example -> DependencyScope.COMPILE
    SourceSetType.Test -> DependencyScope.TEST
    SourceSetType.Other -> null
  }

  private fun configureModuleDependencies(modules: List<SourceSetModel>) {
    // only source sets of the same project: a dependency on another project of the workspace is resolved, not
    // implied, and is wired by `configureProjectDependencies`
    for ((_, projectModules) in modules.groupBy { it.project.name }) {
      val mainModules = projectModules.filter { it.sourceSet.effectiveType(it.name) == SourceSetType.Source }
      val otherModules = projectModules.filter { it.sourceSet.effectiveType(it.name) != SourceSetType.Source }

      // other modules depend on main modules
      for (test in otherModules) {
        for (main in mainModules) {
          val data = ModuleDependencyData(test.module.data, main.module.data)
          data.scope = DependencyScope.TEST
          test.module.createChild(ProjectKeys.MODULE_DEPENDENCY, data)
        }
      }
    }
  }

  /**
   * Wire every artifact one project of the workspace consumes from another as a module dependency.
   *
   * The CLI resolves such a dependency to the JAR the producing project's build writes, which the IDE cannot compile
   * against — it is an output, and is dropped from the libraries for that reason. A dependency on the modules backing
   * that JAR is what the IDE can act on: it compiles the sibling first, and navigates into its sources.
   *
   * [artifactRefs] holds what each source set resolved, which is the only complete account of it: an artifact reached
   * *through* a sibling is on the consumer's classpath without being declared by it anywhere, exactly as Elide
   * compiles it. The declarations are walked as well, because a reference resolved into a usage the IDE never asks
   * the CLI for — a processor, or a runtime-only dependency — reaches none of these classpaths yet still relates the
   * two projects.
   *
   * Dependencies are exported, so a module depending on the consumer inherits them the way Elide's classpath does.
   */
  private fun configureProjectDependencies(
    workspace: ElideResolvedWorkspace,
    modules: List<SourceSetModel>,
    artifactRefs: Map<String, Map<String, List<ArtifactOutput>>>,
  ) {
    if (!workspace.isWorkspace) return

    // one edge per pair of modules: a consumer commonly reaches the same modules through several artifacts, and a
    // directly declared dependency is resolved onto the classpath as well
    val wired = mutableSetOf<Pair<String, String>>()

    fun wire(consumer: SourceSetModel, targets: List<SourceSetModel>, scope: DependencyScope) {
      for (target in targets) {
        if (target === consumer) continue
        if (!wired.add(consumer.module.data.id to target.module.data.id)) continue

        val data = ModuleDependencyData(consumer.module.data, target.module.data)
        data.isExported = true
        data.scope = scope
        consumer.module.createChild(ProjectKeys.MODULE_DEPENDENCY, data)
      }
    }

    fun modulesOf(project: String, sourceSets: Collection<String>): List<SourceSetModel> = modules.filter {
      it.project.name == project && it.name in sourceSets
    }

    // what each source set resolved, which covers direct and transitive artifacts alike, at the scope of the source
    // set that asked for it
    for (consumer in modules) {
      val scope = dependencyScope(consumer.sourceSet.effectiveType(consumer.name)) ?: continue

      for (output in artifactRefs[consumer.project.name]?.get(consumer.name).orEmpty()) {
        // a project's own artifacts stand for its own source sets, which are already wired by type
        if (output.project == consumer.project.name) continue

        val target = workspace.project(output.project) ?: continue
        wire(consumer, modulesOf(target.name, target.manifest.sourceSetsForArtifactOutput(output.name)), scope)
      }
    }

    // declarations, for the references none of the resolved classpaths carry
    for (project in workspace.projects) {
      for (reference in project.manifest.projectReferences()) {
        val target = workspace.project(reference.project)
        if (target == null) {
          // the CLI reports an unresolvable reference when the build is configured; the model simply carries none
          LOG.warn("Project '${project.name}' references unknown workspace project '${reference.project}'")
          continue
        }

        val targets = modulesOf(target.name, target.manifest.referencedSourceSets(reference.artifact))
        if (targets.isEmpty()) continue

        val consumers = modules.filter {
          val type = it.sourceSet.effectiveType(it.name)
          it.project.name == project.name && when {
            reference.test -> type == SourceSetType.Test
            else -> type == SourceSetType.Source || type == SourceSetType.Example
          }
        }

        for (consumer in consumers) {
          wire(consumer, targets, if (reference.test) DependencyScope.TEST else DependencyScope.COMPILE)
        }
      }
    }
  }

  private fun configureResourceRoots(projectPath: Path, module: SourceSetModel) {
    // only JVM source sets declare resources; the rest report none
    val resourcePaths = module.sourceSet.resources.values.toList()
    if (resourcePaths.isEmpty()) return

    val type = when (module.sourceSet.effectiveType(module.name)) {
      SourceSetType.Test -> ExternalSystemSourceType.TEST_RESOURCE
      else -> ExternalSystemSourceType.RESOURCE
    }

    ElideSourceRoots.collect(projectPath, resourcePaths).forEach { (root, paths) ->
      // resources may live outside every source content root, in which case they get one of their own instead of
      // being silently dropped from the model
      val containingRoot = module.contentRoots.asSequence()
        .filter { root.isPathUnder(it.rootPath) }
        .maxByOrNull { it.rootPath.length }
        ?: ContentRootData(Constants.SYSTEM_ID, root).also {
          module.module.createChild(ProjectKeys.CONTENT_ROOT, it)
          module.contentRoots.add(it)
        }

      if (paths.size == 1 && root == containingRoot.rootPath) containingRoot.storePath(type, paths.single())
      else paths.forEach { containingRoot.storePath(type, it) }
    }
  }

  /**
   * Build the module of one source set.
   *
   * Inside a workspace the module is named after the project declaring it as well as the source set, since every
   * project of a workspace declares source sets of its own and `main` alone would name several. A standalone project
   * keeps the bare source set name, so its modules are unaffected by workspace support.
   *
   * The source set name is also the module's own name below the project's, which is what the tool window nests the
   * source-set modules of a project under the module standing for that project by: the platform reads the grouping
   * out of a module's name being its parent's, a dot, and this name.
   */
  private fun buildSourceSetModule(
    projectNode: DataNode<ProjectData>,
    workspace: ElideResolvedWorkspace,
    project: ElideResolvedProject,
    libraries: Map<String, List<LibraryData>>,
    sourceSetName: String,
    sourceSet: SourceSet,
    jdkName: String?,
  ): SourceSetModel {
    val projectPath = project.root
    val moduleName = if (workspace.isWorkspace) "${project.name}.$sourceSetName" else sourceSetName

    val module = ModuleData(
      /* id = */ moduleName,
      /* owner = */ Constants.SYSTEM_ID,
      /* moduleTypeId = */ "JAVA_MODULE",
      /* externalName = */ moduleName,
      /* moduleFileDirectoryPath = */ projectPath.resolve(".idea/modules").toCanonicalPath(),
      /* linkedExternalProjectPath = */ projectPath.toCanonicalPath(),
    )

    // `cli.main` is the `main` module of `cli`, not a module named `cli.main` at the top level
    module.moduleName = sourceSetName

    val moduleNode = projectNode.createChild(ProjectKeys.MODULE, module)

    // Determine effective source set type (handle "test" name convention)
    val effectiveType = sourceSet.effectiveType(sourceSetName)

    // add library dependencies using the scope implied by the source set type; `Other` sets are not compiled by the
    // IDE, so they take no libraries at all
    val scope = dependencyScope(effectiveType)

    if (scope != null) libraries[sourceSetName]?.forEach { library ->
      val data = LibraryDependencyData(module, library, LibraryLevel.MODULE)
      data.isExported = true
      data.scope = scope
      moduleNode.createChild(ProjectKeys.LIBRARY_DEPENDENCY, data)
    }

    // Determine source type for IntelliJ
    val sourceType = when (effectiveType) {
      SourceSetType.Source, SourceSetType.Example -> ExternalSystemSourceType.SOURCE
      SourceSetType.Test -> ExternalSystemSourceType.TEST
      SourceSetType.Other -> ExternalSystemSourceType.EXCLUDED
    }

    val contentRoots = ElideSourceRoots.collect(projectPath, sourceSet.paths).map { (root, paths) ->
      val data = ContentRootData(Constants.SYSTEM_ID, root)
      for (path in paths) data.storePath(sourceType, path)

      moduleNode.createChild(ProjectKeys.CONTENT_ROOT, data)
      data
    }

    moduleNode.createChild(ModuleSdkData.KEY, ModuleSdkData(jdkName))

    return SourceSetModel(project, sourceSetName, sourceSet, moduleNode, contentRoots.toMutableList())
  }

  private fun buildLibraryData(classpathEntry: String, projectPath: Path, librariesRoot: String): LibraryData {
    val libraryName = parseLibraryName(classpathEntry, librariesRoot)
    val library = LibraryData(Constants.SYSTEM_ID, libraryName)

    val classesPath = projectPath.resolve(classpathEntry)
    library.addPath(LibraryPathType.BINARY, classesPath.pathString)

    classesPath.parent?.resolve("${classesPath.nameWithoutExtension}$SUFFIX_SOURCES")
      ?.takeIf { it.isRegularFile() }
      ?.let { library.addPath(LibraryPathType.SOURCE, it.pathString) }

    classesPath.parent?.resolve("${classesPath.nameWithoutExtension}$SUFFIX_JAVADOC")
      ?.takeIf { it.isRegularFile() }
      ?.let { library.addPath(LibraryPathType.DOC, it.pathString) }

    return library
  }

  /**
   * Parse a Maven-style library name from a classpath entry path.
   *
   * Expected path format: `{librariesRoot}/group/artifact/version/artifact-version.jar`
   * Output format: `group:artifact:version`
   *
   * The CLI prints absolute paths, so the repository root is located anywhere in the entry rather than only at its
   * start; entries outside a Maven repository layout fall back to a name derived from the file itself.
   *
   * @param classpathEntry The classpath entry path.
   * @param librariesRoot The library root marker to strip.
   * @return The parsed library name, never null (see [generateFallbackLibraryName]).
   */
  internal fun parseLibraryName(classpathEntry: String, librariesRoot: String): String {
    val entry = classpathEntry.replace('\\', '/')
    val markers = listOf(
      librariesRoot.replace('\\', '/').let { if (it.endsWith('/')) it else "$it/" },
      DEFAULT_LIBRARIES_ROOT,
    ).distinct()

    val localName = markers.firstNotNullOfOrNull { marker ->
      entry.indexOf(marker).takeIf { it >= 0 }?.let { entry.substring(it + marker.length) }
    }?.substringBeforeLast('/') // strip file name
      ?: return generateFallbackLibraryName(entry)

    val versionIndex = localName.lastIndexOf('/')
    if (versionIndex <= 0) return generateFallbackLibraryName(entry)

    val artifactIndex = localName.lastIndexOf('/', versionIndex - 1)
    if (artifactIndex < 0 || artifactIndex >= versionIndex - 1) return generateFallbackLibraryName(entry)

    val groupPath = localName.substring(0, artifactIndex)
    val artifact = localName.substring(artifactIndex + 1, versionIndex)
    val version = localName.substring(versionIndex + 1)

    if (groupPath.isEmpty() || artifact.isEmpty() || version.isEmpty()) return generateFallbackLibraryName(entry)

    return "${groupPath.replace('/', '.')}:$artifact:$version"
  }

  /**
   * Generate a fallback library name from the classpath entry when standard parsing fails.
   */
  internal fun generateFallbackLibraryName(classpathEntry: String): String {
    val fileName = classpathEntry.replace('\\', '/').substringAfterLast('/')
    val name = fileName.removeSuffix(".$SUFFIX_JAR")
    return if (name.isNotEmpty()) "unknown:$name:unknown" else "unknown:library:unknown"
  }
}
