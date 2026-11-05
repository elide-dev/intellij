package dev.elide.intellij.authoring

import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Local extension links never escape either the Elide workspace or the IDE's opened roots. */
internal object ElideExtensionPaths {
  fun resolve(declaration: String, projectRoot: Path, workspaceRoot: Path, openedRoots: Collection<Path>): Path? {
    if (declaration.isBlank() || declaration.contains('\\') || declaration.contains(':') || declaration.startsWith('/')) return null
    return try {
      val relative = Path.of(declaration)
      if (relative.isAbsolute) return null
      val target = projectRoot.resolve(relative).normalize()
      val tree = workspaceRoot.toRealPath()
      val actual = target.toRealPath()
      if (!target.startsWith(workspaceRoot.toAbsolutePath().normalize()) || !actual.startsWith(tree)) return null
      if (openedRoots.none { target.startsWith(it.toAbsolutePath().normalize()) && actual.startsWith(it.toRealPath()) }) return null
      actual.takeIf { Files.isRegularFile(it) }
    } catch (_: IOException) {
      null
    } catch (_: InvalidPathException) {
      null
    } catch (_: SecurityException) {
      null
    }
  }
}
