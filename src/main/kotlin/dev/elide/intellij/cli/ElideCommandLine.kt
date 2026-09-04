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
package dev.elide.intellij.cli

import com.intellij.openapi.project.Project
import com.intellij.util.io.awaitExit
import dev.elide.intellij.Constants
import dev.elide.intellij.InvalidElideHomeException
import dev.elide.intellij.project.model.ElideClasspath
import dev.elide.intellij.project.model.ElideClasspathUsage
import dev.elide.intellij.service.ElideDistributionResolver
import dev.elide.project.manifest.ElideManifests
import dev.elide.tooling.manifest.project.ProjectModule
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.io.path.isRegularFile

/**
 * Bridge service used to invoke the Elide CLI on a configured distribution. Use [ElideCommandLine.at] to manually set
 * the path to the distribution or [ElideCommandLine.resolve] to automatically retrieve the path from the project
 * settings.
 */
class ElideCommandLine private constructor(
  private val elideHome: Path,
  private val workDir: Path? = null,
) {
  /**
   * Launch the Elide CLI binary as a subprocess and suspend until it finishes executing. If a non-zero exit code is
   * returned, an exception will be thrown.
   *
   * The standard output of the process is collected and returned on exit. To observe both the standard output and
   * error streams, use the [onOutput] callback.
   */
  suspend operator fun invoke(
    vararg args: String,
    environment: Map<String, String>? = null,
    onOutput: ((line: String, stderr: Boolean) -> Unit)? = null,
  ) {
    val elideBin = elideHome.resolve(Constants.ELIDE_BINARIES_DIR).resolve(Constants.ELIDE_BINARY)
    if (!elideBin.isRegularFile()) throw InvalidElideHomeException(elideHome)

    val command = buildList {
      add(elideBin.toString())
      addAll(elements = args)
    }

    val process = withContext(Dispatchers.IO) {
      ProcessBuilder(command)
        .also { if (workDir != null) it.directory(workDir.toFile()) }
        .also { if (environment != null) it.environment().putAll(environment) }
        .start()
    }

    return coroutineScope {
      launch {
        process.inputStream.bufferedReader().forEachLine {
          onOutput?.invoke("$it\n", false)
        }
      }

      if (onOutput != null) launch {
        process.errorStream.bufferedReader().forEachLine {
          onOutput("$it\n", true)
        }
      }

      process.awaitExit().takeIf { it != 0 }?.let {
        error("Command '${command.joinToString(" ")}' failed with exit code $it")
      }
    }
  }

  companion object {
    /** Returns an [ElideCommandLine] at the given [elideHome], optionally using [workDir] when invoking commands. */
    @JvmStatic fun at(elideHome: Path, workDir: Path? = null): ElideCommandLine {
      return ElideCommandLine(elideHome, workDir)
    }

    /** Returns an [ElideCommandLine] configured according to a linked external project's settings. */
    @JvmStatic fun resolve(project: Project, externalProjectPath: String, workDir: Path? = null): ElideCommandLine {
      return at(ElideDistributionResolver.getElideHome(project, externalProjectPath), workDir)
    }
  }
}

suspend fun ElideCommandLine.install(onOutput: ((line: String, stderr: Boolean) -> Unit)? = null) {
  val args = arrayOf("install")

  invoke(args = args, onOutput = onOutput)
}

suspend fun ElideCommandLine.manifest(
  onOutput: ((line: String, stderr: Boolean) -> Unit)? = null,
): ProjectModule {
  val output = StringBuilder()

  invoke("manifest") { line, stderr ->
    onOutput?.invoke(line, stderr)
    if (!stderr) output.append(line)
  }

  return ElideManifests.parse(output.toString().trim())
}

suspend fun ElideCommandLine.classpath(
  sourceSet: String,
  usage: ElideClasspathUsage,
  onOutput: ((line: String, stderr: Boolean) -> Unit)? = null,
): ElideClasspath {
  val output = StringBuilder()

  invoke("classpath", "$sourceSet:${usage.name.lowercase()}") { line, stderr ->
    onOutput?.invoke(line, stderr)
    if (!stderr) output.append(line)
  }

  return ElideClasspath(usage, output.toString().trim().splitToSequence(":"))
}
