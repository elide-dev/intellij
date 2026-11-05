package dev.elide.intellij.android.testing

import com.google.gson.JsonObject
import com.intellij.execution.testframework.sm.runner.GeneralIdBasedToSMTRunnerEventsConvertor
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@TestApplication
class ElideAndroidTestSessionTest {
  private val fixture = projectFixture()

  private fun tree(output: String, exit: Int = 0, targets: List<String> = listOf("android-test-app")): SMTestProxy.SMRootTestProxy {
    val root = SMTestProxy.SMRootTestProxy()
    val processor = GeneralIdBasedToSMTRunnerEventsConvertor(fixture.get(), root, "Elide")
    ElideAndroidTestSession(processor, targets).apply {
      start()
      output(output, false)
      finish(exit)
      finish(exit)
    }
    return root
  }

  private fun message(text: String, level: String = "output", task: String? = "android-test-app"): String = JsonObject().apply {
    addProperty("type", "message"); addProperty("time", 1)
    task?.let { addProperty("task", it) }
    addProperty("level", level); addProperty("text", text)
  }.toString() + "\n"

  @Test fun `zero exit without results is a failure not a green empty tree`() {
    val root = tree("")
    assertEquals(1, root.children.size)
    assertTrue(root.children.single().isDefect)
    assertTrue(root.children.single().children.single().errorMessage!!.contains("No instrumented test results"))
    assertFalse(root.isInProgress)
  }

  @Test fun `method results carry Java locations and failure source stacks`() {
    val root = tree(message("sample.Device#works passed") + message("sample.Device#broken failed", "error") +
      message("java.lang.AssertionError\n\tat sample.Device.broken(Device.kt:31)", "error") + message("sample.Device#later skipped"), exit = 1)
    assertEquals("android-test-app", root.children.single().name)
    val suite = root.children.single().children.single()
    assertEquals("java:suite://sample.Device", suite.locationUrl)
    assertEquals(listOf("works", "broken", "later"), suite.children.map { it.name })
    assertEquals("java:test://sample.Device/works", suite.children[0].locationUrl)
    assertTrue(suite.children[1].isDefect)
    assertTrue(suite.children[1].stacktrace!!.contains("Device.kt:31"))
    assertTrue(suite.children[2].isIgnored)
  }

  @Test fun `nonzero exit after passing result remains failed`() {
    val root = tree(message("sample.Device#works passed"), 1)
    assertTrue(root.children.any { it.isDefect })
    assertEquals("Instrumentation run", root.children.last().name)
  }

  @Test fun `all requested targets get separate suites despite identical class and method`() {
    val targets = listOf("app:android-test-device", "lib:android-test-device", "android-test-other")
    val root = tree(targets.joinToString("") { target -> message("sample.Device#works passed", task = target) }, targets = targets)
    assertEquals(targets, root.children.map { it.name })
    root.children.forEach { target ->
      val suite = target.children.single()
      assertEquals("sample.Device", suite.name)
      assertEquals("java:suite://sample.Device", suite.locationUrl)
      assertEquals("works", suite.children.single().name)
      assertEquals("java:test://sample.Device/works", suite.children.single().locationUrl)
      assertFalse(suite.children.single().isDefect)
      assertFalse(target.isInProgress)
    }
  }

  @Test fun `interleaved failures keep target specific stacks`() {
    val targets = listOf("app:android-test-device", "lib:android-test-device")
    val root = tree(
      message("sample.Device#same failed", "error", targets[0]) +
        message("sample.Device#same failed", "error", targets[1]) +
        message("lib stack", "error", targets[1]) +
        message("app stack", "error", targets[0]), targets = targets)
    val results = root.children.associate { it.name to it.children.single().children.single() }
    assertEquals(targets.toSet(), results.keys)
    assertTrue(results.getValue(targets[0]).stacktrace!!.contains("app stack"))
    assertTrue(results.getValue(targets[1]).stacktrace!!.contains("lib stack"))
    assertTrue(results.values.all { it.isDefect })
  }

  @Test fun `one silent target fails even when another reports passing tests`() {
    val targets = listOf("android-test-a", "android-test-b")
    val root = tree(message("sample.Device#works passed", task = targets[0]), targets = targets)
    assertEquals(targets, root.children.map { it.name })
    assertFalse(root.children[0].isDefect)
    assertTrue(root.children[1].isDefect)
    val problem = root.children[1].children.single()
    assertEquals("Instrumentation run", problem.name)
    assertTrue(problem.errorMessage!!.contains("No instrumented test results were reported for android-test-b"))
  }

  @Test fun `ownerless failures do not hide silent targets or create passing tests`() {
    val targets = listOf("android-test-a", "android-test-b")
    val root = tree(message("device disconnected", "error", null) +
      message("sample.Device#works passed", task = null), targets = targets)
    targets.forEach { target ->
      val suite = root.children.single { it.name == target }
      assertTrue(suite.isDefect)
      assertTrue(suite.children.single().errorMessage!!.contains("No instrumented test results"))
    }
    assertEquals(2, root.children.count { it.name == "Instrumentation run" && it.isDefect })
    assertTrue(root.children.all { it.isDefect })
  }

  @Test fun `member identities remain separate for an unqualified target`() {
    val root = tree(message("sample.Device#works passed", task = "app:android-test-device") +
      message("sample.Device#works passed", task = "lib:android-test-device"), targets = listOf("android-test-device"))
    assertEquals(listOf("app:android-test-device", "lib:android-test-device"), root.children.map { it.name })
    assertEquals(listOf("works", "works"), root.children.map { it.children.single().children.single().name })
  }
}
