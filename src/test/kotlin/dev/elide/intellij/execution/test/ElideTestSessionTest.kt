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
package dev.elide.intellij.execution.test

import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.execution.testframework.Printable
import com.intellij.execution.testframework.Printer
import com.intellij.execution.testframework.sm.runner.GeneralIdBasedToSMTRunnerEventsConvertor
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the test tree an `elide test` run produces, driven by the verbatim TAP 13 stream of a polyglot run in
 * `src/test/resources/tap` (Elide 1.5.2, JVM and guest JavaScript tests in one session).
 */
@TestApplication
class ElideTestSessionTest {
  private val projectFixture = projectFixture()

  /** Feeds [stream] through a session, without ending the run, and returns the root of the tree it built. */
  private fun treeInProgress(stream: String): SMTestProxy.SMRootTestProxy {
    val root = SMTestProxy.SMRootTestProxy()
    val processor = GeneralIdBasedToSMTRunnerEventsConvertor(projectFixture.get(), root, "Elide")

    ElideTestSession(processor).apply {
      start()
      output(stream, stdout = true)
    }

    return root
  }

  /** Feeds [stream] through a session and returns the root of the tree it built. */
  private fun treeOf(stream: String): SMTestProxy.SMRootTestProxy {
    val root = SMTestProxy.SMRootTestProxy()
    val processor = GeneralIdBasedToSMTRunnerEventsConvertor(projectFixture.get(), root, "Elide")

    ElideTestSession(processor).apply {
      start()
      output(stream, stdout = true)
      finish()
    }

    return root
  }

  private fun tree(): SMTestProxy.SMRootTestProxy = treeOf(
    checkNotNull(javaClass.getResourceAsStream("/tap/polyglot-run.tap")) {
      "missing TAP fixture"
    }.use { it.reader().readText() },
  )

  private fun SMTestProxy.child(name: String): SMTestProxy =
    checkNotNull(children.find { it.name == name }) { "no node named '$name' under '${this.name}': ${children.map { it.name }}" }

  /** Everything the console would show for this node. */
  private fun SMTestProxy.printed(): String = buildString {
    printOn(object : Printer {
      override fun print(text: String, contentType: ConsoleViewContentType) = append(text).let { }
      override fun onNewAvailable(printable: Printable) = printable.printOn(this)
      override fun printHyperlink(text: String, info: HyperlinkInfo?) = append(text).let { }
      override fun mark() = Unit
    })
  }

  @Test fun `a test's label becomes its place in the tree`() {
    // TAP carries no structure of its own, only each test's label: a suite chain becomes nested suites, and a test
    // with no chain of its own stays at the root
    val root = tree()

    assertEquals(
      listOf(
        "guest js runs in the same session as the jvm tests",
        "guest suite",
        "guest typescript runs too",
        "polyglot.GreeterTest",
        "polyglot.JavaGreeterTest",
      ),
      root.children.map { it.name },
    )

    val guestSuite = root.child("guest suite")
    assertTrue(guestSuite.isSuite)
    assertEquals(listOf("reports under its own file"), guestSuite.children.map { it.name })

    assertEquals(
      listOf("fails with comparison()", "prints output()", "is ignored()", "greets by name()"),
      root.child("polyglot.GreeterTest").children.map { it.name },
    )
  }

  @Test fun `every node settles, and the run does not read as interrupted`() {
    val root = tree()

    assertFalse(root.isInProgress)
    assertFalse(root.isInterrupted)
    assertTrue(root.allTests.none { it.isInProgress }, "a node was left running")
  }

  @Test fun `a failure carries its message and its stack trace`() {
    val failed = tree().child("polyglot.GreeterTest").child("fails with comparison()")

    assertTrue(failed.isDefect)
    assertEquals(
      "org.opentest4j.AssertionFailedError: expected: <expected value> but was: <actual value>",
      failed.errorMessage,
    )

    // the CLI's own one-tab indentation comes off, so the trace reads as a stack trace and its frames resolve
    val stacktrace = checkNotNull(failed.stacktrace)
    assertContains(stacktrace, "org.opentest4j.AssertionFailedError: expected: <expected value>")
    assertContains(stacktrace, "\tat polyglot.GreeterTest.fails with comparison(GreeterTest.kt:15)")
  }

  @Test fun `a skipped test reports the reason the runner gave`() {
    // the JVM feed reports a disabled test with no start comment at all, only a result line
    val skipped = tree().child("polyglot.GreeterTest").child("is ignored()")

    assertTrue(skipped.isIgnored)
    assertContains(skipped.printed(), "is @Disabled")
  }

  @Test fun `output goes to the test that wrote it`() {
    val greeterTest = tree().child("polyglot.GreeterTest")

    assertContains(greeterTest.child("prints output()").printed(), "jvm test output line")
    assertFalse("jvm test output line" in greeterTest.child("greets by name()").printed())
  }

  @Test fun `jvm tests carry a navigation hint, guest tests do not`() {
    val root = tree()

    assertEquals("java:suite://polyglot.GreeterTest", root.child("polyglot.GreeterTest").locationUrl)
    assertEquals(
      "java:test://polyglot.GreeterTest/greets by name",
      root.child("polyglot.GreeterTest").child("greets by name()").locationUrl,
    )

    // a guest test is named freely and has no class to point at, so it must not be handed to the Java locator
    assertNull(root.child("guest suite").locationUrl)
    assertNull(root.child("guest suite").child("reports under its own file").locationUrl)
    assertNull(root.child("guest typescript runs too").locationUrl)
  }

  @Test fun `a nested jvm class resolves to its binary name`() {
    val root = treeOf("TAP version 13\nok 1 - probe.OuterTest > Inner > innerOne()\n1..1\n")

    val inner = root.child("probe.OuterTest").child("Inner")
    assertEquals("java:suite://probe.OuterTest\$Inner", inner.locationUrl)
    assertEquals("java:test://probe.OuterTest\$Inner/innerOne", inner.child("innerOne()").locationUrl)
  }

  @Test fun `a run that reported nothing leaves an empty tree`() {
    // what the console manager's root formatter explains to the user
    val root = treeOf("TAP version 13\n1..0\n")

    assertTrue(root.isEmptySuite)
    assertFalse(root.isInProgress)
  }

  @Test fun `a test is in the tree, running, before it reports a result`() {
    // what makes this a test runner rather than a log: the stream announces a test's start, and it has to show up
    // then, with the output it writes attached to it, not once the whole run has settled
    val root = treeInProgress(
      """
      TAP version 13
      # start 1: probe.ProbeTest > slowOne()
      # out 1: halfway through
      """.trimIndent() + "\n",
    )

    val test = root.child("probe.ProbeTest").child("slowOne()")
    assertTrue(test.isInProgress, "the test is not reported until it settles")
    assertContains(test.printed(), "halfway through")
  }

  @Test fun `interleaved tests land in their own suites`() {
    // `--concurrency` above one interleaves the starts, output and results of tests from different files
    val root = treeOf(
      """
      TAP version 13
      # start 1: probe.AlphaTest > alpha()
      # start 2: probe.BetaTest > beta()
      # out 2: from beta
      # out 1: from alpha
      ok 1 - probe.BetaTest > beta()
      not ok 2 - probe.AlphaTest > alpha()
      1..2
      """.trimIndent() + "\n",
    )

    assertEquals(listOf("probe.AlphaTest", "probe.BetaTest"), root.children.map { it.name })

    val alpha = root.child("probe.AlphaTest").child("alpha()")
    val beta = root.child("probe.BetaTest").child("beta()")
    assertTrue(alpha.isDefect, "the failing test's result landed on the wrong node")
    assertTrue(beta.isPassed, "the passing test's result landed on the wrong node")
    assertContains(alpha.printed(), "from alpha")
    assertContains(beta.printed(), "from beta")
  }

  @Test fun `the cli's own log stays out of the test tree`() {
    // the build's progress and diagnostics belong to the Build window, which the run's build events reach; mixing
    // them in here would attach them to whichever test happened to be running
    val root = SMTestProxy.SMRootTestProxy()
    val processor = GeneralIdBasedToSMTRunnerEventsConvertor(projectFixture.get(), root, "Elide")

    ElideTestSession(processor).apply {
      start()
      output("TAP version 13\n# start 1: probe.ProbeTest > one()\n", stdout = true)
      output("[ 42ms] Compiled 2 test Kotlin sources\n", stdout = false)
      output("ok 1 - probe.ProbeTest > one()\n1..1\n", stdout = true)
      finish()
    }

    assertFalse("Compiled 2 test Kotlin sources" in root.printed())
  }
}
