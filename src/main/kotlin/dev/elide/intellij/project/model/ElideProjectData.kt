package dev.elide.intellij.project.model

import com.intellij.openapi.externalSystem.model.Key
import elide.tooling.project.manifest.ElidePackageManifest
import elide.tooling.project.manifest.ElidePackageManifest.JvmSettings
import elide.tooling.project.manifest.ElidePackageManifest.KotlinSettings

data class ElideProjectData(
  var kotlinSettings: KotlinSettings? = null,
  var entrypoints: List<String>? = null,
  var jvm: JvmSettings? = null,
  var scripts: Map<String, String>? = null
) {
  constructor(manifest: ElidePackageManifest) : this(
    kotlinSettings = manifest.kotlin,
    entrypoints = manifest.entrypoint,
    jvm = manifest.jvm,
    scripts = manifest.scripts
  )

  companion object {
    /** Key used to store [ElideProjectInfo] in a project node during resolution. */
    val PROJECT_KEY: Key<ElideProjectData> = Key.create(ElideProjectData::class.java, 100)
  }
}
