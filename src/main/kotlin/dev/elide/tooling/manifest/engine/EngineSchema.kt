@file:Suppress("RedundantVisibilityModifier", "Unused")

package dev.elide.tooling.manifest.engine

import kotlin.Long
import kotlin.String
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
 * Application context sizing. Counts do not imply CPU affinity or JIT warming.
 */
@Serializable
@SerialName("elide.engine.ContextSettings")
public data class ContextSettings(
  /**
   * Number of contexts to spawn; auto subtracts reserve from available processors.
   */
  public val spawn: ContextSpawn = ContextSpawn.OfInt(1L),
  /**
   * Processor capacity left for other runtime work when spawn is auto.
   */
  public val reserve: Long = 2L,
  /**
   * Lower bound on the resolved count.
   */
  public val min: Long = 1L,
  /**
   * Optional upper bound on the resolved count; must be at least min.
   */
  public val max: Long? = null,
)

/**
 * Configures the execution engine used by Elide when running this project.
 */
@Serializable
@SerialName("elide.engine.EngineSettings")
public data class EngineSettings(
  /**
   * Context lifecycle strategy.
   */
  public val topology: String = "prespawn",
  /**
   * Application context sizing; omission spawns one context.
   */
  public val contexts: ContextSettings? = null,
  /**
   * Deprecated fixed context count. Use contexts.spawn; do not specify both.
   */
  public val maxContexts: Long? = null,
)

/**
 * Context lifecycle strategy. Prespawn initializes application replicas before serving readiness.
 */
public typealias ContextTopology = String

/**
 * Fixed context count or processor-based sizing, resolved once at startup.
 */
@Serializable
@SerialName("elide.engine.ContextSpawn")
public sealed interface ContextSpawn {
  @Serializable(with = OfInt.Companion::class)
  @JvmInline
  public value class OfInt(
    public val `value`: Long,
  ) : ContextSpawn {
    public companion object : KSerializer<OfInt> {
      override val descriptor: SerialDescriptor =
          buildClassSerialDescriptor("elide.engine.ContextSpawn.OfInt") {
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
  @SerialName("elide.engine.ContextSpawn.Auto")
  public data object Auto : ContextSpawn
}
