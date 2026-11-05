package dev.elide.intellij.authoring

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.writeAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.model.task.TaskData
import com.intellij.openapi.externalSystem.service.project.manage.ExternalProjectsManagerImpl
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.BaseProjectDirectories.Companion.getBaseDirectories
import com.intellij.openapi.roots.ex.ProjectRootManagerEx
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import dev.elide.intellij.Constants
import dev.elide.intellij.cli.ElideCommandLine
import dev.elide.intellij.cli.buildTasks
import dev.elide.intellij.project.model.*
import dev.elide.intellij.service.elideProjectIndex
import java.nio.file.Path
import kotlinx.coroutines.*

/** Reloads the build graph only: saving a helper never installs dependencies or reimports modules. */
@Service(Service.Level.PROJECT)
class ElideBuildTaskRefreshService(private val project: Project, private val scope: CoroutineScope) {
  private val jobs = mutableMapOf<String, Job>()
  private var libraryJob: Job? = null

  fun start() {
    project.messageBus.connect(scope).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
      override fun after(events: List<VFileEvent>) {
        val roots = project.getBaseDirectories().map { it.toNioPath() }
        val paths = events.map { it.path }.filter { path ->
          isAuthoringPath(path) && roots.any { Path.of(path).startsWith(it) }
        }
        if (paths.isEmpty()) return
        // Adding/removing a project-supplied global declaration changes the optional synthetic library, even before sync.
        libraryJob?.cancel()
        libraryJob = scope.launch {
          delay(400)
          withContext(Dispatchers.EDT) {
            writeAction { ProjectRootManagerEx.getInstanceEx(project).makeRootsChange({}, false, true) }
          }
        }
        if (!TrustedProjects.isProjectTrusted(project)) return
        for ((root, info) in project.elideProjectIndex.entries) {
          if (info.workspaceRoot != null || paths.none { Path.of(it).startsWith(Path.of(root)) }) continue
          synchronized(jobs) {
            jobs.remove(root)?.cancel()
            jobs[root] = scope.launch { delay(400); refresh(root, info) }
          }
        }
      }
    })
  }

  private suspend fun refresh(root: String, snapshot: ElideProjectInfo) {
    try {
      if (!TrustedProjects.isProjectTrusted(project)) return
      val tasks = ElideCommandLine.resolve(project, root, Path.of(root)).buildTasks()
      currentCoroutineContext().ensureActive()
      withContext(Dispatchers.EDT) {
        if (project.isDisposed || !TrustedProjects.isProjectTrusted(project) || project.elideProjectIndex[root] !== snapshot) return@withContext
        val index = project.elideProjectIndex
        val projects = index.entries.filter { it.key == root || it.value.workspaceRoot == root }.associate { it.key to it.value }
        for ((path, info) in projects) {
          index.update(path, info.copy(buildTasks = if (path == root) tasks else info.name?.let { buildTasksOf(it, tasks) }.orEmpty()))
        }
        val external = ExternalSystemUtil.getExternalProjectInfo(project, Constants.SYSTEM_ID, root) ?: return@withContext
        val structure = external.externalProjectStructure ?: return@withContext
        replaceTasks(structure, root, projects, tasks)
        val manager = ExternalProjectsManagerImpl.getInstance(project)
        manager.updateExternalProjectData(external)
        manager.getExternalProjectsView(Constants.SYSTEM_ID)?.structure?.updateProjects(listOf(structure))
      }
    } catch (cause: CancellationException) {
      throw cause
    } catch (cause: Exception) {
      LOG.warn("Unable to refresh Elide build tasks for $root; retaining the previous task listing", cause)
    }
  }

  companion object {
    private val LOG = Logger.getInstance(ElideBuildTaskRefreshService::class.java)
    private val EXCLUDED_DIRECTORIES = setOf(".git", ".idea", ".dev", "node_modules")
    private val AUTHORING_EXTENSIONS = setOf("js", "mjs", "cjs", "ts", "mts", "cts", "tsx", "pkl")
    internal fun isAuthoringPath(path: String): Boolean {
      val parts = path.replace('\\', '/').split('/')
      if (parts.any { it in EXCLUDED_DIRECTORIES }) return false
      return parts.last().substringAfterLast('.', "") in AUTHORING_EXTENSIONS
    }

    internal fun replaceTasks(structure: DataNode<ProjectData>, root: String, projects: Map<String, ElideProjectInfo>, tasks: List<ElideBuildTaskInfo>) {
      ExternalSystemApiUtil.getChildren(structure, ElideBuildTasksData.KEY).toList().forEach { it.clear(true) }
      if (tasks.isEmpty()) return
      val parent = structure.createChild(ElideBuildTasksData.KEY, ElideBuildTasksData())
      fun add(parent: DataNode<ElideBuildTasksData>, entries: List<ElideBuildTaskInfo>) {
        entries.forEach { parent.createChild(ProjectKeys.TASK, TaskData(Constants.SYSTEM_ID, it.taskName, root, it.description.ifEmpty { null })) }
      }
      if (projects.size <= 1) {
        add(parent, tasks)
      } else {
        val names = projects.values.mapNotNull { it.name }.toSet()
        val rootName = projects[root]?.name ?: Path.of(root).fileName.toString()
        val groups = tasks.groupBy { it.taskScope?.takeIf { name -> name in names } ?: rootName }
        groups.forEach { (name, entries) -> add(parent.createChild(ElideBuildTasksData.KEY, ElideBuildTasksData(name)), entries) }
      }
    }
  }
}

class ElideBuildAuthoringStartupActivity : ProjectActivity {
  override suspend fun execute(project: Project) {
    project.service<ElideBuildTaskRefreshService>().start()
  }
}
