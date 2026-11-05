package dev.elide.intellij.android

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager

/** IDs owned by Android tooling; no Android Studio classes or fallback implementations are bundled. */
internal enum class ElideAndroidNativeTool(val windowId: String, val label: String) {
  LOGCAT("Logcat", "Logcat"),
  DEVICES("Device Manager 2", "Device Manager / Wi-Fi Pairing"),
  INSPECTION("App Inspection", "App Inspection / Databases");

  fun open(project: Project) {
    val window = ToolWindowManager.getInstance(project).getToolWindow(windowId)
    if (window != null && window.isAvailable) {
      window.activate(null)
    } else {
      Messages.showInfoMessage(project,
        "Native $label is not available in this IDE/project.\n\n" +
          "Enable compatible Android tooling and configure its Android SDK, or use Android Studio.\n\n" +
          (if (this == INSPECTION) "IDEA's Database Tools is not a substitute for Android App Inspection.\n\n" else "") +
          "Elide does not bundle a separate implementation of this tool.",
        "Native Android Tooling Required")
    }
  }
}

class ElideAndroidNativeLogcatAction : ElideAndroidAction() {
  override fun perform(project: Project) = ElideAndroidNativeTool.LOGCAT.open(project)
}

class ElideAndroidNativeDevicesAction : ElideAndroidAction() {
  override fun perform(project: Project) = ElideAndroidNativeTool.DEVICES.open(project)
}

class ElideAndroidNativeInspectionAction : ElideAndroidAction() {
  override fun perform(project: Project) = ElideAndroidNativeTool.INSPECTION.open(project)
}
