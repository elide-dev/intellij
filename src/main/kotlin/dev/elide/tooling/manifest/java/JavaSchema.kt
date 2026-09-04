@file:Suppress("RedundantVisibilityModifier", "Unused")

package dev.elide.tooling.manifest.java

import kotlin.Any
import kotlin.Boolean
import kotlin.Int
import kotlin.String
import kotlin.Suppress
import kotlin.collections.List
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
 * Controls settings relating to the Java compiler.
 */
@Serializable
@SerialName("elide.java.JavaCompiler")
public data class JavaCompiler(
  /**
   * Compiler to be used by the build system, defaults to the embedded native javac
   */
  public val mode: JavaCompilerMode = JavaCompilerMode.Embedded,
  /**
   * Extra flags to pass to the Java compiler.
   */
  public val flags: List<String> = emptyList(),
)

/**
 * Configures Java language features and settings.
 */
@Serializable
@SerialName("elide.java.JavaSettings")
public class JavaSettings() {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other == null || javaClass != other.javaClass) return false
    return true
  }

  override fun hashCode(): Int = javaClass.name.hashCode()
}

/**
 * Selects the Java compiler variant used by the build system
 */
@Serializable
@SerialName("elide.java.JavaCompilerMode")
public sealed interface JavaCompilerMode {
  @Serializable
  @SerialName("elide.java.JavaCompilerMode.Embedded")
  public data object Embedded : JavaCompilerMode

  @Serializable
  @SerialName("elide.java.JavaCompilerMode.External")
  public data object External : JavaCompilerMode

  @Serializable(with = OfString.Companion::class)
  @JvmInline
  public value class OfString(
    public val `value`: String,
  ) : JavaCompilerMode {
    public companion object : KSerializer<OfString> {
      override val descriptor: SerialDescriptor =
          buildClassSerialDescriptor("elide.java.JavaCompilerMode.OfString") {
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
