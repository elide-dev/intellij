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
package dev.elide.intellij.project.model

import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.project.ExternalSystemSourceType
import com.intellij.openapi.externalSystem.model.project.LibraryPathType
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.roots.DependencyScope
import com.intellij.testFramework.junit5.TestApplication
import dev.elide.project.manifest.ElideManifests
import kotlin.io.path.Path
import kotlin.io.path.pathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the model built for a multi-project workspace: the modules of every project the sync resolved, and what one
 * project of the workspace declaring a dependency on another turns into.
 *
 * The fixtures are `elide manifest` output, captured per project of a four-member workspace; only the members this
 * covers are modelled here, which is enough for a member that consumes two siblings (`cli`) and one that is consumed
 * (`model`).
 */
@TestApplication
class ElideWorkspaceModelTest {
  private val root = Path("/projects/logstat")

  private fun manifest(name: String) = ElideManifests.parse(
    requireNotNull(ElideWorkspaceModelTest::class.java.getResourceAsStream("/manifest/workspace/$name.json"))
      .use { it.reader().readText() },
  )

  /** Where the workspace root resolves Maven dependencies to, shared by every project of the workspace. */
  private val guava = "$root/.dev/dependencies/m2/com/google/guava/guava/33.4.8-jre/guava-33.4.8-jre.jar"

  /**
   * What `model`'s build writes, and what the CLI puts on `parser`'s classpath for its `project("model")`.
   *
   * Written the way the CLI prints it: resolved against the consuming project, so the sibling is reached back
   * through the workspace root rather than named directly.
   */
  private val modelJar = "$root/parser/../model/.dev/artifacts/jar/model/model.jar"

  private val workspace = ElideResolvedWorkspace(
    root = ElideResolvedProject.of(root, manifest("root")),
    members = listOf(
      ElideResolvedProject.of(
        root.resolve("model"),
        manifest("model"),
        mapOf("main" to ElideClasspath(ElideClasspathUsage.COMPILE, listOf(guava))),
      ),
      ElideResolvedProject.of(
        root.resolve("parser"),
        manifest("parser"),
        mapOf("main" to ElideClasspath(ElideClasspathUsage.COMPILE, listOf(modelJar, guava))),
      ),
    ),
  )

  private val projectNode = ElideProjectModel.buildModel(
    workspace,
    listOf(
      ElideBuildTaskInfo("maven-dependencies", "Resolve and download Maven dependencies"),
      ElideBuildTaskInfo("model:jar", "Package compiled classes into a JAR archive"),
      ElideBuildTaskInfo("parser:test", "Run the project's tests"),
    ),
  )

  private val modules = ExternalSystemApiUtil.getChildren(projectNode, ProjectKeys.MODULE)

  private fun module(name: String) = modules.single { it.data.externalName == name }

  @Test fun `every project of the workspace contributes its own modules`() {
    // one module per project, named after it, plus one per source set of that project; the root's own source sets
    // come from the schema's defaults, which its manifest does not override
    assertEquals(
      listOf(
        "logstat",
        "logstat.main",
        "logstat.test",
        "model",
        "model.main",
        "model.test",
        "parser",
        "parser.main",
        "parser.test",
      ),
      modules.map { it.data.externalName },
    )

    // the directory each module belongs to, which is what the post-import step matches a manifest's settings against
    assertEquals(root.pathString, module("logstat.main").data.linkedExternalProjectPath)
    assertEquals(root.resolve("parser").pathString, module("parser.main").data.linkedExternalProjectPath)
  }

  @Test fun `a standalone project keeps unqualified source set modules`() {
    val standalone = ElideProjectModel.buildModel(ElideResolvedWorkspace.of(root.resolve("model"), manifest("model")))

    assertEquals(
      listOf("model", "main", "test"),
      ExternalSystemApiUtil.getChildren(standalone, ProjectKeys.MODULE).map { it.data.externalName },
    )
  }

  @Test fun `build tasks are grouped under the project declaring them`() {
    val tasksNode = ExternalSystemApiUtil.getChildren(projectNode, ElideBuildTasksData.KEY).single()
    val groups = ExternalSystemApiUtil.getChildren(tasksNode, ElideBuildTasksData.KEY)

    // every task of a workspace belongs to one of its projects, so the list itself holds none directly
    assertEquals(emptyList(), ExternalSystemApiUtil.getChildren(tasksNode, ProjectKeys.TASK))
    assertEquals(listOf("logstat", "model", "parser"), groups.map { it.data.project })

    fun tasksOf(project: String) = ExternalSystemApiUtil
      .getChildren(groups.single { it.data.project == project }, ProjectKeys.TASK)
      .map { it.data }

    // the root's tasks are the ones the CLI left unqualified; a member's keep the scope, which is the name a build
    // launched from the workspace root resolves them by
    assertEquals(listOf(":maven-dependencies"), tasksOf("logstat").map { it.name })
    assertEquals(listOf(":model:jar"), tasksOf("model").map { it.name })
    assertEquals(root.pathString, tasksOf("parser").single().linkedExternalProjectPath)
  }

  @Test fun `a project reference becomes a dependency on the modules backing the referenced artifact`() {
    val dependency = ExternalSystemApiUtil.getChildren(module("parser.main"), ProjectKeys.MODULE_DEPENDENCY)
      .single()
      .data

    assertEquals("model.main", dependency.target.externalName)

    // exported, so `parser`'s tests inherit it through the main module they already depend on
    assertTrue(dependency.isExported)
    assertEquals(DependencyScope.COMPILE, dependency.scope)
  }

  @Test fun `a reference naming an artifact resolves to the source sets that artifact packages`() {
    // `cli` references `parser` bare and `report` by artifact name; only the projects modelled here are wired
    val cli = ElideResolvedProject.of(root.resolve("cli"), manifest("cli"))
    val node = ElideProjectModel.buildModel(workspace.copy(members = workspace.members + cli))

    val dependencies = ExternalSystemApiUtil.getChildren(node, ProjectKeys.MODULE)
      .single { it.data.externalName == "cli.main" }
      .let { ExternalSystemApiUtil.getChildren(it, ProjectKeys.MODULE_DEPENDENCY) }

    assertEquals(listOf("parser.main"), dependencies.map { it.data.target.externalName })
  }

  @Test fun `an artifact reached through a sibling is wired from the resolved classpath`() {
    // `cli` declares `parser` alone, yet the CLI puts `model`'s JAR on its classpath too: an artifact carries the
    // classpath of the project building it, so a consumer compiles against siblings it never names
    val parserJar = "$root/cli/../parser/.dev/artifacts/jar/parser/parser.jar"
    val modelJar = "$root/cli/../model/.dev/artifacts/jar/model/model.jar"
    val classpath = ElideClasspath(ElideClasspathUsage.COMPILE, listOf(parserJar, modelJar, guava))

    val cli = ElideResolvedProject.of(
      root.resolve("cli"),
      manifest("cli"),
      mapOf("main" to classpath, "test" to classpath),
    )
    val node = ElideProjectModel.buildModel(workspace.copy(members = workspace.members + cli))

    fun dependencies(module: String) = ExternalSystemApiUtil.getChildren(node, ProjectKeys.MODULE)
      .single { it.data.externalName == module }
      .let { ExternalSystemApiUtil.getChildren(it, ProjectKeys.MODULE_DEPENDENCY) }
      .map { it.data.target.externalName to it.data.scope }

    assertEquals(
      listOf("parser.main" to DependencyScope.COMPILE, "model.main" to DependencyScope.COMPILE),
      dependencies("cli.main"),
    )

    // the test source set resolves the same artifacts on its own classpath, and takes them at test scope rather
    // than only inheriting them through the main module
    assertEquals(
      listOf(
        "cli.main" to DependencyScope.TEST,
        "parser.main" to DependencyScope.TEST,
        "model.main" to DependencyScope.TEST,
      ),
      dependencies("cli.test"),
    )
  }

  @Test fun `an artifact is matched by the name its build writes it under`() {
    // `report`'s JAR names itself, so its build writes it to `jar/report-fat/`: that directory name is all a
    // resolved classpath entry carries about the artifact, and it is not the key the artifact is declared under
    val report = ElideResolvedProject.of(root.resolve("report"), manifest("report"))
    val reportJar = "$root/cli/../report/.dev/artifacts/jar/report-fat/report-fat.jar"

    val cli = ElideResolvedProject.of(
      root.resolve("cli"),
      manifest("cli"),
      // only the test source set resolved the artifact, which nothing `cli` declares accounts for: its reference to
      // `report` is a compile-scope one, and reaches `cli.main`
      mapOf("test" to ElideClasspath(ElideClasspathUsage.COMPILE, listOf(reportJar))),
    )

    val node = ElideProjectModel.buildModel(workspace.copy(members = workspace.members + listOf(report, cli)))
    val dependencies = ExternalSystemApiUtil.getChildren(node, ProjectKeys.MODULE)
      .single { it.data.externalName == "cli.test" }
      .let { ExternalSystemApiUtil.getChildren(it, ProjectKeys.MODULE_DEPENDENCY) }

    assertEquals(listOf("cli.main", "report.main"), dependencies.map { it.data.target.externalName })
    assertEquals(DependencyScope.TEST, dependencies.last().data.scope)
  }

  @Test fun `the referenced project's artifact is not offered as a library`() {
    val libraries = ExternalSystemApiUtil.getChildren(projectNode, ProjectKeys.LIBRARY)
      .flatMap { it.data.getPaths(LibraryPathType.BINARY) }

    // the JAR is a build output of another project, and does not exist until that project is built; matching it
    // takes normalizing the entry, which the CLI prints relative to the project consuming it
    assertEquals(emptyList(), libraries.filter { it.endsWith("model.jar") })

    // the sibling's own dependencies, which the CLI reports on the same classpath, are ordinary libraries
    assertEquals(listOf(guava), libraries)
  }

  @Test fun `each project is rooted at the directory holding its manifest`() {
    fun roots(module: String) = ExternalSystemApiUtil.getChildren(module(module), ProjectKeys.CONTENT_ROOT)
      .map { it.data }

    // the module standing for a project owns the project directory itself, which is what puts the manifest, and
    // everything else a member directory holds, inside the IDE's model of the workspace
    assertEquals(root.pathString, roots("logstat").single().rootPath)
    assertEquals(root.resolve("model").pathString, roots("model").single().rootPath)

    // the source sets keep the directories they declare, nested inside the project they belong to; `model` declares
    // its tests at the top level, which owns itself rather than taking the project directory as a root
    assertEquals(root.resolve("model/src/main").pathString, roots("model.main").single().rootPath)
    assertEquals(root.resolve("model/test").pathString, roots("model.test").single().rootPath)
  }

  @Test fun `the project root excludes what the build writes`() {
    val excluded = ExternalSystemApiUtil.getChildren(module("model"), ProjectKeys.CONTENT_ROOT)
      .single()
      .data
      .getPaths(ExternalSystemSourceType.EXCLUDED)

    // dependencies and build outputs are read as libraries and artifacts; indexing them as project content would
    // walk the whole installed dependency tree
    assertEquals(listOf(root.resolve("model/.dev").pathString), excluded.map { it.path })
  }

  @Test fun `a source set covering the whole project keeps a single content root`() {
    val flat = ElideManifests.parse(
      """{"name":"flat","sources":{"main":{"@type":"elide.sources.SourceSet.OfString","value":"**/*.kt"}}}""",
    )
    val node = ElideProjectModel.buildModel(ElideResolvedWorkspace.of(root.resolve("flat"), flat))

    val roots = ExternalSystemApiUtil.getChildren(node, ProjectKeys.MODULE)
      .flatMap { ExternalSystemApiUtil.getChildren(it, ProjectKeys.CONTENT_ROOT) }
      .map { it.data }

    // the source set already owns the project directory, and the platform rejects the same directory as the content
    // root of two modules: the exclusion lands on the root there is
    val single = roots.single()
    assertEquals(root.resolve("flat").pathString, single.rootPath)
    assertEquals(
      listOf(root.resolve("flat/.dev").pathString),
      single.getPaths(ExternalSystemSourceType.EXCLUDED).map { it.path },
    )
  }

  @Test fun `each project is attached its own data, with the tasks it accepts`() {
    val data = ExternalSystemApiUtil.getChildren(projectNode, ElideProjectData.PROJECT_KEY).map { it.data }
    assertEquals(listOf("logstat", "model", "parser"), data.map { it.name })

    val rootData = data.single { it.name == "logstat" }
    assertNull(rootData.workspaceRoot)
    assertEquals(listOf(root.resolve("model").pathString, root.resolve("parser").pathString), rootData.members)

    // a build started at the root reaches every project, so it keeps the listing as the CLI printed it
    assertEquals(
      listOf("maven-dependencies", "model:jar", "parser:test"),
      rootData.buildTasks.map { it.name },
    )

    val parserData = data.single { it.name == "parser" }
    assertEquals(root.pathString, parserData.workspaceRoot)
    assertEquals(emptyList(), parserData.members)

    // a member is the project in focus for a run rooted at it, and accepts its own targets unqualified
    assertEquals(listOf("test"), parserData.buildTasks.map { it.name })
  }
}
