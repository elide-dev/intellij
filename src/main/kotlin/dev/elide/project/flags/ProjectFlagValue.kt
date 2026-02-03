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

import kotlinx.serialization.Serializable

/**
 * ## Project Flag Value
 *
 * Describes a value which is assigned to a given [ProjectFlag], and associated with a
 * [ProjectFlagKey]; values must be parsable from simple strings. See [ProjectFlag].
 *
 * @see ProjectFlag concept of project flags
 */
@Serializable
sealed interface ProjectFlagValue {
  val asString: String

  /**
   * ### Project Flag: No Value
   *
   * Sentinel which indicates that no value is set for a given flag.
   */
  @Serializable
  data object NoValue : ProjectFlagValue {
    override val asString: String
      get() = ""
  }

  /**
   * ### Project Flag: Boolean Value
   *
   * Boolean-type value.
   *
   * @property value Raw value of this flag.
   */
  @Serializable
  @JvmInline
  value class BooleanValue(val value: Boolean) : ProjectFlagValue {
    override val asString: String
      get() = value.toString()
  }

  /**
   * ### Project Flag: String-type Value
   *
   * String-type value.
   *
   * @property value Raw value of this flag.
   */
  @Serializable
  @JvmInline
  value class StringValue(val value: String) : ProjectFlagValue {
    override val asString: String
      get() = value
  }
}
