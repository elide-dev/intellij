package dev.elide.intellij.service

import dev.elide.intellij.Constants
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals

class ElideDistributionResolverTest {
  @TempDir lateinit var directory: Path

  private fun distribution(home: Path, windows: Boolean = true): Path {
    Files.createDirectories(home.resolve(Constants.ELIDE_RESOURCES_DIR))
    Files.createDirectories(home.resolve("bin"))
    Files.writeString(home.resolve(if (windows) "bin/elide.exe" else "bin/elide"), "binary")
    return home
  }

  @Test fun `stale standard directories do not mask a later valid install`() {
    val local = Files.createDirectories(directory.resolve("local/elide"))
    val expected = distribution(directory.resolve("programs/Elide"))
    assertEquals(expected, ElideDistributionResolver.detectDistributionPath(
      mapOf("LOCALAPPDATA" to local.parent.toString(), "ProgramFiles" to expected.parent.toString()),
      directory.resolve("user"), true,
    ))
  }

  @Test fun `valid standard installation wins over PATH`() {
    val expected = distribution(directory.resolve("local/elide"))
    val other = distribution(directory.resolve("other"))
    assertEquals(expected, ElideDistributionResolver.detectDistributionPath(
      mapOf("LOCALAPPDATA" to expected.parent.toString(), "PATH" to other.resolve("bin").toString()),
      directory.resolve("user"), true,
    ))
  }

  @EnabledOnOs(OS.LINUX, OS.MAC)
  @Test fun `PATH symlink resolves to actual distribution home`() {
    val expected = distribution(directory.resolve("actual"))
    val path = Files.createDirectories(directory.resolve("path"))
    Files.createSymbolicLink(path.resolve("elide.exe"), expected.resolve("bin/elide.exe"))
    assertEquals(expected.toRealPath(), ElideDistributionResolver.detectDistributionPath(
      mapOf("PATH" to path.toString()), directory.resolve("user"), true,
    ))
  }

  @EnabledOnOs(OS.LINUX, OS.MAC)
  @Test fun `PATH skips dangling symlinks and binaries outside distribution bin`() {
    val broken = Files.createDirectories(directory.resolve("broken"))
    Files.createSymbolicLink(broken.resolve("elide.exe"), directory.resolve("gone"))
    val loose = Files.createDirectories(directory.resolve("loose"))
    Files.writeString(loose.resolve("elide.exe"), "binary")
    val expected = distribution(directory.resolve("actual"))
    assertEquals(expected.toRealPath(), ElideDistributionResolver.detectDistributionPath(
      mapOf("PATH" to "$broken;$loose;${expected.resolve("bin")}"), directory.resolve("user"), true,
    ))
  }

  @Test fun `explicit invalid home is preserved for the callers error`() {
    val explicit = directory.resolve("missing")
    val installed = distribution(directory.resolve("local/elide"))
    assertEquals(explicit, ElideDistributionResolver.detectDistributionPath(
      mapOf("ELIDE_HOME" to explicit.toString(), "LOCALAPPDATA" to installed.parent.toString()), directory, true,
    ))
  }

  @Test fun `Unix discovery skips stale XDG directory`() {
    val user = directory.resolve("user")
    val expected = distribution(user.resolve(".local/share/elide"), windows = false)
    val xdg = Files.createDirectories(directory.resolve("xdg/elide"))
    assertEquals(expected, ElideDistributionResolver.detectDistributionPath(
      mapOf("XDG_DATA_HOME" to xdg.parent.toString()), user, false,
    ))
  }

  @Test fun `missing installation retains the platform default diagnostic path`() {
    val local = directory.resolve("local")
    assertEquals(local.resolve("elide"), ElideDistributionResolver.detectDistributionPath(
      mapOf("LOCALAPPDATA" to local.toString()), directory.resolve("user"), true,
    ))
  }
}
