@file:Suppress("RedundantVisibilityModifier", "Unused")

package dev.elide.tooling.manifest.dev

import kotlin.Boolean
import kotlin.Long
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
 * Project source configuration.
 */
@Serializable
@SerialName("elide.dev.ProjectSourceSpec")
public data class ProjectSourceSpec(
  /**
   * Platform which holds this project's source.
   */
  public val platform: SourcePlatform? = null,
  /**
   * Project name or path.
   */
  public val project: String? = null,
  /**
   * Subpath for this project, as applicable.
   */
  public val subpath: String? = null,
)

/**
 * Declaration for an MCP resource.
 */
@Serializable
@SerialName("elide.dev.McpResource")
public data class McpResource(
  /**
   * Path to the file.
   */
  public val path: String,
  /**
   * Resource name
   */
  public val name: String = "",
  /**
   * Resource description
   */
  public val description: String = "",
  /**
   * Mime type to explicitly set; one is detected if not provided.
   */
  public val mimeType: String? = null,
)

/**
 * Settings for Model Context Protocol (MCP) servers.
 */
@Serializable
@SerialName("elide.dev.McpSettings")
public data class McpSettings(
  /**
   * Additional MCP resources to be used by the project.
   */
  public val resources: List<McpResource> = emptyList(),
  /**
   * Whether to register Elide as an MCP tool.
   */
  public val registerElide: Boolean = true,
  /**
   * Whether to register project advice with MCP.
   */
  public val advice: Boolean = true,
)

/**
 * Development server settings for the project.
 */
@Serializable
@SerialName("elide.dev.DevServerSettings")
public data class DevServerSettings(
  /**
   * Host to listen on.
   */
  public val host: String = "0.0.0.0",
  /**
   * Port to listen on.
   */
  public val port: Long = 8_080L,
)

/**
 * Development settings for the project.
 */
@Serializable
@SerialName("elide.dev.DevSettings")
public data class DevSettings(
  /**
   * Source info for the project.
   */
  public val source: ProjectSourceSpec? = null,
  /**
   * Settings which apply to Model Context Protocol (MCP) servers.
   */
  public val mcp: McpSettings? = null,
  /**
   * Development server settings for the project.
   */
  public val server: DevServerSettings? = null,
)

/**
 * Source platform where the project is hosted.
 */
@Serializable
@SerialName("elide.dev.SourcePlatform")
public sealed interface SourcePlatform {
  @Serializable
  @SerialName("elide.dev.SourcePlatform.Github")
  public data object Github : SourcePlatform

  @Serializable
  @SerialName("elide.dev.SourcePlatform.Gitlab")
  public data object Gitlab : SourcePlatform

  @Serializable
  @SerialName("elide.dev.SourcePlatform.Bitbucket")
  public data object Bitbucket : SourcePlatform

  @Serializable
  @SerialName("elide.dev.SourcePlatform.Git")
  public data object Git : SourcePlatform

  @Serializable(with = OfString.Companion::class)
  @JvmInline
  public value class OfString(
    public val `value`: String,
  ) : SourcePlatform {
    public companion object : KSerializer<OfString> {
      override val descriptor: SerialDescriptor =
          buildClassSerialDescriptor("elide.dev.SourcePlatform.OfString") {
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
