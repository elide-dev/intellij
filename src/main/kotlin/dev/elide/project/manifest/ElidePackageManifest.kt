/*
 * Copyright (c) 2024-2025 Elide Technologies, Inc.
 *
 * Licensed under the MIT license (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *   https://opensource.org/license/mit/
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under the License.
 */
@file:Suppress("unused")

package dev.elide.project.manifest

import dev.elide.api.Symbolic
import dev.elide.project.manifest.ElidePackageManifest.DependencyResolution
import dev.elide.project.manifest.ElidePackageManifest.MavenDependencies
import dev.elide.tooling.web.Browsers
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json

// Default Java target version for JVM projects
private const val DEFAULT_JAVA_TARGET = 21u

// Optimization level strings.
private const val OPTIMIZATION_LEVEL_AUTO = "auto"
private const val OPTIMIZATION_LEVEL_BUILD = "b"
private const val OPTIMIZATION_LEVEL_SIZE = "s"
private const val OPTIMIZATION_LEVEL_ZERO = "0"
private const val OPTIMIZATION_LEVEL_ONE = "1"
private const val OPTIMIZATION_LEVEL_TWO = "2"
private const val OPTIMIZATION_LEVEL_THREE = "3"
private const val OPTIMIZATION_LEVEL_FOUR = "4"

@Serializable
data class ElidePackageManifest(
  val name: String? = null,
  val version: String? = null,
  val description: String? = null,
  val entrypoint: List<String>? = null,
  val scripts: Map<String, String> = emptyMap(),
  val artifacts: Map<String, Artifact> = emptyMap(),
  val dependencies: DependencyResolution = DependencyResolution(),
  val javascript: JavaScriptSettings? = null,
  val jvm: JvmSettings? = null,
  val kotlin: KotlinSettings? = null,
  val nativeImage: NativeImageSettings? = null,
  val dev: DevSettings? = null,
  val toolchain: ToolchainSettings? = null,
  val sources: Map<String, SourceSet> = emptyMap(),
  val web: WebSettings? = null,
  val engine: RuntimeEngineSettings? = null,
  val testing: TestingSettings? = null,
) {
  companion object {
    private var ManifestJson = Json { ignoreUnknownKeys = true }

    @JvmStatic fun parse(input: String): ElidePackageManifest {
      return runCatching { ManifestJson.decodeFromString<ElidePackageManifest>(input) }
        .getOrElse { throw IllegalStateException("Failed to parse Elide project manifest: ${it.message}", it) }
    }
  }

  @Transient
  private val workspace: AtomicReference<Pair<Path, ElidePackageManifest>> = AtomicReference(null)

  fun within(path: Path, workspace: ElidePackageManifest): ElidePackageManifest = apply {
    this.workspace.set(path to workspace)
  }

  fun activeWorkspace(): Pair<Path, ElidePackageManifest>? {
    return this.workspace.get()
  }

  @Serializable
  sealed interface Artifact {
    val dependsOn: List<String>
  }

  @Serializable data class JarResource(val path: String)

  @Serializable
  data class ProjectSourceSpec(
    val platform: String? = null,
    val project: String? = null,
    val subpath: String? = null,
  )

  @Serializable
  data class McpResource(
    val path: String,
    val name: String,
    val description: String = "",
    val mimeType: String? = null,
  )

  @Serializable
  data class McpSettings(
    val resources: List<McpResource>? = null,
    val advice: Boolean = true,
    val registerElide: Boolean = true,
  )

  @Serializable data class DevServerSettings(val host: String = "0.0.0.0", val port: Int = 8080)

  @Serializable
  data class DevSettings(
    val source: ProjectSourceSpec? = null,
    val mcp: McpSettings? = null,
    val server: DevServerSettings? = null,
  )

  @Serializable data class EngineSettings(val version: String? = null)

  @Serializable data class ToolchainSettings(val engines: Map<String, EngineSettings>? = null)

  @Serializable
  data class Jar(
    val name: String? = null,
    val main: String? = null,
    val sources: List<String> = emptyList(),
    val resources: Map<String, JarResource> = emptyMap(),
    val manifest: Map<String, String> = emptyMap(),
    val manifestFile: String? = null,
    val excludes: List<String> = emptyList(),
    val options: JarOptions = JarOptions(),
    override val dependsOn: List<String> = emptyList(),
  ) : Artifact

  @Serializable
  data class JarOptions(
    val compress: Boolean = true,
    val defaultManifestProperties: Boolean = true,
    val entrypoint: String? = null,
  )

  /**
   * Output mode for container images.
   * - `DAEMON`: Publish to the local Docker daemon.
   * - `REGISTRY`: Publish directly to the registry specified in the image coordinates.
   * - `TARBALL`: Write to a local tarball that can be imported manually into Docker.
   */
  @Serializable
  enum class ContainerOutputMode(override val symbol: String) : Symbolic<String> {
    DAEMON("daemon"),
    REGISTRY("registry"),
    TARBALL("tarball");

    companion object : Symbolic.SealedResolver<String, ContainerOutputMode> {
      override fun resolve(symbol: String): ContainerOutputMode =
        when (symbol.lowercase().trim()) {
          "daemon" -> DAEMON
          "registry" -> REGISTRY
          "tarball" -> TARBALL
          else -> throw unresolved(symbol)
        }
    }
  }

  /**
   * Container image format.
   * - `OCI`: Open Container Initiative (OCI) image format.
   * - `DOCKER`: Docker image format (Docker Image Manifest V2).
   */
  @Serializable
  enum class ContainerFormat(override val symbol: String) : Symbolic<String> {
    OCI("oci"),
    DOCKER("docker");

    companion object : Symbolic.SealedResolver<String, ContainerFormat> {
      override fun resolve(symbol: String): ContainerFormat =
        when (symbol.lowercase().trim()) {
          "oci" -> OCI
          "docker" -> DOCKER
          else -> throw unresolved(symbol)
        }
    }
  }

  @Serializable
  data class ContainerImage(
    val image: String? = null,
    val base: String? = null,
    val tags: List<String> = emptyList(),
    val output: ContainerOutputMode = ContainerOutputMode.DAEMON,
    val format: ContainerFormat = ContainerFormat.DOCKER,
    val from: List<String> = emptyList(),
    override val dependsOn: List<String> = emptyList(),
  ) : Artifact

  /** Javadoc JAR artifact - generates and packages Javadoc documentation */
  @Serializable
  data class JavadocJar(
    val groups: Map<String, List<String>> = emptyMap(), // title -> packages
    val links: List<String> = emptyList(), // external doc links
    val excludes: List<String> = emptyList(),
    val windowTitle: String? = null,
    val docTitle: String? = null,
    val sources: List<String> = emptyList(),
    override val dependsOn: List<String> = emptyList(),
  ) : Artifact

  /** Source JAR artifact - packages source files */
  @Serializable
  data class SourceJar(
    val classifier: String? = null, // e.g., "sources" or "no-tzdb-sources"
    val excludes: List<String> = emptyList(),
    val includes: List<String> = emptyList(),
    val sources: List<String> = emptyList(),
    override val dependsOn: List<String> = emptyList(),
  ) : Artifact

  /** Assembly archive artifact - creates distribution archives (tar.gz, zip) */
  @Serializable
  data class Assembly(
    val id: String,
    val formats: List<String> = listOf("tar.gz", "zip"),
    val baseDirectory: String? = null,
    val fileSets: List<AssemblyFileSet> = emptyList(),
    val descriptorPath: String? = null, // path to assembly descriptor XML
    val from: List<String> = emptyList(),
    override val dependsOn: List<String> = emptyList(),
  ) : Artifact

  /** File set within an assembly descriptor */
  @Serializable
  data class AssemblyFileSet(
    val directory: String? = null,
    val outputDirectory: String? = null,
    val includes: List<String> = emptyList(),
    val excludes: List<String> = emptyList(),
  )

  @Serializable
  sealed interface SourceSet {
    enum class SourceSetType {
      Source,
      Test,
      Example,
      Other;

      companion object {
        fun parse(spec: String): SourceSetType =
          when (spec) {
            "source" -> Source
            "test" -> Test
            "example" -> Example
            else -> Other
          }
      }
    }

    @SerialName("kind") val type: SourceSetType

    val dependsOn: List<String>

    val paths: List<String>
  }

  @Serializable
  data class DefaultSourceSet(
    @SerialName("kind") override val type: SourceSet.SourceSetType = SourceSet.SourceSetType.Source,
    override val paths: List<String> = emptyList(),
    override val dependsOn: List<String> = emptyList(),
  ) : SourceSet

  @Serializable
  data class JvmSourceSet(
    @SerialName("kind") override val type: SourceSet.SourceSetType = SourceSet.SourceSetType.Source,
    override val paths: List<String> = emptyList(),
    override val dependsOn: List<String> = emptyList(),
    val resources: Map<String, String> = emptyMap(),
  ) : SourceSet

  @Serializable
  data class WebSettings(
    val css: CssSettings = CssSettings(),
    val browsers: Browsers = Browsers.Defaults,
  )

  @Serializable data class CssTarget(val browser: String, val version: String? = null)

  @Serializable
  data class CssSettings(val minify: Boolean = true, val targets: List<CssTarget> = emptyList())

  sealed interface DependencyEcosystemConfig {
    sealed interface PackageSpec

    sealed interface RepositorySpec
  }

  @JvmRecord
  @Serializable
  data class NpmDependencies(
    val packages: List<NpmPackage> = emptyList(),
    val devPackages: List<NpmPackage> = emptyList(),
    val repositories: Map<String, NpmRepository> = emptyMap(),
    val from: List<String> = emptyList(),
  ) : DependencyEcosystemConfig {
    fun hasPackages(): Boolean = packages.isNotEmpty() || devPackages.isNotEmpty()
  }

  @JvmRecord
  @Serializable
  data class NpmPackage(val name: String, val version: String?) :
    DependencyEcosystemConfig.PackageSpec {
    companion object {
      @JvmStatic
      fun parse(str: String): NpmPackage {
        val version = str.substringAfterLast('@')
        val name = str.substringBeforeLast('@')
        return NpmPackage(name = name, version = version.ifEmpty { null })
      }
    }
  }

  @JvmRecord
  @Serializable
  data class NpmRepository(val name: String, val url: String) :
    DependencyEcosystemConfig.RepositorySpec

  @JvmRecord
  @Serializable
  data class GradleCatalog(val name: String? = null, val path: String) : Comparable<GradleCatalog> {
    companion object {
      @JvmStatic
      fun parse(str: String): GradleCatalog =
        GradleCatalog(name = str.substringBefore("."), path = str)
    }

    override fun compareTo(other: GradleCatalog): Int = path.compareTo(other.path)
  }

  @JvmRecord
  @Serializable
  data class MavenPackage(
    val group: String = "",
    val name: String = "",
    val version: String? = "",
    val classifier: String? = "",
    val coordinate: String? = null,
  ) : DependencyEcosystemConfig.PackageSpec, Comparable<MavenPackage> {
    fun spec(): String {
      return coordinate.takeUnless { it.isNullOrBlank() } ?: buildSpec()
    }

    private fun buildSpec(): String = buildString {
      append(group)
      append(':')
      append(name)
      if (!version.isNullOrBlank()) {
        append(':')
        append(version)
      }
      if (!classifier.isNullOrBlank()) {
        append(':')
        append(classifier)
      }
    }

    init {
      if (coordinate.isNullOrBlank()) {
        require(group.isNotBlank() && name.isNotBlank() && !version.isNullOrBlank()) {
          "Maven dependency without explicit coordinates must specify group, name, and version"
        }
      }
    }

    companion object {
      @JvmStatic
      fun parse(str: String): MavenPackage {
        return when (str.count { it == ':' }) {
          0 -> error("Maven package missing group or artifact: '$str'")
          1 ->
            MavenPackage(
              group = str.substringBefore(':'),
              name = str.substringAfter(':'),
              coordinate = str,
            )

          2 ->
            MavenPackage(
              group = str.substringBefore(':'),
              name = str.substringAfter(':').substringBefore(':'),
              version = str.substringAfterLast(':'),
              coordinate = str,
            )

          3 ->
            str.split(':').let { split ->
              MavenPackage(
                group = split.first(),
                name = split[1],
                classifier = split[2],
                version = str.substringAfterLast(':'),
                coordinate = str,
              )
            }

          else -> error("Too many separators in Maven coordinate: '$str'")
        }
      }
    }

    override fun compareTo(other: MavenPackage): Int = spec().compareTo(other.spec())

    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (javaClass != other?.javaClass) return false

      other as MavenPackage

      if (group != other.group) return false
      if (name != other.name) return false
      if (version != other.version) return false
      if (classifier != other.classifier) return false
      if (coordinate != other.coordinate) return false

      return true
    }

    override fun hashCode(): Int {
      var result = group.ifBlank { null }?.hashCode() ?: 0
      result = 31 * result + (name.ifBlank { null }?.hashCode() ?: 0)
      result = 31 * result + (version?.ifBlank { null }?.hashCode() ?: 0)
      result = 31 * result + (classifier?.ifBlank { null }?.hashCode() ?: 0)
      result = 31 * result + coordinate.hashCode()
      return result
    }
  }

  @Serializable
  data class MavenRepository(
    val url: String,
    var name: String? = null,
    val description: String? = null,
    val credentials: MavenRepositoryCredentials = MavenRepositoryCredentials(),
  ) : DependencyEcosystemConfig.RepositorySpec {
    @Serializable
    data class MavenRepositoryCredentials(
      val username: String? = null,
      val password: String? = null,
    )

    companion object {
      @JvmStatic
      fun parse(str: String): MavenRepository {
        return try {
            URI.create(str)
          } catch (_: IllegalArgumentException) {
            error("Invalid URI for Maven repository: '$str'")
          }
          .let { MavenRepository(url = str) }
      }
    }
  }

  @JvmRecord
  @Serializable
  data class MavenCoordinates(val group: String, val name: String, val classifier: String? = null)

  @JvmRecord
  @Serializable
  data class MavenDependencies(
    val packages: List<MavenPackage> = emptyList(),
    val modules: List<MavenPackage> = emptyList(),
    val devPackages: List<MavenPackage> = emptyList(),
    val testPackages: List<MavenPackage> = emptyList(),
    val compileOnly: List<MavenPackage> = emptyList(),
    val runtimeOnly: List<MavenPackage> = emptyList(),
    val processors: List<MavenPackage> = emptyList(),
    val kotlinPlugins: List<MavenPackage> = emptyList(),
    val exclusions: List<MavenPackage> = emptyList(),
    val repositories: Map<String, MavenRepository> = emptyMap(),
    val enableDefaultRepositories: Boolean = true,
    val localRepository: String? = null,
  ) : DependencyEcosystemConfig {
    fun hasPackages(): Boolean =
      (packages.isNotEmpty() ||
        devPackages.isNotEmpty() ||
        testPackages.isNotEmpty() ||
        modules.isNotEmpty() ||
        compileOnly.isNotEmpty() ||
        runtimeOnly.isNotEmpty())

    fun allPackages(): Sequence<MavenPackage> =
      sequence {
          yieldAll(packages.asSequence())
          yieldAll(modules.asSequence())
          yieldAll(compileOnly.asSequence())
          yieldAll(runtimeOnly.asSequence())
          yieldAll(testPackages.asSequence())
          yieldAll(devPackages.asSequence())
        }
        .distinct()
  }

  @JvmRecord
  @Serializable
  data class PipDependencies(
    val packages: List<PipPackage> = emptyList(),
    val optionalPackages: Map<String, List<PipPackage>> = emptyMap(),
  ) : DependencyEcosystemConfig {
    fun hasPackages(): Boolean = packages.isNotEmpty() || optionalPackages.isNotEmpty()

    fun allPackages(): Sequence<PipPackage> {
      return (packages.asSequence() + optionalPackages.values.flatten().asSequence()).distinct()
    }
  }

  @JvmRecord
  @Serializable
  data class PipPackage(val name: String, val version: String? = null) :
    DependencyEcosystemConfig.PackageSpec

  @JvmRecord
  @Serializable
  data class GemDependencies(
    val packages: List<GemPackage> = emptyList(),
    val devPackages: List<GemPackage> = emptyList(),
    val from: List<String> = emptyList(),
  ) : DependencyEcosystemConfig

  @JvmRecord
  @Serializable
  data class GemPackage(val name: String) : DependencyEcosystemConfig.PackageSpec

  @JvmRecord
  @Serializable
  data class DependencyResolution(val maven: MavenDependencies = MavenDependencies())

  @Serializable
  sealed interface JvmTarget {
    @JvmRecord
    @Serializable
    data class NumericJvmTarget(val number: UInt) : JvmTarget {
      override val argValue: String
        get() = number.toString()
    }

    @JvmRecord
    @Serializable
    data class StringJvmTarget(val name: String) : JvmTarget {
      override val argValue: String
        get() = name
    }

    val argValue: String

    companion object {
      val DEFAULT: JvmTarget = NumericJvmTarget(DEFAULT_JAVA_TARGET)
    }
  }

  @JvmRecord
  @Serializable
  data class JvmFeatures(val testing: Boolean = true, val automodules: Boolean = true)

  @JvmRecord
  @Serializable
  data class JavaCompilerSettings(
    val flags: List<String> = emptyList(),
    val mode: CompilerMode = CompilerMode.Embedded,
  ) {
    enum class CompilerMode(override val symbol: String) : Symbolic<String> {
      Embedded("embedded"),
      External("external");

      companion object : Symbolic.SealedResolver<String, CompilerMode> {
        override fun resolve(symbol: String): CompilerMode =
          when (symbol) {
            Embedded.symbol -> Embedded
            External.symbol -> External
            else -> error("Unrecognized compiler mode: $symbol")
          }
      }
    }
  }

  @JvmRecord
  @Serializable
  data class JavaLanguage(
    val source: JvmTarget? = null,
    val release: JvmTarget? = null,
    val compiler: JavaCompilerSettings = JavaCompilerSettings(),
  )

  @JvmRecord
  @Serializable
  data class JvmSettings(
    val main: String? = null,
    val target: JvmTarget? = null,
    val javaHome: String? = null,
    val features: JvmFeatures = JvmFeatures(),
    val java: JavaLanguage = JavaLanguage(),
    val flags: List<String> = emptyList(),
  )

  @JvmRecord @Serializable data class JavaScriptSettings(val ecma: EcmaStandard? = null)

  @Serializable
  sealed interface EcmaStandard {
    @JvmRecord
    @Serializable
    data class NumericEcmaStandard(val number: UInt) : EcmaStandard {
      override val argValue: String
        get() = number.toString()
    }

    @JvmRecord
    @Serializable
    data class StringEcmaStandard(val name: String) : EcmaStandard {
      override val argValue: String
        get() = name
    }

    val argValue: String
  }

  @Serializable
  @Suppress("UNUSED")
  enum class JvmTargetValidationMode {
    WARNING,
    ERROR,
    IGNORE,
  }

  @JvmRecord
  @Serializable
  data class KotlinJvmCompilerOptions(
    // Abstract Options
    val optIn: List<String> = emptyList(),
    val progressiveMode: Boolean = false,
    val extraWarnings: Boolean = false,
    val allWarningsAsErrors: Boolean = false,
    val suppressWarnings: Boolean = false,
    val verbose: Boolean = false,
    val freeCompilerArgs: List<String> = emptyList(),
    val apiVersion: String = "auto",
    val languageVersion: String = "auto",
    val includeRuntime: Boolean = false,
    val noStdlib: Boolean = false,

    // JVM Options
    val javaParameters: Boolean = false,
    val jvmTarget: JvmTarget? = null,
    val noJdk: Boolean = false,
    val jvmTargetValidationMode: JvmTargetValidationMode = JvmTargetValidationMode.ERROR,
  ) {
    fun collect(): Sequence<String> = sequence {
      // opt-ins
      optIn.forEach { yield("-opt-in=$it") }

      // compiler options
      if (progressiveMode) yield("-progressive")
      if (extraWarnings) yield("-Wextra")
      if (allWarningsAsErrors) yield("-Werror")
      if (suppressWarnings) yield("-nowarn")
      if (verbose) yield("-verbose")
      if (apiVersion != "auto") yield("-api-version=$apiVersion")
      if (languageVersion != "auto") yield("-language-version=$languageVersion")
      if (includeRuntime) yield("-include-runtime")
      if (noStdlib) yield("-no-stdlib")

      if (freeCompilerArgs.isNotEmpty()) yieldAll(freeCompilerArgs)
    }
  }

  @JvmRecord
  @Serializable
  data class KotlinFeatureOptions(
    val testing: Boolean = true,
    val kotlinx: Boolean = true,
    val kapt: Boolean = true,
    val defaultPlugins: Boolean = true,
    val serialization: Boolean = kotlinx && defaultPlugins,
    val coroutines: Boolean = kotlinx,
    val reflection: Boolean = true,
  )

  @Serializable
  sealed interface KotlinToolchainMode {
    @Serializable data object Embedded : KotlinToolchainMode

    @Serializable @JvmInline value class Custom(val kotlinHome: String) : KotlinToolchainMode

    @Serializable @JvmInline value class Managed(val version: String) : KotlinToolchainMode
  }

  @JvmRecord
  @Serializable
  data class KotlinSettings(
    val apiLevel: String = "auto",
    val languageLevel: String = "auto",
    val compilerOptions: KotlinJvmCompilerOptions = KotlinJvmCompilerOptions(),
    val features: KotlinFeatureOptions = KotlinFeatureOptions(),
    val toolchain: KotlinToolchainMode = KotlinToolchainMode.Embedded,
    val plugins: Map<String, Map<String, String>> = emptyMap(),
  )

  @JvmRecord
  @Serializable
  data class NativeImageLinkAtBuildTime(
    val enabled: Boolean = true,
    val packages: List<String> = emptyList(),
  )

  @JvmRecord
  @Serializable
  data class NativeImageClassInit(
    val default: DefaultSetting = DefaultSetting.BUILDTIME,
    val buildtime: List<String> = emptyList(),
    val runtime: List<String> = emptyList(),
  ) {
    enum class DefaultSetting(override val symbol: String) : Symbolic<String> {
      BUILDTIME("buildtime"),
      RUNTIME("runtime");

      companion object : Symbolic.SealedResolver<String, DefaultSetting> {
        override fun resolve(symbol: String): DefaultSetting {
          return when (symbol) {
            BUILDTIME.symbol -> BUILDTIME
            RUNTIME.symbol -> RUNTIME
            else -> throw Symbolic.Unresolved(symbol)
          }
        }
      }
    }
  }

  @JvmRecord
  @Serializable
  data class NativeImageExclusions(
    val all: List<MavenPackage> = emptyList(),
    val classpath: List<MavenPackage> = emptyList(),
    val modulepath: List<MavenPackage> = emptyList(),
  )

  @Serializable
  enum class OptimizationLevel(override val symbol: String) : Symbolic<String> {
    AUTO(OPTIMIZATION_LEVEL_AUTO),
    BUILD(OPTIMIZATION_LEVEL_BUILD),
    SIZE(OPTIMIZATION_LEVEL_SIZE),
    ZERO(OPTIMIZATION_LEVEL_ZERO),
    ONE(OPTIMIZATION_LEVEL_ONE),
    TWO(OPTIMIZATION_LEVEL_TWO),
    THREE(OPTIMIZATION_LEVEL_THREE),
    FOUR(OPTIMIZATION_LEVEL_FOUR);

    companion object : Symbolic.SealedResolver<String, OptimizationLevel> {
      override fun resolve(symbol: String): OptimizationLevel =
        when (symbol.lowercase().trim()) {
          OPTIMIZATION_LEVEL_AUTO -> AUTO
          OPTIMIZATION_LEVEL_BUILD -> BUILD
          OPTIMIZATION_LEVEL_SIZE -> SIZE
          OPTIMIZATION_LEVEL_ZERO -> ZERO
          OPTIMIZATION_LEVEL_ONE -> ONE
          OPTIMIZATION_LEVEL_TWO -> TWO
          OPTIMIZATION_LEVEL_THREE -> THREE
          OPTIMIZATION_LEVEL_FOUR -> FOUR
          else -> throw unresolved(symbol)
        }
    }
  }

  @JvmRecord
  @Serializable
  data class ProfileGuidedOptimization(
    val enabled: Boolean = true,
    val autoprofile: Boolean = false,
    val instrument: Boolean = false,
    val sampling: Boolean = false,
    val profiles: List<String> = emptyList(),
  )

  @Serializable
  enum class NativeImageDriverMode(override val symbol: String) : Symbolic<String> {
    EMBEDDED("embedded"),
    EXTERNAL("external");

    companion object : Symbolic.SealedResolver<String, NativeImageDriverMode> {
      override fun resolve(symbol: String): NativeImageDriverMode =
        when (symbol) {
          "embedded" -> EMBEDDED
          "external" -> EXTERNAL
          else -> throw unresolved(symbol)
        }
    }
  }

  @JvmRecord
  @Serializable
  data class NativeImageOptions(
    val verbose: Boolean = false,
    val linkAtBuildTime: NativeImageLinkAtBuildTime = NativeImageLinkAtBuildTime(),
    val classInit: NativeImageClassInit = NativeImageClassInit(),
    val exclusions: NativeImageExclusions = NativeImageExclusions(),
    val optimization: OptimizationLevel = OptimizationLevel.AUTO,
    val pgo: ProfileGuidedOptimization = ProfileGuidedOptimization(),
    val driverMode: NativeImageDriverMode = NativeImageDriverMode.EMBEDDED,
    val flags: List<String> = emptyList(),
    val cflags: List<String> = emptyList(),
    val ldflags: List<String> = emptyList(),
    val defs: Map<String, String> = emptyMap(),
    val features: List<String> = emptyList(),
  )

  @JvmRecord @Serializable data class NativeImageSettings(val verbose: Boolean = false)

  @Serializable
  enum class NativeImageType(override val symbol: String) : Symbolic<String> {
    BINARY("binary"),
    LIBRARY("library");

    companion object : Symbolic.SealedResolver<String, NativeImageType> {
      override fun resolve(symbol: String): NativeImageType =
        when (symbol.lowercase().trim()) {
          "binary" -> BINARY
          "library" -> LIBRARY
          else -> throw unresolved(symbol)
        }
    }
  }

  @JvmRecord
  @Serializable
  data class NativeImage(
    val name: String? = null,
    @SerialName("imageType") val type: NativeImageType = NativeImageType.BINARY,
    val entrypoint: String? = null,
    val moduleName: String? = null,
    val options: NativeImageOptions = NativeImageOptions(),
    val from: List<String> = emptyList(),
    override val dependsOn: List<String> = emptyList(),
  ) : Artifact

  @JvmRecord
  @Serializable
  data class StaticSite(
    val srcs: String,
    val prefix: String = "/",
    val assets: String? = null,
    val domain: String? = null,
    val preview: String? = null,
    val stylesheets: List<String> = emptyList(),
    val scripts: List<String> = emptyList(),
    val hosting: String? = null,
    override val dependsOn: List<String> = emptyList(),
  ) : Artifact

  @JvmRecord @Serializable data class RuntimeEngineSettings(val maxContexts: Int? = null)

  @JvmRecord @Serializable data class TestingSettings(val coverage: CoverageSettings? = null)

  @JvmRecord @Serializable data class CoverageSettings(val jvm: JvmCoverageSettings? = null)

  @JvmRecord
  @Serializable
  data class JvmCoverageSettings(
    val enabled: Boolean = true,
    val reports: List<JvmCoverageReport> = emptyList(),
  )

  @Serializable
  sealed interface JvmCoverageReport {
    val name: String

    @JvmRecord @Serializable data class HtmlReport(override val name: String) : JvmCoverageReport

    @JvmRecord @Serializable data class XmlReport(override val name: String) : JvmCoverageReport

    @JvmRecord @Serializable data class CsvReport(override val name: String) : JvmCoverageReport
  }
}

fun MavenDependencies.merge(other: MavenDependencies): MavenDependencies {
  return MavenDependencies(
    packages = packages.union(other.packages).toList(),
    modules = modules.union(other.modules).toList(),
    devPackages = devPackages.union(other.devPackages).toList(),
    testPackages = testPackages.union(other.testPackages).toList(),
    compileOnly = compileOnly.union(other.compileOnly).toList(),
    runtimeOnly = runtimeOnly.union(other.runtimeOnly).toList(),
    processors = processors.union(other.processors).toList(),
    exclusions = exclusions.union(other.exclusions).toList(),
    repositories = repositories.plus(other.repositories),
  )
}

fun DependencyResolution.merge(other: DependencyResolution): DependencyResolution {
  return DependencyResolution(maven = maven.merge(other.maven))
}

fun ElidePackageManifest.merge(other: ElidePackageManifest): ElidePackageManifest {
  return ElidePackageManifest(
    name = name ?: other.name,
    version = version ?: other.version,
    description = description ?: other.description,
    entrypoint = (entrypoint ?: emptyList()).plus(other.entrypoint ?: emptyList()).distinct(),
    scripts = scripts + other.scripts,
    artifacts = artifacts + other.artifacts,
    dependencies = dependencies.merge(other.dependencies),
    sources = sources + other.sources,
    jvm = (other.jvm ?: jvm),
    kotlin = (other.kotlin ?: kotlin),
    nativeImage = (other.nativeImage ?: nativeImage),
    web = (other.web ?: web),
    testing = (other.testing ?: testing),
  )
}
