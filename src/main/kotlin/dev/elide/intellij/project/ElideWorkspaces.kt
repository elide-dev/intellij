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

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.toCanonicalPath
import dev.elide.intellij.service.elideProjectIndex
import dev.elide.intellij.settings.ElideSettings
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Relates the Elide projects a sync resolved to the external projects the IDE actually links.
 *
 * A workspace member is never a linked external project: only the root is, and a member is synced, built and
 * configured as part of it. That makes [ElideSettings] unable to describe membership at all — it holds one
 * [ElideProjectSettings][dev.elide.intellij.settings.ElideProjectSettings] per *linked* project — so the persisted
 * index is the source of truth for workspace structure, and linkage is only ever read back from the settings.
 */
internal object ElideWorkspaces {
  /**
   * Returns the canonical path of the innermost Elide project the sync resolved that contains [path], or `null` when
   * no resolved project does.
   *
   * The innermost match is the answer a workspace needs: a file under a member belongs to that member, not to the
   * root whose directory also contains it.
   */
  fun owningProject(project: Project, path: Path): String? {
    // index keys are canonicalized at every write site; apply the same transform before the prefix comparison
    val canonical = Path.of(path.toCanonicalPath())

    return project.elideProjectIndex.entries
      .filter { (candidate, _) ->
        try {
          canonical.startsWith(Path.of(candidate))
        } catch (_: InvalidPathException) {
          false
        }
      }
      .maxByOrNull { (candidate, _) -> candidate.length }
      ?.key
  }

  /**
   * Returns the linked external project owning [externalProjectPath]: the path itself when it is linked, the
   * workspace root indexed for it when it is a member, or the nearest linked ancestor. Returns `null` when no linked
   * project owns it.
   *
   * Everything keyed by a linked project — distribution settings, project-level configuration, the IDE's notion of
   * what is already imported — has to be reached through this, since a member carries none of it.
   */
  fun linkedRoot(project: Project, externalProjectPath: String): String? {
    val settings = ElideSettings.getSettings(project)

    // the settings also answer for a path they know as a module of a linked project, so the owner is the path the
    // matching settings are keyed by rather than the one asked about
    settings.getLinkedProjectSettings(externalProjectPath)?.externalProjectPath?.let { return it }

    val workspaceRoot = project.elideProjectIndex[externalProjectPath]?.workspaceRoot
    if (workspaceRoot != null && settings.getLinkedProjectSettings(workspaceRoot) != null) return workspaceRoot

    // a path the index does not know (a directory below a project, or one synced before the index was written) is
    // still owned by a linked project when one of its ancestors is linked
    var ancestor = try {
      Path.of(externalProjectPath).parent
    } catch (_: InvalidPathException) {
      null
    }

    while (ancestor != null) {
      val candidate = ancestor.toCanonicalPath()
      settings.getLinkedProjectSettings(candidate)?.externalProjectPath?.let { return it }

      ancestor = ancestor.parent
    }

    return null
  }

  /**
   * Returns the canonical paths of the workspace members indexed for the root at [externalProjectPath], empty when it
   * is a standalone project or was never synced.
   */
  fun members(project: Project, externalProjectPath: String): List<String> {
    return project.elideProjectIndex[externalProjectPath]?.members ?: emptyList()
  }
}
