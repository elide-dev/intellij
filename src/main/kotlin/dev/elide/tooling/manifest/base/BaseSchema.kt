@file:Suppress("RedundantVisibilityModifier", "Unused", "EnumEntryName")

package dev.elide.tooling.manifest.base

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
import kotlinx.serialization.serializer

/**
 * Semver-style version string.
 */
public typealias SemanticVersion = String

/**
 * Semver-style version range string.
 */
public typealias SemanticVersionRange = String

/**
 * Special symbolic version string.
 */
@Serializable
@SerialName("elide.base.SymbolicVersion")
public enum class SymbolicVersion {
  @SerialName("latest")
  Latest,
  @SerialName("stable")
  Stable,
  @SerialName("snapshot")
  Snapshot,
}

/**
 * Specifies a package version string, which can be `latest`, or a specific semver, or a version range.
 */
@Serializable
@SerialName("elide.base.PackageVersion")
public sealed interface PackageVersion {
  @Serializable(with = OfSymbolicVersion.Companion::class)
  @JvmInline
  public value class OfSymbolicVersion(
    public val `value`: SymbolicVersion,
  ) : PackageVersion {
    public companion object : KSerializer<OfSymbolicVersion> {
      override val descriptor: SerialDescriptor =
          buildClassSerialDescriptor("elide.base.PackageVersion.OfSymbolicVersion") {
        element<SymbolicVersion>("value",isOptional=false)
      }

      override fun serialize(encoder: Encoder, `value`: OfSymbolicVersion) {
        encoder.encodeStructure(descriptor) {
          encodeSerializableElement(descriptor,0,serializer<SymbolicVersion>(),value.value)
        }
      }

      override fun deserialize(decoder: Decoder): OfSymbolicVersion = decoder.decodeStructure(descriptor) {
        var value: SymbolicVersion? = null
        while (true) {
          when (val index = decodeElementIndex(descriptor)) {
            0 -> value = decodeSerializableElement(descriptor,0,serializer<SymbolicVersion>())
            CompositeDecoder.DECODE_DONE -> break
            else -> error("""Unexpected index: $index""")
          }
        }
        OfSymbolicVersion(value!!)
      }
    }
  }

  @Serializable(with = OfString.Companion::class)
  @JvmInline
  public value class OfString(
    public val `value`: String,
  ) : PackageVersion {
    public companion object : KSerializer<OfString> {
      override val descriptor: SerialDescriptor =
          buildClassSerialDescriptor("elide.base.PackageVersion.OfString") {
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
 * Typealias for a dependency repository name.
 */
public typealias RepositoryName = String

/**
 * Typealias for a dependency repository URL.
 */
public typealias RepositoryUrl = String

/**
 * Expects a file path.
 */
public typealias FilePath = String

/**
 * Expects a shell command.
 */
public typealias ShellCommand = String

/**
 * Expects a domain name.
 */
public typealias DomainName = String

/**
 * Expects a file name.
 */
public typealias FileName = String

/**
 * Expects a binary name.
 */
public typealias BinName = String

/**
 * Defines a universal Package URL (purl) string format.
 */
public typealias Purl = String
