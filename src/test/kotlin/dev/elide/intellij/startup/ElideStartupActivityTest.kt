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
package dev.elide.intellij.startup

import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import dev.elide.intellij.project.model.ElideProjectInfo
import dev.elide.intellij.service.elideProjectIndex
import dev.elide.intellij.settings.ElideProjectSettings
import dev.elide.intellij.settings.ElideSettings
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers which of the directories the IDE opened the startup activity imports as Elide projects.
 *
 * `workspace.members` entries are resolved against the root directory but may climb out of it, so a member can turn
 * up among the project's base directories beside the root that declares it, manifest and all. Importing it there
 * would duplicate the whole member — sources, classpath and output directory — on every IDE start.
 */
@EnabledOnOs(OS.LINUX, OS.MAC)
@TestApplication
class ElideStartupActivityTest {
  private val projectFixture = projectFixture()

  /** Indexes a workspace whose member sits beside its root, the way a sync leaves it: only the root is linked. */
  private fun workspace() {
    val project = projectFixture.get()
    ElideSettings.getSettings(project).linkProject(ElideProjectSettings().apply { externalProjectPath = ROOT })

    project.elideProjectIndex.update(ROOT, ElideProjectInfo(members = listOf(MEMBER)))
    project.elideProjectIndex.update(MEMBER, ElideProjectInfo(workspaceRoot = ROOT))
  }

  @Test fun `a member beside the workspace root is not imported as a project of its own`() {
    workspace()

    val project = projectFixture.get()
    assertFalse(ElideStartupActivity.isLinkable(project, MEMBER), "a member is synced through its root")
  }

  @Test fun `a linked project is still linked, which is how a reopened project re-syncs`() {
    workspace()

    val project = projectFixture.get()
    assertTrue(ElideStartupActivity.isLinkable(project, ROOT), "the linked root was skipped")
    assertTrue(ElideStartupActivity.isLinkable(project, OTHER), "an unrelated project belongs to no workspace")
  }

  private companion object {
    private const val ROOT = "/projects/demo"
    private const val MEMBER = "/projects/shared"
    private const val OTHER = "/projects/other"
  }
}
