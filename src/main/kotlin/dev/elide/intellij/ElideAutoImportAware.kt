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
package dev.elide.intellij

import com.intellij.openapi.externalSystem.ExternalSystemAutoImportAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import dev.elide.intellij.project.ElideWorkspaces
import dev.elide.intellij.settings.ElideSettings
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import org.pkl.parser.Parser
import org.pkl.parser.ParserError
import org.pkl.parser.syntax.Expr
import org.pkl.parser.syntax.ExtendsOrAmendsClause
import org.pkl.parser.syntax.ImportClause
import org.pkl.parser.syntax.Node

/**
 * Maps file system changes onto the linked Elide project they affect, so the IDE can offer (or perform) a re-import.
 *
 * Both the manifest and the lockfile are tracked: a lockfile written by an out-of-IDE `elide install` changes the
 * dependencies the model was built from. The platform's settings-file tracker compares content CRCs, so the CLI's
 * byte-identical lockfile rewrites do not request a reload. Paths are cached through
 * [CachingExternalSystemAutoImportAware][com.intellij.openapi.externalSystem.service.project.autoimport.CachingExternalSystemAutoImportAware].
 */
class ElideAutoImportAware : ExternalSystemAutoImportAware {
  override fun getAffectedExternalProjectPath(changedFileOrDirPath: String, project: Project): String? {
    val file = File(changedFileOrDirPath)

    val candidate = when {
      // the manifest itself, or the lockfile under the project's output directory
      file.name == Constants.MANIFEST_NAME -> file.parentFile
      Constants.isLockfileName(file.name) && file.parentFile?.name == Constants.OUTPUT_DIR ->
        file.parentFile?.parentFile

      // a directory only matters when it holds a manifest: reacting to any directory under a linked project would
      // mark it out of date on every unrelated file system event
      file.isDirectory && File(file, Constants.MANIFEST_NAME).isFile -> file

      else -> {
        val changed = file.toPath().toAbsolutePath().normalize()
        return ElideSettings.getSettings(project).linkedProjectsSettings.firstNotNullOfOrNull { settings ->
          val root = settings.externalProjectPath ?: return@firstNotNullOfOrNull null
          if (!changed.startsWith(Path.of(root).toAbsolutePath().normalize())) return@firstNotNullOfOrNull null
          root.takeIf { changed in manifestFiles(root, project) }
        }
      }
    } ?: return null

    // the change only matters if it belongs to a project the IDE actually tracks; walking up the ancestors covers
    // changes reported through nested paths (e.g. a lockfile in a linked subproject)
    return findLinkedProject(candidate, project)
  }

  /**
   * The files whose modification marks the project out of date.
   *
   * A workspace's members are listed alongside the root: a member is never a linked project, so the platform would
   * never ask about its manifest on its own, and an edit to it changes the model the root was built from.
   *
   * This is `ExternalSystemAutoImportAware.getAffectedExternalProjectFilePaths`, added in build 262; it carries no
   * `override` because the plugin compiles against 261, where the interface only declares the [File] form. The JVM
   * still dispatches to it on 262, so the deprecated form below is only reached on older builds.
   */
  fun getAffectedExternalProjectFilePaths(projectPath: String?, project: Project): List<Path> {
    if (projectPath == null) return emptyList()

    return buildList {
      addAll(manifestFiles(projectPath, project))

      // the lockfile carries a version in its name, so every candidate in the output directory is watched; a
      // workspace keeps its dependency state at the root, so the members' output directories hold none
      File(projectPath, Constants.OUTPUT_DIR).listFiles { candidate ->
        candidate.isFile && Constants.isLockfileName(candidate.name)
      }?.forEach { add(it.toPath()) }
    }
  }

  override fun getAffectedExternalProjectFiles(projectPath: String?, project: Project): List<File> {
    return getAffectedExternalProjectFilePaths(projectPath, project).map(Path::toFile)
  }

  private fun manifestFiles(projectPath: String, project: Project): Set<Path> =
    LocalPklDependencies.files(
      Path.of(projectPath),
      ElideWorkspaces.projectPaths(project, projectPath).map { Path.of(it).resolve(Constants.MANIFEST_NAME) },
    )

  private fun findLinkedProject(start: File, project: Project): String? {
    val path = FileUtil.toCanonicalPath(start.path) ?: return null
    ElideWorkspaces.linkedRoot(project, path)?.let { return it }

    // not linked (yet): fall back to the directory holding the manifest, which is what the platform links
    return start.takeIf { File(it, Constants.MANIFEST_NAME).isFile }?.let { FileUtil.toCanonicalPath(it.path) }
  }
}

/** Parse syntax only: no Pkl evaluation, URI resolution by the Pkl plugin, or network access. */
internal object LocalPklDependencies {
  fun files(workspace: Path, manifests: List<Path>): Set<Path> {
    val root = workspace.toAbsolutePath().normalize()
    val realRoot = try {
      root.toRealPath()
    } catch (_: IOException) {
      return emptySet()
    }
    val found = linkedSetOf<Path>()
    val pending = ArrayDeque<Path>()
    manifests.forEach { pending.add(it.toAbsolutePath().normalize()) }
    while (pending.isNotEmpty()) {
      val file = pending.removeLast()
      if (!isWithinWorkspace(root, realRoot, file) || !found.add(file)) continue
      val text = try {
        Files.readString(file)
      } catch (_: IOException) {
        // Retain missing references so creating the file can make the model out of date.
        continue
      }
      for (reference in references(text)) {
        localPath(file.parent, reference)?.let(pending::addLast)
      }
    }
    return found
  }

  private fun isWithinWorkspace(root: Path, realRoot: Path, file: Path): Boolean {
    if (!file.startsWith(root)) return false
    var current = root
    for (part in root.relativize(file)) {
      current = current.resolve(part)
      if (Files.isSymbolicLink(current)) return false
    }
    return try {
      !Files.exists(file) || file.toRealPath().startsWith(realRoot)
    } catch (_: IOException) {
      false
    }
  }

  private fun localPath(parent: Path, reference: String): Path? {
    return try {
      val uri = URI(reference)
      if (uri.rawAuthority != null || uri.rawQuery != null || uri.rawFragment != null) return null
      when (uri.scheme) {
        null -> parent.resolve(uri.path)
        "file" -> Path.of(uri)
        else -> return null
      }.toAbsolutePath().normalize()
    } catch (_: URISyntaxException) {
      null
    } catch (_: InvalidPathException) {
      null
    } catch (_: IllegalArgumentException) {
      null
    }
  }

  internal fun references(text: String): List<String> {
    val module = try {
      Parser().parseModule(text)
    } catch (error: ParserError) {
      error.partialParseResult ?: return emptyList()
    }
    val references = mutableListOf<String>()
    val pending = ArrayDeque<Node>()
    pending.add(module)
    while (pending.isNotEmpty()) {
      val node = pending.removeLast()
      when (node) {
        is ExtendsOrAmendsClause -> references.add(node.url.string)
        is ImportClause -> if (!node.isGlob) references.add(node.importStr.string)
        is Expr.ImportExpr -> if (!node.isGlob) references.add(node.importStr.string)
      }
      for (child in node.children().orEmpty()) if (child != null) pending.addLast(child)
    }
    return references
  }
}
