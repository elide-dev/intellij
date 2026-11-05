package dev.elide.intellij.android.testing

import com.intellij.execution.testframework.JavaTestLocator
import com.intellij.execution.testframework.sm.runner.GeneralTestEventsProcessor
import com.intellij.execution.testframework.sm.runner.events.TestFailedEvent
import com.intellij.execution.testframework.sm.runner.events.TestFinishedEvent
import com.intellij.execution.testframework.sm.runner.events.TestIgnoredEvent
import com.intellij.execution.testframework.sm.runner.events.TestStartedEvent
import com.intellij.execution.testframework.sm.runner.events.TestSuiteFinishedEvent
import com.intellij.execution.testframework.sm.runner.events.TestSuiteStartedEvent
import com.intellij.execution.testframework.sm.runner.events.TreeNodeEvent
import dev.elide.intellij.execution.test.ElideTestOutputSession

/** Instrumentation reports completed methods, not TAP starts; never infer success from process exit alone. */
internal class ElideAndroidTestSession(
  private val processor: GeneralTestEventsProcessor,
  targets: List<String>,
) : ElideTestOutputSession {
  private val targets = targets.distinct()
  private val parser = ElideAndroidTestParser(this.targets, ::report, ::problem)
  private val targetSuites = linkedMapOf<String, String>()
  private val suites = linkedMapOf<Pair<String, String>, String>()
  private val reportedTargets = mutableSetOf<String>()
  private val problemTargets = mutableSetOf<String>()
  private var count = 0
  private var problems = 0
  private var failedTests = false
  private var finished = false

  @Synchronized override fun start() {
    processor.onStartTesting()
    processor.onTestsReporterAttached()
  }

  @Synchronized override fun output(text: String, stdout: Boolean) {
    // Structured progress is stderr. Stdout may contain arbitrary text from build subprocesses.
    if (!finished && !stdout) parser.feed(text)
  }

  @Synchronized override fun finish(exitCode: Int?) {
    if (finished) return
    parser.end()
    targets.filter { target ->
      reportedTargets.none { ElideAndroidTestParser.matchesTarget(target, it) } &&
        problemTargets.none { ElideAndroidTestParser.matchesTarget(target, it) }
    }.forEach { target ->
      problem(target, "No instrumented test results were reported for $target. Check the device, instrumentation runner and test filter.")
    }
    if (exitCode != null && exitCode != 0 && problems == 0 && !failedTests) {
      problem(null, "Android instrumentation exited with code $exitCode; the run did not complete successfully.")
    }
    finished = true
    suites.entries.reversed().forEach { (key, id) -> processor.onSuiteFinished(TestSuiteFinishedEvent(key.second, id)) }
    targetSuites.entries.reversed().forEach { (name, id) -> processor.onSuiteFinished(TestSuiteFinishedEvent(name, id)) }
    processor.onFinishTesting()
  }

  private fun targetSuite(target: String): String = targetSuites.getOrPut(target) {
    val id = "android-target-${targetSuites.size}"
    processor.onSuiteStarted(TestSuiteStartedEvent(target, id, TreeNodeEvent.ROOT_NODE_ID, null, null, null, null, true))
    id
  }

  private fun report(target: String, result: ElideAndroidTestResult) {
    reportedTargets.add(target)
    val parent = targetSuite(target)
    val suite = suites.getOrPut(target to result.className) {
      val id = "android-suite-${suites.size}"
      processor.onSuiteStarted(TestSuiteStartedEvent(result.className, id, parent,
        JavaTestLocator.createLocationUrl(JavaTestLocator.SUITE_PROTOCOL, result.className), null, null, null, true))
      id
    }
    val id = "android-test-${++count}"
    processor.onTestStarted(TestStartedEvent(result.method, id, suite,
      JavaTestLocator.createLocationUrl(JavaTestLocator.TEST_PROTOCOL, result.className, result.method), null, null, null, true))
    when (result.outcome) {
      "failed" -> {
        failedTests = true
        failure(result.method, id, result.detail?.lineSequence()?.firstOrNull() ?: "Instrumented test failed", result.detail)
      }
      "skipped" -> processor.onTestIgnored(TestIgnoredEvent(result.method, id, "Skipped by Android instrumentation", result.detail))
    }
    processor.onTestFinished(TestFinishedEvent(result.method, id, 0))
  }

  private fun problem(target: String?, message: String) {
    val parent = target?.let {
      problemTargets.add(it)
      targetSuite(it)
    } ?: TreeNodeEvent.ROOT_NODE_ID
    val name = "Instrumentation run"
    val id = "android-problem-${++problems}"
    processor.onTestStarted(TestStartedEvent(name, id, parent, null, null, null, null, true))
    failure(name, id, message, null)
    processor.onTestFinished(TestFinishedEvent(name, id, 0))
  }

  private fun failure(name: String, id: String, message: String, detail: String?) {
    processor.onTestFailure(TestFailedEvent(name, id, message, detail, false, null, null, null, null, false, false, -1))
  }
}
