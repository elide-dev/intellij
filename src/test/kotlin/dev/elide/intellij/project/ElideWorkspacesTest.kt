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
package dev.elide.intellij.project

import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import dev.elide.intellij.project.model.ElideProjectInfo
import dev.elide.intellij.service.elideProjectIndex
import dev.elide.intellij.settings.ElideProjectSettings
import dev.elide.intellij.settings.ElideSettings
import java.nio.file.Path
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Covers which Elide project a path belongs to in a workspace, where the member holding a file and the linked project
 * the IDE tracks it through are two different directories.
 */
@EnabledOnOs(OS.LINUX, OS.MAC)
@TestApplication
class ElideWorkspacesTest {
  private val projectFixture = projectFixture()

  /** Indexes a workspace the way a sync leaves it: one entry per project, and only the root linked. */
  private fun workspace() {
    val project = projectFixture.get()
    ElideSettings.getSettings(project).linkProject(ElideProjectSettings().apply { externalProjectPath = ROOT })

    project.elideProjectIndex.update(ROOT, ElideProjectInfo(members = listOf(MEMBER)))
    project.elideProjectIndex.update(MEMBER, ElideProjectInfo(workspaceRoot = ROOT))
  }

  @Test fun `a file of a member belongs to the member, not to the workspace root above it`() {
    workspace()

    val project = projectFixture.get()
    assertEquals(MEMBER, ElideWorkspaces.owningProject(project, Path.of("$MEMBER/src/main/App.kt")))
    assertEquals(ROOT, ElideWorkspaces.owningProject(project, Path.of("$ROOT/src/main/Root.kt")))
  }

  @Test fun `a member is owned by the linked root it was synced with`() {
    workspace()

    val project = projectFixture.get()
    assertEquals(ROOT, ElideWorkspaces.linkedRoot(project, MEMBER))
    assertEquals(ROOT, ElideWorkspaces.linkedRoot(project, ROOT))
    assertEquals(listOf(MEMBER), ElideWorkspaces.members(project, ROOT))
    assertEquals(emptyList<String>(), ElideWorkspaces.members(project, MEMBER))
  }

  @Test fun `a path beside every known project is owned by nothing`() {
    workspace()

    val project = projectFixture.get()
    assertNull(ElideWorkspaces.owningProject(project, Path.of("/projects/other/src/main/App.kt")))
    assertNull(ElideWorkspaces.linkedRoot(project, "/projects/other"))
  }

  private companion object {
    private const val ROOT = "/projects/demo"
    private const val MEMBER = "$ROOT/app"
  }
}
