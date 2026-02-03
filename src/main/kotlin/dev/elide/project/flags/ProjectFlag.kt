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

@file:Suppress("unused")

package dev.elide.project.flags

/**
 * # Project Flag
 *
 * Defines flags which exist within the context of a specific Elide project; flags can be used to
 * create conditional state within a project's build file, or pass inputs which are used within the
 * project's scripts.
 *
 * Project flags are always defined explicitly, values for which are provided during the evaluation
 * stage of the project `elide.pkl` file(s).
 */
sealed interface ProjectFlag {
  val key: ProjectFlagKey
  val value: ProjectFlagValue

  val asString: String
    get() = value.asString

  /**
   * ## Project Flag: Keyed
   *
   * Holds a [key] and associated [value] as a project flag entry.
   */
  @JvmRecord
  data class KeyedFlag(
    override val key: ProjectFlagKey,
    override val value: ProjectFlagValue,
  ) : ProjectFlag

  companion object {
    @JvmStatic fun of(key: ProjectFlagKey, value: ProjectFlagValue): ProjectFlag =
      KeyedFlag(key = key, value = value)
  }
}
