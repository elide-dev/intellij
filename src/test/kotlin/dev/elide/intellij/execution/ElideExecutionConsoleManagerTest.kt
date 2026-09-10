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

import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.service.internal.ExternalSystemExecuteTaskTask
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import dev.elide.intellij.Constants
import dev.elide.intellij.execution.build.ElideSourceLinkFilter
import dev.elide.intellij.execution.test.ElideTestsExecutionConsoleManager
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Covers which console manager claims an Elide run, and the links it puts in that run's consoles. */
@TestApplication
class ElideExecutionConsoleManagerTest {
  private val projectFixture = projectFixture()

  private fun task(vararg taskNames: String): ExternalSystemExecuteTaskTask {
    val settings = ExternalSystemTaskExecutionSettings().apply {
      externalSystemIdString = Constants.SYSTEM_ID.id
      externalProjectPath = System.getProperty("user.home")
      this.taskNames = taskNames.toList()
    }

    val project = projectFixture.get()
    val configuration = ElideRunConfiguration(project, ElideExternalTaskConfigurationType.configurationFactory, "elide")

    return ExternalSystemExecuteTaskTask(project, settings, null, configuration)
  }

  @Test fun `a build claims the console that links the locations it prints`() {
    val build = task("build")

    assertTrue(ElideExecutionConsoleManager().isApplicableFor(build))

    // the filter is what the run's own console and the console beside each node of its build tree are filtered
    // with: the platform installs it from here and from nowhere else
    val filters = ElideExecutionConsoleManager().getCustomExecutionFilters(projectFixture.get(), build, null)
    assertIs<ElideSourceLinkFilter>(filters.single())
  }

  @Test fun `a test run is left to the console that owns the test tree`() {
    val test = task("test", "--reporter=tap")

    // both managers are registered for the same extension point, and which of them claims a run must not depend on
    // the order the platform happens to hold them in
    assertTrue(ElideTestsExecutionConsoleManager().isApplicableFor(test))
    assertFalse(ElideExecutionConsoleManager().isApplicableFor(test))
  }
}
