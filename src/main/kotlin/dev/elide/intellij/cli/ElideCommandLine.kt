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
import dev.elide.intellij.ElideCommandFailedException
import dev.elide.intellij.InvalidElideHomeException
import dev.elide.intellij.project.model.ElideClasspath
import dev.elide.intellij.project.model.ElideClasspathUsage
import dev.elide.intellij.service.ElideDistributionResolver
import dev.elide.project.manifest.ElideManifests
import dev.elide.tooling.manifest.project.ProjectModule
import java.io.File
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
   * returned, an [ElideCommandFailedException] carrying the captured standard error output is thrown.
   *
   * Both output streams are always drained, regardless of whether [onOutput] is set: a chatty child filling an unread
   * pipe buffer would otherwise block forever. Cancelling the calling coroutine terminates the child process.
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

    // the readers are intentionally *not* children of the calling coroutine: `forEachLine` blocks uninterruptibly
    // until the stream reaches EOF, so a structured-concurrency scope would wait on them before it could destroy the
    // process it is being cancelled for; instead they are joined in the `finally` block, after the process is dead
    val readers = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val stderr = StringBuilder()

    val stdoutReader = readers.launch {
      process.inputStream.bufferedReader().forEachLine { line -> onOutput?.invoke("$line\n", false) }
    }

    val stderrReader = readers.launch {
      process.errorStream.bufferedReader().forEachLine { line ->
        synchronized(stderr) { if (stderr.length < STDERR_CAPTURE_LIMIT) stderr.appendLine(line) }
        onOutput?.invoke("$line\n", true)
      }
    }

    try {
      val exitCode = process.awaitExit()

      stdoutReader.join()
      stderrReader.join()

      if (exitCode != 0) {
        throw ElideCommandFailedException(command, exitCode, synchronized(stderr) { stderr.toString().trim() })
      }
    } finally {
      withContext(NonCancellable) {
        if (process.isAlive) {
          // the CLI re-executes itself and runs JVM entrypoints in a grandchild process, neither of which exits with
          // the launcher; the handles are collected first, because a dead parent's children are reparented away and
          // stop showing up in `descendants()`
          val tree = buildList {
            add(process.toHandle())
            addAll(process.descendants().toList())
          }

          tree.forEach { it.destroy() }
          withTimeoutOrNull(TERMINATION_GRACE_MILLIS) { process.awaitExit() }
          tree.forEach { if (it.isAlive) it.destroyForcibly() }
        }

        // safe to join now: the process is gone, so both streams are at EOF
        stdoutReader.join()
        stderrReader.join()
      }

      readers.cancel()
    }
  }

  companion object {
    /** Upper bound on the amount of standard error output retained for failure messages. */
    private const val STDERR_CAPTURE_LIMIT = 64 * 1024

    /** Time given to a cancelled process to exit gracefully before it is killed. */
    private const val TERMINATION_GRACE_MILLIS = 5_000L

    /** Returns an [ElideCommandLine] at the given [elideHome], optionally using [workDir] when invoking commands. */
    @JvmStatic fun at(elideHome: Path, workDir: Path? = null): ElideCommandLine {
      return ElideCommandLine(elideHome, workDir)
    }

    /** Returns an [ElideCommandLine] configured according to a linked external project's settings. */
    @JvmStatic fun resolve(project: Project, externalProjectPath: String, workDir: Path? = null): ElideCommandLine {
      return at(ElideDistributionResolver.getElideHome(project, externalProjectPath), workDir)
    }

    /**
     * Split the output of `elide classpath` into individual entries.
     *
     * The CLI prints a platform-native classpath string, so entries are separated by [File.pathSeparator] (`;` on
     * Windows, where a `:` split would also mangle drive letters).
     */
    @JvmStatic fun parseClasspath(output: String): List<String> {
      return output.trim()
        .split(File.pathSeparatorChar)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
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

  return ElideClasspath(usage, ElideCommandLine.parseClasspath(output.toString()))
}
