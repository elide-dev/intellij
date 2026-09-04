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
package dev.elide.intellij.startup

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.util.ExternalSystemConstants
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.project.BaseProjectDirectories.Companion.getBaseDirectories
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.io.toCanonicalPath
import com.intellij.openapi.util.registry.Registry
import dev.elide.intellij.Constants
import dev.elide.intellij.settings.ElideProjectSettings
import dev.elide.intellij.settings.ElideSettings
import dev.elide.intellij.settings.ElideSettingsListener
import java.util.concurrent.atomic.AtomicBoolean

/** Startup activity used to detect an Elide project and sync it if needed. */
class ElideStartupActivity : ProjectActivity {
  override suspend fun execute(project: Project) {
    // request to run external system APIs in-process, as opposed to calling them in a separate background process;
    // this simplifies the setup of components like the project resolver, and allows them to use the full intellij API
    // (there is no dedicated API for this: `ExternalSystemApiUtil.isInProcessMode` reads the same registry key)
    val inProcessKey = Registry.get(
      Constants.SYSTEM_ID.id + ExternalSystemConstants.USE_IN_PROCESS_COMMUNICATION_REGISTRY_KEY_SUFFIX,
    )
    if (!inProcessKey.asBoolean()) inProcessKey.setValue(true)

    // subscriptions are owned by a project service, so a second run of this activity (project reopened within the
    // same IDE session) does not stack duplicate listeners
    val tracker = project.getService(ElideAutoLinkTracker::class.java)
    tracker.subscribeToSettingsChanges(project)

    // every base directory holding a manifest is a linked Elide project, not just the first one found
    for (baseDir in project.getBaseDirectories()) {
      LOG.debug("Searching for Elide manifest in base dir $baseDir")
      baseDir.findChild(Constants.MANIFEST_NAME) ?: continue

      val externalProjectPath = baseDir.toNioPath().toCanonicalPath()

      // have the IDE track changes to the project config files, then trigger a sync
      LOG.debug("Found manifest, linking project at $externalProjectPath")
      val projectSettings = ElideSettings.getSettings(project)
        .getLinkedProjectSettings(externalProjectPath)
        ?: ElideProjectSettings().also { it.externalProjectPath = externalProjectPath }

      // NOTE: `ImportSpecBuilder` exists on every supported build, but its `withPreviewMode` setter and the
      // `linkExternalProject(settings, ImportSpec)` / `refreshProject(path, ImportSpec)` overloads do not (251),
      // so the deprecated -- but present everywhere -- parameter lists are used instead
      ExternalSystemUtil.linkExternalProject(
        /* externalSystemId = */ Constants.SYSTEM_ID,
        /* projectSettings = */ projectSettings,
        /* project = */ project,
        /* importResultCallback = */ { },
        /* isPreviewMode = */ false,
        /* progressExecutionMode = */ ProgressExecutionMode.IN_BACKGROUND_ASYNC,
      )
    }
  }

  /**
   * Project service owning the settings subscription installed by [ElideStartupActivity].
   *
   * Using a service as the parent disposable ties the subscription to the project lifetime and gives the activity a
   * place to record that it already subscribed, so a second run of the activity (project reopened within the same
   * IDE session) does not stack duplicate listeners.
   */
  @Service(Service.Level.PROJECT)
  class ElideAutoLinkTracker : Disposable {
    private val subscribed = AtomicBoolean(false)

    fun subscribeToSettingsChanges(project: Project) {
      if (!subscribed.compareAndSet(false, true)) return

      project.messageBus.connect(this).subscribe(
        topic = ElideSettings.getSettings(project).changesTopic,
        handler = object : ElideSettingsListener {
          // only re-sync the project whose distribution actually changed
          override fun onDistributionChange(linkedProjectPath: String) {
            @Suppress("DEPRECATION")
            ExternalSystemUtil.refreshProject(
              /* project = */ project,
              /* externalSystemId = */ Constants.SYSTEM_ID,
              /* externalProjectPath = */ linkedProjectPath,
              /* isPreviewMode = */ false,
              /* progressExecutionMode = */ ProgressExecutionMode.IN_BACKGROUND_ASYNC,
            )
          }
        },
      )
    }

    override fun dispose() = Unit
  }

  private companion object {
    @JvmStatic private val LOG = Logger.getInstance(ElideStartupActivity::class.java)
  }
}
