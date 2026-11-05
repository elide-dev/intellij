@file:Suppress("RedundantVisibilityModifier", "Unused", "EnumEntryName")

package dev.elide.tooling.manifest.project

import dev.elide.tooling.manifest.android.AndroidSettings
import dev.elide.tooling.manifest.artifacts.Artifact
import dev.elide.tooling.manifest.coverage.CoverageSettings
import dev.elide.tooling.manifest.dev.DevSettings
import dev.elide.tooling.manifest.engine.EngineSettings
import dev.elide.tooling.manifest.javascript.JavaScriptSettings
import dev.elide.tooling.manifest.jvm.JvmSettings
import dev.elide.tooling.manifest.jvm.MavenDependencies
import dev.elide.tooling.manifest.jvm.MicronautDependencies
import dev.elide.tooling.manifest.jvm.SpringDependencies
import dev.elide.tooling.manifest.kotlin.KotlinSettings
import dev.elide.tooling.manifest.nativeimage.NativeImageSettings
import dev.elide.tooling.manifest.opentelemetry.OpenTelemetrySettings
import dev.elide.tooling.manifest.publishing.PublishingSettings
import dev.elide.tooling.manifest.sources.SourceSet
import dev.elide.tooling.manifest.testing.Testing
import dev.elide.tooling.manifest.toolchain.ToolchainSettings
import dev.elide.tooling.manifest.web.WebSettings
import kotlin.Any
import kotlin.Long
import kotlin.String
import kotlin.Suppress
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.collections.emptyList
import kotlin.collections.emptyMap
import kotlin.jvm.JvmInline
import kotlinx.serialization.Contextual
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
 * Describes an Elide project and its dependencies, build, distribution, and runtime configuration.
 */
@Serializable
@SerialName("elide.project.Project")
public data class ProjectModule(
  /**
   * A name for the project.
   */
  public val name: String? = null,
  /**
   * Version of the project.
   */
  public val version: String? = null,
  /**
   * Optional description for the project, displayed when published as a package.
   */
  public val description: String? = null,
  /**
   * Relative path to the source file evaluated by the runtime on startup.
   */
  public val entrypoint: List<String>? = null,
  /**
   * Script mappings; each entry is a name and a script to run.
   */
  public val scripts: Map<String, String> = emptyMap(),
  /**
   * Build extensions loaded when this project's build is configured.
   *
   * Each entry names a JavaScript or TypeScript module whose default export is called with the
   * build's extension API, or a `.elx` package carrying one. The tasks it declares join the
   * project's task graph alongside Elide's own.
   *
   * An entry is a path relative to the project root — which it may not resolve outside of, nor
   * outside the workspace root when this project is a member — or a [BuildExtensionSource] Elide
   * fetches and caches: a `url:`, an `npm:` package or a `maven:` artifact.
   *
   * An entry that takes settings is amended in place; the settings reach that extension alone. A
   * fetched entry may also pin the digest of the bytes it expects ([ExtensionSpec.integrity]).
   *
   * Settings an extension types as a Pkl class are written against a copy of its module kept in the
   * project: a manifest imports local files, the Pkl standard library and Elide's own schema, never
   * a `package://` or other network URI.
   *
   * ```
   * import "tools/Codegen.pkl"
   *
   * extensions {
   *   "tools/lint.ts"
   *   "tools/codegen.js"
   *   "tools/checks.elx"
   *   "npm:@acme/build-tasks@1.4.0"
   *   "maven:com.acme:build-tasks:1.4.0"
   *   "url:https://downloads.example.com/protoc-tasks-1.4.0.elx"
   *   new {
   *     from = "tools/protoc.ts"
   *     config {
   *       version = "25.1"
   *       plugins = new Listing { "grpc" }
   *     }
   *   }
   *   new {
   *     from = "npm:@acme/codegen@2.0.1"
   *     config = new Codegen.Settings { target = "es2022" }
   *   }
   *   new {
   *     from = "url:https://downloads.example.com/codegen-2.0.1.elx"
   *     integrity = "sha256-47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU="
   *   }
   * }
   * ```
   */
  public val extensions: List<BuildExtension> = emptyList(),
  /**
   * Dependency resolution configuration.
   */
  public val dependencies: Dependencies = Dependencies(),
  /**
   * Configure JVM runtime settings.
   */
  public val jvm: JvmSettings? = null,
  /**
   * Configure Kotlin language settings.
   *
   * An Android project (one declaring [android]) defaults `kotlinx`, `defaultPlugins` and `reflection`
   * off: whatever they put on the runtime classpath is dexed into the app, and none of it is something
   * an Android app is assumed to use. Declaring a feature turns it back on.
   */
  public val kotlin: KotlinSettings?,
  /**
   * Configure JavaScript language settings.
   */
  public val javascript: JavaScriptSettings? = null,
  /**
   * Configure the Android SDK this project builds against.
   */
  public val android: AndroidSettings? = null,
  /**
   * Project-wide settings for the Native Image compiler.
   */
  public val nativeImage: NativeImageSettings? = null,
  /**
   * Configuration for the execution engine.
   */
  public val engine: EngineSettings? = null,
  /**
   * OpenTelemetry traces, metrics, logs, and profiles; off unless destinations are configured here or in the environment.
   */
  public val opentelemetry: OpenTelemetrySettings? = null,
  /**
   * Top-level project sources definition.
   *
   * Left empty, a project's source sets are the ones its layout implies: `src/main` (or `src`) as
   * `main`, `src/test` (or `test`) as `test`, with `src/<name>/<language>` roots and
   * `src/<name>/resources` honored when present. Declaring a name here replaces the convention for
   * that name; see `elide:Sources.pkl`.
   */
  public val sources: Map<String, SourceSet> = emptyMap(),
  /**
   * Development settings for this project.
   */
  public val dev: DevSettings = DevSettings(),
  /**
   * Toolchain settings for this project.
   */
  public val toolchain: ToolchainSettings = ToolchainSettings(),
  /**
   * Artifacts to be produced by this project.
   */
  public val artifacts: Map<String, Artifact> = emptyMap(),
  /**
   * How `elide publish` publishes this project's artifacts.
   */
  public val publishing: PublishingSettings = PublishingSettings(),
  /**
   * Settings which apply to web-based projects and applications.
   */
  public val web: WebSettings? = WebSettings(),
  /**
   * Settings for testing features
   */
  public val testing: Testing = Testing(),
  /**
   * Coverage settings for this project, applied to whichever tasks `coverage.scope` names.
   */
  public val coverage: CoverageSettings = CoverageSettings(),
  /**
   * Workspace settings for this project, when it is the root of a multi-project workspace.
   */
  public val workspace: WorkspaceSettings = WorkspaceSettings(),
)

/**
 * A build extension declared together with the settings it reads.
 */
@Serializable
@SerialName("elide.project.ExtensionSpec")
public data class ExtensionSpec(
  /**
   * Where the extension module or archive comes from: a path relative to the project root, or a
   * `url:`, `npm:` or `maven:` source Elide fetches. A path may not resolve outside the project
   * root, or outside the workspace root when this project is a workspace member.
   */
  public val from: String,
  /**
   * Digest the fetched bytes must match, spelled as Subresource Integrity: `sha256-<base64>` or
   * `sha512-<base64>`.
   *
   * An extension is code the build runs, and a URL is trusted on TLS and its host alone; a pin
   * is what makes the bytes reproducible. It is checked before a download is cached and again on
   * every cache hit, so neither the server nor the workspace's shared cache can change what runs.
   * A digest an npm or Maven registry publishes is checked as well, never instead of this one.
   *
   * Only a fetched source can be pinned.
   */
  public val integrity: String? = null,
  /**
   * Settings for this extension, carried through the manifest unchanged; the extension reads
   * this block as `ctx.config`.
   */
  public val config: Map<String, @Contextual Any?> = emptyMap(),
) : BuildExtension

/**
 * Configuration for NPM dependency resolution.
 */
@Serializable
@SerialName("elide.project.NpmDependencies")
public data class NpmDependencies(
  /**
   * A list of NPM packages to install for this project.
   */
  public val packages: List<String> = emptyList(),
  /**
   * A list of NPM packages to make available at dev-time only.
   */
  public val devPackages: List<String> = emptyList(),
  /**
   * Registry `npm:` build extensions are fetched from; defaults to Elide's `npm.pkg.st` proxy, or
   * to `ELIDE_PKGST_NPM_ENDPOINT` when that is set. NPM package dependencies do not read it.
   */
  public val registry: String? = null,
)

/**
 * Configuration for PyPI dependency resolution.
 */
@Serializable
@SerialName("elide.project.PypiDependencies")
public data class PypiDependencies(
  /**
   * A list of PyPI packages to install for this project.
   */
  public val packages: List<String> = emptyList(),
  /**
   * Optional PyPI dependency groups.
   */
  public val optionalPackages: Map<String, List<String>> = emptyMap(),
)

/**
 * Dependency resolution configuration.
 */
@Serializable
@SerialName("elide.project.Dependencies")
public data class Dependencies(
  /**
   * Configure JVM dependencies using Maven.
   */
  public val maven: MavenDependencies = MavenDependencies(),
  /**
   * Configure JavaScript dependencies using NPM.
   */
  public val npm: NpmDependencies = NpmDependencies(),
  /**
   * Configure Python dependencies using PyPI.
   */
  public val pypi: PypiDependencies = PypiDependencies(),
  /**
   * Configure Spring Boot starters; expands into `maven` at manifest parse time.
   */
  public val spring: SpringDependencies? = null,
  /**
   * Micronaut core modules and the matching annotation processor.
   */
  public val micronaut: MicronautDependencies? = null,
)

/**
 * Workspace settings: the member projects this project is the root of.
 */
@Serializable
@SerialName("elide.project.WorkspaceSettings")
public data class WorkspaceSettings(
  /**
   * Directories of member projects, relative to this manifest's directory; each holds its own `elide.pkl`.
   */
  public val members: List<String> = emptyList(),
)

/**
 * Name of a script within the project's configuration.
 */
public typealias ScriptName = String

/**
 * Entrypoint definition.
 */
public typealias Entrypoint = List<String>

/**
 * A feature set grouping build tasks and other capabilities.
 */
@Serializable
@SerialName("elide.project.FeatureSet")
public enum class FeatureSet {
  @SerialName("kotlin")
  Kotlin,
  @SerialName("java")
  Java,
  @SerialName("js")
  Js,
}

/**
 * A script entry: a shell command, a path to a file to run, or a plain string.
 */
public typealias ScriptValue = String

/**
 * Where a build extension comes from: a path relative to the project root, or `scheme:rest`
 * naming something to fetch.
 *
 * A path names a JavaScript or TypeScript module whose default export declares build tasks, or a
 * `.elx` package carrying one. The three schemes fetch exactly one of those:
 *
 * - `url:https://…/tasks.elx` downloads the file the URL names.
 * - `npm:@scope/tasks@1.2.3` resolves a package tarball through the npm registry.
 * - `maven:group:artifact:1.2.3` resolves one artifact against the `maven.repositories` this
 *   manifest configures, with the usual `[:packaging[:classifier]]` in between.
 *
 * A remote version must be pinned to one exact release; ranges, dist-tags and `-SNAPSHOT` are
 * rejected. Only those three schemes are reserved, so anything else is a path.
 */
public typealias BuildExtensionSource = String

/**
 * An extension declaration: a bare source, or a source carrying that extension's configuration.
 */
@Serializable
@SerialName("elide.project.BuildExtension")
public sealed interface BuildExtension {
  @Serializable(with = OfString.Companion::class)
  @JvmInline
  public value class OfString(
    public val `value`: String,
  ) : BuildExtension {
    public companion object : KSerializer<OfString> {
      override val descriptor: SerialDescriptor =
          buildClassSerialDescriptor("elide.project.BuildExtension.OfString") {
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
 * Represents a major version of the Elide project manifest format, used to indicate breaking changes.
 */
public typealias ManifestVersion = Long

/**
 * NPM package dependency specifier, such as `react@19.0.0` or `@scope/package@1.0.0`.
 */
public typealias NpmPackageDependency = String

/**
 * PyPI package dependency specifier, such as `six==1.17.0` or `requests>=2`.
 */
public typealias PypiPackageDependency = String
