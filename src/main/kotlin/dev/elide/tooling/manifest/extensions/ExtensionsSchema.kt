@file:Suppress("RedundantVisibilityModifier", "Unused")

package dev.elide.tooling.manifest.extensions

import dev.elide.tooling.manifest.artifacts.Artifact
import kotlin.String
import kotlin.Suppress
import kotlin.collections.List
import kotlin.collections.emptyList
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Declares an Elide build extension, packaged as an `.elx` archive.
 */
@Serializable
@SerialName("elide.extensions.ExtensionArchive")
public data class ExtensionArchive(
  /**
   * Other artifacts this artifact depends on.
   */
  override val dependsOn: List<String> = emptyList(),
  /**
   * JavaScript or TypeScript module whose default export declares the extension's tasks, relative
   * to the project root; packaged as the archive's entrypoint.
   */
  public val entrypoint: String,
  /**
   * Name the package describes itself by; defaults to the project's own name.
   */
  public val name: String? = null,
  /**
   * Version the package describes itself by; defaults to the project's own version.
   */
  public val version: String? = null,
  /**
   * Description of what the extension does; defaults to the project's own description.
   */
  public val description: String? = null,
  /**
   * SPDX identifier of the license the extension is distributed under.
   */
  public val license: String? = null,
  /**
   * Source repository the extension is developed in.
   */
  public val repository: String? = null,
  /**
   * Homepage documenting the extension.
   */
  public val homepage: String? = null,
  /**
   * Who authors the extension.
   */
  public val authors: List<String> = emptyList(),
  /**
   * Sources packaged beside the entrypoint, as paths or glob patterns relative to the project
   * root. The entrypoint itself is always packaged.
   */
  public val srcs: List<String> = emptyList(),
) : Artifact
