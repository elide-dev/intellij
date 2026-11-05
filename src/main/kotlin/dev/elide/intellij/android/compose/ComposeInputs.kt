package dev.elide.intellij.android.compose

import com.intellij.openapi.project.Project
import dev.elide.intellij.cli.ElideCommandLine
import dev.elide.intellij.cli.classpath
import dev.elide.intellij.cli.manifest
import dev.elide.intellij.project.model.ElideClasspathUsage
import dev.elide.intellij.project.model.ElideProjectInfo
import dev.elide.intellij.service.elideProjectIndex
import dev.elide.project.manifest.*
import dev.elide.tooling.manifest.android.AndroidApplication
import dev.elide.tooling.manifest.android.AndroidTarget
import dev.elide.tooling.manifest.project.ProjectModule
import dev.elide.tooling.manifest.sources.SourceSetType
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

internal data class ComposeProject(val root: Path, val info: ElideProjectInfo, val manifest: ProjectModule) {
  val name get() = manifest.projectName(root.fileName.toString())
  val buildRoot get() = info.workspaceRoot?.let(Path::of) ?: root
  fun task(name: String) = if (info.workspaceRoot != null || info.members.isNotEmpty()) "${this.name}:$name" else name
}
internal data class ComposeModule(val project: ComposeProject, val sourceSet: String, val distance: Int)
internal data class ComposeApplication(val project: ComposeProject, val artifact: String, val target: AndroidTarget, val modules: List<ComposeModule>, val distance: Int) {
  override fun toString() = "${project.name}:$artifact"
}
internal data class ComposeInputs(val classPath: List<String>, val projectClassPath: List<String>, val namespace: String, val resourceApkPath: String, val modules: List<ComposeModule>, val compileClassPath: List<String> = classPath)

internal object ComposeInputResolver {
  suspend fun applications(project: Project, file: Path): List<ComposeApplication> {
    val index = project.elideProjectIndex.entries.toList()
    val projects = index.map { (root, info) ->
      val directory = Path.of(root)
      ComposeProject(directory, info, ElideCommandLine.resolve(project, info.workspaceRoot ?: root, directory).manifest().resolveSources(directory))
    }
    return projects.flatMap { owner -> owner.manifest.artifacts.mapNotNull { (name, value) ->
      if (value !is AndroidApplication) return@mapNotNull null
      val modules = closure(owner, value, projects)
      val distance = modules.filter { module ->
        module.project.manifest.sources[module.sourceSet]?.sourcePaths(module.project.root, module.sourceSet)?.any { matches(module.project.root, it, file) } == true
      }.minOfOrNull { it.distance } ?: return@mapNotNull null
      ComposeApplication(owner, name, value, modules, distance)
    } }.sortedBy { it.distance }
  }

  private fun closure(owner: ComposeProject, target: AndroidTarget, projects: List<ComposeProject>): List<ComposeModule> {
    val queue = ArrayDeque<ComposeModule>()
    owner.manifest.packagedSourceSets(target).forEach { queue.add(ComposeModule(owner, it, 0)) }
    val result = linkedMapOf<Pair<Path, String>, ComposeModule>()
    while (queue.isNotEmpty()) {
      val module = queue.removeFirst()
      val set = module.project.manifest.sources[module.sourceSet] ?: error("Missing source set ${module.sourceSet}; sync the workspace.")
      if (set.effectiveType(module.sourceSet) == SourceSetType.Test || result.putIfAbsent(module.project.root to module.sourceSet, module) != null) continue
      set.dependencies(module.sourceSet).forEach { queue.add(ComposeModule(module.project, it, module.distance + 1)) }
      val maven = module.project.manifest.dependencies.maven
      (maven.packages + maven.devPackages + maven.modules + maven.runtimeOnly)
        .mapNotNull { it.projectArtifact }.distinct().forEach { reference ->
        val dependency = projects.find { it.name == reference.project && it.buildRoot == owner.buildRoot }
          ?: error("Cannot resolve workspace project ${reference.project}; sync the whole workspace.")
        val sets = dependency.manifest.referencedSourceSets(reference.artifact)
        require(sets.isNotEmpty()) { "Cannot resolve ${reference.project}'s runtime artifact; sync the whole workspace." }
        sets.forEach { queue.add(ComposeModule(dependency, it, module.distance + 1)) }
      }
    }
    return result.values.toList()
  }

  internal fun compilationOrder(modules: List<ComposeModule>): List<ComposeModule> {
    val byKey = modules.associateBy { it.project.root to it.sourceSet }
    val projects = modules.map { it.project }.distinctBy { it.root }
    val visited = mutableSetOf<Pair<Path, String>>()
    val visiting = mutableSetOf<Pair<Path, String>>()
    val ordered = mutableListOf<ComposeModule>()
    fun visit(module: ComposeModule) {
      val key = module.project.root to module.sourceSet
      if (key in visited) return
      require(visiting.add(key)) { "Cyclic preview source-set dependency at ${module.project.name}:${module.sourceSet}" }
      module.project.manifest.sources[module.sourceSet]?.dependencies(module.sourceSet)?.forEach { name ->
        byKey[module.project.root to name]?.let(::visit)
      }
      val maven = module.project.manifest.dependencies.maven
      (maven.packages + maven.devPackages + maven.modules + maven.runtimeOnly).mapNotNull { it.projectArtifact }.distinct().forEach { reference ->
        val owner = projects.find { it.name == reference.project && it.buildRoot == module.project.buildRoot } ?: return@forEach
        owner.manifest.referencedSourceSets(reference.artifact).forEach { name -> byKey[owner.root to name]?.let(::visit) }
      }
      visiting.remove(key)
      visited.add(key)
      ordered.add(module)
    }
    modules.forEach(::visit)
    return ordered
  }

  suspend fun build(project: Project, app: ComposeApplication, log: (String) -> Unit): ComposeInputs {
    val tasks = linkedSetOf(app.project.task("android-r-${app.artifact}"))
    for (module in app.modules) {
      for (language in listOf("kotlin", "java")) {
        val name = "compile-$language-${module.sourceSet}"
        module.project.info.buildTasks.firstOrNull { it.name.substringAfterLast(':') == name }?.let { tasks.add(module.project.task(name)) }
      }
    }
    require(tasks.size > 1) { "No JVM compilation tasks are indexed. Sync the Elide project before previewing." }
    val cli = ElideCommandLine.resolve(project, app.project.buildRoot.toString(), app.project.buildRoot)
    cli.invoke("build", *tasks.toTypedArray(), onOutput = { line, _ -> log(line) })
    val stage = app.project.root.resolve(".dev/android/${app.artifact}")
    val namespace = manifestPackage(stage.resolve("AndroidManifest.xml"))
    val resources = stage.resolve("resources.apk")
    require(Files.isRegularFile(resources)) { "Linked resources are missing: $resources" }
    val rClasses = stage.resolve("r-classes")
    require(Files.isDirectory(rClasses)) { "Android R classes are missing: $rClasses" }
    val outputs = app.modules.flatMap { module -> listOf("kotlin", "java").map {
      module.project.root.resolve(".dev/jvm/classes/${module.sourceSet}/$it")
    }.filter(Files::isDirectory) }.map(Path::toString).distinct()
    val entries = linkedSetOf(rClasses.toString()).apply { addAll(outputs) }
    val compileEntries = linkedSetOf(rClasses.toString()).apply { addAll(outputs) }
    for (module in app.modules) {
      val moduleCli = ElideCommandLine.resolve(project, app.project.buildRoot.toString(), module.project.root)
      compileEntries.addAll(moduleCli.classpath(module.sourceSet, ElideClasspathUsage.COMPILE).entries)
      val record = module.project.root.resolve(".dev/jvm/classes/${module.sourceSet}/kotlinc.args")
      if (Files.isRegularFile(record)) {
        val physicalRoot = app.project.buildRoot.toRealPath()
        val logicalRoot = app.project.buildRoot.toAbsolutePath().normalize()
        compileEntries.addAll(ComposeClasspath.recordedClassPath(Files.readAllLines(record)).map { entry ->
          val path = module.project.root.resolve(entry).toAbsolutePath().normalize()
          (if (path.startsWith(physicalRoot)) logicalRoot.resolve(physicalRoot.relativize(path)) else path).toString()
        })
      }
      entries.addAll(moduleCli.classpath(module.sourceSet, ElideClasspathUsage.RUNTIME).entries.filterNot {
        Regex("[/\\\\]platforms[/\\\\]android-[^/\\\\]+[/\\\\]android\\.jar$").containsMatchIn(it)
      })
    }
    val runtime = ComposeClasspath.select(entries.toList(), app.project.root)
    runtime.forEach { require(Files.exists(Path.of(it))) { "Missing Compose runtime classpath entry: $it" } }
    return ComposeInputs(runtime, outputs, namespace, resources.toString(), app.modules, ComposeClasspath.select(compileEntries.toList(), app.project.root))
  }

  internal fun matches(root: Path, selector: String, file: Path): Boolean {
    val absolute = root.resolve(selector).normalize()
    if (!selector.any { it in "*?[{" }) return file.normalize().startsWith(absolute)
    val relative = runCatching { root.relativize(file) }.getOrNull() ?: return false
    return FileSystems.getDefault().getPathMatcher("glob:$selector").matches(relative) ||
      (selector.startsWith("**/") && FileSystems.getDefault().getPathMatcher("glob:${selector.removePrefix("**/")}").matches(relative))
  }
  internal fun manifestPackage(path: Path): String {
    val factory = DocumentBuilderFactory.newInstance()
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    return factory.newDocumentBuilder().parse(path.toFile()).documentElement.getAttribute("package").also {
      require(it.isNotBlank()) { "Merged manifest has no package: $path" }
    }
  }
}
