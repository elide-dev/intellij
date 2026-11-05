@file:Suppress("RedundantVisibilityModifier", "Unused", "EnumEntryName")

package dev.elide.tooling.manifest.publishing

import dev.elide.tooling.manifest.jvm.MavenRepository
import kotlin.String
import kotlin.Suppress
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.collections.emptyList
import kotlin.collections.emptyMap
import kotlin.jvm.JvmInline
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import kotlinx.serialization.serializer

/**
 * A license the published package is offered under.
 */
@Serializable
@SerialName("elide.publishing.PomLicense")
public data class PomLicense(
  /**
   * License name, preferably an SPDX identifier such as `Apache-2.0`.
   */
  public val name: String,
  /**
   * URL of the license text.
   */
  public val url: String? = null,
)

/**
 * A developer of the published package.
 */
@Serializable
@SerialName("elide.publishing.PomDeveloper")
public data class PomDeveloper(
  /**
   * Developer's identifier, such as a username.
   */
  public val id: String? = null,
  /**
   * Developer's full name.
   */
  public val name: String? = null,
  /**
   * Developer's email address.
   */
  public val email: String? = null,
  /**
   * Developer's website.
   */
  public val url: String? = null,
  /**
   * Organization the developer belongs to.
   */
  public val organization: String? = null,
  /**
   * Website of the developer's organization.
   */
  public val organizationUrl: String? = null,
)

/**
 * Source control the published package is developed in.
 */
@Serializable
@SerialName("elide.publishing.PomScm")
public data class PomScm(
  /**
   * URL of the browsable repository.
   */
  public val url: String? = null,
  /**
   * Read-only connection, as Maven spells it (`scm:git:https://…`).
   */
  public val connection: String? = null,
  /**
   * Read-write connection, as Maven spells it (`scm:git:ssh://…`).
   */
  public val developerConnection: String? = null,
  /**
   * Tag of this release.
   */
  public val tag: String? = null,
)

/**
 * Organization that publishes the package.
 */
@Serializable
@SerialName("elide.publishing.PomOrganization")
public data class PomOrganization(
  /**
   * Organization name.
   */
  public val name: String? = null,
  /**
   * Organization website.
   */
  public val url: String? = null,
)

/**
 * Metadata written into the published POM.
 */
@Serializable
@SerialName("elide.publishing.PomMetadata")
public data class PomMetadata(
  /**
   * Display name of the package.
   */
  public val name: String? = null,
  /**
   * Description of the package; defaults to the project's `description`.
   */
  public val description: String? = null,
  /**
   * Website of the package.
   */
  public val url: String? = null,
  /**
   * Year the package was first released.
   */
  public val inceptionYear: String? = null,
  /**
   * Licenses the package is offered under.
   */
  public val licenses: List<PomLicense> = emptyList(),
  /**
   * Developers of the package.
   */
  public val developers: List<PomDeveloper> = emptyList(),
  /**
   * Source control the package is developed in.
   */
  public val scm: PomScm? = null,
  /**
   * Organization that publishes the package.
   */
  public val organization: PomOrganization? = null,
)

/**
 * Signs published files with the `gpg` binary.
 */
@Serializable
@SerialName("elide.publishing.GpgSigning")
public data class GpgSigning(
  /**
   * Key to sign with, as `gpg --local-user` takes it; defaults to gpg's default key.
   */
  public val keyName: String? = null,
)

/**
 * How published files are signed.
 */
@Serializable
@SerialName("elide.publishing.MavenSigning")
public data class MavenSigning(
  /**
   * Sign with the `gpg` binary.
   */
  public val gpg: GpgSigning? = null,
)

/**
 * Maven Central, through the Sonatype Central Publisher Portal. The Portal user token comes from the
 * `ELIDE_MAVEN_<NAME>_USERNAME` and `ELIDE_MAVEN_<NAME>_PASSWORD` environment variables.
 */
@Serializable
@SerialName("elide.publishing.CentralPortal")
public data class CentralPortal(
  /**
   * Portal to upload to; Sonatype's unless testing against another.
   */
  public val url: String = "https://central.sonatype.com",
  /**
   * `USER_MANAGED` waits for the release to be confirmed in the Portal; `AUTOMATIC` releases it once
   * it passes validation.
   */
  public val publishingType: CentralPublishingType = CentralPublishingType.USER_MANAGED,
) : PublishingRepository

/**
 * Publishes the project's JARs as a Maven package.
 */
@Serializable
@SerialName("elide.publishing.MavenPublishing")
public data class MavenPublishing(
  /**
   * Maven group the package is published under; required to publish.
   */
  public val groupId: String? = null,
  /**
   * Maven artifact ID of the package; defaults to the project's name. Not inherited by workspace members.
   */
  public val artifactId: String? = null,
  /**
   * Local Maven repository `elide publish` installs into; defaults to `~/.m2/repository`.
   */
  public val localRepository: String? = null,
  /**
   * Names of the `artifacts` entries to publish; defaults to every `Jar`, `SourceJar` and `JavadocJar` artifact.
   * Not inherited by workspace members.
   */
  public val artifacts: List<String>? = null,
  /**
   * Metadata written into the published POM.
   */
  public val pom: PomMetadata = PomMetadata(),
  /**
   * Remote repositories `elide publish --to <name>` publishes to, by name. Credentials come from the
   * `ELIDE_MAVEN_<NAME>_USERNAME` and `ELIDE_MAVEN_<NAME>_PASSWORD` environment variables.
   */
  public val repositories: Map<String, PublishingRepository> = emptyMap(),
  /**
   * How published files are signed; a remote publish signs whenever this is set, and `signing {}`
   * alone signs with gpg's default key.
   */
  public val signing: MavenSigning? = null,
)

/**
 * Publishing settings, one block per package ecosystem.
 */
@Serializable
@SerialName("elide.publishing.PublishingSettings")
public data class PublishingSettings(
  /**
   * Publish the project's JARs as a Maven package.
   */
  public val maven: MavenPublishing? = null,
)

/**
 * A Maven `groupId` or `artifactId`: segments of letters, digits, `_` and `-`, separated by single dots.
 */
public typealias MavenIdentifier = String

/**
 * How a Central Portal deployment is released once it passes validation.
 */
@Serializable
@SerialName("elide.publishing.CentralPublishingType")
public enum class CentralPublishingType {
  @SerialName("USER_MANAGED")
  USER_MANAGED,
  @SerialName("AUTOMATIC")
  AUTOMATIC,
}

/**
 * A repository `elide publish --to <name>` publishes to: a Maven repository, or Maven Central.
 */
@Serializable
@SerialName("elide.publishing.PublishingRepository")
public sealed interface PublishingRepository {
  @Serializable(with = OfMavenRepository.Companion::class)
  @JvmInline
  public value class OfMavenRepository(
    public val `value`: MavenRepository,
  ) : PublishingRepository {
    public companion object : KSerializer<OfMavenRepository> {
      override val descriptor: SerialDescriptor =
          buildClassSerialDescriptor("elide.publishing.PublishingRepository.OfMavenRepository") {
        element<MavenRepository>("value",isOptional=false)
      }

      override fun serialize(encoder: Encoder, `value`: OfMavenRepository) {
        encoder.encodeStructure(descriptor) {
          encodeSerializableElement(descriptor,0,serializer<MavenRepository>(),value.value)
        }
      }

      override fun deserialize(decoder: Decoder): OfMavenRepository = decoder.decodeStructure(descriptor) {
        var value: MavenRepository? = null
        while (true) {
          when (val index = decodeElementIndex(descriptor)) {
            0 -> value = decodeSerializableElement(descriptor,0,serializer<MavenRepository>())
            CompositeDecoder.DECODE_DONE -> break
            else -> error("""Unexpected index: $index""")
          }
        }
        OfMavenRepository(value!!)
      }
    }
  }
}
