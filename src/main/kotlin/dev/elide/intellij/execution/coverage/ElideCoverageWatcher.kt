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

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.elide.intellij.settings.ElideSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Notices the coverage of runs the IDE did not start.
 *
 * `elide test --coverage` writes its reports whoever started it, so a run from a terminal — the IDE's own included —
 * or from a run configuration whose command line already carries the flag produces exactly the same files as "Run
 * with Coverage" does. Watching for them is what makes coverage appear after any of those, rather than only after a
 * run the IDE drove itself.
 *
 * The reports are looked for rather than listened for. They live in the project's output directory, which is under
 * no content root — the plugin's modules are rooted at the source folders the manifest declares — so the IDE's
 * virtual file system never learns of them and raises no events when they change. What a check costs is a directory
 * listing of the two report directories, which hold a handful of files between them; only a report that actually
 * changed is merged and attached, which [ElideCoverageService] decides.
 */
@Service(Service.Level.PROJECT)
class ElideCoverageWatcher(private val project: Project, private val scope: CoroutineScope) {
  private val started = AtomicBoolean(false)

  /** Starts checking the linked projects for new coverage; subsequent calls do nothing. */
  fun start() {
    if (!started.compareAndSet(false, true)) return

    scope.launch {
      while (isActive) {
        delay(CHECK_INTERVAL_MILLIS)

        val service = ElideCoverageService.getInstance(project)
        for (linkedProject in ElideSettings.getSettings(project).linkedProjectsSettings) {
          service.scheduleAttach(linkedProject.externalProjectPath ?: continue)
        }
      }
    }
  }

  internal companion object {
    fun getInstance(project: Project): ElideCoverageWatcher = project.service()

    /** How often the report directories are checked; a run's coverage shows up within this of the run finishing. */
    private const val CHECK_INTERVAL_MILLIS = 5_000L
  }
}
