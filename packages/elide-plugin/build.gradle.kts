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

plugins {
  alias(libs.plugins.intellij.platform)
  kotlin("jvm")
  id("java")
}

repositories {
  intellijPlatform {
    customPluginRepository("https://plugins.elide.dev/intellij", CustomPluginRepositoryType.SIMPLE)
    defaultRepositories()
  }

  mavenLocal()
  mavenCentral()
  google()
}

dependencies {
  implementation(libs.kotlinx.serialization.json)

  intellijPlatform {
    create("IC", libs.versions.intellij.target.ide.get())
    bundledPlugin("com.intellij.java")
    bundledPlugin("org.jetbrains.kotlin")
    plugin("pkl-intellij", "0.32.0", "org.pkl")
    testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
  }
}

intellijPlatform {
  pluginConfiguration {
    id = "dev.elide"

    ideaVersion {
      sinceBuild = libs.versions.intellij.sinceBuild.get()
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
