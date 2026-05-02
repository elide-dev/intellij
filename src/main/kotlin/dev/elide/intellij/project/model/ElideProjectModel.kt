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
import dev.elide.project.manifest.ElidePackageManifest
import dev.elide.project.manifest.ElidePackageManifest.SourceSet
import dev.elide.project.manifest.ElidePackageManifest.SourceSet.SourceSetType
import java.nio.file.Path
import kotlin.io.path.absolutePathString
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
    val contentRoots: List<ContentRootData>,
  )

  fun buildModel(
    projectPath: Path,
    classpaths: Map<String, ElideClasspath>,
    manifest: ElidePackageManifest,
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
      value.entries.distinct().mapNotNull { buildLibraryData(it, projectPath, librariesRoot) }.toList()
    }

    libraries.values.asSequence().flatMap { it }.distinctBy { it.getPaths(LibraryPathType.BINARY) }.forEach {
      projectNode.createChild(ProjectKeys.LIBRARY, it)
    }

    // build modules for each source set
    val modules = manifest.sources.entries.map { (name, sourceSet) ->
      buildSourceSetModule(projectNode, projectPath, libraries, name, sourceSet, manifest)
    }

    // add module dependencies based on source set types
    configureModuleDependencies(modules)

    // add resources roots from JVM source sets
    for (module in modules) {
      val sourceSet = module.sourceSet
      if (sourceSet !is ElidePackageManifest.JvmSourceSet) continue

      val resourcePaths = sourceSet.resources.values.toList()
      if (resourcePaths.isEmpty()) continue

      collectRoots(projectPath, resourcePaths).forEach { (root, paths) ->
        val containingRoot = module.contentRoots.asSequence()
          .filter { root.startsWith(it.rootPath) }
          .maxByOrNull { it.rootPath.length }
          ?: return@forEach

        val type = when {
          sourceSet.type == SourceSetType.Test || module.name == "test" -> ExternalSystemSourceType.TEST_RESOURCE
          else -> ExternalSystemSourceType.RESOURCE
        }

        if (paths.size == 1 && root == containingRoot.rootPath) containingRoot.storePath(type, paths.single())
        else containingRoot.storePath(type, root)
      }
    }

    // configure the project's JDK using intelligent selection
    val jdkName = ElideJdkContributor.selectJdk(manifest)
    projectNode.createChild(ProjectSdkData.KEY, ProjectSdkData(jdkName))

    // attached additional data so we can finish the import after the project is resolved
    projectNode.createChild(ElideProjectData.PROJECT_KEY, ElideProjectData(manifest))

    // invoke registered contributors
    invokeContributors(projectNode, projectPath, manifest)

    return projectNode
  }

  private fun invokeContributors(
    projectNode: DataNode<ProjectData>,
    projectPath: Path,
    manifest: ElidePackageManifest,
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
    val mainModules = modules.filter { it.name != "test" && it.sourceSet.type == SourceSetType.Source }
    val otherModules = modules.filter { it.name == "test" || it.sourceSet.type != SourceSetType.Source }

    // other modules depend on main modules
    for (test in otherModules) {
      for (main in mainModules) {
        val data = ModuleDependencyData(test.module.data, main.module.data)
        data.scope = DependencyScope.TEST
        test.module.createChild(ProjectKeys.MODULE_DEPENDENCY, data)
      }
    }
  }

  private fun buildSourceSetModule(
    projectNode: DataNode<ProjectData>,
    projectPath: Path,
    libraries: Map<String, List<LibraryData>>,
    sourceSetName: String,
    sourceSet: SourceSet,
    manifest: ElidePackageManifest,
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
    val effectiveType = if (sourceSetName == "test") SourceSetType.Test else sourceSet.type

    // add library dependencies based on source set type
    val classpathUsages = when (effectiveType) {
      SourceSetType.Source -> setOf(ElideClasspathUsage.COMPILE, ElideClasspathUsage.RUNTIME)
      SourceSetType.Example -> setOf(ElideClasspathUsage.COMPILE, ElideClasspathUsage.RUNTIME)
      SourceSetType.Test -> setOf(ElideClasspathUsage.TEST)
      SourceSetType.Other -> emptySet()
    }

    for (usage in classpathUsages) libraries[sourceSetName]?.forEach { library ->
      val data = LibraryDependencyData(module, library, LibraryLevel.MODULE)
      data.isExported = true
      data.scope = when (usage) {
        ElideClasspathUsage.COMPILE -> DependencyScope.COMPILE
        ElideClasspathUsage.RUNTIME -> DependencyScope.RUNTIME
        ElideClasspathUsage.TEST -> DependencyScope.TEST
      }
      moduleNode.createChild(ProjectKeys.LIBRARY_DEPENDENCY, data)
    }

    // Determine source type for IntelliJ
    val sourceType = when (effectiveType) {
      SourceSetType.Source -> ExternalSystemSourceType.SOURCE
      SourceSetType.Test -> ExternalSystemSourceType.TEST
      SourceSetType.Example -> ExternalSystemSourceType.SOURCE
      SourceSetType.Other -> ExternalSystemSourceType.EXCLUDED
    }

    val contentRoots = buildList {
      collectRoots(projectPath, sourceSet.paths).onEach { (root, paths) ->
        val data = ContentRootData(Constants.SYSTEM_ID, root)
        for (path in paths) data.storePath(sourceType, path)

        moduleNode.createChild(ProjectKeys.CONTENT_ROOT, data)
        add(data)
      }
    }

    // configure the module's JDK using intelligent selection
    val jdkName = ElideJdkContributor.selectJdk(manifest)
    moduleNode.createChild(ModuleSdkData.KEY, ModuleSdkData(jdkName))

    return SourceSetModel(sourceSetName, sourceSet, moduleNode, contentRoots)
  }

  private fun buildLibraryData(classpathEntry: String, projectPath: Path, librariesRoot: String): LibraryData? {
    val libraryName = parseLibraryName(classpathEntry, librariesRoot)
    if (libraryName == null) {
      LOG.warn("Failed to parse library name from classpath entry: $classpathEntry")
      return null
    }

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
   * @param classpathEntry The classpath entry path.
   * @param librariesRoot The library root prefix to strip.
   * @return The parsed library name, or null if the path is malformed.
   */
  private fun parseLibraryName(classpathEntry: String, librariesRoot: String): String? {
    return try {
      val localName = classpathEntry.removePrefix(librariesRoot)
        .substringBeforeLast('/') // strip file name

      if (localName.isEmpty()) {
        return generateFallbackLibraryName(classpathEntry)
      }

      val versionIndex = localName.lastIndexOf('/')
      if (versionIndex <= 0) {
        return generateFallbackLibraryName(classpathEntry)
      }

      val artifactIndex = localName.lastIndexOf('/', versionIndex - 1)
      if (artifactIndex < 0 || artifactIndex >= versionIndex - 1) {
        return generateFallbackLibraryName(classpathEntry)
      }

      val groupPath = localName.substring(0, artifactIndex)
      val artifact = localName.substring(artifactIndex + 1, versionIndex)
      val version = localName.substring(versionIndex + 1)

      if (groupPath.isEmpty() || artifact.isEmpty() || version.isEmpty()) {
        return generateFallbackLibraryName(classpathEntry)
      }

      "${groupPath.replace('/', '.')}:$artifact:$version"
    } catch (e: StringIndexOutOfBoundsException) {
      LOG.debug("Failed to parse library name from path: $classpathEntry", e)
      generateFallbackLibraryName(classpathEntry)
    }
  }

  /**
   * Generate a fallback library name from the classpath entry when standard parsing fails.
   */
  private fun generateFallbackLibraryName(classpathEntry: String): String {
    val fileName = classpathEntry.substringAfterLast('/')
    val name = fileName.removeSuffix(".$SUFFIX_JAR")
    return if (name.isNotEmpty()) "unknown:$name:unknown" else "unknown:library:unknown"
  }

  private fun collectRoots(
    projectRoot: Path,
    paths: List<String>,
  ): Map<String, Set<String>> {
    if (paths.isEmpty()) return emptyMap()

    // extract the non-glob section from each pattern
    val patternRoots = paths.map { pattern -> extractStaticPrefix(pattern.absolutePath(projectRoot)) }

    // group patterns by their longest common static prefix
    return buildMap<String, MutableSet<String>> {
      for (root in patternRoots) {
        // find if there's an existing root that this should belong to
        val matchingRoot = keys.find { root.startsWith(it) || it.startsWith(root) }

        if (matchingRoot != null) {
          // use the shorter path as the root (most general)
          val actualRoot = if (root.length < matchingRoot.length) {
            // move patterns from old root to new root
            getOrPut(root) { remove(matchingRoot)!! }
            root
          } else {
            matchingRoot
          }
          get(actualRoot)!!.add(root)
        } else {
          getOrPut(root.substringBeforeLast('/')) { mutableSetOf(root) }
        }
      }
    }
  }

  private fun extractStaticPrefix(pattern: String): String {
    val normalized = pattern.replace('\\', '/')

    // Find the first occurrence of glob wildcards
    val wildcardIndex = normalized.indexOfFirst { it == '*' || it == '?' || it == '[' || it == '{' }
    if (wildcardIndex == -1) return normalized

    // Take everything up to the last slash before the wildcard
    val prefix = normalized.substring(0, wildcardIndex)
    val lastSlash = prefix.lastIndexOf('/')

    return if (lastSlash >= 0) prefix.substring(0, lastSlash) else "."
  }

  private fun String.absolutePath(defaultRoot: Path): String {
    return if (startsWith('/')) this else defaultRoot.absolutePathString().removeSuffix("/") + "/$this"
  }
}
