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
import dev.elide.intellij.settings.ElideSettings
import java.io.File

/**
 * Maps file system changes onto the linked Elide project they affect, so the IDE can offer (or perform) a re-import.
 *
 * Both the manifest and the lockfile are tracked: a lockfile written by an out-of-IDE `elide install` changes the
 * dependencies the model was built from. Results are cached by the platform through
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

      else -> null
    } ?: return null

    // the change only matters if it belongs to a project the IDE actually tracks; walking up the ancestors covers
    // changes reported through nested paths (e.g. a lockfile in a linked subproject)
    return findLinkedProject(candidate, project)
  }

  override fun getAffectedExternalProjectFiles(projectPath: String?, project: Project): List<File?> {
    if (projectPath == null) return emptyList()

    return buildList {
      File(projectPath, Constants.MANIFEST_NAME).takeIf { it.exists() }?.let { add(it) }

      // the lockfile carries a version in its name, so every candidate in the output directory is watched
      File(projectPath, Constants.OUTPUT_DIR).listFiles { candidate ->
        candidate.isFile && Constants.isLockfileName(candidate.name)
      }?.forEach { add(it) }
    }
  }

  private fun findLinkedProject(start: File, project: Project): String? {
    val settings = ElideSettings.getSettings(project)

    var current: File? = start
    while (current != null) {
      val path = FileUtil.toCanonicalPath(current.path)
      if (path != null && settings.getLinkedProjectSettings(path) != null) return path

      current = current.parentFile
    }

    // not linked (yet): fall back to the directory holding the manifest, which is what the platform links
    return start.takeIf { File(it, Constants.MANIFEST_NAME).isFile }?.let { FileUtil.toCanonicalPath(it.path) }
  }
}
