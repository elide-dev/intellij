package dev.elide.intellij.execution.build

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** A validated record from the CLI's public ELIDE_PROGRESS=json wire format. Times are epoch milliseconds. */
internal class ElideBuildProgressRecord private constructor(private val fields: JsonObject) {
  val type: String get() = string("type")!!
  val time: Long get() = number("time")!!
  val task: String? get() = string("task")
  val step: String? get() = string("step")
  val message: String? get() = string("message")
  val outcome: String? get() = string("outcome")
  val durationMillis: Long? get() = number("durationMs")

  fun string(name: String): String? = fields[name]?.takeIf {
    it.isJsonPrimitive && it.asJsonPrimitive.isString
  }?.asString

  fun number(name: String): Long? = fields[name]?.takeIf {
    it.isJsonPrimitive && it.asJsonPrimitive.isNumber
  }?.asString?.toLongOrNull()?.takeIf { it >= 0 }

  fun label(): String = listOfNotNull(
    string("project")?.let { "[$it]" },
    message ?: task?.substringAfterLast(':') ?: type,
  ).joinToString(" ")

  /** Human console output only; progress-only records update the native tree instead of flooding the log. */
  fun console(): String? = when (type) {
    "message" -> string("text")!!.let { if (it.endsWith('\n')) it else "$it\n" }
    "task-started" -> "> ${label()}\n"
    "task-finished" -> {
      val mark = when (outcome) { "success" -> "✓"; "skipped" -> "⇥"; else -> "✗" }
      val detail = string("reason") ?: durationMillis?.let { "${it}ms" }
      "$mark ${label()}${detail?.let { " ($it)" }.orEmpty()}\n"
    }
    "build-finished" -> "${if (outcome == "success") "✓" else "✗"} " +
      (message ?: if (outcome == "success") "Build successful" else "Build failed") +
      durationMillis?.let { " (${it}ms)" }.orEmpty() + "\n"
    else -> null
  }

  companion object {
    /** Unknown/malformed objects remain program output; never consume a lookalike record accidentally. */
    fun parse(line: String): ElideBuildProgressRecord? {
      if (!line.trimStart().startsWith('{')) return null
      val fields = try {
        JsonParser.parseString(line).takeIf { it.isJsonObject }?.asJsonObject
      } catch (_: RuntimeException) { null } ?: return null
      val record = ElideBuildProgressRecord(fields)
      if (record.number("time") == null) return null
      val valid = when (record.string("type")) {
        "build-started", "build-progress" -> true
        "build-configured" -> record.number("totalTasks") != null
        "task-started", "task-progress" -> !record.task.isNullOrBlank()
        "task-finished" -> !record.task.isNullOrBlank() && record.outcome in OUTCOMES
        "step-started", "step-progress", "step-finished" -> !record.task.isNullOrBlank() && !record.step.isNullOrBlank()
        "message" -> record.string("text") != null && record.string("level") in LEVELS
        "build-finished" -> record.outcome == "success" || record.outcome == "failure"
        else -> false
      }
      return record.takeIf { valid }
    }

    private val OUTCOMES = setOf("success", "failure", "skipped")
    private val LEVELS = setOf("output", "hint", "warning", "error")
  }
}

/** Console text is separate from the original stream, which test adapters must receive unchanged. */
internal data class ElideBuildOutput(val claimed: Boolean, val text: String?)
