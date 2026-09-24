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
import com.intellij.openapi.externalSystem.view.ModuleNode
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
import dev.elide.intellij.project.model.ElideResolvedProject
import dev.elide.intellij.project.model.ElideResolvedWorkspace
import dev.elide.project.manifest.ElideManifests
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Covers the Elide tool window's tree: the task nodes it builds, their labels and their icon, and how a workspace's
 * projects nest below it.
 */
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

  private fun workspaceManifest(name: String) = ElideManifests.parse(
    requireNotNull(ElideTaskViewTest::class.java.getResourceAsStream("/manifest/workspace/$name.json"))
      .use { it.reader().readText() },
  )

  @Test fun `the tree lists the build tasks flat under one tasks node`() = runBlocking {
    val workspace = ElideResolvedWorkspace.of(Path("/projects/demo"), manifest)
    val projectNode = ElideProjectModel.buildModel(workspace, buildTasks)

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

  @Test fun `a workspace nests tasks under their project and source sets under their module`() = runBlocking {
    val root = Path("/projects/logstat")
    val workspace = ElideResolvedWorkspace(
      root = ElideResolvedProject.of(root, workspaceManifest("root")),
      members = listOf("model", "parser").map { ElideResolvedProject.of(root.resolve(it), workspaceManifest(it)) },
    )

    val projectNode = ElideProjectModel.buildModel(
      workspace,
      listOf(
        ElideBuildTaskInfo("maven-dependencies", "Resolve and download Maven dependencies"),
        ElideBuildTaskInfo("model:jar", "Package compiled classes into a JAR archive"),
      ),
    )

    withContext(Dispatchers.EDT) {
      val disposable = Disposer.newDisposable()

      try {
        val view = elideView(disposable)
        val nodes = view.createNodes(view, null, projectNode)

        // one node per project holding that project's tasks, instead of one list of qualified names
        val groups = nodes.filterIsInstance<ElideTasksNode>().single().children.filterIsInstance<ElideTasksNode>()
        assertEquals(listOf("logstat", "model"), groups.map { it.name })

        // the scope is the node above the task now, so the label drops it and keeps the task's own name
        assertEquals(listOf("jar"), groups.single { it.name == "model" }.children.map { it.name })
        assertEquals(listOf("maven-dependencies"), groups.single { it.name == "logstat" }.children.map { it.name })

        // a project's source-set modules hang off the module standing for that project rather than beside it
        val model = nodes.filterIsInstance<ModuleNode>().single { it.name == "model" }
        assertEquals(
          listOf("model.main", "model.test"),
          model.children.filterIsInstance<ModuleNode>().map { it.name },
        )
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
