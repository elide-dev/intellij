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

import dev.elide.tooling.manifest.android.AndroidApk
import dev.elide.tooling.manifest.extensions.ExtensionArchive
import dev.elide.tooling.manifest.base.PackageVersion
import dev.elide.tooling.manifest.jvm.Jar
import dev.elide.tooling.manifest.sources.SourceSetSpec
import dev.elide.tooling.manifest.sources.ResourceRoot
import dev.elide.tooling.manifest.jvm.JvmTargetLevel
import dev.elide.tooling.manifest.jvm.MavenPackageDependency
import dev.elide.tooling.manifest.jvm.MavenPackageSpec
import dev.elide.tooling.manifest.kotlin.KotlinLanguageTargets
import dev.elide.tooling.manifest.project.BuildExtension
import dev.elide.tooling.manifest.project.ExtensionSpec
import dev.elide.tooling.manifest.sources.ResourceEntry
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

    val main = assertIs<SourceSetSpec>(manifest.sources.getValue("main"))
    assertEquals(SourceSetType.Source, main.type)
    assertEquals(listOf("src/main/kotlin/**/*.kt"), main.paths)
    assertEquals(listOf(ResourceRoot(from = "src/main/resources/**", into = "res")), main.resources)

    // a bare glob carries no options, and takes the structural form's defaults
    val test = manifest.sources.getValue("test")
    assertEquals(SourceSetType.Source, test.type)
    assertEquals(listOf("src/test/kotlin/**/*.kt"), test.paths)
    assertTrue(test.resources.isEmpty())

    val samples = manifest.sources.getValue("samples")
    assertEquals(SourceSetType.Example, samples.type)
    assertEquals(listOf("samples/**/*.kt"), samples.paths)
  }

  @Test fun `current source defaults honor explicit types and dependency overrides`() {
    val inferred = SourceSetSpec()
    assertEquals(SourceSetType.Test, inferred.effectiveType("tests"))
    assertEquals(SourceSetType.Example, inferred.effectiveType("examples"))
    assertEquals(listOf("main"), inferred.dependencies("tests"))
    assertEquals(emptyList(), SourceSetSpec(dependsOn = emptyList()).dependencies("tests"))
    assertEquals(SourceSetType.Source, SourceSetSpec(type = SourceSetType.Source).effectiveType("test"))
    assertEquals(listOf("shared"), SourceSetSpec(dependsOn = listOf("shared")).dependencies("main"))
  }

  @Test fun `decodes current resource declarations without legacy translation`() {
    val current = ElideManifests.parse(
      """{"sources":{"main":{"@type":"elide.sources.SourceSetSpec","resources":[
        {"@type":"elide.sources.ResourceRoot","from":"assets","into":"static"},
        {"@type":"elide.sources.ResourceEntry.OfString","value":"config"}
      ]}}}""",
    )
    assertEquals(listOf("assets", "config"), current.sources.getValue("main").resources)
  }

  @Test fun `decodes artifacts polymorphically`() {
    val jar = assertIs<Jar>(manifest.artifacts.getValue("app"))
    assertEquals("fixture.jar", jar.name)
    assertEquals("fixture.MainKt", jar.main)
    assertEquals(listOf("main"), jar.dependsOn)
  }

  @Test fun `decodes legacy resource maps on all resource entry owners`() {
    val legacy = ElideManifests.parse(
      """{
        "sources": {
          "jvm": {"@type":"elide.jvm.JvmSourceSetSpec","resources":{"classes":"jvm/**"}},
          "base": {"@type":"elide.sources.SourceSetSpec","resources":{"":"shared/**"}},
          "empty": {"@type":"elide.jvm.JvmSourceSetSpec","resources":{}}
        },
        "artifacts": {
          "app": {"@type":"elide.jvm.Jar","resources":{"META-INF":"metadata/**","assets":"assets/**"}},
          "empty": {"@type":"elide.jvm.Jar","resources":{}}
        }
      }""",
    )
    assertEquals(
      listOf(ResourceRoot(from = "jvm/**", into = "classes")),
      assertIs<SourceSetSpec>(legacy.sources.getValue("jvm")).resources,
    )
    assertEquals(
      listOf(ResourceRoot(from = "shared/**", into = "")),
      assertIs<SourceSetSpec>(legacy.sources.getValue("base")).resources,
    )
    assertEquals(listOf("jvm/**"), legacy.sources.getValue("jvm").resources)
    assertTrue(assertIs<SourceSetSpec>(legacy.sources.getValue("empty")).resources.isEmpty())
    assertEquals(
      listOf(ResourceRoot(from = "metadata/**", into = "META-INF"), ResourceRoot(from = "assets/**", into = "assets")),
      assertIs<Jar>(legacy.artifacts.getValue("app")).resources,
    )
    assertTrue(assertIs<Jar>(legacy.artifacts.getValue("empty")).resources.isEmpty())
  }

  @Test fun `resource normalization preserves current declarations and unrelated artifact subtypes`() {
    val current = ElideManifests.parse(
      """{
        "sources": {
          "jvm": {"@type":"elide.jvm.JvmSourceSetSpec","resources":[
            {"@type":"elide.sources.ResourceEntry.OfString","value":"config"}
          ]}
        },
        "artifacts": {
          "app": {"@type":"elide.jvm.Jar","resources":[
            {"@type":"elide.sources.ResourceRoot","from":"assets","into":"static"},
            {"@type":"elide.sources.ResourceEntry.OfString","value":"config"}
          ]},
          "android": {"@type":"elide.android.AndroidApk","resources":["src/main/res"]}
        }
      }""",
    )
    assertEquals(listOf("config"), current.sources.getValue("jvm").resources)
    assertEquals(
      listOf(ResourceRoot(from = "assets", into = "static"), ResourceEntry.OfString("config")),
      assertIs<Jar>(current.artifacts.getValue("app")).resources,
    )
    assertEquals(listOf("src/main/res"), assertIs<AndroidApk>(current.artifacts.getValue("android")).resources)
  }

  @Test fun `decodes current build extension CLI export`() {
    // Captured with `elide project export` in ../elide-vscode/samples/build-extension.
    val exported = requireNotNull(
      javaClass.getResourceAsStream("/manifest/build-extension-export.json"),
    ).use { ElideManifests.parse(it.reader().readText()) }
    assertEquals("build-extension-sample", exported.name)
    val extension = assertIs<ExtensionSpec>(exported.extensions[0])
    assertEquals("tools/tasks.ts", extension.from)
    assertEquals(mapOf("greeting" to "Hello"), extension.config)
    assertEquals("tools/javascript.mjs", assertIs<BuildExtension.OfString>(exported.extensions[1]).value)
    val archive = assertIs<ExtensionArchive>(exported.artifacts.getValue("tasks"))
    assertEquals("tools/tasks.ts", archive.entrypoint)
    assertEquals(listOf("tools/**/*.ts"), archive.srcs)
  }

  @Test fun `extension config preserves JSON types exact numbers and arbitrary nested objects`() {
    val project = ElideManifests.parse(
      """{"extensions":[{
        "@type":"elide.project.ExtensionSpec","from":"tools/tasks.ts","config":{
          "enabled":true,"disabled":false,"text":"true","nothing":null,
          "integer":9007199254740993,"huge":9223372036854775808,
          "decimal":0.12345678901234567890123456789,"exponent":1.234567890123456789e100,
          "items":[true,null,42,{"label":"nested","values":[false,"42"]}],
          "object":{"@type":"elide.jvm.JvmSourceSetSpec","resources":{"res":"input/**"}},
          "emptyArray":[],"emptyObject":{}
        }
      }]}""",
    )
    val config = assertIs<ExtensionSpec>(project.extensions.single()).config
    assertEquals(true, config["enabled"])
    assertEquals(false, config["disabled"])
    assertEquals("true", config["text"])
    assertTrue(config.containsKey("nothing"))
    assertNull(config["nothing"])
    assertEquals(9007199254740993L, config["integer"])
    assertEquals("9223372036854775808".toBigInteger(), config["huge"])
    assertEquals("0.12345678901234567890123456789".toBigDecimal(), config["decimal"])
    assertEquals("1.234567890123456789e100".toBigDecimal(), config["exponent"])
    assertEquals(
      listOf(true, null, 42L, mapOf("label" to "nested", "values" to listOf(false, "42"))),
      config["items"],
    )
    assertEquals(
      mapOf("@type" to "elide.jvm.JvmSourceSetSpec", "resources" to mapOf("res" to "input/**")),
      config["object"],
    )
    assertEquals(emptyList<Any?>(), config["emptyArray"])
    assertEquals(emptyMap<String, Any?>(), config["emptyObject"])
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
