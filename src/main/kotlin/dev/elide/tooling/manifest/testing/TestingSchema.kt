@file:Suppress("RedundantVisibilityModifier", "Unused")

package dev.elide.tooling.manifest.testing

import dev.elide.tooling.manifest.jvm.JvmCoverageSettings
import kotlin.Boolean
import kotlin.String
import kotlin.Suppress
import kotlin.collections.List
import kotlin.collections.emptyList
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Abstract type specifying a test report.
 */
public interface TestReport {
  /**
   * Name for this report; typically the format name by default.
   */
  public val name: String
}

/**
 * Test report in JUnit-compatible XML format.
 */
@Serializable
@SerialName("elide.testing.XmlTestReport")
public data class XmlTestReport(
  /**
   * Name for this report; typically the format name by default.
   */
  override val name: String,
) : TestReport

/**
 * Abstract type specifying a coverage report.
 */
public interface CoverageReport {
  /**
   * Name for this report; typically the format name by default.
   */
  public val name: String
}

/**
 * Settings for guest JavaScript/TypeScript test coverage.
 */
@Serializable
@SerialName("elide.testing.JsCoverageSettings")
public data class JsCoverageSettings(
  /**
   * Whether guest coverage is collected. Off by default: enable it here, or pass `--coverage`.
   */
  public val enabled: Boolean = false,
  /**
   * Source roots to report coverage for; empty reports everything under the project root.
   */
  public val paths: List<String> = emptyList(),
)

/**
 * Settings for test coverage in supported ecosystems.
 */
@Serializable
@SerialName("elide.testing.CoverageSettings")
public data class CoverageSettings(
  /**
   * Configure JVM test coverage settings.
   */
  public val jvm: JvmCoverageSettings = JvmCoverageSettings(),
  /**
   * Configure guest JavaScript/TypeScript coverage settings.
   */
  public val js: JsCoverageSettings = JsCoverageSettings(),
)

/**
 * DSL root type where test settings and configuration are mounted.
 */
@Serializable
@SerialName("elide.testing.Testing")
public data class Testing(
  /**
   * Settings which govern coverage support; collection stays off until enabled per ecosystem.
   */
  public val coverage: CoverageSettings = CoverageSettings(),
  /**
   * Machine-readable test reports requested from each supported language runner.
   */
  public val reports: List<TestReport> = emptyList(),
)
