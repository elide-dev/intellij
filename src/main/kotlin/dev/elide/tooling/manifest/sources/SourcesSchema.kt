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
 * A directory (or single file) of files carried into whatever a source set produces.
 *
 * "Carried" means copied byte-for-byte: resources are never compiled, filtered or expanded, so a
 * file lands in the output exactly as it sits on disk.
 *
 * `from` is read relative to the project root; `into` is the prefix the contents take inside that
 * output, so `into = "META-INF"` publishes `<from>/services/…` as `META-INF/services/…`. The
 * default, the empty string, publishes the contents at the output's root.
 */
@Serializable
@SerialName("elide.sources.ResourceRoot")
public data class ResourceRoot(
  /**
   * Directory or file holding the resources, relative to the project root.
   */
  public val from: String,
  /**
   * Prefix the contents take in the build's output; empty places them at the root.
   */
  public val into: String = "",
) : ResourceEntry

/**
 * Describes a single named group, or set, of source code files.
 *
 * Every property is optional; each unset property takes the conventional layout for the set's
 * name: `src/<name>` (with `src/<name>/<language>` roots and `src/<name>/resources` honored when
 * present), falling back to `src` for `main` and `test`/`tests` for `test`.
 */
@Serializable
@SerialName("elide.sources.SourceSetSpec")
public open class SourceSetSpec(
  /**
   * The type of source set; inferred from the name when absent (`test`/`tests` are test source
   * sets, `example`/`examples` example source sets, anything else a source set).
   */
  public val type: SourceSetType? = null,
  /**
   * Dependencies of this source set; declarations from dependencies will be visible to sources in
   * this source set. Absent means the convention: a test or example source set sees `main`, and
   * `main` sees nothing. An empty listing declares that this source set sees nothing.
   */
  public val dependsOn: List<String>? = null,
  /**
   * Paths or globs of source files to enclose; empty takes the conventional layout for the name.
   */
  public val paths: List<String> = emptyList(),
  /**
   * Files carried with this source set's output; empty takes the conventional layout
   * (`src/<name>/resources`, when it exists). See [ResourceRoot] for what "carried" means.
   * An entry is a bare path or a structural root; the latter needs no type name, since an element
   * amended in place (`new { from = "assets"; into = "static" }`) takes this listing's default.
   */
  public val resources: List<ResourceEntry> = emptyList(),
) : SourceSet {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other == null || javaClass != other.javaClass) return false
    other as SourceSetSpec
    if (this.type != other.type) return false
    if (this.dependsOn != other.dependsOn) return false
    if (this.paths != other.paths) return false
    if (this.resources != other.resources) return false
    return true
  }

  override fun hashCode(): Int {
    var result = javaClass.name.hashCode()
    result = 31 * result + (this.type?.hashCode() ?: 0)
    result = 31 * result + (this.dependsOn?.hashCode() ?: 0)
    result = 31 * result + this.paths.hashCode()
    result = 31 * result + this.resources.hashCode()
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
 * Resource declaration: a structural [ResourceRoot], or a path published at the output root.
 */
@Serializable
@SerialName("elide.sources.ResourceEntry")
public sealed interface ResourceEntry {
  @Serializable(with = OfString.Companion::class)
  @JvmInline
  public value class OfString(
    public val `value`: String,
  ) : ResourceEntry {
    public companion object : KSerializer<OfString> {
      override val descriptor: SerialDescriptor =
          buildClassSerialDescriptor("elide.sources.ResourceEntry.OfString") {
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
 *
 * Undeclared source sets take the layout the project implies; declaring one by name replaces the
 * convention for that name only.
 */
public typealias Sources = Map<String, SourceSet>
