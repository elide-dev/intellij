@file:Suppress("RedundantVisibilityModifier", "Unused", "EnumEntryName")

package dev.elide.tooling.manifest.web

import dev.elide.tooling.manifest.artifacts.Artifact
import kotlin.Boolean
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
 * Declares a static website as an artifact.
 */
@Serializable
@SerialName("elide.web.StaticSite")
public data class StaticSite(
  /**
   * Other artifacts this artifact depends on.
   */
  override val dependsOn: List<String> = emptyList(),
  /**
   * Path to the root source directory for the site.
   */
  public val srcs: String? = null,
  /**
   * Production domain to use for this site.
   */
  public val domain: String? = null,
  /**
   * Preview domain to use for this site.
   */
  public val preview: String? = null,
  /**
   * Web prefix where this site is mounted; considered for links and assets. Must end with a slash.
   */
  public val prefix: String = "/",
  /**
   * Public web path to use for public assets for this site.
   */
  public val assets: String,
  /**
   * Stylesheets to add to all pages.
   */
  public val stylesheets: List<String> = emptyList(),
  /**
   * Scripts to add to all pages.
   */
  public val scripts: List<String> = emptyList(),
  /**
   * Rewrite links when rendering markdown documents.
   */
  public val rewriteLinks: Boolean = true,
  /**
   * Static site host (optional).
   */
  public val hosting: StaticSiteHost? = null,
) : Artifact

/**
 * Describes targeting of a CSS-enabled platform (i.e. browsers).
 */
@Serializable
@SerialName("elide.web.CssTarget")
public data class CssTarget(
  /**
   * The name of the browser type to target.
   */
  public val browser: Browser,
  /**
   * The version of the browser to target.
   */
  public val version: String? = null,
)

/**
 * Describes settings which configure CSS builder and serving behavior.
 */
@Serializable
@SerialName("elide.web.CssSettings")
public data class CssSettings(
  /**
   * Whether to enable minification of CSS code.
   */
  public val minify: Boolean = true,
  /**
   * Target platforms (browsers) to consider when rendering/building CSS.
   */
  public val targets: List<CssTarget> = emptyList(),
)

/**
 * Describes settings which apply in web-based environments; these settings configure how Elide builds and serves web
 * applications, and related resources like images, stylesheets, and JavaScript.
 */
@Serializable
@SerialName("elide.web.WebSettings")
public data class WebSettings(
  /**
   * Settings to apply to CSS processing and serving. If both `browsers` and `css.targets` are specified, the CSS suite
   * wins at build-time.
   */
  public val css: CssSettings = CssSettings(),
  /**
   * Minify rendered HTML.
   */
  public val minifyHtml: Boolean = true,
  /**
   * Browser support for this project, which applies to all built targets.
   */
  public val browsers: List<String> = emptyList(),
)

/**
 * CSS browser types.
 */
@Serializable
@SerialName("elide.web.Browser")
public sealed interface Browser {
  @Serializable
  @SerialName("elide.web.Browser.Chrome")
  public data object Chrome : Browser

  @Serializable
  @SerialName("elide.web.Browser.Firefox")
  public data object Firefox : Browser

  @Serializable
  @SerialName("elide.web.Browser.Safari")
  public data object Safari : Browser

  @Serializable
  @SerialName("elide.web.Browser.Edge")
  public data object Edge : Browser

  @Serializable
  @SerialName("elide.web.Browser.Opera")
  public data object Opera : Browser

  @Serializable(with = OfString.Companion::class)
  @JvmInline
  public value class OfString(
    public val `value`: String,
  ) : Browser {
    public companion object : KSerializer<OfString> {
      override val descriptor: SerialDescriptor =
          buildClassSerialDescriptor("elide.web.Browser.OfString") {
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
 * Static site hosting types.
 */
@Serializable
@SerialName("elide.web.StaticSiteHost")
public enum class StaticSiteHost {
  @SerialName("workers")
  Workers,
  @SerialName("github-pages")
  Github_pages,
}
