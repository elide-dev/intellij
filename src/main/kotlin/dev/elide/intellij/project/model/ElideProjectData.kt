package dev.elide.intellij.project.model

import com.intellij.openapi.externalSystem.model.Key
import dev.elide.tooling.manifest.jvm.JvmSettings
import dev.elide.tooling.manifest.kotlin.KotlinSettings
import dev.elide.tooling.manifest.project.ProjectModule

data class ElideProjectData(
  var kotlinSettings: KotlinSettings? = null,
  var entrypoints: List<String>? = null,
  var jvm: JvmSettings? = null,
  var scripts: Map<String, String>? = null
) {
  constructor(manifest: ProjectModule) : this(
    kotlinSettings = manifest.kotlin,
    entrypoints = manifest.entrypoint,
    jvm = manifest.jvm,
    scripts = manifest.scripts,
  )

  companion object {
    /** Key used to store [ElideProjectInfo] in a project node during resolution. */
    val PROJECT_KEY: Key<ElideProjectData> = Key.create(ElideProjectData::class.java, 100)
  }
}
