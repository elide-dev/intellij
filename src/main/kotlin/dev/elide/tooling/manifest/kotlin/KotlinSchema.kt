@file:Suppress("RedundantVisibilityModifier", "Unused", "EnumEntryName")

package dev.elide.tooling.manifest.kotlin

import dev.elide.tooling.manifest.jvm.JvmTargetLevel
import kotlin.Boolean
import kotlin.String
import kotlin.Suppress
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.collections.emptyList
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
 * Specifies options which relate to the Kotlin compiler.
 */
public interface KotlinCompilerOptions {
  /**
   * Opt-ins to add to Kotlin compiler invocations.
   */
  public val optIn: List<String>

  /**
   * Whether to enable the compiler's progressive mode.
   */
  public val progressiveMode: Boolean

  /**
   * Whether to enable extra K2 warnings and checks.
   */
  public val extraWarnings: Boolean

  /**
   * Report an error if there are any warnings.
   */
  public val allWarningsAsErrors: Boolean

  /**
   * Don't generate any warnings.
   */
  public val suppressWarnings: Boolean

  /**
   * Enable verbose logging output.
   */
  public val verbose: Boolean

  /**
   * Arbitrary arguments to pass to the Kotlin compiler.
   */
  public val freeCompilerArgs: List<String>

  /**
   * Explicitly set an API version for Kotlin Compiler invocations; this should typically be left at the default, which
   * allows Elide to align API version options.
   */
  public val apiVersion: KotlinLanguageTargets

  /**
   * Explicitly set a language version for Kotlin Compiler invocations; this should typically be left at the default,
   * which allows Elide to align language version options.
   */
  public val languageVersion: KotlinLanguageTargets

  /**
   * Include Kotlin runtime classes within the output artifact.
   */
  public val includeRuntime: Boolean

  /**
   * Don't automatically include the Kotlin Standard Library on the classpath.
   */
  public val noStdlib: Boolean
}

/**
 * Configures the Kotlin compiler when targeting JVM.
 */
@Serializable
@SerialName("elide.kotlin.KotlinCompilerJvmOptions")
public data class KotlinCompilerJvmOptions(
  /**
   * Opt-ins to add to Kotlin compiler invocations.
   */
  override val optIn: List<String> = emptyList(),
  /**
   * Whether to enable the compiler's progressive mode.
   */
  override val progressiveMode: Boolean = false,
  /**
   * Whether to enable extra K2 warnings and checks.
   */
  override val extraWarnings: Boolean = false,
  /**
   * Report an error if there are any warnings.
   */
  override val allWarningsAsErrors: Boolean = false,
  /**
   * Don't generate any warnings.
   */
  override val suppressWarnings: Boolean = false,
  /**
   * Enable verbose logging output.
   */
  override val verbose: Boolean = false,
  /**
   * Arbitrary arguments to pass to the Kotlin compiler.
   */
  override val freeCompilerArgs: List<String> = emptyList(),
  /**
   * Explicitly set an API version for Kotlin Compiler invocations; this should typically be left at the default, which
   * allows Elide to align API version options.
   */
  override val apiVersion: KotlinLanguageTargets = KotlinLanguageTargets.VAuto,
  /**
   * Explicitly set a language version for Kotlin Compiler invocations; this should typically be left at the default,
   * which allows Elide to align language version options.
   */
  override val languageVersion: KotlinLanguageTargets = KotlinLanguageTargets.VAuto,
  /**
   * Include Kotlin runtime classes within the output artifact.
   */
  override val includeRuntime: Boolean = false,
  /**
   * Don't automatically include the Kotlin Standard Library on the classpath.
   */
  override val noStdlib: Boolean = false,
  /**
   * Generate metadata for Java 1.8 reflection on method parameters.
   */
  public val javaParameters: Boolean = false,
  /**
   * Explicitly set a JVM target; typically this should be left at the default, which allows Elide to align JVM target
   * options with Java, as applicable.
   */
  public val jvmTarget: JvmTargetLevel = JvmTargetLevel.Auto,
  /**
   * Don't automatically include the Java runtime on the classpath.
   */
  public val noJdk: Boolean = false,
  /**
   * Validation of JVM target compatibility between Kotlin and Java.
   */
  public val jvmTargetValidationMode: JvmTargetValidationMode = JvmTargetValidationMode.ERROR,
  public val incremental: Boolean = true,
) : KotlinCompilerOptions

/**
 * Specifies Kotlin-related features and options within Elide.
 */
@Serializable
@SerialName("elide.kotlin.KotlinFeatureOptions")
public data class KotlinFeatureOptions(
  /**
   * Deprecated: KAPT is retired. This property is accepted for backward compatibility but is
   * ignored — annotation processing now runs under `javac` after the Kotlin compile.
   */
  public val kapt: Boolean = true,
  /**
   * Whether to enable Kotlin's test support features.
   */
  public val testing: Boolean = true,
  /**
   * Whether to enable KotlinX dependencies automatically on the classpath.
   */
  public val kotlinx: Boolean = true,
  /**
   * Whether to enable the default suite of built-in plugins for the Kotlin compiler.
   */
  public val defaultPlugins: Boolean = true,
  /**
   * Enable or disable KotlinX serialization support.
   */
  public val serialization: Boolean,
  /**
   * Enable or disable KotlinX Coroutines support.
   */
  public val coroutines: Boolean,
  /**
   * Enable or disable the KotlinX AtomicFU compiler plugin.
   */
  public val atomicfu: Boolean,
  /**
   * Enable or disable the Power Assert compiler plugin.
   */
  public val powerAssert: Boolean,
  /**
   * Enable or disable the Metro dependency-injection compiler plugin.
   */
  public val metro: Boolean,
  /**
   * Whether to enable Kotlin's reflection features.
   */
  public val reflection: Boolean = true,
)

/**
 * Describes a managed Kotlin toolchain using a specific version. Managed toolchains are automatically
 * resolved and downloaded by Elide when required.
 */
@Serializable
@SerialName("elide.kotlin.ManagedKotlinToolchain")
public data class ManagedKotlinToolchain(
  /**
   * Kotlin version for this managed toolchain.
   */
  public val version: String,
) : KotlinToolchain

/**
 * Describes a custom Kotlin toolchain, using the path to a local distribution.
 */
@Serializable
@SerialName("elide.kotlin.CustomKotlinToolchain")
public data class CustomKotlinToolchain(
  /**
   * Path to a local Kotlin distribution.
   */
  public val path: String,
) : KotlinToolchain

/**
 * Specifies settings which apply to Kotlin projects.
 */
@Serializable
@SerialName("elide.kotlin.KotlinSettings")
public data class KotlinSettings(
  /**
   * Set the uniform Kotlin API level. Defaults to auto-detecting the best API level to use.
   */
  public val apiLevel: KotlinLanguageTargets = KotlinLanguageTargets.VAuto,
  /**
   * Set the uniform Kotlin language target. Defaults to auto-detecting the best language target to use.
   */
  public val languageLevel: KotlinLanguageTargets = KotlinLanguageTargets.VAuto,
  /**
   * Adjust settings for the Kotlin compiler.
   */
  public val compilerOptions: KotlinCompilerJvmOptions = KotlinCompilerJvmOptions(),
  /**
   * Manage Elide settings which relate to Kotlin.
   */
  public val features: KotlinFeatureOptions,
  /**
   * Select the Kotlin toolchain to be used.
   */
  public val toolchain: KotlinToolchain = KotlinToolchain.Embedded,
  /**
   * Configure options passed to Kotlin compiler plugins.
   */
  public val plugins: Map<String, Map<String, String>> = emptyMap(),
)

/**
 * Kotlin language targets.
 */
@Serializable
@SerialName("elide.kotlin.KotlinLanguageTargets")
public sealed interface KotlinLanguageTargets {
  @Serializable
  @SerialName("elide.kotlin.KotlinLanguageTargets.VLatest")
  public data object VLatest : KotlinLanguageTargets

  @Serializable
  @SerialName("elide.kotlin.KotlinLanguageTargets.VStable")
  public data object VStable : KotlinLanguageTargets

  @Serializable
  @SerialName("elide.kotlin.KotlinLanguageTargets.VAuto")
  public data object VAuto : KotlinLanguageTargets

  @Serializable
  @SerialName("elide.kotlin.KotlinLanguageTargets.V1_9")
  public data object V1_9 : KotlinLanguageTargets

  @Serializable
  @SerialName("elide.kotlin.KotlinLanguageTargets.V2_0")
  public data object V2_0 : KotlinLanguageTargets

  @Serializable
  @SerialName("elide.kotlin.KotlinLanguageTargets.V2_1")
  public data object V2_1 : KotlinLanguageTargets

  @Serializable
  @SerialName("elide.kotlin.KotlinLanguageTargets.V2_2")
  public data object V2_2 : KotlinLanguageTargets

  @Serializable
  @SerialName("elide.kotlin.KotlinLanguageTargets.V2_3")
  public data object V2_3 : KotlinLanguageTargets

  @Serializable
  @SerialName("elide.kotlin.KotlinLanguageTargets.V2_4")
  public data object V2_4 : KotlinLanguageTargets

  @Serializable(with = OfString.Companion::class)
  @JvmInline
  public value class OfString(
    public val `value`: String,
  ) : KotlinLanguageTargets {
    public companion object : KSerializer<OfString> {
      override val descriptor: SerialDescriptor =
          buildClassSerialDescriptor("elide.kotlin.KotlinLanguageTargets.OfString") {
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
 * Kotlin language levels.
 */
public typealias KotlinLanguageLevel = KotlinLanguageTargets

/**
 * Kotlin API levels.
 */
public typealias KotlinApiLevel = KotlinLanguageTargets

/**
 * Modes for the JVM target validation mode option when compiling for JVM.
 */
@Serializable
@SerialName("elide.kotlin.JvmTargetValidationMode")
public enum class JvmTargetValidationMode {
  @SerialName("WARNING")
  WARNING,
  @SerialName("ERROR")
  ERROR,
  @SerialName("IGNORE")
  IGNORE,
}

/**
 * Describes a Kotlin toolchain to be used for compilation.
 */
@Serializable
@SerialName("elide.kotlin.KotlinToolchain")
public sealed interface KotlinToolchain {
  @Serializable
  @SerialName("elide.kotlin.KotlinToolchain.Embedded")
  public data object Embedded : KotlinToolchain
}

/**
 * Describes a set of option key-value pairs used to configure a Kotlin compiler plugin
 */
public typealias KotlinPluginOptions = Map<String, String>

/**
 * An identifier for a Kotlin compiler plugin.
 */
public typealias KotlinPluginId = String
