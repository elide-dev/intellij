@file:Suppress("RedundantVisibilityModifier", "Unused")

package dev.elide.tooling.manifest.engine

import kotlin.Long
import kotlin.Suppress
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Configures the execution engine used by Elide when running this project.
 */
@Serializable
@SerialName("elide.engine.EngineSettings")
public data class EngineSettings(
  /**
   * Sets the maximum number of guest contexts to use when running the
   * application. Defaults to a sensible limit based on available processors.
   */
  public val maxContexts: Long? = null,
)
