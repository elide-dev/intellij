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
package dev.elide.intellij.project.model

import java.nio.file.Path
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the translation of manifest source globs into IDE content roots, including the path-boundary handling that
 * previously let `src/a` swallow `src/ab`.
 */
@EnabledOnOs(OS.LINUX, OS.MAC)
class ElideSourceRootsTest {
  private val projectRoot: Path = Path.of("/projects/demo")

  @Test fun `no patterns yields no roots`() {
    assertEquals(emptyMap(), ElideSourceRoots.collect(projectRoot, emptyList()))
  }

  @Test fun `sibling source folders share one content root`() {
    val roots = ElideSourceRoots.collect(
      projectRoot,
      listOf("src/main/kotlin/**/*.kt", "src/main/java/**/*.java"),
    )

    assertEquals(
      mapOf("/projects/demo/src/main" to setOf("/projects/demo/src/main/kotlin", "/projects/demo/src/main/java")),
      roots,
    )
  }

  @Test fun `main and test folders get separate content roots`() {
    val roots = ElideSourceRoots.collect(projectRoot, listOf("src/main/kotlin/**/*.kt", "src/test/kotlin/**/*.kt"))

    assertEquals(
      mapOf(
        "/projects/demo/src/main" to setOf("/projects/demo/src/main/kotlin"),
        "/projects/demo/src/test" to setOf("/projects/demo/src/test/kotlin"),
      ),
      roots,
    )
  }

  @Test fun `similar path prefixes are not treated as nested`() {
    // regression: string-prefix matching merged `src/ab` into `src/a`
    assertFalse("/projects/demo/src/ab".isPathUnder("/projects/demo/src/a"))
    assertTrue("/projects/demo/src/a/deep".isPathUnder("/projects/demo/src/a"))
    assertTrue("/projects/demo/src/a".isPathUnder("/projects/demo/src/a"))

    val roots = ElideSourceRoots.collect(projectRoot, listOf("src/a/**/*.kt", "src/ab/nested/**/*.kt"))

    assertEquals(
      mapOf("/projects/demo/src" to setOf("/projects/demo/src/a", "/projects/demo/src/ab/nested")),
      roots,
    )
  }

  @Test fun `nested content root candidates collapse into the outermost root`() {
    val roots = ElideSourceRoots.collect(projectRoot, listOf("src/**/*.kt", "src/main/kotlin/**/*.kt"))

    assertEquals(
      mapOf("/projects/demo/src" to setOf("/projects/demo/src", "/projects/demo/src/main/kotlin")),
      roots,
    )
  }

  @Test fun `a top level source folder owns its content root`() {
    // the project root must not become a module content root: it encloses every other module's roots
    val roots = ElideSourceRoots.collect(projectRoot, listOf("samples/**/*.kt"))

    assertEquals(mapOf("/projects/demo/samples" to setOf("/projects/demo/samples")), roots)
  }

  @Test fun `patterns outside the project root become their own content root`() {
    val roots = ElideSourceRoots.collect(projectRoot, listOf("/generated/build/kotlin/**/*.kt"))

    // the parent of an out-of-project folder is not ours to claim as a content root
    assertEquals(mapOf("/generated/build/kotlin" to setOf("/generated/build/kotlin")), roots)
  }

  @Test fun `windows separators are normalized`() {
    val roots = ElideSourceRoots.collect(projectRoot, listOf("""src\main\kotlin\**\*.kt"""))

    assertEquals(mapOf("/projects/demo/src/main" to setOf("/projects/demo/src/main/kotlin")), roots)
  }

  @Test fun `static prefix stops at the first wildcard`() {
    assertEquals("/a/b/c", ElideSourceRoots.staticPrefix("/a/b/c"))
    assertEquals("/a/b", ElideSourceRoots.staticPrefix("/a/b/**/*.kt"))
    assertEquals("/a/b", ElideSourceRoots.staticPrefix("/a/b/Main?.kt"))
    assertEquals("/a/b", ElideSourceRoots.staticPrefix("/a/b/{one,two}/*.kt"))
    assertEquals("/a/b", ElideSourceRoots.staticPrefix("/a/b/[abc]*.kt"))
  }

  @Test fun `relative patterns resolve against the project root`() {
    assertEquals("/projects/demo/src", ElideSourceRoots.absolutePath("./src", projectRoot))
    assertEquals("/projects/demo/src", ElideSourceRoots.absolutePath("src/", projectRoot))
    assertEquals("/elsewhere/src", ElideSourceRoots.absolutePath("/elsewhere/src", projectRoot))
    assertEquals("C:/code/src", ElideSourceRoots.absolutePath("""C:\code\src""", projectRoot))
  }
}
