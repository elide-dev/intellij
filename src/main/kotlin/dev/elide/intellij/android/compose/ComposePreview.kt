package dev.elide.intellij.android.compose

import com.intellij.icons.AllIcons
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.content.ContentFactory
import dev.elide.intellij.service.elideProjectIndex
import dev.elide.intellij.settings.ElideSettings
import dev.elide.project.manifest.sourcePaths
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

class ElideComposePreviewAction : AnAction("Compose Preview", "Render this Kotlin file's @Preview functions with Komodo", AllIcons.Actions.Preview) {
  override fun getActionUpdateThread() = ActionUpdateThread.BGT
  override fun update(event: AnActionEvent) {
    val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
    val project = event.project
    event.presentation.isEnabledAndVisible = project != null && file?.extension == "kt" && project.elideProjectIndex.entries.any { it.value.android != null }
  }
  override fun actionPerformed(event: AnActionEvent) {
    val project = event.project ?: return
    val file = event.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
    project.service<ComposePreviewService>().open(file)
    ToolWindowManager.getInstance(project).getToolWindow(COMPOSE_TOOL_WINDOW)?.activate(null)
  }
}

const val COMPOSE_TOOL_WINDOW = "Elide Compose Preview"
class ElideComposePreviewToolWindowFactory : ToolWindowFactory {
  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val preview = project.service<ComposePreviewService>()
    val content = ContentFactory.getInstance().createContent(preview.panel, "", false)
    content.setDisposer { preview.deactivate() }
    toolWindow.contentManager.addContent(content)
  }
}

@Service(Service.Level.PROJECT)
class ComposePreviewService(private val project: Project) : Disposable {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val generation = AtomicLong()
  private val lifecycle = AtomicLong()
  private data class Refresh(val file: VirtualFile, val revision: Long, val lifecycle: Long, val build: Long, val debounce: Long)
  private val refreshes = Channel<Refresh>(Channel.CONFLATED)
  @Volatile private var work: Job? = null
  @Volatile private var renderedRevision = -1L
  private val changedSources = java.util.concurrent.ConcurrentHashMap.newKeySet<Path>()
  private var source: VirtualFile? = null
  @Volatile private var visible = false
  @Volatile private var backend: KomodoBackend? = null
  private var backendKey: Pair<ComposeInputs, String>? = null
  private val buildRevision = AtomicLong()
  private var candidateCache: Triple<Long, String, List<ComposeApplication>>? = null
  private var inputCache: Triple<Long, String, ComposeInputs>? = null
  @Volatile private var selectedApplication: String? = null
  private var autoRefresh = true
  private var lastCards: List<ComposeCard> = emptyList()
  private val input = Channel<() -> Unit>(Channel.UNLIMITED)
  private val pendingCards = java.util.concurrent.atomic.AtomicReference<Triple<KomodoBackend, Long, List<ComposeCard>>?>()
  private val cardsScheduled = java.util.concurrent.atomic.AtomicBoolean()
  private val view: ComposePreviewPanel = ComposePreviewPanel(project, object : ComposePreviewPanel.Callbacks {
    override fun refresh() = refreshPreview()
    override fun cancel() = cancelPreview("Cancelled — retained images are stale")
    override fun applicationSelected(value: String) { selectedApplication = value; refreshPreview() }
    override fun autoRefreshChanged(enabled: Boolean) { autoRefresh = enabled; if (enabled) refreshPreview(rebuild = false) }
    override fun navigate(location: ComposeLayoutSource) = navigateSource(location)
    override fun pointer(cardId: String, action: String, x: Int, y: Int, deltaX: Double, deltaY: Double) = enqueueInput { it.pointer(cardId, action, x, y, deltaX, deltaY) }
    override fun key(cardId: String, action: String, key: String, modifiers: Int) = enqueueInput { it.key(cardId, action, key, modifiers) }
    override fun focus(cardId: String?) = enqueueInput { it.blur() }
    override fun installKomodo() {
      val root = Path.of(System.getProperty("user.home"), ".local", "share", "komodo")
      view.appendLog("Install the standalone Komodo distribution at $root (komodo executable and plugins directory), then Refresh. Komodo is independent of Elide; this action does not install software.\n")
      scope.launch {
        try { java.awt.Desktop.getDesktop().open(root.toFile().takeIf { it.isDirectory } ?: root.parent.toFile()) }
        catch (failure: Exception) { ui { view.appendLog("Cannot open Komodo folder: ${failure.message}\n", true) } }
      }
    }
  })
  val panel: javax.swing.JPanel get() = view

  init {
    Disposer.register(this, view)
    scope.launch {
      for (queued in refreshes) {
        var request = queued
        while (true) {
          delay(request.debounce)
          request = refreshes.tryReceive().getOrNull() ?: break
        }
        if (visible && request.revision == generation.get() && request.lifecycle == lifecycle.get()) {
          val active = launch { refresh(request) }
          work = active
          active.join()
          work = null
        }
      }
    }
    scope.launch { for (action in input) try { action() } catch (failure: Exception) { ui { view.appendLog("Komodo input failed: ${failure.message}\n", true) } } }
    view.addHierarchyListener { event ->
      if (event.changeFlags and java.awt.event.HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) {
        if (view.isShowing) { visible = true; refreshPreview(rebuild = false) } else deactivate()
      }
    }
    EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
      override fun documentChanged(event: DocumentEvent) {
        val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
        if (visible && source != null && file.extension in listOf("kt", "java") && relevant(file.path)) {
          generation.incrementAndGet()
          ui {
            view.setStatus("Editor changed — previews are stale", stale = true)
            if (autoRefresh) refreshPreview(120, rebuild = false)
          }
        }
      }
    }, this)
    project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
      override fun after(events: List<VFileEvent>) {
        val relevantEvents = events.filter { relevant(it.path) }
        if (source != null && relevantEvents.isNotEmpty()) {
          val rebuild = relevantEvents.any { it !is VFileContentChangeEvent || it.file.extension != "kt" }
          if (rebuild) { buildRevision.incrementAndGet(); lifecycle.incrementAndGet(); work?.cancel() }
          relevantEvents.filter { it is VFileContentChangeEvent && it.file.extension == "kt" }
            .forEach { changedSources.add(Path.of(it.path).toAbsolutePath().normalize()) }
          generation.incrementAndGet()
          ui { if (visible) {
            if (autoRefresh) refreshPreview(120, rebuild = false) else view.setStatus("Project changed — previews are stale", stale = true)
          } }
        }
      }
    })
  }

  private fun ui(action: () -> Unit) { ApplicationManager.getApplication().invokeLater { if (!project.isDisposed) action() } }
  private fun publishCards(session: KomodoBackend, cards: List<ComposeCard>) {
    pendingCards.set(Triple(session, renderedRevision, cards))
    scheduleCards()
  }
  private fun scheduleCards() {
    if (!cardsScheduled.compareAndSet(false, true)) return
    ui {
      pendingCards.getAndSet(null)?.let { (session, revision, cards) ->
        if (visible && backend === session && revision == generation.get()) { lastCards = cards; view.showCards(cards) }
      }
      cardsScheduled.set(false)
      if (pendingCards.get() != null) scheduleCards()
    }
  }
  private fun enqueueInput(action: (KomodoBackend) -> Unit) {
    val current = backend ?: return
    val revision = generation.get()
    input.trySend { if (visible && backend === current && renderedRevision == revision && generation.get() == revision) action(current) }
  }
  private fun relevant(path: String): Boolean = project.elideProjectIndex.entries.any { (root, _) ->
    val base = Path.of(root).toAbsolutePath().normalize()
    val candidate = runCatching { Path.of(path).toAbsolutePath().normalize() }.getOrNull() ?: return@any false
    candidate.startsWith(base) && base.relativize(candidate).none { segment ->
      val name = segment.toString()
      name == ".dev" || name == ".git" || name == ".idea"
    }
  }
  fun open(file: VirtualFile) {
    val changed = source != file
    if (changed) { lastCards = emptyList(); view.clear(); selectedApplication = null; changedSources.clear() }
    source = file
    refreshPreview(rebuild = changed)
  }
  private fun invalidatePreview() {
    generation.incrementAndGet()
    lifecycle.incrementAndGet()
    work?.cancel()
    pendingCards.set(null)
    backend?.invalidate()
  }
  private fun cancelPreview(message: String) {
    invalidatePreview()
    val retired = backend
    backend = null
    backendKey = null
    scope.launch { retired?.close() }
    view.setStatus(message, stale = true)
  }
  fun deactivate() { visible = false; cancelPreview("Paused while preview is hidden — images are stale") }

  private fun refreshPreview(debounce: Long = 0, rebuild: Boolean = true) {
    if (!visible) return
    val file = source ?: run { view.setStatus("Open a Kotlin file and choose Compose Preview."); return }
    if (rebuild) { buildRevision.incrementAndGet(); lifecycle.incrementAndGet(); work?.cancel() }
    val revision = generation.incrementAndGet()
    view.setStatus("Refreshing ${file.name} with Komodo", busy = true, stale = true)
    refreshes.trySend(Refresh(file, revision, lifecycle.get(), buildRevision.get(), debounce))
  }

  /** Finish an in-flight source delta, then consume only the newest edit; typing must not kill the compiler. */
  private suspend fun refresh(request: Refresh) {
      val (file, revision, epoch, build) = request
      val job = currentCoroutineContext()
      fun check() {
        job.ensureActive()
        if (!visible || project.isDisposed || lifecycle.get() != epoch) throw CancellationException("Preview superseded")
      }
      fun update(action: () -> Unit) = ui { if (generation.get() == revision) action() }
      fun log(text: String) = ui { view.appendLog(text) }
      try {
        check()
        require(TrustedProjects.isProjectTrusted(project)) { "Trust this project before running Compose previews; previews execute project code." }
        KomodoTransport.installation()
        update { view.showInstallAction(false) }
        val cachedCandidates = candidateCache
        val candidates = if (cachedCandidates?.first == build && cachedCandidates.second == file.path) cachedCandidates.third
          else ComposeInputResolver.applications(project, file.toNioPath()).also { check(); candidateCache = Triple(build, file.path, it) }
        require(candidates.isNotEmpty()) { "No owning Android application includes ${file.name}. Sync the workspace, including this library's consuming application." }
        val app = candidates.find { it.toString() == selectedApplication } ?: candidates.first()
        selectedApplication = app.toString()
        update { view.setApplications(candidates.map { it.toString() }, app.toString()) }
        val cachedInputs = inputCache
        val inputs = if (cachedInputs?.first == build && cachedInputs.second == app.toString()) cachedInputs.third
          else {
            backend?.close(); backend = null; backendKey = null
            ComposeInputResolver.build(project, app, ::log).also { check(); inputCache = Triple(build, app.toString(), it) }
          }
        val buffers = ReadAction.compute<Map<Path, String>, RuntimeException> {
          FileDocumentManager.getInstance().unsavedDocuments.mapNotNull { document ->
            FileDocumentManager.getInstance().getFile(document)?.takeIf { candidate ->
              candidate.extension in listOf("kt", "java") && inputs.modules.any { module ->
                module.project.manifest.sources[module.sourceSet]?.sourcePaths(module.project.root, module.sourceSet)?.any { ComposeInputResolver.matches(module.project.root, it, candidate.toNioPath()) } == true
              }
            }?.let { it.toNioPath().toAbsolutePath().normalize() to document.text }
          }.toMap().toMutableMap().apply {
            changedSources.forEach { path ->
              val candidate = LocalFileSystem.getInstance().findFileByNioFile(path)
              if (candidate != null && candidate.isValid && inputs.modules.any { module ->
                  module.project.manifest.sources[module.sourceSet]?.sourcePaths(module.project.root, module.sourceSet)
                    ?.any { ComposeInputResolver.matches(module.project.root, it, path) } == true
                }) {
                put(path, FileDocumentManager.getInstance().getDocument(candidate)?.text ?: String(candidate.contentsToByteArray(), candidate.charset))
              }
            }
            put(file.toNioPath().toAbsolutePath().normalize(), FileDocumentManager.getInstance().getDocument(file)?.text ?: String(file.contentsToByteArray(), file.charset))
          }
        }
        val runtime = ComposeRuntime.ensure(::check) { message -> update { view.setStatus(message, busy = true, stale = true) } }
        val key = inputs to file.path
        if (backendKey != key) { backend?.close(); backend = null; backendKey = key }
        val settings = project.service<ElideSettings>().getLinkedProjectSettings(app.project.buildRoot.toString())
        val home = settings?.jdkHome?.takeIf(String::isNotBlank) ?: ReadAction.compute<String?, RuntimeException> { ProjectRootManager.getInstance(project).projectSdk?.homePath } ?: System.getProperty("java.home")
        val session = backend ?: run {
          lateinit var created: KomodoBackend
          created = KomodoBackend(project, inputs, runtime, file.toNioPath().toAbsolutePath().normalize(), home, ::log,
            cardsChanged = { cards -> publishCards(created, cards) }, failed = { failure -> ui {
              if (visible && backend === created) {
                cancelPreview("Komodo stopped — retained images are stale")
                view.setStatus("Komodo stopped: ${failure.message}", stale = true, failed = true)
                view.appendLog("${failure.message}\n", true)
              }
            } })
          created.also { backend = it }
        }
        renderedRevision = -1
        session.refresh(buffers, ::check)
        check()
        renderedRevision = revision
        update {
          lastCards = session.snapshot()
          view.showCards(lastCards)
          val failures = lastCards.count { it.error != null }
          view.setStatus(if (lastCards.isEmpty()) "No @Preview functions found in ${file.name}" else if (failures > 0) "$failures preview(s) failed — see log" else "${lastCards.size} live Komodo preview(s) — editor buffers synchronized", failed = failures > 0)
        }
      } catch (cancelled: CancellationException) {
        backend?.close(); backend = null; backendKey = null
        job.ensureActive()
      } catch (failure: KomodoCompilationFailure) {
        update { view.setStatus("Preview failed: ${failure.message}", stale = true, failed = true); view.appendLog("${failure.message}\n", true) }
      } catch (failure: Throwable) {
        backend?.close(); backend = null; backendKey = null
        update { view.setStatus("Preview failed: ${failure.message}", stale = true, failed = true); view.appendLog("${failure.message}\n", true); view.showInstallAction(failure is KomodoUnavailable) }
      }
  }

  private fun navigateSource(location: ComposeLayoutSource) {
    scope.launch {
      val target = ReadAction.compute<VirtualFile?, RuntimeException> {
        if (location.path != null) {
          val path = runCatching { Path.of(location.path).toAbsolutePath().normalize() }.getOrNull()
          if (path != null && relevant(path.toString())) LocalFileSystem.getInstance().findFileByNioFile(path) else null
        } else if (location.fileName.contains('/') || location.fileName.contains('\\')) null
        else FilenameIndex.getVirtualFilesByName(location.fileName, GlobalSearchScope.allScope(project)).filter { candidate ->
          val text = FileDocumentManager.getInstance().getDocument(candidate)?.text ?: return@filter false
          location.matchesPackage(text)
        }.singleOrNull()
      } ?: run {
        ui { view.appendLog("Cannot locate ${location.path ?: location.fileName} in the project or attached library sources.\n", true) }
        return@launch
      }
      ui {
        if (location.lineNumber > 0) OpenFileDescriptor(project, target, location.lineNumber - 1, 0).navigate(true)
        else if (location.offset >= 0) OpenFileDescriptor(project, target, location.offset).navigate(true)
      }
    }
  }
  override fun dispose() {
    visible = false
    generation.incrementAndGet()
    backend?.invalidate()
    val retired = backend
    backend = null
    input.close(); refreshes.close(); scope.cancel()
    com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService().execute { retired?.close() }
  }
}
