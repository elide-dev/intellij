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

import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.testframework.JavaTestLocator
import com.intellij.execution.testframework.sm.runner.GeneralTestEventsProcessor
import com.intellij.execution.testframework.sm.runner.events.TestFailedEvent
import com.intellij.execution.testframework.sm.runner.events.TestFinishedEvent
import com.intellij.execution.testframework.sm.runner.events.TestIgnoredEvent
import com.intellij.execution.testframework.sm.runner.events.TestOutputEvent
import com.intellij.execution.testframework.sm.runner.events.TestStartedEvent
import com.intellij.execution.testframework.sm.runner.events.TestSuiteFinishedEvent
import com.intellij.execution.testframework.sm.runner.events.TestSuiteStartedEvent
import com.intellij.execution.testframework.sm.runner.events.TreeNodeEvent
import dev.elide.intellij.Constants

/**
 * Drives the IDE's test tree from the TAP 13 stream of one `elide test` run.
 *
 * Tests are reported as the stream announces them: a test appears in the tree, running, on its `# start` comment,
 * the lines it writes are attached to it as they arrive, and its result settles it. Every node is addressed by the
 * start number TAP tags it with rather than by position in a suite stack, which is what lets a run with
 * `--concurrency` above one interleave several tests' starts and output without tearing the tree apart.
 *
 * The CLI's own log is not fed into the tree. It reaches the Build window through the run's build events, where a
 * compile diagnostic belongs; mixing it in here would attach the CLI's progress lines to whichever test happened to
 * be running when they arrived.
 *
 * Every entry point is synchronised: standard output and standard error are drained by separate threads, and the
 * tree's event processor expects its events in stream order.
 */
internal class ElideTestSession(private val processor: GeneralTestEventsProcessor) {
  private val parser = ElideTapParser()

  /** Tests announced by a `# start` comment that have not reported a result yet, keyed by start number. */
  private val running = LinkedHashMap<Int, TestNode>()

  /** Node id of every suite opened so far, keyed by the suite chain that names it. */
  private val suites = LinkedHashMap<String, SuiteNode>()

  /** Source of node ids for the tests and suites TAP does not number itself. */
  private var syntheticIds = 0

  private var finished = false

  private class TestNode(val label: String, val name: String, val nodeId: String, val startedAt: Long)

  private class SuiteNode(val name: String, val nodeId: String)

  /** Announce the run to the test tree; called once, before any output is fed in. */
  @Synchronized fun start() {
    processor.onStartTesting()
    processor.onTestsReporterAttached()
  }

  /**
   * Feed one chunk of CLI output to the tree.
   *
   * Only standard output carries the TAP stream; standard error is where the CLI writes its build progress and
   * diagnostics, which belong to the build, not to a test.
   */
  @Synchronized fun output(text: String, stdout: Boolean) {
    if (finished || !stdout) return
    parser.feed(text, ::accept)
  }

  /**
   * Close the run: decode whatever the stream left unterminated, close the suites still open, and let the tree
   * settle. Idempotent, because a failed run reports both a failure and an end.
   */
  @Synchronized fun finish() {
    if (finished) return
    parser.flush(::accept)
    finished = true

    // a test that started and never reported keeps its node running on purpose: the processor reads that as an
    // incomplete tree and marks the run terminated, which is what a bailed or crashed run is
    suites.values.reversed().forEach {
      processor.onSuiteFinished(TestSuiteFinishedEvent(it.name, it.nodeId))
    }
    suites.clear()

    processor.onFinishTesting()
  }

  private fun accept(event: ElideTapEvent) {
    when (event) {
      is ElideTapEvent.Started -> startTest(event.id, event.label)

      is ElideTapEvent.Output -> {
        val test = running[event.id]
        if (test != null) processor.onTestOutput(TestOutputEvent(test.name, test.nodeId, event.text + "\n", true))
        else processor.onUncapturedOutput(event.text + "\n", ProcessOutputType.STDOUT)
      }

      is ElideTapEvent.Result -> report(event)

      // the plan trails the results, so this only ever corrects the total the tree already counted; it still
      // matters for a run that reported fewer tests than it planned
      is ElideTapEvent.Plan -> processor.onTestsCountInSuite(event.count)

      // output a test wrote while none was running: commentary of the run itself, which the root node owns
      is ElideTapEvent.Unowned -> processor.onUncapturedOutput(event.text + "\n", ProcessOutputType.STDOUT)
    }
  }

  /** Put the test [label] names into the tree as running, and return its node. */
  private fun startTest(startNumber: Int?, label: String): TestNode {
    val path = label.split(SUITE_SEPARATOR)
    val name = path.last()
    val containers = path.subList(0, path.size - 1)

    val nodeId = startNumber?.let { "$TEST_ID_PREFIX$it" } ?: "$TEST_ID_PREFIX!${++syntheticIds}"
    val node = TestNode(label, name, nodeId, System.nanoTime())

    processor.onTestStarted(
      TestStartedEvent(
        /* name = */ name,
        /* id = */ nodeId,
        /* parentId = */ openSuites(containers),
        /* locationUrl = */ testLocation(containers, name),
        /* metainfo = */ null,
        /* nodeType = */ null,
        /* nodeArgs = */ null,
        /* running = */ true,
      ),
    )

    if (startNumber != null) running[startNumber] = node
    return node
  }

  private fun report(result: ElideTapEvent.Result) {
    // the `# start` comment states the label its result line will carry, which is the only thing binding the two;
    // two tests may share a label, and taking the oldest pairs them up in the order they settle
    val startNumber = running.entries.firstOrNull { it.value.label == result.label }?.key

    // a result with no start of its own: the JVM feed reports a disabled or filtered test that way
    val test = startNumber?.let(running::remove) ?: startTest(startNumber = null, label = result.label)

    when (result.outcome) {
      ElideTestOutcome.PASSED -> Unit
      ElideTestOutcome.FAILED -> processor.onTestFailure(
        TestFailedEvent(
          /* testName = */ test.name,
          /* id = */ test.nodeId,
          /* localizedFailureMessage = */ result.message ?: Constants.Strings["execution.test.failed"],
          /* stackTrace = */ stackTrace(result.detail),
          /* testError = */ false,
          // TAP carries the failure as text, not as the pair of values a diff view needs
          /* comparisonFailureActualText = */ null,
          /* comparisonFailureExpectedText = */ null,
          /* expectedFilePath = */ null,
          /* actualFilePath = */ null,
          /* expectedFileTemp = */ false,
          /* actualFileTemp = */ false,
          /* durationMillis = */ -1,
        ),
      )
      ElideTestOutcome.SKIPPED,
      ElideTestOutcome.TODO -> processor.onTestIgnored(
        TestIgnoredEvent(test.name, test.nodeId, result.reason.orEmpty(), stackTrace(result.detail)),
      )
    }

    // TAP states no durations, so the tree shows how long the IDE saw the test take: the span between its start
    // comment and its result. A test that reported no start settles the moment it is registered
    val duration = (System.nanoTime() - test.startedAt) / NANOS_PER_MILLI
    processor.onTestFinished(TestFinishedEvent(test.name, test.nodeId, duration))
  }

  /**
   * Open every suite the chain [containers] names that is not open yet, and return the node id of the innermost
   * one, which is the parent a test in that chain belongs under.
   *
   * Suites stay open until the run ends. A suite is a display grouping here, not something the stream opens and
   * closes, so nothing has to guess when one is done and no interleaved test can close another's suite.
   */
  private fun openSuites(containers: List<String>): String {
    var parentId = TreeNodeEvent.ROOT_NODE_ID

    for (depth in containers.indices) {
      val chain = containers.subList(0, depth + 1).joinToString(SUITE_SEPARATOR)
      val open = suites[chain]
      if (open != null) {
        parentId = open.nodeId
        continue
      }

      val name = containers[depth]
      val nodeId = "$SUITE_ID_PREFIX${++syntheticIds}"
      processor.onSuiteStarted(
        TestSuiteStartedEvent(
          /* name = */ name,
          /* id = */ nodeId,
          /* parentId = */ parentId,
          /* locationUrl = */ suiteLocation(containers, depth),
          /* metainfo = */ null,
          /* nodeType = */ null,
          /* nodeArgs = */ null,
          /* running = */ true,
        ),
      )
      suites[chain] = SuiteNode(name, nodeId)
      parentId = nodeId
    }

    return parentId
  }

  private companion object {
    /** Separator Elide joins a test's suite chain and name with; mirrors `TestId.SUITE_SEPARATOR`. */
    private const val SUITE_SEPARATOR = " > "

    /** Suffix JUnit's display names give a test method, and the mark of a JVM test in a TAP label. */
    private const val METHOD_SUFFIX = "()"

    private const val TEST_ID_PREFIX = "test-"
    private const val SUITE_ID_PREFIX = "suite-"

    private const val NANOS_PER_MILLI = 1_000_000L

    /**
     * A binary class name: dot-separated packages, `$`-separated nesting, every segment a Java identifier.
     *
     * Guest tests are named freely, so this is what keeps a JavaScript suite from being handed to the Java test
     * locator as if it were a class.
     */
    private val BINARY_CLASS_NAME = Regex("[\\p{L}_\$][\\p{L}\\p{N}_\$]*(\\.[\\p{L}_\$][\\p{L}\\p{N}_\$]*)*")

    /**
     * Navigation target for the test [name] declared in the suite chain [containers], or `null` when the label does
     * not describe a JVM test.
     *
     * The JVM feed reports a test as its class' binary name followed by JUnit's display name for the method, which
     * ends in `()`; a guest test has neither. A method carrying a custom `@DisplayName` therefore resolves to
     * nothing, which the locator reports as an unnavigable node rather than a wrong one.
     */
    private fun testLocation(containers: List<String>, name: String): String? {
      if (!name.endsWith(METHOD_SUFFIX)) return null
      val className = binaryClassName(containers, containers.size - 1) ?: return null

      return JavaTestLocator.createLocationUrl(JavaTestLocator.TEST_PROTOCOL, className, name)
    }

    /** Navigation target for the suite at [depth] of the chain [containers], or `null` when it names no class. */
    private fun suiteLocation(containers: List<String>, depth: Int): String? {
      val className = binaryClassName(containers, depth) ?: return null

      return JavaTestLocator.createLocationUrl(JavaTestLocator.SUITE_PROTOCOL, className)
    }

    /** The binary class name the chain [containers] spells out down to [depth], or `null` when it spells none. */
    private fun binaryClassName(containers: List<String>, depth: Int): String? {
      if (depth < 0) return null

      // the outermost container is the class' fully qualified name, and JUnit reports a `@Nested` class as a
      // further container, which is a nested class of the one before it
      val className = containers.subList(0, depth + 1).joinToString(NESTED_CLASS_SEPARATOR)
      return className.takeIf { BINARY_CLASS_NAME.matches(it) }
    }

    private const val NESTED_CLASS_SEPARATOR = "\$"

    /**
     * The failure's diagnostic lines as a stack trace.
     *
     * The CLI indents its own diagnostic output by one tab, which the console renders as part of the trace and the
     * platform's stack trace filters do not expect; the frames' own indentation is left alone.
     */
    private fun stackTrace(detail: List<String>): String? =
      detail.takeIf { it.isNotEmpty() }?.joinToString("\n") { it.removePrefix("\t") }
  }
}
