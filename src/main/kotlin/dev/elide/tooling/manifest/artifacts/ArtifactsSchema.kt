@file:Suppress("RedundantVisibilityModifier", "Unused")

package dev.elide.tooling.manifest.artifacts

import kotlin.String
import kotlin.Suppress
import kotlin.collections.List
import kotlin.collections.Map

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
 * Artifact name typealias.
 */
public typealias ArtifactName = String

/**
 * Holds artifact configurations for a given project.
 */
public typealias Artifacts = Map<String, Artifact>
