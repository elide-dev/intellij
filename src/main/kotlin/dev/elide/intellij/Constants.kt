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
package dev.elide.intellij

import com.intellij.DynamicBundle
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.util.IconLoader
import dev.elide.intellij.Constants.Strings.get
import org.jetbrains.annotations.PropertyKey
import javax.swing.Icon

/** Useful constants used by the Elide plugin. */
object Constants {
  /** External System ID for Elide. */
  val SYSTEM_ID = ProjectSystemId("ELIDE")

  /** Elide plugin ID. */
  const val PLUGIN_ID = "dev.elide"

  /** ID used to reference settings panels. */
  const val CONFIGURABLE_ID = "reference.settingsdialog.project.elide"

  /** Name and extension of the Elide manifest file. */
  const val MANIFEST_NAME = "elide.pkl"

  /** Name of the project directory where the lockfile and other artifacts are placed. */
  const val OUTPUT_DIR = ".dev"

  /** Name of the directory under [OUTPUT_DIR] where installed dependencies are placed. */
  const val DEPENDENCIES_DIR = "dependencies"

  /** Prefix shared by every Elide lockfile name. */
  const val LOCKFILE_PREFIX = "elide.lock"

  /** Extension used by Elide lockfiles. */
  const val LOCKFILE_EXTENSION = ".bin"

  /**
   * Returns whether [fileName] names an Elide lockfile.
   *
   * The lockfile carries a format version in its name (`elide.lock.v2.bin` as of Elide 1.5), so the version segment
   * is matched loosely instead of pinning a single file name the CLI may bump.
   */
  @JvmStatic fun isLockfileName(fileName: String): Boolean {
    return fileName.startsWith(LOCKFILE_PREFIX) && fileName.endsWith(LOCKFILE_EXTENSION)
  }

  /** Directory name for the Elide distribution root; used as a path segment when constructing platform-specific install paths. */
  const val ELIDE_HOME = "elide"

  /** Resources path relative to the root of the Elide distribution. */
  const val ELIDE_RESOURCES_DIR = "resources"

  /** Binaries path relative to the root of the Elide distribution. */
  const val ELIDE_BINARIES_DIR = "bin"

  /** Relative path to the CLI binary in an Elide distribution. */
  const val ELIDE_BINARY = "elide"

  /** Browser URL for the installation section of the documentation. */
  const val INSTALL_URL = "https://docs.elide.dev/installation"

  // command names
  const val COMMAND_RUN = "run"
  const val COMMAND_BUILD = "build"
  const val COMMAND_INSTALL = "install"
  const val COMMAND_SERVE = "serve"
  const val COMMAND_TEST = "test"

  /** Flag for `elide test` that narrows the run to tests whose `pkg.Class#method` id matches a Java regex. */
  const val FLAG_TEST_NAME_PATTERN = "--test-name-pattern"

  /** Commands available to all projects by default. */
  val DEFAULT_COMMANDS = arrayOf(COMMAND_BUILD, COMMAND_INSTALL, COMMAND_RUN, COMMAND_SERVE, COMMAND_TEST)

  /**
   * Flag that turns on the CLI's debugging features.
   *
   * For JVM entrypoints, `elide run --debugger` launches the guest JVM with
   * `-agentlib:jdwp=transport=dt_socket,server=y,suspend=y`: the CLI owns the socket and the program stays suspended
   * until a debugger dials in.
   */
  const val FLAG_DEBUGGER = "--debugger"

  /** Host the CLI's JDWP server is reachable at; the plugin only ever runs the CLI locally. */
  const val DEBUGGER_HOST = "127.0.0.1"

  /** Port the CLI's JDWP server listens on by default. */
  const val DEBUGGER_PORT = 5005

  /** Descriptor for a file chooser to be used when selecting an Elide project. */
  @JvmStatic fun projectFileChooser(): FileChooserDescriptor {
    return FileChooserDescriptor(
      /* chooseFiles = */ true,
      /* chooseFolders = */ false,
      /* chooseJars = */ false,
      /* chooseJarsAsFiles = */ false,
      /* chooseJarContents = */ false,
      /* chooseMultiple = */ false,
    ).withFileFilter { it.name == MANIFEST_NAME }
  }

  /** Descriptor for a file chooser to be used when selecting an Elide distribution. */
  @JvmStatic fun sdkFileChooser(): FileChooserDescriptor {
    return FileChooserDescriptor(
      /* chooseFiles = */ false,
      /* chooseFolders = */ true,
      /* chooseJars = */ false,
      /* chooseJarsAsFiles = */ false,
      /* chooseJarContents = */ false,
      /* chooseMultiple = */ false,
    )
  }

  data object Icons {
    /** Generic Icon for Elide. */
    @JvmStatic val ELIDE = load("/icons/elide.svg")

    /** Load an icon at the given [path] from the plugin resources. */
    private fun load(path: String): Icon {
      return IconLoader.getIcon(path, Icons::class.java)
    }
  }

  /**
   * Localized strings provided by a resource bundle, use the indexing operator or the static [get] function to obtain
   * formatted messages.
   */
  data object Strings : DynamicBundle("i18n.Strings") {
    @JvmStatic operator fun get(@PropertyKey(resourceBundle = "i18n.Strings") key: String, vararg params: Any): String {
      return getMessage(key, params = params)
    }
  }
}
