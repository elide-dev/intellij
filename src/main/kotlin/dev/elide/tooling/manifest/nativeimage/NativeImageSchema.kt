@file:Suppress("RedundantVisibilityModifier", "Unused", "EnumEntryName")

package dev.elide.tooling.manifest.nativeimage

import dev.elide.tooling.manifest.artifacts.Artifact
import dev.elide.tooling.manifest.jvm.MavenPackageDependency
import kotlin.Any
import kotlin.Boolean
import kotlin.Int
import kotlin.String
import kotlin.Suppress
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.collections.emptyList
import kotlin.collections.emptyMap
import kotlin.collections.listOf
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
 * Configuration for Native Image link-at-build-time settings.
 */
@Serializable
@SerialName("elide.nativeImage.NativeImageLinkAtBuildTime")
public data class NativeImageLinkAtBuildTime(
  /**
   * Whether link-at-build-time is enabled as the default.
   */
  public val enabled: Boolean = true,
  /**
   * Specific packages to link at build time.
   */
  public val packages: List<String> = emptyList(),
)

/**
 * Configuration for Native Image class initialization settings.
 */
@Serializable
@SerialName("elide.nativeImage.NativeImageClassInit")
public data class NativeImageClassInit(
  /**
   * Whether initialize-at-build-time is enabled as the default.
   */
  public val default: ClassInitialization = ClassInitialization.Buildtime,
  /**
   * Specific classes or packages to initialize at build time.
   */
  public val buildtime: List<String> = emptyList(),
  /**
   * Specific classes or packages to initialize at runtime.
   */
  public val runtime: List<String> = emptyList(),
)

/**
 * Settings which govern Profile Guided Optimization (PGO) for Native Images.
 */
@Serializable
@SerialName("elide.nativeImage.ProfileGuidedOptimization")
public data class ProfileGuidedOptimization(
  /**
   * Whether PGO is enabled (only activates with present profiles).
   */
  public val enabled: Boolean = true,
  /**
   * Whether to enable auto-build features for PGO.
   */
  public val autoprofile: Boolean = false,
  /**
   * Whether to instrument for PGO.
   */
  public val instrument: Boolean = false,
  /**
   * Whether to use sampling for PGO.
   */
  public val sampling: Boolean = false,
  /**
   * PGO profiles to apply.
   */
  public val profiles: List<String> = emptyList(),
)

/**
 * Specifies classes and modules to be excluded when building a Native Image.
 */
@Serializable
@SerialName("elide.nativeImage.NativeImageExclusions")
public data class NativeImageExclusions(
  /**
   * Exclusions from all paths.
   */
  public val all:
      List<MavenPackageDependency> = listOf(MavenPackageDependency.OfString("org.graalvm.compiler:compiler"), MavenPackageDependency.OfString("org.graalvm.espresso:espresso-svm"), MavenPackageDependency.OfString("org.graalvm.nativeimage:native-image-base"), MavenPackageDependency.OfString("org.graalvm.nativeimage:objectfile"), MavenPackageDependency.OfString("org.graalvm.nativeimage:pointsto"), MavenPackageDependency.OfString("org.graalvm.nativeimage:svm")),
  /**
   * Classpath exclusions to apply.
   */
  public val classpath: List<MavenPackageDependency> = emptyList(),
  /**
   * Modulepath exclusions to apply.
   */
  public val modulepath: List<MavenPackageDependency> = emptyList(),
)

/**
 * Specifies the layout of options for a Native Image.
 */
@Serializable
@SerialName("elide.nativeImage.NativeImageOptions")
public open class NativeImageOptions(
  /**
   * Whether to activate verbose output.
   */
  public val verbose: Boolean = false,
  /**
   * How Native Image should be invoked.
   */
  public val driverMode: NativeImageDriverMode = NativeImageDriverMode.Embedded,
  /**
   * Build-time linkage options.
   */
  public val linkAtBuildTime: NativeImageLinkAtBuildTime = NativeImageLinkAtBuildTime(),
  /**
   * Class initialization options.
   */
  public val classInit: NativeImageClassInit = NativeImageClassInit(),
  /**
   * Exclusions to apply to classpath and modulepath calculations.
   */
  public val exclusions: NativeImageExclusions = NativeImageExclusions(),
  /**
   * Optimization level for the Native Image.
   */
  public val optimization: OptimizationLevel = OptimizationLevel.OAuto,
  /**
   * PGO (Profiling Guided Optimization) settings.
   */
  public val pgo: ProfileGuidedOptimization = ProfileGuidedOptimization(),
  /**
   * Enabled compiler features.
   */
  public val features: List<String> = emptyList(),
  /**
   * Extra flags to pass to the Native Image compiler; added to all project targets.
   */
  public val flags: List<String> = emptyList(),
  /**
   * Extra flags to pass to the native C compiler; added to all project targets.
   */
  public val cflags: List<String> = emptyList(),
  /**
   * Extra flags to pass to the native linker; added to all project targets.
   */
  public val ldflags: List<String> = emptyList(),
  /**
   * Definitions of system properties to pass during the Native Image build.
   */
  public val defs: Map<String, String> = emptyMap(),
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other == null || javaClass != other.javaClass) return false
    other as NativeImageOptions
    if (this.verbose != other.verbose) return false
    if (this.driverMode != other.driverMode) return false
    if (this.linkAtBuildTime != other.linkAtBuildTime) return false
    if (this.classInit != other.classInit) return false
    if (this.exclusions != other.exclusions) return false
    if (this.optimization != other.optimization) return false
    if (this.pgo != other.pgo) return false
    if (this.features != other.features) return false
    if (this.flags != other.flags) return false
    if (this.cflags != other.cflags) return false
    if (this.ldflags != other.ldflags) return false
    if (this.defs != other.defs) return false
    return true
  }

  override fun hashCode(): Int {
    var result = javaClass.name.hashCode()
    result = 31 * result + this.verbose.hashCode()
    result = 31 * result + this.driverMode.hashCode()
    result = 31 * result + this.linkAtBuildTime.hashCode()
    result = 31 * result + this.classInit.hashCode()
    result = 31 * result + this.exclusions.hashCode()
    result = 31 * result + this.optimization.hashCode()
    result = 31 * result + this.pgo.hashCode()
    result = 31 * result + this.features.hashCode()
    result = 31 * result + this.flags.hashCode()
    result = 31 * result + this.cflags.hashCode()
    result = 31 * result + this.ldflags.hashCode()
    result = 31 * result + this.defs.hashCode()
    return result
  }
}

/**
 * Configures Native Image generation settings, project-wide.
 */
@Serializable(with = NativeImageSettings.Companion::class)
public class NativeImageSettings(
  verbose: Boolean = false,
  driverMode: NativeImageDriverMode = NativeImageDriverMode.Embedded,
  linkAtBuildTime: NativeImageLinkAtBuildTime = NativeImageLinkAtBuildTime(),
  classInit: NativeImageClassInit = NativeImageClassInit(),
  exclusions: NativeImageExclusions = NativeImageExclusions(),
  optimization: OptimizationLevel = OptimizationLevel.OAuto,
  pgo: ProfileGuidedOptimization = ProfileGuidedOptimization(),
  features: List<String> = emptyList(),
  flags: List<String> = emptyList(),
  cflags: List<String> = emptyList(),
  ldflags: List<String> = emptyList(),
  defs: Map<String, String> = emptyMap(),
) : NativeImageOptions(verbose=verbose, driverMode=driverMode, linkAtBuildTime=linkAtBuildTime, classInit=classInit, exclusions=exclusions, optimization=optimization, pgo=pgo, features=features, flags=flags, cflags=cflags, ldflags=ldflags, defs=defs) {
  public companion object : KSerializer<NativeImageSettings> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("elide.nativeImage.NativeImageSettings") {
      element<Boolean>("verbose",isOptional=true)
      element<NativeImageDriverMode>("driverMode",isOptional=true)
      element<NativeImageLinkAtBuildTime>("linkAtBuildTime",isOptional=true)
      element<NativeImageClassInit>("classInit",isOptional=true)
      element<NativeImageExclusions>("exclusions",isOptional=true)
      element<OptimizationLevel>("optimization",isOptional=true)
      element<ProfileGuidedOptimization>("pgo",isOptional=true)
      element<List<String>>("features",isOptional=true)
      element<List<String>>("flags",isOptional=true)
      element<List<String>>("cflags",isOptional=true)
      element<List<String>>("ldflags",isOptional=true)
      element<Map<String, String>>("defs",isOptional=true)
    }

    override fun serialize(encoder: Encoder, `value`: NativeImageSettings) {
      encoder.encodeStructure(descriptor) {
        encodeBooleanElement(descriptor,0,value.verbose)
        encodeSerializableElement(descriptor,1,serializer<NativeImageDriverMode>(),value.driverMode)
        encodeSerializableElement(descriptor,2,serializer<NativeImageLinkAtBuildTime>(),value.linkAtBuildTime)
        encodeSerializableElement(descriptor,3,serializer<NativeImageClassInit>(),value.classInit)
        encodeSerializableElement(descriptor,4,serializer<NativeImageExclusions>(),value.exclusions)
        encodeSerializableElement(descriptor,5,serializer<OptimizationLevel>(),value.optimization)
        encodeSerializableElement(descriptor,6,serializer<ProfileGuidedOptimization>(),value.pgo)
        encodeSerializableElement(descriptor,7,serializer<List<String>>(),value.features)
        encodeSerializableElement(descriptor,8,serializer<List<String>>(),value.flags)
        encodeSerializableElement(descriptor,9,serializer<List<String>>(),value.cflags)
        encodeSerializableElement(descriptor,10,serializer<List<String>>(),value.ldflags)
        encodeSerializableElement(descriptor,11,serializer<Map<String, String>>(),value.defs)
      }
    }

    override fun deserialize(decoder: Decoder): NativeImageSettings = decoder.decodeStructure(descriptor) {
      var verbose: Boolean = false
      var driverMode: NativeImageDriverMode = NativeImageDriverMode.Embedded
      var linkAtBuildTime: NativeImageLinkAtBuildTime = NativeImageLinkAtBuildTime()
      var classInit: NativeImageClassInit = NativeImageClassInit()
      var exclusions: NativeImageExclusions = NativeImageExclusions()
      var optimization: OptimizationLevel = OptimizationLevel.OAuto
      var pgo: ProfileGuidedOptimization = ProfileGuidedOptimization()
      var features: List<String> = emptyList()
      var flags: List<String> = emptyList()
      var cflags: List<String> = emptyList()
      var ldflags: List<String> = emptyList()
      var defs: Map<String, String> = emptyMap()
      while (true) {
        when (val index = decodeElementIndex(descriptor)) {
          0 -> verbose=decodeBooleanElement(descriptor,0)
          1 -> driverMode=decodeSerializableElement(descriptor,1,serializer<NativeImageDriverMode>())
          2 -> linkAtBuildTime=decodeSerializableElement(descriptor,2,serializer<NativeImageLinkAtBuildTime>())
          3 -> classInit=decodeSerializableElement(descriptor,3,serializer<NativeImageClassInit>())
          4 -> exclusions=decodeSerializableElement(descriptor,4,serializer<NativeImageExclusions>())
          5 -> optimization=decodeSerializableElement(descriptor,5,serializer<OptimizationLevel>())
          6 -> pgo=decodeSerializableElement(descriptor,6,serializer<ProfileGuidedOptimization>())
          7 -> features=decodeSerializableElement(descriptor,7,serializer<List<String>>())
          8 -> flags=decodeSerializableElement(descriptor,8,serializer<List<String>>())
          9 -> cflags=decodeSerializableElement(descriptor,9,serializer<List<String>>())
          10 -> ldflags=decodeSerializableElement(descriptor,10,serializer<List<String>>())
          11 -> defs=decodeSerializableElement(descriptor,11,serializer<Map<String, String>>())
          CompositeDecoder.DECODE_DONE -> break
          else -> error("""Unexpected index: $index""")
        }
      }
      NativeImageSettings(verbose,driverMode,linkAtBuildTime,classInit,exclusions,optimization,pgo,features,flags,cflags,ldflags,defs)
    }
  }
}

/**
 * Describes a Native Image artifact within an Elide project.
 */
@Serializable
@SerialName("elide.nativeImage.NativeImage")
public data class NativeImage(
  /**
   * Other artifacts this artifact depends on.
   */
  override val dependsOn: List<String> = emptyList(),
  /**
   * Artifacts from which to build the Native Image.
   */
  public val from: List<String> = emptyList(),
  /**
   * Name of the output artifact (binary or library). If not provided, one is calculated.
   */
  public val name: String? = null,
  /**
   * Entrypoint class for the Native Image. If the class is within a JPMS module, the module should be specified in the
   * `moduleName` field.
   */
  public val entrypoint: String? = null,
  /**
   * Type of image to produce. Defaults to "binary".
   */
  public val type: ImageType = ImageType.Binary,
  /**
   * Module where the entrypoint class is located, if applicable.
   */
  public val moduleName: String? = null,
  /**
   * Options which apply to this artifact only.
   */
  public val options: NativeImageOptions = NativeImageOptions(),
) : Artifact

/**
 * Flag for the Native Image compiler.
 */
public typealias NativeImageFlag = String

/**
 * Optimization levels.
 */
@Serializable
@SerialName("elide.nativeImage.OptimizationLevel")
public enum class OptimizationLevel {
  @SerialName("auto")
  OAuto,
  @SerialName("b")
  OB,
  @SerialName("s")
  OS,
  @SerialName("0")
  O0,
  @SerialName("1")
  O1,
  @SerialName("2")
  O2,
  @SerialName("3")
  O3,
  @SerialName("4")
  O4,
}

/**
 * Type of image produced by a native image task.
 */
@Serializable
@SerialName("elide.nativeImage.ImageType")
public enum class ImageType {
  @SerialName("binary")
  Binary,
  @SerialName("library")
  Library,
}

/**
 * Whether to invoke Native Image internally, or as a sub-process.
 */
@Serializable
@SerialName("elide.nativeImage.NativeImageDriverMode")
public enum class NativeImageDriverMode {
  @SerialName("embedded")
  Embedded,
  @SerialName("external")
  External,
}

@Serializable
@SerialName("elide.nativeImage.ClassInitialization")
public enum class ClassInitialization {
  @SerialName("buildtime")
  Buildtime,
  @SerialName("runtime")
  Runtime,
}
