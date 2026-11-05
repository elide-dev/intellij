package dev.elide.intellij.android.testing

import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import dev.elide.intellij.android.ElideAndroidDeployment
import dev.elide.intellij.android.ElideAndroidTools
import dev.elide.intellij.execution.ElideExternalTaskConfigurationType
import dev.elide.intellij.execution.ElideRunConfiguration
import dev.elide.intellij.service.elideProjectIndex

/** Runs one synced test APK, optionally narrowed to binary class names or class#method selectors. */
class ElideAndroidTestAction : DumbAwareAction() {
  override fun actionPerformed(event: AnActionEvent) {
    val project = event.project ?: return
    if (!TrustedProjects.isProjectTrusted(project)) {
      Messages.showErrorDialog(project, "Trust this project before running instrumented tests.", "Android Instrumented Tests")
      return
    }
    val targets = project.elideProjectIndex.entries.flatMap { (root, info) ->
      info.android?.artifacts.orEmpty().filter { it.instrumented }.map { root to it }
    }
    if (targets.isEmpty()) {
      Messages.showErrorDialog(project, "No synced APK declares instrumentation with an android-test target. Sync the project first.", "Android Instrumented Tests")
      return
    }
    val selected = project.getService(ElideAndroidDeployment::class.java).selectedTarget()
    val preferred = targets.indexOfFirst { it.first == selected?.root && it.second.name == selected?.artifact?.name }.coerceAtLeast(0)
    val index = if (targets.size == 1) 0 else Messages.showChooseDialog(project, "Test APK to run", "Android Instrumented Tests",
      null, targets.map { "${it.second.name} — ${it.first}" }.toTypedArray(), "${targets[preferred].second.name} — ${targets[preferred].first}")
    if (index < 0) return
    val (root, artifact) = targets[index]
    val filter = Messages.showInputDialog(project,
      "Optional class or class#method selectors, separated by commas. Leave empty to run the entire APK.",
      "Android Instrumented Tests", null) ?: return
    val filters = filter.split(',').map(String::trim).filter(String::isNotEmpty)
    FileDocumentManager.getInstance().saveAllDocuments()
    object : Task.Backgroundable(project, "Preparing Android instrumented tests", true) {
      private var device: String? = null
      override fun run(indicator: ProgressIndicator) {
        val deployment = project.getService(ElideAndroidDeployment::class.java)
        val target = deployment.targets().firstOrNull { it.root == root && it.artifact.name == artifact.name }
          ?: error("The test APK changed. Sync the project and select it again.")
        device = deployment.prepareDevice(target, indicator)
          ?: ElideAndroidTools.devices(project, root, indicator).filter { it.ready }.singleOrNull()?.serial
          ?: error("Select one ready Android deployment device before running tests.")
      }
      override fun onSuccess() {
        val settings = RunManager.getInstance(project).createConfiguration("Android tests: ${artifact.name}", ElideExternalTaskConfigurationType.configurationFactory)
        (settings.configuration as ElideRunConfiguration).apply {
          this.settings.externalProjectPath = root
          this.settings.taskNames = commandLine(artifact.name, requireNotNull(device), filters)
        }
        RunManager.getInstance(project).setTemporaryConfiguration(settings)
        ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
      }
      override fun onThrowable(error: Throwable) {
        Messages.showErrorDialog(project, error.message ?: error.toString(), "Android Instrumented Tests")
      }
    }.queue()
  }

  companion object {
    internal fun commandLine(artifact: String, device: String, filters: List<String>): List<String> = buildList {
      add("build")
      add("android-test-$artifact")
      add("--device=$device")
      filters.forEach { add("--tests=$it") }
    }
  }
}
