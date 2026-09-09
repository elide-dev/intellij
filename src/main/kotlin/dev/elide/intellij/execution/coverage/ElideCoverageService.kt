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

import com.intellij.coverage.CoverageDataManager
import com.intellij.coverage.CoverageSuite
import com.intellij.coverage.CoverageSuitesBundle
import com.intellij.coverage.DefaultCoverageFileProvider
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import dev.elide.intellij.Constants
import dev.elide.intellij.execution.ElideRunConfiguration
import dev.elide.intellij.ui.ElideNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.name
import kotlin.io.path.writeText

/**
 * Turns the coverage an Elide run leaves on disk into a coverage suite the IDE displays.
 *
 * Two things reach this service: [attachRunReports], for a run the IDE started under the coverage executor, and
 * [scheduleAttach], for a report that appeared on its own — a run from a terminal, or from a run configuration
 * whose command line already carried `--coverage`. Both end in the same place, an [ElideCoverageSuite] over one
 * merged LCOV report, and both go through [ElideCoverageReports] to produce it.
 */
@Service(Service.Level.PROJECT)
class ElideCoverageService(private val project: Project, private val scope: CoroutineScope) {
  /** Report fingerprints already attached, keyed by project path; see [ElideCoverageReports.Artifacts.fingerprint]. */
  private val attached = ConcurrentHashMap<String, String>()

  /** Attach jobs in flight, keyed by project path; a project has at most one. */
  private val pending = ConcurrentHashMap<String, Job>()

  /** Projects whose coverage run the IDE is driving itself, and whose reports it will attach when the run ends. */
  private val running = ConcurrentHashMap.newKeySet<String>()

  /** Records that the IDE started a coverage run of the project at [projectPath]. */
  fun runStarted(projectPath: String) {
    running.add(projectPath)
  }

  /**
   * Attaches the coverage of a run the IDE started, as a suite named after [configuration].
   *
   * Only reports written since [startedAt] count: a run that collected nothing — because it failed before the tests
   * ran, or because the user removed `--coverage` from the command line — must not attach the report of an earlier
   * one, which would silently show stale coverage.
   */
  fun attachRunReports(configuration: ElideRunConfiguration, startedAt: Long) {
    val projectPath = configuration.settings.externalProjectPath

    scope.launch {
      try {
        attach(projectPath, configuration.name, modifiedSince = startedAt - CLOCK_SLACK_MILLIS)
      } finally {
        running.remove(projectPath)
      }
    }
  }

  /**
   * Attaches the coverage of a run the IDE did not start, once the files it writes have settled.
   *
   * Reports arrive file by file — one per engine, plus the JVM execution data written when the test JVM exits — so
   * a check that finds them mid-run waits and looks again: merging halfway through a run would show coverage for
   * whichever engine happened to finish first, and the run's own next check picks the complete set up.
   */
  fun scheduleAttach(projectPath: String) {
    // the IDE's own run attaches its reports itself, under its run configuration's name
    if (projectPath in running) return

    pending.compute(projectPath) { _, previous ->
      // a check that arrives while one is already merging is dropped rather than replacing it: cancelling an
      // attach halfway leaves a half-written report behind the suite that is about to read it
      if (previous?.isActive == true) return@compute previous

      scope.launch {
        val root = Path(projectPath)
        val before = ElideCoverageReports.discover(root).fingerprint()
        delay(SETTLE_DELAY_MILLIS)
        if (ElideCoverageReports.discover(root).fingerprint() != before) return@launch

        attachExternalReports(projectPath)
      }
    }
  }

  /** Merges and attaches the reports under [projectPath], naming the suite after the project directory. */
  internal suspend fun attachExternalReports(projectPath: String) {
    attach(projectPath, Path(projectPath).name, modifiedSince = 0L)
  }

  /**
   * Merges the coverage under [projectPath] and shows it as the suite named after [label].
   *
   * The suite reads a report of the plugin's own, written under the IDE's coverage directory rather than into the
   * project: replacing a suite deletes the report it was reading, and the platform asks the user first when that
   * report lives outside its own directory — a confirmation dialog after every test run is not what "attached
   * automatically" should mean.
   */
  private suspend fun attach(projectPath: String, label: String, modifiedSince: Long) {
    val root = Path(projectPath)
    val artifacts = ElideCoverageReports.discover(root, modifiedSince)
    if (artifacts.isEmpty) {
      LOG.debug("No Elide coverage reports to attach under $root")
      return
    }

    // the same reports can be seen more than once: the IDE refreshes the output directory for reasons of its own,
    // and re-attaching would reset the coverage view (and the user's chosen suite) every time it does
    val fingerprint = artifacts.fingerprint()
    if (attached.put(projectPath, fingerprint) == fingerprint) return

    val report = merge(root, artifacts) ?: return
    val manager = CoverageDataManager.getInstance(project)
    val name = Constants.Strings["coverage.suite", label]
    val target = reportPath(label)

    // registering the suite first, and writing the report only then, is the order the platform's own coverage runs
    // use: registering deletes the report of the suite this one replaces, which is the same file
    val suite: CoverageSuite = withContext(Dispatchers.EDT) {
      manager.addCoverageSuite(
        /* name = */ name,
        /* fileProvider = */ DefaultCoverageFileProvider(target.toFile()),
        /* filters = */ null,
        /* lastCoverageTimeStamp = */ System.currentTimeMillis(),
        /* suiteToMergeWith = */ null,
        /* coverageRunner = */ ElideCoverageRunner.instance,
        /* coverageByTestEnabled = */ false,
        /* branchCoverage = */ false,
      )
    } ?: return

    write(target, report)

    withContext(Dispatchers.EDT) {
      // `coverageGathered` would offer to merge this report with the one it replaces, and ask every time the
      // option is left at its default. There is nothing to merge: an Elide report describes a whole run, and the
      // suite it replaces is the previous run of the same project or configuration, reading the same file
      manager.chooseSuitesBundle(CoverageSuitesBundle(suite))
      ElideNotifications.notifyCoverageAttached(project, name)
    }
  }

  /** Where the merged report of the suite named after [label] is written. */
  private fun reportPath(label: String): Path =
    Path(PathManager.getSystemPath(), COVERAGE_DIR)
      .resolve("${sanitized(project.name)}$SUITE_SEPARATOR${sanitized(label)}.$REPORT_EXTENSION")

  /** [name] with everything that is not safe in a file name replaced. */
  private fun sanitized(name: String): String = name.replace(UNSAFE_IN_FILE_NAME, "_")

  /**
   * Merges the reports under [root] into one LCOV report.
   *
   * Source roots come from the IDE rather than the manifest: they are what a JaCoCo execution file, which names a
   * source only by package and file name, has to be resolved against.
   */
  private suspend fun merge(root: Path, artifacts: ElideCoverageReports.Artifacts): String? {
    val sourceRoots = readAction {
      ProjectRootManager.getInstance(project).contentSourceRoots.mapNotNull {
        runCatching { it.toNioPath() }.getOrNull()
      }
    }

    return withContext(Dispatchers.IO) { ElideCoverageReports.merge(root, artifacts, sourceRoots) }
  }

  private suspend fun write(target: Path, report: String) = withContext(Dispatchers.IO) {
    target.createParentDirectories()
    target.writeText(report)
  }

  internal companion object {
    fun getInstance(project: Project): ElideCoverageService = project.service()

    /** How long to wait for the files of an external run to settle before merging them. */
    private const val SETTLE_DELAY_MILLIS = 1_500L

    /**
     * Slack allowed between the time a run starts and the modification time of the reports it produces.
     *
     * The two clocks are the same one, but a file system that stores whole seconds can date a file written
     * immediately after the run started to just before it.
     */
    private const val CLOCK_SLACK_MILLIS = 2_000L

    /** Directory under the IDE's system path where the platform keeps coverage reports. */
    private const val COVERAGE_DIR = "coverage"

    /** Separator between project and suite in a report file name, as the platform's own configurations use. */
    private const val SUITE_SEPARATOR = "$"

    /** Extension of the merged report, the one LCOV conventionally uses. */
    private const val REPORT_EXTENSION = "info"

    private val UNSAFE_IN_FILE_NAME = Regex("[^A-Za-z0-9._-]")

    @JvmStatic private val LOG = Logger.getInstance(ElideCoverageService::class.java)
  }
}
