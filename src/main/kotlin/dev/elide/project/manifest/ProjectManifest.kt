/*
 * Copyright (c) 2024-2025 Elide Technologies, Inc.
 *
 * Licensed under the MIT license (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *   https://opensource.org/license/mit/
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under the License.
 */
package dev.elide.project.manifest

import dev.elide.tooling.manifest.Serializers
import dev.elide.tooling.manifest.artifacts.ProjectArtifact
import dev.elide.tooling.manifest.android.AndroidTarget
import dev.elide.tooling.manifest.android.AndroidAar
import dev.elide.tooling.manifest.jvm.Jar
import dev.elide.tooling.manifest.sources.ResourceEntry
import dev.elide.tooling.manifest.sources.ResourceRoot
import dev.elide.tooling.manifest.jvm.JvmTargetLevel
import dev.elide.tooling.manifest.jvm.MavenPackageDependency
import dev.elide.tooling.manifest.kotlin.KotlinCompilerJvmOptions
import dev.elide.tooling.manifest.kotlin.KotlinLanguageTargets
import dev.elide.tooling.manifest.project.ProjectModule
import dev.elide.tooling.manifest.sources.SourceSet
import dev.elide.tooling.manifest.sources.SourceSetSpec
import dev.elide.tooling.manifest.sources.SourceSetType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.modules.SerializersModule
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

/**
 * Decoding entrypoint for Elide project manifests.
 *
 * The manifest model in [dev.elide.tooling.manifest] is generated from Elide's published Pkl schema (see
 * `tools/codegen.sh`), and the plugin never evaluates Pkl itself: it runs `elide project export`, which prints the
 * manifest the CLI resolved as JSON, and decodes that here. The two agree by construction, because the CLI serializes
 * the same generated model with the same configuration -- which is why [ManifestJson] must keep mirroring the
 * CLI's `ManifestCommand`.
 */
object ElideManifests {
  /**
   * `@type` is the discriminator the CLI writes: source sets carry a `type` property of their own, and no Pkl
   * identifier can begin with `@`, so the key cannot collide with a schema property. Unions and the Pkl scalar types
   * the schema embeds are registered by the generated [Serializers] module rather than being discoverable from the
   * classes themselves.
   *
   * Unknown keys are ignored, and explicit `null`s fall back to schema defaults, so a manifest produced by a newer
   * Elide than the model was generated from still decodes as far as its shape allows.
   */
  private val ManifestJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    classDiscriminator = "@type"
    serializersModule = SerializersModule {
      include(Serializers.serializersModule)
      contextual(Any::class, ManifestConfigSerializer)
    }
  }

  /** Decode the JSON output of `elide project export` (`elide manifest` before 1.6) into the manifest model. */
  @JvmStatic fun parse(input: String): ProjectModule {
    return runCatching {
      val document = ManifestJson.parseToJsonElement(input)
      val root = document as? JsonObject ?: error("Expected a project object")
      val normalized = root.toMutableMap()
      (root["sources"] as? JsonObject)?.let { sources ->
        normalized["sources"] = JsonObject(sources.mapValues { (_, value) ->
          val source = value as? JsonObject ?: return@mapValues value
          when (source["@type"]) {
            JsonPrimitive("elide.jvm.JvmSourceSetSpec") -> normalizeResources(
              JsonObject(source + ("@type" to JsonPrimitive("elide.sources.SourceSetSpec"))),
            )
            JsonPrimitive("elide.sources.SourceSetSpec") -> normalizeResources(source)
            else -> source
          }
        })
      }
      (root["artifacts"] as? JsonObject)?.let { artifacts ->
        normalized["artifacts"] = JsonObject(artifacts.mapValues { (_, value) ->
          val artifact = value as? JsonObject ?: return@mapValues value
          if (artifact["@type"] == JsonPrimitive("elide.jvm.Jar")) normalizeResources(artifact) else artifact
        })
      }
      ManifestJson.decodeFromJsonElement<ProjectModule>(JsonObject(normalized))
    }
      .getOrElse { throw IllegalStateException("Failed to parse Elide project manifest: ${it.message}", it) }
  }

  /**
   * Before 1.6, JVM source and JAR resources keyed publication prefixes to input selectors.
   * Only apply this at resource-entry owners: Android resources have a different shape, and
   * extension config may contain arbitrary objects with these same property names.
   */
  private fun normalizeResources(owner: JsonObject): JsonObject {
    val resources = owner["resources"] as? JsonObject ?: return owner
    return JsonObject(owner + ("resources" to JsonArray(resources.map { (into, from) ->
      JsonObject(mapOf(
        "@type" to JsonPrimitive("elide.sources.ResourceRoot"),
        "from" to from,
        "into" to JsonPrimitive(into),
      ))
    })))
  }
}

// -- Source sets

/**
 * Type of this source set. A bare glob is a source set with no options, and takes the same default the structural form
 * does.
 */
val SourceSet.type: SourceSetType
  get() = (this as? SourceSetSpec)?.type ?: SourceSetType.Source

/** Explicit types override the conventional test and example names. */
fun SourceSet.effectiveType(name: String): SourceSetType =
  (this as? SourceSetSpec)?.type ?: when (name.lowercase()) {
    "test", "tests" -> SourceSetType.Test
    "example", "examples" -> SourceSetType.Example
    else -> SourceSetType.Source
  }

/** Name of the source set Elide treats as the project's tests. */
const val TEST_SOURCE_SET: String = "test"

/** Source paths or globs enclosed by this source set. */
val SourceSet.paths: List<String>
  get() = when (val set = this) {
    is SourceSet.OfString -> listOf(set.value)
    is SourceSetSpec -> set.paths
  }

/** Resource input paths; publication prefixes do not change their location in the editor. */
val SourceSet.resources: List<String>
  get() = (this as? SourceSetSpec)?.resources.orEmpty().map {
    when (it) {
      is ResourceEntry.OfString -> it.value
      is ResourceRoot -> it.from
    }
  }

private fun sourceRoot(root: Path, name: String): Path? {
  val nested = root.resolve("src/$name")
  if (nested.isDirectory()) return nested
  return when (name) {
    "main" -> root.resolve("src").takeIf { it.isDirectory() }
    "test" -> listOf("test", "tests").map(root::resolve).firstOrNull { it.isDirectory() }
    else -> null
  }
}

fun SourceSet.sourcePaths(root: Path, name: String, siblings: Set<String> = emptySet()): List<String> {
  if (paths.isNotEmpty()) return paths
  val base = sourceRoot(root, name) ?: return emptyList()
  val languages = listOf("java", "kotlin", "js", "ts", "python")
    .map(base::resolve).filter { it.isDirectory() }
  if (languages.isNotEmpty()) return languages.map { root.relativize(it).toString() }
  val excluded = mutableSetOf("resources")
  if (base == root.resolve("src")) excluded.addAll((siblings - name) + listOf("test", "tests"))
  val entries = runCatching { base.listDirectoryEntries() }.getOrDefault(emptyList())
  val kept = entries.filterNot { it.fileName.toString() in excluded }
  return when {
    kept.isEmpty() -> emptyList()
    kept.size == entries.size -> listOf(root.relativize(base).toString())
    else -> kept.map { root.relativize(it).toString() }
  }
}

fun SourceSet.resourcePaths(root: Path, name: String): List<String> = resources.ifEmpty {
  sourceRoot(root, name)?.resolve("resources")?.takeIf { it.isDirectory() }
    ?.let { listOf(root.relativize(it).toString()) }.orEmpty()
}

/** Match the CLI's layout inference without manufacturing modules for absent directories. */
fun ProjectModule.resolveSources(root: Path): ProjectModule {
  val names = sources.keys.toMutableSet()
  val covered = sources.values.flatMap { it.paths }.map { it.substringBefore('*').trimEnd('/').removeSuffix(".") }
  for (name in listOf("main", "test")) {
    if (name in names) continue
    val base = sourceRoot(root, name) ?: continue
    val relative = root.relativize(base).toString().replace('\\', '/')
    if (covered.none { it.isEmpty() || relative == it || relative.startsWith("$it/") || it.startsWith("$relative/") }) names += name
  }
  val resolved = names.associateWith { name ->
    val source = sources[name] ?: SourceSetSpec()
    val paths = source.sourcePaths(root, name, names)
    val resources = (source as? SourceSetSpec)?.resources.orEmpty().ifEmpty {
      source.resourcePaths(root, name).map { ResourceEntry.OfString(it) }
    }
    SourceSetSpec(
      type = source.effectiveType(name),
      dependsOn = source.dependencies(name).filter { it in names && it != name },
      paths = paths,
      resources = resources,
    )
  }.filter { (name, set) -> name in sources || set.paths.isNotEmpty() || set.resources.isNotEmpty() }
  return copy(sources = resolved)
}

fun SourceSet.dependencies(name: String): List<String> =
  (this as? SourceSetSpec)?.dependsOn ?: when (effectiveType(name)) {
    SourceSetType.Test, SourceSetType.Example -> listOf(DEFAULT_SOURCE_SET)
    else -> emptyList()
  }

// -- JVM

/**
 * Bytecode major version this target level pins, or `null` when the manifest left the choice to Elide.
 *
 * The float levels the schema admits spell Java 8 and 9 as `1.8`/`1.9`, so their fractional part is the version.
 */
fun JvmTargetLevel.majorVersionOrNull(): Int? = when (this) {
  JvmTargetLevel.Auto, JvmTargetLevel.Latest, JvmTargetLevel.Stable -> null
  is JvmTargetLevel.OfInt -> value.toInt()
  is JvmTargetLevel.OfFloat -> if (value < 2.0) ((value - 1.0) * 10.0).toInt() else value.toInt()
}

// -- Kotlin

/** Compiler-argument spelling of a Kotlin API or language level. */
val KotlinLanguageTargets.argValue: String
  get() = when (this) {
    KotlinLanguageTargets.VAuto -> "auto"
    KotlinLanguageTargets.VLatest -> "latest"
    KotlinLanguageTargets.VStable -> "stable"
    KotlinLanguageTargets.V1_9 -> "1.9"
    KotlinLanguageTargets.V2_0 -> "2.0"
    KotlinLanguageTargets.V2_1 -> "2.1"
    KotlinLanguageTargets.V2_2 -> "2.2"
    KotlinLanguageTargets.V2_3 -> "2.3"
    KotlinLanguageTargets.V2_4 -> "2.4"
    KotlinLanguageTargets.V2_5 -> "2.5"
    is KotlinLanguageTargets.OfString -> value
  }

/** This level when the manifest pinned one, or `null` when it left the choice to Elide. */
fun KotlinLanguageTargets.explicitOrNull(): KotlinLanguageTargets? = takeUnless { it == KotlinLanguageTargets.VAuto }

/**
 * Kotlin compiler flags implied by these options, in the order the compiler expects them.
 *
 * Only the options which map to a flag are emitted; the rest are consumed by the build itself.
 */
fun KotlinCompilerJvmOptions.collect(): Sequence<String> = sequence {
  optIn.forEach { yield("-opt-in=$it") }

  if (progressiveMode) yield("-progressive")
  if (extraWarnings) yield("-Wextra")
  if (allWarningsAsErrors) yield("-Werror")
  if (suppressWarnings) yield("-nowarn")
  if (verbose) yield("-verbose")
  apiVersion.explicitOrNull()?.let { yield("-api-version=${it.argValue}") }
  languageVersion.explicitOrNull()?.let { yield("-language-version=${it.argValue}") }
  jvmTarget.majorVersionOrNull()?.let { yield("-jvm-target=${if (it == 8) "1.8" else it.toString()}") }
  if (includeRuntime) yield("-include-runtime")
  if (noStdlib) yield("-no-stdlib")

  if (freeCompilerArgs.isNotEmpty()) yieldAll(freeCompilerArgs)
}

// -- Workspaces

/**
 * Directories of the member projects this manifest declares, relative to the directory holding it.
 *
 * A manifest that declares members is the root of a multi-project workspace; every other project has none, including
 * the members themselves — Elide's workspaces are exactly two layers deep, and a member's own declaration is never
 * composed with its root's.
 */
val ProjectModule.workspaceMembers: List<String> get() = workspace.members

/** The name Elide knows the project rooted at [directory] by: the one it declares, else the directory's own. */
fun ProjectModule.projectName(directory: String): String = name ?: directory

/** The sibling artifact this dependency references, or `null` when it names a coordinate or a path instead. */
val MavenPackageDependency.projectArtifact: ProjectArtifact?
  get() = (this as? MavenPackageDependency.OfProjectArtifact)?.value

/**
 * A reference from one project of a workspace to an artifact another of them builds, as written in a dependency
 * block: `project("core")`, or `project("core").artifact("fat")`.
 */
data class ProjectReference(
  /** Name of the project declaring the artifact. */
  val project: String,
  /** Name of the artifact, or `null` when the reference left the choice to the project declaring exactly one. */
  val artifact: String?,
  /** Whether the reference was declared in a bucket only the tests consume. */
  val test: Boolean,
  val provided: Boolean = false,
)

/**
 * Every sibling artifact this manifest's JVM dependencies reference, in declaration order.
 *
 * `kotlinPlugins` is left out: a compiler plugin is loaded, not put on a classpath, and Elide rejects a project
 * reference there rather than resolving it. `exclusions` name what is *not* resolved, so a reference there describes
 * no dependency either.
 */
fun ProjectModule.projectReferences(): List<ProjectReference> {
  val maven = dependencies.maven

  val buckets = listOf(
    Triple(maven.packages, false, false),
    Triple(maven.devPackages, false, false),
    Triple(maven.modules, false, false),
    Triple(maven.compileOnly, false, true),
    Triple(maven.runtimeOnly, false, false),
    Triple(maven.processors, false, false),
    Triple(maven.testPackages, true, false),
  )
  return buckets.flatMap { (dependencies, test, provided) ->
    dependencies.mapNotNull { dependency ->
      dependency.projectArtifact?.let { ProjectReference(it.project, it.artifact, test, provided) }
    }
  }.distinct()
}

/**
 * The source sets of this manifest backing the JAR artifact [artifact] references, or an empty list when it resolves
 * to no JAR this manifest declares.
 *
 * A reference that names no artifact resolves against a project declaring exactly one JAR, the way Elide resolves it
 * when the build is configured; one that names several is a manifest error the CLI reports, and is left alone here.
 */
fun ProjectModule.referencedSourceSets(artifact: String?): List<String> {
  val consumable = artifacts.filterValues { it is Jar || it is AndroidAar }
  val target = if (artifact == null) consumable.values.singleOrNull() else consumable[artifact]
  return when (target) {
    is Jar -> packagedSourceSets(target)
    is AndroidTarget -> packagedSourceSets(target)
    else -> emptyList()
  }
}

/**
 * The source sets of this manifest backing the JAR the build writes under the output name [artifact], or an empty
 * list when this manifest declares no such JAR.
 *
 * Elide writes an artifact to a directory named after it — the name the artifact declares, falling back to the key it
 * is declared under — which is the only thing a resolved classpath entry carries about it. [artifact] is `null` for an
 * entry naming no directory of its own, and resolves the way an unnamed reference does.
 */
fun ProjectModule.sourceSetsForArtifactOutput(artifact: String?, staged: Boolean = false): List<String> {
  if (staged) return (artifacts[artifact] as? AndroidTarget)?.let { packagedSourceSets(it) }.orEmpty()
  val jars = jarArtifacts()
  val jar = when (artifact) {
    null -> jars.values.singleOrNull()
    else -> jars.entries.find { (key, jar) -> (jar.name ?: key) == artifact }?.value
  } ?: return emptyList()
  return packagedSourceSets(jar)
}

/** The JARs this manifest declares, keyed by the name they are declared under. */
private fun ProjectModule.jarArtifacts(): Map<String, Jar> = artifacts.entries
  .mapNotNull { (name, declared) -> (declared as? Jar)?.let { name to it } }
  .toMap()

/** Current Elide packages every production source set when a JAR does not select explicit sets. */
private fun ProjectModule.packagedSourceSets(jar: Jar): List<String> =
  jar.sources.filter { it in sources }.ifEmpty {
    sources.filter { (name, set) -> set.effectiveType(name) != SourceSetType.Test }.keys.toList()
  }

/** Android packages all non-test sets when it does not select explicit sets. */
fun ProjectModule.packagedSourceSets(target: AndroidTarget): List<String> =
  target.sources.filter { it in sources }.ifEmpty {
    sources.filter { (name, sourceSet) -> sourceSet.effectiveType(name) != SourceSetType.Test }.keys.toList()
  }

/** Name of the source set Elide packages when an artifact names none. */
const val DEFAULT_SOURCE_SET: String = "main"
