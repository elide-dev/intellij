package dev.elide.intellij.android.testing

import com.google.gson.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ElideAndroidTestParserTest {
  private fun message(text: String, level: String = "output", task: String? = "app:android-test-device"): String = JsonObject().apply {
    addProperty("type", "message")
    addProperty("time", 1)
    addProperty("text", text)
    addProperty("level", level)
    task?.let { addProperty("task", it) }
  }.toString() + "\n"

  @Test fun `arbitrary chunk boundaries preserve nested classes spaces and multiline failure`() {
    val results = mutableListOf<ElideAndroidTestResult>()
    val problems = mutableListOf<String>()
    val parser = ElideAndroidTestParser(listOf("android-test-device"), { _, result -> results.add(result) }, { _, problem -> problems.add(problem) })
    val detail = "java.lang.AssertionError: wrong\n\tat sample.Device\$Inner.test(Device.kt:19)"
    val output = message("sample.Device\$Inner#method with spaces failed", "error") + message(detail, "error") +
      message("sample.Device#passes passed") + message("sample.Device#ignored skipped")
    output.forEach { parser.feed(it.toString()) }
    parser.end()
    assertEquals(3, results.size)
    assertEquals("sample.Device\$Inner", results[0].className)
    assertEquals("method with spaces", results[0].method)
    assertEquals(detail, results[0].detail)
    assertEquals(listOf("failed", "passed", "skipped"), results.map { it.outcome })
    assertEquals(emptyList(), problems)
  }

  @Test fun `foreign tasks malformed JSON and nonmessage records are not tests`() {
    val results = mutableListOf<ElideAndroidTestResult>()
    val problems = mutableListOf<String>()
    val parser = ElideAndroidTestParser(listOf("android-test-device"), { _, result -> results.add(result) }, { _, problem -> problems.add(problem) })
    parser.feed(message("sample.Other#bad failed", "error", "android-test-other"))
    parser.feed("{broken\n[]\nnull\n{\"type\":\"task-finished\",\"text\":\"sample.Test#bad passed\"}\n")
    parser.feed(message("No instrumentation was found", "error", null))
    parser.end()
    assertEquals(emptyList(), results)
    assertEquals(listOf("No instrumentation was found"), problems)
  }

  @Test fun `unterminated final failure flushes once and summary is not its stacktrace`() {
    val results = mutableListOf<ElideAndroidTestResult>()
    val parser = ElideAndroidTestParser(listOf("android-test-device"), { _, result -> results.add(result) }) { _, problem -> error(problem) }
    parser.feed(message("sample.Test#first failed", "error"))
    parser.feed(message("0 passed, 1 failed, 0 skipped", "error"))
    parser.feed(message("sample.Test#last failed", "error").trimEnd())
    parser.end()
    parser.end()
    assertEquals(listOf("first", "last"), results.map { it.method })
    assertNull(results[0].detail)
    assertNull(results[1].detail)
  }

  @Test fun `instrumentation commands retain selectors as distinct arguments`() {
    assertEquals(listOf("build", "android-test-device", "--device=emulator-5554", "--tests=sample.Test", "--tests=sample.Other#method with spaces"),
      ElideAndroidTestAction.commandLine("device", "emulator-5554", listOf("sample.Test", "sample.Other#method with spaces")))
    assertEquals(listOf("app:android-test-device"), ElideAndroidTestParser.targets(listOf("build", "app:android-test-device", "--device=x")))
    assertEquals(emptyList(), ElideAndroidTestParser.targets(listOf("test", "android-test-device")))
    assertEquals(listOf("android-test-device"), ElideAndroidTestParser.targets(listOf(":android-test-device")))
    assertEquals(emptyList(), ElideAndroidTestParser.targets(listOf("build", "--project", "android-test-device")))
    assertEquals(emptyList(), ElideAndroidTestParser.targets(listOf("build", "--", "android-test-device")))
    assertNull(ElideAndroidTestParser.parseResult("sample.Test#method maybe"))
  }

  @Test fun `all explicit instrumented targets retain workspace identity`() {
    assertEquals(listOf("app:android-test-device", "lib:android-test-device", "android-test-other"),
      ElideAndroidTestParser.targets(listOf("build", ":app:android-test-device", "lib:android-test-device", "android-test-other",
        "app:android-test-device", "assemble", "--project", "android-test-option", "--", "android-test-passthrough")))
    assertEquals(listOf("app:android-test-device", "lib:android-test-device"),
      ElideAndroidTestParser.targets(listOf(":app:android-test-device", ":lib:android-test-device")))
  }

  @Test fun `interleaved target failures retain independent stacks and identical method names`() {
    val results = mutableListOf<Pair<String, ElideAndroidTestResult>>()
    val parser = ElideAndroidTestParser(listOf("app:android-test-device", "lib:android-test-device"),
      { target, result -> results.add(target to result) }) { _, problem -> error(problem) }
    parser.feed(message("sample.Test#same failed", "error", "app:android-test-device"))
    parser.feed(message("sample.Test#same failed", "error", "lib:android-test-device"))
    parser.feed(message("lib stack", "error", "lib:android-test-device"))
    parser.feed(message("foreign stack", "error", "other:android-test-device"))
    parser.feed(message("app stack", "error", "app:android-test-device"))
    parser.end()
    assertEquals(listOf("lib:android-test-device", "app:android-test-device"), results.map { it.first })
    assertEquals(listOf("lib stack", "app stack"), results.map { it.second.detail })
    assertEquals(listOf("same", "same"), results.map { it.second.method })
  }

  @Test fun `unqualified target preserves distinct observed member identities`() {
    val results = mutableListOf<Pair<String, ElideAndroidTestResult>>()
    val parser = ElideAndroidTestParser(listOf("android-test-device"),
      { target, result -> results.add(target to result) }) { _, problem -> error(problem) }
    parser.feed(message("sample.Test#same failed", "error", "app:android-test-device"))
    parser.feed(message("sample.Test#same passed", task = "lib:android-test-device"))
    parser.feed(message("app stack", "error", "app:android-test-device"))
    parser.end()
    assertEquals(listOf("lib:android-test-device", "app:android-test-device"), results.map { it.first })
    assertEquals("app stack", results.last().second.detail)
  }

  @Test fun `ambiguous ownerless diagnostics never attach to a pending target`() {
    val results = mutableListOf<Pair<String, ElideAndroidTestResult>>()
    val problems = mutableListOf<Pair<String?, String>>()
    val parser = ElideAndroidTestParser(listOf("android-test-a", "android-test-b"),
      { target, result -> results.add(target to result) }, { target, problem -> problems.add(target to problem) })
    parser.feed(message("sample.Test#same failed", "error", "android-test-a"))
    parser.feed(message("unowned stack", "error", null))
    parser.feed(message("sample.Test#same passed", task = null))
    parser.feed(message("owned stack", "error", "android-test-a"))
    parser.end()
    assertEquals(1, results.size)
    assertEquals("owned stack", results.single().second.detail)
    assertEquals(listOf(null, null), problems.map { it.first })
    assertEquals("unowned stack", problems.first().second)
    assertEquals("Instrumented test result has no unambiguous target: sample.Test#same passed", problems.last().second)
  }

  @Test fun `single target accepts unambiguous ownerless results and stack`() {
    val results = mutableListOf<Pair<String, ElideAndroidTestResult>>()
    val parser = ElideAndroidTestParser(listOf("app:android-test-device"),
      { target, result -> results.add(target to result) }) { _, problem -> error(problem) }
    parser.feed(message("sample.Test#same failed", "error", null))
    parser.feed(message("owned by the only target", "error", null))
    parser.end()
    assertEquals("app:android-test-device", results.single().first)
    assertEquals("owned by the only target", results.single().second.detail)
  }

  @Test fun `all pending target failures flush once at end without fabricated details`() {
    val results = mutableListOf<Pair<String, ElideAndroidTestResult>>()
    val targets = listOf("android-test-a", "android-test-b")
    val parser = ElideAndroidTestParser(targets,
      { target, result -> results.add(target to result) }) { _, problem -> error(problem) }
    targets.forEach { parser.feed(message("sample.Test#same failed", "error", it)) }
    parser.end()
    parser.end()
    assertEquals(targets, results.map { it.first })
    assertEquals(listOf(null, null), results.map { it.second.detail })
  }

  @Test fun `ownerless diagnostics stay global when one unqualified target spans members`() {
    val results = mutableListOf<Pair<String, ElideAndroidTestResult>>()
    val problems = mutableListOf<Pair<String?, String>>()
    val parser = ElideAndroidTestParser(listOf("android-test-device"),
      { target, result -> results.add(target to result) }, { target, problem -> problems.add(target to problem) })
    parser.feed(message("sample.Test#same failed", "error", "app:android-test-device"))
    parser.feed(message("sample.Test#same failed", "error", "lib:android-test-device"))
    parser.feed(message("unowned stack", "error", null))
    parser.end()
    assertEquals(listOf<Pair<String?, String>>(null to "unowned stack"), problems)
    assertEquals(listOf("app:android-test-device", "lib:android-test-device"), results.map { it.first })
    assertEquals(listOf(null, null), results.map { it.second.detail })
  }
}
