@file:Suppress("RedundantVisibilityModifier", "Unused")

package dev.elide.tooling.manifest.testing

import kotlin.Long
import kotlin.String
import kotlin.Suppress
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.collections.emptyList
import kotlin.collections.emptyMap
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
 * DSL root type where test settings and configuration are mounted.
 */
@Serializable
@SerialName("elide.testing.Testing")
public data class Testing(
  /**
   * Machine-readable test reports requested from each supported language runner.
   */
  public val reports: List<TestReport> = emptyList(),
  /**
   * Containers shared by this project's test runners for one invocation.
   */
  public val testcontainers: Testcontainers? = null,
)

/**
 * Readiness condition, evaluated before any tests run.
 */
@Serializable
@SerialName("elide.testing.ContainerWait")
public data class ContainerWait(
  /**
   * "port", "http", or "log". Port waits check all exposed TCP ports.
   */
  public val strategy: String = "port",
  /**
   * Container-side port for an HTTP readiness check.
   */
  public val port: Long? = null,
  /**
   * HTTP readiness path.
   */
  public val path: String = "/",
  /**
   * Required HTTP response status.
   */
  public val status: Long = 200L,
  /**
   * Java regular expression for a log readiness check.
   */
  public val pattern: String? = null,
  /**
   * Readiness timeout in seconds, after image download.
   */
  public val timeoutSeconds: Long = 60L,
)

/**
 * A disposable container, managed by Testcontainers.
 */
@Serializable
@SerialName("elide.testing.TestContainer")
public data class TestContainer(
  /**
   * OCI image reference, preferably pinned to a tag or digest.
   */
  public val image: String,
  /**
   * Command arguments; never interpreted by a host shell.
   */
  public val command: List<String> = emptyList(),
  /**
   * Environment inside the container.
   */
  public val environment: Map<String, String> = emptyMap(),
  /**
   * TCP ports exposed on randomly allocated host ports.
   */
  public val ports: List<Long> = emptyList(),
  /**
   * Readiness check.
   */
  public val wait: ContainerWait = ContainerWait(),
)

/**
 * Language-independent test infrastructure. Requires a Docker-compatible daemon.
 */
@Serializable
@SerialName("elide.testing.Testcontainers")
public data class Testcontainers(
  /**
   * Named containers, started in declaration order and stopped after the test run.
   */
  public val containers: Map<String, TestContainer> = emptyMap(),
  /**
   * Test environment bindings: ${name.host}, ${name.port.5432}, or ${name.id}.
   * Explicit bindings override inherited host environment values for test runners only.
   */
  public val environment: Map<String, String> = emptyMap(),
  /**
   * Total startup deadline, including image downloads and every readiness check.
   */
  public val startupTimeoutSeconds: Long = 300L,
)
