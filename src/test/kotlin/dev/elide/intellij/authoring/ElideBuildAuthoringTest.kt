package dev.elide.intellij.authoring

import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import dev.elide.intellij.Constants
import dev.elide.intellij.project.model.ElideBuildTaskInfo
import dev.elide.intellij.project.model.ElideBuildTasksData
import dev.elide.intellij.project.model.ElideProjectInfo
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ElideBuildAuthoringTest {
  @Test fun `member links may reach shared helpers but cannot escape the workspace`() = withTree { root ->
    val member = root.resolve("member").createDirectories()
    val helper = root.resolve("tasks.ts").also { it.writeText("export default () => {}") }
    assertEquals(helper.toRealPath(), ElideExtensionPaths.resolve("../tasks.ts", member, root, listOf(root)))
    assertNull(ElideExtensionPaths.resolve("../tasks.ts", member, root, listOf(member)))
    assertNull(ElideExtensionPaths.resolve("https://example.test/tasks.ts", member, root, listOf(root)))
    assertNull(ElideExtensionPaths.resolve(helper.toString(), member, root, listOf(root)))
    assertNull(ElideExtensionPaths.resolve("missing.ts", member, root, listOf(root)))
    assertNull(ElideExtensionPaths.resolve(".", member, root, listOf(root)))
  }

  @Test fun `symlinks cannot smuggle files outside the source tree`() = withTree { outer ->
    val root = outer.resolve("project").createDirectories()
    val external = outer.resolve("secret.ts").also { it.writeText("secret") }
    Files.createSymbolicLink(root.resolve("tasks.ts"), external)
    assertNull(ElideExtensionPaths.resolve("tasks.ts", root, root, listOf(outer)))
    assertNull(ElideExtensionPaths.resolve("../secret.ts", root, root, listOf(outer)))
  }

  @Test fun `task refresh replaces rather than appends and removes stale groups`() {
    val root = "/project"
    val structure = DataNode(ProjectKeys.PROJECT, ProjectData(Constants.SYSTEM_ID, "demo", root, root), null)
    val projects = mapOf(root to ElideProjectInfo(name = "demo"), "$root/core" to ElideProjectInfo(name = "core", workspaceRoot = root))
    ElideBuildTaskRefreshService.replaceTasks(structure, root, projects,
      listOf(ElideBuildTaskInfo("demo:old"), ElideBuildTaskInfo("core:greet")))
    ElideBuildTaskRefreshService.replaceTasks(structure, root, projects, listOf(ElideBuildTaskInfo("core:changed", "new description")))
    val tasks = ExternalSystemApiUtil.findAllRecursively(structure, ProjectKeys.TASK).map { it.data }
    assertEquals(listOf(":core:changed"), tasks.map { it.name })
    assertEquals(listOf(root), tasks.map { it.linkedExternalProjectPath })
    assertEquals(listOf("new description"), tasks.map { it.description })
    ElideBuildTaskRefreshService.replaceTasks(structure, root, projects, emptyList())
    assertEquals(emptyList(), ExternalSystemApiUtil.getChildren(structure, ElideBuildTasksData.KEY))
  }

  @Test fun `helper saves refresh tasks but generated build output does not`() {
    assertEquals(true, ElideBuildTaskRefreshService.isAuthoringPath("/project/tools/helper.mts"))
    assertEquals(true, ElideBuildTaskRefreshService.isAuthoringPath("/project/elide.pkl"))
    assertEquals(false, ElideBuildTaskRefreshService.isAuthoringPath("/project/.dev/generated/helper.ts"))
    assertEquals(false, ElideBuildTaskRefreshService.isAuthoringPath("/project/node_modules/example/index.js"))
  }

  private fun withTree(action: (Path) -> Unit) {
    val root = Files.createTempDirectory("elide-authoring")
    try { action(root) } finally {
      Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
  }
}
