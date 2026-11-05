package dev.elide.intellij.android

import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.util.xmlb.XmlSerializer
import dev.elide.intellij.project.model.ElideProjectInfo
import dev.elide.intellij.service.elideProjectIndex
import kotlin.test.assertFailsWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse

@TestApplication
class ElideAndroidDeploymentTest {
  private val projectFixture = projectFixture()
  private val project get() = projectFixture.get()

  @Test fun testRememberedMissingApkNeverSwitchesToAvailableAlternative() {
    val service = ElideAndroidDeployment(project)
    val old = ElideAndroidArtifact(name = "removed", kind = "apk", runnable = true)
    val current = ElideAndroidArtifact(name = "available", kind = "apk", runnable = true)
    val info = ElideAndroidInfo(artifacts = listOf(old, current))
    project.elideProjectIndex.update("/android", ElideProjectInfo(android = info))
    service.select(ElideAndroidDeploymentTarget("/android", info, old), "serial-123")
    project.elideProjectIndex.update("/android", ElideProjectInfo(android = info.copy(artifacts = listOf(current))))
    assertNull(service.selectedTarget())
    assertEquals("removed", service.getState().artifact)
    assertEquals("serial-123", service.getState().deviceSerial)
  }

  @Test fun testRestoredProjectApkAndDeviceIdentityRoundTrip() {
    val service = ElideAndroidDeployment(project)
    val artifact = ElideAndroidArtifact(name = "app", kind = "apk", runnable = true)
    val info = ElideAndroidInfo(artifacts = listOf(artifact))
    project.elideProjectIndex.update("/android", ElideProjectInfo(android = info))
    val target = ElideAndroidDeploymentTarget("/android", info, artifact)
    service.select(target, "emulator-5554", "phone-avd")
    val element = XmlSerializer.serialize(service.getState())
    val restored = ElideAndroidDeployment(project)
    restored.loadState(XmlSerializer.deserialize(element, ElideAndroidDeployment.Selection::class.java))
    assertEquals(target, restored.selectedTarget())
    assertEquals("emulator-5554", restored.getState().deviceSerial)
    assertEquals("phone-avd", restored.getState().deviceAvd)
    assertFalse(restored.getState().projectEmulator)
  }

  @Test fun testModelCapabilityDoesNotTreatBundleAsDeployableApk() {
    val service = ElideAndroidDeployment(project)
    val apk = ElideAndroidArtifact(name = "app", kind = "apk", runnable = true, debuggable = false)
    val info = ElideAndroidInfo(artifacts = listOf(apk, ElideAndroidArtifact(name = "bundle", kind = "aab")))
    project.elideProjectIndex.update("/android", ElideProjectInfo(android = info))
    assertEquals(listOf("app"), service.targets().map { it.artifact.name })
    val target = service.targets().single()
    service.select(target, null)
    service.validate(target, false)
    assertFailsWith<IllegalStateException> { service.validate(target, true) }
  }

  @Test fun testAndroidModelRoundTripsWithProjectIndexXml() {
    val original = ElideProjectInfo(android = ElideAndroidInfo(sdk = "sdk", compileSdk = 36,
      artifacts = listOf(ElideAndroidArtifact(name = "app", kind = "apk", runnable = true, output = "/android/.dev/artifacts/app.apk"))))
    assertEquals(original, XmlSerializer.deserialize(XmlSerializer.serialize(original), ElideProjectInfo::class.java))
  }
}
