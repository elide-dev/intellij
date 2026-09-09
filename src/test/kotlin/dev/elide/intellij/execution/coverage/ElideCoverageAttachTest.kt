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
import com.intellij.openapi.application.PathManager
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import com.intellij.testFramework.junit5.fixture.sourceRootFixture
import dev.elide.intellij.execution.ElideExternalTaskConfigurationType
import dev.elide.intellij.execution.ElideRunConfiguration
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.io.path.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Covers attaching the coverage of a run the IDE did not start, end to end: the reports on disk become the project's
 * active coverage suite, holding the lines the run measured.
 *
 * The order the suite is registered in matters as much as the merge does — registering a suite deletes the report of
 * the one it replaces, which is the same file — so this exercises the service rather than its parts.
 */
@TestApplication
class ElideCoverageAttachTest {
  private val projectFixture = projectFixture()
  private val moduleFixture = projectFixture.moduleFixture()
  private val sourceRootFixture = moduleFixture.sourceRootFixture()
  private val sourceFileFixture = sourceRootFixture.psiFileFixture("app.ts", "export const answer = 42\n")

  @Test fun `reports left by a run become the active coverage suite`() = runBlocking {
    val project = projectFixture.get()
    val sourceFile = assertNotNull(sourceFileFixture.get().virtualFile)
    val projectPath = assertNotNull(project.basePath)

    val report = Path(projectPath).resolve(".dev/reports/coverage/js/lcov.info")
    report.createParentDirectories()
    report.writeText("TN:\nSF:${sourceFile.path}\nDA:1,2\nDA:2,0\nend_of_record\n")

    ElideCoverageService.getInstance(project).attachExternalReports(projectPath)

    val manager = CoverageDataManager.getInstance(project)
    val bundle = assertNotNull(manager.currentSuitesBundle, "no coverage suite was activated")
    val suite = bundle.suites.single()

    assertIs<ElideCoverageSuite>(suite)

    val coverage = assertNotNull(suite.getCoverageData(manager), "the attached suite has no coverage data")
    val fileCoverage = assertNotNull(coverage.getClassData(sourceFile.path), "no coverage for ${sourceFile.path}")

    assertEquals(2, fileCoverage.getLineData(1).hits)
    assertEquals(0, fileCoverage.getLineData(2).hits)

    // a later run replaces the suite of the earlier one, and does so without asking the user anything: there is
    // nothing to merge, and the platform's merge question is asked every time while the option is at its default
    report.writeText("TN:\nSF:${sourceFile.path}\nDA:1,9\nend_of_record\n")
    ElideCoverageService.getInstance(project).attachExternalReports(projectPath)

    val replaced = assertNotNull(manager.currentSuitesBundle).suites.single()
    val replacedCoverage = assertNotNull(replaced.getCoverageData(manager))

    assertEquals(9, assertNotNull(replacedCoverage.getClassData(sourceFile.path)).getLineData(1).hits)
  }

  @Test fun `a coverage run reports into a suite of its own configuration`() = runBlocking {
    val project = projectFixture.get()
    val sourceFile = assertNotNull(sourceFileFixture.get().virtualFile)
    val projectPath = assertNotNull(project.basePath)

    val report = Path(projectPath).resolve(".dev/reports/coverage/js/lcov.info")
    report.createParentDirectories()
    report.writeText("TN:\nSF:${sourceFile.path}\nDA:1,7\nend_of_record\n")

    val configuration = ElideRunConfiguration(project, ElideExternalTaskConfigurationType.configurationFactory, "tests")
    configuration.settings.externalProjectPath = projectPath
    configuration.rawCommandLine = "test"

    ElideCoverageService.getInstance(project)
      .attachRunReports(configuration, startedAt = System.currentTimeMillis())

    val manager = CoverageDataManager.getInstance(project)
    val suite = assertNotNull(
      waitForSuite(manager) { it.presentableName.contains(configuration.name) },
      "the run's coverage was not attached to its configuration",
    )

    // the report the suite reads is the platform's own copy, not the one in the project's output directory: a suite
    // over the latter asks the user before replacing it
    assertTrue(suite.coverageDataFileName.startsWith(PathManager.getSystemPath()), suite.coverageDataFileName)

    val coverage = assertNotNull(suite.getCoverageData(manager), "the attached suite has no coverage data")

    assertEquals(7, assertNotNull(coverage.getClassData(sourceFile.path)).getLineData(1).hits)
  }

  /** Waits for a suite matching [predicate] to become active, which the attach does off the calling thread. */
  private suspend fun waitForSuite(
    manager: CoverageDataManager,
    predicate: (CoverageSuite) -> Boolean,
  ): CoverageSuite? {
    repeat(ATTACH_ATTEMPTS) {
      manager.currentSuitesBundle?.suites?.firstOrNull(predicate)?.let { return it }
      delay(ATTACH_POLL_MILLIS)
    }

    return null
  }

  private companion object {
    private const val ATTACH_ATTEMPTS = 100
    private const val ATTACH_POLL_MILLIS = 100L
  }
}
