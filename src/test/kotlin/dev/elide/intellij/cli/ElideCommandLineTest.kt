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
package dev.elide.intellij.cli

import dev.elide.intellij.ElideCommandFailedException
import dev.elide.intellij.InvalidElideHomeException
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import kotlin.io.path.createDirectories
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Exercises the CLI bridge against a stand-in `elide` script: output streaming, failure reporting, and the process
 * lifecycle guarantees (both streams drained, child destroyed on cancellation).
 */
@EnabledOnOs(OS.LINUX, OS.MAC)
class ElideCommandLineTest {
  @TempDir lateinit var tempDir: Path

  private fun fakeElide(script: String): Path {
    val home = tempDir.resolve("elide-home")
    val bin = home.resolve("bin")
    bin.createDirectories()

    val binary = bin.resolve("elide")
    binary.writeText("#!/bin/sh\n$script\n")
    binary.setPosixFilePermissions(
      setOf(
        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
        java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
      ),
    )

    return home
  }

  @Test fun `splits classpath output on the platform separator`() {
    val entries = listOf("/libs/a.jar", "/libs/b.jar", "/libs/c.jar")
    val output = entries.joinToString(File.pathSeparator) + "\n"

    assertEquals(entries, ElideCommandLine.parseClasspath(output))
  }

  @Test fun `ignores blank classpath output`() {
    assertEquals(emptyList(), ElideCommandLine.parseClasspath(""))
    assertEquals(emptyList(), ElideCommandLine.parseClasspath("  \n "))
    assertEquals(
      listOf("/libs/a.jar"),
      ElideCommandLine.parseClasspath("/libs/a.jar${File.pathSeparator}${File.pathSeparator}\n"),
    )
  }

  @Test fun `missing binary reports an invalid distribution`() {
    val home = tempDir.resolve("empty")
    home.createDirectories()

    assertFailsWith<InvalidElideHomeException> {
      runBlocking { ElideCommandLine.at(home)("install") }
    }
  }

  @Test fun `streams both output streams`() {
    val home = fakeElide("echo out-line; echo err-line >&2")
    val stdout = CopyOnWriteArrayList<String>()
    val stderr = CopyOnWriteArrayList<String>()

    runBlocking {
      ElideCommandLine.at(home)("run") { line, isStderr ->
        if (isStderr) stderr.add(line.trim()) else stdout.add(line.trim())
      }
    }

    assertEquals(listOf("out-line"), stdout.toList())
    assertEquals(listOf("err-line"), stderr.toList())
  }

  @Test fun `passes each argument as its own argv element`() {
    // the task manager hands a whole command line over as an argument vector; joining it into one argument would
    // make the CLI look for a file literally named "run src/main.kt"
    val home = fakeElide("""echo "count=${'$'}#"; for arg in "${'$'}@"; do echo "arg=${'$'}arg"; done""")
    val output = mutableListOf<String>()

    runBlocking {
      ElideCommandLine.at(home)(
        args = arrayOf("run", "src/main file.kt"),
        onOutput = { line, isStderr -> if (!isStderr) output.add(line.trim()) },
      )
    }

    assertEquals(listOf("count=2", "arg=run", "arg=src/main file.kt"), output.toList())
  }

  @Test fun `passes environment variables to the child`() {
    val home = fakeElide("""echo "value=${'$'}ELIDE_TEST_VAR"""")
    val output = StringBuilder()

    runBlocking {
      ElideCommandLine.at(home)(
        "run",
        environment = mapOf("ELIDE_TEST_VAR" to "hello"),
        onOutput = { line, isStderr -> if (!isStderr) output.append(line) },
      )
    }

    assertEquals("value=hello", output.toString().trim())
  }

  @Test fun `non-zero exit reports the captured stderr`() {
    val home = fakeElide("echo 'boom: dependency not found' >&2; exit 3")

    val failure = assertFailsWith<ElideCommandFailedException> {
      runBlocking { ElideCommandLine.at(home)("install") }
    }

    assertEquals(3, failure.exitCode)
    assertEquals("boom: dependency not found", failure.stderr)
    assertContains(failure.message.orEmpty(), "boom: dependency not found")
  }

  @Test fun `drains stderr without an output callback`() {
    // a chatty child with nobody reading stderr used to block on a full pipe buffer
    val home = fakeElide("i=0; while [ ${'$'}i -lt 4000 ]; do echo \"noisy stderr line ${'$'}i\" >&2; i=${'$'}((i+1)); done")

    runBlocking {
      withTimeout(30_000) { ElideCommandLine.at(home)("install") }
    }
  }

  @Test fun `cancellation destroys the child process`() {
    val marker = tempDir.resolve("still-running")
    val home = fakeElide("""while true; do echo tick > "$marker"; sleep 0.1; done""")

    runBlocking {
      val call = async { ElideCommandLine.at(home)("serve") }

      // let the child start writing, then cancel the call and wait for it to unwind
      delay(500)
      assertTrue(marker.toFile().exists(), "expected the fake CLI to have started")
      call.cancel()
      withTimeout(30_000) { call.join() }
    }

    // the process must be gone: nothing may touch the marker after the call unwound
    val lastModified = marker.toFile().lastModified()
    Thread.sleep(500)
    assertEquals(lastModified, marker.toFile().lastModified(), "the CLI process outlived its cancelled call")
  }
}
