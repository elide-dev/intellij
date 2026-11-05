package dev.elide.intellij

import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import dev.elide.intellij.project.model.ElideProjectInfo
import dev.elide.intellij.service.ElideDistributionResolver
import dev.elide.intellij.service.elideProjectIndex
import dev.elide.intellij.settings.ElideDistributionSetting
import dev.elide.intellij.settings.ElideProjectSettings
import dev.elide.intellij.settings.ElideSettings
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestApplication
class ElideAutoImportAwareTest {
  @TempDir lateinit var directory: Path
  private val projectFixture = projectFixture()

  private fun file(path: String, text: String = ""): Path {
    val target = directory.resolve(path)
    Files.createDirectories(target.parent)
    Files.writeString(target, text)
    return target
  }

  @Test fun `tracks recursive amendments imports expressions and cycles`() {
    val manifest = file("elide.pkl", "amends \"base.pkl\"\nimport \"versions.pkl\"\nvalue = import(\"expr.pkl\")")
    val base = file("base.pkl", "extends \"shared.pkl\"")
    val versions = file("versions.pkl", "import \"shared.pkl\"")
    val expression = file("expr.pkl")
    val shared = file("shared.pkl", "import \"elide.pkl\"")
    assertEquals(setOf(manifest, base, versions, expression, shared), LocalPklDependencies.files(directory, listOf(manifest)))
  }

  @Test fun `parser ignores comments strings remote URIs and glob imports`() {
    val manifest = file("elide.pkl", """
      amends "elide:project.pkl"
      // import "comment.pkl"
      /* import "block.pkl" */
      import "https://example.invalid/remote.pkl"
      import "package://example.invalid/pkg@1.0.0#/module.pkl"
      import* "glob/*.pkl"
      text = "import \"string.pkl\""
    """.trimIndent())
    assertEquals(setOf(manifest), LocalPklDependencies.files(directory, listOf(manifest)))
  }

  @Test fun `local file URIs and encoded relative paths are tracked including missing files`() {
    val target = file("shared versions.pkl")
    val manifest = file("elide.pkl", "import \"${target.toUri()}\"\nimport \"shared%20versions.pkl\"\nimport \"missing.pkl\"")
    assertEquals(setOf(manifest, target, directory.resolve("missing.pkl")), LocalPklDependencies.files(directory, listOf(manifest)))
  }

  @Test fun `references outside workspace and remote file authorities are excluded`() {
    val manifest = file("root/elide.pkl", "import \"../outside.pkl\"\nimport \"file://server/share/module.pkl\"")
    file("outside.pkl", "import \"extra.pkl\"")
    assertEquals(setOf(manifest), LocalPklDependencies.files(manifest.parent, listOf(manifest)))
  }

  @EnabledOnOs(OS.LINUX, OS.MAC)
  @Test fun `symlinked files and directories cannot cross the workspace boundary`() {
    val outside = file("outside/shared.pkl", "import \"nested.pkl\"")
    val manifest = file("root/elide.pkl", "import \"link.pkl\"\nimport \"linked/shared.pkl\"")
    Files.createSymbolicLink(manifest.parent.resolve("link.pkl"), outside)
    Files.createSymbolicLink(manifest.parent.resolve("linked"), outside.parent)
    assertEquals(setOf(manifest), LocalPklDependencies.files(manifest.parent, listOf(manifest)))
  }

  @Test fun `workspace member imported modules mark linked root stale without tracking unrelated sources`() {
    val project = projectFixture.get()
    val root = directory.toString()
    val member = directory.resolve("app").toString()
    val manifest = file("elide.pkl")
    val memberManifest = file("app/elide.pkl", "amends \"../shared.pkl\"")
    val shared = file("shared.pkl", "import \"versions.pkl\"")
    val versions = file("versions.pkl")
    val unrelated = file("app/src/App.kt")
    val unrelatedPkl = file("unused.pkl")
    val lock = file(".dev/elide.lock.v2.bin", "lock data")
    ElideSettings.getSettings(project).linkProject(ElideProjectSettings().apply { externalProjectPath = root })
    project.elideProjectIndex.update(root, ElideProjectInfo(name = "root", members = listOf(member)))
    project.elideProjectIndex.update(member, ElideProjectInfo(name = "app", workspaceRoot = root))

    val aware = ElideAutoImportAware()
    val watched = aware.getAffectedExternalProjectFilePaths(root, project).toSet()
    assertTrue(watched.containsAll(listOf(manifest, memberManifest, shared, versions, lock)))
    assertFalse(unrelated in watched)
    assertEquals(root, aware.getAffectedExternalProjectPath(shared.toString(), project))
    assertEquals(root, aware.getAffectedExternalProjectPath(versions.toString(), project))
    assertEquals(root, aware.getAffectedExternalProjectPath(memberManifest.toString(), project))
    assertNull(aware.getAffectedExternalProjectPath(unrelated.toString(), project))
    assertNull(aware.getAffectedExternalProjectPath(unrelatedPkl.toString(), project))
    Files.delete(versions)
    assertEquals(root, aware.getAffectedExternalProjectPath(versions.toString(), project))
  }

  @Test fun `module settings and custom distribution resolve through the linked workspace`() {
    val project = projectFixture.get()
    val root = directory.toString()
    val member = directory.resolve("app").toString()
    val custom = directory.resolve("missing-custom-home")
    file("elide.pkl")
    val manifest = file("app/elide.pkl")
    ElideSettings.getSettings(project).linkProject(ElideProjectSettings().apply {
      externalProjectPath = root
      setModules(setOf(member))
      elideDistributionType = ElideDistributionSetting.Custom
      elideDistributionPath = custom.toString()
    })
    assertEquals(root, ElideAutoImportAware().getAffectedExternalProjectPath(manifest.toString(), project))
    assertEquals(custom, ElideDistributionResolver.getElideHome(project, member))
  }
}
