@file:Suppress("RedundantVisibilityModifier", "Unused")

package dev.elide.tooling.manifest.toolchain

import kotlin.Any
import kotlin.Boolean
import kotlin.Int
import kotlin.String
import kotlin.Suppress
import kotlin.collections.Map
import kotlin.collections.emptyMap
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
 * Settings which govern engine behavior, selection, and configuration.
 */
@Serializable
@SerialName("elide.toolchain.EngineSettings")
public open class EngineSettings(
  /**
   * Version or version range to accept.
   */
  public val version: String,
) : EngineSpec {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other == null || javaClass != other.javaClass) return false
    other as EngineSettings
    if (this.version != other.version) return false
    return true
  }

  override fun hashCode(): Int {
    var result = javaClass.name.hashCode()
    result = 31 * result + this.version.hashCode()
    return result
  }
}

/**
 * Settings which govern a project's toolchain and use of that toolchain.
 */
@Serializable
@SerialName("elide.toolchain.ToolchainSettings")
public data class ToolchainSettings(
  /**
   * Engine settings.
   */
  public val engines: Map<String, EngineSpec> = emptyMap(),
)

/**
 * Known engine names.
 */
public typealias KnownEngine = String

/**
 * Known or custom engine name.
 */
public typealias EngineName = String

/**
 * Version or version range accepted for an engine: a semver, a semver range, or any other string
 * an engine chooses to interpret.
 */
public typealias EngineVersion = String

/**
 * Engine settings, or a bare version string standing in for them.
 */
@Serializable
@SerialName("elide.toolchain.EngineSpec")
public sealed interface EngineSpec {
  @Serializable(with = OfString.Companion::class)
  @JvmInline
  public value class OfString(
    public val `value`: String,
  ) : EngineSpec {
    public companion object : KSerializer<OfString> {
      override val descriptor: SerialDescriptor =
          buildClassSerialDescriptor("elide.toolchain.EngineSpec.OfString") {
        element<String>("value",isOptional=false)
      }

      override fun serialize(encoder: Encoder, `value`: OfString) {
        encoder.encodeStructure(descriptor) {
          encodeStringElement(descriptor,0,value.value)
        }
      }

      override fun deserialize(decoder: Decoder): OfString = decoder.decodeStructure(descriptor) {
        var value: String? = null
        while (true) {
          when (val index = decodeElementIndex(descriptor)) {
            0 -> value = decodeStringElement(descriptor,0)
            CompositeDecoder.DECODE_DONE -> break
            else -> error("""Unexpected index: $index""")
          }
        }
        OfString(value!!)
      }
    }
  }
}

/**
 * Mapping of engines to their settings; a simple string semver or range is suitable as well.
 */
public typealias Engines = Map<String, EngineSpec>
