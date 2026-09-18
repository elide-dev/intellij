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

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.lineMarker.ExecutorAction
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.writeAction
import com.intellij.openapi.application.writeIntentReadAction
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemBeforeRunTask
import com.intellij.openapi.util.io.toCanonicalPath
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.testFramework.common.ThreadLeakTracker
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.sourceRootFixture
import dev.elide.intellij.execution.nativeimage.ElideNativeImageRunConfiguration
import dev.elide.intellij.project.model.ElideEntrypointInfo
import java.nio.file.Files
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Covers the manifest gutter icons: which manifest declarations carry one, and the Elide command line the action
 * behind an artifact's icon runs.
 */
@TestApplication
class ElideManifestGutterTest {
  // the project has to be open: run configuration producers only answer for open projects
  private val projectFixture = projectFixture(openAfterCreation = true)
  private val moduleFixture = projectFixture.moduleFixture()
  private val sourceRootFixture = moduleFixture.sourceRootFixture()

  @BeforeTest fun allowPklPackageServiceTimer() {
    // reading Pkl PSI starts the Pkl plugin's package service, which polls for declared packages on a
    // `java.util.Timer` of its own; the platform's leak tracker fails the test for it otherwise
    ThreadLeakTracker.longRunningThreadCreated(ApplicationManager.getApplication(), "Timer-")
  }

  @Test fun `artifacts carry a build icon`() = runBlocking {
    val info = assertNotNull(infoAtCaret("artifact.pkl", manifest(artifactKey = "a<caret>pp")))

    assertEquals(AllIcons.Actions.Compile, info.icon)
  }

  @Test fun `artifact icons only offer the run executor`() = runBlocking {
    val info = assertNotNull(infoAtCaret("executors.pkl", manifest(artifactKey = "a<caret>pp")))
    val executors = info.actions.filterIsInstance<ExecutorAction>().map { it.executor.id }.distinct()

    // `elide build app` has no JDWP server to attach to and writes no coverage report, so a debug or coverage action
    // on this icon would promise something the run cannot deliver
    assertEquals(listOf(DefaultRunExecutor.EXECUTOR_ID), executors)
  }

  @Test fun `scripts keep their run icon`() = runBlocking {
    val info = assertNotNull(infoAtCaret("script.pkl", manifest(scriptKey = "hel<caret>lo")))
    val executors = info.actions.filterIsInstance<ExecutorAction>().map { it.executor.id }

    assertEquals(AllIcons.Actions.Execute, info.icon)
    assertEquals(true, DefaultDebugExecutor.EXECUTOR_ID in executors)
  }

  @Test fun `mappings nested in an artifact carry no icon`() = runBlocking {
    // `resources` keys name files packaged into the artifact, and are no build targets of their own
    assertNull(infoAtCaret("nested.pkl", manifest(resourceKey = "r<caret>es")))
  }

  @Test fun `the artifact gutter action builds the artifact`() = runBlocking {
    val configuration = assertNotNull(configurationAtCaret("build.pkl", manifest(artifactKey = "a<caret>pp")))

    assertEquals("build app", configuration.rawCommandLine)
    assertEquals("Build app", configuration.name)
    assertEquals(ElideEntrypointInfo.Kind.Artifact, configuration.entrypointKind)
    assertEquals("app", configuration.entrypointValue)
  }

  @Test fun `a caret inside an artifact declaration builds nothing`() = runBlocking {
    // the nearest mapping entry there is a resource, which as a build target would read `elide build res`
    assertNull(configurationAtCaret("resource.pkl", manifest(resourceKey = "r<caret>es")))
  }

  @Test fun `a native image binary carries a run icon offering run and debug of the binary`() = runBlocking {
    val info = assertNotNull(infoAtCaret("image.pkl", manifest(nativeImageKey = "b<caret>in")))

    assertEquals(AllIcons.Actions.Execute, info.icon)

    val executors = info.actions.filterIsInstance<ExecutorAction>()
      .groupBy({ it.order }, { it.executor.id })
      .mapValues { (_, ids) -> ids.distinct() }

    // configuration 0 is the build of the artifact, which can only be run; configuration 1 is the binary it
    // produces, which can also be debugged
    assertEquals(
      mapOf(
        0 to listOf(DefaultRunExecutor.EXECUTOR_ID),
        1 to listOf(DefaultRunExecutor.EXECUTOR_ID, DefaultDebugExecutor.EXECUTOR_ID),
      ),
      executors,
    )
  }

  @Test fun `a native image library keeps the build icon`() = runBlocking {
    // a library image is a shared object, so there is nothing to start
    val info = assertNotNull(infoAtCaret("library.pkl", manifest(libraryKey = "sha<caret>red")))
    val executors = info.actions.filterIsInstance<ExecutorAction>().map { it.executor.id }.distinct()

    assertEquals(AllIcons.Actions.Compile, info.icon)
    assertEquals(listOf(DefaultRunExecutor.EXECUTOR_ID), executors)
  }

  @Test fun `a native image key offers the build first and the binary run second`() = runBlocking {
    val configurations = configurationsAtCaret("both.pkl", manifest(nativeImageKey = "b<caret>in"))

    val build = assertIs<ElideRunConfiguration>(configurations.getOrNull(0))
    assertEquals("build bin", build.rawCommandLine)

    val run = assertIs<ElideNativeImageRunConfiguration>(configurations.getOrNull(1))
    assertEquals("bin", run.artifact)
    assertEquals(sourceRootFixture.get().virtualFile.toNioPath().toCanonicalPath(), run.externalProjectPath)
  }

  @Test fun `a native image run builds its artifact before it launches`() = runBlocking {
    val configurations = configurationsAtCaret("beforeRun.pkl", manifest(nativeImageKey = "b<caret>in"))
    val run = assertIs<ElideNativeImageRunConfiguration>(configurations.getOrNull(1))

    val task = assertIs<ExternalSystemBeforeRunTask>(run.beforeRunTasks.singleOrNull())

    assertEquals(true, task.isEnabled)
    assertEquals(listOf("build", "bin"), task.taskExecutionSettings.taskNames)
    assertEquals(
      sourceRootFixture.get().virtualFile.toNioPath().toCanonicalPath(),
      task.taskExecutionSettings.externalProjectPath,
    )
  }

  /** The gutter icon the contributor puts on the [CARET] marker in [text], if any. */
  private suspend fun infoAtCaret(name: String, text: String): RunLineMarkerContributor.Info? {
    val (file, offset) = addManifest(name, text)
    val contributor = ElideManifestLineMarkerContributor()

    return readAction { contributor.getInfo(elementAt(file, offset)) }
  }

  /** The configuration the gutter action at the [CARET] marker in [text] would run. */
  private suspend fun configurationAtCaret(name: String, text: String): ElideRunConfiguration? {
    return configurationsAtCaret(name, text).firstOrNull() as? ElideRunConfiguration
  }

  /** Every configuration the context at the [CARET] marker in [text] yields, in producer preference order. */
  private suspend fun configurationsAtCaret(name: String, text: String): List<RunConfiguration> {
    val (file, offset) = addManifest(name, text)

    // configurations from context are ordered by producer preference, so the first entry is what the gutter runs, and
    // the platform resolves them on the EDT, as it does during action updates
    return withContext(Dispatchers.EDT) {
      writeIntentReadAction {
        ConfigurationContext(elementAt(file, offset)).configurationsFromContext.orEmpty().map { it.configuration }
      }
    }
  }

  /** Writes [text] (minus its [CARET] marker) to a new manifest, returning the file and the marker offset. */
  private suspend fun addManifest(name: String, text: String): Pair<VirtualFile, Int> {
    val offset = text.indexOf(CARET)
    require(offset >= 0) { "text has no $CARET marker" }

    // the file is written before the VFS ever sees it: a file created empty and filled afterwards keeps the PSI of
    // its empty first version, which parses as a manifest declaring nothing
    val path = sourceRootFixture.get().virtualFile.toNioPath().resolve(name)
    Files.writeString(path, text.replace(CARET, ""))
    val file = writeAction { VfsUtil.findFile(path, true) ?: error("no VFS entry for $path") }

    return file to offset
  }

  // PSI is resolved inside the action that consumes it: creating the file replaces its view provider, which
  // invalidates any element held across actions
  private fun elementAt(file: VirtualFile, offset: Int): PsiElement {
    val psiFile = PsiManager.getInstance(projectFixture.get()).findFile(file) ?: error("no PSI for ${file.name}")
    return psiFile.findElementAt(offset) ?: psiFile
  }

  private companion object {
    private const val CARET = "<caret>"

    /**
     * A manifest declaring one script and three artifacts — a jar, a Native Image binary and a Native Image library
     * — with the [CARET] marker placed in one of their keys.
     */
    private fun manifest(
      artifactKey: String = "app",
      scriptKey: String = "hello",
      resourceKey: String = "res",
      nativeImageKey: String = "bin",
      libraryKey: String = "shared",
    ): String = """
      amends "elide:project.pkl"

      import "elide:Jvm.pkl" as Jvm
      import "elide:NativeImage.pkl" as NativeImage

      name = "gutter"

      scripts {
        ["$scriptKey"] = "echo hi"
      }

      artifacts {
        ["$artifactKey"] = new Jvm.Jar {
          main = "gutter.MainKt"
          resources {
            ["$resourceKey"] = "src/main/resources/**"
          }
        }
        ["$nativeImageKey"] = new NativeImage.NativeImage {
          name = "gutter-bin"
          entrypoint = "gutter.MainKt"
        }
        ["$libraryKey"] = new NativeImage.NativeImage {
          type = "library"
          name = "libgutter"
        }
      }
    """.trimIndent()
  }
}
