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

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.externalSystem.model.task.TaskData
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import dev.elide.intellij.Constants
import dev.elide.intellij.project.model.ElideNativeImageInfo
import dev.elide.intellij.project.model.ElideProjectInfo
import dev.elide.intellij.project.model.taskName
import dev.elide.intellij.project.model.ElideBuildTaskInfo
import dev.elide.intellij.service.elideProjectIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the "Run"/"Debug Native Image" entries of the Elide tool window: that they reach the places the tree offers
 * actions from, and which task nodes they apply to.
 */
@TestApplication
class ElideNativeImageActionsTest {
  private val projectFixture = projectFixture()

  private val projectPath = "/projects/demo"

  private val memberPath = "$projectPath/cli"

  private fun task(name: String): TaskData {
    return TaskData(Constants.SYSTEM_ID, ElideBuildTaskInfo(name).taskName, projectPath, null)
  }

  private fun index(vararg images: ElideNativeImageInfo) {
    projectFixture.get().elideProjectIndex.update(projectPath, ElideProjectInfo(nativeImages = images.toList()))
  }

  /** Indexes a workspace the way a sync leaves it: one entry per project, each with the artifacts it declares. */
  private fun workspace() {
    val index = projectFixture.get().elideProjectIndex

    index.update(
      projectPath,
      ElideProjectInfo(
        name = "demo",
        nativeImages = listOf(ElideNativeImageInfo("tool", "demo-tool")),
        members = listOf(memberPath),
      ),
    )
    index.update(
      memberPath,
      ElideProjectInfo(
        name = "cli",
        nativeImages = listOf(ElideNativeImageInfo("myapp", "cli-app")),
        workspaceRoot = projectPath,
      ),
    )
  }

  @Test fun `the actions are offered by the task node menu and the tool window toolbar`() {
    val actionManager = ActionManager.getInstance()

    for (parent in listOf("ExternalSystemView.TaskMenu", "ExternalSystemView.ActionsToolbar.RunPanel")) {
      val children = assertIs<ActionGroup>(actionManager.getAction(parent))
        .getChildren(null)
        .mapNotNull { actionManager.getId(it) }

      assertTrue(GROUP_ID in children, "$parent is missing $GROUP_ID, has $children")
    }

    // the group is not a popup, so what the menu and the toolbar show is its children inlined
    val group = assertIs<ActionGroup>(actionManager.getAction(GROUP_ID))
    val actions = group.getChildren(null).mapNotNull { actionManager.getId(it) }

    assertEquals(false, group.isPopup)
    assertEquals(listOf("Elide.NativeImage.Run", "Elide.NativeImage.Debug"), actions)
  }

  @Test fun `the actions carry their own text`() {
    val run = ActionManager.getInstance().getAction("Elide.NativeImage.Run")
    val debug = ActionManager.getInstance().getAction("Elide.NativeImage.Debug")

    assertIs<ElideNativeImageRunAction>(run)
    assertIs<ElideNativeImageDebugAction>(debug)
    assertEquals("Run Native Image", run.templatePresentation.text)
    assertEquals("Debug Native Image", debug.templatePresentation.text)
  }

  @Test fun `only a task producing a runnable image is offered a run`() {
    index(ElideNativeImageInfo("bin", "demo-bin"))

    val project = projectFixture.get()

    assertEquals(ElideNativeImageTarget(projectPath, "bin"), nativeImageOf(project, task("bin")))
    // a jar target assembles something the IDE cannot start, and neither can a task of the build graph's own
    assertNull(nativeImageOf(project, task("app")))
    assertNull(nativeImageOf(project, task("compile-kotlin-main")))
  }

  @Test fun `a task of an unsynced project is offered nothing`() {
    // the output name comes from the synced model, so without it there is no image to name
    assertNull(nativeImageOf(projectFixture.get(), task("bin")))
  }

  @Test fun `a task qualified with a member is run against that member`() {
    workspace()

    val project = projectFixture.get()
    // the tree hangs every task off the linked root, but the member is where its build writes the binary
    assertEquals(ElideNativeImageTarget(memberPath, "myapp"), nativeImageOf(project, task("cli:myapp")))
    assertNull(nativeImageOf(project, task("cli:tool")))
  }

  @Test fun `a task qualified with the workspace root's own name is run against the root`() {
    workspace()

    val project = projectFixture.get()
    // the CLI qualifies the root's targets too, so the qualifier is all that tells its images from a member's
    assertEquals(ElideNativeImageTarget(projectPath, "tool"), nativeImageOf(project, task("demo:tool")))
    assertNull(nativeImageOf(project, task("demo:myapp")))
  }

  private companion object {
    /** Id of the group the plugin adds to the tool window's menus; the actions themselves live inside it. */
    private const val GROUP_ID = "Elide.NativeImage"
  }
}
