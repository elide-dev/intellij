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
package dev.elide.project.manifest

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** JSON values for the generated model's contextual extension config, without floating-point loss. */
internal object ManifestConfigSerializer : KSerializer<Any> {
  override val descriptor = JsonElement.serializer().descriptor

  override fun deserialize(decoder: Decoder): Any {
    val json = decoder as? JsonDecoder ?: throw SerializationException("Extension config requires JSON")
    return decodeValue(json.decodeJsonElement())
      ?: throw SerializationException("Expected a non-null extension config value")
  }

  override fun serialize(encoder: Encoder, value: Any) {
    val json = encoder as? JsonEncoder ?: throw SerializationException("Extension config requires JSON")
    json.encodeJsonElement(encodeValue(value))
  }

  private fun decodeValue(value: JsonElement): Any? = when (value) {
    JsonNull -> null
    is JsonObject -> value.mapValues { (_, entry) -> decodeValue(entry) }
    is JsonArray -> value.map(::decodeValue)
    is JsonPrimitive -> if (value.isString) value.content else {
      value.booleanOrNull ?: value.longOrNull ?: if (value.content.any { it == '.' || it == 'e' || it == 'E' }) {
        value.content.toBigDecimal()
      } else {
        value.content.toBigInteger()
      }
    }
  }

  private fun encodeValue(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is List<*> -> JsonArray(value.map(::encodeValue))
    is Map<*, *> -> JsonObject(value.entries.associate { (key, entry) ->
      (key as? String ?: throw SerializationException("Extension config object keys must be strings")) to encodeValue(entry)
    })
    else -> throw SerializationException("Unsupported extension config value: ${value::class}")
  }
}
