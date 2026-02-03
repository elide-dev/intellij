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

import java.util.*

/**
 * ## Project Flags Context
 *
 * Created within the scope of a given Elide project to hold project flag definitions and to hold
 * their keys and values once such definitions have been parsed.
 */
class ProjectFlagsContext
private constructor(private val flagMap: TreeMap<ProjectFlagKey, ProjectFlag>) {
  companion object {
    // Immutable empty flag context singleton.
    val EMPTY: ProjectFlagsContext = ProjectFlagsContext(TreeMap())

    @JvmStatic fun from(flagMap: Map<ProjectFlagKey, ProjectFlagValue>): ProjectFlagsContext =
      create(
        TreeMap<ProjectFlagKey, ProjectFlag>().also {
          flagMap.forEach { (key, value) -> it[key] = ProjectFlag.of(key, value) }
        },
      )

    @JvmStatic fun create(flags: TreeMap<ProjectFlagKey, ProjectFlag>): ProjectFlagsContext =
      ProjectFlagsContext(flags)
  }

  operator fun contains(key: String): Boolean {
    return contains(ProjectFlagKey.of(key))
  }

  operator fun contains(key: ProjectFlagKey): Boolean {
    return flagMap.containsKey(key)
  }

  operator fun get(key: ProjectFlagKey): ProjectFlag {
    return flagMap[key] ?: ProjectFlag.of(key, ProjectFlagValue.NoValue)
  }
}
