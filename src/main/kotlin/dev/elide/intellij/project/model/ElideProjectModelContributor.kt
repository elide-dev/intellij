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
package dev.elide.intellij.project.model

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.project.ProjectData
import dev.elide.tooling.manifest.project.ProjectModule
import java.nio.file.Path

/**
 * Extension point for contributing to the Elide project model during import.
 *
 * Contributors are invoked after the base project model is constructed, allowing them to modify or enhance the model
 * with additional data such as JDK configuration, dependencies, or other project-specific settings.
 */
interface ElideProjectModelContributor {
  companion object {
    @JvmField
    val EP_NAME: ExtensionPointName<ElideProjectModelContributor> =
      ExtensionPointName.create("dev.elide.projectModelContributor")
  }

  /**
   * Contribute to the project model after initial construction.
   *
   * @param projectNode The root project data node being constructed.
   * @param projectPath The path to the project root directory.
   * @param manifest The parsed Elide package manifest.
   */
  fun contribute(
    projectNode: DataNode<ProjectData>,
    projectPath: Path,
    manifest: ProjectModule,
  )
}
