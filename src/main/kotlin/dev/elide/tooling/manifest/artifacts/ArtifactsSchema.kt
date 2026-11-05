@file:Suppress("RedundantVisibilityModifier", "Unused")

package dev.elide.tooling.manifest.artifacts

import kotlin.Boolean
import kotlin.String
import kotlin.Suppress
import kotlin.collections.List
import kotlin.collections.Map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Base type for all artifacts.
 */
public interface Artifact {
  /**
   * Other artifacts this artifact depends on.
   */
  public val dependsOn: List<String>
}

/**
 * An artifact built by another project of the same workspace, consumed by this one.
 *
 * The reference names no artifact kind: whether the artifact a given dependency block resolves to
 * is one that block can consume is checked when the build is configured.
 */
@Serializable
@SerialName("elide.artifacts.ProjectArtifact")
public data class ProjectArtifact(
  /**
   * Workspace member name, or the root project's name, declaring the artifact.
   */
  public val project: String,
  /**
   * Artifact declared by that project; omit when it declares exactly one usable artifact.
   */
  public val artifact: String? = null,
  /**
   * Whether this project compiles as a *friend* of the referenced artifact: the sibling's Kotlin
   * `internal` declarations are visible to it. Only meaningful where this project compiles Kotlin
   * and only for a reference in a compile bucket (`packages`, `modules`, `compileOnly`,
   * `testPackages`); friendship is not transitive.
   */
  public val friend: Boolean = false,
)

/**
 * Artifact name typealias.
 */
public typealias ArtifactName = String

/**
 * Name of a project within a workspace.
 */
public typealias ProjectName = String

/**
 * Holds artifact configurations for a given project.
 */
public typealias Artifacts = Map<String, Artifact>
