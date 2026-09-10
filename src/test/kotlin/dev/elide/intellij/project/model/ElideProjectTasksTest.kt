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
package dev.elide.intellij.project.model

import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.testFramework.junit5.TestApplication
import dev.elide.project.manifest.ElideManifests
import kotlin.io.path.Path
import kotlin.io.path.pathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Covers the build tasks the resolved project model carries, which are what the Elide tool window lists and what a
 * run started from that list executes.
 */
@TestApplication
class ElideProjectTasksTest {
  private val manifest = ElideManifests.parse(
    requireNotNull(ElideProjectTasksTest::class.java.getResourceAsStream("/manifest/elide-manifest.json"))
      .use { it.reader().readText() },
  )

  private val projectPath = Path("/projects/demo")

  private val buildTasks = listOf(
    ElideBuildTaskInfo("app", "Package compiled classes into a JAR archive"),
    ElideBuildTaskInfo("write-classpath-files"),
  )

  @Test fun `build tasks hang off the project's task list`() {
    val projectNode = ElideProjectModel.buildModel(projectPath, emptyMap(), manifest, buildTasks)
    val tasksNode = ExternalSystemApiUtil.getChildren(projectNode, ElideBuildTasksData.KEY).single()
    val tasks = ExternalSystemApiUtil.getChildren(tasksNode, ProjectKeys.TASK).map { it.data }

    assertEquals(listOf(":app", ":write-classpath-files"), tasks.map { it.name })
    assertEquals("Package compiled classes into a JAR archive", tasks.first().description)

    // the description column of the listing is optional, and an empty one must not show as a blank tooltip
    assertNull(tasks.last().description)

    // the path a run of the task starts at
    assertEquals(projectPath.pathString, tasks.first().linkedExternalProjectPath)

    // no group: the list is flat, and a group would only nest the tasks one level deeper
    assertNull(tasks.first().group)
  }

  @Test fun `a project without a task listing carries no task list`() {
    // an Elide distribution whose `build --inspect` fails, or has no `--inspect` at all, still imports
    val projectNode = ElideProjectModel.buildModel(projectPath, emptyMap(), manifest)

    assertEquals(emptyList(), ExternalSystemApiUtil.getChildren(projectNode, ElideBuildTasksData.KEY))
    assertEquals(emptyList(), ExternalSystemApiUtil.findAllRecursively(projectNode, ProjectKeys.TASK))
  }
}
