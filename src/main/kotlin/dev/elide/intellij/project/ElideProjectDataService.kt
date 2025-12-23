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
import com.intellij.openapi.externalSystem.util.ExternalSystemConstants
import com.intellij.openapi.externalSystem.util.Order
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.modules
import dev.elide.intellij.project.model.ElideEntrypointInfo
import dev.elide.intellij.project.model.ElidePackageManifest
import dev.elide.intellij.project.model.ElideProjectData
import dev.elide.intellij.project.model.ElideProjectInfo
import dev.elide.intellij.service.elideProjectIndex
import org.jetbrains.kotlin.config.CompilerSettings
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.idea.facet.KotlinFacet
import org.jetbrains.kotlin.idea.facet.KotlinFacetConfigurationImpl

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

    configureKotlinFacet(project, data.kotlinSettings, modelsProvider)

    val projectIndex = project.elideProjectIndex
    if (projectData.linkedExternalProjectPath in projectIndex) return
    projectIndex.update(projectData.linkedExternalProjectPath, collectElideProjectInfo(data))
  }

  private fun configureKotlinFacet(
    project: Project,
    kotlinSettings: ElidePackageManifest.KotlinSettings?,
    modelsProvider: IdeModifiableModelsProvider
  ) {
    project.modules.forEach { module ->
      val facets = modelsProvider.getModifiableFacetModel(module)
      val kotlin = KotlinFacet.get(module) ?: KotlinFacet(module, module.name, KotlinFacetConfigurationImpl()).also {
        facets.addFacet(it)
      }

      kotlin.configuration.apply {
        val kotlinOptions = kotlinSettings ?: return@apply

        settings.useProjectSettings = false
        settings.apiLevel = LanguageVersion.fromVersionString(kotlinOptions.apiLevel.takeUnless { it == "auto" })
        settings.languageLevel = LanguageVersion.fromVersionString(kotlinOptions.languageLevel.takeIf { it == "auto" })

        settings.compilerSettings = CompilerSettings().apply {
          additionalArguments = kotlinOptions.compilerOptions.collect().joinToString(" ")
        }
      }
    }
  }

  private fun collectElideProjectInfo(data: ElideProjectData): ElideProjectInfo {
    val entrypoints = buildList {
      // scripts can be used as tasks
      data.scripts?.forEach { (name, _) ->
        add(ElideEntrypointInfo.script(name))
      }

      // explicit entry points
      data.entrypoints?.forEach { entrypoint ->
        add(ElideEntrypointInfo.generic(entrypoint))
      }

      // JVM main class
      data.jvm?.main?.let { mainClassName ->
        add(ElideEntrypointInfo.jvmMain(mainClassName))
      }
    }

    return ElideProjectInfo(entrypoints)
  }

  private companion object {
    private val LOG = Logger.getInstance(ElideProjectDataService::class.java)
  }
}
