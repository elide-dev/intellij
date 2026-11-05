@file:Suppress("RedundantVisibilityModifier", "Unused", "EnumEntryName")

package dev.elide.tooling.manifest.android

import dev.elide.tooling.manifest.artifacts.Artifact
import kotlin.Boolean
import kotlin.Long
import kotlin.String
import kotlin.Suppress
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.collections.emptyList
import kotlin.collections.emptyMap
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Virtual device the emulator tasks create and boot.
 */
@Serializable
@SerialName("elide.android.AndroidDevice")
public data class AndroidDevice(
  /**
   * Name of the AVD to create and boot; absent takes the project's name (`elide-android` when the
   * project has none), or `<project>-<name>` for a device in [AndroidSettings.devices], with any
   * character `avdmanager` refuses replaced by `_`.
   */
  public val avd: String? = null,
  /**
   * API level of the system image the AVD is cut from; absent takes [AndroidSettings.compileSdk].
   * Ignored when [systemImage] is declared.
   */
  public val api: Long? = null,
  /**
   * System image the AVD is cut from, in `sdkmanager` package form; absent takes an image already
   * installed for [api], else derives `system-images;android-<api>;default;<abi>` from the host
   * architecture.
   */
  public val systemImage: String? = null,
  /**
   * ABI of the derived system image; absent takes the host's (`arm64-v8a` on Apple silicon and
   * ARM Linux, `x86_64` elsewhere). Ignored when [systemImage] is declared.
   */
  public val abi: AndroidAbi? = null,
  /**
   * Device profile the AVD is cut from, as `avdmanager list device` names it.
   */
  public val deviceProfile: String = "pixel_6",
)

/**
 * Android SDK settings: where the SDK lives, and which of its components the build uses.
 *
 * The SDK is not vendored — it carries its own licenses. The build locates an installation,
 * resolves which of its components answer for [compileSdk], and reports the `sdkmanager --install`
 * line for anything missing; passing `--install-sdk` installs it instead, which is how its license
 * is accepted.
 */
@Serializable
@SerialName("elide.android.AndroidSettings")
public data class AndroidSettings(
  /**
   * Installed Android SDK root. Absent reads `ANDROID_HOME`, then `ANDROID_SDK_ROOT`, then the
   * location the SDK installs itself in on this platform.
   */
  public val sdk: String? = null,
  /**
   * API level the app compiles, links and dexes against: this platform's `android.jar` lands on
   * the compile classpath, and is passed to `aapt2 link -I` and `d8 --lib`.
   */
  public val compileSdk: Long,
  /**
   * Oldest API level the dex output runs on, which `d8` dexes for. Absent dexes for [compileSdk].
   * The merged manifest's `minSdkVersion` is this level: a manifest omitting it receives it, and one
   * declaring another value fails the build, since the dex would not run where it installs.
   */
  public val minSdk: Long? = null,
  /**
   * API level an application or bundle declares it was built and tested against
   * (`targetSdkVersion`), which decides the platform's compatibility behaviours. Declared, the
   * merged manifest carries it and a manifest declaring another value fails the build; absent, a
   * manifest's own value stands and one declaring none receives [compileSdk]. A library's manifest
   * never carries it.
   */
  public val targetSdk: Long? = null,
  /**
   * Build-tools release providing `aapt2`, `d8`, `zipalign` and `apksigner`. Absent takes the
   * newest installed release for [compileSdk], else the newest installed release newer than it,
   * else `<compileSdk>.0.0`.
   */
  public val buildTools: String? = null,
  /**
   * Virtual device the `android-emulator` and `android-run-*` tasks boot, and `android-test-*`
   * boots when no device is attached.
   */
  public val device: AndroidDevice = AndroidDevice(),
  /**
   * Virtual devices instrumented tests run on by name, as Gradle's managed devices do. Every APK
   * gets `android-test-<artifact>-on-<name>` per device, and `android-test-<artifact>-on-all` for
   * all of them. Each run creates the AVD if it is missing, boots it (headless with `--no-window`)
   * or adopts it if it is already running, and stops it afterwards if it booted it.
   */
  public val devices: Map<String, AndroidDevice> = emptyMap(),
  /**
   * Artifact whose resources, assets and merged manifest the project's JVM tests run against under
   * Robolectric; absent takes the first application, library or bundle the project declares.
   */
  public val hostTests: String? = null,
  /**
   * Configure the Hilt annotation processor (`hilt-compiler`, which must be declared in
   * `dependencies.maven.processors`) to aggregate in this project, and transform the superclasses of
   * Android entry points (`@AndroidEntryPoint`) to the ones it generates.
   */
  public val hilt: Boolean = false,
)

/**
 * How an artifact is signed, and how `elide build android-keystore-<artifact>` creates the keystore
 * it signs with.
 *
 * No password is ever declared here: both are read from the environment, so a manifest committed
 * to a repository carries no secret. A keystore this build created itself, and a keystore `adb`
 * installs from, use the Android debug conventions when the variables are unset.
 */
@Serializable
@SerialName("elide.android.AndroidSigning")
public data class AndroidSigning(
  /**
   * Keystore holding the signing key, relative to the project root.
   */
  public val keystore: String,
  /**
   * Key alias within the keystore.
   */
  public val alias: String = "androiddebugkey",
  /**
   * Environment variable holding the keystore password; unset falls back to `android`, the
   * password an Android debug keystore carries.
   */
  public val passwordEnv: String = "ANDROID_KEYSTORE_PASS",
  /**
   * Environment variable holding the key password, when it differs from the store password.
   * Absent, or unset in the environment, reuses the store password.
   */
  public val keyPasswordEnv: String? = null,
  /**
   * Distinguished name `elide build android-keystore-<artifact>` records in a keystore it creates.
   */
  public val dname: String = "CN=Android Debug, O=Android, C=US",
  /**
   * Days a keystore `elide build android-keystore-<artifact>` creates stays valid.
   */
  public val validity: Long = 10_000L,
)

/**
 * Fields shared by every Android artifact built from this project's sources.
 *
 * An application package and a library archive reach `aapt2` the same way: resources compile,
 * the manifest merges with the manifests of the Android libraries the artifact consumes, and
 * linking produces the `R` the code is compiled against.
 */
public interface AndroidTarget : Artifact {
  /**
   * File name of the published artifact; absent takes the artifact's name plus the extension of
   * its kind (`.apk`, `.aar`).
   */
  public val name: String?

  /**
   * Source sets whose compiled classes the artifact carries; empty takes every non-test set.
   */
  public val sources: List<String>

  /**
   * Android manifest of this artifact, merged with those of the libraries it consumes.
   */
  public val manifestFile: String

  /**
   * Resource directories compiled by `aapt2`; empty takes `src/main/res` when it exists.
   */
  public val resources: List<String>

  /**
   * Directories packaged verbatim under `assets/`; empty takes `src/main/assets` when it exists.
   */
  public val assets: List<String>

  /**
   * Values substituted for `${name}` placeholders while merging manifests. An application always
   * supplies `applicationId` too: its declared [AndroidApplication.applicationId], else the
   * `package` its own manifest declares. A library substitutes only these and leaves every other
   * placeholder, `${applicationId}` included, for the application that consumes it.
   */
  public val placeholders: Map<String, String>

  /**
   * Extra arguments appended to `aapt2 link`.
   */
  public val linkArgs: List<String>

  /**
   * Directories of native libraries, laid out as `<abi>/lib<name>.so`; empty takes
   * `src/main/jniLibs` when it exists.
   */
  public val jniLibs: List<String>
}

/**
 * How the Java resources an application's dexed classpath carries are packaged.
 *
 * Beyond these, the defaults Android's own tooling applies always hold: class files, jar
 * signatures and manifests, `META-INF/maven`, `META-INF/proguard`, root licence and notice files and
 * version-control files never ship.
 */
@Serializable
@SerialName("elide.android.AndroidPackaging")
public data class AndroidPackaging(
  /**
   * Java resources left out of the archive, as glob patterns matched against the entry's path with a
   * leading `/` (`/META-INF/{AL2.0,LGPL2.1}`, `META-INF/LICENSE*`); a pattern starting with neither
   * `/` nor a wildcard is rooted at `/`, as the Android Gradle Plugin reads it. `*`, `?` and `[…]`
   * match within one path segment, `**` across segments.
   */
  public val excludes: List<String> = emptyList(),
)

/**
 * Fields shared by the artifacts that are an installable application: an APK, and the bundle a store
 * splits into APKs.
 */
public interface AndroidApplication : AndroidTarget {
  /**
   * Package the application is installed and published as. Absent takes the `package` its
   * manifest declares, which stays the namespace its `R` class and relative class names live in;
   * declaring one is how a debug build installs beside a release build of the same sources.
   */
  public val applicationId: String?

  /**
   * `android:versionCode` of the merged manifest, replacing any the manifest declares.
   */
  public val versionCode: Long?

  /**
   * `android:versionName` of the merged manifest, replacing any the manifest declares.
   */
  public val versionName: String?

  /**
   * Whether this is a release build. `false` is a debug build: `d8` keeps debug information and
   * skips optimization, and the merged manifest marks the app `android:debuggable` unless a
   * manifest declares that attribute itself.
   */
  public val release: Boolean

  /**
   * Signing configuration; absent publishes the artifact unsigned. An APK is signed with
   * `apksigner`; a bundle with `jarsigner`, which is how a store's upload key is applied.
   */
  public val signing: AndroidSigning?

  /**
   * Extra arguments appended to the dexer: `d8`, or R8 when [minify] is set — so an argument only
   * one of them accepts fails the other.
   */
  public val dexArgs: List<String>

  /**
   * ABIs whose native libraries are packaged; empty packages every ABI found under
   * [AndroidTarget.jniLibs].
   */
  public val abiFilters: List<AndroidAbi>

  /**
   * Whether R8 shrinks, optimizes and obfuscates the classes before they are dexed, in place of
   * `d8`. Distinct from [release], which only decides whether debug information survives dexing.
   */
  public val minify: Boolean

  /**
   * ProGuard rule files, appended after the rules the consumed libraries contribute. Ignored
   * unless [minify] is set.
   */
  public val proguardFiles: List<String>

  /**
   * How Java resources are packaged.
   */
  public val packaging: AndroidPackaging
}

/**
 * The test runner an APK of instrumented tests declares, which `android-test-<artifact>` starts.
 */
@Serializable
@SerialName("elide.android.AndroidInstrumentation")
public data class AndroidInstrumentation(
  /**
   * Runner class: `<instrumentation android:name>`.
   */
  public val runner: String = "androidx.test.runner.AndroidJUnitRunner",
  /**
   * Package whose process the tests run in. Absent instruments the APK itself — a
   * self-instrumenting test APK, which carries the code under test beside its tests.
   */
  public val targetPackage: String? = null,
)

/**
 * Describes an Android application package (APK) built from this project's sources.
 *
 * The pipeline is `aapt2 compile` → `d8` → `aapt2 link` → `zipalign` → `apksigner`: resources
 * compile to a flat archive, compiled classes and the runtime classpath dex, and the linked APK
 * is aligned and (when [signing] is declared) signed before it is published.
 */
@Serializable
@SerialName("elide.android.AndroidApk")
public data class AndroidApk(
  /**
   * Other artifacts this artifact depends on.
   */
  override val dependsOn: List<String> = emptyList(),
  /**
   * File name of the published artifact; absent takes the artifact's name plus the extension of
   * its kind (`.apk`, `.aar`).
   */
  override val name: String? = null,
  /**
   * Source sets whose compiled classes the artifact carries; empty takes every non-test set.
   */
  override val sources: List<String> = emptyList(),
  /**
   * Android manifest of this artifact, merged with those of the libraries it consumes.
   */
  override val manifestFile: String = "src/main/AndroidManifest.xml",
  /**
   * Resource directories compiled by `aapt2`; empty takes `src/main/res` when it exists.
   */
  override val resources: List<String> = emptyList(),
  /**
   * Directories packaged verbatim under `assets/`; empty takes `src/main/assets` when it exists.
   */
  override val assets: List<String> = emptyList(),
  /**
   * Values substituted for `${name}` placeholders while merging manifests. An application always
   * supplies `applicationId` too: its declared [AndroidApplication.applicationId], else the
   * `package` its own manifest declares. A library substitutes only these and leaves every other
   * placeholder, `${applicationId}` included, for the application that consumes it.
   */
  override val placeholders: Map<String, String> = emptyMap(),
  /**
   * Extra arguments appended to `aapt2 link`.
   */
  override val linkArgs: List<String> = emptyList(),
  /**
   * Directories of native libraries, laid out as `<abi>/lib<name>.so`; empty takes
   * `src/main/jniLibs` when it exists.
   */
  override val jniLibs: List<String> = emptyList(),
  /**
   * Package the application is installed and published as. Absent takes the `package` its
   * manifest declares, which stays the namespace its `R` class and relative class names live in;
   * declaring one is how a debug build installs beside a release build of the same sources.
   */
  override val applicationId: String? = null,
  /**
   * `android:versionCode` of the merged manifest, replacing any the manifest declares.
   */
  override val versionCode: Long? = null,
  /**
   * `android:versionName` of the merged manifest, replacing any the manifest declares.
   */
  override val versionName: String? = null,
  /**
   * Whether this is a release build. `false` is a debug build: `d8` keeps debug information and
   * skips optimization, and the merged manifest marks the app `android:debuggable` unless a
   * manifest declares that attribute itself.
   */
  override val release: Boolean = true,
  /**
   * Signing configuration; absent publishes the artifact unsigned. An APK is signed with
   * `apksigner`; a bundle with `jarsigner`, which is how a store's upload key is applied.
   */
  override val signing: AndroidSigning? = null,
  /**
   * Extra arguments appended to the dexer: `d8`, or R8 when [minify] is set — so an argument only
   * one of them accepts fails the other.
   */
  override val dexArgs: List<String> = emptyList(),
  /**
   * ABIs whose native libraries are packaged; empty packages every ABI found under
   * [AndroidTarget.jniLibs].
   */
  override val abiFilters: List<AndroidAbi> = emptyList(),
  /**
   * Whether R8 shrinks, optimizes and obfuscates the classes before they are dexed, in place of
   * `d8`. Distinct from [release], which only decides whether debug information survives dexing.
   */
  override val minify: Boolean = false,
  /**
   * ProGuard rule files, appended after the rules the consumed libraries contribute. Ignored
   * unless [minify] is set.
   */
  override val proguardFiles: List<String> = emptyList(),
  /**
   * How Java resources are packaged.
   */
  override val packaging: AndroidPackaging = AndroidPackaging(),
  /**
   * Declares the APK an instrumented test APK: the merged manifest gains the `<instrumentation>`
   * element naming [AndroidInstrumentation.runner], unless the manifest declares that runner
   * itself.
   */
  public val instrumentation: AndroidInstrumentation? = null,
) : AndroidApplication

/**
 * Describes an Android library archive (AAR) built from this project's sources.
 *
 * The archive carries the library's classes, its resources as written, the manifest it merged
 * and the `R.txt` naming what it declares. Resources are *not* linked into it: an application
 * links its own and its libraries' resources in one pass, which is what gives every identifier
 * in the application one numbering.
 */
@Serializable
@SerialName("elide.android.AndroidAar")
public data class AndroidAar(
  /**
   * Other artifacts this artifact depends on.
   */
  override val dependsOn: List<String> = emptyList(),
  /**
   * File name of the published artifact; absent takes the artifact's name plus the extension of
   * its kind (`.apk`, `.aar`).
   */
  override val name: String? = null,
  /**
   * Source sets whose compiled classes the artifact carries; empty takes every non-test set.
   */
  override val sources: List<String> = emptyList(),
  /**
   * Android manifest of this artifact, merged with those of the libraries it consumes.
   */
  override val manifestFile: String = "src/main/AndroidManifest.xml",
  /**
   * Resource directories compiled by `aapt2`; empty takes `src/main/res` when it exists.
   */
  override val resources: List<String> = emptyList(),
  /**
   * Directories packaged verbatim under `assets/`; empty takes `src/main/assets` when it exists.
   */
  override val assets: List<String> = emptyList(),
  /**
   * Values substituted for `${name}` placeholders while merging manifests. An application always
   * supplies `applicationId` too: its declared [AndroidApplication.applicationId], else the
   * `package` its own manifest declares. A library substitutes only these and leaves every other
   * placeholder, `${applicationId}` included, for the application that consumes it.
   */
  override val placeholders: Map<String, String> = emptyMap(),
  /**
   * Extra arguments appended to `aapt2 link`.
   */
  override val linkArgs: List<String> = emptyList(),
  /**
   * Directories of native libraries, laid out as `<abi>/lib<name>.so`; empty takes
   * `src/main/jniLibs` when it exists.
   */
  override val jniLibs: List<String> = emptyList(),
  /**
   * ProGuard rule files shipped in the archive as `proguard.txt`, which an application that
   * minifies applies ahead of its own rules — whether it consumes the archive or this project.
   */
  public val consumerProguardFiles: List<String> = emptyList(),
) : AndroidTarget

/**
 * Describes an Android app bundle (AAB): the publishing format Play splits into per-device APKs.
 *
 * The pipeline is the application's own up to dexing, and then diverges: `aapt2 link` emits the
 * manifest and resource table in protobuf form, the pieces are assembled into a bundletool
 * *module* zip, and `bundletool build-bundle` writes the bundle. A bundle is never aligned —
 * alignment is decided when the APKs are split out of it — and is signed with the upload key when
 * [signing] is declared; the store re-signs the APKs it generates with the app signing key.
 */
@Serializable
@SerialName("elide.android.AndroidAab")
public data class AndroidAab(
  /**
   * Other artifacts this artifact depends on.
   */
  override val dependsOn: List<String> = emptyList(),
  /**
   * File name of the published artifact; absent takes the artifact's name plus the extension of
   * its kind (`.apk`, `.aar`).
   */
  override val name: String? = null,
  /**
   * Source sets whose compiled classes the artifact carries; empty takes every non-test set.
   */
  override val sources: List<String> = emptyList(),
  /**
   * Android manifest of this artifact, merged with those of the libraries it consumes.
   */
  override val manifestFile: String = "src/main/AndroidManifest.xml",
  /**
   * Resource directories compiled by `aapt2`; empty takes `src/main/res` when it exists.
   */
  override val resources: List<String> = emptyList(),
  /**
   * Directories packaged verbatim under `assets/`; empty takes `src/main/assets` when it exists.
   */
  override val assets: List<String> = emptyList(),
  /**
   * Values substituted for `${name}` placeholders while merging manifests. An application always
   * supplies `applicationId` too: its declared [AndroidApplication.applicationId], else the
   * `package` its own manifest declares. A library substitutes only these and leaves every other
   * placeholder, `${applicationId}` included, for the application that consumes it.
   */
  override val placeholders: Map<String, String> = emptyMap(),
  /**
   * Extra arguments appended to `aapt2 link`.
   */
  override val linkArgs: List<String> = emptyList(),
  /**
   * Directories of native libraries, laid out as `<abi>/lib<name>.so`; empty takes
   * `src/main/jniLibs` when it exists.
   */
  override val jniLibs: List<String> = emptyList(),
  /**
   * Package the application is installed and published as. Absent takes the `package` its
   * manifest declares, which stays the namespace its `R` class and relative class names live in;
   * declaring one is how a debug build installs beside a release build of the same sources.
   */
  override val applicationId: String? = null,
  /**
   * `android:versionCode` of the merged manifest, replacing any the manifest declares.
   */
  override val versionCode: Long? = null,
  /**
   * `android:versionName` of the merged manifest, replacing any the manifest declares.
   */
  override val versionName: String? = null,
  /**
   * Whether this is a release build. `false` is a debug build: `d8` keeps debug information and
   * skips optimization, and the merged manifest marks the app `android:debuggable` unless a
   * manifest declares that attribute itself.
   */
  override val release: Boolean = true,
  /**
   * Signing configuration; absent publishes the artifact unsigned. An APK is signed with
   * `apksigner`; a bundle with `jarsigner`, which is how a store's upload key is applied.
   */
  override val signing: AndroidSigning? = null,
  /**
   * Extra arguments appended to the dexer: `d8`, or R8 when [minify] is set — so an argument only
   * one of them accepts fails the other.
   */
  override val dexArgs: List<String> = emptyList(),
  /**
   * ABIs whose native libraries are packaged; empty packages every ABI found under
   * [AndroidTarget.jniLibs].
   */
  override val abiFilters: List<AndroidAbi> = emptyList(),
  /**
   * Whether R8 shrinks, optimizes and obfuscates the classes before they are dexed, in place of
   * `d8`. Distinct from [release], which only decides whether debug information survives dexing.
   */
  override val minify: Boolean = false,
  /**
   * ProGuard rule files, appended after the rules the consumed libraries contribute. Ignored
   * unless [minify] is set.
   */
  override val proguardFiles: List<String> = emptyList(),
  /**
   * How Java resources are packaged.
   */
  override val packaging: AndroidPackaging = AndroidPackaging(),
  /**
   * Extra arguments appended to `bundletool build-bundle`.
   */
  public val bundleArgs: List<String> = emptyList(),
  /**
   * Dynamic feature artifacts in this project, packaged as separate bundle modules.
   */
  public val features: List<String> = emptyList(),
) : AndroidApplication

/**
 * A separately delivered bundle module. Shared workspace/Maven libraries belong to the base;
 * feature sources must be explicit and disjoint from the base and other features.
 *
 * Unlike the other targets, a feature takes no defaults from the project layout: empty
 * [sources] is an error, and empty [resources], [assets] and [jniLibs] package none. It is
 * never published on its own, so it declares no [name].
 */
@Serializable
@SerialName("elide.android.AndroidFeature")
public data class AndroidFeature(
  /**
   * Other artifacts this artifact depends on.
   */
  override val dependsOn: List<String> = emptyList(),
  /**
   * File name of the published artifact; absent takes the artifact's name plus the extension of
   * its kind (`.apk`, `.aar`).
   */
  override val name: String? = null,
  /**
   * Source sets whose compiled classes the artifact carries; empty takes every non-test set.
   */
  override val sources: List<String> = emptyList(),
  /**
   * Android manifest of this artifact, merged with those of the libraries it consumes.
   */
  override val manifestFile: String = "src/main/AndroidManifest.xml",
  /**
   * Resource directories compiled by `aapt2`; empty takes `src/main/res` when it exists.
   */
  override val resources: List<String> = emptyList(),
  /**
   * Directories packaged verbatim under `assets/`; empty takes `src/main/assets` when it exists.
   */
  override val assets: List<String> = emptyList(),
  /**
   * Values substituted for `${name}` placeholders while merging manifests. An application always
   * supplies `applicationId` too: its declared [AndroidApplication.applicationId], else the
   * `package` its own manifest declares. A library substitutes only these and leaves every other
   * placeholder, `${applicationId}` included, for the application that consumes it.
   */
  override val placeholders: Map<String, String> = emptyMap(),
  /**
   * Extra arguments appended to `aapt2 link`.
   */
  override val linkArgs: List<String> = emptyList(),
  /**
   * Directories of native libraries, laid out as `<abi>/lib<name>.so`; empty takes
   * `src/main/jniLibs` when it exists.
   */
  override val jniLibs: List<String> = emptyList(),
  /**
   * AndroidAab artifact containing this module.
   */
  public val base: String,
  /**
   * Delivery policy encoded in the module's distribution manifest.
   */
  public val delivery: AndroidFeatureDelivery = AndroidFeatureDelivery.On_demand,
  /**
   * Localized title resource defined by the base (for example `@string/feature_title`).
   */
  public val title: String,
  /**
   * Include this module when producing fused APKs for pre-Lollipop devices.
   */
  public val fusing: Boolean = true,
  /**
   * Other feature modules this module requires, in the same bundle.
   */
  public val featureDependencies: List<String> = emptyList(),
) : AndroidTarget

/**
 * Android API level: the platform revision an app is compiled, linked or dexed against.
 */
public typealias AndroidApiLevel = Long

/**
 * Android build-tools release, as the SDK names it (`36.0.0`); supplies `aapt2`, `d8`, `zipalign`
 * and `apksigner`.
 */
public typealias AndroidBuildToolsVersion = String

/**
 * An Android ABI: the instruction set a native library is built for, and the one an emulator
 * system image runs.
 */
@Serializable
@SerialName("elide.android.AndroidAbi")
public enum class AndroidAbi {
  @SerialName("arm64-v8a")
  Arm64_v8a,
  @SerialName("x86_64")
  X86_64,
  @SerialName("armeabi-v7a")
  Armeabi_v7a,
  @SerialName("x86")
  X86,
}

/**
 * Name of a virtual device in [AndroidSettings.devices]: a letter, then letters, digits, `_` or
 * `-`. `all` is taken by the task that runs every device.
 */
public typealias AndroidDeviceName = String

/**
 * Delivery policy for a dynamic feature module.
 */
@Serializable
@SerialName("elide.android.AndroidFeatureDelivery")
public enum class AndroidFeatureDelivery {
  @SerialName("install-time")
  Install_time,
  @SerialName("on-demand")
  On_demand,
}
