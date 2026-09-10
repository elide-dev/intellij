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

import com.intellij.build.BuildViewManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolder
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins which runs go to the Build window rather than the Run window.
 *
 * [ElideRunConfiguration.buildsArtifacts] decides it, and has to read the argument vector the way the task manager
 * runs it: a run misread as a build is published to a window that never raises itself for it, and a build misread
 * as a run shows up in the Run window with no tree.
 */
@TestApplication
class ElideRunConfigurationBuildTest {
  private val projectFixture = projectFixture()

  /** The progress listener the run's state names, which is what decides the window its build tree appears in. */
  private fun progressListener(project: Project, commandLine: String): Class<*>? {
    val configuration = ElideRunConfiguration(project, ElideExternalTaskConfigurationType.configurationFactory, "run")
    configuration.settings.externalProjectPath = project.basePath.orEmpty()
    configuration.rawCommandLine = commandLine

    val environment = ExecutionEnvironmentBuilder(project, DefaultRunExecutor.getRunExecutorInstance())
      .runProfile(configuration)
      .build()
    val state = configuration.getState(environment.executor, environment)

    return state?.let { (it as UserDataHolder).getUserData(ExternalSystemRunConfiguration.PROGRESS_LISTENER_KEY) }
  }

  @Test fun `a build publishes its tree to the build window, a run does not`() {
    val project = projectFixture.get()

    // the platform reads this key off the state and sends the run's build events to the named project service,
    // which for a build is the Build window's own view manager; the Run window's content is hidden in turn
    assertEquals(BuildViewManager::class.java, progressListener(project, "build :app"))
    assertEquals(BuildViewManager::class.java, progressListener(project, ":compile-kotlin-main"))

    // a run and a test render in the Run window, the test one as a test tree
    assertNull(progressListener(project, "run src/main.kt"))
    assertNull(progressListener(project, "test"))
  }

  @Test fun `a build command line is a build, whichever flags it carries`() {
    assertTrue(ElideRunConfiguration.buildsArtifacts(listOf("build")))
    assertTrue(ElideRunConfiguration.buildsArtifacts(listOf("build", "compile", "--no-cache")))
    assertTrue(ElideRunConfiguration.buildsArtifacts(listOf("-p", "./app", "build", "app")))
    assertTrue(ElideRunConfiguration.buildsArtifacts(listOf("build", "test")))
  }

  @Test fun `a task named by the project model is a build`() {
    // the tool window's "Run", task activation and keymap shortcuts execute a target by the name it carries in the
    // model, which reaches the CLI as `elide build <target>`
    assertTrue(ElideRunConfiguration.buildsArtifacts(listOf(":compile-kotlin-main")))
    assertTrue(ElideRunConfiguration.buildsArtifacts(listOf(":app", "--no-cache")))
  }

  @Test fun `running, testing and an empty command line are not builds`() {
    assertFalse(ElideRunConfiguration.buildsArtifacts(listOf("run", "src/main.kt")))
    // `elide build test` builds the testing task group; `elide test` runs the tests, and belongs to the test tree
    assertFalse(ElideRunConfiguration.buildsArtifacts(listOf("test", "--reporter=tap")))
    assertFalse(ElideRunConfiguration.buildsArtifacts(listOf("install")))
    assertFalse(ElideRunConfiguration.buildsArtifacts(emptyList()))
  }
}
