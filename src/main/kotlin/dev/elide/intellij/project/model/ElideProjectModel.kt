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

import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.project.*
import com.intellij.openapi.projectRoots.ProjectJdkTable
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
  private const val SUFFIX_JAR = "jar"
  private const val SUFFIX_JAVADOC = "-javadoc.$SUFFIX_JAR"
  private const val SUFFIX_SOURCES = "-sources.$SUFFIX_JAR"
  private const val LIBRARIES_ROOT = ".dev/dependencies/m2/"

  private data class SourceSetModel(
    val name: String,
    val sourceSet: SourceSet,
    val module: DataNode<ModuleData>,
    val contentRoots: List<ContentRootData>,
  )

  fun buildModel(
    projectPath: Path,
    classpaths: Map<ElideClasspathUsage, ElideClasspath>,
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

    // add project library data from classpath info
    val libraries = classpaths.mapValues { (_, value) ->
      value.entries.distinct().map { buildLibraryData(it, projectPath) }.toList()
    }

    libraries.values.asSequence().flatMap { it }.distinctBy { it.getPaths(LibraryPathType.BINARY) }.forEach {
      projectNode.createChild(ProjectKeys.LIBRARY, it)
    }

    // build modules for each source set
    val modules = manifest.sources.entries.map { (name, sourceSet) ->
      buildSourceSetModule(projectNode, projectPath, libraries, name, sourceSet)
    }

    // add module dependencies (test modules depend on main modules)
    for (test in modules.filter { it.name == "test" || it.sourceSet.type == SourceSetType.Test }) {
      for (main in modules.filter { it.name != "test" && it.sourceSet.type == SourceSetType.Main }) {
        val data = ModuleDependencyData(test.module.data, main.module.data)
        test.module.createChild(ProjectKeys.MODULE_DEPENDENCY, data)
      }
    }

    // add resources roots to the modules containing them
    for (artifact in manifest.artifacts.values) {
      if (artifact !is ElidePackageManifest.Jar) continue
      collectRoots(projectPath, artifact.resources.values.map { it.path }).forEach { (root, paths) ->
        for (module in modules) {
          val containingRoot = module.contentRoots.asSequence()
            .filter { root.startsWith(it.rootPath) }
            .maxByOrNull { it.rootPath.length }
            ?: continue

          val type = when {
            module.sourceSet.type == SourceSetType.Test || module.name == "test" -> ExternalSystemSourceType.TEST_RESOURCE
            else -> ExternalSystemSourceType.RESOURCE
          }

          if (paths.size == 1 && root == containingRoot.rootPath) containingRoot.storePath(type, paths.single())
          else containingRoot.storePath(type, root)

          break
        }
      }
    }

    // configure the project's JDK
    val jdkName = ProjectJdkTable.getInstance().allJdks.lastOrNull()?.name
    projectNode.createChild(ProjectSdkData.KEY, ProjectSdkData(jdkName))

    // attached additional data so we can finish the import after the project is resolved
    projectNode.createChild(ElideProjectData.PROJECT_KEY, ElideProjectData(manifest))
    return projectNode
  }

  private fun buildSourceSetModule(
    projectNode: DataNode<ProjectData>,
    projectPath: Path,
    libraries: Map<ElideClasspathUsage, List<LibraryData>>,
    sourceSetName: String,
    sourceSet: SourceSet
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

    // add library dependencies
    val classpathUsages = when (if (sourceSetName == "test") SourceSetType.Test else sourceSet.type) {
      SourceSetType.Main,
      SourceSetType.Example -> setOf(ElideClasspathUsage.COMPILE, ElideClasspathUsage.RUNTIME)

      SourceSetType.Test,
      SourceSetType.Integration -> setOf(ElideClasspathUsage.TEST)

      SourceSetType.Docs,
      SourceSetType.Infra,
      SourceSetType.Other -> emptySet()
    }

    for (usage in classpathUsages) libraries[usage]?.forEach { library ->
      val data = LibraryDependencyData(module, library, LibraryLevel.MODULE)
      data.isExported = true
      data.scope = when (usage) {
        ElideClasspathUsage.COMPILE -> DependencyScope.COMPILE
        ElideClasspathUsage.RUNTIME -> DependencyScope.RUNTIME
        ElideClasspathUsage.TEST -> DependencyScope.TEST
      }
      moduleNode.createChild(ProjectKeys.LIBRARY_DEPENDENCY, data)
    }

    val sourceType = when {
      sourceSet.type == SourceSetType.Test || sourceSetName == "test" -> ExternalSystemSourceType.TEST
      else -> ExternalSystemSourceType.SOURCE
    }

    val contentRoots = buildList {
      collectRoots(projectPath, sourceSet.paths).onEach { (root, paths) ->
        val data = ContentRootData(Constants.SYSTEM_ID, root)
        for (path in paths) data.storePath(sourceType, path)

        moduleNode.createChild(ProjectKeys.CONTENT_ROOT, data)
        add(data)
      }
    }

    // configure the module's JDK
    val jdkName = ProjectJdkTable.getInstance().allJdks.lastOrNull()?.name
    moduleNode.createChild(ModuleSdkData.KEY, ModuleSdkData(jdkName))

    return SourceSetModel(sourceSetName, sourceSet, moduleNode, contentRoots)
  }

  private fun buildLibraryData(classpathEntry: String, projectPath: Path): LibraryData {
    val libraryName = buildString {
      val localName = classpathEntry.removePrefix(LIBRARIES_ROOT)
        .substringBeforeLast('/') // strip file name

      val versionIndex = localName.lastIndexOf('/')
      val artifactIndex = localName.lastIndexOf('/', versionIndex - 1)

      append(localName.substring(0, artifactIndex).replace('/', '.'))
      append(":")
      append(localName.substring(artifactIndex + 1, versionIndex))
      append(":")
      append(localName.substring(versionIndex + 1))
    }

    val library = LibraryData(Constants.SYSTEM_ID, libraryName)

    val classesPath = projectPath.resolve(classpathEntry)
    library.addPath(LibraryPathType.BINARY, classesPath.pathString)

    classesPath.parent.resolve("${classesPath.nameWithoutExtension}$SUFFIX_SOURCES")
      .takeIf { it.isRegularFile() }
      ?.let { library.addPath(LibraryPathType.SOURCE, it.pathString) }

    classesPath.parent.resolve("${classesPath.nameWithoutExtension}$SUFFIX_JAVADOC")
      .takeIf { it.isRegularFile() }
      ?.let { library.addPath(LibraryPathType.DOC, it.pathString) }

    return library
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
