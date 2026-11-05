package dev.elide.intellij.android

import dev.elide.intellij.project.model.ElideBuildTaskInfo
import dev.elide.tooling.manifest.android.AndroidAab
import dev.elide.tooling.manifest.android.AndroidAar
import dev.elide.tooling.manifest.android.AndroidApk
import dev.elide.tooling.manifest.android.AndroidInstrumentation
import dev.elide.tooling.manifest.android.AndroidSettings
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ElideAndroidToolsTest {
  @Test fun `explicit invalid SDK never silently falls back`() {
    val root = Path.of("/workspace/app")
    val available = setOf(Path.of("/sdk/env"), root.resolve("manifest-sdk"))
    assertNull(ElideAndroidTools.resolveSdk("missing", "manifest-sdk", root,
      mapOf("ANDROID_HOME" to "/sdk/env"), "/home/test", "Linux", available::contains))
    assertEquals(root.resolve("manifest-sdk"), ElideAndroidTools.resolveSdk(null, "manifest-sdk", root,
      mapOf("ANDROID_HOME" to "/sdk/env"), "/home/test", "Linux", available::contains))
    assertEquals(Path.of("/sdk/env"), ElideAndroidTools.resolveSdk("/sdk/env", "manifest-sdk", root,
      emptyMap(), "/home/test", "Linux", available::contains))
  }

  @Test fun `SDK discovery respects environment then platform conventions`() {
    val root = Path.of("/project")
    val mac = Path.of("/home/test/Library/Android/sdk")
    assertEquals(mac, ElideAndroidTools.resolveSdk(null, null, root,
      mapOf("ANDROID_HOME" to "/missing"), "/home/test", "Mac OS X") { it == mac })
    assertEquals(Path.of("/sdk/secondary"), ElideAndroidTools.resolveSdk(null, null, root,
      mapOf("ANDROID_HOME" to "/missing", "ANDROID_SDK_ROOT" to "/sdk/secondary"), "/home/test", "Linux") { it == Path.of("/sdk/secondary") })
  }

  @Test fun `device list preserves unavailable devices and ignores daemon chatter`() {
    val devices = ElideAndroidTools.parseDevices("""
      * daemon started successfully
      List of devices attached
      emulator-5554 device product:sdk model:Pixel_6 transport_id:1
      usb-123 unauthorized usb:1
      usb-456 offline
      usb-789 no permissions (user in plugdev group)
    """.trimIndent())
    assertEquals(listOf("emulator-5554", "usb-123", "usb-456", "usb-789"), devices.map { it.serial })
    assertEquals("Pixel 6", devices.first().label)
    assertTrue(devices.first().emulator)
    assertEquals(listOf(true, false, false, false), devices.map { it.ready })
    assertEquals("no permissions", devices.last().state)
  }

  @Test fun `task availability gates APK actions and never enables bundle or library deployment`() {
    val root = Files.createTempDirectory("elide-android-model")
    try {
      val tasks = listOf("android-run-app", "android-debug-app", "android-test-app", "android-run-bundle", "android-debug-library").map { ElideBuildTaskInfo(it) }
      val info = ElideAndroidInfo.from(root, "mobile", AndroidSettings(compileSdk = 36),
        mapOf("app" to AndroidApk(name = "custom.apk", instrumentation = AndroidInstrumentation()), "bundle" to AndroidAab(), "library" to AndroidAar()), tasks)!!
      val app = info.artifacts.first()
      assertTrue(app.runnable)
      assertTrue(app.debuggable)
      assertTrue(app.instrumented)
      assertEquals(root.resolve(".dev/artifacts/custom.apk").toString(), app.output)
      assertEquals(root.resolve(".dev/artifacts/custom-mapping.txt").toString(), app.mappingFile)
      assertTrue(info.artifacts.drop(1).all { !it.runnable && !it.debuggable && !it.instrumented })
      val bytes = ByteArrayOutputStream().also { buffer -> ObjectOutputStream(buffer).use { it.writeObject(info) } }.toByteArray()
      assertEquals(info, ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() })
    } finally { Files.delete(root) }
  }

  @Test fun `source manifest debuggability and instrumentation override optimistic task listing`() {
    val root = Files.createTempDirectory("elide-android-manifest")
    val manifest = root.resolve("AndroidManifest.xml")
    try {
      Files.writeString(manifest, """<manifest xmlns:a="http://schemas.android.com/apk/res/android" package="example.app"><!-- <application a:debuggable="true"/> --><application a:debuggable="false"/><instrumentation a:name="example.Runner" a:targetPackage="example.app"/></manifest>""")
      val info = ElideAndroidInfo.from(root, "app", AndroidSettings(compileSdk = 36), mapOf("app" to AndroidApk(manifestFile = "AndroidManifest.xml")),
        listOf("android-run-app", "android-debug-app", "android-test-app").map { ElideBuildTaskInfo(it) })!!
      assertFalse(info.artifacts.single().debuggable)
      assertTrue(info.artifacts.single().instrumented)
      assertEquals("example.app", info.artifacts.single().applicationId)
      assertEquals("example.Runner", info.artifacts.single().instrumentationRunner)
    } finally { Files.deleteIfExists(manifest); Files.delete(root) }
  }
}
