package dev.elide.intellij.android

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.roots.ProjectRootManager
import dev.elide.intellij.Constants
import dev.elide.intellij.service.ElideDistributionResolver
import dev.elide.intellij.service.elideProjectIndex
import dev.elide.intellij.settings.ElideProjectSettings
import dev.elide.intellij.settings.ElideSettings
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

/** SDK commands are argument vectors, never a shell. */
object ElideAndroidTools {
  fun settings(project: Project, root: String): ElideProjectSettings {
    val linked = project.elideProjectIndex[root]?.workspaceRoot ?: root
    return ElideSettings.getSettings(project).getLinkedProjectSettings(linked) ?: ElideProjectSettings()
  }

  fun sdkRoot(project: Project, root: String, info: ElideAndroidInfo? = null): Path? = resolveSdk(
    settings(project, root).androidSdk, (info ?: project.elideProjectIndex[root]?.android)?.sdk, Path.of(root),
  )

  /** An explicit but invalid SDK is an error, not permission to use a different SDK. */
  internal fun resolveSdk(setting: String?, declared: String?, root: Path, env: Map<String, String> = System.getenv(),
    home: String = System.getProperty("user.home"), os: String = System.getProperty("os.name"),
    isDirectory: (Path) -> Boolean = { Files.isDirectory(it) }): Path? {
    val explicit = setting?.takeIf(String::isNotBlank) ?: declared?.takeIf(String::isNotBlank)
    if (explicit != null) return root.resolve(explicit).normalize().takeIf(isDirectory)
    val conventional = when {
      os.startsWith("Mac", true) -> listOf(Path.of(home, "Library", "Android", "sdk"))
      os.startsWith("Windows", true) -> listOfNotNull(env["LOCALAPPDATA"]?.let { Path.of(it, "Android", "Sdk") }, Path.of(home, "AppData", "Local", "Android", "Sdk"))
      else -> listOf(Path.of(home, "Android", "Sdk"))
    }
    return (listOfNotNull(env["ANDROID_HOME"]?.takeIf(String::isNotBlank), env["ANDROID_SDK_ROOT"]?.takeIf(String::isNotBlank)).map { Path.of(it).toAbsolutePath() } + conventional).firstOrNull(isDirectory)
  }

  fun adbExecutable(project: Project, root: String, info: ElideAndroidInfo? = null): Path {
    val sdk = sdkRoot(project, root, info) ?: error("Android SDK not found. Set Android SDK in Elide settings, android.sdk, or ANDROID_HOME.")
    return sdk.resolve("platform-tools").resolve(if (SystemInfo.isWindows) "adb.exe" else "adb").also {
      check(Files.isRegularFile(it)) { "Android SDK Platform-Tools is missing: $it. Install SDK components first." }
    }
  }

  private fun startAdb(project: Project, root: String, serial: String?, args: List<String>): Process {
    check(TrustedProjects.isProjectTrusted(project)) { "Trust this project before running Android SDK tools." }
    val command = listOf(adbExecutable(project, root).toString()) + (serial?.let { listOf("-s", it) } ?: emptyList()) + args
    return ProcessBuilder(command).directory(Path.of(root).toFile()).start()
  }

  fun command(project: Project, root: String, serial: String?, args: List<String>, indicator: ProgressIndicator? = null,
    timeoutSeconds: Long = 30): String {
    indicator?.checkCanceled()
    ProgressManager.checkCanceled()
    val process = startAdb(project, root, serial, args)
    fun collect(stream: java.io.InputStream) = ApplicationManager.getApplication().executeOnPooledThread(Callable {
      stream.use {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
          val count = it.read(buffer)
          if (count < 0) break
          check(output.size() + count <= 8 * 1024 * 1024) { "adb response exceeded 8 MiB." }
          output.write(buffer, 0, count)
        }
        output.toString(Charsets.UTF_8)
      }
    })
    val stdout = collect(process.inputStream)
    val stderr = collect(process.errorStream)
    try {
      process.outputStream.close()
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
      while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
        indicator?.checkCanceled()
        ProgressManager.checkCanceled()
        if (project.isDisposed) throw com.intellij.openapi.progress.ProcessCanceledException()
        check(System.nanoTime() < deadline) { "adb ${args.firstOrNull().orEmpty()} timed out after $timeoutSeconds seconds." }
        if (stdout.isDone) stdout.get()
        if (stderr.isDone) stderr.get()
      }
      val output = stdout.get()
      val diagnostic = stderr.get()
      check(process.exitValue() == 0) { "adb ${args.firstOrNull().orEmpty()} failed (${process.exitValue()}): ${(diagnostic + "\n" + output).trim().take(2048)}" }
      return output
    } finally {
      if (process.isAlive) {
        process.destroy()
        if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly()
      }
      stdout.cancel(true)
      stderr.cancel(true)
    }
  }

  fun devices(project: Project, root: String, indicator: ProgressIndicator? = null): List<ElideAndroidDevice> =
    parseDevices(command(project, root, null, listOf("devices", "-l"), indicator))

  internal fun parseDevices(output: String): List<ElideAndroidDevice> = output.lineSequence().mapNotNull { line ->
    if (line.startsWith("List of devices") || line.startsWith('*')) return@mapNotNull null
    val parts = line.trim().split(Regex("\\s+"))
    if (parts.size < 2 || parts[0].isEmpty()) return@mapNotNull null
    val state = parts[1]
    if (state !in setOf("device", "offline", "unauthorized", "bootloader", "recovery", "sideload", "rescue", "no")) return@mapNotNull null
    ElideAndroidDevice(parts[0], if (state == "no") "no permissions" else state,
      parts.firstOrNull { it.startsWith("model:") }?.removePrefix("model:")?.replace('_', ' ') ?: parts[0])
  }.toList()

  /** Does not invoke adb, the CLI, sdkmanager, or sync; safe even before project trust/import. */
  fun environmentReport(project: Project, root: String): String {
    val info = project.elideProjectIndex[root]?.android
    val sdk = sdkRoot(project, root, info)
    return buildString {
      appendLine("Android environment (inspection only; no sync or installation performed)")
      appendLine("Project: $root")
      appendLine("SDK setting: ${settings(project, root).androidSdk.ifBlank { "not set" }}")
      appendLine("Manifest SDK: ${info?.sdk ?: "not declared / not yet imported"}")
      appendLine("ANDROID_HOME: ${System.getenv("ANDROID_HOME") ?: "not set"}")
      appendLine("ANDROID_SDK_ROOT: ${System.getenv("ANDROID_SDK_ROOT") ?: "not set"}")
      appendLine("Resolved SDK: ${sdk ?: "not found"}")
      if (sdk != null) {
        appendLine("Platform-Tools: ${Files.isRegularFile(sdk.resolve("platform-tools").resolve(if (SystemInfo.isWindows) "adb.exe" else "adb"))}")
        appendLine("Emulator: ${Files.isRegularFile(sdk.resolve("emulator").resolve(if (SystemInfo.isWindows) "emulator.exe" else "emulator"))}")
      }
      appendLine("SDK override applies to device tooling only; builds use android.sdk/environment.")
      appendLine("Connected authorized device: unknown until deployment target selection or native device inspection.")
      appendLine("Binary presence does not verify executable compatibility, server health, or emulator boot readiness.")
      val distribution = ElideDistributionResolver.getElideHome(project, root)
      val binary = ElideDistributionResolver.executablePath(distribution)
      appendLine("Elide CLI: $binary — present=${Files.isRegularFile(binary)} (version not executed)")
      val jdk = settings(project, root).jdkHome.takeIf(String::isNotBlank)
        ?: ProjectRootManager.getInstance(project).projectSdk?.homePath
        ?: System.getenv("JAVA_HOME")
      appendLine("JDK: ${jdk ?: "not configured"}")
      if (jdk != null) appendLine("Java binary present: ${Files.isRegularFile(Path.of(jdk, "bin", if (SystemInfo.isWindows) "java.exe" else "java"))}")
      val platformJars = OrderEnumerator.orderEntries(project).librariesOnly().classes().roots
        .map { it.path.substringBefore("!/") }.filter { Regex("[/\\\\]platforms[/\\\\]android-[^/\\\\]+[/\\\\]android\\.jar$").containsMatchIn(it) }.distinct()
      appendLine("Synced workspace Android platform jars: " + platformJars.joinToString().ifBlank { "unknown until sync" })
      platformJars.forEach { appendLine("  $it — present=${Files.isRegularFile(Path.of(it))}") }
      appendLine("Logcat: requires platform-tools and an authorized device; no stream started.")
      appendLine("Database inspector: installed debuggable app/run-as access unknown until explicitly opened; no app queried.")
      appendLine("Compose preview: host-side and independent of devices; no renderer downloaded or started by this report.")
      if (info == null) appendLine("No synced Android model. Import the project to inspect manifest-specific requirements.")
      else {
        appendLine("AVD: ${info.avd}")
        appendLine("Managed devices: ${info.managedDevices.joinToString().ifBlank { "none" }}")
        for (artifact in info.artifacts) appendLine("${artifact.name} (${artifact.kind}): run=${artifact.runnable}, debug=${artifact.debuggable}, tests=${artifact.instrumented}" +
          (artifact.featureBase?.let { ", base=$it, delivery=${artifact.featureDelivery}, dependencies=${artifact.featureDependencies.joinToString()}" } ?: ""))
      }
    }
  }
}

data class ElideAndroidDevice(val serial: String, val state: String, val label: String = serial) {
  val ready: Boolean get() = state == "device"
  val emulator: Boolean get() = serial.startsWith("emulator-")
  override fun toString(): String = "$label ($serial) — $state"
}
