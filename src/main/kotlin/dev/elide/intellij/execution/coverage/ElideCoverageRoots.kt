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
package dev.elide.intellij.execution.coverage

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import dev.elide.intellij.settings.ElideSettings

/**
 * The directory an Elide project's coverage is reported under.
 *
 * Everything the coverage tool window shows hangs off this directory: it is the root of its tree, and the topmost
 * directory the annotator records figures for. The linked Elide project is the right answer rather than the IDE's
 * own guess at a project directory, which for a project whose modules are rooted at source folders — as Elide's
 * are — lands on one of those folders and hides everything beside it.
 */
internal object ElideCoverageRoots {
  /** Returns the linked Elide project holding [file], or the IDE's project directory when none does. */
  fun of(project: Project, file: VirtualFile): VirtualFile? {
    val fileSystem = LocalFileSystem.getInstance()
    val linked = ElideSettings.getSettings(project).linkedProjectsSettings
      .mapNotNull { it.externalProjectPath?.let(fileSystem::findFileByPath) }
      .filter { VfsUtilCore.isAncestor(it, file, false) }
      // the innermost linked project, for a workspace whose projects nest
      .maxByOrNull { it.path.length }

    return linked ?: ApplicationManager.getApplication().runReadAction(Computable { project.guessProjectDir() })
  }
}
