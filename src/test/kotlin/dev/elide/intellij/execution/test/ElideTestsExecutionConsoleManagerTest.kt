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
package dev.elide.intellij.execution.test

import com.intellij.build.BuildProgressListener
import com.intellij.build.events.BuildEvent
import com.intellij.build.events.FinishBuildEvent
import com.intellij.build.events.OutputBuildEvent
import com.intellij.build.events.impl.SuccessResultImpl
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfigurationViewManager
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import dev.elide.intellij.Constants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Covers which of an `elide test` run's build events reach the Build window while the test tree owns the run. */
@TestApplication
class ElideTestsExecutionConsoleManagerTest {
  private val projectFixture = projectFixture()

  @Test fun `the tap stream stays out of the build window`() {
    val project = projectFixture.get()
    val taskId = ExternalSystemTaskId.create(Constants.SYSTEM_ID, ExternalSystemTaskType.EXECUTE_TASK, project)
    val otherRun = ExternalSystemTaskId.create(Constants.SYSTEM_ID, ExternalSystemTaskType.EXECUTE_TASK, project)
    val forwarded = mutableListOf<BuildEvent>()

    ElideTestsExecutionConsoleManager().forwardBuildEvents(
      project,
      taskId,
      SMTestProxy.SMRootTestProxy(),
      BuildProgressListener { _, event -> forwarded += event },
    )

    val source = project.getService(ExternalSystemRunConfigurationViewManager::class.java)

    // standard output addressed to the run itself is the TAP stream the test tree renders; standard error is the
    // CLI's own log, and standard output addressed to a node of the build tree is that node's console
    val diagnostic = Any()
    source.onEvent(taskId, output(taskId, "# out 1: jvm test output line\n", ProcessOutputType.STDOUT))
    source.onEvent(taskId, output(taskId, "ok 1 - polyglot.GreeterTest > prints output()\n", ProcessOutputType.STDOUT))
    source.onEvent(taskId, output(taskId, "error: kotlinc: Return type mismatch\n", ProcessOutputType.STDERR))
    source.onEvent(taskId, output(diagnostic, "src/test/Greeter.kt:4:1\n", ProcessOutputType.STDOUT))
    // another run's events belong to that run's own build view
    source.onEvent(otherRun, output(otherRun, "not this run's log\n", ProcessOutputType.STDERR))
    source.onEvent(taskId, FinishBuildEvent.builder(taskId, "finished", SuccessResultImpl()).build())

    assertEquals(
      listOf("error: kotlinc: Return type mismatch\n", "src/test/Greeter.kt:4:1\n", "finished"),
      forwarded.map { (it as? OutputBuildEvent)?.message ?: it.message },
    )

    // the CLI's log is what this window is for, so it is drawn as ordinary text rather than as the error output
    // the stream it arrived on would otherwise make it
    assertTrue(forwarded.filterIsInstance<OutputBuildEvent>().all { it.isStdOut })
  }

  private fun output(parentId: Any, text: String, type: ProcessOutputType) =
    OutputBuildEvent.builder(text).withParentId(parentId).withOutputType(type).build()
}
