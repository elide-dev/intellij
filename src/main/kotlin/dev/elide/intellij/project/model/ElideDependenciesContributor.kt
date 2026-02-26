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

import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.project.ProjectData
import dev.elide.project.manifest.ElidePackageManifest
import java.nio.file.Path

/**
 * Contributor for processing and configuring project dependencies during Elide project import.
 *
 * This contributor handles any additional dependency configuration that is not covered by the base model construction,
 * such as resolving transitive dependencies or configuring dependency scopes.
 */
class ElideDependenciesContributor : ElideProjectModelContributor {
  override fun contribute(
    projectNode: DataNode<ProjectData>,
    projectPath: Path,
    manifest: ElidePackageManifest,
  ) {
    // Dependencies are already handled in buildModel via classpaths.
    // This contributor is a placeholder for future dependency enhancements such as:
    // - Transitive dependency resolution
    // - Dependency conflict resolution
    // - Optional dependency handling
  }
}
