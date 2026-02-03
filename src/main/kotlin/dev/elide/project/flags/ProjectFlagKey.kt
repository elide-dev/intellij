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

/**
 * ## Project Flag Key
 *
 * Describes a key which carries an identity for a given "project" flag; such flags are defined
 * explicitly within the scope of a given Elide project. See [ProjectFlag].
 *
 * @see ProjectFlag concept of project flags
 */
sealed interface ProjectFlagKey : Comparable<ProjectFlagKey> {
  /** Name of this flag without any preceding flag symbols. */
  val strippedName: String

  override fun compareTo(other: ProjectFlagKey): Int {
    return strippedName.compareTo(other.strippedName)
  }

  @JvmInline
  private value class StringFlagKey private constructor(val name: String) : ProjectFlagKey {
    override val strippedName: String
      get() = name.replace("--", "").replace("-", "")

    companion object {
      @JvmStatic fun of(name: String): ProjectFlagKey = StringFlagKey(name.trim())
    }
  }

  companion object {
    @JvmStatic fun of(name: String): ProjectFlagKey = StringFlagKey.of(name)
  }
}
