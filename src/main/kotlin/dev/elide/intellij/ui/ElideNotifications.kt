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
package dev.elide.intellij.ui

import com.intellij.ide.BrowserUtil
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.updateSettings.impl.pluginsAdvertisement.installAndEnable
import com.intellij.openapi.vfs.VfsUtil
import dev.elide.intellij.Constants
import dev.elide.intellij.execution.nativeimage.ElideNativeDebugger
import dev.elide.intellij.execution.nativeimage.ElideNativeImageLaunch
import dev.elide.intellij.settings.ElideConfigurable
import java.nio.file.Files

object ElideNotifications {
  fun notifyInvalidElideHome(project: Project? = null) {
    NotificationGroupManager.getInstance()
      .getNotificationGroup("Elide Notifications")
      .createNotification(Constants.Strings["elide.notifications.invalidHome.content"], NotificationType.ERROR)
      .setTitle(Constants.Strings["elide.notifications.invalidHome.title"])
      .addAction(
        object : NotificationAction(Constants.Strings["elide.notifications.invalidHome.configure"]) {
          override fun actionPerformed(e: AnActionEvent, n: Notification) {
            ShowSettingsUtil.getInstance().showSettingsDialog(e.project, ElideConfigurable::class.java)
          }
        },
      )
      .addAction(
        object : NotificationAction(Constants.Strings["elide.notifications.invalidHome.install"]) {
          override fun actionPerformed(e: AnActionEvent, n: Notification) {
            BrowserUtil.browse(Constants.INSTALL_URL)
          }
        },
      )
      .notify(project)
  }

  /** Report that `elide init` could not generate a new project's files, quoting the CLI's own [details]. */
  fun notifyProjectGenerationFailed(project: Project, details: String) {
    NotificationGroupManager.getInstance()
      .getNotificationGroup("Elide Notifications")
      .createNotification(
        Constants.Strings["elide.notifications.generationFailed.content", details],
        NotificationType.ERROR,
      )
      .setTitle(Constants.Strings["elide.notifications.generationFailed.title"])
      .notify(project)
  }

  /** Report that coverage reports produced outside the IDE were attached as the suite named [suiteName]. */
  fun notifyCoverageAttached(project: Project, suiteName: String) {
    NotificationGroupManager.getInstance()
      .getNotificationGroup("Elide Notifications")
      .createNotification(
        Constants.Strings["elide.notifications.coverageAttached.content", suiteName],
        NotificationType.INFORMATION,
      )
      .setTitle(Constants.Strings["elide.notifications.coverageAttached.title"])
      .notify(project)
  }

  /**
   * Report that a Native Image debug session cannot start because Native Debugging Support is not installed, and
   * offer to install it.
   *
   * The install action is the platform's own advertiser, which resolves the plugin from the marketplace and handles
   * an IDE that cannot run it — the backend is licensed for IntelliJ IDEA Ultimate — with a dialog of its own.
   */
  fun notifyNativeDebuggerMissing(project: Project) {
    NotificationGroupManager.getInstance()
      .getNotificationGroup("Elide Notifications")
      .createNotification(
        Constants.Strings["elide.notifications.nativeDebuggerMissing.content"],
        NotificationType.WARNING,
      )
      .setTitle(Constants.Strings["elide.notifications.nativeDebuggerMissing.title"])
      .addAction(
        object : NotificationAction(Constants.Strings["elide.notifications.nativeDebuggerMissing.install"]) {
          override fun actionPerformed(e: AnActionEvent, n: Notification) {
            installAndEnable(e.project, setOf(ElideNativeDebugger.PLUGIN_ID)) { n.expire() }
          }
        },
      )
      .notify(project)
  }

  /**
   * Report that the image of [artifact] carries no debug info, so a session that just started will not reach source
   * level, and point at the manifest where the flags producing it are declared.
   *
   * Two different things cause it and the wording says which: a build that never asked for debug info, and a build
   * that did on a platform where GraalVM emits none — it only does so on Linux, and the pretty-printer script it
   * writes alongside a `-g` build is what tells the two apart.
   */
  fun notifyNativeImageWithoutDebugInfo(project: Project, artifact: String, launch: ElideNativeImageLaunch) {
    val content = when {
      Files.isRegularFile(launch.gdbHelpers) -> "elide.notifications.nativeImageNoDebugInfo.platform"
      else -> "elide.notifications.nativeImageNoDebugInfo.flag"
    }

    NotificationGroupManager.getInstance()
      .getNotificationGroup("Elide Notifications")
      .createNotification(Constants.Strings[content, artifact], NotificationType.WARNING)
      .setTitle(Constants.Strings["elide.notifications.nativeImageNoDebugInfo.title"])
      .addAction(
        object : NotificationAction(Constants.Strings["elide.notifications.nativeImageNoDebugInfo.open"]) {
          override fun actionPerformed(e: AnActionEvent, n: Notification) {
            val manifest = VfsUtil.findFile(launch.root.resolve(Constants.MANIFEST_NAME), true) ?: return
            OpenFileDescriptor(project, manifest).navigate(true)
          }
        },
      )
      .notify(project)
  }
}
