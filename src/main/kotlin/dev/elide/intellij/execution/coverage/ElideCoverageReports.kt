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
import javax.xml.XMLConstants
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import org.jacoco.core.tools.ExecFileLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.fileSize
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText

/**
 * The coverage a `--coverage` run leaves in an Elide project's output directory, and its merge into the single LCOV
 * report the IDE reads.
 *
 * Guest coverage is LCOV, keyed by absolute source path. JVM coverage is either JaCoCo execution data resolved
 * against compiled classes or a JaCoCo XML report with instruction and branch counters. Both become LCOV here,
 * so the IDE sees one report covering every language of the run rather than one suite per engine.
 */
internal object ElideCoverageReports {
  /** Coverage artifacts of a single project, as they exist on disk. */
  data class Artifacts(
    /** Guest coverage reports, already in LCOV format. */
    val lcov: List<Path>,
    /** JaCoCo execution files written by the JVM test runs. */
    val exec: List<Path>,
    /** JaCoCo XML reports, usable without compiled classes. */
    val xml: List<Path>,
  ) {
    /** Whether the run produced no coverage at all. */
    val isEmpty: Boolean get() = lcov.isEmpty() && exec.isEmpty() && xml.isEmpty()

    /**
     * Identity of this set of files, used to tell a report that has been re-generated from one that has not.
     *
     * Size and modification time stand in for the content: a coverage run rewrites every file it produces, and
     * hashing megabytes of execution data to answer "is this the report I already attached" is not worth it.
     */
    fun fingerprint(): String = buildString {
      for (path in lcov + exec + xml) {
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
   *
   * Reports are looked for under both coverage directories: Elide 1.6 writes them under [REPORTS_DIR],
   * while earlier releases wrote JVM coverage under [ARTIFACTS_DIR].
   */
  fun discover(projectRoot: Path, modifiedSince: Long = 0L): Artifacts {
    val output = projectRoot.resolve(Constants.OUTPUT_DIR)
    val reports = output.resolve(REPORTS_DIR).resolve(COVERAGE_DIR)
    val artifacts = output.resolve(ARTIFACTS_DIR).resolve(COVERAGE_DIR)
    val directories = listOf(reports, artifacts)
    val discovered = directories.flatMap { dir ->
      files(dir, modifiedSince) { it.name == LCOV_NAME || it.extension == EXEC_EXT || it.extension == XML_EXT }
    }
    // The same task may have outputs in both layouts after a CLI upgrade. Keep only its newest copy.
    fun identity(path: Path): Path {
      val directory = directories.first { path.startsWith(it) }
      val relative = directory.relativize(path)
      return relative.resolveSibling(path.nameWithoutExtension)
    }
    fun newest(extension: String): List<Path> = discovered.filter { it.extension == extension }
      .groupBy(::identity)
      .values.map { copies -> copies.maxBy { it.getLastModifiedTime() } }

    val exec = newest(EXEC_EXT)
    val executionReports = exec.mapTo(HashSet(), ::identity)
    return Artifacts(
      lcov = discovered.filter { it.name == LCOV_NAME },
      exec = exec,
      // XML is another rendering of this task's probes, not another measurement. Never fall back to a possibly
      // stale XML sibling if reading current execution data fails. Filtering by run time happens before pairing.
      xml = newest(XML_EXT).filterNot { identity(it) in executionReports },
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

      val jvm = linkedMapOf<String, MutableMap<Int, LineCoverage>>()
      runCatching { readExecutionData(artifacts.exec, classRoots, sourceRoots) }
        .onSuccess { mergeCounters(jvm, it) }
        .onFailure { LOG.warn("Failed to analyze JVM coverage", it) }

      for (path in artifacts.xml) {
        runCatching { readXmlReport(path, sourceRoots) }
          .onSuccess { mergeCounters(jvm, it) }
          .onFailure { LOG.warn("Failed to read JVM XML coverage report $path", it) }
      }
      for ((source, lines) in jvm) {
        append(SOURCE_FILE_PREFIX).append(source).append('\n')
        for ((line, counters) in lines.toSortedMap()) {
          append(LINE_HITS_PREFIX).append(line).append(',').append(counters.hit).append('\n')
          appendBranches(line, counters.covered, counters.total - counters.covered)
        }
        append(END_OF_RECORD).append('\n')
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

  /** Loads each report atomically, then unions probes before deriving line and branch counters. */
  private fun readExecutionData(
    reports: List<Path>,
    classRoots: List<Path>,
    sourceRoots: List<Path>,
  ): Map<String, Map<Int, LineCoverage>> {
    val loader = ExecFileLoader()
    var loaded = false
    for (path in reports) {
      runCatching {
        // A truncated report may already have supplied probes before load throws. Do not let any of them escape.
        val report = ExecFileLoader().also { it.load(path.toFile()) }
        val entries = report.executionDataStore.contents
        for (entry in entries) {
          loader.executionDataStore.get(entry.id)?.assertCompatibility(entry.id, entry.name, entry.probes.size)
        }
        for (entry in entries) loader.executionDataStore.put(entry)
        loaded = true
      }.onFailure { LOG.warn("Failed to read JVM coverage report $path", it) }
    }
    if (!loaded) return emptyMap()

    val result = linkedMapOf<String, MutableMap<Int, LineCoverage>>()
    val coverage = CoverageBuilder()
    val analyzer = Analyzer(loader.executionDataStore, coverage)
    for (root in classRoots) analyzer.analyzeAll(root.toFile())

    for (source in coverage.sourceFiles) {
      // a class compiled without debug information carries no line table, and nothing about it can be shown in an
      // editor; the console report the CLI prints still counts it
      if (source.firstLine < FIRST_LINE) continue
      val path = locate(source.packageName, source.name, sourceRoots) ?: continue

      val lines = result.getOrPut(path) { linkedMapOf() }
      for (line in source.firstLine..source.lastLine) {
        val status = source.getLine(line).status
        if (status == ICounter.EMPTY) continue

        // LCOV counts executions, JaCoCo only knows whether a line was reached: a partly covered line (one branch
        // of a condition taken) is a line that ran
        val branches = source.getLine(line).branchCounter
        lines[line] = LineCoverage(
          if (status == ICounter.NOT_COVERED) 0 else 1,
          branches.coveredCount,
          branches.totalCount,
        )
      }
    }
    return result
  }

  /** Reads JaCoCo's counters without loading its DTD or resolving entities from the report. */
  private fun readXmlReport(xml: Path, sourceRoots: List<Path>): Map<String, Map<Int, LineCoverage>> {
    val result = linkedMapOf<String, MutableMap<Int, LineCoverage>>()
    val factory = XMLInputFactory.newDefaultFactory().apply {
      setProperty(XMLInputFactory.SUPPORT_DTD, false)
      setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
      setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
      setXMLResolver { _, _, _, _ -> throw XMLStreamException("External XML resources are disabled") }
    }
    Files.newInputStream(xml).use { input ->
      val reader = factory.createXMLStreamReader(input)
      try {
        var depth = 0
        var packageDepth = -1
        var sourceDepth = -1
        var packageName: String? = null
        var source: String? = null
        while (reader.hasNext()) {
          when (reader.next()) {
            XMLStreamConstants.START_ELEMENT -> {
              depth++
              val name = reader.localName
              if (depth == 1 && name != "report") return emptyMap()
              when {
                name == "package" && packageDepth < 0 -> {
                  packageName = reader.getAttributeValue(null, "name")
                  packageDepth = depth
                }
                name == "sourcefile" && depth == packageDepth + 1 -> {
                  sourceDepth = depth
                  source = reader.getAttributeValue(null, "name")?.let { locate(packageName, it, sourceRoots) }
                }
                name == "line" && source != null && depth == sourceDepth + 1 -> {
                  fun counter(name: String): Int =
                    reader.getAttributeValue(null, name)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                  val line = counter("nr")
                  if (line < FIRST_LINE) continue
                  val covered = counter("cb")
                  val counters = LineCoverage(if (counter("ci") > 0) 1 else 0, covered, covered + counter("mb"))
                  val lines = result.getOrPut(source) { linkedMapOf() }
                  lines[line] = lines[line]?.union(counters) ?: counters
                }
              }
            }
            XMLStreamConstants.END_ELEMENT -> {
              if (depth == sourceDepth) {
                source = null
                sourceDepth = -1
              }
              if (depth == packageDepth) {
                packageName = null
                packageDepth = -1
              }
              depth--
            }
          }
        }
      } finally {
        reader.close()
      }
    }
    return result
  }

  private data class LineCoverage(val hit: Int, val covered: Int, val total: Int) {
    // XML has no branch identities. The greatest observed covered count is the only guaranteed union;
    // adding counts could double-count the same branch. Retain the greatest observed denominator as well.
    fun union(other: LineCoverage) =
      LineCoverage(maxOf(hit, other.hit), maxOf(covered, other.covered), maxOf(total, other.total))
  }

  private fun mergeCounters(
    target: MutableMap<String, MutableMap<Int, LineCoverage>>,
    report: Map<String, Map<Int, LineCoverage>>,
  ) {
    for ((source, incoming) in report) {
      val lines = target.getOrPut(source) { linkedMapOf() }
      for ((line, counters) in incoming) lines[line] = lines[line]?.union(counters) ?: counters
    }
  }

  /**
   * Encodes final counts as synthetic LCOV slots, not identities of bytecode branches. Emit these only after
   * merging probes and conservatively combining XML counters, never once per input report.
   */
  private fun StringBuilder.appendBranches(line: Int, covered: Int, missed: Int) {
    for (branch in 0 until covered + missed) {
      append("BRDA:").append(line).append(",0,").append(branch).append(',')
        .append(if (branch < covered) 1 else 0).append('\n')
    }
  }

  /** Resolves a package-relative source only within a configured source root. */
  private fun locate(packageName: String?, name: String, sourceRoots: List<Path>): String? {
    val relative = packageName?.takeIf { it.isNotEmpty() }?.let { "$it/$name" } ?: name
    for (root in sourceRoots) {
      val normalizedRoot = root.toAbsolutePath().normalize()
      val candidate = normalizedRoot.resolve(relative).normalize()
      if (candidate.startsWith(normalizedRoot) && Files.isRegularFile(candidate)) return candidate.absolutePathString()
    }
    return null
  }

  /** Returns the regular files under [directory] that satisfy [predicate] and are not older than [modifiedSince]. */
  private fun files(directory: Path, modifiedSince: Long, predicate: (Path) -> Boolean): List<Path> {
    if (!directory.exists()) return emptyList()

    return runCatching {
      Files.walk(directory).use { paths ->
        paths.filter { Files.isRegularFile(it) && predicate(it) && it.getLastModifiedTime().toMillis() >= modifiedSince }
          .sorted().toList()
      }
    }.getOrElse { cause ->
      LOG.warn("Failed to scan coverage reports under $directory", cause)
      emptyList()
    }
  }

  /** Directory under the project output holding the reports the CLI writes for humans and tools. */
  private const val REPORTS_DIR = "reports"

  /** Directory under the project output holding build artifacts, including JaCoCo execution data before Elide 1.6. */
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

  /** Extension of a JaCoCo XML report. */
  private const val XML_EXT = "xml"

  /** Lowest line number a source file can report; JaCoCo answers `-1` for a class without line information. */
  private const val FIRST_LINE = 1

  private const val SOURCE_FILE_PREFIX = "SF:"
  private const val LINE_HITS_PREFIX = "DA:"
  private const val END_OF_RECORD = "end_of_record"

  @JvmStatic private val LOG = Logger.getInstance(ElideCoverageReports::class.java)
}
