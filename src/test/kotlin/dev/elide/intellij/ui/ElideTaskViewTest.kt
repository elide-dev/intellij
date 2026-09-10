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
package dev.elide.intellij.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.externalSystem.util.ExternalSystemUiUtil
import com.intellij.openapi.externalSystem.view.ExternalProjectsStructure
import com.intellij.openapi.externalSystem.view.ExternalProjectsView
import com.intellij.openapi.externalSystem.view.ExternalProjectsViewAdapter
import com.intellij.openapi.externalSystem.view.ExternalProjectsViewImpl
import com.intellij.openapi.externalSystem.view.TaskNode
import com.intellij.openapi.externalSystem.view.TasksNode
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.ui.treeStructure.Tree
import dev.elide.intellij.Constants
import dev.elide.intellij.project.model.ElideBuildTaskInfo
import dev.elide.intellij.project.model.ElideProjectModel
import dev.elide.project.manifest.ElideManifests
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Covers the task list of the Elide tool window: the nodes it builds, their labels and their icon. */
@TestApplication
class ElideTaskViewTest {
  private val projectFixture = projectFixture()

  private val manifest = ElideManifests.parse(
    requireNotNull(ElideTaskViewTest::class.java.getResourceAsStream("/manifest/elide-manifest.json"))
      .use { it.reader().readText() },
  )

  private val buildTasks = listOf(
    ElideBuildTaskInfo("app", "Package compiled classes into a JAR archive"),
    ElideBuildTaskInfo("compile-kotlin-main", "Compile main Kotlin source files to bytecode"),
  )

  @Test fun `the tree lists the build tasks flat under one tasks node`() = runBlocking {
    val projectNode = ElideProjectModel.buildModel(Path("/projects/demo"), emptyMap(), manifest, buildTasks)

    withContext(Dispatchers.EDT) {
      val disposable = Disposer.newDisposable()

      try {
        val view = elideView(disposable)
        val nodes = view.createNodes(view, null, projectNode)

        // the platform's own tasks node buckets tasks by task group, so its absence is the flat list
        assertEquals(emptyList(), nodes.filterIsInstance<TasksNode>())

        val tasksNode = nodes.filterIsInstance<ElideTasksNode>().single()
        assertEquals("Tasks", tasksNode.name)

        // the tasks themselves are the platform's nodes, which carry its "Run" action and task activation; the
        // labels come from this plugin's view contributor, which has to win over the platform's default one
        val tasks = tasksNode.children.toList()
        assertEquals(2, tasks.filterIsInstance<TaskNode>().size)
        assertEquals(listOf("app", "compile-kotlin-main"), tasks.map { it.name })
      } finally {
        Disposer.dispose(disposable)
      }
    }
  }

  @Test fun `tasks carry the elide icon`() {
    // the tree asks the external system manager for the icon of a task node
    assertEquals(Constants.Icons.ELIDE, ExternalSystemUiUtil.getUiAware(Constants.SYSTEM_ID).taskIcon)
  }

  /**
   * A view over the fixture's project, with a tree structure of its own.
   *
   * The nodes ask the view for the structure while deciding whether they are visible, and the real one only builds
   * its structure once the tool window it lives in shows its content.
   */
  private fun elideView(disposable: Disposable): ExternalProjectsView {
    val project = projectFixture.get()
    // the tool window the factory would fill; a headless application registers none of its own
    val toolWindow = ToolWindowManager.getInstance(project)
      .registerToolWindow("Elide") { anchor = ToolWindowAnchor.RIGHT } as ToolWindowEx

    val view = ExternalProjectsViewImpl(disposable, project, toolWindow, Constants.SYSTEM_ID)
    val structure = ExternalProjectsStructure(project, Tree()).also { Disposer.register(disposable, it) }

    return object : ExternalProjectsViewAdapter(view) {
      override fun getStructure(): ExternalProjectsStructure = structure
    }
  }
}
