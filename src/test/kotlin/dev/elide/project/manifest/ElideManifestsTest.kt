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
package dev.elide.project.manifest

import dev.elide.tooling.manifest.base.PackageVersion
import dev.elide.tooling.manifest.jvm.Jar
import dev.elide.tooling.manifest.jvm.JvmSourceSetSpec
import dev.elide.tooling.manifest.jvm.JvmTargetLevel
import dev.elide.tooling.manifest.jvm.MavenPackageDependency
import dev.elide.tooling.manifest.jvm.MavenPackageSpec
import dev.elide.tooling.manifest.kotlin.KotlinLanguageTargets
import dev.elide.tooling.manifest.sources.SourceSetType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the contract the plugin depends on: the JSON `elide manifest` prints decodes into the generated model.
 *
 * The fixture in `src/test/resources/manifest` is verbatim `elide manifest` output for the manifest beside it, so a
 * regenerated model which stops understanding the CLI's discriminators, union encodings, or property names fails here
 * rather than at project import time.
 */
class ElideManifestsTest {
  private val manifestJson = requireNotNull(
    ElideManifestsTest::class.java.getResourceAsStream("/manifest/elide-manifest.json"),
  ).use { it.reader().readText() }

  private val manifest = ElideManifests.parse(manifestJson)

  @Test fun `decodes project coordinates`() {
    assertEquals("fixture", manifest.name)
    assertEquals("1.0.0", manifest.version)
    assertEquals("Manifest decoding fixture", manifest.description)
    assertEquals(listOf("src/main/kotlin/Main.kt"), manifest.entrypoint)
    assertEquals(mapOf("hello" to "echo hi"), manifest.scripts)
  }

  @Test fun `decodes maven dependencies in both declared shapes`() {
    val maven = manifest.dependencies.maven
    assertEquals("custom/m2", maven.localRepository)
    assertEquals(2, maven.packages.size)

    val coordinate = assertIs<MavenPackageDependency.OfString>(maven.packages[0])
    assertEquals("com.google.guava:guava:32.1.3-jre", coordinate.value)

    val structural = assertIs<MavenPackageSpec>(maven.packages[1])
    assertEquals("org.slf4j", structural.group)
    assertEquals("slf4j-api", structural.name)
    assertEquals("2.0.13", assertIs<PackageVersion.OfString>(structural.version).value)
    assertNull(structural.classifier)

    val testPackage = assertIs<MavenPackageDependency.OfString>(maven.testPackages.single())
    assertEquals("org.junit.jupiter:junit-jupiter:5.11.0", testPackage.value)
  }

  @Test fun `decodes jvm settings and target levels`() {
    val jvm = requireNotNull(manifest.jvm)
    assertEquals("fixture.MainKt", jvm.main)
    assertEquals(listOf("-Xmx2g"), jvm.flags)
    assertEquals(JvmTargetLevel.OfInt(21), jvm.target)
    assertEquals(21, jvm.target.majorVersionOrNull())
    assertEquals(21, jvm.java.release.majorVersionOrNull())
    assertEquals(17, jvm.java.source.majorVersionOrNull())
  }

  @Test fun `unset target levels resolve to no version`() {
    assertNull(JvmTargetLevel.Auto.majorVersionOrNull())
    assertNull(JvmTargetLevel.Latest.majorVersionOrNull())
    assertNull(JvmTargetLevel.Stable.majorVersionOrNull())
    assertEquals(8, JvmTargetLevel.OfFloat(1.8).majorVersionOrNull())
  }

  @Test fun `decodes kotlin settings into compiler arguments`() {
    val kotlin = requireNotNull(manifest.kotlin)
    assertEquals(KotlinLanguageTargets.V2_2, kotlin.apiLevel)
    assertEquals("2.2", kotlin.apiLevel.argValue)
    assertEquals("2.2", requireNotNull(kotlin.languageLevel.explicitOrNull()).argValue)

    assertEquals(
      listOf("-opt-in=kotlin.ExperimentalStdlibApi", "-progressive", "-Werror", "-Xcontext-parameters"),
      kotlin.compilerOptions.collect().toList(),
    )
  }

  @Test fun `auto language levels are not passed to the compiler`() {
    assertNull(KotlinLanguageTargets.VAuto.explicitOrNull())
    assertEquals("auto", KotlinLanguageTargets.VAuto.argValue)
  }

  @Test fun `decodes every source set shape the schema admits`() {
    assertEquals(setOf("main", "test", "samples"), manifest.sources.keys)

    val main = assertIs<JvmSourceSetSpec>(manifest.sources.getValue("main"))
    assertEquals(SourceSetType.Source, main.type)
    assertEquals(listOf("src/main/kotlin/**/*.kt"), main.paths)
    assertEquals(mapOf("res" to "src/main/resources/**"), main.resources)

    // a bare glob carries no options, and takes the structural form's defaults
    val test = manifest.sources.getValue("test")
    assertEquals(SourceSetType.Source, test.type)
    assertEquals(listOf("src/test/kotlin/**/*.kt"), test.paths)
    assertTrue(test.resources.isEmpty())

    val samples = manifest.sources.getValue("samples")
    assertEquals(SourceSetType.Example, samples.type)
    assertEquals(listOf("samples/**/*.kt"), samples.paths)
  }

  @Test fun `decodes artifacts polymorphically`() {
    val jar = assertIs<Jar>(manifest.artifacts.getValue("app"))
    assertEquals("fixture.jar", jar.name)
    assertEquals("fixture.MainKt", jar.main)
    assertEquals(listOf("main"), jar.dependsOn)
  }

  @Test fun `properties a newer elide adds are ignored`() {
    val forwardCompatible = manifestJson.replaceFirst("""{""", """{ "propertyFromTheFuture": {"nested": [1]},""")
    assertEquals(manifest, ElideManifests.parse(forwardCompatible))
  }

  @Test fun `undecodable output fails loudly`() {
    val failure = assertFailsWith<IllegalStateException> { ElideManifests.parse("not a manifest") }
    assertTrue(failure.message!!.startsWith("Failed to parse Elide project manifest"))
  }
}
