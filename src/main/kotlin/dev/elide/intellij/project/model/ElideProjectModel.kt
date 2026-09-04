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
import com.intellij.openapi.roots.DependencyScope
import com.intellij.openapi.util.io.toCanonicalPath
import dev.elide.intellij.Constants
import dev.elide.project.manifest.effectiveType
import dev.elide.project.manifest.paths
import dev.elide.project.manifest.resources
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
    val name: String,
    val sourceSet: SourceSet,
    val module: DataNode<ModuleData>,
    val contentRoots: MutableList<ContentRootData>,
  )

  fun buildModel(
    projectPath: Path,
    classpaths: Map<String, ElideClasspath>,
    manifest: ProjectModule,
  ): DataNode<ProjectData> {
    val projectData = ProjectData(
      /* owner = */ Constants.SYSTEM_ID,
      /* externalName = */ manifest.name ?: projectPath.nameWithoutExtension,
      /* ideProjectFileDirectoryPath = */ projectPath.resolve(".idea").pathString,
      /* linkedExternalProjectPath = */ projectPath.pathString,
    )
    val projectNode = DataNode(ProjectKeys.PROJECT, projectData, null)

    val rootModule = ModuleData(
      /* id = */ projectData.id,
      /* owner = */ Constants.SYSTEM_ID,
      /* moduleTypeId = */ "JAVA_MODULE",
      /* externalName = */ projectData.externalName,
      /* moduleFileDirectoryPath = */ projectPath.resolve(".idea").toCanonicalPath(),
      /* externalConfigPath = */ projectPath.resolve(Constants.MANIFEST_NAME).toCanonicalPath(),
    )

    projectNode.createChild(ProjectKeys.MODULE, rootModule)

    // Determine library root from manifest or use default
    val librariesRoot = manifest.dependencies.maven.localRepository?.let { customPath ->
      if (customPath.endsWith("/")) customPath else "$customPath/"
    } ?: DEFAULT_LIBRARIES_ROOT

    // add project library data from classpath info
    val libraries = classpaths.mapValues { (_, value) ->
      value.entries.distinct().map { buildLibraryData(it, projectPath, librariesRoot) }
    }

    libraries.values.asSequence().flatMap { it }.distinctBy { it.getPaths(LibraryPathType.BINARY) }.forEach {
      projectNode.createChild(ProjectKeys.LIBRARY, it)
    }

    // the JDK is selected once here and applied to the project and every module; contributors must not re-run it
    val jdkName = ElideJdkSelector.selectJdk(manifest)

    // build modules for each source set
    val modules = manifest.sources.entries.map { (name, sourceSet) ->
      buildSourceSetModule(projectNode, projectPath, libraries, name, sourceSet, jdkName)
    }

    // add module dependencies based on source set types
    configureModuleDependencies(modules)

    // add resources roots from JVM source sets
    for (module in modules) {
      configureResourceRoots(projectPath, module)
    }

    projectNode.createChild(ProjectSdkData.KEY, ProjectSdkData(jdkName))

    // attached additional data so we can finish the import after the project is resolved
    projectNode.createChild(ElideProjectData.PROJECT_KEY, ElideProjectData.from(manifest))

    // invoke registered contributors
    invokeContributors(projectNode, projectPath, manifest)

    return projectNode
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

  private fun configureModuleDependencies(modules: List<SourceSetModel>) {
    val mainModules = modules.filter { it.sourceSet.effectiveType(it.name) == SourceSetType.Source }
    val otherModules = modules.filter { it.sourceSet.effectiveType(it.name) != SourceSetType.Source }

    // other modules depend on main modules
    for (test in otherModules) {
      for (main in mainModules) {
        val data = ModuleDependencyData(test.module.data, main.module.data)
        data.scope = DependencyScope.TEST
        test.module.createChild(ProjectKeys.MODULE_DEPENDENCY, data)
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

  private fun buildSourceSetModule(
    projectNode: DataNode<ProjectData>,
    projectPath: Path,
    libraries: Map<String, List<LibraryData>>,
    sourceSetName: String,
    sourceSet: SourceSet,
    jdkName: String?,
  ): SourceSetModel {
    val module = ModuleData(
      /* id = */ sourceSetName,
      /* owner = */ Constants.SYSTEM_ID,
      /* moduleTypeId = */ "JAVA_MODULE",
      /* externalName = */ sourceSetName,
      /* moduleFileDirectoryPath = */ projectPath.resolve(".idea/modules").toCanonicalPath(),
      /* externalConfigPath = */ projectPath.resolve(Constants.MANIFEST_NAME).toCanonicalPath(),
    )

    val moduleNode = projectNode.createChild(ProjectKeys.MODULE, module)

    // Determine effective source set type (handle "test" name convention)
    val effectiveType = sourceSet.effectiveType(sourceSetName)

    // add library dependencies using the scope implied by the source set type; `Other` sets are not compiled by the
    // IDE, so they take no libraries at all
    val scope = when (effectiveType) {
      SourceSetType.Source, SourceSetType.Example -> DependencyScope.COMPILE
      SourceSetType.Test -> DependencyScope.TEST
      SourceSetType.Other -> null
    }

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

    return SourceSetModel(sourceSetName, sourceSet, moduleNode, contentRoots.toMutableList())
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
