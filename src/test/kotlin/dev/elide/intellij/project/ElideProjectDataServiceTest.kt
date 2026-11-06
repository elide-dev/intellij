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
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.writeAction
import com.intellij.openapi.externalSystem.ExternalSystemModulePropertyManager
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import com.intellij.testFramework.junit5.fixture.sourceRootFixture
import dev.elide.intellij.Constants
import dev.elide.intellij.cancelPklPackageRefresh
import dev.elide.intellij.project.model.ElideProjectData
import dev.elide.intellij.service.elideProjectIndex
import dev.elide.intellij.settings.ElideLocalSettings
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinCompilerPluginsProvider
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinProjectStructureProvider
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import org.jetbrains.kotlin.platform.jvm.JdkPlatform

/**
 * Covers what a finished sync leaves behind for IDE consumers: the plugin's index, external-project choosers,
 * and native Kotlin analysis configuration.
 */
@TestApplication
@OptIn(KaPlatformInterface::class)
class ElideProjectDataServiceTest {
  private val projectFixture = projectFixture(openAfterCreation = true)
  private val moduleFixture = projectFixture.moduleFixture()
  private val sourceFileFixture = moduleFixture.sourceRootFixture().psiFileFixture("Screen.kt", "fun screen() = Unit")

  @AfterTest fun stopPackageRefresh() = cancelPklPackageRefresh(projectFixture.get())

  private val projectData = ProjectData(Constants.SYSTEM_ID, "logstat", "$ROOT/.idea", ROOT)

  private fun workspaceNodes(): List<DataNode<ElideProjectData?>?> = listOf(
    ElideProjectData(projectPath = ROOT, name = "logstat", members = listOf(MEMBER, UNNAMED)),
    ElideProjectData(projectPath = MEMBER, name = "cli", workspaceRoot = ROOT),
    // a member whose manifest declares no name is known by its directory, the way Elide names it
    ElideProjectData(projectPath = UNNAMED, workspaceRoot = ROOT),
  ).map { DataNode(ElideProjectData.PROJECT_KEY, it, null) }

  private fun import(nodes: List<DataNode<ElideProjectData?>?>, commit: Boolean = false) {
    val project = projectFixture.get()

    // the models provider touches the project model, which the platform only allows from the write thread
    ApplicationManager.getApplication().invokeAndWait {
      WriteIntentReadAction.run {
        val modelsProvider = ProjectDataManager.getInstance().createModifiableModelsProvider(project)
        var committed = false

        try {
          ElideProjectDataService().importData(nodes, projectData, project, modelsProvider)
          if (commit) {
            WriteAction.run<RuntimeException> { modelsProvider.commit() }
            committed = true
          }
        } finally {
          if (!committed) modelsProvider.dispose()
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

  @Test fun `the platform can forget a synced project`() {
    import(workspaceNodes())

    val settings = ExternalSystemApiUtil.getLocalSettings<ElideLocalSettings>(projectFixture.get(), Constants.SYSTEM_ID)
    settings.forgetExternalProjects(setOf(ROOT))

    assertEquals(emptyMap(), settings.availableProjects)
  }

  @Test fun `native Kotlin analysis loads Compose and honors JVM target then drops Compose on resync`() = runBlocking {
    val module = moduleFixture.get()
    val file = sourceFileFixture.get()
    writeAction {
      ExternalSystemModulePropertyManager.getInstance(module).apply {
        setExternalId(Constants.SYSTEM_ID)
        setLinkedProjectPath(ROOT)
      }
    }

    fun sync(compose: Boolean, target: String) {
      val data = ElideProjectData(
        projectPath = ROOT,
        kotlin = ElideProjectData.KotlinFacetData(
          apiLevel = "2.2",
          languageLevel = "2.2",
          compilerArguments = listOf("-jvm-target=$target"),
          compose = compose,
        ),
      )
      import(listOf(DataNode(ElideProjectData.PROJECT_KEY, data, null)), commit = true)
    }

    suspend fun assertAnalysisConfiguration(compose: Boolean, target: JvmTarget) = readAction {
      val project = projectFixture.get()
      val analysisModule = KotlinProjectStructureProvider.getModule(project, file, null)
      val platform = assertIs<JdkPlatform>(analysisModule.targetPlatform.componentPlatforms.single())
      assertEquals(target, platform.targetVersion, "the analysis module must use the manifest JVM target")
      val provider = assertNotNull(project.getService(KotlinCompilerPluginsProvider::class.java))
      val composeRegistrars = provider.getRegisteredExtensions(analysisModule, FirExtensionRegistrarAdapter)
        .filter { it.javaClass.name == "androidx.compose.compiler.plugins.kotlin.k2.ComposeFirExtensionRegistrar" }
      // Ask the native analysis consumer to load the plugin, rather than echoing the facet's jar path.
      if (compose) assertEquals(1, composeRegistrars.size)
      else assertTrue(composeRegistrars.isEmpty(), "resync must invalidate the native compiler-plugin cache")
    }

    sync(compose = false, target = "1.8")
    assertAnalysisConfiguration(compose = false, target = JvmTarget.JVM_1_8)
    sync(compose = true, target = "17")
    assertAnalysisConfiguration(compose = true, target = JvmTarget.JVM_17)
    sync(compose = false, target = "21")
    assertAnalysisConfiguration(compose = false, target = JvmTarget.JVM_21)
  }

  private companion object {
    private const val ROOT = "/projects/logstat"
    private const val MEMBER = "$ROOT/cli"
    private const val UNNAMED = "$ROOT/tools"
  }
}
