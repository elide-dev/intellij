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

import dev.elide.project.manifest.ElideManifests
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Pins the payload attached to the resolved project node.
 *
 * External system `DataNode` payloads are serialized by the platform, so this must stay a plain serializable DTO
 * rather than the generated (non-serializable) manifest model.
 */
class ElideProjectDataTest {
  private val manifest = ElideManifests.parse(
    requireNotNull(ElideProjectDataTest::class.java.getResourceAsStream("/manifest/elide-manifest.json"))
      .use { it.reader().readText() },
  )

  private val data = ElideProjectData.from(manifest)

  @Test fun `collects entrypoints scripts and jvm main from the manifest`() {
    assertEquals(listOf("src/main/kotlin/Main.kt"), data.entrypoints)
    assertEquals(listOf("hello"), data.scripts)
    assertEquals("fixture.MainKt", data.jvmMainClass)
  }

  @Test fun `collects kotlin facet settings`() {
    val kotlin = assertNotNull(data.kotlin)

    assertEquals("2.2", kotlin.apiLevel)
    assertEquals("2.2", kotlin.languageLevel)
    assertEquals(
      listOf(
        "-opt-in=kotlin.ExperimentalStdlibApi",
        "-progressive",
        "-Werror",
        "-Xcontext-parameters",
      ),
      kotlin.compilerArguments,
    )
  }

  @Test fun `round trips through java serialization`() {
    val bytes = ByteArrayOutputStream().also { out ->
      ObjectOutputStream(out).use { it.writeObject(data) }
    }.toByteArray()

    val restored = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() }

    assertEquals(data, restored)
  }
}
