package dev.elide.intellij.android

import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.XCollection
import dev.elide.intellij.project.model.ElideBuildTaskInfo
import dev.elide.intellij.project.model.unqualifiedTaskName
import dev.elide.tooling.manifest.android.*
import dev.elide.tooling.manifest.artifacts.Artifact
import java.io.Serializable
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** Plain import-cache and XML-persistence model; generated manifest objects never cross this boundary. */
data class ElideAndroidInfo(
  @Attribute val sdk: String? = null,
  @Attribute val compileSdk: Long = 0,
  @Attribute val minSdk: Long? = null,
  @Attribute val targetSdk: Long? = null,
  @Attribute val buildTools: String? = null,
  @Attribute val avd: String = "elide-android",
  @XCollection val managedDevices: List<String> = emptyList(),
  @XCollection val artifacts: List<ElideAndroidArtifact> = emptyList(),
  @XCollection val tasks: List<String> = emptyList(),
) : Serializable {
  companion object {
    fun from(root: Path, projectName: String?, settings: AndroidSettings?, artifacts: Map<String, Artifact>, tasks: List<ElideBuildTaskInfo>): ElideAndroidInfo? {
      if (settings == null) return null
      val listed = tasks.map { unqualifiedTaskName(it.name) }.toSet()
      return ElideAndroidInfo(settings.sdk, settings.compileSdk, settings.minSdk, settings.targetSdk, settings.buildTools,
        (settings.device.avd ?: projectName ?: "elide-android").replace(Regex("[^a-zA-Z0-9_.-]"), "_"),
        settings.devices.keys.toList(), artifacts.mapNotNull { (key, value) ->
          val target = value as? AndroidTarget ?: return@mapNotNull null
          val kind = when (target) { is AndroidApk -> "apk"; is AndroidAab -> "aab"; is AndroidAar -> "aar"; is AndroidFeature -> "feature"; else -> return@mapNotNull null }
          val application = target as? AndroidApplication
          val manifest = root.resolve(target.manifestFile).normalize()
          val facts = androidManifestFacts(manifest)
          val instrumentation = (target as? AndroidApk)?.instrumentation
          val output = if (kind == "feature") root.resolve(".dev/android/$key/$key.zip") else root.resolve(".dev/artifacts").resolve(target.name ?: "$key.$kind")
          ElideAndroidArtifact(name = key, kind = kind, outputName = target.name, output = output.toString(),
            manifestFile = manifest.toString(), applicationId = application?.applicationId ?: facts?.packageName,
            runnable = kind == "apk" && "android-run-$key" in listed,
            debuggable = kind == "apk" && facts?.debuggable != false && "android-debug-$key" in listed,
            instrumented = kind == "apk" && (instrumentation != null || facts?.instrumentationRunner != null) && "android-test-$key" in listed,
            instrumentationRunner = instrumentation?.runner ?: facts?.instrumentationRunner,
            instrumentationTargetPackage = instrumentation?.targetPackage ?: facts?.instrumentationTargetPackage,
            minify = application?.minify ?: false,
            mappingFile = root.resolve(".dev/artifacts/${target.name?.substringBeforeLast('.') ?: key}-mapping.txt").toString(),
            featureBase = (target as? AndroidFeature)?.base, featureDelivery = (target as? AndroidFeature)?.delivery?.name,
            featureDependencies = (target as? AndroidFeature)?.featureDependencies.orEmpty())
        }, listed.toList())
    }
  }
}

data class ElideAndroidArtifact(
  @Attribute val name: String = "",
  @Attribute val kind: String = "",
  @Attribute val outputName: String? = null,
  @Attribute val output: String = "",
  @Attribute val manifestFile: String = "",
  @Attribute val applicationId: String? = null,
  @Attribute val runnable: Boolean = false,
  @Attribute val debuggable: Boolean = false,
  @Attribute val instrumented: Boolean = false,
  @Attribute val instrumentationRunner: String? = null,
  @Attribute val instrumentationTargetPackage: String? = null,
  @Attribute val minify: Boolean = false,
  @Attribute val mappingFile: String = "",
  @Attribute val featureBase: String? = null,
  @Attribute val featureDelivery: String? = null,
  @XCollection val featureDependencies: List<String> = emptyList(),
) : Serializable

internal data class AndroidManifestFacts(val packageName: String?, val debuggable: Boolean?, val instrumentationRunner: String?, val instrumentationTargetPackage: String?)

/** Local manifest inspection must never fetch a DTD or resolve external entities. */
internal fun androidManifestFacts(path: Path): AndroidManifestFacts? {
  if (!Files.isRegularFile(path)) return null
  return try {
    val factory = DocumentBuilderFactory.newInstance().apply {
      isNamespaceAware = true
      setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
      setFeature("http://xml.org/sax/features/external-general-entities", false)
      setFeature("http://xml.org/sax/features/external-parameter-entities", false)
      setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
      setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
    }
    val document = Files.newInputStream(path).use { factory.newDocumentBuilder().parse(it) }
    val app = document.getElementsByTagName("application").item(0) as? org.w3c.dom.Element
    val instrumentation = document.getElementsByTagName("instrumentation").item(0) as? org.w3c.dom.Element
    val namespace = "http://schemas.android.com/apk/res/android"
    AndroidManifestFacts(document.documentElement.getAttribute("package").ifBlank { null },
      app?.getAttributeNS(namespace, "debuggable")?.toBooleanStrictOrNull(),
      instrumentation?.getAttributeNS(namespace, "name")?.ifBlank { null },
      instrumentation?.getAttributeNS(namespace, "targetPackage")?.ifBlank { null })
  } catch (_: org.xml.sax.SAXException) { null } catch (_: java.io.IOException) { null }
}
