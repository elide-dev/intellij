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
 * ## Project Flag Type
 *
 * Enumerates the types of project flags which may be defined; the "type" of a project flag
 * determines the underlying data type, which is paired here as well for use in generic cases.
 */
enum class ProjectFlagType {
  /**
   * Boolean switch field that accepts true/false (or aliases like 'on/off') and which allows
   * negation.
   */
  BOOLEAN,

  /** String field that accepts simple string values. */
  STRING,

  /**
   * Enumeration field that accepts string values to select from a pre-defined list of instances.
   */
  ENUM,

  /** Integer field that accepts whole number values. */
  INTEGER,

  /** Float field that accepts floating-point number values. */
  FLOAT,
}
