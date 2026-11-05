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
import dev.elide.intellij.project.ElideWorkspaces
import dev.elide.intellij.service.ElideDistributionResolver.Companion.defaultDistributionPath
import dev.elide.intellij.service.ElideDistributionResolver.Companion.getElideHome
import dev.elide.intellij.service.ElideDistributionResolver.Companion.resourcesPath
import dev.elide.intellij.service.ElideDistributionResolver.Companion.validateDistributionPath
import dev.elide.intellij.settings.ElideDistributionSetting
import dev.elide.intellij.settings.ElideSettings
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
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
   * Resolve the path to the preferred Elide distribution for the project at [externalProjectPath]. A workspace member
   * inherits the distribution its workspace root configures, since only the root carries linked settings; if no
   * linked project owns the path, [defaultDistributionPath] is returned instead.
   *
   * The returned path is *not* validated by [validateDistributionPath] or in any other way; it is the responsibility
   * of the caller to properly check that the path correspond to a valid Elide distribution before using it as such.
   */
  fun resolveDistributionPath(externalProjectPath: String): Path {
    val linkedRoot = ElideWorkspaces.linkedRoot(project, externalProjectPath) ?: return defaultDistributionPath()
    val settings = ElideSettings.getSettings(project)
      .getLinkedProjectSettings(linkedRoot)
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
     * 2. First valid distribution (in platform priority order); stale directories are ignored.
     * 3. An executable on `PATH`, following symlinks back to its distribution's `bin` directory.
     * 4. Platform-appropriate default (regardless of whether it exists yet).
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
      return detectDistributionPath(System.getenv(), Path(System.getProperty("user.home")), isWindows())
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
      if (!executablePath(path).isRegularFile()) return false

      return true
    }

    private fun isWindows(): Boolean =
      System.getProperty("os.name")?.lowercase()?.contains("windows") == true

    /** The platform-specific CLI binary, shared by discovery and process invocation. */
    @JvmStatic fun executablePath(home: Path): Path =
      home.resolve(Constants.ELIDE_BINARIES_DIR).resolve(if (isWindows()) "elide.exe" else Constants.ELIDE_BINARY)

    internal fun detectDistributionPath(env: Map<String, String>, userHome: Path, windows: Boolean): Path {
      // Explicit selections must reach the normal invalid-home diagnostic, never silently select another install.
      env["ELIDE_HOME"]?.takeIf(String::isNotBlank)?.let { return Path(it) }
      val preferred = env[if (windows) "LOCALAPPDATA" else "XDG_DATA_HOME"]
        ?.takeIf(String::isNotBlank)?.let { Path(it).resolve(Constants.ELIDE_HOME) }
      val fallback = preferred ?: userHome.resolve(".local/share/${Constants.ELIDE_HOME}")
      val candidates = buildList {
        preferred?.let(::add)
        if (windows) {
          env["ProgramFiles"]?.takeIf(String::isNotBlank)?.let { add(Path(it).resolve("Elide")) }
        }
        add(userHome.resolve(".local/share/${Constants.ELIDE_HOME}"))
        if (!windows) add(Path("/opt/elide/current"))
        add(userHome.resolve(".${Constants.ELIDE_HOME}"))
      }
      val binary = if (windows) "elide.exe" else Constants.ELIDE_BINARY
      fun valid(home: Path): Boolean =
        home.resolve(Constants.ELIDE_RESOURCES_DIR).isDirectory() &&
          home.resolve(Constants.ELIDE_BINARIES_DIR).resolve(binary).isRegularFile()

      candidates.firstOrNull(::valid)?.let { return it }
      for (directory in env["PATH"].orEmpty().split(if (windows) ';' else File.pathSeparatorChar)) {
        if (directory.isBlank()) continue
        try {
          val executable = Path(directory).resolve(binary)
          if (!executable.isRegularFile() || (!windows && !Files.isExecutable(executable))) continue
          val real = executable.toRealPath()
          val bin = real.parent ?: continue
          if (bin.fileName?.toString() != Constants.ELIDE_BINARIES_DIR || real.fileName.toString() != binary) continue
          val home = bin.parent ?: continue
          if (valid(home)) return home
        } catch (_: IOException) {
          // A dangling symlink or an inaccessible PATH entry does not hide a later valid installation.
        } catch (_: InvalidPathException) {
          // PATH may include entries that this filesystem cannot represent.
        }
      }
      return fallback
    }
  }
}
