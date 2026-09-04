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

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import dev.elide.project.manifest.majorVersionOrNull
import dev.elide.tooling.manifest.jvm.JvmTargetLevel
import dev.elide.tooling.manifest.project.ProjectModule

/**
 * Selects the JDK used by an imported Elide project, based on the manifest's JVM settings.
 *
 * Selection priority is:
 * 1. `jvm.target` - The target JVM version
 * 2. `jvm.java.release` - The Java release version
 * 3. `jvm.java.source` - The Java source version
 * 4. Fallback to highest available JDK
 *
 * The selected name is applied once, by [ElideProjectModel], to the project node and every module node; nothing else
 * should assign SDK data during import.
 */
object ElideJdkSelector {
  private val LOG = Logger.getInstance(ElideJdkSelector::class.java)

  /**
   * Legacy `1.x` version strings (`1.8.0_292`); the *fractional* component is the feature version.
   *
   * The lookbehind excludes digits **and** dots: `\b1\.` would match the `1.0` inside `21.1.0`, because a dot is a
   * non-word character, and report JDK 21 as version 0.
   */
  private val LEGACY_VERSION = Regex("""(?<![\d.])1\.(\d+)""")

  /** Modern version strings (`21`, `21.0.1`, `openjdk 21.0.1`, `java version "21"`). */
  private val MODERN_VERSION = Regex("""(\d+)(?:\.\d+)*""")

  /** Vendor-prefixed SDK names used as a last resort (`temurin-21`, `GraalVM 21`). */
  private val VENDOR_NAME = Regex(
    """(?:corretto|temurin|graalvm|openjdk|liberica|semeru|zulu|jbr|jdk)[-_ ]?(\d+)""",
    RegexOption.IGNORE_CASE,
  )

  /**
   * Select the most appropriate JDK based on manifest settings.
   *
   * @param manifest The Elide package manifest.
   * @return The name of the selected JDK, or null if no suitable JDK is available.
   */
  fun selectJdk(manifest: ProjectModule): String? {
    // the JDK table is application state, so it is read under a read action; the Kotlin `runReadAction` helpers are
    // avoided because they are not available across the whole supported build range (251+)
    val javaSdks = ApplicationManager.getApplication().runReadAction<List<Sdk>> {
      ProjectJdkTable.getInstance().allJdks.filter { it.sdkType is JavaSdk }
    }
    if (javaSdks.isEmpty()) {
      LOG.warn("No Java SDKs available in IDE")
      return null
    }

    val versions = javaSdks.mapNotNull { sdk -> parseJdkVersion(sdk)?.let { sdk to it } }
    val jvmSettings = manifest.jvm

    val targetVersion = jvmSettings?.let {
      extractTargetVersion(it.target)
        ?: extractTargetVersion(it.java.release)
        ?: extractTargetVersion(it.java.source)
    }

    if (targetVersion == null) {
      LOG.debug("No JVM target version specified in manifest, using fallback")
      return selectFallbackJdk(javaSdks, versions)
    }

    LOG.debug("Looking for JDK matching target version: $targetVersion")
    return selectJdkForVersion(targetVersion, versions) ?: selectFallbackJdk(javaSdks, versions)
  }

  /**
   * Parse the feature version of a JDK from its version string, falling back to its name.
   *
   * Legacy `1.x` strings are matched first: a generic "leading integer" pattern reads `1.8.0_292` as version 1, which
   * silently disqualifies every JDK 8 installation from both exact and compatible matching.
   */
  internal fun parseJdkVersion(versionString: String?, name: String): Int? {
    if (versionString != null) {
      LEGACY_VERSION.find(versionString)?.let { return it.groupValues[1].toIntOrNull() }
      MODERN_VERSION.find(versionString)?.let { return it.groupValues[1].toIntOrNull() }
    }

    // the legacy form is matched before the vendor pattern, which would read `corretto-1.8` as version 1
    LEGACY_VERSION.find(name)?.let { return it.groupValues[1].toIntOrNull() }
    VENDOR_NAME.find(name)?.let { return it.groupValues[1].toIntOrNull() }
    MODERN_VERSION.find(name)?.let { return it.groupValues[1].toIntOrNull() }

    return null
  }

  private fun parseJdkVersion(sdk: Sdk): Int? = parseJdkVersion(sdk.versionString, sdk.name)

  private fun extractTargetVersion(target: JvmTargetLevel?): Int? = target?.majorVersionOrNull()

  private fun selectJdkForVersion(targetVersion: Int, versions: List<Pair<Sdk, Int>>): String? {
    val exactMatch = versions.find { (_, version) -> version == targetVersion }
    if (exactMatch != null) {
      LOG.info("Found exact JDK match for version $targetVersion: ${exactMatch.first.name}")
      return exactMatch.first.name
    }

    // no exact match: use the smallest version that can still compile for the target
    val compatibleJdk = versions
      .filter { (_, version) -> version >= targetVersion }
      .minByOrNull { (_, version) -> version }

    if (compatibleJdk != null) {
      LOG.info("Found compatible JDK for version $targetVersion: ${compatibleJdk.first.name}")
      return compatibleJdk.first.name
    }

    LOG.warn("No JDK found with version >= $targetVersion")
    return null
  }

  private fun selectFallbackJdk(javaSdks: List<Sdk>, versions: List<Pair<Sdk, Int>>): String? {
    return versions.maxByOrNull { (_, version) -> version }?.first?.name ?: javaSdks.lastOrNull()?.name
  }
}
