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
package dev.elide.intellij.action

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.util.io.toCanonicalPath
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import dev.elide.intellij.project.model.ElideBuildTaskInfo
import dev.elide.intellij.project.model.ElideProjectInfo
import dev.elide.intellij.service.elideProjectIndex
import dev.elide.intellij.settings.ElideProjectSettings
import dev.elide.intellij.settings.ElideSettings
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * Covers which project the "run anything" popup completes against, and therefore which directory the command it runs
 * is rooted at.
 *
 * A workspace links its root alone, so the generic "Project" context would otherwise stand for the whole workspace
 * everywhere in it: the tasks of the member the user is working in would be missing from the popup, and `elide test`
 * typed into it would run every member's tests.
 */
@TestApplication
class ElideRunAnythingProviderTest {
  private val projectFixture = projectFixture()
  private val workspaceFixture = tempPathFixture()
  private val foreignFixture = tempPathFixture()

  private val provider = ElideRunAnythingProvider()

  /**
   * Links and indexes a workspace the way a sync leaves it: one index entry per project, only the root linked, and
   * each project carrying the build tasks it accepts unqualified.
   */
  private fun workspace() {
    val project = projectFixture.get()
    val root = workspaceFixture.get().toCanonicalPath()
    val member = Files.createDirectories(workspaceFixture.get().resolve("app")).toCanonicalPath()

    ElideSettings.getSettings(project).linkProject(ElideProjectSettings().apply { externalProjectPath = root })

    project.elideProjectIndex.update(
      root,
      ElideProjectInfo(members = listOf(member), buildTasks = listOf(ElideBuildTaskInfo("jar"))),
    )
    project.elideProjectIndex.update(
      member,
      ElideProjectInfo(workspaceRoot = root, buildTasks = listOf(ElideBuildTaskInfo("docs"))),
    )
  }

  /** Writes a source file at [relative] below [directory] and returns it, refreshed into the VFS. */
  private fun file(directory: Path, relative: String): VirtualFile {
    val path = directory.resolve(relative)
    Files.createDirectories(path.parent)
    path.writeText("fun main() = Unit\n")

    return assertNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
  }

  /** The completion the popup offers for `elide build ` with [selected] as the file the user is on. */
  private fun buildVariants(selected: VirtualFile?): List<String> {
    val project = projectFixture.get()
    val context: DataContext = when (selected) {
      null -> SimpleDataContext.getProjectContext(project)
      else -> SimpleDataContext.builder()
        .add(CommonDataKeys.PROJECT, project)
        .add(CommonDataKeys.VIRTUAL_FILE, selected)
        .build()
    }

    return provider.getValues(context, "elide build ")
  }

  @Test fun `a file of a member completes the member's tasks, not the workspace root's`() {
    workspace()
    val variants = buildVariants(file(workspaceFixture.get(), "app/src/App.kt"))

    assertContains(variants, "elide build docs")
    assertFalse("elide build jar" in variants, "the root's tasks are no targets of a build run in the member")
  }

  @Test fun `a file of the workspace root completes the root's own tasks`() {
    workspace()
    val variants = buildVariants(file(workspaceFixture.get(), "src/Root.kt"))

    assertContains(variants, "elide build jar")
    assertFalse("elide build docs" in variants, "a member's tasks are unqualified, and name nothing at the root")
  }

  @Test fun `no file, and a file of no Elide project, complete no project's tasks`() {
    workspace()

    // neither names a project of the workspace, so the popup falls back to the linked project, which this test
    // leaves unsynced: the CLI's own schema is all that is left to offer
    for (variants in listOf(buildVariants(null), buildVariants(file(foreignFixture.get(), "Other.kt")))) {
      assertContains(variants, "elide build compile")
      assertFalse("elide build jar" in variants)
      assertFalse("elide build docs" in variants)
    }
  }
}
