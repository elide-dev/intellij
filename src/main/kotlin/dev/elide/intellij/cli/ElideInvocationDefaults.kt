package dev.elide.intellij.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Project defaults sit below explicit CLI options; tokens after `--` always belong to the program. */
internal object ElideInvocationDefaults {
  private val optionName = Regex("[A-Za-z0-9][A-Za-z0-9-]*")
  fun parse(text: String): JsonObject {
    val options = Json.parseToJsonElement(text.ifBlank { "{}" }) as? JsonObject
      ?: throw IllegalArgumentException("Command options must be a JSON object")
    for ((name, value) in options) {
      require(name.removePrefix("--").removePrefix("-").matches(optionName)) {
        "Invalid command option name: $name"
      }
      val values = if (value is JsonArray) value else listOf(value)
      require(values.all { it is JsonPrimitive && it != JsonNull }) {
        "Option $name must contain strings, numbers or booleans"
      }
    }
    return options
  }

  fun apply(arguments: List<String>, defaults: Map<String, String>): List<String> {
    val invocation = ElideCli.parse(arguments)
    val command = invocation.command ?: return arguments
    val text = defaults[command.name] ?: return arguments
    val options = parse(text)
    if (options.isEmpty()) return arguments
    val end = invocation.passthroughIndex.takeIf { it >= 0 } ?: arguments.size
    val flags = invocation.applicableFlags()
    fun canonical(token: String): String = flags.firstOrNull { it.matches(token) }?.option
      ?: token.substringBefore('=')
    val explicit = HashSet<String>()
    var index = 0
    while (index < end) {
      val token = arguments[index]
      if (token.startsWith('-')) {
        explicit.add(canonical(token))
        if (flags.any { it.takesNextToken(token) }) index++
      }
      index++
    }
    val added = buildList {
      for ((name, value) in options) {
        val bare = name.removePrefix("--").removePrefix("-")
        val option = if (bare.length == 1) "-$bare" else "--$bare"
        if (canonical(option) in explicit) continue
        val values: List<JsonElement> = if (value is JsonArray) value else listOf(value)
        for (entry in values) {
          val primitive = entry as JsonPrimitive
          when {
            !primitive.isString && primitive.booleanOrNull == false -> Unit
            !primitive.isString && primitive.booleanOrNull == true -> add(option)
            bare.length == 1 -> { add(option); add(primitive.content) }
            else -> add("$option=${primitive.content}")
          }
        }
      }
    }
    if (added.isEmpty()) return arguments
    // Build target options are trailing-var-args: they must follow at least one target.
    val insertion = if (command == ElideCli.BUILD) end else invocation.commandIndex + 1
    return buildList(arguments.size + added.size) {
      addAll(arguments.subList(0, insertion))
      addAll(added)
      addAll(arguments.subList(insertion, arguments.size))
    }
  }
}
