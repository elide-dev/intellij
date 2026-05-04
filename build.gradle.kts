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

import org.jetbrains.intellij.platform.gradle.CustomPluginRepositoryType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.intellij.platform)
  id("java")
}

// plugin version is separate from the overall Elide version
version = layout.projectDirectory.file(".version").asFile.readText().trim()

kotlin {
  jvmToolchain {
    languageVersion = JavaLanguageVersion.of(21)
  }
}

repositories {
  intellijPlatform {
    customPluginRepository("https://plugins.elide.dev/intellij", CustomPluginRepositoryType.SIMPLE) {
      credentials { username = "" } // leave empty, workaround for Gradle's "MissingValueException" import bug
      content { includeGroup("org.pkl") }
    }
    defaultRepositories()
  }

  maven {
    name = "elide-snapshots"
    url = uri("https://maven.elide.dev")
    content {
      includeGroup("dev.elide")
      includeGroup("org.pkl-lang")
    }
  }

  maven {
    name = "oss-snapshots"
    url = uri("https://oss.sonatype.org/content/repositories/snapshots")
    content { includeGroup("dev.elide") }
  }

  mavenLocal()
  mavenCentral()
  google()
}

dependencies {
  implementation(libs.kotlinx.serialization.json)

  intellijPlatform {
    intellijIdea(libs.versions.intellij.target.ide.get())
    bundledPlugin("com.intellij.java")
    bundledPlugin("org.jetbrains.kotlin")
    plugin(id = "org.pkl", version = "0.35.1")
    testFramework(TestFrameworkType.Platform)
  }
}

intellijPlatform {
  pluginConfiguration {
    id = "dev.elide"

    ideaVersion {
      sinceBuild = libs.versions.intellij.sinceBuild.get()
      untilBuild = libs.versions.intellij.untilBuild.get()
    }

    changeNotes = "Initial release."
  }

  pluginVerification {
    ides {
      recommended()
    }
  }

  signing {
    certificateChain = providers.environmentVariable("ELIDE_JB_CERT_CHAIN")
    privateKey = providers.environmentVariable("ELIDE_JB_KEY")
    password = providers.environmentVariable("ELIDE_JB_KEY_PASSWORD")
  }

  publishing {
    token = providers.environmentVariable("ELIDE_JB_TOKEN")
  }
}

tasks.processResources {
  from(layout.projectDirectory.dir("src/main/pkl")) {
    into("/elide/pkl/")
  }
}
