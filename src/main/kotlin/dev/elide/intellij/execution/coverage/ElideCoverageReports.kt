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
package dev.elide.intellij.execution.coverage

import com.intellij.openapi.diagnostic.Logger
import dev.elide.intellij.Constants
import org.jacoco.core.analysis.Analyzer
import org.jacoco.core.analysis.CoverageBuilder
import org.jacoco.core.analysis.ICounter
import org.jacoco.core.analysis.ISourceFileCoverage
import org.jacoco.core.tools.ExecFileLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.fileSize
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * The coverage a `--coverage` run leaves in an Elide project's output directory, and its merge into the single LCOV
 * report the IDE reads.
 *
 * The CLI writes one file per engine, in two formats: guest coverage is LCOV, keyed by absolute source path, while
 * JVM coverage is a JaCoCo execution file, which carries only probe hits per class and has to be resolved against
 * the compiled classes to say anything about lines. Both become LCOV here, so the IDE sees one report covering every
 * language of the run rather than one suite per engine.
 */
internal object ElideCoverageReports {
  /** Coverage artifacts of a single project, as they exist on disk. */
  data class Artifacts(
    /** Guest coverage reports, already in LCOV format. */
    val lcov: List<Path>,
    /** JaCoCo execution files written by the JVM test runs. */
    val exec: List<Path>,
  ) {
    /** Whether the run produced no coverage at all. */
    val isEmpty: Boolean get() = lcov.isEmpty() && exec.isEmpty()

    /**
     * Identity of this set of files, used to tell a report that has been re-generated from one that has not.
     *
     * Size and modification time stand in for the content: a coverage run rewrites every file it produces, and
     * hashing megabytes of execution data to answer "is this the report I already attached" is not worth it.
     */
    fun fingerprint(): String = buildString {
      for (path in lcov + exec) {
        append(path.absolutePathString()).append(':')
        append(runCatching { path.fileSize() }.getOrDefault(-1L)).append(':')
        append(runCatching { path.getLastModifiedTime().toMillis() }.getOrDefault(-1L)).append('\n')
      }
    }
  }

  /**
   * Returns the coverage artifacts under [projectRoot], ignoring any last modified before [modifiedSince].
   *
   * The threshold is what keeps a run that produced no coverage of its own from attaching the report of an earlier
   * one: the files stay in the output directory until the next `--coverage` run overwrites them.
   */
  fun discover(projectRoot: Path, modifiedSince: Long = 0L): Artifacts {
    val output = projectRoot.resolve(Constants.OUTPUT_DIR)

    return Artifacts(
      lcov = files(output.resolve(REPORTS_DIR).resolve(COVERAGE_DIR), modifiedSince) { it.name == LCOV_NAME },
      exec = files(output.resolve(ARTIFACTS_DIR).resolve(COVERAGE_DIR), modifiedSince) { it.extension == EXEC_EXT },
    )
  }

  /**
   * Merges the coverage under [projectRoot] into one LCOV report, or returns `null` when nothing could be read.
   *
   * Guest reports are already LCOV and are taken almost as they are; records for a source that appears in more than
   * one of them are merged by the reader, which unions their line hits. JVM coverage is resolved against the
   * project's compiled classes for its lines and against [sourceRoots] for the files those lines belong to: JaCoCo
   * knows a source only by package and file name, while an LCOV record names an absolute path.
   */
  fun merge(projectRoot: Path, artifacts: Artifacts, sourceRoots: List<Path>): String? {
    if (artifacts.isEmpty) return null

    // the CLI resolves the project directory before recording a source path, so a project reached through a symlink
    // is described in terms of the link's target; the IDE knows the same files under the path it opened, and a
    // record naming the other one belongs to no file it can annotate
    val canonicalRoot = runCatching { projectRoot.toRealPath() }.getOrNull()?.takeIf { it != projectRoot }
    val classRoots = classRoots(projectRoot)

    val report = buildString {
      for (path in artifacts.lcov) {
        val text = runCatching { path.readText() }.getOrElse { cause ->
          LOG.warn("Failed to read guest coverage report $path", cause)
          continue
        }

        appendGuestReport(text, projectRoot, canonicalRoot)
      }

      for (path in artifacts.exec) {
        runCatching { appendExecutionData(path, classRoots, sourceRoots) }
          .onFailure { cause -> LOG.warn("Failed to read JVM coverage report $path", cause) }
      }
    }

    return report.ifEmpty { null }
  }

  /** Appends a guest LCOV report, restating the sources it names under [projectRoot] rather than [canonicalRoot]. */
  private fun StringBuilder.appendGuestReport(text: String, projectRoot: Path, canonicalRoot: Path?) {
    if (canonicalRoot == null) {
      append(text)
      // records are separated by `end_of_record` on a line of its own, so a report that does not end in a newline
      // would run into the first record of the next one
      if (!text.endsWith('\n')) append('\n')
      return
    }

    val canonical = canonicalRoot.absolutePathString()
    val opened = projectRoot.absolutePathString()

    for (line in text.trimEnd('\n').lineSequence()) {
      if (line.startsWith(SOURCE_FILE_PREFIX) && line.startsWith(canonical, SOURCE_FILE_PREFIX.length)) {
        append(SOURCE_FILE_PREFIX).append(opened).append(line, SOURCE_FILE_PREFIX.length + canonical.length, line.length)
      } else {
        append(line)
      }
      append('\n')
    }
  }

  /** Returns the directories under [projectRoot] holding the classes a JaCoCo execution file describes. */
  private fun classRoots(projectRoot: Path): List<Path> =
    listOf(projectRoot.resolve(Constants.OUTPUT_DIR).resolve(JVM_DIR).resolve(CLASSES_DIR)).filter { it.exists() }

  /** Appends the LCOV records described by the JaCoCo execution file at [exec]. */
  private fun StringBuilder.appendExecutionData(exec: Path, classRoots: List<Path>, sourceRoots: List<Path>) {
    val loader = ExecFileLoader()
    loader.load(exec.toFile())

    val coverage = CoverageBuilder()
    val analyzer = Analyzer(loader.executionDataStore, coverage)
    for (root in classRoots) analyzer.analyzeAll(root.toFile())

    for (source in coverage.sourceFiles) {
      // a class compiled without debug information carries no line table, and nothing about it can be shown in an
      // editor; the console report the CLI prints still counts it
      if (source.firstLine < FIRST_LINE) continue
      val path = locate(source, sourceRoots) ?: continue

      append(SOURCE_FILE_PREFIX).append(path).append('\n')
      for (line in source.firstLine..source.lastLine) {
        val status = source.getLine(line).status
        if (status == ICounter.EMPTY) continue

        // LCOV counts executions, JaCoCo only knows whether a line was reached: a partly covered line (one branch
        // of a condition taken) is a line that ran
        append(LINE_HITS_PREFIX).append(line).append(',')
        append(if (status == ICounter.NOT_COVERED) 0 else 1).append('\n')
      }
      append(END_OF_RECORD).append('\n')
    }
  }

  /**
   * Returns the absolute path of the source JaCoCo describes as [source], or `null` when no source root holds it.
   *
   * A source that cannot be resolved is dropped rather than recorded under its package path: an LCOV record naming
   * `polyglot/Greeter.kt` points at no file the IDE can annotate, and would only show up in the coverage view as a
   * row that navigates nowhere.
   */
  private fun locate(source: ISourceFileCoverage, sourceRoots: List<Path>): String? {
    val relative = source.packageName?.takeIf { it.isNotEmpty() }?.let { "$it/${source.name}" } ?: source.name

    return sourceRoots.asSequence()
      .map { it.resolve(relative).normalize() }
      .firstOrNull { it.exists() }
      ?.absolutePathString()
  }

  /** Returns the regular files under [directory] that satisfy [predicate] and are not older than [modifiedSince]. */
  private fun files(directory: Path, modifiedSince: Long, predicate: (Path) -> Boolean): List<Path> {
    if (!directory.exists()) return emptyList()

    return runCatching {
      Files.walk(directory).use { paths ->
        paths.filter { Files.isRegularFile(it) && predicate(it) && it.getLastModifiedTime().toMillis() >= modifiedSince }
          .toList()
      }
    }.getOrElse { cause ->
      LOG.warn("Failed to scan coverage reports under $directory", cause)
      emptyList()
    }
  }

  /** Directory under the project output holding the reports the CLI writes for humans and tools. */
  private const val REPORTS_DIR = "reports"

  /** Directory under the project output holding build artifacts, including JaCoCo execution data. */
  private const val ARTIFACTS_DIR = "artifacts"

  /** Directory holding coverage under both [REPORTS_DIR] and [ARTIFACTS_DIR]. */
  private const val COVERAGE_DIR = "coverage"

  /** Directory under the project output holding JVM build outputs. */
  private const val JVM_DIR = "jvm"

  /** Directory under [JVM_DIR] holding compiled classes, one subdirectory per source set and compiler. */
  private const val CLASSES_DIR = "classes"

  /** Name of a guest coverage report. */
  private const val LCOV_NAME = "lcov.info"

  /** Extension of a JaCoCo execution file. */
  private const val EXEC_EXT = "exec"

  /** Lowest line number a source file can report; JaCoCo answers `-1` for a class without line information. */
  private const val FIRST_LINE = 1

  private const val SOURCE_FILE_PREFIX = "SF:"
  private const val LINE_HITS_PREFIX = "DA:"
  private const val END_OF_RECORD = "end_of_record"

  @JvmStatic private val LOG = Logger.getInstance(ElideCoverageReports::class.java)
}
