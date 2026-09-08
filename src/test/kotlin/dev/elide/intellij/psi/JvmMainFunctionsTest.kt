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

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.writeAction
import com.intellij.openapi.application.writeIntentReadAction
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.sourceRootFixture
import dev.elide.intellij.execution.ElideRunConfiguration
import dev.elide.intellij.project.model.ElideEntrypointInfo
import dev.elide.intellij.project.model.ElideProjectInfo
import dev.elide.intellij.service.elideProjectIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Covers the entrypoint lookup behind the gutter run and debug actions: which class the caret resolves to, and that an
 * Elide entrypoint takes the location over the platform's own producers.
 */
@TestApplication
class JvmMainFunctionsTest {
  // the project has to be open: run configuration producers and the project file index only answer for open projects
  private val projectFixture = projectFixture(openAfterCreation = true)
  private val moduleFixture = projectFixture.moduleFixture()
  private val sourceRootFixture = moduleFixture.sourceRootFixture()

  @Test fun `java main class takes the location from the platform producer`() = runBlocking {
    val project = projectFixture.get()
    project.elideProjectIndex.update(PROJECT_PATH, ElideProjectInfo(listOf(ElideEntrypointInfo.jvmMain("probe.App"))))

    val configuration = elideConfigurationAtCaret(
      "App.java",
      """
      package probe;
      public class App {
        public static void main(String[] args) { System.out.pri<caret>ntln("hi"); }
      }
      """.trimIndent(),
    )

    assertIs<ElideRunConfiguration>(configuration, "expected the Elide producer to win over the Java producer")
    assertEquals("App", configuration.name)
    assertEquals(ElideEntrypointInfo.Kind.JvmMainClass, configuration.entrypointKind)
    assertEquals("probe.App", configuration.entrypointValue)
    assertEquals(PROJECT_PATH, configuration.settings.externalProjectPath)
  }

  @Test fun `main class outside the manifest is left to the platform`() = runBlocking {
    val project = projectFixture.get()
    project.elideProjectIndex.update(PROJECT_PATH, ElideProjectInfo(emptyList()))

    val configuration = elideConfigurationAtCaret(
      "Unlisted.java",
      """
      package probe;
      public class Unlisted {
        public static void main(String[] args) { System.out.pri<caret>ntln("hi"); }
      }
      """.trimIndent(),
    )

    assertNull(configuration)
  }

  @Test fun `kotlin top level main resolves to the file facade`() = runBlocking {
    assertEquals(
      "probe.AppKt",
      mainClassNameAtCaret(
        "App.kt",
        """
        package probe
        fun main() {
          pri<caret>ntln("hi")
        }
        """.trimIndent(),
      ),
    )
  }

  @Test fun `java varargs main is an entrypoint`() = runBlocking {
    assertEquals(
      "probe.Varargs",
      mainClassNameAtCaret(
        "Varargs.java",
        """
        package probe;
        public class Varargs {
          public static void ma<caret>in(String... args) { }
        }
        """.trimIndent(),
      ),
    )
  }

  @Test fun `java qualified parameter type is an entrypoint`() = runBlocking {
    assertEquals(
      "probe.Qualified",
      mainClassNameAtCaret(
        "Qualified.java",
        """
        package probe;
        public class Qualified {
          public static void ma<caret>in(java.lang.String[] args) { }
        }
        """.trimIndent(),
      ),
    )
  }

  @Test fun `java nested class resolves to its binary name`() = runBlocking {
    assertEquals(
      "probe.Outer\$Inner",
      mainClassNameAtCaret(
        "Outer.java",
        """
        package probe;
        public class Outer {
          public static class Inner {
            public static void main(String[] args) { int x<caret> = 1; }
          }
        }
        """.trimIndent(),
      ),
    )
  }

  @Test fun `caret outside the class still resolves the entrypoint of its file`() = runBlocking {
    assertEquals(
      "probe.Header",
      mainClassNameAtCaret(
        "Header.java",
        """
        package pro<caret>be;
        public class Header {
          public static void main(String[] args) { }
        }
        """.trimIndent(),
      ),
    )
  }

  @Test fun `caret in a local class resolves the enclosing entrypoint`() = runBlocking {
    assertEquals(
      "probe.Enclosing",
      mainClassNameAtCaret(
        "Enclosing.java",
        """
        package probe;
        public class Enclosing {
          public static void main(String[] args) {
            class Local { void go() { int y<caret> = 2; } }
          }
        }
        """.trimIndent(),
      ),
    )
  }

  @Test fun `instance main is not an entrypoint`() = runBlocking {
    assertNull(
      mainClassNameAtCaret(
        "Instance.java",
        """
        package probe;
        public class Instance {
          public void ma<caret>in(String[] args) { }
        }
        """.trimIndent(),
      ),
    )
  }

  @Test fun `main with another parameter type is not an entrypoint`() = runBlocking {
    assertNull(
      mainClassNameAtCaret(
        "IntArray.java",
        """
        package probe;
        public class IntArray {
          public static void ma<caret>in(int[] args) { }
        }
        """.trimIndent(),
      ),
    )
  }

  @Test fun `main returning a value is not an entrypoint`() = runBlocking {
    assertNull(
      mainClassNameAtCaret(
        "Returning.java",
        """
        package probe;
        public class Returning {
          public static int ma<caret>in(String[] args) { return 0; }
        }
        """.trimIndent(),
      ),
    )
  }

  @Test fun `interface main is not an entrypoint`() = runBlocking {
    assertNull(
      mainClassNameAtCaret(
        "Iface.java",
        """
        package probe;
        public interface Iface {
          static void ma<caret>in(String[] args) { }
        }
        """.trimIndent(),
      ),
    )
  }

  /** Binary name of the entrypoint class resolved at the [CARET] marker in [text]. */
  private suspend fun mainClassNameAtCaret(name: String, text: String): String? {
    val (file, offset) = addSourceFile(name, text)
    return readAction { findJvmMainClassName(elementAt(file, offset)) }
  }

  /** The configuration the gutter action would run at the [CARET] marker, when Elide claims the location. */
  private suspend fun elideConfigurationAtCaret(name: String, text: String): ElideRunConfiguration? {
    val (file, offset) = addSourceFile(name, text)

    // configurations from context are ordered by producer preference, so the first entry is what the gutter runs, and
    // the platform resolves them on the EDT, as it does during action updates
    return withContext(Dispatchers.EDT) {
      writeIntentReadAction {
        val fromContext = ConfigurationContext(elementAt(file, offset)).configurationsFromContext.orEmpty()
        fromContext.firstOrNull()?.configuration as? ElideRunConfiguration
      }
    }
  }

  /** Writes [text] (minus its [CARET] marker) to a new source file, returning the file and the marker offset. */
  private suspend fun addSourceFile(name: String, text: String): Pair<VirtualFile, Int> {
    val offset = text.indexOf(CARET)
    require(offset >= 0) { "text has no $CARET marker" }

    val sourceRoot = sourceRootFixture.get().virtualFile
    val file = writeAction {
      sourceRoot.createChildData(this, name).also { VfsUtil.saveText(it, text.replace(CARET, "")) }
    }

    return file to offset
  }

  // PSI is resolved inside the action that consumes it: creating the file replaces its view provider, which
  // invalidates any element held across actions
  private fun elementAt(file: VirtualFile, offset: Int): PsiElement {
    val psiFile = PsiManager.getInstance(projectFixture.get()).findFile(file) ?: error("no PSI for ${file.name}")
    return psiFile.findElementAt(offset) ?: psiFile
  }

  private companion object {
    private const val PROJECT_PATH = "/tmp/elide-probe"
    private const val CARET = "<caret>"
  }
}
