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
// @file:Suppress("unused")

@file:Suppress("unused")

package dev.elide.intellij.project.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val SERIAL_NAME_BASE = "elide.tooling.project.manifest.ElidePackageManifest."

@Serializable data class ElidePackageManifest(
  var name: String? = null,
  var version: String? = null,
  var description: String? = null,
  var entrypoint: List<String>? = null,
  var workspaces: List<String> = emptyList(),
  var scripts: Map<String, String> = emptyMap(),
  var artifacts: Map<String, Artifact> = emptyMap(),
  var dependencies: DependencyResolution = DependencyResolution(),
  var javascript: JavaScriptSettings? = null,
  var typescript: TypeScriptSettings? = null,
  var jvm: JvmSettings? = null,
  var kotlin: KotlinSettings? = null,
  var python: PythonSettings? = null,
  var ruby: RubySettings? = null,
  var pkl: PklSettings? = null,
  var nativeImage: NativeImageSettings? = null,
  var dev: DevSettings? = null,
  var toolchain: ToolchainSettings? = null,
  var sources: Map<String, SourceSet> = emptyMap(),
  var tests: TestingSettings? = null,
  var lockfile: LockfileSettings? = null,
  var web: WebSettings? = null,
  var secrets: SecretSettings? = null,
  var engine: RuntimeEngineSettings? = null,
  var server: ServerSettings? = null,
) {
  companion object {
    private var ManifestJson = Json { ignoreUnknownKeys = true }

    @JvmStatic fun parse(input: String): ElidePackageManifest {
      return runCatching { Json.decodeFromString<ElidePackageManifest>(input) }
        .getOrElse { throw IllegalStateException("Failed to parse Elide project manifest: ${it.message}", it) }
    }
  }

  @Serializable sealed interface Artifact {
    var from: List<String>
    var dependsOn: List<String>
  }

  @Serializable data class JarResource(
    var path: String,
  )

  @Serializable data class ProjectSourceSpec(
    var platform: String? = null,
    var project: String? = null,
    var subpath: String? = null,
  )

  @Serializable data class LspSettings(
    var delegates: List<String>? = null,
  )

  @Serializable data class McpResource(
    var path: String,
    var name: String,
    var description: String = "",
    var mimeType: String? = null,
  )

  @Serializable data class McpSettings(
    var resources: List<McpResource>? = null,
    var advice: Boolean = true,
    var registerElide: Boolean = true,
  )

  @Serializable data class DevServerSettings(
    var host: String = "0.0.0.0",
    var port: Int = 8080,
  )

  @Serializable data class DevSettings(
    var source: ProjectSourceSpec? = null,
    var lsp: LspSettings? = null,
    var mcp: McpSettings? = null,
    var server: DevServerSettings? = null,
  )

  @Serializable class NativeToolchainSettings

  @Serializable class JvmToolchainSettings

  @Serializable data class EngineSettings(
    var version: String? = null,
  )

  @Serializable data class ToolchainSettings(
    var native: NativeToolchainSettings? = null,
    var jvm: JvmToolchainSettings? = null,
    var engines: Map<String, EngineSettings>? = null,
  )

  @Serializable @SerialName(SERIAL_NAME_BASE + "Jar") data class Jar(
    var name: String? = null,
    var sources: List<String> = emptyList(),
    var resources: Map<String, JarResource> = emptyMap(),
    var manifest: Map<String, String> = emptyMap(),
    var options: JarOptions = JarOptions(),
    override var from: List<String> = emptyList(),
    override var dependsOn: List<String> = emptyList(),
  ) : Artifact

  @Serializable data class JarOptions(
    var compress: Boolean = true,
    var defaultManifestProperties: Boolean = true,
    var entrypoint: String? = null,
  )

  @Serializable @SerialName(SERIAL_NAME_BASE + "ContainerImage") data class ContainerImage(
    var image: String? = null,
    var base: String? = null,
    var tags: List<String> = emptyList(),
    override var from: List<String> = emptyList(),
    override var dependsOn: List<String> = emptyList(),
  ) : Artifact

  @Serializable data class SourceSet(
    var type: SourceSetType = SourceSetType.Main,
    var synthetic: Boolean = false,
    var paths: List<String>,
  ) {
    enum class SourceSetType {
      Main,
      Test,
      Integration,
      Example,
      Docs,
      Infra,
      Other
      ;

      companion object {
        fun parse(spec: String): SourceSetType = when (spec) {
          "main" -> Main
          "test" -> Test
          "integration" -> Integration
          "example" -> Example
          "docs" -> Docs
          "infra" -> Infra
          else -> Other
        }
      }
    }
  }

  @Serializable data class WebSettings(
    var debug: Boolean = false,
    var css: CssSettings = CssSettings(),
  )

  @Serializable data class CssTarget(
    var browser: String,
    var version: String? = null,
  )

  @Serializable data class CssSettings(
    var minify: Boolean = true,
    var targets: List<CssTarget> = emptyList(),
  )

  @Serializable sealed interface DependencyEcosystemConfig {
    @Serializable sealed interface PackageSpec
    @Serializable sealed interface RepositorySpec
  }

  @Serializable @SerialName(SERIAL_NAME_BASE + "NpmDependencies")
  data class NpmDependencies(
    var packages: List<NpmPackage> = emptyList(),
    var devPackages: List<NpmPackage> = emptyList(),
    var repositories: Map<String, NpmRepository> = emptyMap(),
    var from: List<String> = emptyList(),
  ) : DependencyEcosystemConfig {
    fun hasPackages(): Boolean = packages.isNotEmpty() || devPackages.isNotEmpty()
  }

  @Serializable data class NpmPackage(
    var name: String,
    var version: String?,
  ) : DependencyEcosystemConfig.PackageSpec {
    companion object {
      @JvmStatic fun parse(str: String): NpmPackage {
        val version = str.substringAfterLast('@')
        val name = str.substringBeforeLast('@')
        return NpmPackage(
          name = name,
          version = version.ifEmpty { null },
        )
      }
    }
  }

  @Serializable @SerialName(SERIAL_NAME_BASE + "NpmRepository")
  data class NpmRepository(
    var name: String,
    var url: String,
  ) : DependencyEcosystemConfig.RepositorySpec

  @Serializable data class GradleCatalog(
    var name: String? = null,
    var path: String,
  ) : Comparable<GradleCatalog> {
    companion object {
      @JvmStatic fun parse(str: String): GradleCatalog = GradleCatalog(
        name = str.substringBefore("."),
        path = str,
      )
    }

    override fun compareTo(other: GradleCatalog): Int = path.compareTo(other.path)
  }

  @Serializable data class MavenPackage(
    var group: String = "",
    var name: String = "",
    var version: String? = "",
    var classifier: String? = "",
    var repository: String? = "",
    var path: String? = "",
    var coordinate: String,
  ) : DependencyEcosystemConfig.PackageSpec

  @Serializable @SerialName(SERIAL_NAME_BASE + "MavenRepository")
  data class MavenRepository(
    var url: String,
    var name: String? = null,
    var description: String? = null,
  ) : DependencyEcosystemConfig.RepositorySpec

  @Serializable data class MavenCoordinates(
    var group: String,
    var name: String,
    var classifier: String? = null,
  )

  @Serializable @SerialName(SERIAL_NAME_BASE + "MavenDependencies")
  data class MavenDependencies(
    var coordinates: MavenCoordinates? = null,
    var packages: List<MavenPackage> = emptyList(),
    var modules: List<MavenPackage> = emptyList(),
    var devPackages: List<MavenPackage> = emptyList(),
    var testPackages: List<MavenPackage> = emptyList(),
    var compileOnly: List<MavenPackage> = emptyList(),
    var runtimeOnly: List<MavenPackage> = emptyList(),
    var processors: List<MavenPackage> = emptyList(),
    var exclusions: List<MavenPackage> = emptyList(),
    var catalogs: List<GradleCatalog> = emptyList(),
    var repositories: Map<String, MavenRepository> = emptyMap(),
    var enableDefaultRepositories: Boolean = true,
    var from: List<String> = emptyList(),
  ) : DependencyEcosystemConfig

  @Serializable @SerialName(SERIAL_NAME_BASE + "PipDependencies")
  data class PipDependencies(
    var packages: List<PipPackage> = emptyList(),
    var optionalPackages: Map<String, List<PipPackage>> = emptyMap(),
  ) : DependencyEcosystemConfig

  @Serializable data class PipPackage(
    var name: String,
    var version: String? = null,
  ) : DependencyEcosystemConfig.PackageSpec

  @Serializable @SerialName(SERIAL_NAME_BASE + "GemDependencies")
  data class GemDependencies(
    var packages: List<GemPackage> = emptyList(),
    var devPackages: List<GemPackage> = emptyList(),
    var from: List<String> = emptyList(),
  ) : DependencyEcosystemConfig

  @Serializable data class GemPackage(
    var name: String,
  ) : DependencyEcosystemConfig.PackageSpec

  @Serializable data class DependencyResolution(
    var maven: MavenDependencies = MavenDependencies(),
    var npm: NpmDependencies = NpmDependencies(),
    var pip: PipDependencies = PipDependencies(),
    var gems: GemDependencies = GemDependencies(),
  )

  @Serializable
  sealed interface JvmTarget {
    @Serializable @SerialName(SERIAL_NAME_BASE + "JvmTarget.NumericJvmTarget")
    data class NumericJvmTarget(var number: UInt) : JvmTarget {
      override val argValue: String get() = number.toString()
    }

    @Serializable @SerialName(SERIAL_NAME_BASE + "JvmTarget.StringJvmTarget")
    data class StringJvmTarget(var name: String) : JvmTarget {
      override val argValue: String get() = name
    }

    val argValue: String
  }

  @Serializable data class JvmFeatures(
    var testing: Boolean = true,
    var automodules: Boolean = true,
  )

  @Serializable data class JvmTesting(
    var enabled: Boolean = true,
    var driver: JvmTestDriver = JvmTestDriver.Elide
  ) {
    enum class JvmTestDriver {
      Elide,
      JUnit,
    }
  }

  @Serializable data class JavaCompilerSettings(
    var flags: List<String> = emptyList(),
  )

  @Serializable data class JavaLanguage(
    var source: JvmTarget? = null,
    var release: JvmTarget? = null,
    var compiler: JavaCompilerSettings = JavaCompilerSettings(),
  )

  @Serializable data class JvmSettings(
    var main: String? = null,
    var target: JvmTarget? = null,
    var javaHome: String? = null,
    var features: JvmFeatures = JvmFeatures(),
    var java: JavaLanguage = JavaLanguage(),
    var flags: List<String> = emptyList(),
  )

  @Serializable data class JavaScriptSettings(
    var debug: Boolean = false,
  )

  @Serializable data class TypeScriptSettings(
    var debug: Boolean = false,
  )

  @Serializable enum class JvmTargetValidationMode {
    WARNING,
    ERROR,
    IGNORE,
  }

  @Serializable data class KotlinJvmCompilerOptions(
    // Abstract Options
    var optIn: List<String> = emptyList(),
    var progressiveMode: Boolean = false,
    var extraWarnings: Boolean = false,
    var allWarningsAsErrors: Boolean = false,
    var suppressWarnings: Boolean = false,
    var verbose: Boolean = false,
    var freeCompilerArgs: List<String> = emptyList(),
    var apiVersion: String = "auto",
    var languageVersion: String = "auto",
    var includeRuntime: Boolean = false,
    var noStdlib: Boolean = false,

    // JVM Options
    var javaParameters: Boolean = false,
    var jvmTarget: JvmTarget? = null,
    var noJdk: Boolean = false,
    var jvmTargetValidationMode: JvmTargetValidationMode = JvmTargetValidationMode.ERROR,
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
      when (val tgt = jvmTarget) {
        null -> {}
        else -> yield("-jvm-target=${tgt.argValue}")
      }

      if (freeCompilerArgs.isNotEmpty()) yieldAll(freeCompilerArgs)
    }
  }

  @Serializable data class KotlinFeatureOptions(
    var injection: Boolean = true,
    var testing: Boolean = true,
    var kotlinx: Boolean = true,
    var kapt: Boolean = true,
    var ksp: Boolean = true,
    var enableDefaultPlugins: Boolean = true,
    var experimental: Boolean = true,
    var incremental: Boolean = true,
    var serialization: Boolean = kotlinx && enableDefaultPlugins && experimental,
    var coroutines: Boolean = kotlinx,
    var redaction: Boolean = enableDefaultPlugins && experimental,
    var autoClasspath: Boolean = true,
    var reflection: Boolean = true,
  )

  @Serializable data class KotlinSettings(
    var apiLevel: String = "auto",
    var languageLevel: String = "auto",
    var compilerOptions: KotlinJvmCompilerOptions = KotlinJvmCompilerOptions(),
    var features: KotlinFeatureOptions = KotlinFeatureOptions(),
  )

  @Serializable data class PythonSettings(
    var debug: Boolean = false,
    var wsgi: WsgiSettings = WsgiSettings(),
  )

  @Serializable data class WsgiSettings(
    var name: String? = null,
    var args: List<String>? = null,
  )

  @Serializable data class RubySettings(
    var debug: Boolean = false,
  )

  @Serializable data class PklSettings(
    var debug: Boolean = false,
  )

  @Serializable data class NativeImageLinkAtBuildTime(
    var enabled: Boolean = true,
    var packages: List<String> = emptyList(),
  )

  @Serializable data class NativeImageClassInit(
    var enabled: Boolean = true,
    var buildtime: List<String> = emptyList(),
    var runtime: List<String> = emptyList(),
  )

  @Serializable data class NativeImageExclusions(
    var all: List<MavenPackage> = emptyList(),
    var classpath: List<MavenPackage> = emptyList(),
    var modulepath: List<MavenPackage> = emptyList(),
  )

  @Serializable enum class OptimizationLevel {
    AUTO,
    BUILD,
    SIZE,
    ZERO,
    ONE,
    TWO,
    THREE,
    FOUR;
  }

  @Serializable data class ProfileGuidedOptimization(
    var enabled: Boolean = true,
    var autoprofile: Boolean = false,
    var instrument: Boolean = false,
    var sampling: Boolean = false,
    var profiles: List<String> = emptyList(),
  )

  @Serializable enum class NativeImageDriverMode {
    EMBEDDED,
    EXTERNAL;
  }

  @Serializable data class NativeImageOptions(
    var verbose: Boolean = false,
    var linkAtBuildTime: NativeImageLinkAtBuildTime = NativeImageLinkAtBuildTime(),
    var classInit: NativeImageClassInit = NativeImageClassInit(),
    var exclusions: NativeImageExclusions = NativeImageExclusions(),
    var optimization: OptimizationLevel = OptimizationLevel.AUTO,
    var pgo: ProfileGuidedOptimization = ProfileGuidedOptimization(),
    var driverMode: NativeImageDriverMode = NativeImageDriverMode.EMBEDDED,
    var flags: List<String> = emptyList(),
    var cflags: List<String> = emptyList(),
    var ldflags: List<String> = emptyList(),
    var defs: Map<String, String> = emptyMap(),
    var features: List<String> = emptyList(),
  )

  @Serializable data class NativeImageSettings(
    var verbose: Boolean = false,
  )

  @Serializable enum class NativeImageType {
    BINARY,
    LIBRARY;
  }

  @Serializable @SerialName(SERIAL_NAME_BASE + "NativeImage") data class NativeImage(
    var name: String? = null,
    var type: NativeImageType = NativeImageType.BINARY,
    var entrypoint: String? = null,
    var moduleName: String? = null,
    var options: NativeImageOptions = NativeImageOptions(),
    override var from: List<String> = emptyList(),
    override var dependsOn: List<String> = emptyList(),
  ) : Artifact

  @Serializable @SerialName(SERIAL_NAME_BASE + "StaticSite") data class StaticSite(
    var srcs: String,
    var prefix: String = "/",
    var assets: String? = null,
    var domain: String? = null,
    var preview: String? = null,
    var stylesheets: List<String> = emptyList(),
    var scripts: List<String> = emptyList(),
    var hosting: String? = null,
    override var from: List<String> = emptyList(),
    override var dependsOn: List<String> = emptyList(),
  ) : Artifact

  @Serializable data class CoverageSettings(
    var enabled: Boolean = false,
    var paths: List<String> = emptyList(),
  )

  @Serializable data class TestingSettings(
    var coverage: CoverageSettings = CoverageSettings(),
    var jvm: JvmTesting = JvmTesting(),
  )

  @Serializable data class LockfileSettings(
    var enabled: Boolean = true,
    var format: String = "auto",
  )

  @Serializable enum class SecretsRemote {
    PROJECT,
    GITHUB;
  }

  @Serializable data class ProjectRemoteSettings(
    var path: String? = null,
  )

  @Serializable data class GithubRemoteSettings(
    var repository: String? = null,
  )

  @Serializable data class SecretSettings(
    var profile: String? = null,
    var remote: SecretsRemote? = null,
    var project: ProjectRemoteSettings? = null,
    var github: GithubRemoteSettings? = null,
  )

  @Serializable data class RuntimeEngineSettings(
    var maxContexts: Int? = null,
  )

  @Serializable data class ServerSettings(
    var address: BindingAddress? = null,
    var cleartext: Boolean = true,
    var transport: String? = null,
    var https: HttpsServerSettings? = null,
    var http3: Http3ServerSettings? = null,
    var serverName: String? = null,
  ) {
    @Serializable sealed interface SSLCertificate {
      @Serializable @SerialName(SERIAL_NAME_BASE + "ServerSettings.SSLCertificate.LocalFileCertificate")
      data class LocalFileCertificate(
        var certFile: String,
        var keyFile: String,
        var keyPassphrase: String? = null,
      ) : SSLCertificate

      @Serializable @SerialName(SERIAL_NAME_BASE + "ServerSettings.SSLCertificate.SelfSignedCertificate")
      data class SelfSignedCertificate(
        var subject: String? = null,
        var notBefore: Long? = null,
        var notAfter: Long? = null,
      ) : SSLCertificate
    }

    @Serializable sealed interface BindingAddress {
      @Serializable @JvmInline @SerialName(SERIAL_NAME_BASE + "BindingAddress.DomainSocketAddress")
      value class DomainSocketAddress(val path: String) : BindingAddress

      @Serializable @SerialName(SERIAL_NAME_BASE + "BindingAddress.SocketAddress")
      data class SocketAddress(
        var hostname: String? = null,
        var port: Int? = null,
      ) : BindingAddress
    }

    @Serializable data class HttpsServerSettings(
      var certificate: SSLCertificate,
      var address: BindingAddress? = null,
    )

    @Serializable data class Http3ServerSettings(
      var certificate: SSLCertificate,
      var address: BindingAddress? = null,
      var advertise: Boolean = false,
    )
  }
}
