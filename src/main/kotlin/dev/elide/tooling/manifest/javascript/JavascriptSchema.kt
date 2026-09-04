@file:Suppress("RedundantVisibilityModifier", "Unused")

package dev.elide.tooling.manifest.javascript

import kotlin.Long
import kotlin.Suppress
import kotlin.jvm.JvmInline
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure

/**
 * Configures JavaScript language features and settings.
 */
@Serializable
@SerialName("elide.javascript.JavaScriptSettings")
public data class JavaScriptSettings(
  /**
   * The target ECMAScript standard to support.
   */
  public val ecma: EcmaStandard = EcmaStandard.Stable,
)

/**
 * Defines an ECMA standard level to support.
 */
@Serializable
@SerialName("elide.javascript.EcmaStandard")
public sealed interface EcmaStandard {
  @Serializable(with = OfInt.Companion::class)
  @JvmInline
  public value class OfInt(
    public val `value`: Long,
  ) : EcmaStandard {
    public companion object : KSerializer<OfInt> {
      override val descriptor: SerialDescriptor =
          buildClassSerialDescriptor("elide.javascript.EcmaStandard.OfInt") {
        element<Long>("value",isOptional=false)
      }

      override fun serialize(encoder: Encoder, `value`: OfInt) {
        encoder.encodeStructure(descriptor) {
          encodeLongElement(descriptor,0,value.value)
        }
      }

      override fun deserialize(decoder: Decoder): OfInt = decoder.decodeStructure(descriptor) {
        var value: Long? = null
        while (true) {
          when (val index = decodeElementIndex(descriptor)) {
            0 -> value = decodeLongElement(descriptor,0)
            CompositeDecoder.DECODE_DONE -> break
            else -> error("""Unexpected index: $index""")
          }
        }
        OfInt(value!!)
      }
    }
  }

  @Serializable
  @SerialName("elide.javascript.EcmaStandard.Stable")
  public data object Stable : EcmaStandard

  @Serializable
  @SerialName("elide.javascript.EcmaStandard.Latest")
  public data object Latest : EcmaStandard
}
