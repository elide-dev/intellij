package dev.elide.intellij.android.compose

import dev.elide.intellij.project.model.ElideProjectInfo
import dev.elide.tooling.manifest.android.AndroidAar
import dev.elide.tooling.manifest.project.ProjectModule
import dev.elide.tooling.manifest.sources.SourceSetSpec
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComposePreviewTest {
  @Test fun `pinned runtime accepts exactly supported native hosts`() {
    assertEquals("mac-arm", ComposeRuntime.host("Mac OS X", "aarch64").classifier)
    assertEquals("mac", ComposeRuntime.host("Mac OS X", "x86_64").classifier)
    assertEquals("linux", ComposeRuntime.host("Linux", "amd64").classifier)
    assertEquals("win", ComposeRuntime.host("Windows 11", "amd64").classifier)
    assertFailsWith<IllegalStateException> { ComposeRuntime.host("Linux", "aarch64") }
    assertFailsWith<IllegalStateException> { ComposeRuntime.host("Windows 11", "x86") }
  }

  @Test fun `native archive cannot escape staging directory`() {
    val root = Path.of(System.getProperty("java.io.tmpdir"), "compose-staging").toAbsolutePath()
    assertEquals(root.resolve("data/framework.jar"), ComposeRuntime.safeZipPath(root, "data/framework.jar"))
    for (entry in listOf("../outside", "data/../../outside", "..\\outside", root.resolveSibling("outside").toString())) {
      assertFailsWith<IllegalArgumentException> { ComposeRuntime.safeZipPath(root, entry) }
    }
  }

  @Test fun `workspace archive overlay keeps resources as one absolute classpath entry without stale bytecode`() {
    val root = Files.createTempDirectory("compose archive test ")
    try {
      val archive = root.resolve(".dev/android/library/classes.jar")
      Files.createDirectories(archive.parent)
      ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
        for ((name, value) in mapOf("example/Screen.class" to "stale", "META-INF/main.kotlin_module" to "stale",
          "assets/value.txt" to "resource", "META-INF/services/example.Service" to "example.Provider")) {
          zip.putNextEntry(ZipEntry(name))
          zip.write(value.toByteArray())
          zip.closeEntry()
        }
      }
      val original = root.resolve(".dev/jvm/classes/main/kotlin")
      Files.createDirectories(original.parent)
      Files.writeString(original.parent.resolve("kotlinc.args"), "-classpath\n$archive\n-d\n$original\n")
      val output = Files.createDirectories(root.resolve("private/classes"))
      val manifest = ProjectModule(kotlin = null, sources = mapOf("main" to SourceSetSpec(paths = listOf("src/main/**"))),
        artifacts = mapOf("library" to AndroidAar(sources = listOf("main"))))
      val module = ComposeModule(ComposeProject(root, ElideProjectInfo(), manifest), "main", 0)
      val inputs = ComposeInputs(emptyList(), listOf(original.toString()), "example", "", listOf(module))
      val entries = ComposeArtifacts.overlays(inputs, mapOf(original.toString() to output), root.resolve("private")).getValue(archive.toString())
      assertEquals(2, entries.size)
      assertEquals(output, entries.first())
      assertTrue(entries.last().isAbsolute)
      ZipFile(entries.last().toFile()).use { resources ->
        assertEquals(setOf("assets/value.txt", "META-INF/services/example.Service"), resources.entries().asSequence().map { it.name }.toSet())
        assertEquals("resource", resources.getInputStream(resources.getEntry("assets/value.txt")).bufferedReader().use { it.readText() })
      }
    } finally { root.toFile().deleteRecursively() }
  }

  @Test fun `runtime chooses shipped Maven versions rather than lexicographic versions`() {
    assertTrue(ComposeClasspath.compareVersions("1.10", "1.9") > 0)
    assertTrue(ComposeClasspath.compareVersions("2.0-rc1", "2.0") < 0)
    assertTrue(ComposeClasspath.compareVersions("2.0-sp1", "2.0") > 0)
    assertEquals(0, ComposeClasspath.compareVersions("1.0.0-final", "1"))
    assertEquals(0, ComposeClasspath.compareVersions("1.0-a1", "1.0-alpha1"))
    val root = Path.of(System.getProperty("java.io.tmpdir"), "compose-owner").toAbsolutePath()
    val old = root.resolve(".dev/dependencies/m2/example/widget/1.9/widget-1.9.jar").toString()
    val newest = root.resolve(".dev/dependencies/m2/example/widget/1.10/widget-1.10.jar").toString()
    assertEquals(listOf(newest), ComposeClasspath.select(listOf(old, newest), root))
  }

  @Test fun `Komodo inline sources resolve exploded AARs from their Maven coordinates`() {
    val root = Files.createTempDirectory("compose-inline-sources")
    try {
      val repository = root.resolve("custom-repository")
      val sources = repository.resolve("androidx/compose/runtime/runtime-android/1.7.8/runtime-android-1.7.8-sources.jar")
      Files.createDirectories(sources.parent)
      Files.write(sources, byteArrayOf())
      val classes = root.resolve("app/.dev/android/libraries/androidx/compose/runtime/runtime-android/1.7.8/classes.jar")
      val absent = root.resolve("app/.dev/android/libraries/androidx/compose/runtime/runtime-android/1.8.0/classes.jar")
      assertEquals(listOf(sources.toString()), ComposeClasspath.sourceArchives(listOf(classes.toString(), absent.toString()), setOf(repository)))
    } finally { root.toFile().deleteRecursively() }
  }

  @Test fun `source ownership respects directory boundaries and glob selectors`() {
    val root = Path.of(System.getProperty("java.io.tmpdir"), "compose-workspace").toAbsolutePath()
    assertTrue(ComposeInputResolver.matches(root, "src/main", root.resolve("src/main/kotlin/Screen.kt")))
    assertFalse(ComposeInputResolver.matches(root, "src/main", root.resolve("src/mainOther/Screen.kt")))
    assertTrue(ComposeInputResolver.matches(root, "src/**/*.kt", root.resolve("src/main/Screen.kt")))
    assertFalse(ComposeInputResolver.matches(root, "src/**/*.kt", root.resolve("src/main/Screen.java")))
  }

  @Test fun `manifest namespace parser refuses external entities`() {
    val root = Files.createTempDirectory("compose-manifest-test")
    try {
      val file = root.resolve("AndroidManifest.xml")
      Files.writeString(file, "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"example.preview\"/>")
      assertEquals("example.preview", ComposeInputResolver.manifestPackage(file))
      Files.writeString(file, "<!DOCTYPE manifest [<!ENTITY secret SYSTEM 'file:///etc/passwd'>]><manifest package='&secret;'/>")
      assertFailsWith<org.xml.sax.SAXParseException> { ComposeInputResolver.manifestPackage(file) }
    } finally { root.toFile().deleteRecursively() }
  }
}
