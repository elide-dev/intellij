package dev.elide.intellij.project.model

import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.project.ExternalSystemSourceType
import com.intellij.openapi.externalSystem.model.project.LibraryPathType
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.roots.DependencyScope
import com.intellij.testFramework.junit5.TestApplication
import dev.elide.project.manifest.resolveSources
import dev.elide.project.manifest.sourceSetsForArtifactOutput
import dev.elide.tooling.manifest.android.AndroidAar
import dev.elide.tooling.manifest.android.AndroidSettings
import dev.elide.tooling.manifest.jvm.Jar
import dev.elide.tooling.manifest.project.ProjectModule
import dev.elide.tooling.manifest.sources.SourceSetSpec
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

@TestApplication
class ElideAndroidModelTest {
  @TempDir lateinit var root: Path

  @Test fun `platform and R are provided and processor sources exclude kapt stubs`() {
    val generated = listOf("java", "kapt").map { root.resolve(".dev/jvm/generated-sources/main/$it").createDirectories() }
    root.resolve(".dev/jvm/generated-sources/main/kapt-stubs").createDirectories()
    listOf("src/main/kotlin", "src/main/res", "src/main/assets", "src/main/jniLibs").forEach {
      root.resolve(it).createDirectories()
    }
    val manifest = ProjectModule(
      kotlin = null,
      name = "android-app", android = AndroidSettings(compileSdk = 36),
      artifacts = mapOf("library" to AndroidAar()),
    )
    val platform = root.resolve("sdk/platforms/android-36/android.jar").toString()
    val resources = root.resolve(".dev/android/library/r-classes").toString()
    val resolved = ElideResolvedProject.of(root, manifest).copy(
      providedClasspaths = mapOf("main" to ElideClasspath(ElideClasspathUsage.PROVIDED, listOf(platform, resources))),
    )
    val node = ElideProjectModel.buildModel(ElideResolvedWorkspace(resolved))
    val main = ExternalSystemApiUtil.getChildren(node, ProjectKeys.MODULE).single { it.data.externalName == "main" }
    val libraries = ExternalSystemApiUtil.getChildren(main, ProjectKeys.LIBRARY_DEPENDENCY)
    assertEquals(2, libraries.size)
    assertTrue(libraries.all { it.data.scope == DependencyScope.PROVIDED })
    val roots = ExternalSystemApiUtil.getChildren(main, ProjectKeys.CONTENT_ROOT).map { it.data }
    val generatedPaths = roots.flatMap { it.getPaths(ExternalSystemSourceType.SOURCE_GENERATED) }.map { it.path }.toSet()
    assertEquals(generated.map { it.toString() }.toSet(), generatedPaths)
    assertFalse(roots.any { it.rootPath.contains("kapt-stubs") })
    val resourcePaths = roots.flatMap { it.getPaths(ExternalSystemSourceType.RESOURCE) }.map { it.path }.toSet()
    assertEquals(listOf("res", "assets", "jniLibs").map { root.resolve("src/main/$it").toString() }.toSet(), resourcePaths)
  }

  @Test fun `exploded AAR keeps its coordinate and attaches repository sources`() {
    val classes = root.resolve(".dev/android/libraries/androidx/core/core/1.15.0/classes.jar")
    val sources = root.resolve(".dev/dependencies/m2/androidx/core/core/1.15.0/core-1.15.0-sources.jar")
    sources.parent.createDirectories()
    sources.writeText("")
    val manifest = ProjectModule(kotlin = null, name = "app", sources = mapOf("main" to SourceSetSpec(paths = listOf("src/**"))))
    val node = ElideProjectModel.buildModel(ElideResolvedWorkspace.of(
      root, manifest, mapOf("main" to ElideClasspath(ElideClasspathUsage.COMPILE, listOf(classes.toString()))),
    ))
    val library = ExternalSystemApiUtil.getChildren(node, ProjectKeys.LIBRARY).single().data
    assertEquals("androidx.core:core:1.15.0", library.externalName)
    assertEquals(setOf(sources.toString()), library.getPaths(LibraryPathType.SOURCE).toSet())
  }

  @Test fun `staged sibling classes resolve to modules not external libraries`() {
    val library = ProjectModule(
      kotlin = null,
      name = "library", android = AndroidSettings(compileSdk = 36),
      sources = mapOf("main" to SourceSetSpec(paths = listOf("src/main/**")), "extra" to SourceSetSpec(paths = listOf("src/extra/**"))),
      artifacts = mapOf("api" to AndroidAar(name = "published.aar", sources = listOf("extra"))),
    )
    val staged = root.resolve("library/.dev/android/api/classes.jar").toString()
    val app = ElideResolvedProject.of(root.resolve("app"), ProjectModule(
      kotlin = null,
      name = "app", sources = mapOf("main" to SourceSetSpec(paths = listOf("src/**"))),
    )).copy(providedClasspaths = mapOf("main" to ElideClasspath(ElideClasspathUsage.PROVIDED, listOf(staged))))
    val node = ElideProjectModel.buildModel(ElideResolvedWorkspace(
      ElideResolvedProject.of(root, ProjectModule(kotlin = null, name = "workspace")),
      listOf(ElideResolvedProject.of(root.resolve("library"), library), app),
    ))
    val module = ExternalSystemApiUtil.getChildren(node, ProjectKeys.MODULE).single { it.data.externalName == "app.main" }
    val dependency = ExternalSystemApiUtil.getChildren(module, ProjectKeys.MODULE_DEPENDENCY).single().data
    assertEquals("library.extra", dependency.target.externalName)
    assertEquals(DependencyScope.PROVIDED, dependency.scope)
    assertTrue(ExternalSystemApiUtil.getChildren(node, ProjectKeys.LIBRARY).isEmpty())
  }

  @Test fun `layout inference separates tests resources and explicitly covered roots`() {
    root.resolve("src/main/kotlin").createDirectories()
    root.resolve("src/main/resources").createDirectories()
    root.resolve("src/test/java").createDirectories()
    val inferred = ProjectModule(kotlin = null).resolveSources(root)
    assertEquals(listOf("main", "test"), inferred.sources.keys.toList())
    assertEquals(listOf("src/main/kotlin"), (inferred.sources.getValue("main") as SourceSetSpec).paths)
    assertEquals(listOf("main"), (inferred.sources.getValue("test") as SourceSetSpec).dependsOn)
    val renamed = ProjectModule(kotlin = null, sources = mapOf("api" to SourceSetSpec(paths = listOf("src/main/**")))).resolveSources(root)
    assertEquals(listOf("api", "test"), renamed.sources.keys.toList())
    assertEquals(emptyList(), (renamed.sources.getValue("test") as SourceSetSpec).dependsOn)
    val jar = renamed.copy(artifacts = mapOf("api" to Jar()))
    assertEquals(listOf("api"), jar.sourceSetsForArtifactOutput("api"))
  }

  @Test fun `explicit empty source dependencies do not inherit main`() {
    val manifest = ProjectModule(kotlin = null, name = "sets", sources = mapOf(
      "main" to SourceSetSpec(paths = listOf("src/main/**")),
      "test" to SourceSetSpec(paths = listOf("src/test/**"), dependsOn = emptyList()),
      "examples" to SourceSetSpec(paths = listOf("examples/**")),
    ))
    val node = ElideProjectModel.buildModel(ElideResolvedWorkspace.of(root, manifest))
    val modules = ExternalSystemApiUtil.getChildren(node, ProjectKeys.MODULE)
    val test = modules.single { it.data.externalName == "test" }
    assertTrue(ExternalSystemApiUtil.getChildren(test, ProjectKeys.MODULE_DEPENDENCY).isEmpty())
    val example = modules.single { it.data.externalName == "examples" }
    val dependency = ExternalSystemApiUtil.getChildren(example, ProjectKeys.MODULE_DEPENDENCY).single().data
    assertEquals("main", dependency.target.externalName)
    assertEquals(DependencyScope.COMPILE, dependency.scope)
  }

  @Test fun `R preparation rebuilds missing and unrecorded artifacts only`() {
    val recorded = root.resolve(".dev/android/recorded/r-classes").createDirectories()
    root.resolve(".dev/android/unrecorded/r-classes").createDirectories()
    val artifacts = listOf("recorded", "unrecorded", "missing")
    assertEquals(listOf("missing"), androidResourceArtifactsToPrepare(root, artifacts))
    assertEquals(
      listOf("unrecorded", "missing"),
      androidResourceArtifactsToPrepare(root, artifacts, listOf(recorded.toString())),
    )
    assertEquals(
      emptyList(),
      androidResourceArtifactsToPrepare(root, listOf("recorded"), listOf(".dev/android/recorded/r-classes")),
    )
  }
}
