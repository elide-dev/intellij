@file:Suppress("RedundantVisibilityModifier", "Unused", "EnumEntryName")

package dev.elide.tooling.manifest.containers

import dev.elide.tooling.manifest.artifacts.Artifact
import kotlin.String
import kotlin.Suppress
import kotlin.collections.List
import kotlin.collections.emptyList
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Describes a container image output.
 */
@Serializable
@SerialName("elide.containers.ContainerImage")
public data class ContainerImage(
  /**
   * Other artifacts this artifact depends on.
   */
  override val dependsOn: List<String> = emptyList(),
  /**
   * Target image coordinate.
   */
  public val image: String? = null,
  /**
   * Base image to use for this container image.
   */
  public val base: String? = null,
  /**
   * Tags to apply to the container image.
   */
  public val tags: List<String> = emptyList(),
  /**
   * Output mode for the container image. Defaults to `"daemon"`.
   */
  public val output: ContainerOutputMode = ContainerOutputMode.Daemon,
  /**
   * Container image format. Defaults to `"docker"`.
   */
  public val format: ContainerFormat = ContainerFormat.Docker,
  /**
   * Artifacts to include in this container image.
   */
  public val from: List<String> = emptyList(),
) : Artifact

/**
 * Image coordinate string.
 */
public typealias ContainerImageCoordinate = String

/**
 * Container info without versioning.
 */
public typealias ContainerCoordinate = String

/**
 * Validation for compliant container image tags.
 */
public typealias ContainerTag = String

/**
 * Container coordinate qualified with versioning.
 */
public typealias QualifiedContainerCoordinate = String

/**
 * Cryptographic fingerprint for a container image.
 */
public typealias ContainerHash = String

/**
 * Output mode for container images.
 *
 * - `"daemon"`: Publish to the local Docker daemon.
 * - `"tarball"`: Write to a local tarball that can be imported manually into Docker.
 */
@Serializable
@SerialName("elide.containers.ContainerOutputMode")
public enum class ContainerOutputMode {
  @SerialName("daemon")
  Daemon,
  @SerialName("tarball")
  Tarball,
}

/**
 * Container image format.
 *
 * - `"oci"`: Open Container Initiative (OCI) image format.
 * - `"docker"`: Docker image format (Docker Image Manifest V2).
 */
@Serializable
@SerialName("elide.containers.ContainerFormat")
public enum class ContainerFormat {
  @SerialName("oci")
  Oci,
  @SerialName("docker")
  Docker,
}
