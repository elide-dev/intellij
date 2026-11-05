package dev.elide.intellij.authoring

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.writeAction
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.common.ThreadLeakTracker
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.sourceRootFixture
import dev.elide.intellij.cancelPklPackageRefresh
import dev.elide.intellij.psi.extensionReferences
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.pkl.intellij.psi.PklStringContent
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@TestApplication
class ElideExtensionReferencesTest {
  private val projectFixture = projectFixture(openAfterCreation = true)
  private val moduleFixture = projectFixture.moduleFixture()
  private val sourceRootFixture = moduleFixture.sourceRootFixture()

  @BeforeTest fun prepare() {
    ThreadLeakTracker.longRunningThreadCreated(ApplicationManager.getApplication(), "Timer-")
    TrustedProjects.setProjectTrusted(projectFixture.get(), true)
  }

  @AfterTest fun cleanup() { cancelPklPackageRefresh(projectFixture.get()) }

  @Test fun `local links resolve before sync and follow current unsaved PSI`() = runBlocking {
    val project = projectFixture.get()
    val root = sourceRootFixture.get().virtualFile.toNioPath()
    Files.writeString(root.resolve("first.ts"), "export default () => {}")
    Files.writeString(root.resolve("second.ts"), "export default () => {}")
    Files.writeString(root.resolve("elide.pkl"), "extensions { \"first.ts\" }")
    val manifest = writeAction {
      VfsUtil.markDirtyAndRefresh(false, true, true, sourceRootFixture.get().virtualFile)
      VfsUtil.findFile(root.resolve("elide.pkl"), true)!!
    }
    fun resolve(): List<String> {
      val file = PsiManager.getInstance(project).findFile(manifest)!!
      return PsiTreeUtil.findChildrenOfType(file, PklStringContent::class.java).flatMap { content ->
        extensionReferences(content).mapNotNull { (it.resolve() as? PsiFile)?.name }
      }
    }
    assertEquals(listOf("first.ts"), readAction { resolve() })
    writeAction {
      val file = PsiManager.getInstance(project).findFile(manifest)!!
      val documents = PsiDocumentManager.getInstance(project)
      val document = documents.getDocument(file)!!
      document.setText("extensions { \"second.ts\" }\nlocal nested { extensions { \"first.ts\" } }")
      documents.commitDocument(document)
    }
    assertEquals(listOf("second.ts"), readAction { resolve() })
  }
}
