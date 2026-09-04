@file:Suppress("RedundantVisibilityModifier", "Unused", "EnumEntryName")

package dev.elide.tooling.manifest.jvm

import dev.elide.tooling.manifest.artifacts.Artifact
import dev.elide.tooling.manifest.base.PackageVersion
import dev.elide.tooling.manifest.java.JavaCompiler
import dev.elide.tooling.manifest.sources.SourceSetSpec
import dev.elide.tooling.manifest.sources.SourceSetType
import dev.elide.tooling.manifest.testing.CoverageReport
import kotlin.Any
import kotlin.Boolean
import kotlin.Double
import kotlin.Int
import kotlin.Long
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
import kotlinx.serialization.serializer

/**
 * Describes KJVM configurations for an Elide project.
 */
@Serializable
@SerialName("elide.jvm.Jvm")
public data object JvmModule {
  /**
   * Maven group which publishes Spring Boot starters.
   */
  public const val springBootGroup: String = "org.springframework.boot"

  /**
   * Artifact-name prefix shared by every Spring Boot starter.
   */
  public const val springBootStarterPrefix: String = "spring-boot-starter-"
}

/**
 * Describes a source set containing JVM (Java/Kotlin) sources.
 */
@Serializable(with = JvmSourceSetSpec.Companion::class)
public class JvmSourceSetSpec(
  type: SourceSetType = SourceSetType.Source,
  dependsOn: List<String> = emptyList(),
  paths: List<String> = emptyList(),
  /**
   * Resources to be included in JVM artifacts built from this source set.
   */
  public val resources: Map<String, String> = emptyMap(),
) : SourceSetSpec(type=type, dependsOn=dependsOn, paths=paths) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (!super.equals(other)) return false
    other as JvmSourceSetSpec
    if (this.resources != other.resources) return false
    return true
  }

  override fun hashCode(): Int {
    var result = super.hashCode()
    result = 31 * result + this.resources.hashCode()
    return result
  }

  public companion object : KSerializer<JvmSourceSetSpec> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("elide.jvm.JvmSourceSetSpec") {
      element<SourceSetType>("type",isOptional=true)
      element<List<String>>("dependsOn",isOptional=true)
      element<List<String>>("paths",isOptional=true)
      element<Map<String, String>>("resources",isOptional=true)
    }

    override fun serialize(encoder: Encoder, `value`: JvmSourceSetSpec) {
      encoder.encodeStructure(descriptor) {
        encodeSerializableElement(descriptor,0,serializer<SourceSetType>(),value.type)
        encodeSerializableElement(descriptor,1,serializer<List<String>>(),value.dependsOn)
        encodeSerializableElement(descriptor,2,serializer<List<String>>(),value.paths)
        encodeSerializableElement(descriptor,3,serializer<Map<String, String>>(),value.resources)
      }
    }

    override fun deserialize(decoder: Decoder): JvmSourceSetSpec = decoder.decodeStructure(descriptor) {
      var type: SourceSetType = SourceSetType.Source
      var dependsOn: List<String> = emptyList()
      var paths: List<String> = emptyList()
      var resources: Map<String, String> = emptyMap()
      while (true) {
        when (val index = decodeElementIndex(descriptor)) {
          0 -> type=decodeSerializableElement(descriptor,0,serializer<SourceSetType>())
          1 -> dependsOn=decodeSerializableElement(descriptor,1,serializer<List<String>>())
          2 -> paths=decodeSerializableElement(descriptor,2,serializer<List<String>>())
          3 -> resources=decodeSerializableElement(descriptor,3,serializer<Map<String, String>>())
          CompositeDecoder.DECODE_DONE -> break
          else -> error("""Unexpected index: $index""")
        }
      }
      JvmSourceSetSpec(type,dependsOn,paths,resources)
    }
  }
}

/**
 * Options which apply to JARs.
 */
@Serializable
@SerialName("elide.jvm.JarOptions")
public data class JarOptions(
  /**
   * Whether to apply compression.
   */
  public val compress: Boolean = true,
  /**
   * Whether to add default manifest properties.
   */
  public val defaultManifestProperties: Boolean = true,
  /**
   * Main entrypoint for the JAR, if applicable.
   */
  public val entrypoint: String? = null,
)

/**
 * Abstract definition of a JAR resource.
 */
public interface JarResource {
  /**
   * File path to be mounted as a resource within the JAR.
   */
  public val path: String

  @Serializable
  @SerialName("elide.jvm.JarResource.Impl")
  public data class Impl(
    /**
     * File path to be mounted as a resource within the JAR.
     */
    override val path: String,
  ) : JarResource
}

/**
 * Describes a JAR output artifact.
 */
@Serializable
@SerialName("elide.jvm.Jar")
public open class Jar(
  /**
   * Other artifacts this artifact depends on.
   */
  override val dependsOn: List<String> = emptyList(),
  /**
   * Filename for the resulting JAR.
   */
  public val name: String? = null,
  /**
   * Optional main class for this JAR, added to the manifest automatically
   */
  public val main: String? = null,
  /**
   * Which source set to build this JAR from.
   */
  public val sources: List<String> = emptyList(),
  /**
   * Which resources to add to the JAR.
   */
  public val resources: Map<String, String> = emptyMap(),
  /**
   * Keys and values to include in the JAR's manifest.
   */
  public val manifest: Map<String, String> = emptyMap(),
  /**
   * Manifest file path
   */
  public val manifestFile: String? = null,
  /**
   * Patterns to exclude
   */
  public val excludes: List<String> = emptyList(),
  /**
   * Options for the JAR.
   */
  public val options: JarOptions = JarOptions(),
) : Artifact {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other == null || javaClass != other.javaClass) return false
    other as Jar
    if (this.dependsOn != other.dependsOn) return false
    if (this.name != other.name) return false
    if (this.main != other.main) return false
    if (this.sources != other.sources) return false
    if (this.resources != other.resources) return false
    if (this.manifest != other.manifest) return false
    if (this.manifestFile != other.manifestFile) return false
    if (this.excludes != other.excludes) return false
    if (this.options != other.options) return false
    return true
  }

  override fun hashCode(): Int {
    var result = javaClass.name.hashCode()
    result = 31 * result + this.dependsOn.hashCode()
    result = 31 * result + (this.name?.hashCode() ?: 0)
    result = 31 * result + (this.main?.hashCode() ?: 0)
    result = 31 * result + this.sources.hashCode()
    result = 31 * result + this.resources.hashCode()
    result = 31 * result + this.manifest.hashCode()
    result = 31 * result + (this.manifestFile?.hashCode() ?: 0)
    result = 31 * result + this.excludes.hashCode()
    result = 31 * result + this.options.hashCode()
    return result
  }
}

/**
 * Describes a sources JAR artifact that packages source files.
 */
@Serializable
@SerialName("elide.jvm.SourceJar")
public open class SourceJar(
  /**
   * Other artifacts this artifact depends on.
   */
  override val dependsOn: List<String> = emptyList(),
  /**
   * Which source set to build this JAR from.
   */
  public val sources: List<String> = emptyList(),
  /**
   * Classifier for the JAR (e.g., "sources" or "no-tzdb-sources").
   */
  public val classifier: String? = null,
  /**
   * Patterns to exclude from the sources JAR.
   */
  public val excludes: List<String> = emptyList(),
  /**
   * Patterns to include in the sources JAR.
   */
  public val includes: List<String> = emptyList(),
) : Artifact {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other == null || javaClass != other.javaClass) return false
    other as SourceJar
    if (this.dependsOn != other.dependsOn) return false
    if (this.sources != other.sources) return false
    if (this.classifier != other.classifier) return false
    if (this.excludes != other.excludes) return false
    if (this.includes != other.includes) return false
    return true
  }

  override fun hashCode(): Int {
    var result = javaClass.name.hashCode()
    result = 31 * result + this.dependsOn.hashCode()
    result = 31 * result + this.sources.hashCode()
    result = 31 * result + (this.classifier?.hashCode() ?: 0)
    result = 31 * result + this.excludes.hashCode()
    result = 31 * result + this.includes.hashCode()
    return result
  }
}

/**
 * Describes a Javadoc JAR artifact that generates and packages documentation.
 */
@Serializable
@SerialName("elide.jvm.JavadocJar")
public open class JavadocJar(
  /**
   * Other artifacts this artifact depends on.
   */
  override val dependsOn: List<String> = emptyList(),
  /**
   * Which source set to build this JAR from.
   */
  public val sources: List<String> = emptyList(),
  /**
   * Package groupings for the Javadoc (title -> packages).
   */
  public val groups: Map<String, List<String>> = emptyMap(),
  /**
   * External documentation URLs to link.
   */
  public val links: List<String> = emptyList(),
  /**
   * Patterns to exclude from documentation.
   */
  public val excludes: List<String> = emptyList(),
  /**
   * Title for the browser window.
   */
  public val windowTitle: String? = null,
  /**
   * Title for the documentation.
   */
  public val docTitle: String? = null,
) : Artifact {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other == null || javaClass != other.javaClass) return false
    other as JavadocJar
    if (this.dependsOn != other.dependsOn) return false
    if (this.sources != other.sources) return false
    if (this.groups != other.groups) return false
    if (this.links != other.links) return false
    if (this.excludes != other.excludes) return false
    if (this.windowTitle != other.windowTitle) return false
    if (this.docTitle != other.docTitle) return false
    return true
  }

  override fun hashCode(): Int {
    var result = javaClass.name.hashCode()
    result = 31 * result + this.dependsOn.hashCode()
    result = 31 * result + this.sources.hashCode()
    result = 31 * result + this.groups.hashCode()
    result = 31 * result + this.links.hashCode()
    result = 31 * result + this.excludes.hashCode()
    result = 31 * result + (this.windowTitle?.hashCode() ?: 0)
    result = 31 * result + (this.docTitle?.hashCode() ?: 0)
    return result
  }
}

/**
 * Base coordinate specification for a Maven package.
 */
@Serializable
@SerialName("elide.jvm.MavenCoordinateSpec")
public open class MavenCoordinateSpec(
  /**
   * Group for the coordinate.
   */
  public val group: String? = null,
  /**
   * Name for the coordinate.
   */
  public val name: String? = null,
  /**
   * Classifier for this Maven coordinate.
   */
  public val classifier: String? = null,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other == null || javaClass != other.javaClass) return false
    other as MavenCoordinateSpec
    if (this.group != other.group) return false
    if (this.name != other.name) return false
    if (this.classifier != other.classifier) return false
    return true
  }

  override fun hashCode(): Int {
    var result = javaClass.name.hashCode()
    result = 31 * result + (this.group?.hashCode() ?: 0)
    result = 31 * result + (this.name?.hashCode() ?: 0)
    result = 31 * result + (this.classifier?.hashCode() ?: 0)
    return result
  }
}

/**
 * Describes a Maven package dependency.
 */
@Serializable(with = MavenPackageSpec.Companion::class)
public class MavenPackageSpec(
  group: String? = null,
  name: String? = null,
  classifier: String? = null,
  /**
   * Version or symbolic version for the package.
   */
  public val version: PackageVersion? = null,
  /**
   * Full Maven coordinate for the package; if specified, this is preferred to the group and name.
   */
  public val coordinate: String? = null,
) : MavenCoordinateSpec(group=group, name=name, classifier=classifier),
    MavenPackageDependency {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (!super.equals(other)) return false
    other as MavenPackageSpec
    if (this.version != other.version) return false
    if (this.coordinate != other.coordinate) return false
    return true
  }

  override fun hashCode(): Int {
    var result = super.hashCode()
    result = 31 * result + (this.version?.hashCode() ?: 0)
    result = 31 * result + (this.coordinate?.hashCode() ?: 0)
    return result
  }

  public companion object : KSerializer<MavenPackageSpec> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("elide.jvm.MavenPackageSpec") {
      element<String?>("group",isOptional=true)
      element<String?>("name",isOptional=true)
      element<String?>("classifier",isOptional=true)
      element<PackageVersion?>("version",isOptional=true)
      element<String?>("coordinate",isOptional=true)
    }

    override fun serialize(encoder: Encoder, `value`: MavenPackageSpec) {
      encoder.encodeStructure(descriptor) {
        encodeSerializableElement(descriptor,0,serializer<String?>(),value.group)
        encodeSerializableElement(descriptor,1,serializer<String?>(),value.name)
        encodeSerializableElement(descriptor,2,serializer<String?>(),value.classifier)
        encodeSerializableElement(descriptor,3,serializer<PackageVersion?>(),value.version)
        encodeSerializableElement(descriptor,4,serializer<String?>(),value.coordinate)
      }
    }

    override fun deserialize(decoder: Decoder): MavenPackageSpec = decoder.decodeStructure(descriptor) {
      var group: String? = null
      var name: String? = null
      var classifier: String? = null
      var version: PackageVersion? = null
      var coordinate: String? = null
      while (true) {
        when (val index = decodeElementIndex(descriptor)) {
          0 -> group=decodeSerializableElement(descriptor,0,serializer<String?>())
          1 -> name=decodeSerializableElement(descriptor,1,serializer<String?>())
          2 -> classifier=decodeSerializableElement(descriptor,2,serializer<String?>())
          3 -> version=decodeSerializableElement(descriptor,3,serializer<PackageVersion?>())
          4 -> coordinate=decodeSerializableElement(descriptor,4,serializer<String?>())
          CompositeDecoder.DECODE_DONE -> break
          else -> error("""Unexpected index: $index""")
        }
      }
      MavenPackageSpec(group,name,classifier,version,coordinate)
    }
  }
}

/**
 * Provides authentication settings for a remote Maven repository.
 */
@Serializable
@SerialName("elide.jvm.MavenRepositoryCredentials")
public data class MavenRepositoryCredentials(
  /**
   * Username used for authentication with the remote repository.
   */
  public val username: String? = null,
  /**
   * Password used for authentication with the remote repository.
   */
  public val password: String? = null,
)

/**
 * Describes a Maven repository to include when resolving dependencies.
 */
@Serializable
@SerialName("elide.jvm.MavenRepositorySpec")
public data class MavenRepositorySpec(
  /**
   * Optional descriptive name for this repository.
   */
  public val name: String? = null,
  /**
   * Optional description for this repository.
   */
  public val description: String? = null,
  /**
   * Optional authentication material for this repository.
   */
  public val credentials: MavenRepositoryCredentials = MavenRepositoryCredentials(),
  /**
   * URL where this repository can be accessed.
   */
  public val url: String,
) : MavenRepository

/**
 * Coordinates for this project as a library.
 */
@Serializable(with = MavenLibraryCoordinate.Companion::class)
public class MavenLibraryCoordinate(
  group: String? = null,
  name: String? = null,
  classifier: String? = null,
) : MavenCoordinateSpec(group=group, name=name, classifier=classifier) {
  public companion object : KSerializer<MavenLibraryCoordinate> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("elide.jvm.MavenLibraryCoordinate") {
      element<String?>("group",isOptional=true)
      element<String?>("name",isOptional=true)
      element<String?>("classifier",isOptional=true)
    }

    override fun serialize(encoder: Encoder, `value`: MavenLibraryCoordinate) {
      encoder.encodeStructure(descriptor) {
        encodeSerializableElement(descriptor,0,serializer<String?>(),value.group)
        encodeSerializableElement(descriptor,1,serializer<String?>(),value.name)
        encodeSerializableElement(descriptor,2,serializer<String?>(),value.classifier)
      }
    }

    override fun deserialize(decoder: Decoder): MavenLibraryCoordinate = decoder.decodeStructure(descriptor) {
      var group: String? = null
      var name: String? = null
      var classifier: String? = null
      while (true) {
        when (val index = decodeElementIndex(descriptor)) {
          0 -> group=decodeSerializableElement(descriptor,0,serializer<String?>())
          1 -> name=decodeSerializableElement(descriptor,1,serializer<String?>())
          2 -> classifier=decodeSerializableElement(descriptor,2,serializer<String?>())
          CompositeDecoder.DECODE_DONE -> break
          else -> error("""Unexpected index: $index""")
        }
      }
      MavenLibraryCoordinate(group,name,classifier)
    }
  }
}

/**
 * Configuration for Maven dependency resolution.
 */
@Serializable
@SerialName("elide.jvm.MavenDependencies")
public data class MavenDependencies(
  /**
   * A list of Maven package dependencies to be resolved for this project.
   */
  public val packages: List<MavenPackageDependency> = emptyList(),
  /**
   * A list of Maven packages to make available at dev-time only.
   */
  public val devPackages: List<MavenPackageDependency> = emptyList(),
  /**
   * A list of dependencies to include specifically on the module path.
   */
  public val modules: List<MavenPackageDependency> = emptyList(),
  /**
   * A list of Maven packages to be resolved as compile-time only dependencies.
   */
  public val compileOnly: List<MavenPackageDependency> = emptyList(),
  /**
   * A list of Maven package dependencies to be resolved as runtime dependencies.
   */
  public val runtimeOnly: List<MavenPackageDependency> = emptyList(),
  /**
   * A list of Maven package dependencies to be resolved as processor dependencies.
   */
  public val processors: List<MavenPackageDependency> = emptyList(),
  /**
   * A list of Maven package dependencies to be resolved for this project.
   */
  public val testPackages: List<MavenPackageDependency> = emptyList(),
  /**
   * A list of Maven package dependencies to be resolved as Kotlin compiler plugins.
   */
  public val kotlinPlugins: List<MavenPackageDependency> = emptyList(),
  /**
   * A list of Maven package dependencies to be excluded from resolution and classpath calculations.
   */
  public val exclusions: List<MavenPackageDependency> = emptyList(),
  /**
   * A suite of extra Maven repositories.
   */
  public val repositories: Map<String, MavenRepository> = emptyMap(),
  /**
   * Whether to enable default repositories like Maven Central. Defaults to `true`.
   */
  public val enableDefaultRepositories: Boolean = true,
  /**
   * Path to local maven repository
   */
  public val localRepository: String? = null,
)

/**
 * Shorthand for Spring Boot starter dependencies; expands into [MavenDependencies].
 *
 * Starters follow the fixed convention `org.springframework.boot:spring-boot-starter-<name>`, so
 * only the simple name is declared here. Artifacts which do not follow it — `spring-boot-devtools`,
 * or anything in the `org.springframework` group — are declared in `maven` as ordinary coordinates;
 * the two merge.
 */
@Serializable
@SerialName("elide.jvm.SpringDependencies")
public data class SpringDependencies(
  /**
   * Spring Boot version. Starters are versioned by Spring Boot, not by Spring Framework.
   */
  public val version: PackageVersion,
  /**
   * Starters to resolve into the compile scope.
   */
  public val starter: List<String> = emptyList(),
  /**
   * Starters to resolve into the test scope.
   */
  public val testStarter: List<String> = emptyList(),
  /**
   * Expanded compile-scope coordinates; consumed by the manifest codec.
   */
  public val packages: List<String>,
  /**
   * Expanded test-scope coordinates; consumed by the manifest codec.
   */
  public val testPackages: List<String>,
)

/**
 * Controls features related to JVM support in Elide projects.
 */
@Serializable
@SerialName("elide.jvm.JvmFeatures")
public data class JvmFeatures(
  /**
   * Automatically provide test dependencies for JVM projects, like JUnit.
   */
  public val testing: Boolean = true,
  /**
   * Whether auto-modules are enabled (JDK 9+ modulepath handling).
   */
  public val automodules: Boolean = true,
)

/**
 * Controls settings relating to the Java language.
 */
@Serializable
@SerialName("elide.jvm.JavaLanguage")
public data class JavaLanguage(
  /**
   * The source version to use.
   */
  public val source: JvmTargetLevel = JvmTargetLevel.Auto,
  /**
   * The release version to use.
   */
  public val release: JvmTargetLevel = JvmTargetLevel.Auto,
  /**
   * Configuration for the Java compiler.
   */
  public val compiler: JavaCompiler = JavaCompiler(),
)

/**
 * Specifies settings which apply to JVM targets.
 */
@Serializable
@SerialName("elide.jvm.JvmSettings")
public data class JvmSettings(
  /**
   * Entrypoint class for this project as an application.
   */
  public val main: String? = null,
  /**
   * Set the JVM bytecode target level for this project.
   */
  public val target: JvmTargetLevel = JvmTargetLevel.Auto,
  /**
   * Set a custom Java Home override for this project.
   */
  public val javaHome: String? = null,
  /**
   * Prefer Bali from PATH for external JVM invocations.
   */
  public val preferBali: Boolean = false,
  /**
   * Controls features and settings related to JVM support.
   */
  public val features: JvmFeatures = JvmFeatures(),
  /**
   * Java language settings.
   */
  public val java: JavaLanguage = JavaLanguage(),
  /**
   * Runtime JVM flags.
   */
  public val flags: List<String> = emptyList(),
  /**
   * Runtime definitions (system properties).
   */
  public val defs: Map<String, String> = emptyMap(),
  /**
   * Controls whether debug info (`-g`) is emitted during compilation. Defaults to true.
   */
  public val debug: Boolean = true,
)

public interface JvmCoverageReport : CoverageReport

/**
 * Coverage report in HTML format.
 */
@Serializable
@SerialName("elide.jvm.HtmlCoverageReport")
public data class HtmlCoverageReport(
  /**
   * Name for this report; typically the format name by default.
   */
  override val name: String,
) : JvmCoverageReport

/**
 * Coverage report in XML format.
 */
@Serializable
@SerialName("elide.jvm.XmlCoverageReport")
public data class XmlCoverageReport(
  /**
   * Name for this report; typically the format name by default.
   */
  override val name: String,
) : JvmCoverageReport

/**
 * Coverage report in CSV format.
 */
@Serializable
@SerialName("elide.jvm.CsvCoverageReport")
public data class CsvCoverageReport(
  /**
   * Name for this report; typically the format name by default.
   */
  override val name: String,
) : JvmCoverageReport

/**
 * Structured settings for configuring test coverage.
 */
@Serializable
@SerialName("elide.jvm.JvmCoverageSettings")
public data class JvmCoverageSettings(
  /**
   * Whether coverage is active. Off by default: enable it here, or pass `--coverage`.
   */
  public val enabled: Boolean = false,
  /**
   * Coverage reports to be generated.
   */
  public val reports: List<JvmCoverageReport> = emptyList(),
)

/**
 * JVM target levels as integers.
 */
@Serializable
@SerialName("elide.jvm.JvmTargetLevel")
public sealed interface JvmTargetLevel {
  @Serializable
  @SerialName("elide.jvm.JvmTargetLevel.Latest")
  public data object Latest : JvmTargetLevel

  @Serializable
  @SerialName("elide.jvm.JvmTargetLevel.Stable")
  public data object Stable : JvmTargetLevel

  @Serializable
  @SerialName("elide.jvm.JvmTargetLevel.Auto")
  public data object Auto : JvmTargetLevel

  @Serializable(with = OfFloat.Companion::class)
  @JvmInline
  public value class OfFloat(
    public val `value`: Double,
  ) : JvmTargetLevel {
    public companion object : KSerializer<OfFloat> {
      override val descriptor: SerialDescriptor =
          buildClassSerialDescriptor("elide.jvm.JvmTargetLevel.OfFloat") {
        element<Double>("value",isOptional=false)
      }

      override fun serialize(encoder: Encoder, `value`: OfFloat) {
        encoder.encodeStructure(descriptor) {
          encodeDoubleElement(descriptor,0,value.value)
        }
      }

      override fun deserialize(decoder: Decoder): OfFloat = decoder.decodeStructure(descriptor) {
        var value: Double? = null
        while (true) {
          when (val index = decodeElementIndex(descriptor)) {
            0 -> value = decodeDoubleElement(descriptor,0)
            CompositeDecoder.DECODE_DONE -> break
            else -> error("""Unexpected index: $index""")
          }
        }
        OfFloat(value!!)
      }
    }
  }

  @Serializable(with = OfInt.Companion::class)
  @JvmInline
  public value class OfInt(
    public val `value`: Long,
  ) : JvmTargetLevel {
    public companion object : KSerializer<OfInt> {
      override val descriptor: SerialDescriptor =
          buildClassSerialDescriptor("elide.jvm.JvmTargetLevel.OfInt") {
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
}

/**
 * JVM target levels as enumeration.
 */
public typealias JvmTarget = JvmTargetLevel

/**
 * Maven group string.
 */
public typealias MavenGroup = String

/**
 * Maven module string.
 */
public typealias MavenModule = String

/**
 * Matches against Maven coordinate strings.
 *
 * Accepts `group:artifact`, `group:artifact:version`, the Gradle 4-part
 * `group:artifact:version:classifier`, and the Aether 5-part
 * `group:artifact:extension:classifier:version` (2–5 colon-separated segments).
 */
public typealias MavenCoordinate = String

/**
 * Simple name of a Spring Boot starter — the segment following `spring-boot-starter-`.
 */
public typealias SpringStarterName = String

/**
 * Known standard JAR manifest keys.
 */
@Serializable
@SerialName("elide.jvm.StandardManifestKey")
public enum class StandardManifestKey {
  @SerialName("Implementation-Title")
  Implementation_Title,
  @SerialName("Implementation-Version")
  Implementation_Version,
  @SerialName("Implementation-Vendor")
  Implementation_Vendor,
  @SerialName("Implementation-URL")
  Implementation_URL,
  @SerialName("Implementation-Description")
  Implementation_Description,
  @SerialName("Implementation-Build-Id")
  Implementation_Build_Id,
  @SerialName("Implementation-Build-Time")
  Implementation_Build_Time,
  @SerialName("Implementation-Build-Host")
  Implementation_Build_Host,
  @SerialName("Implementation-Build-User")
  Implementation_Build_User,
  @SerialName("Class-Path")
  Class_Path,
  @SerialName("Main-Class")
  Main_Class,
  @SerialName("Bundle-SymbolicName")
  Bundle_SymbolicName,
  @SerialName("Bundle-Version")
  Bundle_Version,
}

/**
 * Key within a JAR manifest.
 */
public typealias JarManifestKey = String

/**
 * Value within a JAR manifest.
 */
public typealias JarManifestValue = String

/**
 * Class name for a JVM class, which is a string.
 */
public typealias JvmClass = String

/**
 * Maven packages can be specified structurally, or as coordinate strings, Gradle-style coordinate strings, or purls.
 */
@Serializable
@SerialName("elide.jvm.MavenPackageDependency")
public sealed interface MavenPackageDependency {
  @Serializable(with = OfString.Companion::class)
  @JvmInline
  public value class OfString(
    public val `value`: String,
  ) : MavenPackageDependency {
    public companion object : KSerializer<OfString> {
      override val descriptor: SerialDescriptor =
          buildClassSerialDescriptor("elide.jvm.MavenPackageDependency.OfString") {
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
 * Specifies a simple Maven repository endpoint or structural definition.
 */
@Serializable
@SerialName("elide.jvm.MavenRepository")
public sealed interface MavenRepository {
  @Serializable(with = OfString.Companion::class)
  @JvmInline
  public value class OfString(
    public val `value`: String,
  ) : MavenRepository {
    public companion object : KSerializer<OfString> {
      override val descriptor: SerialDescriptor =
          buildClassSerialDescriptor("elide.jvm.MavenRepository.OfString") {
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
