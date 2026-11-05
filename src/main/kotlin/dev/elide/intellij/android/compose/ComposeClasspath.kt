package dev.elide.intellij.android.compose

import dev.elide.intellij.project.model.ElideProjectModel
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.math.BigInteger
import java.util.Locale

/** Runtime conflict rules match elide-vscode core/composeInputs and core/mavenVersion (MIT). */
internal object ComposeClasspath {
  private data class Library(val identity: String, val name: String, val version: String, val location: String, val root: String, val archive: Boolean)
  private fun library(entry: String): Library? {
    val normalized = Path.of(entry).toAbsolutePath().normalize().toString().replace('\\', '/')
    val aarMarker = "/.dev/android/libraries/"
    val aar = normalized.lastIndexOf(aarMarker)
    if (aar >= 0 && normalized.endsWith("/classes.jar")) {
      val parts = normalized.substring(aar + aarMarker.length).split('/')
      if (parts.size < 4) return null
      val name = "${parts.dropLast(3).joinToString(".")}:${parts[parts.size - 3]}"
      return Library("aar:$name", name, parts[parts.size - 2], normalized.substringBeforeLast('/'), normalized.substring(0, aar), true)
    }
    val marker = "/.dev/dependencies/m2/"
    val repository = normalized.lastIndexOf(marker)
    if (repository < 0) return null
    val parts = normalized.substring(repository + marker.length).split('/')
    if (parts.size < 4) return null
    val artifact = parts[parts.size - 3]
    val version = parts[parts.size - 2]
    val file = parts.last()
    val stem = "$artifact-$version"
    if (!file.startsWith(stem) || !file.endsWith(".jar")) return null
    val name = "${parts.dropLast(3).joinToString(".")}:$artifact"
    return Library("jar:$name:${file.removePrefix(stem)}", name, version, normalized, normalized.substring(0, repository), false)
  }

  fun select(entries: List<String>, owner: Path): List<String> {
    val own = owner.toAbsolutePath().normalize().toString().replace('\\', '/')
    val libraries = entries.mapNotNull(::library)
    val hasGuava = libraries.any { !it.archive && it.name == "com.google.guava:guava" }
    val selected = libraries.groupBy { it.identity }.mapValues { (_, versions) ->
      val eligible = versions.filterNot { it.name == "com.google.guava:listenablefuture" && it.version == "9999.0-empty-to-avoid-conflict-with-guava" && !hasGuava }.ifEmpty { versions }
      eligible.reduce { best, candidate ->
        val relation = compareVersions(candidate.version, best.version)
        if (relation > 0 || relation == 0 && candidate.root == own && best.root != own) candidate else best
      }
    }
    val archives = libraries.filter { it.archive }.associateBy { it.location }
    return entries.mapNotNull { entry ->
      val jar = library(entry)
      if (jar != null && !jar.archive) return@mapNotNull selected.getValue(jar.identity).location
      val normalized = Path.of(entry).toAbsolutePath().normalize().toString().replace('\\', '/')
      val archive = archives.entries.firstOrNull { normalized.startsWith("${it.key}/") }?.value ?: return@mapNotNull entry
      val chosen = selected.getValue(archive.identity).location + normalized.removePrefix(archive.location)
      if (Files.exists(Path.of(chosen))) chosen else if (!normalized.endsWith("/classes.jar")) null else error("Missing selected AAR classes: $chosen")
    }.distinct()
  }

  /** Exploded AARs keep their inline Kotlin sources in the Maven repository, not beside classes.jar. */
  fun sourceArchives(entries: List<String>, repositories: Set<Path>): List<String> = buildSet {
    for (entry in entries) {
      if (!entry.endsWith(".jar")) continue
      val coordinate = ElideProjectModel.explodedLibraryCoordinate(entry)
      if (coordinate != null) {
        val (group, artifact, version) = coordinate.split(':', limit = 3)
        val relative = "${group.replace('.', '/')}/$artifact/$version/$artifact-$version-sources.jar"
        for (repository in repositories) {
          val candidate = repository.resolve(relative)
          if (Files.isRegularFile(candidate)) add(candidate.toString())
        }
      } else {
        val candidate = Path.of(entry.removeSuffix(".jar") + "-sources.jar")
        if (Files.isRegularFile(candidate)) add(candidate.toString())
      }
    }
  }.toList()

  /** Elide's compiler records contain one argument per line, not a shell command. */
  fun recordedClassPath(arguments: List<String>): List<String> = buildList {
    arguments.forEachIndexed { index, argument ->
      val name = argument.substringBefore('=')
      if (name == "-classpath" || name == "-cp" || name == "--class-path" || name == "--module-path") {
        val value = if ('=' in argument) argument.substringAfter('=') else arguments.getOrNull(index + 1)
        value?.split(File.pathSeparatorChar)?.filter(String::isNotBlank)?.let(::addAll)
      }
    }
  }

  private data class Item(val kind: Int, val text: String = "", val qualifier: Int = 0) {
    fun padding(): Int = when (kind) { 4 -> BigInteger(text).signum(); 3 -> 1; else -> qualifier.compareTo(0) }
  }
  private val qualifiers = mapOf("alpha" to -5, "beta" to -4, "milestone" to -3, "cr" to -2, "rc" to -2,
    "snapshot" to -1, "ga" to 0, "final" to 0, "release" to 0, "" to 0, "sp" to 1)
  private fun parse(version: String): MutableList<Item> {
    val text = version.ifEmpty { "0" }
    val items = mutableListOf<Item>()
    var index = 0
    while (index < text.length) {
      var state = -2
      var start = index
      var end = text.length
      var terminatedByNumber = false
      while (index < text.length) {
        val c = text[index]
        if (c in ".-_") { end = index; index++; break }
        if (c in '0'..'9') {
          if (state == -1) { end = index; terminatedByNumber = true; break }
          if (state == 0) start++
          state = if (state > 0 || c != '0') 1 else 0
        } else {
          if (state >= 0) { end = index; break }
          state = -1
        }
        index++
      }
      val token = if (end > start) text.substring(start, end) else "0"
      if (end <= start || state >= 0) items.add(Item(4, token)) else {
        val lower = token.lowercase(Locale.ROOT)
        val abbreviated = if (terminatedByNumber) mapOf("a" to "alpha", "b" to "beta", "m" to "milestone")[lower] else null
        val qualifier = qualifiers[abbreviated ?: lower]
        items.add(if (qualifier == null) Item(3, lower) else Item(2, qualifier = qualifier))
      }
    }
    var number: Boolean? = null
    var end = items.lastIndex
    for (i in items.lastIndex downTo 1) {
      val item = items[i]
      val isNumber = item.kind == 4
      if (isNumber != number) { end = i; number = isNumber }
      if (end == i && (i == items.lastIndex || (items[i - 1].kind == 4) == isNumber) && item.padding() == 0) { items.removeAt(i); end-- }
    }
    return items
  }
  internal fun compareVersions(a: String, b: String): Int {
    val left = parse(a)
    val right = parse(b)
    fun padding(items: List<Item>, index: Int, number: Boolean?): Int {
      for (i in index until items.size) {
        if (number != null && number != (items[i].kind == 4)) break
        val relation = items[i].padding()
        if (relation != 0) return relation
      }
      return 0
    }
    var number = true
    var index = 0
    while (true) {
      if (index >= left.size && index >= right.size) return 0
      if (index >= left.size) return -padding(right, index, null)
      if (index >= right.size) return padding(left, index, null)
      val first = left[index]; val second = right[index]
      val firstNumber = first.kind == 4
      if (firstNumber != (second.kind == 4)) return if (number == firstNumber) padding(left, index, number) else -padding(right, index, number)
      val relation = when {
        first.kind != second.kind -> first.kind.compareTo(second.kind)
        first.kind == 4 -> BigInteger(first.text).compareTo(BigInteger(second.text))
        first.kind == 3 -> first.text.compareTo(second.text)
        else -> first.qualifier.compareTo(second.qualifier)
      }
      if (relation != 0) return relation
      number = firstNumber
      index++
    }
  }
}
