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
package dev.elide.intellij.psi

import com.intellij.testFramework.junit5.TestApplication
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@TestApplication
class ElidePklModulesTest {
  @Test
  fun `every bundled schema module resolves through the elide scheme`() {
    val modules = Files.list(Path.of("src/main/pkl")).use { files -> files.map { it.fileName.toString() }.toList() }
    for (module in modules) {
      val file = assertNotNull(ElidePklModuleResolver.find("elide:$module"), module)
      assertEquals(module, file.name)
    }
  }

  @Test
  fun `a leading slash names the same module`() {
    assertEquals(ElidePklModuleResolver.find("elide:project.pkl"), ElidePklModuleResolver.find("elide:/project.pkl"))
  }

  @Test
  fun `other schemes and names outside the schema are left alone`() {
    assertNull(ElidePklModuleResolver.find("modulepath:/project.pkl"))
    assertNull(ElidePklModuleResolver.find("elide:Missing.pkl"))
    assertNull(ElidePklModuleResolver.find("elide:../META-INF/plugin.xml"))
    assertNull(ElidePklModuleResolver.find("elide:project"))
  }
}
