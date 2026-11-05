@file:Suppress("RedundantVisibilityModifier", "Unused", "EnumEntryName")

package dev.elide.tooling.manifest.coverage

import kotlin.Boolean
import kotlin.String
import kotlin.Suppress
import kotlin.collections.List
import kotlin.collections.emptyList
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
 * Report format written by the JVM collector (JaCoCo).
 */
public interface JvmCoverageReport : CoverageReport

/**
 * Coverage report in HTML format.
 */
@Serializable
@SerialName("elide.coverage.HtmlCoverageReport")
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
@SerialName("elide.coverage.XmlCoverageReport")
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
@SerialName("elide.coverage.CsvCoverageReport")
public data class CsvCoverageReport(
  /**
   * Name for this report; typically the format name by default.
   */
  override val name: String,
) : JvmCoverageReport

/**
 * Settings for JVM coverage, collected by JaCoCo.
 */
@Serializable
@SerialName("elide.coverage.JvmCoverageSettings")
public data class JvmCoverageSettings(
  /**
   * Coverage reports to be generated; each collecting task writes them under its own report root.
   */
  public val reports: List<JvmCoverageReport> = emptyList(),
  /**
   * Class-name patterns the agent must not instrument, in JaCoCo's own syntax (`*` and `?`
   * wildcards). Generated and build-time-only code belongs here: instrumenting it costs startup
   * and reports coverage nothing can exercise.
   */
  public val excludes: List<String> = emptyList(),
)

/**
 * Settings for guest JavaScript/TypeScript coverage.
 */
@Serializable
@SerialName("elide.coverage.JsCoverageSettings")
public data class JsCoverageSettings(
  /**
   * Source roots to report coverage for; empty reports everything under the project root.
   */
  public val paths: List<String> = emptyList(),
)

/**
 * Coverage settings for a project, independent of the task that measures it.
 */
@Serializable
@SerialName("elide.coverage.CoverageSettings")
public data class CoverageSettings(
  /**
   * Whether coverage is collected. Off by default: enable it here, or pass `--coverage`.
   */
  public val enabled: Boolean = false,
  /**
   * Which tasks collect when [enabled]. Instrumenting a program run forks a JVM and attaches an
   * agent, which a project reporting coverage for its tests never asked to pay for, so `tests` is
   * the default; `--coverage` always collects from whichever task it was passed to.
   */
  public val scope: CoverageScope = CoverageScope.Tests,
  /**
   * Configure JVM coverage settings.
   */
  public val jvm: JvmCoverageSettings = JvmCoverageSettings(),
  /**
   * Configure guest JavaScript/TypeScript coverage settings.
   */
  public val js: JsCoverageSettings = JsCoverageSettings(),
)

/**
 * Task kinds which collect coverage once it is enabled.
 */
@Serializable
@SerialName("elide.coverage.CoverageScope")
public enum class CoverageScope {
  @SerialName("tests")
  Tests,
  @SerialName("runs")
  Runs,
  @SerialName("all")
  All,
}
