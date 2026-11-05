package dev.elide.intellij.android.testing

import com.google.gson.JsonParser
import dev.elide.intellij.cli.ElideCli
import dev.elide.intellij.project.model.buildCommandLine

internal data class ElideAndroidTestResult(
  val className: String,
  val method: String,
  val outcome: String,
  val detail: String? = null,
)

/** Reads ELIDE_PROGRESS=json messages, retaining a failed test until its stack trace arrives. */
internal class ElideAndroidTestParser(
  targets: List<String>,
  private val onResult: (String, ElideAndroidTestResult) -> Unit,
  private val onProblem: (String?, String) -> Unit,
) {
  private val buffer = StringBuilder()
  private val targets = targets.distinct()
  private val owners = linkedSetOf<String>()
  private val pending = linkedMapOf<String, ElideAndroidTestResult>()
  private var ended = false

  fun feed(text: String) {
    if (ended) return
    buffer.append(text)
    var end = buffer.indexOf("\n")
    while (end >= 0) {
      line(buffer.substring(0, end).trimEnd('\r'))
      buffer.delete(0, end + 1)
      end = buffer.indexOf("\n")
    }
  }

  fun end() {
    if (ended) return
    ended = true
    if (buffer.isNotEmpty()) line(buffer.toString())
    buffer.setLength(0)
    pending.forEach { (target, result) -> onResult(target, result) }
    pending.clear()
  }

  private fun line(line: String) {
    val record = try { JsonParser.parseString(line).takeIf { it.isJsonObject }?.asJsonObject } catch (_: RuntimeException) { null }
      ?: return
    fun field(name: String): String? = record.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    if (field("type") != "message") return
    val owner = field("task")?.removePrefix(":")
    if (owner != null && targets.none { matchesTarget(it, owner) }) return
    if (owner != null) owners.add(owner)
    // Missing ownership is only safe when both the command and the observed stream identify one target.
    val target = owner ?: if (targets.size == 1 && owners.size <= 1) owners.singleOrNull() ?: targets.single() else null
    val text = field("text")?.removePrefix("error: ") ?: return
    val result = parseResult(text)
    if (target == null) {
      if (result != null) onProblem(null, "Instrumented test result has no unambiguous target: $text")
      else if (field("level") == "error" && !SUMMARY.matches(text.trim())) onProblem(null, text)
      return
    }
    if (result == null && field("level") == "error" && pending.containsKey(target) && !SUMMARY.matches(text.trim())) {
      onResult(target, pending.remove(target)!!.copy(detail = text))
      return
    }
    flush(target)
    if (result?.outcome == "failed") pending[target] = result
    else if (result != null) onResult(target, result)
    else if (field("level") == "error" && !SUMMARY.matches(text.trim())) onProblem(target, text)
  }

  private fun flush(target: String) {
    pending.remove(target)?.let { onResult(target, it) }
  }

  companion object {
    private val RESULT = Regex("^(?:error:\\s+)?([^\\s#]+)#(.+) (passed|failed|skipped)$")
    private val SUMMARY = Regex("^\\d+ passed, \\d+ failed, \\d+ skipped$")

    fun parseResult(text: String): ElideAndroidTestResult? = RESULT.matchEntire(text.trim())?.let {
      ElideAndroidTestResult(it.groupValues[1], it.groupValues[2], it.groupValues[3])
    }

    fun matchesTarget(target: String, owner: String): Boolean =
      owner == target || (':' !in target && owner.substringAfterLast(':') == target)

    fun targets(commandLine: List<String>): List<String> {
      val normalized = buildCommandLine(commandLine) ?: commandLine
      return ElideCli.buildTargets(normalized).filter {
        it.substringAfterLast(':').startsWith("android-test-")
      }.distinct()
    }
  }
}
