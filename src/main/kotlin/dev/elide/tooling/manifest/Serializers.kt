@file:Suppress("RedundantVisibilityModifier", "Unused")

package dev.elide.tooling.manifest

import dev.elide.tooling.manifest.artifacts.Artifact
import dev.elide.tooling.manifest.containers.ContainerImage
import dev.elide.tooling.manifest.jvm.CsvCoverageReport
import dev.elide.tooling.manifest.jvm.HtmlCoverageReport
import dev.elide.tooling.manifest.jvm.Jar
import dev.elide.tooling.manifest.jvm.JarResource
import dev.elide.tooling.manifest.jvm.JavadocJar
import dev.elide.tooling.manifest.jvm.JvmCoverageReport
import dev.elide.tooling.manifest.jvm.JvmSourceSetSpec
import dev.elide.tooling.manifest.jvm.MavenCoordinateSpec
import dev.elide.tooling.manifest.jvm.MavenLibraryCoordinate
import dev.elide.tooling.manifest.jvm.MavenPackageDependency
import dev.elide.tooling.manifest.jvm.MavenPackageSpec
import dev.elide.tooling.manifest.jvm.MavenRepository
import dev.elide.tooling.manifest.jvm.MavenRepositorySpec
import dev.elide.tooling.manifest.jvm.SourceJar
import dev.elide.tooling.manifest.jvm.XmlCoverageReport
import dev.elide.tooling.manifest.kotlin.CustomKotlinToolchain
import dev.elide.tooling.manifest.kotlin.KotlinCompilerJvmOptions
import dev.elide.tooling.manifest.kotlin.KotlinCompilerOptions
import dev.elide.tooling.manifest.kotlin.KotlinToolchain
import dev.elide.tooling.manifest.kotlin.ManagedKotlinToolchain
import dev.elide.tooling.manifest.nativeimage.NativeImage
import dev.elide.tooling.manifest.nativeimage.NativeImageOptions
import dev.elide.tooling.manifest.nativeimage.NativeImageSettings
import dev.elide.tooling.manifest.sources.SourceSet
import dev.elide.tooling.manifest.sources.SourceSetSpec
import dev.elide.tooling.manifest.testing.CoverageReport
import dev.elide.tooling.manifest.testing.TestReport
import dev.elide.tooling.manifest.testing.XmlTestReport
import dev.elide.tooling.manifest.toolchain.EngineSettings
import dev.elide.tooling.manifest.toolchain.EngineSpec
import dev.elide.tooling.manifest.web.StaticSite
import kotlin.Number
import kotlin.Suppress
import kotlin.text.Regex
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

public object RegexSerializer : KSerializer<Regex> {
  override val descriptor: SerialDescriptor =
      PrimitiveSerialDescriptor("pkl.base.Regex", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, `value`: Regex) {
    encoder.encodeString(value.pattern)
  }

  override fun deserialize(decoder: Decoder): Regex {
    return Regex(decoder.decodeString())
  }
}

public object NumberSerializer : KSerializer<Number> {
  override val descriptor: SerialDescriptor =
      PrimitiveSerialDescriptor("pkl.base.Number", PrimitiveKind.DOUBLE)

  override fun serialize(encoder: Encoder, `value`: Number) {
    encoder.encodeDouble(value.toDouble())
  }

  override fun deserialize(decoder: Decoder): Number {
    return decoder.decodeDouble()
  }
}

public data object Serializers {
  public val serializersModule: SerializersModule = SerializersModule {
    contextual(Regex::class, RegexSerializer)
    contextual(Number::class, NumberSerializer)
    polymorphic(TestReport::class) {
      subclass(XmlTestReport::class)
    }
    polymorphic(CoverageReport::class) {
      subclass(HtmlCoverageReport::class)
      subclass(XmlCoverageReport::class)
      subclass(CsvCoverageReport::class)
    }
    polymorphic(Artifact::class) {
      subclass(ContainerImage::class)
      subclass(Jar::class)
      subclass(SourceJar::class)
      subclass(JavadocJar::class)
      subclass(NativeImage::class)
      subclass(StaticSite::class)
    }
    polymorphic(SourceSetSpec::class) {
      subclass(JvmSourceSetSpec::class)
    }
    polymorphic(JarResource::class) {
      subclass(JarResource.Impl::class)
    }
    polymorphic(MavenCoordinateSpec::class) {
      subclass(MavenPackageSpec::class)
      subclass(MavenLibraryCoordinate::class)
    }
    polymorphic(JvmCoverageReport::class) {
      subclass(HtmlCoverageReport::class)
      subclass(XmlCoverageReport::class)
      subclass(CsvCoverageReport::class)
    }
    polymorphic(NativeImageOptions::class) {
      subclass(NativeImageSettings::class)
    }
    polymorphic(KotlinCompilerOptions::class) {
      subclass(KotlinCompilerJvmOptions::class)
    }
    polymorphic(MavenPackageDependency::class) {
      subclass(MavenPackageSpec::class)
    }
    polymorphic(MavenRepository::class) {
      subclass(MavenRepositorySpec::class)
    }
    polymorphic(EngineSpec::class) {
      subclass(EngineSettings::class)
    }
    polymorphic(SourceSet::class) {
      subclass(SourceSetSpec::class)
      subclass(JvmSourceSetSpec::class)
    }
    polymorphic(KotlinToolchain::class) {
      subclass(ManagedKotlinToolchain::class)
      subclass(CustomKotlinToolchain::class)
    }
  }
}
