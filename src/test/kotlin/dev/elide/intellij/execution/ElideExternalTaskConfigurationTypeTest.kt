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
package dev.elide.intellij.execution

import com.intellij.openapi.util.io.toCanonicalPath
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import dev.elide.intellij.project.model.ElideProjectInfo
import dev.elide.intellij.service.elideProjectIndex
import dev.elide.intellij.settings.ElideProjectSettings
import dev.elide.intellij.settings.ElideSettings
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Covers the directory a configuration created by hand starts in.
 *
 * Only the root of a workspace is a linked project, so defaulting to the linked project alone points every new
 * configuration at the whole workspace, however deep inside a member the user is working.
 */
@TestApplication
class ElideExternalTaskConfigurationTypeTest {
  private val projectFixture = projectFixture()
  private val workspaceFixture = tempPathFixture()
  private val foreignFixture = tempPathFixture()

  /** Links and indexes a workspace the way a sync leaves it: one index entry per project, and only the root linked. */
  private fun workspace(): Pair<String, String> {
    val project = projectFixture.get()
    val root = workspaceFixture.get().toCanonicalPath()
    val member = Files.createDirectories(workspaceFixture.get().resolve("app")).toCanonicalPath()

    ElideSettings.getSettings(project).linkProject(ElideProjectSettings().apply { externalProjectPath = root })

    project.elideProjectIndex.update(root, ElideProjectInfo(members = listOf(member)))
    project.elideProjectIndex.update(member, ElideProjectInfo(workspaceRoot = root))

    return root to member
  }

  /** Writes a source file at [relative] below [directory] and returns it, refreshed into the VFS. */
  private fun file(directory: Path, relative: String): VirtualFile {
    val path = directory.resolve(relative)
    Files.createDirectories(path.parent)
    path.writeText("fun main() = Unit\n")

    return assertNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
  }

  @Test fun `a file of a member points the configuration at the member`() {
    val (_, member) = workspace()
    val selected = file(workspaceFixture.get(), "app/src/App.kt")

    // the CLI resolves the workspace from a member's directory just as well, and keeps the run to that member
    assertEquals(member, ElideExternalTaskConfigurationType.defaultProjectPath(projectFixture.get(), selected))
  }

  @Test fun `a file of the root, and one of no Elide project at all, point at the linked project`() {
    val (root, _) = workspace()
    val project = projectFixture.get()
    val rootFile = file(workspaceFixture.get(), "src/Root.kt")
    val foreign = file(foreignFixture.get(), "Other.kt")

    assertEquals(root, ElideExternalTaskConfigurationType.defaultProjectPath(project, rootFile))
    assertEquals(root, ElideExternalTaskConfigurationType.defaultProjectPath(project, foreign))
  }

  @Test fun `a configuration created with no file open starts at the linked project`() {
    val (root, _) = workspace()

    val configuration = ElideExternalTaskConfigurationType.configurationFactory
      .createTemplateConfiguration(projectFixture.get())

    assertEquals(root, assertIs<ElideRunConfiguration>(configuration).settings.externalProjectPath)
  }
}
