package dev.elide.intellij.android

import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.execution.ParametersListUtil
import dev.elide.intellij.service.ElideExecutionService
import dev.elide.intellij.service.elideProjectIndex
import java.awt.BorderLayout
import java.awt.GridLayout
import javax.swing.*

internal fun <T : Any> androidChoice(project: Project, title: String, values: List<T>): T? {
  if (values.isEmpty()) { Messages.showErrorDialog(project, "No matching Android targets are available. Sync the project and check its manifest.", title); return null }
  val combo = JComboBox<T>().apply { values.forEach(::addItem) }
  val dialog = object : DialogWrapper(project) {
    init { this.title = title; init() }
    override fun createCenterPanel(): JComponent = JPanel(BorderLayout()).apply { add(combo, BorderLayout.CENTER) }
  }
  return if (dialog.showAndGet()) values[combo.selectedIndex] else null
}

internal fun androidReport(project: Project, title: String, text: String) {
  object : DialogWrapper(project) {
    init { this.title = title; setOKButtonText("Close"); init() }
    override fun createActions(): Array<Action> = arrayOf(okAction)
    override fun createCenterPanel(): JComponent = JScrollPane(JTextArea(text, 24, 100).apply { isEditable = false; caretPosition = 0 })
  }.show()
}

internal fun <T> androidBackground(project: Project, title: String, operation: (ProgressIndicator) -> T, success: (T) -> Unit) {
  object : Task.Backgroundable(project, title, true) {
    private var result: Result<T>? = null
    override fun run(indicator: ProgressIndicator) {
      try { result = Result.success(operation(indicator)) }
      catch (cancelled: com.intellij.openapi.progress.ProcessCanceledException) { throw cancelled }
      catch (error: Exception) { result = Result.failure(error) }
    }
    override fun onSuccess() {
      if (project.isDisposed) return
      try { result?.fold(success) { Messages.showErrorDialog(project, it.message ?: it.javaClass.simpleName, title) } }
      catch (cancelled: com.intellij.openapi.progress.ProcessCanceledException) { throw cancelled }
      catch (error: Exception) { Messages.showErrorDialog(project, error.message ?: error.javaClass.simpleName, title) }
    }
  }.queue()
}

abstract class ElideAndroidAction : AnAction(), DumbAware {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
  override fun update(e: AnActionEvent) { e.presentation.isEnabled = e.project != null }
  final override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    try { perform(project) }
    catch (cancelled: com.intellij.openapi.progress.ProcessCanceledException) { throw cancelled }
    catch (error: Exception) { Messages.showErrorDialog(project, error.message ?: error.javaClass.simpleName, "Elide Android") }
  }
  abstract fun perform(project: Project)
}

private data class DeviceChoice(val device: ElideAndroidDevice? = null, val projectEmulator: Boolean = false) {
  override fun toString(): String = device?.toString() ?: if (projectEmulator) "Project emulator (start or create its AVD)" else "Automatic (let Elide select a device)"
}

class ElideAndroidSelectTargetAction : ElideAndroidAction() {
  override fun perform(project: Project) = selectAndroidTarget(project) {}
}

internal fun selectAndroidTarget(project: Project, selected: () -> Unit) {
  val service = project.getService(ElideAndroidDeployment::class.java)
  val target = androidChoice(project, "Select Android APK", service.targets()) ?: return
  androidBackground(project, "Discover Android Devices", { ElideAndroidTools.devices(project, target.root, it) }) { devices ->
    val choice = androidChoice(project, "Select Android Deployment Device", listOf(DeviceChoice(), DeviceChoice(projectEmulator = true)) + devices.map { DeviceChoice(it) }) ?: return@androidBackground
    val device = choice.device
    if (device != null && !device.ready) {
      Messages.showErrorDialog(project, "${device.serial} is ${device.state}. Authorize USB debugging or reconnect the device before selecting it.", "Android Device Unavailable")
      return@androidBackground
    }
    if (device?.emulator == true) {
      androidBackground(project, "Read Emulator Identity", {
        ElideAndroidTools.command(project, target.root, device.serial, listOf("emu", "avd", "name"), it).lineSequence().firstOrNull()?.trim()
      }) { avd ->
        service.select(target, device.serial, avd?.takeUnless { it == "OK" || it.isBlank() })
        selected()
      }
    } else { service.select(target, device?.serial, projectEmulator = choice.projectEmulator); selected() }
  }
}

private fun deploy(project: Project, debug: Boolean) {
  val service = project.getService(ElideAndroidDeployment::class.java)
  if (service.getState().projectPath == null || service.getState().artifact == null) {
    selectAndroidTarget(project) { deploy(project, debug) }; return
  }
  val target = service.selectedTarget() ?: error("The selected Android APK is no longer available. Select an Android Deployment Target explicitly; no other application was substituted.")
  FileDocumentManager.getInstance().saveAllDocuments()
  val revision = service.revision
  androidBackground(project, if (debug) "Prepare Android Debug" else "Prepare Android Run", {
    service.validate(target, debug)
    service.prepareDevice(target, it)
  }) { serial ->
    check(service.revision == revision) { "Deployment selection changed while preparing. Run the selected target again." }
    service.launch(target, debug, serial)
  }
}

class ElideAndroidRunAction : ElideAndroidAction() { override fun perform(project: Project) = deploy(project, false) }
class ElideAndroidDebugAction : ElideAndroidAction() { override fun perform(project: Project) = deploy(project, true) }

internal data class AndroidProjectChoice(val root: String, val info: ElideAndroidInfo) {
  override fun toString(): String = root
}

internal fun androidProject(project: Project): AndroidProjectChoice? {
  val roots = project.elideProjectIndex.entries.mapNotNull { (root, info) -> info.android?.let { AndroidProjectChoice(root, it) } }
  val selected = project.getService(ElideAndroidDeployment::class.java).getState().projectPath
  return if (roots.size == 1) roots.first() else androidChoice(project, "Select Android Project", roots.sortedBy { it.root != selected })
}

internal fun androidBuild(project: Project, owner: AndroidProjectChoice, task: String, options: List<String> = emptyList()) {
  check(TrustedProjects.isProjectTrusted(project)) { "Trust this project before running Android build targets." }
  check(task in owner.info.tasks) { "The CLI does not list $task for this project. Sync again after checking the manifest and CLI version." }
  FileDocumentManager.getInstance().saveAllDocuments()
  project.getService(ElideExecutionService::class.java).execute(ParametersListUtil.join(listOf("build", task) + options), owner.root, DefaultRunExecutor.getRunExecutorInstance())
}

class ElideAndroidEnvironmentAction : ElideAndroidAction() {
  override fun perform(project: Project) {
    val root = project.getService(ElideAndroidDeployment::class.java).getState().projectPath ?: project.basePath ?: error("Project directory is unavailable.")
    androidReport(project, "Android Environment (Read Only)", ElideAndroidTools.environmentReport(project, root))
  }
}

/** Native tools dialog is reachable from the Elide toolwindow toolbar and Find Action, including before sync. */
class ElideAndroidToolsAction : ElideAndroidAction() {
  override fun perform(project: Project) {
    val service = project.getService(ElideAndroidDeployment::class.java)
    val panel = JPanel(GridLayout(0, 2, 8, 8))
    val dialog = object : DialogWrapper(project) {
      init { title = "Elide Android Devices and Tools"; setOKButtonText("Close"); init() }
      override fun createActions(): Array<Action> = arrayOf(okAction)
      override fun createCenterPanel(): JComponent = panel
    }
    fun button(text: String, run: () -> Unit) { panel.add(JButton(text).apply { addActionListener {
      try { run() } catch (error: Exception) { Messages.showErrorDialog(project, error.message ?: "Android operation failed", text) }
    } }) }
    button("Environment Report (No Sync)") { ElideAndroidEnvironmentAction().perform(project) }
    button("Select Deployment Target") { selectAndroidTarget(project) {} }
    button("Run Selected APK") { deploy(project, false) }
    button("Debug Selected APK") { deploy(project, true) }
    button("Open Native Device Manager / Pairing") { ElideAndroidNativeTool.DEVICES.open(project) }
    button("Open Native Logcat") { ElideAndroidNativeTool.LOGCAT.open(project) }
    button("Open Native App Inspection") { ElideAndroidNativeTool.INSPECTION.open(project) }
    button("Check SDK Components") { androidProject(project)?.let { androidBuild(project, it, "android-sdk") } }
    button("Install Missing SDK Components") {
      val owner = androidProject(project) ?: return@button
      if (Messages.showYesNoDialog(project, "Install missing Android SDK components and accept their licenses?", "Install Android SDK Components", Messages.getQuestionIcon()) == Messages.YES)
        androidBuild(project, owner, "android-sdk", listOf("--install-sdk"))
    }
    button("Create Project AVD") { androidProject(project)?.let { androidBuild(project, it, "android-avd") } }
    button("Start Project Emulator") {
      androidProject(project)?.let { androidBuild(project, it, "android-emulator", if (ElideAndroidTools.settings(project, it.root).emulatorWindow) emptyList() else listOf("--no-window")) }
    }
    button("Stop Emulator") {
      val owner = androidProject(project) ?: return@button
      androidBackground(project, "Find Running Emulators", { ElideAndroidTools.devices(project, owner.root, it).filter { device -> device.emulator && device.ready } }) { devices ->
        val device = androidChoice(project, "Stop Android Emulator", devices) ?: return@androidBackground
        androidBuild(project, owner, "android-emulator-stop", listOf("--device=${device.serial}"))
      }
    }
    button("Create Signing Keystore") {
      val owner = androidProject(project) ?: return@button
      val task = androidChoice(project, "Create Android Keystore", owner.info.tasks.filter { it.startsWith("android-keystore-") }) ?: return@button
      androidBuild(project, owner, task)
    }
    button("Build Artifact / Feature Module") {
      val owner = androidProject(project) ?: return@button
      val artifact = androidChoice(project, "Build Android Artifact", owner.info.artifacts.map { it.name }) ?: return@button
      val model = owner.info.artifacts.first { it.name == artifact }
      androidBuild(project, owner, if (model.kind == "feature") "android-module-$artifact" else artifact)
    }
    button("Build Obfuscation Mapping") {
      val owner = androidProject(project) ?: return@button
      val task = androidChoice(project, "Build Android Mapping", owner.info.tasks.filter { it.startsWith("android-mapping-") }) ?: return@button
      androidBuild(project, owner, task)
    }
    button("Open Obfuscation Mapping") {
      val owner = androidProject(project) ?: return@button
      val artifact = androidChoice(project, "Open Android Mapping", owner.info.artifacts.filter { it.minify }.map { it.name }) ?: return@button
      val path = owner.info.artifacts.first { it.name == artifact }.mappingFile
      val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(path) ?: error("Mapping file is missing. Run Build Obfuscation Mapping first: $path")
      OpenFileDescriptor(project, file).navigate(true)
    }
    panel.border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
    dialog.show()
  }
}
