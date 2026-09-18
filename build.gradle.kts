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
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel

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

  mavenCentral()
}

fun renderChangelogSection(changelog: String, version: String): String {
  val lines = changelog.lines()
  val headings = lines.withIndex().filter { it.value.startsWith("## ") }
  val start = headings.firstOrNull { it.value.startsWith("## [$version]") }
    ?: error("CHANGELOG.md has no section for $version")
  val end = headings.firstOrNull { it.index > start.index }?.index ?: lines.size

  // fold the section into blocks first: bullets wrap across lines, and only their joined text can be rendered
  val blocks = mutableListOf<Pair<String, String>>()
  for (raw in lines.subList(start.index + 1, end)) {
    val line = raw.trim()
    when {
      line.isEmpty() -> Unit
      line.startsWith("### ") -> blocks += "h" to line.removePrefix("### ")
      line.startsWith("- ") || line.startsWith("* ") -> blocks += "li" to line.drop(2)
      blocks.lastOrNull()?.first == "li" -> blocks += "li" to "${blocks.removeAt(blocks.lastIndex).second} $line"
      else -> blocks += "p" to line
    }
  }

  // the inline spans changelog entries use
  val inlineSpans = Regex("""`([^`]+)`|\[([^]]+)]\(([^)]+)\)|\*\*([^*]+)\*\*|\*([^*]+)\*""")

  fun spans(text: String): String = text.replace(inlineSpans) { match ->
    val (code, linkText, href, bold, italic) = match.destructured
    when {
      code.isNotEmpty() -> "<code>$code</code>"
      linkText.isNotEmpty() -> """<a href="$href">${spans(linkText)}</a>"""
      bold.isNotEmpty() -> "<b>${spans(bold)}</b>"
      else -> "<i>${spans(italic)}</i>"
    }
  }

  fun inline(text: String) = spans(
    text
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
  )

  return buildString {
    var inList = false
    fun closeList() = if (inList) append("</ul>").also { inList = false } else this
    for ((kind, text) in blocks) when (kind) {
      "h" -> closeList().append("<p><b>${inline(text)}</b></p>")
      "li" -> {
        if (!inList) append("<ul>").also { inList = true }
        append("<li>${inline(text)}</li>")
      }
      else -> closeList().append("<p>${inline(text)}</p>")
    }
    closeList()
  }
}

dependencies {
  implementation(libs.kotlinx.serialization.json)
  // the JVM half of an `elide test --coverage` run is a JaCoCo execution file, which only class analysis turns into
  // the line coverage the IDE displays; the guest half is already LCOV, which the platform parses
  implementation(libs.jacoco.core)

  testImplementation(platform(libs.junit.bom))
  testImplementation(kotlin("test"))
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)
  // tests are written in JUnit 5; JUnit 4 is only on the runtime classpath because the platform test framework's
  // `JUnit5TestSessionListener` and its rules load `org.junit.runners` classes
  testRuntimeOnly(libs.junit4)

  intellijPlatform {
    intellijIdea(libs.versions.intellij.target.ide.get())
    bundledPlugin("com.intellij.java")
    bundledPlugin("org.jetbrains.kotlin")
    // `com.intellij.coverage`: the coverage engine, runner and LCOV reader behind "Run with Coverage", and the
    // `ProjectData` model both of them speak
    bundledModule("intellij.platform.coverage")
    bundledModule("intellij.platform.coverage.agent")
    plugin(id = "org.pkl", version = libs.versions.pkl.plugin.get())
    // the Pkl plugin depends on Markdown; without it the platform refuses to load Pkl in the test IDE, and with it
    // every Pkl-language extension of this plugin (the manifest gutter icons, references and inspections)
    bundledPlugin("org.intellij.plugins.markdown")
    // the CIDR debugger API a Native Image session runs on; resolved from the marketplace, not part of the IDE
    // distribution, and depended on optionally so the plugin still loads without it
    plugin(id = "com.intellij.nativeDebug", version = libs.versions.native.debug.plugin.get())
    testFramework(TestFrameworkType.Platform)
    testFramework(TestFrameworkType.JUnit5)
  }
}

configurations.runtimeClasspath {
  // provided by intellij
  exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
  exclude(group = "org.jetbrains", module = "annotations")
}

// The IntelliJ platform test framework drives the PSI and run-configuration tests through its JUnit 5 fixtures, next
// to the plain Jupiter tests over the generated model.
tasks.test {
  useJUnitPlatform()
}

intellijPlatform {
  pluginConfiguration {
    id = "dev.elide"

    ideaVersion {
      sinceBuild = libs.versions.intellij.sinceBuild.get()
      untilBuild = libs.versions.intellij.untilBuild.get()
    }

    // `project.version` is qualified on purpose: inside this block, a bare `version` resolves to the plugin
    // configuration's own `version` property, not the project's
    changeNotes = providers.fileContents(layout.projectDirectory.file("CHANGELOG.md")).asText.map { changelog ->
      renderChangelogSection(changelog, project.version.toString())
    }
  }

  pluginVerification {
    ides {
      recommended()
    }

    failureLevel = listOf(
      FailureLevel.COMPATIBILITY_WARNINGS,
      FailureLevel.COMPATIBILITY_PROBLEMS,
      FailureLevel.INVALID_PLUGIN,
    )
  }

  signing {
    certificateChain = providers.environmentVariable("ELIDE_JB_CERT_CHAIN")
    privateKey = providers.environmentVariable("ELIDE_JB_KEY")
    password = providers.environmentVariable("ELIDE_JB_KEY_PASSWORD")
  }

  publishing {
    token = providers.environmentVariable("ELIDE_JB_TOKEN")

    channels = provider {
      // derive channel from the version qualifier
      listOf(version.toString().substringAfter('-', "").substringBefore('.').ifEmpty { "default" })
    }
  }
}

tasks.processResources {
  from(layout.projectDirectory.dir("src/main/pkl")) {
    into("/elide/pkl/")
  }
}
