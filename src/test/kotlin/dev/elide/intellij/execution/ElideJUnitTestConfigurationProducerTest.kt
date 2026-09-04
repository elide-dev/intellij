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
package dev.elide.intellij.execution

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the `--test-name-pattern` regexes handed to `elide test`, which `find()`-matches them against JVM test ids of
 * the form `pkg.Class#method` (nested classes: `pkg.Outer$Inner#method`).
 */
class ElideJUnitTestConfigurationProducerTest {
  @Test fun `method pattern matches exactly one test id`() {
    assertEquals(
      "^\\Qprobe.ProbeTest\\E#\\QaddsNumbers\\E$",
      ElideJUnitTestConfigurationProducer.testNamePattern("probe.ProbeTest", "addsNumbers"),
    )
  }

  @Test fun `class pattern covers nested classes via the dollar separator`() {
    assertEquals(
      "^\\Qprobe.OuterTest\\E[#$]",
      ElideJUnitTestConfigurationProducer.testNamePattern("probe.OuterTest", null),
    )
  }

  @Test fun `nested class names keep their dollar sign quoted`() {
    assertEquals(
      "^\\Qprobe.OuterTest\$InnerCase\\E#\\QinnerOne\\E$",
      ElideJUnitTestConfigurationProducer.testNamePattern("probe.OuterTest\$InnerCase", "innerOne"),
    )
  }
}
