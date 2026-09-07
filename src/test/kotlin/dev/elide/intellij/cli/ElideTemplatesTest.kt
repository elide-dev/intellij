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
package dev.elide.intellij.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Decoding and validation of the template catalog `elide init --templates --json` prints.
 *
 * The fixture is verbatim output of that command, so a change to the CLI's listing shape shows up here.
 */
class ElideTemplatesTest {
  private val catalog: List<ElideTemplate> by lazy {
    val json = checkNotNull(javaClass.getResourceAsStream("/templates/catalog.json")) {
      "missing template catalog fixture"
    }

    ElideTemplates.parse(json.reader().readText())
  }

  private fun template(id: String): ElideTemplate =
    assertNotNull(catalog.find { it.id == id }, "no `$id` template in the catalog")

  private fun parameter(template: ElideTemplate, id: String): ElideTemplateParameter =
    assertNotNull(template.parameters.find { it.id == id }, "no `$id` parameter in `${template.id}`")

  @Test fun `decodes every shipped template`() {
    assertEquals(listOf("java", "ktjvm", "web-demo", "web-simple"), catalog.map { it.id }.sorted())
    assertEquals("Kotlin Hello World Project", template("ktjvm").title)
  }

  @Test fun `decodes optional blocks with their own parameters and defaults`() {
    val ktjvm = template("ktjvm")

    val tests = assertNotNull(ktjvm.blocks.find { it.id == "tests" })
    assertTrue(tests.default, "the `tests` block is generated unless the user opts out")
    assertEquals(listOf("test"), tests.actions)

    val mcp = assertNotNull(ktjvm.blocks.find { it.id == "mcp" })
    assertEquals(false, mcp.default)
    assertTrue(parameter(mcp, "mcp_elide").isBoolean, "a Boolean parameter must render as a flag")
  }

  @Test fun `carries the attributes answers are validated against`() {
    val ktjvm = template("ktjvm")

    assertEquals(listOf(ElideTemplates.ATTRIBUTE_JAVA_PACKAGE), parameter(ktjvm, "package").attributes)
    assertEquals(listOf(ElideTemplates.ATTRIBUTE_JAVA_CLASS), parameter(ktjvm, "main_class").attributes)
    assertEquals("com.example", parameter(ktjvm, "package").default)
  }

  @Test fun `ignores fields a newer Elide adds`() {
    val listing = """[{"id":"future","title":"Future","tags":["experimental"],"parameters":[]}]"""

    assertEquals(listOf("future"), ElideTemplates.parse(listing).map { it.id })
  }

  @Test fun `rejects package names the CLI would reject`() {
    val pkg = parameter(template("ktjvm"), "package")

    assertNull(ElideTemplates.validate(pkg, "com.example"))
    assertNull(ElideTemplates.validate(pkg, "app"))
    assertNotNull(ElideTemplates.validate(pkg, "Com.Example"))
    assertNotNull(ElideTemplates.validate(pkg, "com..example"))
    assertNotNull(ElideTemplates.validate(pkg, ""))
  }

  @Test fun `rejects class names the CLI would reject`() {
    val mainClass = parameter(template("ktjvm"), "main_class")

    assertNull(ElideTemplates.validate(mainClass, "Hello"))
    assertNull(ElideTemplates.validate(mainClass, "_H1"))
    assertNotNull(ElideTemplates.validate(mainClass, "1Hello"))
    assertNotNull(ElideTemplates.validate(mainClass, "my.Class"))
  }

  @Test fun `accepts anything for an unconstrained parameter`() {
    val greeting = parameter(template("ktjvm"), "greeting")

    assertNull(ElideTemplates.validate(greeting, "Hello Elide!"))
    assertNull(ElideTemplates.validate(greeting, ""))
  }
}
