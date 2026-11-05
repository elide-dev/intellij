package dev.elide.intellij.authoring

import com.intellij.lang.javascript.psi.JSFile
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.writeAction
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.sourceRootFixture
import dev.elide.intellij.cancelPklPackageRefresh
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestApplication
class ElideBuildLibraryProviderTest {
  private val projectFixture = projectFixture()
  private val sourceRootFixture = projectFixture.moduleFixture().sourceRootFixture()

  @AfterTest fun stopPackageRefresh() = cancelPklPackageRefresh(projectFixture.get())

  @Test fun `project globals replace bundled libraries without querying unfinished roots`() = runBlocking {
    val project = projectFixture.get()
    val root = sourceRootFixture.get().virtualFile
    val declaration = writeAction {
      root.createChildData(this, "elide.pkl")
      root.createChildData(this, "build.ts").also {
        VfsUtil.saveText(it, "export const task = (build: ElideBuild) => build;")
      }
    }
    val provider = ElideBuildLibraryProvider()
    assertEquals(1, readAction { provider.getAdditionalProjectLibraries(project).size })
    writeAction { VfsUtil.saveText(declaration, "interface ElideBuild { custom: string }") }
    assertTrue(readAction { provider.getAdditionalProjectLibraries(project).isEmpty() })
    writeAction { VfsUtil.saveText(declaration, "export interface ElideBuild { custom: string }") }
    assertEquals(1, readAction { provider.getAdditionalProjectLibraries(project).size })
  }

  @Test fun `a project global interface takes precedence over bundled declarations`() = runBlocking {
    assertTrue(declares("interface ElideBuild { custom: string }"))
  }

  @Test fun `a project global type alias takes precedence over bundled declarations`() = runBlocking {
    assertTrue(declares("type ElideBuild = { custom: string };"))
  }

  @Test fun `a module local name does not hide the bundled global`() = runBlocking {
    assertFalse(declares("export interface ElideBuild { custom: string }"))
  }

  @Test fun `an import makes otherwise unexported declarations module local`() = runBlocking {
    assertFalse(declares("import type { Custom } from './custom'; interface ElideBuild { custom: Custom }"))
    assertFalse(declares("export {}; type ElideBuild = { custom: string };"))
  }

  @Test fun `an explicit global augmentation takes precedence`() = runBlocking {
    assertTrue(declares("export {}; declare global { interface ElideBuild { custom: string } }"))
    assertTrue(declares("export {}; declare global { type ElideBuild = { custom: string }; }"))
  }

  @Test fun `comments and unrelated namespaces do not hide the bundled global`() = runBlocking {
    assertFalse(declares("// interface ElideBuild {}\nnamespace Local { interface ElideBuild {} }"))
    assertFalse(declares("declare module 'custom' { interface ElideBuild {} }"))
    assertFalse(declares("export {}; declare global { namespace Local { interface ElideBuild {} } }"))
  }

  private suspend fun declares(text: String): Boolean = readAction {
    // Resolve by filename deliberately: explicitly supplying a file type would conceal a disabled JavaScript plugin.
    val file = assertIs<JSFile>(
      PsiFileFactory.getInstance(projectFixture.get()).createFileFromText("project-globals.d.ts", text),
    )
    assertNull(PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java), "Expected valid TypeScript PSI")
    ElideBuildLibraryProvider.declaresBuildGlobal(file)
  }
}
