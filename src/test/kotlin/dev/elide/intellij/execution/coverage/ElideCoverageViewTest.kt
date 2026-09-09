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
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.application.readAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDirectory
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import dev.elide.intellij.settings.ElideProjectSettings
import dev.elide.intellij.settings.ElideSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Covers what the coverage tool window shows for an Elide project.
 *
 * The layout is the one an Elide project has: the module is rooted at the source folder the manifest declares, so
 * the project directory and everything between it and that folder belong to no content root. The platform's
 * directory view roots its tree at the IDE's guess of a project directory and lists only children the annotator has
 * figures for, and neither is true of those directories — which is how the tool window ends up empty for a report
 * the editor and project view annotate perfectly well.
 */
@TestApplication
class ElideCoverageViewTest {
  private val projectFixture = projectFixture()
  private val moduleFixture = projectFixture.moduleFixture()

  @Test fun `the tool window shows the project's tree down to the covered file`() = runBlocking {
    val project = projectFixture.get()
    val module = moduleFixture.get()
    val projectPath = assertNotNull(project.basePath)
    ElideSettings.getSettings(project).linkProject(ElideProjectSettings().apply { externalProjectPath = projectPath })

    val sourceRoot = Path(projectPath).resolve("src/main").createDirectories()
    val source = sourceRoot.resolve("app.ts").apply { writeText("export const answer = 42\n") }
    val localFileSystem = LocalFileSystem.getInstance()
    val sourceRootFile = assertNotNull(localFileSystem.refreshAndFindFileByNioFile(sourceRoot))
    val sourceFile = assertNotNull(localFileSystem.refreshAndFindFileByNioFile(source))
    PsiTestUtil.addSourceRoot(module, sourceRootFile)

    val report = Path(projectPath).resolve(".dev/reports/coverage/js/lcov.info")
    report.createParentDirectories()
    report.writeText("TN:\nSF:${sourceFile.path}\nDA:1,1\nend_of_record\n")

    ElideCoverageService.getInstance(project).attachExternalReports(projectPath)

    val manager = CoverageDataManager.getInstance(project)
    val bundle = assertNotNull(manager.currentSuitesBundle, "no coverage suite was activated")
    val annotator = ElideCoverageAnnotator.getInstance(project)
    val projectDirectory = assertNotNull(localFileSystem.findFileByPath(projectPath))

    // the figures the tree's columns read, which the platform's walk never reaches above a content root
    val coverage = assertNotNull(
      waitFor { annotator.getDirCoverageInformationString(project, projectDirectory, bundle, manager) },
      "the project directory carries no coverage of its own",
    )
    assertTrue(coverage.isNotEmpty(), coverage)

    val view = ElideCoverageEngine().createCoverageViewExtension(project, bundle)
    val root = readAction { view.createRootNode() }

    assertEquals(projectDirectory, (root.value as PsiDirectory).virtualFile)

    // and the tree itself: project directory → src → main → app.ts
    val branch = mutableListOf<String>()
    var node: AbstractTreeNode<*>? = root
    while (node != null) {
      val current = node
      branch.add(readAction { current.name }.orEmpty())
      node = readAction { view.getChildrenNodes(current) }.singleOrNull()
    }

    assertEquals(listOf(projectDirectory.name, "src", "main", "app.ts"), branch)
  }

  /** Waits for a value the annotator only has once the suite's coverage has been computed in the background. */
  private suspend fun <T : Any> waitFor(value: () -> T?): T? {
    repeat(ATTACH_ATTEMPTS) {
      value()?.let { return it }
      delay(ATTACH_POLL_MILLIS)
    }

    return null
  }

  private companion object {
    private const val ATTACH_ATTEMPTS = 100
    private const val ATTACH_POLL_MILLIS = 100L
  }
}
