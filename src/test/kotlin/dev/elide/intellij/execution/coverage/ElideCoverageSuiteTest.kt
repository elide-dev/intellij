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

import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import com.intellij.testFramework.junit5.fixture.sourceRootFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Covers the one thing that decides whether coverage shows up in an editor: that the name the engine answers for a
 * file is the name the report is keyed by.
 *
 * The two are produced by different code — the engine from a `VirtualFile`, the report from the paths the CLI and
 * JaCoCo record — and the platform looks a file up by exact key, so a mismatch means a suite that loads without
 * error and highlights nothing.
 */
@TestApplication
class ElideCoverageSuiteTest {
  private val projectFixture = projectFixture()
  private val moduleFixture = projectFixture.moduleFixture()
  private val sourceRootFixture = moduleFixture.sourceRootFixture()
  private val sourceFileFixture = sourceRootFixture.psiFileFixture("app.ts", "export const answer = 42\n")
  private val reportDirFixture = tempPathFixture()

  @Test fun `a report names the lines of the file the IDE opens`() {
    val sourceFile = sourceFileFixture.get()
    val path = assertNotNull(sourceFile.virtualFile).path

    val report = reportDirFixture.get().resolve("coverage.info")
    report.writeText("TN:\nSF:$path\nDA:1,3\nDA:2,0\nend_of_record\n")

    val coverage = assertNotNull(ElideCoverageRunner().loadCoverageDataWithReporting(report.toFile(), null))
    val name = ElideCoverageEngine().getQualifiedNames(sourceFile).single()
    val fileCoverage = assertNotNull(coverage.getClassData(name), "no coverage recorded under $name")

    assertEquals(3, fileCoverage.getLineData(1).hits)
    assertEquals(0, fileCoverage.getLineData(2).hits)
  }
}
