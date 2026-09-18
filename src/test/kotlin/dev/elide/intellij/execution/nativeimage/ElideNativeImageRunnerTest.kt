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
package dev.elide.intellij.execution.nativeimage

import com.intellij.execution.BeforeRunTaskProvider
import com.intellij.execution.ExecutionException
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemBeforeRunTask
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import dev.elide.intellij.execution.ElideBeforeRunTaskProvider
import dev.elide.intellij.execution.nativeimage.debug.ElideNativeImageDebugRunner
import java.nio.file.Files
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions

/**
 * Covers how a Native Image run reaches the IDE: which runner claims each executor, and what a launch resolves to
 * before the artifact has been built.
 *
 * Runner resolution is what decides whether the actions show up at all — the gutter and the toolbar only offer an
 * executor some runner claims — and "Debug" has to stay offered even where the backend plugin is missing, since the
 * notification pointing at it is the only way a user learns why.
 */
@TestApplication
class ElideNativeImageRunnerTest {
  private val projectFixture = projectFixture()
  private val projectPathFixture = tempPathFixture()

  private val runExecutor = DefaultRunExecutor.getRunExecutorInstance()
  private val debugExecutor = DefaultDebugExecutor.getDebugExecutorInstance()

  private fun configuration(): ElideNativeImageRunConfiguration {
    val factory = ElideNativeImageConfigurationType.factory
    val configuration = factory.createTemplateConfiguration(projectFixture.get())

    return (configuration as ElideNativeImageRunConfiguration).also {
      ElideNativeImageRunConfigurations.configure(it, projectPathFixture.get().toString(), "bin")
    }
  }

  @Test fun `a run starts the binary through the command line state`() {
    val configuration = configuration()
    val runner = ProgramRunner.getRunner(runExecutor.id, configuration)
    val environment = ExecutionEnvironmentBuilder(projectFixture.get(), runExecutor)
      .runProfile(configuration)
      .runner(checkNotNull(runner))
      .build()

    assertIs<ElideNativeImageRunState>(configuration.getState(runExecutor, environment))
  }

  @Test fun `a run with no working directory of its own runs in the elide project`() {
    val root = projectPathFixture.get()
    // `ProgramParametersUtil` fills a blank working directory with the IDE project's base path, which for an Elide
    // project linked outside the IDE root is the wrong directory to start the binary in
    val binary = root.resolve(".dev/artifacts/native-image/${root.name}")
    Files.createDirectories(binary.parent)
    Files.writeString(binary, "#!/bin/sh\n")

    assertEquals(root.toFile(), configuration().resolveLaunch().commandLine.workDirectory)

    val absolute = configuration().also { it.workingDirectory = root.resolve(".dev").toString() }

    assertEquals(root.resolve(".dev").toFile(), absolute.resolveLaunch().commandLine.workDirectory)

    // a relative entry means "relative to the Elide project", not to the directory the IDE was started in
    val relative = configuration().also { it.workingDirectory = ".dev/artifacts" }

    assertEquals(root.resolve(".dev/artifacts").toFile(), relative.resolveLaunch().commandLine.workDirectory)
  }

  @Test fun `debug is always claimed, by the backend runner or by the advertiser`() {
    val runner = ProgramRunner.getRunner(debugExecutor.id, configuration())

    // without Native Debugging Support there is no session to start, and the advertiser keeps the action in place to
    // offer its installation
    if (ElideNativeDebugger.hasDebugRunner()) assertIs<ElideNativeImageDebugRunner>(runner)
    else assertIs<ElideNativeImageDebugAdvertiser>(runner)
  }

  @Test fun `a launch with no binary names the path the build was expected to write`() {
    val root = projectPathFixture.get()
    val failure = assertFailsWith<ExecutionException> { configuration().resolveLaunch() }

    // the message has to name the file, since a project that was never synced falls back to the directory's own name
    // and a mismatch is otherwise invisible
    assertTrue(
      failure.message.orEmpty().contains(root.resolve(".dev/artifacts/native-image/${root.name}").toString()),
      "expected the missing binary path in: ${failure.message}",
    )
  }

  @Test fun `configuring a run attaches the build of its artifact`() {
    val configuration = configuration()
    val task = assertIs<ExternalSystemBeforeRunTask>(configuration.beforeRunTasks.singleOrNull())

    assertEquals("bin", configuration.artifact)
    assertEquals("bin", configuration.name)
    assertEquals(listOf("build", "bin"), task.taskExecutionSettings.taskNames)

    // the platform resolves the provider of a before-run task by its key, and drops a task whose provider it cannot
    // find: without the registration the build would silently be skipped
    assertIs<ElideBeforeRunTaskProvider>(
      BeforeRunTaskProvider.getProvider(projectFixture.get(), ElideBeforeRunTaskProvider.ID),
    )
  }

  @Test fun `debug without the backend is refused before the artifact is built`() {
    // a Native Image build takes minutes; spending one only to report that there is nothing to debug with would be
    // the worst possible order, so the before-run task refuses the launch outright
    Assumptions.assumeFalse(ElideNativeDebugger.hasDebugRunner(), "the debug backend is installed")

    val configuration = configuration()
    val provider = assertIs<ElideBeforeRunTaskProvider>(
      BeforeRunTaskProvider.getProvider(projectFixture.get(), ElideBeforeRunTaskProvider.ID),
    )
    val task = assertIs<ExternalSystemBeforeRunTask>(configuration.beforeRunTasks.single())
    val environment = ExecutionEnvironmentBuilder(projectFixture.get(), debugExecutor)
      .runProfile(configuration)
      .runner(checkNotNull(ProgramRunner.getRunner(debugExecutor.id, configuration)))
      .build()

    assertFalse(provider.executeTask(DataContext.EMPTY_CONTEXT, configuration, environment, task))

    // the same task still builds for an ordinary run
    val run = ExecutionEnvironmentBuilder(projectFixture.get(), runExecutor)
      .runProfile(configuration)
      .runner(checkNotNull(ProgramRunner.getRunner(runExecutor.id, configuration)))
      .build()

    assertTrue(provider.canExecuteTask(configuration, task), "the build step must stay enabled for a plain run")
    assertEquals(runExecutor.id, run.executor.id)
  }
}
