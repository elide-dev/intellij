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
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import com.intellij.testFramework.junit5.fixture.sourceRootFixture
import dev.elide.intellij.Constants
import dev.elide.intellij.project.model.ElideProjectInfo
import dev.elide.intellij.service.elideProjectIndex
import dev.elide.intellij.settings.ElideProjectSettings
import dev.elide.intellij.settings.ElideSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.io.path.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Covers the path from a report file appearing on disk to coverage on screen, for a run the IDE did not start —
 * `elide test --coverage` typed into a terminal, the IDE's own included.
 *
 * The reports live in the project's output directory, which is under no content root, so the IDE's virtual file
 * system never learns of them and the plugin looks for them itself; a watcher that stops looking leaves the IDE
 * showing nothing after such a run, and one that only ever looks at the linked root does the same for the members
 * of a workspace, which write their reports beside their own sources.
 */
@TestApplication
class ElideCoverageWatcherTest {
  private val projectFixture = projectFixture()
  private val moduleFixture = projectFixture.moduleFixture()
  private val sourceRootFixture = moduleFixture.sourceRootFixture()
  private val sourceFileFixture = sourceRootFixture.psiFileFixture("app.ts", "export const answer = 42\n")

  @Test fun `a report appearing in a linked project attaches its coverage`() = runBlocking {
    val project = projectFixture.get()
    val sourceFile = assertNotNull(sourceFileFixture.get().virtualFile)
    val projectPath = assertNotNull(project.basePath)

    ElideSettings.getSettings(project).linkProject(ElideProjectSettings().apply { externalProjectPath = projectPath })
    ElideCoverageWatcher.getInstance(project).start()

    val report = Path(projectPath).resolve(".dev/reports/coverage/js/lcov.info")
    report.createParentDirectories()
    report.writeText("TN:\nSF:${sourceFile.path}\nDA:1,4\nend_of_record\n")

    val manager = CoverageDataManager.getInstance(project)
    val suite = assertNotNull(waitForSuite(manager), "the report the run left behind was not attached")
    val coverage = assertNotNull(suite.getCoverageData(manager), "the attached suite has no coverage data")

    assertNotNull(coverage.getClassData(sourceFile.path), "no coverage for ${sourceFile.path}")
    Unit
  }

  @Test fun `a report appearing in a workspace member attaches its coverage`() = runBlocking {
    val project = projectFixture.get()
    val sourceFile = assertNotNull(sourceFileFixture.get().virtualFile)
    val rootPath = assertNotNull(project.basePath)
    val memberPath = "$rootPath/$MEMBER_NAME"

    // only the root of a workspace is ever linked; its members are reached through the index the sync leaves behind
    ElideSettings.getSettings(project).linkProject(ElideProjectSettings().apply { externalProjectPath = rootPath })
    project.elideProjectIndex.update(rootPath, ElideProjectInfo(members = listOf(memberPath)))
    project.elideProjectIndex.update(memberPath, ElideProjectInfo(workspaceRoot = rootPath))

    ElideCoverageWatcher.getInstance(project).start()

    val report = Path(memberPath).resolve(".dev/reports/coverage/js/lcov.info")
    report.createParentDirectories()
    report.writeText("TN:\nSF:${sourceFile.path}\nDA:1,4\nend_of_record\n")

    val manager = CoverageDataManager.getInstance(project)
    val suite = assertNotNull(waitForSuite(manager), "the report the member's run left behind was not attached")

    // the suite is named after the directory whose reports it holds, which is the member rather than the root
    assertEquals(Constants.Strings["coverage.suite", MEMBER_NAME], suite.presentableName)

    val coverage = assertNotNull(suite.getCoverageData(manager), "the attached suite has no coverage data")
    assertNotNull(coverage.getClassData(sourceFile.path), "no coverage for ${sourceFile.path}")
    Unit
  }

  /** Waits for the watcher's next check and the settling delay that follows it. */
  private suspend fun waitForSuite(manager: CoverageDataManager): CoverageSuite? {
    repeat(ATTACH_ATTEMPTS) {
      manager.currentSuitesBundle?.suites?.firstOrNull()?.let { return it }
      delay(ATTACH_POLL_MILLIS)
    }

    return null
  }

  private companion object {
    private const val ATTACH_ATTEMPTS = 300
    private const val ATTACH_POLL_MILLIS = 100L

    /** Directory the workspace member lives in, and the name its coverage suite is presented under. */
    private const val MEMBER_NAME = "app"
  }
}
