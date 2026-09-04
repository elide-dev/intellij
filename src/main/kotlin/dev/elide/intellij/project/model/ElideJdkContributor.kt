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

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.model.project.ProjectSdkData
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import dev.elide.project.manifest.majorVersionOrNull
import dev.elide.tooling.manifest.jvm.JvmTargetLevel
import dev.elide.tooling.manifest.project.ProjectModule
import java.nio.file.Path

/**
 * Contributor for selecting the appropriate JDK based on project manifest settings.
 *
 * This contributor analyzes the manifest's JVM settings and selects the most appropriate JDK from those available in
 * the IDE. Selection priority is:
 * 1. `jvm.target` - The target JVM version
 * 2. `jvm.java.release` - The Java release version
 * 3. `jvm.java.source` - The Java source version
 * 4. Fallback to highest available JDK
 */
class ElideJdkContributor : ElideProjectModelContributor {
  override fun contribute(
    projectNode: DataNode<ProjectData>,
    projectPath: Path,
    manifest: ProjectModule,
  ) {
    val jdkName = selectJdk(manifest)
    if (jdkName != null) {
      // Find and update the ProjectSdkData node
      val sdkNode = projectNode.children.find { it.key == ProjectSdkData.KEY }
      if (sdkNode != null) {
        @Suppress("UNCHECKED_CAST")
        val typedNode = sdkNode as DataNode<ProjectSdkData>
        typedNode.data.sdkName = jdkName
      }
    }
  }

  companion object {
    private val LOG = Logger.getInstance(ElideJdkContributor::class.java)

    /**
     * Select the most appropriate JDK based on manifest settings.
     *
     * @param manifest The Elide package manifest.
     * @return The name of the selected JDK, or null if no suitable JDK is available.
     */
    fun selectJdk(manifest: ProjectModule): String? {
      val jvmSettings = manifest.jvm ?: return selectFallbackJdk()

      // Get target version from manifest, checking in priority order
      val targetVersion = extractTargetVersion(jvmSettings.target)
        ?: extractTargetVersion(jvmSettings.java.release)
        ?: extractTargetVersion(jvmSettings.java.source)

      if (targetVersion == null) {
        LOG.debug("No JVM target version specified in manifest, using fallback")
        return selectFallbackJdk()
      }

      LOG.debug("Looking for JDK matching target version: $targetVersion")
      return selectJdkForVersion(targetVersion) ?: selectFallbackJdk()
    }

    private fun extractTargetVersion(target: JvmTargetLevel?): Int? = target?.majorVersionOrNull()

    private fun selectJdkForVersion(targetVersion: Int): String? {
      val allJdks = ProjectJdkTable.getInstance().allJdks
      val javaSdks = allJdks.filter { it.sdkType is JavaSdk }

      if (javaSdks.isEmpty()) {
        LOG.warn("No Java SDKs available in IDE")
        return null
      }

      // Parse version from each JDK and find the best match
      val jdkVersions = javaSdks.mapNotNull { sdk ->
        val version = parseJdkVersion(sdk)
        if (version != null) sdk to version else null
      }

      // First, try to find an exact match
      val exactMatch = jdkVersions.find { (_, version) -> version == targetVersion }
      if (exactMatch != null) {
        LOG.info("Found exact JDK match for version $targetVersion: ${exactMatch.first.name}")
        return exactMatch.first.name
      }

      // If no exact match, find the smallest version >= target
      val compatibleJdk = jdkVersions
        .filter { (_, version) -> version >= targetVersion }
        .minByOrNull { (_, version) -> version }

      if (compatibleJdk != null) {
        LOG.info("Found compatible JDK for version $targetVersion: ${compatibleJdk.first.name} (version ${compatibleJdk.second})")
        return compatibleJdk.first.name
      }

      // If no compatible JDK found, warn and return null
      LOG.warn("No JDK found with version >= $targetVersion")
      return null
    }

    private fun parseJdkVersion(sdk: Sdk): Int? {
      val versionString = sdk.versionString ?: return null

      // Handle various version string formats:
      // "21" / "21.0.1" / "java version \"21\"" / "openjdk 21.0.1" / "1.8.0_292"
      val patterns = listOf(
        Regex("""(?:java|openjdk|version)?\s*"?(\d+)(?:\.\d+)*"?""", RegexOption.IGNORE_CASE),
        Regex("""^(\d+)(?:\.\d+)*$"""),
        Regex("""^1\.(\d+)\..*$"""),  // Handle 1.8 format
      )

      for (pattern in patterns) {
        val match = pattern.find(versionString)
        if (match != null) {
          return match.groupValues[1].toIntOrNull()
        }
      }

      // Try parsing the SDK name as a fallback
      val namePatterns = listOf(
        Regex("""(?:corretto|temurin|graalvm|openjdk|jdk)[- ]?(\d+)""", RegexOption.IGNORE_CASE),
        Regex("""(\d+)(?:\.\d+)*"""),
      )

      for (pattern in namePatterns) {
        val match = pattern.find(sdk.name)
        if (match != null) {
          return match.groupValues[1].toIntOrNull()
        }
      }

      return null
    }

    private fun selectFallbackJdk(): String? {
      val allJdks = ProjectJdkTable.getInstance().allJdks.filter { it.sdkType is JavaSdk }
      if (allJdks.isEmpty()) {
        LOG.warn("No Java SDKs available for fallback selection")
        return null
      }

      // Select the highest version JDK available
      val bestJdk = allJdks.mapNotNull { sdk ->
        val version = parseJdkVersion(sdk)
        if (version != null) sdk to version else null
      }.maxByOrNull { (_, version) -> version }

      return bestJdk?.first?.name ?: allJdks.lastOrNull()?.name
    }
  }
}
