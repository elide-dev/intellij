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
package dev.elide.intellij.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import dev.elide.intellij.Constants
import dev.elide.intellij.service.ElideDistributionResolver.Companion.defaultDistributionPath
import dev.elide.intellij.service.ElideDistributionResolver.Companion.getElideHome
import dev.elide.intellij.service.ElideDistributionResolver.Companion.resourcesPath
import dev.elide.intellij.service.ElideDistributionResolver.Companion.validateDistributionPath
import dev.elide.intellij.settings.ElideDistributionSetting
import dev.elide.intellij.settings.ElideSettings
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * Service used to resolve an Elide distribution for a project; use the static [getElideHome] function to obtain a
 * distribution path that respects project configuration, with a fallback to [defaultDistributionPath].
 *
 * For simple validation cases, [validateDistributionPath] can be used to verify that a minimal distribution structure
 * is present in the selected Elide home directory.
 */
@Service(Service.Level.PROJECT)
class ElideDistributionResolver(private val project: Project) {
  /**
   * Resolve the path to the preferred Elide distribution for the linked project at [externalProjectPath]. If no linked
   * settings are found, [defaultDistributionPath] is returned instead.
   *
   * The returned path is *not* validated by [validateDistributionPath] or in any other way; it is the responsibility
   * of the caller to properly check that the path correspond to a valid Elide distribution before using it as such.
   */
  fun resolveDistributionPath(externalProjectPath: String): Path {
    val settings = ElideSettings.getSettings(project)
      .getLinkedProjectSettings(externalProjectPath)
      ?: return defaultDistributionPath()

    return when (settings.elideDistributionType) {
      ElideDistributionSetting.Custom -> Path(settings.elideDistributionPath).normalize()
      ElideDistributionSetting.AutoDetect -> defaultDistributionPath()
    }
  }

  companion object {
    /**
     * Returns the path to the preferred Elide distribution for a linked external project, or the
     * [defaultDistributionPath] if no project settings are found.
     *
     * The returned path is *not* validated by [validateDistributionPath] or in any other way; it is the responsibility
     * of the caller to properly check that the path correspond to a valid Elide distribution before using it as such.
     */
    @JvmStatic fun getElideHome(project: Project, externalProjectPath: String): Path {
      return project.getService(ElideDistributionResolver::class.java).resolveDistributionPath(externalProjectPath)
    }

    /**
     * Returns the default path to the Elide installation, resolved using platform-specific conventions. Resolution
     * order:
     *
     * 1. `$ELIDE_HOME` environment variable, if set.
     * 2. First candidate directory (in platform priority order) that already exists on disk.
     * 3. Platform-appropriate default (regardless of whether it exists yet).
     *
     * **Unix (Linux / macOS) candidates**, in order:
     * - `$XDG_DATA_HOME/elide` (when `XDG_DATA_HOME` is set)
     * - `~/.local/share/elide` (XDG base-dir default)
     * - `/opt/elide/current` (system-wide deb/rpm/DMG install symlink)
     * - `~/.elide` (legacy)
     *
     * **Windows candidates**, in order:
     * - `%LOCALAPPDATA%\elide` (shell installer default)
     * - `%ProgramFiles%\Elide` (MSI installer)
     * - `%USERPROFILE%\.local\share\elide` (fallback when `LOCALAPPDATA` is absent)
     * - `%USERPROFILE%\.elide` (legacy)
     *
     * Note that the returned path is not guaranteed to contain a valid distribution or even exist on disk.
     */
    @JvmStatic fun defaultDistributionPath(): Path {
      System.getenv("ELIDE_HOME")?.takeIf { it.isNotBlank() }?.let { return Path(it) }
      return if (isWindows()) resolveWindowsDefaultPath() else resolveUnixDefaultPath()
    }

    /**
     * Shorthand for resolving the [resourcesPath] in the [preferred Elide distribution][getElideHome] for a linked
     * external project.
     */
    @JvmStatic fun resourcesPath(project: Project, externalProjectPath: String): Path {
      val elideHome = getElideHome(project, externalProjectPath)
      return elideHome.resolve(Constants.ELIDE_RESOURCES_DIR)
    }

    /** Returns the path to the resources directory inside the given Elide distribution. */
    @JvmStatic fun resourcesPath(elideHome: Path): Path {
      return elideHome.resolve(Constants.ELIDE_RESOURCES_DIR)
    }

    /** Lightly validates an Elide distribution [path], verifying some basic directories and files are presents. */
    @JvmStatic fun validateDistributionPath(path: Path): Boolean {
      if (!path.resolve(Constants.ELIDE_RESOURCES_DIR).isDirectory()) return false
      if (!path.resolve(Constants.ELIDE_BINARIES_DIR).resolve(Constants.ELIDE_BINARY).isRegularFile()) return false

      return true
    }

    private fun isWindows(): Boolean =
      System.getProperty("os.name")?.lowercase()?.contains("windows") == true

    private fun resolveUnixDefaultPath(): Path {
      val userHome = System.getProperty("user.home")
      val xdgDataHome = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
      val candidates = buildList {
        xdgDataHome?.let { add(Path(it).resolve(Constants.ELIDE_HOME)) }
        add(Path(userHome).resolve(".local/share/${Constants.ELIDE_HOME}"))
        add(Path("/opt/elide/current"))
        add(Path(userHome).resolve(".${Constants.ELIDE_HOME}"))
      }
      return candidates.firstOrNull { it.isDirectory() }
        ?: xdgDataHome?.let { Path(it).resolve(Constants.ELIDE_HOME) }
        ?: Path(userHome).resolve(".local/share/${Constants.ELIDE_HOME}")
    }

    private fun resolveWindowsDefaultPath(): Path {
      val userHome = System.getProperty("user.home")
      val localAppData = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
      val programFiles = System.getenv("ProgramFiles")?.takeIf { it.isNotBlank() }
      val candidates = buildList {
        localAppData?.let { add(Path(it).resolve(Constants.ELIDE_HOME)) }
        programFiles?.let { add(Path(it).resolve("Elide")) }
        add(Path(userHome).resolve(".local/share/${Constants.ELIDE_HOME}"))
        add(Path(userHome).resolve(".${Constants.ELIDE_HOME}"))
      }
      return candidates.firstOrNull { it.isDirectory() }
        ?: localAppData?.let { Path(it).resolve(Constants.ELIDE_HOME) }
        ?: Path(userHome).resolve(".local/share/${Constants.ELIDE_HOME}")
    }
  }
}
