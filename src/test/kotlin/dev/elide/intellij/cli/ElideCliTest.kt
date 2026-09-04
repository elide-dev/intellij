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

import dev.elide.intellij.project.model.ElideEntrypointInfo
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

  @Test fun `flag matching covers every form the CLI accepts`() {
    assertTrue(ElideCli.TEST_NAME_PATTERN.matches("--test-name-pattern"))
    assertTrue(ElideCli.TEST_NAME_PATTERN.matches("--test-name-pattern=abc"))
    assertTrue(ElideCli.TEST_NAME_PATTERN.matches("-t"))
    assertTrue(ElideCli.TEST_NAME_PATTERN.matches("-tabc"))
    assertFalse(ElideCli.TEST_NAME_PATTERN.matches("--test-timeout"))
  }

  @Test fun `flags offered are those of the named command, plus the global ones`() {
    val variants = texts(ElideCliCompletion.flags(listOf("test"), includeShort = false))

    assertContains(variants, "--test-name-pattern")
    assertContains(variants, "--reporter=junit")
    assertContains(variants, "--project")
    // `--debugger` and `--profiler` are declared by `run` and the root command only, and `--host` by the servers
    assertFalse("--debugger" in variants)
    assertFalse("--profiler" in variants)
    assertFalse("--host" in variants)
    // `--coverage` is genuinely global, and applies to a test run like any other
    assertContains(variants, "--coverage")
  }

  @Test fun `root flags configure the implicit run command`() {
    val variants = texts(ElideCliCompletion.flags(emptyList(), includeShort = true))

    assertContains(variants, "-s")
    assertContains(variants, "--debugger")
    assertContains(variants, "--debugger=cdp")
  }

  @Test fun `flags already given are not offered again unless they are repeatable`() {
    val variants = texts(ElideCliCompletion.flags(listOf("test", "--only", "--insights", "a.js"), false))

    assertFalse("--only" in variants)
    assertContains(variants, "--insights")
  }

  @Test fun `nothing is completed where the CLI expects a value or a program argument`() {
    assertTrue(ElideCliCompletion.flags(listOf("run", "a.js", "--"), includeShort = true).isEmpty())
    assertTrue(ElideCliCompletion.tasks(listOf("run", "a.js", "--"), emptyList()).isEmpty())
    // `--project` takes the following token as its value
    assertTrue(ElideCliCompletion.flags(listOf("--project"), includeShort = true).isEmpty())
  }

  @Test fun `entrypoints and commands are offered before a command is named`() {
    val variants = texts(ElideCliCompletion.tasks(emptyList(), listOf(ElideEntrypointInfo.script("hello"))))

    assertEquals("run hello", variants.first())
    assertContains(variants, "test")
    assertContains(variants, "dev")
    assertContains(variants, "format")
  }

  @Test fun `run offers the entrypoints that have a positional form`() {
    val entrypoints = listOf(ElideEntrypointInfo.script("hello"), ElideEntrypointInfo.jvmMain("app.Main"))

    // the JVM entrypoint is what a bare `run` resolves to; it is not a positional argument
    assertEquals(listOf("hello", "--"), texts(ElideCliCompletion.tasks(listOf("run"), entrypoints)))
  }

  @Test fun `build offers its task groups`() {
    assertEquals(
      listOf("deps", "compile", "test", "clean"),
      texts(ElideCliCompletion.tasks(listOf("build"), emptyList())),
    )
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
