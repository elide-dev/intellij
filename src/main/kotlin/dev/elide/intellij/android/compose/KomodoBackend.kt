package dev.elide.intellij.android.compose

import com.google.gson.JsonObject
import dev.elide.intellij.project.model.ElideSourceRoots
import dev.elide.project.manifest.dependencies
import dev.elide.project.manifest.projectArtifact
import dev.elide.project.manifest.referencedSourceSets
import dev.elide.project.manifest.sourcePaths
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO

/** One owned workspace compiler and live gallery. All operations run off the event-dispatch thread. */
internal class KomodoBackend(
  private val inputs: ComposeInputs,
  private val runtime: ComposeRuntime,
  private val source: Path,
  private val javaHome: String,
  private val log: (String) -> Unit,
  private val cardsChanged: (List<ComposeCard>) -> Unit,
  private val failed: (Throwable) -> Unit,
) : AutoCloseable {
  private val directory = Files.createTempDirectory("elide-komodo-preview-")
  private val closed = AtomicBoolean()
  private val transport = KomodoTransport(log, ::event, failed)
  private val lock = Any()
  private val cards = linkedMapOf<String, ComposeCard>()
  private var previews = emptyList<JsonObject>()
  private var entries = emptyList<Map<String, Any?>>()
  private var texts = emptyMap<Path, String>()
  private val opened = mutableMapOf<Path, Int>()
  @Volatile private var rejected = false
  private var started = false
  @Volatile private var acceptingFrames = false
  @Volatile private var acceptingInput = false
  private val held = mutableSetOf<String>()
  private val pressed = mutableMapOf<String, Pair<Int, Int>>()
  @Volatile private var frameNanos = 0L
  private val own = inputs.projectClassPath.toSet()
  private val overlays by lazy { ComposeArtifacts.overlays(inputs, inputs.projectClassPath.associateWith { Path.of(it) }, directory) }
  private fun overlay(paths: List<String>): List<String> = paths.flatMap { path -> overlays[path]?.map(Path::toString) ?: listOf(path) }.distinct()

  private fun manifest(): Path {
    val modules = inputs.modules
    val names = modules.mapIndexed { i, m -> (m.project.root to m.sourceSet) to "preview_$i" }.toMap()
    val properties = Properties()
    properties.setProperty("modules", names.values.joinToString(","))
    properties.setProperty("cache", "off")
    fun paths(values: List<String>): String {
      require(values.none { ',' in it || '\n' in it || '\r' in it }) { "Komodo cannot represent a project path containing a comma or newline" }
      return values.joinToString(",")
    }
    for (module in modules) {
      val name = names.getValue(module.project.root to module.sourceSet)
      val set = module.project.manifest.sources.getValue(module.sourceSet)
      val generated = listOf("java", "kapt").map { module.project.root.resolve(".dev/jvm/generated-sources/${module.sourceSet}/$it") }
        .filter(Files::isDirectory).map(Path::toString)
      val roots = (ElideSourceRoots.collect(module.project.root, set.sourcePaths(module.project.root, module.sourceSet)).values.flatten() + generated).distinct()
      require(roots.isNotEmpty()) { "No source roots for ${module.project.name}:${module.sourceSet}" }
      properties.setProperty("module.$name.sources", paths(roots))
      properties.setProperty("module.$name.classpath", paths(overlay(inputs.compileClassPath).filterNot(own::contains)))
      val dependencies = set.dependencies(module.sourceSet).mapNotNull { names[module.project.root to it] }.toMutableSet()
      val maven = module.project.manifest.dependencies.maven
      (maven.packages + maven.devPackages + maven.modules + maven.runtimeOnly).mapNotNull { it.projectArtifact }.distinct().forEach { reference ->
        val target = modules.firstOrNull { it.project.name == reference.project && it.project.buildRoot == module.project.buildRoot }?.project ?: return@forEach
        target.manifest.referencedSourceSets(reference.artifact).mapNotNullTo(dependencies) { names[target.root to it] }
      }
      if (dependencies.isNotEmpty()) properties.setProperty("module.$name.depends", dependencies.joinToString(","))
    }
    return directory.resolve("project.properties").also { path -> Files.newOutputStream(path).use { properties.store(it, "Elide IntelliJ preview-only Komodo workspace") } }
  }

  fun refresh(documents: Map<Path, String>, check: () -> Unit) {
    invalidate()
    blur()
    for (path in documents.keys + setOf(source)) {
      require(path.isAbsolute && path == path.normalize() && inputs.modules.any { module ->
        module.project.manifest.sources[module.sourceSet]?.sourcePaths(module.project.root, module.sourceSet)?.any {
          ComposeInputResolver.matches(module.project.root, it, path)
        } == true
      }) { "Preview source is outside the selected application's source sets: $path" }
    }
    val current = linkedMapOf(source to (documents[source] ?: Files.readString(source)))
    // Keep program files after saving: a later edit must never resurrect their old build bytecode.
    for (file in texts.keys + documents.keys) {
      require(file.toString().endsWith(".kt") || documents[file] == Files.readString(file)) { "Unsaved Java source is not supported by Komodo's Kotlin compiler: $file. Save and rebuild it before previewing; saved bytecode is not substituted for editor text." }
      if (file.toString().endsWith(".kt")) current[file] = documents[file] ?: Files.readString(file)
    }
    if (!started) {
      transport.start(KomodoTransport.installation(), manifest(), javaHome, inputs.modules.first().project.buildRoot, check)
      synchronize(current, check)
      transport.connect(source, check)
      started = true
    } else synchronize(current, check)
    val discovered = transport.request("komodo/previews", mapOf("textDocument" to mapOf("uri" to source.toUri().toString())), check).asJsonObject
    val errors = discovered.getAsJsonArray("errors")
    if (errors != null && errors.size() > 0) {
      val diagnostics = errors.map { value ->
        val diagnostic = value.asJsonObject
        val line = diagnostic.getAsJsonObject("range")?.getAsJsonObject("start")?.get("line")?.asInt?.plus(1)
        "$source${line?.let { ":$it" }.orEmpty()}: ${diagnostic.string("message").orEmpty()}"
      }
      diagnostics.forEach { log("$it\n") }
      error("Komodo compilation failed: ${diagnostics.first()}")
    }
    previews = discovered.getAsJsonArray("previews")?.map { it.asJsonObject }.orEmpty()
    val nextEntries = previewEntries(previews)
    val healthy = synchronized(lock) { cards.isNotEmpty() && cards.values.all { it.image != null && it.error == null } }
    // Provider values are expanded when entries are hosted; a source delta alone cannot add/remove their scenes.
    val providersChanged = texts != current && nextEntries.any { it["methodParameter"] != null }
    if (healthy && !providersChanged && texts.keys == current.keys && entries == nextEntries && texts.isNotEmpty() && entries.isNotEmpty()) {
      if (texts != current) {
        acceptingFrames = false
        rejected = false
        val answer = transport.exchange(mapOf("command" to "sources", "files" to current.mapKeys { it.key.toString() }), 300_000, check) {
          it.string("event") == "compile" || it.string("event") == "error"
        }
        if (answer.string("event") == "compile" && answer.string("outcome") == "failure") error("Komodo could not compile the current editor buffers; see the log")
        if (answer.string("event") == "compile") {
          // Compile precedes delta application. The queued frame is a render-thread barrier.
          val barrier = frameNanos + 1
          val barrierAnswer = transport.exchange(mapOf("command" to "frame", "nanos" to barrier), 60_000, check) {
            it.string("event") == "frame" && it.get("nanos")?.asLong == barrier || it.string("event") == "error"
          }
          if (!rejected && barrierAnswer.string("event") == "frame") {
            texts = current
            acceptingFrames = true
            event(barrierAnswer)
            transport.send(mapOf("command" to "frame"))
            acceptingInput = true
            return
          }
        }
      } else {
        acceptingFrames = true
        acceptingInput = true
        transport.send(mapOf("command" to "frame"))
        publish()
        return
      }
    }
    configure(current, nextEntries, check)
  }

  fun snapshot(): List<ComposeCard> = synchronized(lock) { cards.values.toList() }

  private fun synchronize(documents: Map<Path, String>, check: () -> Unit) {
    val updates = documents.map { (path, text) ->
      val version = (opened[path] ?: 0) + 1
      val uri = path.toUri().toString()
      if (opened.put(path, version) == null) transport.notify("textDocument/didOpen", mapOf("textDocument" to mapOf("uri" to uri, "languageId" to "kotlin", "version" to version, "text" to text)))
      else transport.notify("textDocument/didChange", mapOf("textDocument" to mapOf("uri" to uri, "version" to version), "contentChanges" to listOf(mapOf("text" to text))))
      mapOf("uri" to uri, "version" to version, "text" to text)
    }
    transport.request("komodo/preview/sync", mapOf("documents" to updates), check)
  }

  private fun configure(current: Map<Path, String>, nextEntries: List<Map<String, Any?>>, check: () -> Unit) {
    acceptingFrames = false
    val repositories = inputs.modules.flatMap { module ->
      val repository = module.project.manifest.dependencies.maven.localRepository ?: ".dev/dependencies/m2"
      listOf(module.project.root.resolve(repository), module.project.buildRoot.resolve(repository))
    }.toSet()
    val inline = ComposeClasspath.sourceArchives(inputs.compileClassPath, repositories)
    val answer = transport.exchange(mapOf("command" to "configure", "host" to "layoutlib", "namespace" to inputs.namespace,
      "resourceApkPath" to inputs.resourceApkPath, "classPath" to overlay(inputs.classPath).filterNot(own::contains), "projectClassPath" to inputs.projectClassPath,
      "layoutlib" to runtime.layoutlib.toString(), "rendererClassPath" to runtime.classpath.map(Path::toString), "sources" to current.keys.map(Path::toString),
      "texts" to current.mapKeys { it.key.toString() }, "inlineSources" to inline, "compile" to mapOf("classPath" to overlay(inputs.compileClassPath).filterNot(own::contains))), 300_000, check) {
      it.string("event") == "configured" || it.string("event") == "error" && it.string("kind") == "configure"
    }
    require(answer.string("event") == "configured") { answer.string("message") ?: "Komodo refused the current program" }
    texts = current
    entries = nextEntries
    synchronized(lock) { cards.clear() }
    val hosted = transport.exchange(mapOf("command" to "entries", "entries" to entries), 120_000, check) { it.string("event") == "entries" }
    require(hosted.has("hosted")) { "Komodo returned no hosted preview list" }
    val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60)
    while (true) {
      check()
      val pending = synchronized(lock) { cards.values.any { it.image == null && it.error == null } }
      if (!pending) break
      if (System.nanoTime() >= deadline) {
        synchronized(lock) { cards.replaceAll { _, card -> if (card.image == null && card.error == null) card.copy(error = "Komodo did not render a first frame within 60 seconds") else card } }
        break
      }
      Thread.sleep(25)
    }
    acceptingInput = true
    publish()
  }

  private fun base(id: String): ComposeCard {
    val preview = previews.firstOrNull { id == it.string("previewId") || id.substringBeforeLast(':') == it.string("previewId") }
    return ComposeCard(id, preview?.string("name") ?: id, preview?.string("methodFQN").orEmpty(), null, null, emptyList())
  }
  private fun event(message: JsonObject) {
    when (message.string("event")) {
      "entries" -> {
        synchronized(lock) {
          val old = cards.toMap()
          cards.clear()
          message.getAsJsonArray("hosted")?.forEach { cards[it.asString] = base(it.asString) }
          message.getAsJsonArray("refused")?.forEach { value -> cards[value.asString] = base(value.asString).copy(error = old[value.asString]?.error ?: "Komodo refused this preview or parameter provider; see the log") }
          acceptingFrames = true
        }
        publish()
      }
      "frame" -> {
        frameNanos = message.get("nanos")?.asLong ?: frameNanos
        if (!acceptingFrames) return
        val id = message.string("entry") ?: return
        val previous = synchronized(lock) { cards[id] } ?: return
        val rendered = frame(previous, message, "keyboard" in transport.features)
        synchronized(lock) {
          if (!acceptingFrames || cards[id] !== previous) return
          cards[id] = rendered
        }
        publish()
      }
      "compile" -> message.getAsJsonArray("messages")?.forEach { value ->
        val item = value.asJsonObject
        log("${item.string("level")}: ${item.string("file").orEmpty()}:${item.get("line") ?: ""} ${item.string("text").orEmpty()}\n")
      }
      "error" -> {
        if (message.get("restartRequired")?.asBoolean == true) rejected = true
        val reason = message.string("message") ?: message.toString()
        log("Komodo ${message.string("kind").orEmpty()}: $reason\n")
        message.string("entry")?.let { id ->
          synchronized(lock) { cards[id] = (cards[id] ?: base(id)).copy(error = reason, live = false, nodes = emptyList()) }
          publish()
        }
      }
      "log" -> log(message.string("message").orEmpty() + "\n")
    }
  }
  private fun publish() { if (!closed.get()) cardsChanged(synchronized(lock) { cards.values.toList() }) }
  fun invalidate() {
    acceptingInput = false
    acceptingFrames = false
    synchronized(lock) { cards.replaceAll { _, card -> card.copy(live = false, keyboard = false) } }
  }
  fun pointer(id: String, action: String, x: Int, y: Int, dx: Double, dy: Double) {
    if (!acceptingInput || action !in setOf("down", "up", "move", "scroll")) return
    val card = synchronized(lock) { cards[id] } ?: return
    if (!card.live || card.error != null || card.image == null) return
    if (action == "scroll" && !card.scroll) return
    if (action != "up" && (x !in 0 until card.image.width || y !in 0 until card.image.height)) return
    synchronized(held) {
      held.add(id)
      if (action == "down" || action == "move" && id in pressed) pressed[id] = x to y
      if (action == "up") pressed.remove(id)
    }
    transport.send(mapOf("command" to "pointer", "entry" to id, "action" to action, "x" to x, "y" to y, "dx" to dx, "dy" to dy))
  }
  fun key(id: String, action: String, key: String, modifiers: Int) {
    if (!acceptingInput || "keyboard" !in transport.features || action !in setOf("down", "up")) return
    if (synchronized(lock) { cards[id]?.let { it.live && it.error == null } } != true) return
    synchronized(held) { held.add(id) }
    val code = when { key.length == 1 && key[0].isLetter() -> "Key${key.uppercase()}"; key.length == 1 && key[0].isDigit() -> "Digit$key"; key == " " -> "Space"; else -> key }
    transport.send(mapOf("command" to "keyboard", "entry" to id, "action" to action, "key" to key, "code" to code,
      "shiftKey" to (modifiers and 1 != 0), "ctrlKey" to (modifiers and 2 != 0), "altKey" to (modifiers and 4 != 0), "metaKey" to (modifiers and 8 != 0), "repeat" to false))
  }
  fun blur() {
    val pointers = synchronized(held) { pressed.toMap().also { pressed.clear() } }
    pointers.forEach { (id, point) -> runCatching { transport.send(mapOf("command" to "pointer", "entry" to id, "action" to "up", "x" to point.first, "y" to point.second)) } }
    val release = synchronized(held) { held.toList().also { held.clear() } }
    if ("keyboard" in transport.features) release.forEach { runCatching { transport.send(mapOf("command" to "blur", "entry" to it)) } }
  }
  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    invalidate(); transport.close(); directory.toFile().deleteRecursively()
  }

  companion object {
    internal fun previewEntries(previews: List<JsonObject>): List<Map<String, Any?>> = previews.map { preview ->
      val id = preview.string("previewId") ?: error("Komodo preview has no id")
      val parameters = preview.getAsJsonObject("previewParams") ?: JsonObject()
      val provider = preview.getAsJsonArray("methodParams")?.firstOrNull()
      val typed = parameters.entrySet().associate { (key, value) ->
        val text = value.asString
        key to when { text == "true" -> true; text == "false" -> false; Regex("^-?\\d+(\\.\\d+)?$").matches(text) -> text.toDouble(); else -> text }
      }
      mapOf("id" to if (provider == null) "$id:0" else id, "previewId" to id, "method" to preview.string("methodFQN"), "parameters" to typed, "previewParams" to parameters, "methodParameter" to provider)
    }
    internal fun frame(previous: ComposeCard, message: JsonObject, keyboard: Boolean): ComposeCard {
      val width = message.get("width")?.asInt ?: 0
      val height = message.get("height")?.asInt ?: 0
      require(width in 1..16384 && height in 1..16384 && width.toLong() * height <= 32_000_000) { "Invalid Komodo frame dimensions" }
      val bytes = Base64.getDecoder().decode(message.string("png") ?: error("Komodo frame has no PNG"))
      val image = ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
        val readers = ImageIO.getImageReaders(input)
        require(readers.hasNext()) { "Komodo returned an invalid PNG" }
        val reader = readers.next()
        try {
          reader.input = input
          require(reader.formatName.equals("png", true) && reader.getWidth(0) == width && reader.getHeight(0) == height) { "Komodo PNG dimensions do not match its frame" }
          reader.read(0)
        } finally { reader.dispose() }
      }
      var layoutError = message.string("layoutError")
      val nodes = try {
        val values = message.getAsJsonArray("nodes")
        require(values == null || values.size() <= 10000) { "Komodo layout exceeds the node limit" }
        values?.map { value ->
          val node = value.asJsonObject
          fun number(name: String) = node.get(name).asInt
          val w = number("width"); val h = number("height")
          require(w >= 0 && h >= 0) { "Invalid layout bounds" }
          val origin = node.get("source")?.takeIf { it.isJsonObject }?.asJsonObject
          val source = origin?.let { ComposeLayoutSource(it.string("fileName").orEmpty(), it.get("packageHash")?.asInt ?: 0, it.get("lineNumber")?.asInt ?: -1, it.get("offset")?.asInt ?: -1, it.get("length")?.asInt ?: 0, it.string("path")) }
          ComposeLayoutNode(node.string("id") ?: error("Missing layout node id"), node.string("name").orEmpty(), number("x"), number("y"), w, h, source, node.string("parentId"))
        }.orEmpty()
      } catch (failure: Exception) { layoutError = failure.message; emptyList() }
      return previous.copy(image = image, error = null, nodes = nodes, live = true, keyboard = keyboard, layoutError = layoutError)
    }
  }
}
