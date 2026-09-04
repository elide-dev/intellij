/*
 * Copyright (c) 2024-2025 Elide Technologies, Inc.
 *
 * Licensed under the MIT license (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *   https://opensource.org/license/mit/
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under the License.
 */
package dev.elide.intellij.project.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Pins the JDK version parsing used to match manifest JVM targets against the IDE's JDK table. */
class ElideJdkSelectorTest {
  @Test fun `parses modern version strings`() {
    assertEquals(21, ElideJdkSelector.parseJdkVersion("21", "JDK"))
    assertEquals(21, ElideJdkSelector.parseJdkVersion("21.0.1", "JDK"))
    assertEquals(21, ElideJdkSelector.parseJdkVersion("openjdk 21.0.1", "JDK"))
    assertEquals(21, ElideJdkSelector.parseJdkVersion("java version \"21\"", "JDK"))
    assertEquals(21, ElideJdkSelector.parseJdkVersion("21.0.1", "JDK"))
    // regression: a `\b1\.` pattern reads the `1.0` inside `21.1.0` as the feature version
    assertEquals(21, ElideJdkSelector.parseJdkVersion("21.1.0", "JDK"))
    assertEquals(17, ElideJdkSelector.parseJdkVersion("17.1.2", "JDK"))
    assertEquals(21, ElideJdkSelector.parseJdkVersion("openjdk 21.1.0", "temurin-21"))
    assertEquals(11, ElideJdkSelector.parseJdkVersion("11.0.22", "JDK"))
  }

  @Test fun `parses legacy version strings`() {
    // regression: the generic pattern read `1.8.0_292` as version 1, disqualifying every JDK 8 installation
    assertEquals(8, ElideJdkSelector.parseJdkVersion("1.8.0_292", "corretto-1.8"))
    assertEquals(8, ElideJdkSelector.parseJdkVersion("java version \"1.8.0_292\"", "jdk"))
    assertEquals(9, ElideJdkSelector.parseJdkVersion("1.9", "jdk"))
  }

  @Test fun `falls back to the sdk name`() {
    assertEquals(21, ElideJdkSelector.parseJdkVersion(null, "temurin-21"))
    assertEquals(17, ElideJdkSelector.parseJdkVersion(null, "corretto 17"))
    assertEquals(23, ElideJdkSelector.parseJdkVersion(null, "GraalVM23"))
    assertEquals(8, ElideJdkSelector.parseJdkVersion(null, "1.8"))
    assertEquals(21, ElideJdkSelector.parseJdkVersion("no digits here", "jbr-21"))
  }

  @Test fun `returns null for unparseable input`() {
    assertNull(ElideJdkSelector.parseJdkVersion(null, "mystery sdk"))
    assertNull(ElideJdkSelector.parseJdkVersion("", ""))
  }
}
