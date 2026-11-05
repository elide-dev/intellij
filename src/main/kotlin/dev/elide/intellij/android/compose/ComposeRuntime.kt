package dev.elide.intellij.android.compose

import com.intellij.openapi.application.PathManager
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Pinned layoutlib host artifacts originate in elide-vscode; Komodo owns rendering and compilation. */
internal data class ComposeRuntime(val directory: Path) {
  val classpath get() = listOf(directory.resolve("compose-preview-renderer.jar"), directory.resolve("layoutlib.jar"))
  val layoutlib get() = directory.resolve("layoutlib")

  companion object {
    private const val VERSION = "16.1.0-jdk17"
    private const val MAVEN = "https://dl.google.com/dl/android/maven2"
    internal data class Host(val classifier: String, val sha256: String, val nativeLibrary: String)
    internal fun host(os: String = System.getProperty("os.name"), arch: String = System.getProperty("os.arch")): Host {
      val arm = arch in listOf("aarch64", "arm64")
      val x64 = arch in listOf("amd64", "x86_64", "x64")
      return when {
        os.startsWith("Mac") && arm -> Host("mac-arm", "94d226071f0407a1f23e55dcd7e8b0c4cc8cfc10cccdf3f1366b401ee0c694e0", "data/mac-arm/lib64/layoutlib_jni.dylib")
        os.startsWith("Mac") && x64 -> Host("mac", "92fe9aedf7a3684e24f604b94b4adf3453d8fe7bfeeb06ef46010f44f71a76be", "data/mac/lib64/layoutlib_jni.dylib")
        os.startsWith("Linux") && x64 -> Host("linux", "72b3730d2a8c20b302fcbe4abb4654388bdb4168cf26c86bab8b83065826ab1b", "data/linux/lib64/layoutlib_jni.so")
        os.startsWith("Windows") && x64 -> Host("win", "300d50af7b65befe5e82dfae73652487fc560000da0659410468d5e293f2187f", "data/win/lib64/layoutlib_jni.dll")
        else -> error("Compose host rendering does not support $os/$arch. Supported hosts: macOS ARM64/x64, Linux x64, Windows x64.")
      }
    }
    private data class Artifact(val file: String, val url: String, val sha256: String)
    fun ensure(check: () -> Unit, status: (String) -> Unit): ComposeRuntime {
      val host = host()
      fun layout(name: String, file: String, hash: String, classifier: String = "") = Artifact(file,
        "$MAVEN/com/android/tools/layoutlib/$name/$VERSION/$name-$VERSION$classifier.jar", hash)
      val artifacts = listOf(
        Artifact("compose-preview-renderer.jar", "$MAVEN/com/android/tools/compose/compose-preview-renderer/0.0.1-alpha16/compose-preview-renderer-0.0.1-alpha16.jar", "9c5913a07f35b27fc31d543d4d91c92f4ecc9526fca071a514c80f37e102eeef"),
        layout("layoutlib", "layoutlib.jar", "cc2f6e01c9d5904b0896a3d66d8b1b169ea8163d762d1ddd3c51548c72ca6182"),
        layout("layoutlib-resources", "layoutlib-resources.jar", "2f7beb5e9f441b9f32c12df0a34121155163dfd047804b116262eaad33928388"),
        layout("layoutlib-runtime", "layoutlib-runtime.jar", host.sha256, "-${host.classifier}"),
      )
      val root = Path.of(PathManager.getSystemPath(), "elide", "compose-runtime")
      Files.createDirectories(root)
      val destination = root.resolve("alpha16-$VERSION-${host.classifier}-1")
      val marker = artifacts.joinToString("\n") { "${it.file}:${it.sha256}" }
      val required = artifacts.map { it.file } + listOf("layoutlib/build.prop", "layoutlib/data/framework_res.jar", "layoutlib/${host.nativeLibrary}")
      fun ready() = Files.isRegularFile(destination.resolve("ready")) && Files.readString(destination.resolve("ready")) == marker && required.all { Files.isRegularFile(destination.resolve(it)) && Files.size(destination.resolve(it)) > 0 }
      check()
      if (ready()) return ComposeRuntime(destination)
      val staging = Files.createTempDirectory(root, "install-")
      try {
        for (artifact in artifacts) {
          check()
          status("Downloading ${artifact.file} (SHA-256 verified)")
          val connection = URI(artifact.url).toURL().openConnection().apply { connectTimeout = 30_000; readTimeout = 30_000 }
          val digest = MessageDigest.getInstance("SHA-256")
          connection.getInputStream().use { input -> Files.newOutputStream(staging.resolve(artifact.file)).use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
              check()
              val count = input.read(buffer)
              if (count < 0) break
              digest.update(buffer, 0, count)
              output.write(buffer, 0, count)
            }
          } }
          require(hex(digest.digest()) == artifact.sha256) { "SHA-256 mismatch for ${artifact.file}; runtime was not installed." }
        }
        status("Extracting verified layoutlib runtime")
        val layout = staging.resolve("layoutlib")
        Files.createDirectories(layout)
        ZipInputStream(Files.newInputStream(staging.resolve("layoutlib-runtime.jar"))).use { zip ->
          while (true) {
            check()
            val entry = zip.nextEntry ?: break
            val file = safeZipPath(layout, entry.name)
            if (entry.isDirectory) Files.createDirectories(file) else {
              Files.createDirectories(file.parent)
              Files.newOutputStream(file).use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) { check(); val count = zip.read(buffer); if (count < 0) break; out.write(buffer, 0, count) }
              }
            }
            zip.closeEntry()
          }
        }
        Files.copy(staging.resolve("layoutlib-resources.jar"), layout.resolve("data/framework_res.jar"), StandardCopyOption.REPLACE_EXISTING)
        require(required.all { Files.isRegularFile(staging.resolve(it)) && Files.size(staging.resolve(it)) > 0 }) { "Incomplete Compose runtime" }
        Files.writeString(staging.resolve("ready"), marker)
        check()
        try { Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE) }
        catch (failure: java.io.IOException) {
          if (!ready()) throw IllegalStateException("Cannot publish runtime at $destination. Remove an incomplete cache directory and retry.", failure)
        }
        return ComposeRuntime(destination)
      } finally { staging.toFile().deleteRecursively() }
    }
    internal fun safeZipPath(root: Path, entry: String): Path {
      require(!entry.contains('\\')) { "Invalid ZIP entry: $entry" }
      val result = root.resolve(entry).normalize()
      require(result.startsWith(root) && result != root) { "Unsafe ZIP entry: $entry" }
      return result
    }
    internal fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
  }
}
