@file:Suppress("RedundantVisibilityModifier", "Unused", "EnumEntryName")

package dev.elide.tooling.manifest.jvm

import dev.elide.tooling.manifest.artifacts.Artifact
import dev.elide.tooling.manifest.artifacts.ProjectArtifact
import dev.elide.tooling.manifest.base.PackageVersion
import dev.elide.tooling.manifest.java.JavaCompiler
import dev.elide.tooling.manifest.java.JavaNativeBinding
import dev.elide.tooling.manifest.sources.ResourceEntry
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
   * Which source sets to build this JAR from; empty packages every non-test source set.
   */
  public val sources: List<String> = emptyList(),
  /**
   * JARs of other workspace projects whose contents this JAR also packages, each built first;
   * written `project("name")`. Their manifests are dropped in favor of this JAR's own.
   */
  public val from: List<ProjectArtifact> = emptyList(),
  /**
   * Resources to add to the JAR on top of those its source sets ship; same shape as a source
   * set's own, including the in-place `new { from = …; into = … }` form.
   */
  public val resources: List<ResourceEntry> = emptyList(),
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
    if (this.from != other.from) return false
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
    result = 31 * result + this.from.hashCode()
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
   * Which source sets to package sources from; empty takes every non-test source set.
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
   * Which source sets to document; empty takes every non-test source set.
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
  /**
   * Tool that generates the documentation; `auto` picks Dokka when the sources include Kotlin.
   */
  public val generator: JavadocGenerator = JavadocGenerator.Auto,
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
    if (this.generator != other.generator) return false
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
    result = 31 * result + this.generator.hashCode()
    return result
  }
}

/**
 * Options which apply to a JVM application distribution.
 */
@Serializable
@SerialName("elide.jvm.JvmApplicationOptions")
public data class JvmApplicationOptions(
  /**
   * Whether to generate the POSIX shell start script.
   */
  public val unixStartScript: Boolean = true,
  /**
   * Whether to generate the Windows batch start script.
   */
  public val windowsStartScript: Boolean = true,
  /**
   * Archives to build from the installed distribution, alongside it.
   */
  public val archives: List<ApplicationArchive> = emptyList(),
)

/**
 * Describes a runnable, self-contained JVM application.
 *
 * The artifact installs a distribution tree — start scripts under [executableDir], every runtime
 * JAR under `lib/` — and, when [JvmApplicationOptions.archives] asks for them, `zip`/`tar.gz`
 * archives of that tree. This mirrors Gradle's `application` plugin: `installDist`, `startScripts`
 * and `distZip`/`distTar` in one artifact.
 */
@Serializable
@SerialName("elide.jvm.JvmApplication")
public open class JvmApplication(
  /**
   * Other artifacts this artifact depends on.
   */
  override val dependsOn: List<String> = emptyList(),
  /**
   * JAR artifacts making up the application's own code; empty packages this project's default JAR
   * artifacts.
   */
  public val from: List<String> = emptyList(),
  /**
   * Name of the application: the distribution directory and the start-script filenames. Defaults
   * to the artifact's name.
   */
  public val name: String? = null,
  /**
   * Main class the start scripts launch. Defaults to the project's `jvm.main`.
   */
  public val entrypoint: String? = null,
  /**
   * JVM options baked into the generated start scripts.
   */
  public val defaultJvmArgs: List<String> = emptyList(),
  /**
   * Directory within the distribution holding the start scripts.
   */
  public val executableDir: String = "bin",
  /**
   * Extra files copied into the distribution root; same shape as a source set's resources,
   * including the in-place `new { from = …; into = … }` form.
   */
  public val contents: List<ResourceEntry> = emptyList(),
  /**
   * Options for the distribution.
   */
  public val options: JvmApplicationOptions = JvmApplicationOptions(),
) : Artifact {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other == null || javaClass != other.javaClass) return false
    other as JvmApplication
    if (this.dependsOn != other.dependsOn) return false
    if (this.from != other.from) return false
    if (this.name != other.name) return false
    if (this.entrypoint != other.entrypoint) return false
    if (this.defaultJvmArgs != other.defaultJvmArgs) return false
    if (this.executableDir != other.executableDir) return false
    if (this.contents != other.contents) return false
    if (this.options != other.options) return false
    return true
  }

  override fun hashCode(): Int {
    var result = javaClass.name.hashCode()
    result = 31 * result + this.dependsOn.hashCode()
    result = 31 * result + this.from.hashCode()
    result = 31 * result + (this.name?.hashCode() ?: 0)
    result = 31 * result + (this.entrypoint?.hashCode() ?: 0)
    result = 31 * result + this.defaultJvmArgs.hashCode()
    result = 31 * result + this.executableDir.hashCode()
    result = 31 * result + this.contents.hashCode()
    result = 31 * result + this.options.hashCode()
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
  /**
   * NTLM domain; setting either NTLM field selects connection-bound NTLM authentication.
   */
  public val ntlmDomain: String? = null,
  /**
   * NTLM workstation name, when required by the repository.
   */
  public val ntlmWorkstation: String? = null,
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
   * Local files which stand in for Maven coordinates on every classpath that would carry them.
   *
   * The coordinate is resolved and downloaded as usual — its version, its place in the graph and
   * its transitive dependencies are unchanged — but the artifact handed to compiles, the runtime,
   * annotation processors and tests is the file named here. This is how a patched or stripped copy
   * of a published artifact is used without dropping the coordinate from the graph.
   *
   * A key of `group:artifact` matches every version; `group:artifact:version` matches one. Paths
   * are resolved against the directory of the manifest declaring them, and a workspace member sees
   * its workspace root's substitutions under its own. A file produced by the build needs the task
   * that writes it ordered ahead of whatever reads the classpath.
   */
  public val substitutions: Map<String, String> = emptyMap(),
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
 * Micronaut core modules, all versioned with Micronaut Core (not the platform BOM).
 */
@Serializable
@SerialName("elide.jvm.MicronautDependencies")
public data class MicronautDependencies(
  public val version: PackageVersion,
  public val modules: List<String> = emptyList(),
  public val testModules: List<String> = emptyList(),
  public val packages: List<String>,
  public val testPackages: List<String>,
  public val processors: List<String>,
)

/**
 * Optional application configuration, rendered by Pkl without a framework-specific key allowlist.
 */
@Serializable
@SerialName("elide.jvm.MicronautConfiguration")
public data class MicronautConfiguration(
  /**
   * Rendered classpath resource. Elide supplies the matching Micronaut Jackson Core loader.
   */
  public val applicationJson: String,
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
  /**
   * Provide GraalVM SDK APIs (`org.graalvm.nativeimage`, `word`, `collections`) at compile time.
   */
  public val graalvm: Boolean = false,
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
  /**
   * Java compiler settings for individual source sets, keyed by source set name; each layers over
   * the settings above for that source set alone.
   */
  public val sourceSets: Map<String, JavaSourceSetSettings> = emptyMap(),
  /**
   * Named C header groups; each is selected by one source set or explicitly by the CLI.
   */
  public val bindings: Map<String, JavaNativeBinding> = emptyMap(),
)

/**
 * Java compiler settings for one source set, layered over the project's `jvm.java` settings.
 */
@Serializable
@SerialName("elide.jvm.JavaSourceSetSettings")
public data class JavaSourceSetSettings(
  /**
   * The release version for this source set; `"auto"` keeps the project's `jvm.java.release`.
   */
  public val release: JvmTargetLevel = JvmTargetLevel.Auto,
  /**
   * Flags passed to the Java compiler after the project's `jvm.java.compiler.flags`.
   */
  public val flags: List<String> = emptyList(),
  /**
   * Named binding groups owned by this source set; dependents inherit their classes.
   */
  public val bindings: List<String> = emptyList(),
)

/**
 * Specifies settings which apply to JVM targets.
 */
@Serializable
@SerialName("elide.jvm.JvmSettings")
public data class JvmSettings(
  /**
   * Optional generated application.json; omit to supply configuration through resources.
   */
  public val micronaut: MicronautConfiguration? = null,
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
 * Which tool generates a [JavadocJar]'s documentation.
 *
 * - `auto`: Dokka when the documented sources include Kotlin, `javadoc` otherwise.
 * - `javadoc`: the JDK's `javadoc`, which documents only the Java sources.
 * - `dokka`: Dokka, which documents Kotlin and Java alike.
 */
@Serializable
@SerialName("elide.jvm.JavadocGenerator")
public enum class JavadocGenerator {
  @SerialName("auto")
  Auto,
  @SerialName("javadoc")
  Javadoc,
  @SerialName("dokka")
  Dokka,
}

/**
 * Archive formats a JVM application distribution can be packaged as.
 */
@Serializable
@SerialName("elide.jvm.ApplicationArchive")
public enum class ApplicationArchive {
  @SerialName("zip")
  Zip,
  @SerialName("tar")
  Tar,
}

/**
 * Name of an application distribution: a file name that also names a directory, so the two
 * relative path names are out — the build wipes and rewrites the directory it resolves to.
 */
public typealias ApplicationName = String

/**
 * A directory within a distribution: `/`-separated segments, none of them `.` or `..`, and never
 * absolute, so what it names stays inside the tree the archives carry.
 */
public typealias DistributionDirectory = String

/**
 * Maven packages can be specified structurally, as coordinate strings, as local JAR paths, or as
 * artifacts of another project of the same workspace.
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

  @Serializable(with = OfProjectArtifact.Companion::class)
  @JvmInline
  public value class OfProjectArtifact(
    public val `value`: ProjectArtifact,
  ) : MavenPackageDependency {
    public companion object : KSerializer<OfProjectArtifact> {
      override val descriptor: SerialDescriptor =
          buildClassSerialDescriptor("elide.jvm.MavenPackageDependency.OfProjectArtifact") {
        element<ProjectArtifact>("value",isOptional=false)
      }

      override fun serialize(encoder: Encoder, `value`: OfProjectArtifact) {
        encoder.encodeStructure(descriptor) {
          encodeSerializableElement(descriptor,0,serializer<ProjectArtifact>(),value.value)
        }
      }

      override fun deserialize(decoder: Decoder): OfProjectArtifact = decoder.decodeStructure(descriptor) {
        var value: ProjectArtifact? = null
        while (true) {
          when (val index = decodeElementIndex(descriptor)) {
            0 -> value = decodeSerializableElement(descriptor,0,serializer<ProjectArtifact>())
            CompositeDecoder.DECODE_DONE -> break
            else -> error("""Unexpected index: $index""")
          }
        }
        OfProjectArtifact(value!!)
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
