/*
 * Copyright (c) 2025 Elide Technologies, Inc. All Rights Reserved.
 *
 * PROPRIETARY AND CONFIDENTIAL. This software may contain trade secrets and
 * confidential information of Elide Technologies, Inc.
 *
 * UNAUTHORIZED USE, COPYING, DISTRIBUTION, OR DISCLOSURE IS STRICTLY PROHIBITED.
 * No part of this software may be shared without prior written consent from
 * Elide Technologies, Inc.
 *
 * Contact: engineering@elide.dev
 */

package dev.elide.project.flags

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * ## Project Flag Definition
 *
 * Defines the structure, type, and identity, of a [ProjectFlag], which is constituent to a given
 * Elide project; the flag's definition is responsible for matching the flag during parsing, and for
 * performing any processing required to extract a flag value.
 *
 * The flag's "schema," so to speak, can be interrogated using the flag's definition.
 *
 * @property name Name of the flag under definition.
 * @property aliases Alternate names that should match this flag.
 * @property description Optional human-readable description of the flag.
 * @property type Type of data produced by the project flag.
 * @property required Whether to require a value for this flag.
 * @property defaultValue Default value assigned to this flag, if any.
 */
@Serializable class ProjectFlagDefinition private constructor(private val info: FlagInfo) {
  // Private flag info structure.
  @Serializable
  @JvmRecord
  data class FlagInfo(
    val name: String,
    val aliases: List<String>,
    val description: String? = null,
    @SerialName("kind") val type: ProjectFlagType,
    val required: Boolean,
    val defaultValue: ProjectFlagValue = ProjectFlagValue.NoValue,
  )

  val name: String
    get() = info.name

  val aliases: List<String>
    get() = info.aliases

  val description: String?
    get() = info.description

  val type: ProjectFlagType
    get() = info.type

  val required: Boolean
    get() = info.required

  val defaultValue: ProjectFlagValue
    get() = info.defaultValue

  /** Static utilities for [ProjectFlagDefinition]. */
  companion object {
    /**
     * Create a new project flag definition.
     *
     * @param name Name of the flag.
     * @param aliases Alternate names for the flag.
     * @param description Human-readable description for the flag.
     * @param type Type of data held by the flag.
     * @param required Whether the flag is required.
     * @param defaultValue Default value for the flag, if any.
     * @return Flag definition record.
     */
    @JvmStatic fun of(
      name: String,
      aliases: List<String>? = null,
      description: String? = null,
      type: ProjectFlagType = ProjectFlagType.STRING,
      required: Boolean = false,
      defaultValue: ProjectFlagValue = ProjectFlagValue.NoValue,
    ): ProjectFlagDefinition =
      ProjectFlagDefinition(
        FlagInfo(
          name = name,
          aliases = aliases ?: emptyList(),
          description = description,
          type = type,
          required = required,
          defaultValue = defaultValue,
        ),
      )

    /** @return Flag definition from a decoded [FlagInfo]. */
    @JvmStatic fun from(model: FlagInfo): ProjectFlagDefinition = ProjectFlagDefinition(model)
  }
}
