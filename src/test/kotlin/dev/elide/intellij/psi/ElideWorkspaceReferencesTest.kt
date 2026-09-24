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
package dev.elide.intellij.psi

import com.intellij.codeInsight.completion.CodeCompletionHandlerBase
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.CompletionUtilCore
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.codeInspection.InspectionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.writeAction
import com.intellij.openapi.application.writeIntentReadAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.refactoring.RefactoringFactory
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesProcessor
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.common.ThreadLeakTracker
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.sourceRootFixture
import com.intellij.util.ThreeState
import dev.elide.intellij.Constants
import dev.elide.intellij.cancelPklPackageRefresh
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.pkl.intellij.psi.PklModule
import org.pkl.intellij.psi.PklObjectEntry

/**
 * Covers the references between the manifests of a workspace: member paths, project names and artifact names, what
 * they resolve to, what completion offers for them, and what the inspection reports when they name nothing.
 *
 * Every test writes the same workspace: a root declaring `model`, `libs/core` and `cli`, where `libs/core` names no
 * project of its own and is known by its directory, next to manifests the root does not declare yet.
 */
@TestApplication
class ElideWorkspaceReferencesTest {
  private val projectFixture = projectFixture(openAfterCreation = true)
  private val moduleFixture = projectFixture.moduleFixture()
  private val sourceRootFixture = moduleFixture.sourceRootFixture()

  @BeforeTest fun allowPklPackageServiceTimer() {
    // reading Pkl PSI starts the Pkl plugin's package service, which polls for declared packages on a
    // `java.util.Timer` of its own; the platform's leak tracker fails the test for it otherwise
    ThreadLeakTracker.longRunningThreadCreated(ApplicationManager.getApplication(), "Timer-")
  }

  @AfterTest fun stopPklPackageServiceTimer() {
    // every edit this test makes to a manifest queues a package refresh three seconds out, which outlives the
    // project it reads unless it is cancelled here
    cancelPklPackageRefresh(projectFixture.get())
  }

  // -- Projects

  @Test fun `a project reference resolves to the member manifest`() = runBlocking {
    val target = resolveAt(member(prelude = """local model = project("mo<caret>del")"""))

    assertEquals(path("model/${Constants.MANIFEST_NAME}"), (target as PsiFile).virtualFile.toNioPath())
  }

  @Test fun `a project without a declared name is known by its directory`() = runBlocking {
    val target = resolveAt(member(prelude = """local core = project("co<caret>re")"""))

    assertEquals(path("libs/core/${Constants.MANIFEST_NAME}"), (target as PsiFile).virtualFile.toNioPath())
  }

  @Test fun `the root project can be referenced by name`() = runBlocking {
    val target = resolveAt(member(prelude = """local root = module.project("log<caret>stat")"""))

    assertEquals(path(Constants.MANIFEST_NAME), (target as PsiFile).virtualFile.toNioPath())
  }

  @Test fun `project completion offers every other project of the workspace`() = runBlocking {
    // `cli` is the manifest being edited, and a project never references itself
    assertEquals(setOf("logstat", "model", "core"), variantsAt(member(prelude = """local x = project("<caret>")""")))
  }

  @Test fun `the project of a hand-built reference resolves`() = runBlocking {
    val text = member(
      dependency = """new Artifacts.ProjectArtifact { project = "mod<caret>el" }""",
    )

    assertIs<PsiFile>(resolveAt(text))
  }

  @Test fun `a call of any other function is no project reference`() = runBlocking {
    assertNull(referenceAt(member(prelude = """local x = List("mo<caret>del")""")))
  }

  // -- Artifacts of other projects

  @Test fun `an artifact reference on a local resolves to the artifact declaration`() = runBlocking {
    val text = member(prelude = """local core = project("core")""", dependency = """core.artifact("core-f<caret>at")""")
    val entry = assertIs<PklObjectEntry>(resolveAt(text))

    assertEquals("core-fat", readAction { entry.artifactName() })
    assertEquals(
      path("libs/core/${Constants.MANIFEST_NAME}"),
      readAction { entry.containingFile.virtualFile.toNioPath() },
    )
  }

  @Test fun `an artifact reference on a call resolves`() = runBlocking {
    val text = member(dependency = """module.project("model").artifact("mo<caret>del")""")

    assertIs<PklObjectEntry>(resolveAt(text))
  }

  @Test fun `artifact completion offers what the referenced project declares`() = runBlocking {
    val text = member(prelude = """local core = project("core")""", dependency = """core.artifact("<caret>")""")

    assertEquals(setOf("core", "core-fat"), variantsAt(text))
  }

  @Test fun `the artifact of a hand-built reference resolves against its project`() = runBlocking {
    val text = member(
      dependency = """new Artifacts.ProjectArtifact { project = "core"; artifact = "core-<caret>fat" }""",
    )

    assertIs<PklObjectEntry>(resolveAt(text))
  }

  @Test fun `an amended project reference narrows to an artifact`() = runBlocking {
    val text = member(
      prelude = """local core = project("core")""",
      dependency = """(core) { artifact = "<caret>core" }""",
    )

    assertEquals(setOf("core", "core-fat"), variantsAt(text))
  }

  // -- Artifacts of the same manifest

  @Test fun `an artifact list names the artifacts of the same manifest`() = runBlocking {
    val text = member(artifacts = """
      ["app"] = new Jvm.Jar {}
      ["bin"] = new NativeImage.NativeImage {
        from { "a<caret>pp" }
      }
    """)

    val entry = assertIs<PklObjectEntry>(resolveAt(text))

    assertEquals("app", readAction { entry.artifactName() })
  }

  @Test fun `an artifact list never offers the artifact declaring it`() = runBlocking {
    val text = member(artifacts = """
      ["app"] = new Jvm.Jar {}
      ["lib"] = new Jvm.Jar {}
      ["bin"] = new NativeImage.NativeImage {
        dependsOn { "<caret>" }
      }
    """)

    assertEquals(setOf("app", "lib"), variantsAt(text))
  }

  @Test fun `a from list offers the other artifacts of the manifest`() = runBlocking {
    val text = member(artifacts = """
      ["app"] = new Jvm.Jar {}
      ["lib"] = new Jvm.Jar {}
      ["bin"] = new NativeImage.NativeImage {
        from { "<caret>" }
      }
    """)

    assertEquals(setOf("app", "lib"), variantsAt(text))
  }

  @Test fun `the source sets a source set depends on are no artifacts`() = runBlocking {
    val text = member(sources = """
      ["test"] {
        dependsOn { "ma<caret>in" }
      }
    """)

    assertNull(referenceAt(text))
  }

  // -- Member paths

  @Test fun `a member path resolves to the member directory`() = runBlocking {
    val target = resolveAt(root(members = listOf("model", "libs/co<caret>re", "cli")), file = "")

    assertEquals(path("libs/core"), assertIs<PsiDirectory>(target).virtualFile.toNioPath())
  }

  @Test fun `member completion offers nested manifests not declared yet`() = runBlocking {
    // `nested` declares members itself, and workspaces do not nest: neither it nor its own members are offered
    val text = root(members = listOf("model", "libs/core", "cli", "<caret>"))

    assertEquals(setOf("extra", "libs/util"), variantsAt(text, file = ""))
  }

  @Test fun `member completion continues a path from the segment being typed`() = runBlocking {
    assertEquals(setOf("util"), variantsAt(root(members = listOf("model", "libs/core", "libs/u<caret>")), file = ""))
  }

  @Test fun `renaming a member directory rewrites its entry`() = runBlocking {
    writeWorkspace(root(members = listOf("model", "libs/core", "cli")).withoutCaret(), file = "")

    withContext(Dispatchers.EDT) {
      writeIntentReadAction {
        val directory = PsiManager.getInstance(projectFixture.get()).findDirectory(vfs(path("libs/core")))!!
        RefactoringFactory.getInstance(projectFixture.get()).createRename(directory, "kernel", false, false).run()
      }
    }

    val declared = readAction { (psi(path(Constants.MANIFEST_NAME)) as PklModule).declaredMembers() }
    assertEquals(listOf("model", "libs/kernel", "cli"), declared.map { it.first })
  }

  @Test fun `moving a member directory to another parent rewrites its entry`() = runBlocking {
    writeWorkspace(root(members = listOf("model", "libs/core", "cli")).withoutCaret(), file = "")

    withContext(Dispatchers.EDT) {
      writeIntentReadAction {
        val project = projectFixture.get()
        val manager = PsiManager.getInstance(project)
        val moved = manager.findDirectory(vfs(path("model")))!!
        val destination = manager.findDirectory(vfs(path("libs")))!!

        MoveFilesOrDirectoriesProcessor(project, arrayOf(moved), destination, false, false, null, null).run()
      }
    }

    val declared = readAction { (psi(path(Constants.MANIFEST_NAME)) as PklModule).declaredMembers() }
    assertEquals(listOf("libs/model", "libs/core", "cli"), declared.map { it.first })
  }

  // -- Completion in the editor

  @Test fun `completion invoked in the editor offers the projects of the workspace`() = runBlocking {
    // the names `variantsAt` reads off the reference, this time as the platform's machinery collects them
    val offered = completionAt(member(prelude = """local x = project("<caret>")"""))

    assertEquals(setOf("logstat", "model", "core"), offered)
  }

  @Test fun `the popup only opens by itself inside a manifest`() = runBlocking {
    val text = root(members = listOf("model", "libs/core", "<caret>cli"))

    // the same declarations in a Pkl file that is no manifest name nothing of a workspace; written before the
    // workspace so the refresh that writing it ends with picks the file up
    val plain = path("extra/workspace.pkl")
    Files.createDirectories(plain.parent)
    Files.writeString(plain, text.withoutCaret())

    val (manifest, offset) = writeWorkspace(text, file = "")

    assertEquals(ThreeState.NO, autopopupAnswerAt(manifest, offset))
    assertEquals(ThreeState.UNSURE, autopopupAnswerAt(vfs(plain), offset))
  }

  // -- Inspection

  @Test fun `the inspection reports names the workspace does not know`() = runBlocking {
    val problems = problemsIn(
      member(
        prelude = """
          local ghost = project("ghost")
          local core = project("core")
        """,
        dependency = """core.artifact("missing")""",
      ),
    )

    assertEquals(
      listOf(
        Constants.Strings["elide.inspection.manifest.workspace.unresolvedProject", "ghost"],
        Constants.Strings["elide.inspection.manifest.workspace.unresolvedArtifact", "missing", "core"],
      ),
      problems,
    )
  }

  @Test fun `the inspection reports members that hold no manifest`() = runBlocking {
    Files.createDirectories(path("empty"))
    val problems = problemsIn(root(members = listOf("model", "libs/core", "cli", "empty", "gone")), file = "")

    assertEquals(
      listOf(
        Constants.Strings[
          "elide.inspection.manifest.workspace.memberWithoutManifest", "empty", Constants.MANIFEST_NAME,
        ],
        Constants.Strings["elide.inspection.manifest.workspace.unresolvedMember", "gone"],
      ),
      problems,
    )
  }

  @Test fun `the inspection leaves project names of a standalone manifest alone`() = runBlocking {
    // a manifest no root declares cannot know which projects exist
    val problems = problemsIn(member(prelude = """local ghost = project("ghost")"""), file = "extra")

    assertTrue(problems.isEmpty(), "unexpected problems: $problems")
  }

  // -- Helpers

  /** Writes the workspace with [text] as the manifest at [file] (`cli` by default), and returns the caret offset. */
  private suspend fun writeWorkspace(text: String, file: String = "cli"): Pair<VirtualFile, Int> {
    val offset = text.indexOf(CARET)

    val files = mapOf(
      "" to ROOT,
      "model" to manifest("""name = "model"""", artifacts = """["model"] = new Jvm.Jar {}"""),
      "libs/core" to manifest(artifacts = """
        ["core"] = new Jvm.Jar {}
        ["core-fat"] = new Jvm.Jar { name = "core-all" }
      """),
      "libs/util" to manifest(),
      "cli" to manifest("""name = "cli""""),
      "extra" to manifest(),
      "nested" to NESTED_ROOT,
      "nested/inner" to manifest(),
    ) + (file to text.replace(CARET, ""))

    // files are written before the VFS ever sees them: a file created empty and filled afterwards keeps the PSI of its
    // empty first version
    for ((directory, content) in files) {
      val target = path(directory).resolve(Constants.MANIFEST_NAME)
      Files.createDirectories(target.parent)
      Files.writeString(target, content)
    }

    val manifest = writeAction {
      VfsUtil.markDirtyAndRefresh(false, true, true, vfs(path("")))
      vfs(path(file).resolve(Constants.MANIFEST_NAME))
    }

    // member discovery and rename both read indices, which the new files are only added to once indexing settles
    IndexingTestUtil.suspendUntilIndexesAreReady(projectFixture.get())

    return manifest to offset
  }

  private suspend fun referenceAt(text: String, file: String = "cli"): PsiReference? {
    val (manifest, offset) = writeWorkspace(text, file)
    return readAction { psi(manifest.toNioPath()).findReferenceAt(offset) }
  }

  /** Resolves the reference at the [CARET] marker of [text], written as the manifest at [file]; fails on nothing. */
  private suspend fun resolveAt(text: String, file: String = "cli"): PsiElement {
    val (manifest, offset) = writeWorkspace(text, file)

    return readAction {
      val reference = assertNotNull(psi(manifest.toNioPath()).findReferenceAt(offset), "no reference at the caret")
      val resolved = if (reference is PsiPolyVariantReference) reference.multiResolve(false).firstOrNull()?.element
      else reference.resolve()

      assertNotNull(resolved, "reference '${reference.canonicalText}' does not resolve")
    }
  }

  /**
   * The lookup strings completion offers at the [CARET] marker of [text]. As the completion machinery does, the
   * placeholder identifier is inserted at the caret first, so an empty string still holds a reference to complete.
   */
  private suspend fun variantsAt(text: String, file: String = "cli"): Set<String> {
    val placeheld = text.replace(CARET, CARET + CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED)
    val (manifest, offset) = writeWorkspace(placeheld, file)

    return readAction {
      val reference = assertNotNull(psi(manifest.toNioPath()).findReferenceAt(offset), "no reference at the caret")
      reference.variants.mapTo(HashSet()) { (it as LookupElement).lookupString }
    }
  }

  /**
   * The lookup strings basic completion offers at the [CARET] marker of [text], written as the manifest at [file].
   * Unlike [variantsAt] this goes through the platform: the placeholder identifier, the pattern the reference
   * provider is registered for, and the contributor turning a reference's variants into lookup elements all take
   * part in the result.
   */
  private suspend fun completionAt(text: String, file: String = "cli"): Set<String> {
    val (manifest, offset) = writeWorkspace(text, file)
    val project = projectFixture.get()

    return inEditor(manifest, offset) { editor ->
      val handler = CodeCompletionHandlerBase.createHandler(CompletionType.BASIC)
      val completion = Runnable { handler.invokeCompletion(project, editor) }
      CommandProcessor.getInstance().executeCommand(project, completion, null, null)

      LookupManager.getActiveLookup(editor)?.items.orEmpty().mapTo(HashSet()) { it.lookupString }
    }
  }

  /** What the autopopup gate answers for the string at [offset] of [file]. */
  private suspend fun autopopupAnswerAt(file: VirtualFile, offset: Int): ThreeState {
    val confidence = ElideManifestCompletionConfidence()

    return inEditor(file, offset) { editor ->
      val opened = psi(file.toNioPath())
      val context = assertNotNull(opened.findElementAt(offset), "no element at the caret")

      confidence.shouldSkipAutopopup(editor, context, opened, offset)
    }
  }

  /**
   * Opens [file] with the caret at [offset], as the IDE has it while completion runs, and returns what [action]
   * makes of that editor. The file is closed again: an editor left open outlives the test that opened it.
   */
  private suspend fun <T> inEditor(file: VirtualFile, offset: Int, action: (Editor) -> T): T {
    val project = projectFixture.get()

    return withContext(Dispatchers.EDT) {
      writeIntentReadAction {
        val editors = FileEditorManager.getInstance(project)
        val descriptor = OpenFileDescriptor(project, file, offset)
        val editor = assertNotNull(editors.openTextEditor(descriptor, true), "no editor for ${file.path}")

        try {
          action(editor)
        } finally {
          LookupManager.hideActiveLookup(project)
          editors.closeFile(file)
        }
      }
    }
  }

  private suspend fun problemsIn(text: String, file: String = "cli"): List<String> {
    val (manifest, _) = writeWorkspace(text.withoutCaret(), file)

    return readAction {
      val inspection = ElideManifestWorkspaceReferenceInspection()
      val file = psi(manifest.toNioPath())

      // inspections refuse to run outside a progress, which the highlighting pass provides in the IDE
      ProgressManager.getInstance().runProcess(
        Computable { inspection.processFile(file, InspectionManager.getInstance(projectFixture.get())) },
        EmptyProgressIndicator(),
      ).map { it.descriptionTemplate }
    }
  }

  private fun path(relative: String): Path {
    return sourceRootFixture.get().virtualFile.toNioPath().resolve("ws").resolve(relative)
  }

  private fun vfs(path: Path): VirtualFile = VfsUtil.findFile(path, true) ?: error("no VFS entry for $path")

  private fun psi(path: Path): PsiFile {
    return PsiManager.getInstance(projectFixture.get()).findFile(vfs(path)) ?: error("no PSI for $path")
  }

  private fun String.withoutCaret() = replace(CARET, "")

  companion object {
    private const val CARET = "<caret>"

    private val ROOT = root(members = listOf("model", "libs/core", "cli")).replace(CARET, "")

    private val NESTED_ROOT = """
      amends "elide:project.pkl"

      workspace {
        members {
          "inner"
        }
      }
    """.trimIndent()

    /** The workspace root, declaring [members]. */
    private fun root(members: List<String>): String = """
      amends "elide:project.pkl"

      name = "logstat"

      workspace {
        members {
${members.joinToString("\n") { "          \"$it\"" }}
        }
      }
    """.trimIndent()

    /** A manifest with the given module-level [declarations] and [artifacts] entries. */
    private fun manifest(declarations: String = "", artifacts: String = ""): String = """
      amends "elide:project.pkl"

      import "elide:Artifacts.pkl" as Artifacts
      import "elide:Jvm.pkl" as Jvm
      import "elide:NativeImage.pkl" as NativeImage

      $declarations

      artifacts {
        ${artifacts.trimIndent()}
      }
    """.trimIndent()

    /** The `cli` member, with [prelude] above its declarations and [dependency] as its single package. */
    private fun member(
      prelude: String = "",
      dependency: String = "\"com.google.guava:guava\"",
      artifacts: String = "",
      sources: String = "",
    ): String = """
      amends "elide:project.pkl"

      import "elide:Artifacts.pkl" as Artifacts
      import "elide:Jvm.pkl" as Jvm
      import "elide:NativeImage.pkl" as NativeImage

      ${prelude.trimIndent()}

      name = "cli"

      sources {
        ${sources.trimIndent()}
      }

      dependencies {
        maven {
          packages {
            $dependency
          }
        }
      }

      artifacts {
        ${artifacts.trimIndent()}
      }
    """.trimIndent()
  }
}
