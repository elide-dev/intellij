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
import dev.elide.intellij.project.model.ElideBuildTaskInfo
import dev.elide.intellij.project.model.ElideClasspath
import dev.elide.intellij.project.model.ElideClasspathUsage
import dev.elide.intellij.service.ElideDistributionResolver
import dev.elide.intellij.project.ElideWorkspaces
import dev.elide.intellij.settings.ElideSettings
import com.intellij.util.execution.ParametersListUtil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import java.nio.file.Files

/**
 * Bridge service used to invoke the Elide CLI on a configured distribution. Use [ElideCommandLine.at] to manually set
 * the path to the distribution or [ElideCommandLine.resolve] to automatically retrieve the path from the project
 * settings.
 */
class ElideCommandLine private constructor(
  private val elideHome: Path,
  private val workDir: Path? = null,
  private val buildFlags: List<String> = emptyList(),
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
    val elideBin = ElideDistributionResolver.executablePath(elideHome)
    if (!elideBin.isRegularFile()) throw InvalidElideHomeException(elideHome)

    val command = buildList {
      add(elideBin.toString())
      for (flag in buildFlags) {
        add("-f")
        add(flag)
      }
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

  /** Resolve CLI-relative paths against the queried project, not the IDE's working directory. */
  internal fun absoluteClasspath(entries: List<String>): List<String> = entries.map {
    val path = Path.of(it)
    (if (path.isAbsolute) path else (workDir ?: Path.of("")).resolve(path)).normalize().toString()
  }

  private fun installationSelection(classifiers: List<String>): String = JsonObject(mapOf(
    "classifiers" to JsonArray(classifiers.map(::JsonPrimitive)),
    "flags" to JsonArray(buildFlags.map(::JsonPrimitive)),
  )).toString()

  internal suspend fun isInstallationCurrent(classifiers: List<String>): Boolean {
    val root = workDir ?: return false
    return withContext(Dispatchers.IO) {
      runCatching {
        Files.readString(root.resolve(".dev/ide/intellij-install-classifiers")) == installationSelection(classifiers)
      }.getOrDefault(false)
    }
  }

  internal suspend fun recordInstalledClassifiers(classifiers: List<String>) {
    val root = workDir ?: return
    withContext(Dispatchers.IO) {
      val marker = root.resolve(".dev/ide/intellij-install-classifiers")
      Files.createDirectories(marker.parent)
      Files.writeString(marker, installationSelection(classifiers))
    }
  }

  companion object {
    /** Upper bound on the amount of standard error output retained for failure messages. */
    private const val STDERR_CAPTURE_LIMIT = 64 * 1024

    /** Time given to a cancelled process to exit gracefully before it is killed. */
    private const val TERMINATION_GRACE_MILLIS = 5_000L

    /** Returns an [ElideCommandLine] at the given [elideHome], optionally using [workDir] when invoking commands. */
    @JvmStatic fun at(
      elideHome: Path,
      workDir: Path? = null,
      buildFlags: List<String> = emptyList(),
    ): ElideCommandLine {
      return ElideCommandLine(elideHome, workDir, buildFlags)
    }

    /** Returns an [ElideCommandLine] configured according to a linked external project's settings. */
    @JvmStatic fun resolve(project: Project, externalProjectPath: String, workDir: Path? = null): ElideCommandLine {
      val linked = ElideWorkspaces.linkedRoot(project, externalProjectPath) ?: externalProjectPath
      val settings = ElideSettings.getSettings(project).getLinkedProjectSettings(linked)
      return at(
        ElideDistributionResolver.getElideHome(project, externalProjectPath),
        workDir,
        ParametersListUtil.parse(settings?.buildFlags.orEmpty()),
      )
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

suspend fun ElideCommandLine.install(
  classifiers: List<String> = listOf("sources"),
  onOutput: ((line: String, stderr: Boolean) -> Unit)? = null,
) {
  val args = buildList {
    add("install")
    add("--slim")
    for (classifier in classifiers) {
      add("--with")
      add(classifier)
    }
  }
  invoke(args = args.toTypedArray(), onOutput = onOutput)
  recordInstalledClassifiers(classifiers)
}

/**
 * Read the project manifest with `elide project export`, falling back to `elide manifest` on a CLI that predates the
 * export command (before 1.6). The first attempt's standard error is held back, so a fallback leaves no usage error in
 * the sync log.
 */
suspend fun ElideCommandLine.manifest(
  onOutput: ((line: String, stderr: Boolean) -> Unit)? = null,
): ProjectModule {
  val held = mutableListOf<String>()
  var fallback = false

  try {
    return readManifest("project", "export") { line, stderr ->
      if (stderr) synchronized(held) { held.add(line) } else onOutput?.invoke(line, false)
    }
  } catch (cause: ElideCommandFailedException) {
    if (!cause.isUnknownSubcommand) throw cause
    fallback = true
  } finally {
    if (!fallback) synchronized(held) { held.forEach { onOutput?.invoke(it, true) } }
  }

  return readManifest("manifest", onOutput = onOutput)
}

/** Whether the CLI rejected a subcommand it does not have: a usage error (exit `2`) naming it unrecognized. */
internal val ElideCommandFailedException.isUnknownSubcommand: Boolean
  get() = exitCode == 2 && "unrecognized subcommand" in stderr

private suspend fun ElideCommandLine.readManifest(
  vararg args: String,
  onOutput: ((line: String, stderr: Boolean) -> Unit)?,
): ProjectModule {
  val output = StringBuilder()

  invoke(args = args) { line, stderr ->
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

  return ElideClasspath(usage, absoluteClasspath(ElideCommandLine.parseClasspath(output.toString())))
}

/**
 * Ask a recent CLI for all usages in one project evaluation. Only an explicit rejection of `--json` falls back;
 * dependency and malformed-output failures must remain failures rather than being hidden by another invocation.
 */
suspend fun ElideCommandLine.classpaths(
  queries: List<Pair<String, ElideClasspathUsage>>,
  onOutput: ((line: String, stderr: Boolean) -> Unit)? = null,
): Map<Pair<String, ElideClasspathUsage>, ElideClasspath> {
  if (queries.isEmpty()) return emptyMap()
  val unique = queries.distinct()
  val output = StringBuilder()
  val diagnostics = mutableListOf<String>()
  var fallback = false
  try {
    invoke(args = (listOf("classpath", "--json") + unique.map { (set, usage) ->
      "$set:${usage.name.lowercase()}"
    }).toTypedArray()) { line, stderr ->
      if (stderr) diagnostics.add(line) else output.append(line)
    }
  } catch (cause: ElideCommandFailedException) {
    if (cause.exitCode != 2 || "--json" !in cause.stderr ||
      !(cause.stderr.contains("unexpected argument") || cause.stderr.contains("unrecognized option"))) throw cause
    fallback = true
    return unique.associateWith { (set, usage) -> classpath(set, usage, onOutput) }
  } finally {
    if (!fallback) diagnostics.forEach { onOutput?.invoke(it, true) }
  }
  val json = Json.parseToJsonElement(output.toString()) as? JsonObject
    ?: error("Elide classpath --json returned no object")
  return unique.associateWith { (set, usage) ->
    val query = "$set:${usage.name.lowercase()}"
    val values = json[query] as? JsonArray ?: error("Elide classpath did not answer $query")
    val entries = values.map {
      val value = it as? JsonPrimitive
      require(value?.isString == true) { "Elide classpath returned a non-string entry for $query" }
      value.content
    }
    ElideClasspath(usage, absoluteClasspath(entries))
  }
}

/**
 * List the tasks the project's build graph declares, by way of `elide build --inspect`.
 *
 * `--inspect` only prints the task table, it builds nothing. The flag must stay its own token: `elide build` reads
 * `--inspect=…` as a build target instead, which starts a real build.
 */
suspend fun ElideCommandLine.buildTasks(
  onOutput: ((line: String, stderr: Boolean) -> Unit)? = null,
): List<ElideBuildTaskInfo> {
  val output = StringBuilder()

  invoke("build", "--inspect") { line, stderr ->
    onOutput?.invoke(line, stderr)
    if (!stderr) output.append(line)
  }

  return ElideBuildTasks.parse(output.toString())
}

/**
 * List the project templates the distribution ships, by way of `elide init --templates --json`.
 *
 * `--json` is newer than the flags around it: a distribution whose CLI predates it rejects the command as a usage
 * error, which surfaces as an [ElideCommandFailedException] carrying the CLI's own diagnostic.
 */
suspend fun ElideCommandLine.templates(
  onOutput: ((line: String, stderr: Boolean) -> Unit)? = null,
): List<ElideTemplate> {
  val output = StringBuilder()

  invoke("init", "--templates", "--json") { line, stderr ->
    onOutput?.invoke(line, stderr)
    if (!stderr) output.append(line)
  }

  return ElideTemplates.parse(output.toString().trim())
}

/**
 * Generate the project template identified by [templateId] into this command line's working directory, answering its
 * questionnaire with [answers] (parameter or block id to value).
 *
 * `elide init` is interactive by default: `--plain` trades its full-screen editor for prompts, and `--skip-defaults`
 * keeps those prompts from being asked at all, so every parameter left out of [answers] takes its declared default
 * instead of blocking on a terminal that does not exist here. `--skip-run` suppresses the build/test/run follow-ups,
 * and the `--` separator keeps answers that begin with a dash from being read as flags.
 *
 * `--overwrite` is deliberately never passed: a directory that already holds files a template claims fails with the
 * CLI's own diagnostic rather than losing them.
 */
suspend fun ElideCommandLine.init(
  templateId: String,
  answers: Map<String, String>,
  onOutput: ((line: String, stderr: Boolean) -> Unit)? = null,
) {
  val args = buildList {
    add("init")
    add("--plain")
    add("--skip-defaults")
    add("--skip-run")
    add("--template")
    add(templateId)
    add("--")
    answers.forEach { (id, value) -> add("$id=$value") }
  }

  invoke(args = args.toTypedArray(), onOutput = onOutput)
}
