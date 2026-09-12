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

import dev.elide.intellij.project.model.ElideBuildTaskInfo
import dev.elide.intellij.project.model.ElideEntrypointInfo
import dev.elide.intellij.project.model.ElideProjectInfo
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pins how the CLI schema reads an argument vector and which variants it offers for it. */
class ElideCliTest {
  private fun texts(variants: List<ElideCliCompletion.Variant>) = variants.map { it.text }

  @Test fun `command detection skips leading flags and their values`() {
    val invocation = ElideCli.parse(listOf("--timeout", "30s", "test", "-t", "x"))

    assertEquals(ElideCli.TEST, invocation.command)
    assertEquals(2, invocation.commandIndex)
  }

  @Test fun `command detection resolves aliases`() {
    assertEquals(ElideCli.FORMAT, ElideCli.parse(listOf("fmt")).command)
    assertEquals(ElideCli.SERVE, ElideCli.parse(listOf("start")).command)
  }

  @Test fun `an unknown leading token is a file to run, not a command`() {
    val invocation = ElideCli.parse(listOf("app.ts", "--verbose"))

    assertNull(invocation.command)
    assertEquals(-1, invocation.commandIndex)
  }

  @Test fun `the passthrough separator is located`() {
    assertEquals(2, ElideCli.parse(listOf("run", "a.js", "--", "--port")).passthroughIndex)
    assertEquals(-1, ElideCli.parse(listOf("run", "a.js")).passthroughIndex)
    // a separator before any command hides the command from the parser
    assertNull(ElideCli.parse(listOf("--", "run")).command)
  }

  @Test fun `flag lookup ignores passthrough arguments and other flags' values`() {
    val runnerArgs = listOf("test", "-t", "--reporter=tap", "--", "--reporter=junit")
    val invocation = ElideCli.parse(runnerArgs)

    // `-t` takes the following token as its pattern, and everything past `--` goes to the test runner, so neither
    // `--reporter` here is one the `test` command reads
    assertEquals(-1, ElideCli.flagIndex(runnerArgs, invocation, ElideCli.REPORTER))
    assertEquals(1, ElideCli.flagIndex(runnerArgs, invocation, ElideCli.TEST_NAME_PATTERN))

    val chosen = listOf("-p", "./app", "test", "src/api", "--reporter", "junit")
    assertEquals(4, ElideCli.flagIndex(chosen, ElideCli.parse(chosen), ElideCli.REPORTER))
  }

  @Test fun `flag matching covers every form the CLI accepts`() {
    assertTrue(ElideCli.TEST_NAME_PATTERN.matches("--test-name-pattern"))
    assertTrue(ElideCli.TEST_NAME_PATTERN.matches("--test-name-pattern=abc"))
    assertTrue(ElideCli.TEST_NAME_PATTERN.matches("-t"))
    assertTrue(ElideCli.TEST_NAME_PATTERN.matches("-tabc"))
    assertFalse(ElideCli.TEST_NAME_PATTERN.matches("--test-timeout"))
  }

  @Test fun `flags offered are those of the named command, plus the global ones`() {
    val variants = texts(ElideCliCompletion.flags(listOf("test"), null, includeShort = false))

    assertContains(variants, "--test-name-pattern")
    assertContains(variants, "--reporter=junit")
    assertContains(variants, "--project")
    // `--snippet` and `--language` are declared by `run` and the root command only, and `--host` by the servers
    assertFalse("--snippet" in variants)
    assertFalse("--language" in variants)
    assertFalse("--host" in variants)
    // `--coverage` is genuinely global, and applies to a test run like any other
    assertContains(variants, "--coverage")
    // the diagnostics flags `test` declares alongside `run`
    assertContains(variants, "--debugger")
    assertContains(variants, "--profiler")
  }

  @Test fun `root flags configure the implicit run command`() {
    val variants = texts(ElideCliCompletion.flags(emptyList(), null, includeShort = true))

    assertContains(variants, "-s")
    assertContains(variants, "--debugger")
    assertContains(variants, "--debugger=cdp")
  }

  @Test fun `flags already given are not offered again unless they are repeatable`() {
    val variants = texts(ElideCliCompletion.flags(listOf("test", "--only", "--insights", "a.js"), null, false))

    assertFalse("--only" in variants)
    assertContains(variants, "--insights")
  }

  @Test fun `nothing is completed where the CLI expects a value or a program argument`() {
    assertTrue(ElideCliCompletion.flags(listOf("run", "a.js", "--"), null, includeShort = true).isEmpty())
    assertTrue(ElideCliCompletion.tasks(listOf("run", "a.js", "--"), null).isEmpty())
    // `--project` takes the following token as its value
    assertTrue(ElideCliCompletion.flags(listOf("--project"), null, includeShort = true).isEmpty())
  }

  @Test fun `entrypoints and commands are offered before a command is named`() {
    val info = ElideProjectInfo(entrypoints = listOf(ElideEntrypointInfo.script("hello")))
    val variants = texts(ElideCliCompletion.tasks(emptyList(), info))

    assertEquals("run hello", variants.first())
    assertContains(variants, "test")
    assertContains(variants, "dev")
    assertContains(variants, "format")
  }

  @Test fun `run offers the entrypoints that have a positional form`() {
    val info = ElideProjectInfo(
      entrypoints = listOf(ElideEntrypointInfo.script("hello"), ElideEntrypointInfo.jvmMain("app.Main")),
    )

    // the JVM entrypoint is what a bare `run` resolves to; it is not a positional argument
    assertEquals(listOf("hello", "--"), texts(ElideCliCompletion.tasks(listOf("run"), info)))
  }

  @Test fun `build offers its task groups and the passthrough separator`() {
    assertEquals(
      listOf("deps", "compile", "test", "clean", "--"),
      texts(ElideCliCompletion.tasks(listOf("build"), null)),
    )
  }

  @Test fun `build offers the tasks the project declares ahead of the task groups`() {
    val info = ElideProjectInfo(
      buildTasks = listOf(
        ElideBuildTaskInfo("app", "Package compiled classes into a JAR archive"),
        ElideBuildTaskInfo("compile-kotlin-main", "Compile main Kotlin source files to bytecode"),
      ),
    )

    assertEquals(
      listOf("app", "compile-kotlin-main", "deps", "compile", "test", "clean", "--"),
      texts(ElideCliCompletion.tasks(listOf("build"), info)),
    )

    // a target already on the command line is not offered a second time, and neither task list shadows the other
    assertEquals(
      listOf("compile-kotlin-main", "deps", "compile", "test", "clean", "--"),
      texts(ElideCliCompletion.tasks(listOf("build", "--no-cache", "app"), info)),
    )
  }

  @Test fun `a project task named after a task group is offered once`() {
    val info = ElideProjectInfo(buildTasks = listOf(ElideBuildTaskInfo("test", "Run the project's tests")))

    assertEquals(
      listOf("test", "deps", "compile", "clean", "--"),
      texts(ElideCliCompletion.tasks(listOf("build"), info)),
    )
  }

  @Test fun `project tasks are only offered to the build command`() {
    val info = ElideProjectInfo(buildTasks = listOf(ElideBuildTaskInfo("app", "Package a JAR")))

    assertEquals(listOf("info", "advice"), texts(ElideCliCompletion.tasks(listOf("project"), info)))
  }

  @Test fun `the options of a named build task are offered alongside the command's own flags`() {
    val info = ElideProjectInfo(
      buildTasks = listOf(
        ElideBuildTaskInfo(
          name = "maven-dependencies",
          description = "Resolve and download Maven dependencies",
          options = listOf(
            ElideBuildTaskInfo.Option("--fresh", "Re-download dependencies even if present in the local cache"),
            ElideBuildTaskInfo.Option("--direct", "Copy artifacts to the project repository"),
          ),
        ),
        ElideBuildTaskInfo("run", "Run the main JVM application", listOf(ElideBuildTaskInfo.Option("--debugger"))),
      ),
    )

    // the CLI rejects a task's options until the task is named, so they are offered only from that point on
    val bare = texts(ElideCliCompletion.flags(listOf("build"), info, includeShort = false))
    assertFalse("--fresh" in bare)
    assertContains(bare, "--no-cache")

    val named = texts(ElideCliCompletion.flags(listOf("build", "maven-dependencies"), info, includeShort = false))
    assertEquals(listOf("--fresh", "--direct"), named.take(2))
    assertContains(named, "--offline")
    // `--debugger` belongs to a task that is not a target of this build
    assertFalse("--debugger" in named)

    // an option already given is not offered again, in either of the forms the CLI accepts it in
    val given = ElideCliCompletion.flags(listOf("build", "maven-dependencies", "--fresh"), info, false)
    assertFalse("--fresh" in texts(given))
    val attached = ElideCliCompletion.flags(listOf("build", "run", "--debugger=cdp"), info, false)
    assertFalse("--debugger" in texts(attached))
  }

  @Test fun `task options are not offered past the passthrough separator`() {
    val info = ElideProjectInfo(
      buildTasks = listOf(ElideBuildTaskInfo("test", options = listOf(ElideBuildTaskInfo.Option("--select")))),
    )

    assertTrue(ElideCliCompletion.flags(listOf("build", "test", "--"), info, includeShort = false).isEmpty())
  }

  @Test fun `the word under the caret is context for the tokens before it, not for itself`() {
    // the popup filters variants by the word being typed, so that word must not count as a decided argument
    assertEquals(listOf("test"), ElideCliCompletion.context("test --rep", 10))
    assertEquals(listOf("test", "--rep"), ElideCliCompletion.context("test --rep ", 11))
    assertEquals(emptyList(), ElideCliCompletion.context("test", 4))
    // editing mid-line only sees what precedes the caret
    assertEquals(listOf("test"), ElideCliCompletion.context("test --only --bail", 11))
    assertEquals(emptyList(), ElideCliCompletion.context("", 0))
  }
}
