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
package dev.elide.intellij.project

import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import kotlin.io.path.createDirectories
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Covers the directory walk that recognizes a project the user linked on its own as the member of a workspace above
 * it, against a stand-in `elide` that answers `manifest` the way the CLI does.
 */
@EnabledOnOs(OS.LINUX, OS.MAC)
class ElideWorkspaceDiscoveryTest {
  @TempDir lateinit var tempDir: Path

  /**
   * Writes an `elide` that prints the manifest of whichever directory it was invoked in, reading it from a JSON file
   * placed next to that directory's `elide.pkl`. The real CLI evaluates the Pkl; what matters here is only that a
   * different directory answers differently.
   */
  private fun fakeElide(): Path {
    val home = tempDir.resolve("elide-home")
    val binary = home.resolve("bin/elide")
    binary.parent.createDirectories()

    binary.writeText("#!/bin/sh\ncat ./manifest.json\n")
    binary.setPosixFilePermissions(
      setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE,
      ),
    )

    return home
  }

  /** Creates a project directory holding a manifest whose JSON form declares [members]. */
  private fun project(path: String, vararg members: String): Path {
    val dir = tempDir.resolve(path).createDirectories()
    dir.resolve("elide.pkl").writeText("amends \"elide:project.pkl\"\n")

    val workspace = when {
      members.isEmpty() -> ""
      else -> """, "workspace": { "members": [${members.joinToString(", ") { "\"$it\"" }}] }"""
    }

    dir.resolve("manifest.json").writeText("""{ "name": "${dir.fileName}"$workspace }""")
    return dir
  }

  @Test fun `a directory a manifest above it declares as a member is found`() = runBlocking {
    val root = project("workspace", "model", "cli")
    val member = project("workspace/cli")

    assertEquals(root, enclosingWorkspaceRoot(fakeElide(), member))
  }

  @Test fun `a directory no manifest above it declares is a project of its own`() = runBlocking {
    project("workspace", "model")
    val standalone = project("workspace/tools")

    assertNull(enclosingWorkspaceRoot(fakeElide(), standalone))
  }

  @Test fun `the walk reaches past a directory holding no manifest`() = runBlocking {
    val root = project("workspace", "nested/cli")
    val member = project("workspace/nested/cli")

    assertEquals(root, enclosingWorkspaceRoot(fakeElide(), member))
  }

  @Test fun `a manifest the CLI cannot read leaves the walk going`() = runBlocking {
    val root = project("workspace", "broken/cli")
    val member = project("workspace/broken/cli")

    // the intermediate directory has a manifest but no JSON for the stand-in to print, so the CLI fails there
    tempDir.resolve("workspace/broken/elide.pkl").writeText("amends \"elide:project.pkl\"\n")

    assertEquals(root, enclosingWorkspaceRoot(fakeElide(), member))
  }
}
