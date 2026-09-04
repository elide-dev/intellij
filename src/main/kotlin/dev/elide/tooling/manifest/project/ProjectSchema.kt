@file:Suppress("RedundantVisibilityModifier", "Unused", "EnumEntryName")

package dev.elide.tooling.manifest.project

import dev.elide.tooling.manifest.artifacts.Artifact
import dev.elide.tooling.manifest.dev.DevSettings
import dev.elide.tooling.manifest.engine.EngineSettings
import dev.elide.tooling.manifest.javascript.JavaScriptSettings
import dev.elide.tooling.manifest.jvm.JvmSettings
import dev.elide.tooling.manifest.jvm.MavenDependencies
import dev.elide.tooling.manifest.jvm.SpringDependencies
import dev.elide.tooling.manifest.kotlin.KotlinSettings
import dev.elide.tooling.manifest.nativeimage.NativeImageSettings
import dev.elide.tooling.manifest.sources.SourceSet
import dev.elide.tooling.manifest.testing.Testing
import dev.elide.tooling.manifest.toolchain.ToolchainSettings
import dev.elide.tooling.manifest.web.WebSettings
import kotlin.Long
import kotlin.String
import kotlin.Suppress
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.collections.emptyList
import kotlin.collections.emptyMap
import kotlin.collections.mapOf
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
   * Dependency resolution configuration.
   */
  public val dependencies: Dependencies = Dependencies(),
  /**
   * Configure JVM runtime settings.
   */
  public val jvm: JvmSettings? = null,
  /**
   * Configure Kotlin language settings.
   */
  public val kotlin: KotlinSettings? = null,
  /**
   * Configure JavaScript language settings.
   */
  public val javascript: JavaScriptSettings? = null,
  /**
   * Project-wide settings for the Native Image compiler.
   */
  public val nativeImage: NativeImageSettings? = null,
  /**
   * Configuration for the execution engine.
   */
  public val engine: EngineSettings? = null,
  /**
   * Top-level project sources definition.
   */
  public val sources:
      Map<String, SourceSet> = mapOf("main" to SourceSet.OfString("src/**.*"), "test" to SourceSet.OfString("test/**.*")),
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
   * Settings which apply to web-based projects and applications.
   */
  public val web: WebSettings? = WebSettings(),
  /**
   * Settings for testing features
   */
  public val testing: Testing = Testing(),
)

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
