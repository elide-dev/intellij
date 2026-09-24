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
package dev.elide.intellij.execution

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.service.execution.AbstractExternalSystemTaskConfigurationType
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.elide.intellij.Constants
import dev.elide.intellij.project.ElideWorkspaces
import dev.elide.intellij.settings.ElideSettings
import javax.swing.Icon

/** Extension providing the [ElideRunConfiguration] type. */
class ElideExternalTaskConfigurationType : AbstractExternalSystemTaskConfigurationType(Constants.SYSTEM_ID) {
  override fun getIcon(): Icon {
    return Constants.Icons.ELIDE
  }

  override fun getConfigurationFactoryId(): String = "Elide"
  override fun isDumbAware(): Boolean = true
  override fun isEditableInDumbMode(): Boolean = true

  override fun doCreateConfiguration(
    externalSystemId: ProjectSystemId,
    project: Project,
    factory: ConfigurationFactory,
    name: String
  ): ExternalSystemRunConfiguration {
    val selected = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()

    return ElideRunConfiguration(project, factory, name).apply {
      settings.externalProjectPath = defaultProjectPath(project, selected) ?: project.basePath
    }
  }

  companion object {
    /**
     * Returns the registered Elide configuration type.
     *
     * Every producer and extension resolves the factory through here instead of repeating an unchecked cast of
     * [ExternalSystemUtil.findConfigurationType]'s result.
     */
    @JvmStatic val instance: ElideExternalTaskConfigurationType
      get() = ExternalSystemUtil.findConfigurationType(Constants.SYSTEM_ID) as ElideExternalTaskConfigurationType

    /** Returns the factory used to create [ElideRunConfiguration] instances. */
    @JvmStatic val configurationFactory: ConfigurationFactory get() = instance.factory

    /**
     * Returns the directory a configuration created by hand starts pointed at: the Elide project owning [selected],
     * falling back to the first linked project.
     *
     * A workspace links its root alone, so the linked project is the whole workspace, and a configuration defaulted
     * to it builds and tests every member from wherever the user happens to be working. The file the editor has open
     * says which member that is, and the CLI resolves the same workspace from the member's directory while keeping
     * the run to that member.
     */
    internal fun defaultProjectPath(project: Project, selected: VirtualFile?): String? {
      val owner = selected
        ?.let { runCatching { it.toNioPath() }.getOrNull() }
        ?.let { ElideWorkspaces.owningProject(project, it) }

      return owner ?: ElideSettings.getSettings(project).linkedProjectsSettings.firstOrNull()?.externalProjectPath
    }
  }
}
