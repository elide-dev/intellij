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

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import dev.elide.intellij.Constants
import dev.elide.intellij.project.model.ElideProjectData
import dev.elide.intellij.service.elideProjectIndex
import dev.elide.intellij.settings.ElideLocalSettings
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers what a finished sync leaves behind for the rest of the IDE: the plugin's own index, and the projects the
 * platform's choosers offer for this external system.
 */
@TestApplication
class ElideProjectDataServiceTest {
  private val projectFixture = projectFixture()

  private val projectData = ProjectData(Constants.SYSTEM_ID, "logstat", "$ROOT/.idea", ROOT)

  private fun workspaceNodes(): List<DataNode<ElideProjectData?>?> = listOf(
    ElideProjectData(projectPath = ROOT, name = "logstat", members = listOf(MEMBER, UNNAMED)),
    ElideProjectData(projectPath = MEMBER, name = "cli", workspaceRoot = ROOT),
    // a member whose manifest declares no name is known by its directory, the way Elide names it
    ElideProjectData(projectPath = UNNAMED, workspaceRoot = ROOT),
  ).map { DataNode(ElideProjectData.PROJECT_KEY, it, null) }

  private fun import(nodes: List<DataNode<ElideProjectData?>?>) {
    val project = projectFixture.get()

    // the models provider touches the project model, which the platform only allows from the write thread
    ApplicationManager.getApplication().invokeAndWait {
      WriteIntentReadAction.run {
        val modelsProvider = ProjectDataManager.getInstance().createModifiableModelsProvider(project)

        try {
          ElideProjectDataService().importData(nodes, projectData, project, modelsProvider)
        } finally {
          modelsProvider.dispose()
        }
      }
    }
  }

  @Test fun `every project of the workspace is offered as a project of this external system`() {
    import(workspaceNodes())

    val settings = ExternalSystemApiUtil.getLocalSettings<ElideLocalSettings>(projectFixture.get(), Constants.SYSTEM_ID)
    val available = settings.availableProjects

    // keyed by the linked project, since that is the only one the IDE tracks; the members hang off it
    assertEquals(listOf("logstat" to ROOT), available.keys.map { it.name to it.path })
    assertEquals(
      listOf("cli" to MEMBER, "tools" to UNNAMED),
      available.values.single().map { it.name to it.path },
    )
  }

  @Test fun `a member dropped from the manifest stops being offered`() {
    import(workspaceNodes())
    import(workspaceNodes().take(1))

    val project = projectFixture.get()
    val settings = ExternalSystemApiUtil.getLocalSettings<ElideLocalSettings>(project, Constants.SYSTEM_ID)

    assertEquals(emptyList(), settings.availableProjects.values.single().map { it.path })
    assertEquals(listOf(ROOT), project.elideProjectIndex.entries.map { it.key })
  }

  private companion object {
    private const val ROOT = "/projects/logstat"
    private const val MEMBER = "$ROOT/cli"
    private const val UNNAMED = "$ROOT/tools"
  }
}
