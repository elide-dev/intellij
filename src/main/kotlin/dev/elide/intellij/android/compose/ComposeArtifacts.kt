package dev.elide.intellij.android.compose

import dev.elide.project.manifest.dependencies
import dev.elide.project.manifest.sourceSetsForArtifactOutput
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Workspace archive resources survive without shadowing Komodo's current program with old bytecode. */
internal object ComposeArtifacts {
  fun overlays(inputs: ComposeInputs, outputs: Map<String, Path>, directory: Path): Map<String, List<Path>> {
    val entries = inputs.classPath.toMutableSet()
    inputs.projectClassPath.forEach { output ->
      val parent = Path.of(output).parent
      listOf("kotlinc.args", "javac.args", "kapt.args").forEach { name ->
        val record = parent.resolve(name)
        if (Files.isRegularFile(record)) {
          entries.addAll(ComposeClasspath.recordedClassPath(Files.readAllLines(record)))
        }
      }
    }
    val result = linkedMapOf<String, List<Path>>()
    for (entry in entries) {
      val file = runCatching { Path.of(entry).toAbsolutePath().normalize() }.getOrNull() ?: continue
      if (!Files.isRegularFile(file) || !file.toString().endsWith(".jar")) continue
      for (project in inputs.modules.map { it.project }.distinctBy { it.root }) {
        val roots = setOf(project.root.toAbsolutePath().normalize(), project.root.toRealPath())
        val artifact = roots.firstNotNullOfOrNull { root ->
          val android = root.resolve(".dev/android")
          val jvm = root.resolve(".dev/artifacts")
          when {
            file.startsWith(android) && android.relativize(file).nameCount == 2 && file.fileName.toString() == "classes.jar" -> android.relativize(file).getName(0).toString() to true
            file.startsWith(jvm) && jvm.relativize(file).nameCount >= 2 -> jvm.relativize(file).getName(1).toString() to false
            else -> null
          }
        } ?: continue
        val sets = linkedSetOf<String>()
        fun include(name: String) {
          if (!sets.add(name)) return
          project.manifest.sources[name]?.dependencies(name)?.forEach(::include)
        }
        project.manifest.sourceSetsForArtifactOutput(artifact.first, artifact.second).forEach(::include)
        val classes = sets.flatMap { set -> listOf("kotlin", "java").mapNotNull { language -> outputs[project.root.resolve(".dev/jvm/classes/$set/$language").toString()] } }
        if (classes.isEmpty()) continue
        val hash = ComposeRuntime.hex(MessageDigest.getInstance("SHA-256").digest(file.toString().toByteArray())).take(16)
        val resources = directory.resolve("archive-resources/$hash.jar")
        Files.createDirectories(resources.parent)
        ZipFile(file.toFile()).use { archive -> ZipOutputStream(Files.newOutputStream(resources)).use { output ->
          archive.entries().asSequence().filter { !it.isDirectory && !it.name.endsWith(".class") && !it.name.endsWith(".kotlin_module") }.forEach { item ->
            output.putNextEntry(ZipEntry(item.name))
            archive.getInputStream(item).use { it.copyTo(output) }
            output.closeEntry()
          }
        } }
        val overlay = buildList(classes.size + 1) { addAll(classes); add(resources) }
        result[file.toString()] = overlay
        result[entry] = overlay
        break
      }
    }
    return result
  }
}
