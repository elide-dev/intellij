@file:Suppress("RedundantVisibilityModifier", "Unused", "EnumEntryName")

package dev.elide.tooling.manifest.sources

import kotlin.Any
import kotlin.Boolean
import kotlin.Int
import kotlin.String
import kotlin.Suppress
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.collections.emptyList
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
 * Describes a single named group, or set, of source code files.
 */
@Serializable
@SerialName("elide.sources.SourceSetSpec")
public open class SourceSetSpec(
  /**
   * The type of source set.
   */
  public val type: SourceSetType = SourceSetType.Source,
  /**
   * Dependencies of this source set; declarations from dependencies will be
   * visible to sources in this source set.
   */
  public val dependsOn: List<String> = emptyList(),
  /**
   * Paths of source files to enclose.
   */
  public val paths: List<String> = emptyList(),
) : SourceSet {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other == null || javaClass != other.javaClass) return false
    other as SourceSetSpec
    if (this.type != other.type) return false
    if (this.dependsOn != other.dependsOn) return false
    if (this.paths != other.paths) return false
    return true
  }

  override fun hashCode(): Int {
    var result = javaClass.name.hashCode()
    result = 31 * result + this.type.hashCode()
    result = 31 * result + this.dependsOn.hashCode()
    result = 31 * result + this.paths.hashCode()
    return result
  }
}

/**
 * Path or glob for a source file.
 */
public typealias SourceGlobOrPath = String

/**
 * Types of source sets.
 */
@Serializable
@SerialName("elide.sources.SourceSetType")
public enum class SourceSetType {
  @SerialName("source")
  Source,
  @SerialName("test")
  Test,
  @SerialName("example")
  Example,
  @SerialName("other")
  Other,
}

/**
 * Source set names are simple strings.
 */
public typealias SourceSetName = String

/**
 * Source set definition or simple glob.
 */
@Serializable
@SerialName("elide.sources.SourceSet")
public sealed interface SourceSet {
  @Serializable(with = OfString.Companion::class)
  @JvmInline
  public value class OfString(
    public val `value`: String,
  ) : SourceSet {
    public companion object : KSerializer<OfString> {
      override val descriptor: SerialDescriptor =
          buildClassSerialDescriptor("elide.sources.SourceSet.OfString") {
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
 * Top-level container for project source code configurations.
 */
public typealias Sources = Map<String, SourceSet>
