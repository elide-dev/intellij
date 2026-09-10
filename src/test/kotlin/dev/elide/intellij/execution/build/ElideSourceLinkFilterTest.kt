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
package dev.elide.intellij.execution.build

import com.intellij.execution.filters.FileHyperlinkInfo
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Covers the links an Elide run's consoles turn the source locations the CLI printed into. */
@TestApplication
class ElideSourceLinkFilterTest {
  private val projectFixture = projectFixture()

  /** A project directory holding one source file, which is the only file a link may point at. */
  private fun workDir(): Path {
    val root = Files.createTempDirectory("elide-links")
    root.resolve("src/main/kotlin").createDirectories()
    root.resolve("src/main/kotlin/App.kt").writeText("fun main() {\n  println(1)\n}\n")
    LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)

    return root
  }

  @Test fun `a position the cli printed opens the file it names`() {
    val root = workDir()
    val filter = ElideSourceLinkFilter(projectFixture.get(), root)

    val line = "src/main/kotlin/App.kt:2:3\n"
    val result = checkNotNull(filter.applyFilter(line, line.length)) { "no link for $line" }
    val item = result.resultItems.single()

    // the link covers the location and nothing else, so the message beside it stays plain text
    assertEquals(0, item.highlightStartOffset)
    assertEquals("src/main/kotlin/App.kt:2:3".length, item.highlightEndOffset)

    // the CLI counts lines and columns from one, the editor the link opens from zero: line 2, column 3 of
    // `fun main() {\n  println(1)\n}` is the `p` of `println`
    val descriptor = ReadAction.compute<_, RuntimeException> {
      assertIs<FileHyperlinkInfo>(item.hyperlinkInfo).descriptor
    }
    assertEquals(root.resolve("src/main/kotlin/App.kt").toString(), descriptor?.file?.path)
    assertEquals("fun main() {\n  println(1)\n}\n".indexOf("println"), descriptor?.offset)
  }

  @Test fun `the uri and the absolute path a tool prints link to the same file`() {
    val root = workDir()
    val filter = ElideSourceLinkFilter(projectFixture.get(), root)
    val file = root.resolve("src/main/kotlin/App.kt")

    val lines = listOf(
      "error: kotlinc: ${file.toUri()}:2:3 Unresolved reference 'boom'.\n",
      "In file: $file\n",
    )

    for (line in lines) {
      val item = checkNotNull(filter.applyFilter(line, line.length)?.resultItems?.single()) { "no link for $line" }
      val descriptor = ReadAction.compute<_, RuntimeException> {
        assertIs<FileHyperlinkInfo>(item.hyperlinkInfo).descriptor
      }

      // the URI carries a position and the `In file:` line names the file alone, which opens it at the top
      assertEquals(file.toString(), descriptor?.file?.path)
      assertEquals(if (line.startsWith("In file:")) 0 else "fun main() {\n  println(1)\n}\n".indexOf("println"), descriptor?.offset)
    }
  }

  @Test fun `text that only reads like a path is left alone`() {
    val root = workDir()
    val filter = ElideSourceLinkFilter(projectFixture.get(), root)

    // a file the project does not have, a stack frame and a qualified name are not places to jump to, and a link
    // that opens nothing is worse than plain text
    assertNull(filter.applyFilter("src/main/kotlin/Missing.kt:2:3\n", 30))
    assertNull(filter.applyFilter("\tat dev.elide.tooling.build.Driver.run(Driver.kt:309)\n", 54))
    assertNull(filter.applyFilter("dev.elide.runtime.PrecompilerNotice: failed\n", 44))
  }
}
