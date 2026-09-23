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

import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.Key
import com.intellij.openapi.externalSystem.model.project.ExternalProjectPojo
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
import dev.elide.intellij.settings.ElideLocalSettings
import java.nio.file.Path
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

    // one node per project the sync resolved: the linked project, plus every member of the workspace it is the root
    // of, each of which is indexed under its own directory so the path-keyed features reach it
    val resolved = toImport.mapNotNull { it?.data }
    if (resolved.isEmpty()) return

    for (data in resolved) configureKotlinFacets(data, modelsProvider)

    // the index is rewritten on every sync: manifest edits (new scripts, a renamed main class, removed entrypoints)
    // must reach the gutter producers and completion without deleting the persisted index by hand
    val index = project.elideProjectIndex
    for (data in resolved) index.update(data.projectPath, ElideProjectInfo.from(data))

    // a member dropped from the workspace manifest is no longer part of this project: its entry would otherwise
    // outlive the sync that removed it and keep offering runs for a project the IDE no longer resolves
    val owned = resolved.mapTo(mutableSetOf()) { it.projectPath }
    index.entries
      .filter { (path, info) -> info.workspaceRoot == projectData.linkedExternalProjectPath && path !in owned }
      .forEach { (path, _) -> index.remove(path) }

    publishAvailableProjects(project, projectData, resolved)
  }

  /**
   * Record the projects of the workspace as the ones this external system offers, keyed by the linked project they
   * were synced with.
   *
   * The platform's own project choosers read this rather than the plugin's index — the working directory of an Elide
   * run configuration first among them — and a workspace member is never linked, so without it a user creating a
   * configuration by hand is offered nothing to point it at but a file chooser.
   *
   * Only the entry of the linked project being imported is rewritten: another Elide project linked into the same
   * IDE project keeps the projects its own sync recorded.
   */
  private fun publishAvailableProjects(
    project: Project,
    projectData: ProjectData,
    resolved: List<ElideProjectData>,
  ) {
    val root = resolved.find { it.projectPath == projectData.linkedExternalProjectPath } ?: return
    val members = resolved.filter { it !== root }.map(::projectPojo)

    val settings = ExternalSystemApiUtil.getLocalSettings<ElideLocalSettings>(project, Constants.SYSTEM_ID)
    val others = settings.availableProjects.filterKeys { it.path != projectData.linkedExternalProjectPath }

    settings.availableProjects = others + (projectPojo(root) to members)
  }

  /** The project [data] describes, named the way Elide names it: by its manifest, else by its directory. */
  private fun projectPojo(data: ElideProjectData): ExternalProjectPojo {
    val path = Path.of(data.projectPath)
    return ExternalProjectPojo(data.name ?: path.fileName?.toString() ?: data.projectPath, data.projectPath)
  }

  /**
   * Configure a Kotlin facet for the modules of the project [data] describes.
   *
   * Only modules owned by this external system are touched, and only the ones of this project: every project of a
   * workspace declares its own Kotlin settings, and a facet built from the wrong manifest would pin the wrong
   * language level. Nothing is created when the manifest declares no Kotlin settings, since a facet attached
   * unconditionally would add Kotlin configuration to plain Java modules.
   */
  private fun configureKotlinFacets(data: ElideProjectData, modelsProvider: IdeModifiableModelsProvider) {
    val kotlinSettings = data.kotlin ?: return

    for (module in modelsProvider.modules) {
      if (!isElideModule(module)) continue
      if (ExternalSystemApiUtil.getExternalProjectPath(module) != data.projectPath) continue

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
}
