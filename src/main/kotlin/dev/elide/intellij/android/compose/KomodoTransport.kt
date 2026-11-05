package dev.elide.intellij.android.compose

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class KomodoUnavailable(message: String) : IllegalStateException(message)

/** Only the custom preview service uses this client. It never registers IDE language features. */
internal class KomodoTransport(private val log: (String) -> Unit, private val event: (JsonObject) -> Unit, private val failed: (Throwable) -> Unit) : AutoCloseable {
  private val gson = Gson()
  private val ids = AtomicLong()
  private val stopped = AtomicBoolean()
  private val requests = ConcurrentHashMap<Long, CompletableFuture<JsonElement>>()
  private val waiters = CopyOnWriteArrayList<Pair<(JsonObject) -> Boolean, CompletableFuture<JsonObject>>>()
  @Volatile private var process: Process? = null
  @Volatile private var socket: Socket? = null
  private val rpcLock = Any()
  private val previewLock = Any()
  @Volatile private var previewOutput: OutputStream? = null
  var features: Set<String> = emptySet(); private set

  companion object {
    const val LIMIT = 8 * 1024 * 1024
    fun installation(): Path {
      val root = Path.of(System.getProperty("user.home"), ".local", "share", "komodo")
      val launcher = root.resolve(if (System.getProperty("os.name").startsWith("Windows")) "komodo.exe" else "komodo")
      if (!Files.isRegularFile(launcher) || !Files.isExecutable(launcher)) throw KomodoUnavailable("Komodo is not installed at $launcher. Install the standalone Komodo distribution in $root, including its plugins directory, then Refresh. Elide does not resolve Komodo yet.")
      if (!Files.isRegularFile(root.resolve("plugins/kotlin-compose-compiler-plugin.jar"))) throw KomodoUnavailable("The Komodo installation at $root is missing plugins/kotlin-compose-compiler-plugin.jar. Reinstall the complete standalone distribution.")
      return launcher
    }

    internal fun line(input: InputStream, maximum: Int = LIMIT): String? {
      val bytes = ByteArrayOutputStream()
      while (true) {
        val next = input.read()
        if (next < 0) { if (bytes.size() == 0) return null; error("Truncated Komodo protocol line") }
        if (next == 10) return bytes.toString(StandardCharsets.UTF_8).removeSuffix("\r")
        require(bytes.size() < maximum) { "Komodo protocol line exceeds $maximum bytes" }
        bytes.write(next)
      }
    }
  }

  fun start(launcher: Path, manifest: Path, javaHome: String, root: Path, check: () -> Unit) {
    check()
    val args = mutableListOf(launcher.toString(), "--project", manifest.toString(), "--plugin", "compose=${launcher.parent.resolve("plugins/kotlin-compose-compiler-plugin.jar")}")
    val serialization = launcher.parent.resolve("plugins/kotlin-serialization-compiler-plugin.jar")
    if (Files.isRegularFile(serialization)) args.addAll(listOf("--plugin", "kotlinx-serialization=$serialization"))
    log("Starting preview-only Komodo: ${args.joinToString(" ")}\n")
    val child = ProcessBuilder(args).directory(root.toFile()).apply { environment()["JAVA_HOME"] = javaHome }.start()
    process = child
    if (stopped.get()) { child.destroyForcibly(); error("Komodo session cancelled") }
    AppExecutorUtil.getAppExecutorService().execute {
      try { child.errorStream.bufferedReader().use { reader -> while (true) log((reader.readLine() ?: break) + "\n") } } catch (_: Exception) { }
    }
    AppExecutorUtil.getAppExecutorService().execute {
      try {
        val input = BufferedInputStream(child.inputStream)
        while (!stopped.get()) {
          var length = -1
          while (true) {
            val header = line(input, 8192) ?: error("Komodo exited (check the preview log)")
            if (header.isEmpty()) break
            if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
          }
          require(length in 1..LIMIT) { "Invalid Komodo LSP message length: $length" }
          val body = input.readNBytes(length)
          require(body.size == length) { "Truncated Komodo LSP message" }
          val message = JsonParser.parseString(String(body, StandardCharsets.UTF_8)).asJsonObject
          val id = message.get("id")
          if (message.has("method") && id != null) rpc(mapOf("jsonrpc" to "2.0", "id" to id, "error" to mapOf("code" to -32601, "message" to "Preview-only client does not provide this method")))
          else if (id != null) requests.remove(id.asLong)?.let { future ->
            if (message.has("error")) future.completeExceptionally(IllegalStateException(message.getAsJsonObject("error").get("message")?.asString ?: message.toString()))
            else future.complete(message.get("result"))
          } else if (message.string("method") in setOf("window/logMessage", "window/showMessage")) log(message.getAsJsonObject("params").string("message").orEmpty() + "\n")
        }
      } catch (failure: Throwable) { fail(failure) }
    }
    val initialized = request("initialize", mapOf("processId" to ProcessHandle.current().pid(), "rootUri" to root.toUri().toString(), "capabilities" to emptyMap<String, Any>(), "clientInfo" to mapOf("name" to "Elide IntelliJ Compose Preview")), check)
    require(initialized.isJsonObject && initialized.asJsonObject.has("capabilities")) { "Komodo initialize response has no capabilities" }
    notify("initialized", emptyMap<String, Any>())
  }

  fun notify(method: String, params: Any) = rpc(mapOf("jsonrpc" to "2.0", "method" to method, "params" to params))
  fun request(method: String, params: Any, check: () -> Unit): JsonElement {
    val id = ids.incrementAndGet()
    val future = CompletableFuture<JsonElement>()
    requests[id] = future
    try {
      rpc(mapOf("jsonrpc" to "2.0", "id" to id, "method" to method, "params" to params))
      return await(future, 120_000, check)
    } finally {
      if (requests.remove(id) != null) runCatching { notify("$/cancelRequest", mapOf("id" to id)) }
    }
  }
  private fun rpc(message: Any) = synchronized(rpcLock) {
    check(!stopped.get()) { "Komodo service is closed" }
    val bytes = gson.toJson(message).toByteArray(StandardCharsets.UTF_8)
    require(bytes.size <= LIMIT) { "Komodo LSP request exceeds protocol limit" }
    val output = process?.outputStream ?: error("Komodo service has not started")
    output.write("Content-Length: ${bytes.size}\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)); output.write(bytes); output.flush()
  }

  fun connect(source: Path, check: () -> Unit) {
    val session = request("komodo/preview/open", mapOf("textDocument" to mapOf("uri" to source.toUri().toString())), check).asJsonObject
    val port = session.get("port")?.asInt ?: 0
    val token = session.string("token").orEmpty()
    if (session.get("protocol")?.asInt != 2 || port !in 1..65535 || token.isEmpty() || token.any { it == '\r' || it == '\n' }) throw KomodoUnavailable("Komodo returned an incompatible preview session. Install a standalone distribution supporting preview protocol 2.")
    val ready = waiter { it.string("event") == "ready" }
    val connection = Socket()
    socket = connection
    connection.connect(InetSocketAddress("127.0.0.1", port), 10_000)
    connection.tcpNoDelay = true
    previewOutput = connection.getOutputStream()
    previewOutput!!.write((token + "\n").toByteArray(StandardCharsets.UTF_8)); previewOutput!!.flush()
    AppExecutorUtil.getAppExecutorService().execute {
      try {
        val input = BufferedInputStream(connection.getInputStream())
        while (!stopped.get()) {
          val text = line(input) ?: error("Komodo preview connection closed")
          if (text.isBlank()) continue
          val message = JsonParser.parseString(text).asJsonObject
          event(message)
          for (waiting in waiters) if (waiting.first(message)) { waiters.remove(waiting); waiting.second.complete(message) }
        }
      } catch (failure: Throwable) { fail(failure) }
    }
    val answer = await(ready, 60_000, check)
    if (answer.get("protocol")?.asInt != 2 || answer.string("compiler") != "workspace-fir") throw KomodoUnavailable("Komodo must support protocol 2 with the workspace-fir compiler. Reinstall the standalone distribution.")
    features = answer.getAsJsonArray("features")?.map { it.asString }?.toSet().orEmpty()
    val missing = setOf("push", "multiEntry", "input", "texts", "reconfigure", "previewParameter", "layout").minus(features)
    if (missing.isNotEmpty()) throw KomodoUnavailable("Komodo lacks required live preview capabilities: ${missing.joinToString()}. Update the standalone installation.")
    log("Komodo ready: protocol 2, workspace-fir, features ${features.joinToString()}\n")
  }
  fun send(command: Any) = synchronized(previewLock) {
    check(!stopped.get()) { "Komodo preview is closed" }
    val bytes = (gson.toJson(command) + "\n").toByteArray(StandardCharsets.UTF_8)
    require(bytes.size <= LIMIT) { "Komodo preview command exceeds protocol limit" }
    val output = previewOutput ?: error("Komodo preview is not connected")
    output.write(bytes); output.flush()
  }
  fun exchange(command: Any, timeout: Long, check: () -> Unit, match: (JsonObject) -> Boolean): JsonObject {
    val answer = waiter(match)
    try { send(command); return await(answer, timeout, check) }
    finally { waiters.removeIf { it.second === answer } }
  }
  private fun waiter(match: (JsonObject) -> Boolean): CompletableFuture<JsonObject> = CompletableFuture<JsonObject>().also { waiters.add(match to it) }
  private fun <T> await(future: CompletableFuture<T>, timeout: Long, check: () -> Unit): T {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout)
    while (true) {
      check()
      if (System.nanoTime() >= deadline) error("Komodo did not answer within ${timeout / 1000}s; see the preview log")
      try { return future.get(100, TimeUnit.MILLISECONDS) }
      catch (_: TimeoutException) { }
      catch (failure: java.util.concurrent.ExecutionException) { throw failure.cause ?: failure }
    }
  }
  private fun fail(failure: Throwable) { if (!stopped.get()) { close(); failed(failure) } }
  override fun close() {
    if (!stopped.compareAndSet(false, true)) return
    val failure = IllegalStateException("Komodo preview service closed")
    requests.values.forEach { it.completeExceptionally(failure) }; requests.clear()
    waiters.forEach { it.second.completeExceptionally(failure) }; waiters.clear()
    runCatching { socket?.close() }
    process?.let { child ->
      child.destroy()
      AppExecutorUtil.getAppExecutorService().execute { if (!child.waitFor(5, TimeUnit.SECONDS)) child.destroyForcibly() }
    }
  }
}

internal fun JsonObject.string(name: String): String? = get(name)?.takeUnless { it.isJsonNull }?.asString
