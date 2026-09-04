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

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.Key
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.service.project.IdeModifiableModelsProvider
import com.intellij.openapi.externalSystem.service.project.manage.AbstractProjectDataService
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.externalSystem.util.ExternalSystemConstants
import com.intellij.openapi.externalSystem.util.Order
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import dev.elide.intellij.Constants
import dev.elide.intellij.project.model.ElideProjectData
import dev.elide.intellij.project.model.ElideProjectInfo
import dev.elide.intellij.service.elideProjectIndex
import org.jetbrains.kotlin.config.CompilerSettings
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.idea.facet.KotlinFacet
import org.jetbrains.kotlin.idea.facet.KotlinFacetConfigurationImpl
import org.jetbrains.kotlin.idea.facet.KotlinFacetType

/**
 * Data import service used to populate the [project index][dev.elide.intellij.service.ElideProjectIndexService]
 * after a successful project resolution, allowing the index to be persisted between IDE runs without the need to
 * resync the project.
 */
@Order(ExternalSystemConstants.BUILTIN_SERVICE_ORDER)
class ElideProjectDataService : AbstractProjectDataService<ElideProjectData, Project>() {
  override fun getTargetDataKey(): Key<ElideProjectData> = ElideProjectData.PROJECT_KEY

  override fun importData(
    toImport: Collection<DataNode<ElideProjectData?>?>,
    projectData: ProjectData?,
    project: Project,
    modelsProvider: IdeModifiableModelsProvider
  ) {
    if (projectData == null) return
    if (toImport.size > 1) LOG.warn("More than one node to import (${toImport.size}), only the first one will be used")

    val data = toImport.firstOrNull()?.data ?: return

    configureKotlinFacets(data, modelsProvider)

    // the index is rewritten on every sync: manifest edits (new scripts, a renamed main class, removed entrypoints)
    // must reach the gutter producers and completion without deleting the persisted index by hand
    project.elideProjectIndex.update(projectData.linkedExternalProjectPath, ElideProjectInfo.from(data))
  }

  /**
   * Configure a Kotlin facet for the modules being imported.
   *
   * Only modules owned by this external system are touched, and only when the manifest declares Kotlin settings:
   * creating a facet unconditionally would attach Kotlin configuration to plain Java modules and to modules owned by
   * other build systems in the same project.
   */
  private fun configureKotlinFacets(data: ElideProjectData, modelsProvider: IdeModifiableModelsProvider) {
    val kotlinSettings = data.kotlin ?: return

    for (module in modelsProvider.modules) {
      if (!isElideModule(module)) continue

      val facets = modelsProvider.getModifiableFacetModel(module)
      val kotlin = facets.getFacetByType(KotlinFacetType.TYPE_ID)
        ?: KotlinFacet(module, module.name, KotlinFacetConfigurationImpl()).also { facets.addFacet(it) }

      kotlin.configuration.settings.apply {
        useProjectSettings = false
        apiLevel = parseLanguageVersion(kotlinSettings.apiLevel)
        languageLevel = parseLanguageVersion(kotlinSettings.languageLevel)

        compilerSettings = CompilerSettings().apply {
          additionalArguments = kotlinSettings.compilerArguments.joinToString(" ")
        }
      }
    }
  }

  private fun isElideModule(module: Module): Boolean {
    return ExternalSystemApiUtil.isExternalSystemAwareModule(Constants.SYSTEM_ID, module)
  }

  /**
   * Resolve a Kotlin language level declared by the manifest.
   *
   * The manifest admits the symbolic levels Elide understands; `latest` and `stable` are mapped onto the concrete
   * versions the IDE's Kotlin plugin knows about, since [LanguageVersion.fromVersionString] only accepts `x.y`.
   */
  private fun parseLanguageVersion(level: String?): LanguageVersion? = when (level) {
    null -> null
    "latest" -> LanguageVersion.entries.last()
    "stable" -> LanguageVersion.LATEST_STABLE
    else -> LanguageVersion.fromVersionString(level)
  }

  private companion object {
    private val LOG = Logger.getInstance(ElideProjectDataService::class.java)
  }
}
