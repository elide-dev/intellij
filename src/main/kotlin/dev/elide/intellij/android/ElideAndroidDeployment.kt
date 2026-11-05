package dev.elide.intellij.android

import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.util.execution.ParametersListUtil
import com.intellij.util.xmlb.XmlSerializerUtil
import dev.elide.intellij.service.ElideExecutionService
import dev.elide.intellij.cli.ElideCommandLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import dev.elide.intellij.service.elideProjectIndex
import java.nio.file.Files
import java.nio.file.Path

/** A remembered unavailable target is an error, never a request to select a different application/device. */
@Service(Service.Level.PROJECT)
@State(name = "ElideAndroidDeployment", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class ElideAndroidDeployment(private val project: Project) : PersistentStateComponent<ElideAndroidDeployment.Selection> {
  class Selection {
    var projectPath: String? = null
    var artifact: String? = null
    var deviceSerial: String? = null
    var deviceAvd: String? = null
    var projectEmulator: Boolean = false
  }
  var revision: Long = 0
    private set

  private val selection = Selection()
  override fun getState(): Selection = selection
  override fun loadState(state: Selection) = XmlSerializerUtil.copyBean(state, selection)

  fun targets(): List<ElideAndroidDeploymentTarget> = project.elideProjectIndex.entries.flatMap { (root, projectInfo) ->
    val info = projectInfo.android ?: return@flatMap emptyList()
    info.artifacts.filter { it.kind == "apk" }.map { ElideAndroidDeploymentTarget(root, info, it) }
  }

  fun selectedTarget(): ElideAndroidDeploymentTarget? {
    val root = selection.projectPath ?: return null
    val artifact = selection.artifact ?: return null
    val info = project.elideProjectIndex[root]?.android ?: return null
    return info.artifacts.find { it.name == artifact && it.kind == "apk" }?.let { ElideAndroidDeploymentTarget(root, info, it) }
  }

  fun select(target: ElideAndroidDeploymentTarget, serial: String?, avd: String? = null, projectEmulator: Boolean = false) {
    revision++
    selection.projectPath = target.root
    selection.artifact = target.artifact.name
    selection.deviceSerial = serial
    selection.deviceAvd = avd
    selection.projectEmulator = projectEmulator
  }

  fun requireSelectedDevice(root: String, indicator: ProgressIndicator? = null): String? {
    val serial = selection.deviceSerial ?: return null
    val device = ElideAndroidTools.devices(project, root, indicator).find { it.serial == serial }
    check(device?.ready == true) { "Selected Android device $serial is ${device?.state ?: "disconnected"}. Reconnect it or explicitly select another deployment target." }
    if (device.emulator && selection.deviceAvd != null) {
      val avd = ElideAndroidTools.command(project, root, serial, listOf("emu", "avd", "name"), indicator).lineSequence().firstOrNull()?.trim()
      check(avd == selection.deviceAvd) { "Serial $serial now belongs to AVD $avd, not ${selection.deviceAvd}. Select the intended emulator again." }
    }
    return serial
  }

  /** Boot only the explicitly requested project AVD; afterwards pin its actual serial for Run and Debug. */
  fun prepareDevice(target: ElideAndroidDeploymentTarget, indicator: ProgressIndicator): String? {
    if (!selection.projectEmulator) return requireSelectedDevice(target.root, indicator)
    check(TrustedProjects.isProjectTrusted(project)) { "Trust this project before starting its emulator." }
    check("android-emulator" in target.info.tasks) { "The CLI does not offer this project's emulator target. Sync the project first." }
    fun runningAvd(): String? {
      val matches = ElideAndroidTools.devices(project, target.root, indicator)
        .filter { it.emulator && it.ready }.filter { device ->
          ElideAndroidTools.command(project, target.root, device.serial, listOf("emu", "avd", "name"), indicator)
            .lineSequence().firstOrNull()?.trim() == target.info.avd
        }
      check(matches.size <= 1) {
        "Several instances of AVD ${target.info.avd} are running. Select an explicit device serial before deploying."
      }
      return matches.singleOrNull()?.serial
    }
    runningAvd()?.let { return it }
    val args = mutableListOf("build", "android-emulator")
    if (!ElideAndroidTools.settings(project, target.root).emulatorWindow) args.add("--no-window")
    runBlocking {
      val operation = async(Dispatchers.IO) {
        ElideCommandLine.resolve(project, target.root, Path.of(target.root)).invoke(
          args = args.toTypedArray(),
          onOutput = { line, _ -> indicator.text2 = line.trim().take(250) },
        )
      }
      try {
        while (!operation.isCompleted) {
          indicator.checkCanceled()
          if (project.isDisposed) throw com.intellij.openapi.progress.ProcessCanceledException()
          delay(100)
        }
        operation.await()
      } finally { operation.cancel() }
    }
    return runningAvd() ?: error("The emulator task finished but AVD ${target.info.avd} is not ready in this SDK's adb. Check that device tools and the build use the same Android SDK.")
  }

  fun validate(target: ElideAndroidDeploymentTarget, debug: Boolean) {
    check(selectedTarget() == target) { "The Android model or deployment selection changed. Select the target again." }
    check(if (debug) target.artifact.debuggable else target.artifact.runnable) {
      "${target.artifact.name} does not offer ${if (debug) "Debug" else "Run"}. Check signing, launch activity and the CLI task listing, then sync."
    }
    // Only the build knows whether its merged manifest is current: elide.pkl, dependencies and placeholders
    // can change debuggability without touching AndroidManifest.xml. The debug target validates its rebuilt APK.
  }

  /** Invoke on EDT after readiness validation in a cancellable background task. */
  fun launch(target: ElideAndroidDeploymentTarget, debug: Boolean, serial: String?) {
    check(TrustedProjects.isProjectTrusted(project)) { "Trust this project before running Android build targets." }
    validate(target, debug)
    FileDocumentManager.getInstance().saveAllDocuments()
    val task = if (debug) "android-debug-${target.artifact.name}" else "android-run-${target.artifact.name}"
    val args = buildList {
      add("build"); add(task)
      serial?.let { add("--device=$it") }
      if (!ElideAndroidTools.settings(project, target.root).emulatorWindow) add("--no-window")
    }
    project.getService(ElideExecutionService::class.java).execute(ParametersListUtil.join(args), target.root,
      if (debug) DefaultDebugExecutor.getDebugExecutorInstance() else DefaultRunExecutor.getRunExecutorInstance())
  }
}

data class ElideAndroidDeploymentTarget(val root: String, val info: ElideAndroidInfo, val artifact: ElideAndroidArtifact) {
  override fun toString(): String = "${artifact.name} — $root"
}
