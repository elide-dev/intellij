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
package dev.elide.intellij.execution.coverage

import com.intellij.coverage.CoverageAnnotator
import com.intellij.coverage.CoverageEngine
import com.intellij.coverage.CoverageFileProvider
import com.intellij.coverage.CoverageRunner
import com.intellij.coverage.CoverageSuite
import com.intellij.coverage.CoverageSuitesBundle
import com.intellij.coverage.view.CoverageViewExtension
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.coverage.CoverageEnabledConfiguration
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import dev.elide.intellij.Constants
import java.util.Locale

/**
 * Coverage engine for Elide runs, covering every language one run measures.
 *
 * A single Elide test run reports on Kotlin and Java through JaCoCo and on JavaScript, TypeScript and the other
 * guest languages through the runtime's own instrument. The IDE keeps one active suite per engine, so splitting
 * those halves across the platform's Java engine and an engine of our own would mean two suites for one run, only
 * one of which is displayed at a time. This engine therefore keys coverage by source file path, which every
 * language of a run has, rather than by class.
 *
 * The reports themselves are produced by the CLI and merged by [ElideCoverageReports].
 */
class ElideCoverageEngine : CoverageEngine() {
  override fun getPresentableText(): String = Constants.Strings["coverage.engine.name"]

  /**
   * No run configuration is claimed, and none needs to be: the plugin builds the suite of a coverage run itself,
   * from the reports the CLI writes, rather than through the platform's run-configuration coverage settings.
   *
   * Claiming one is also actively harmful here. The Java coverage extension attaches itself to *any* configuration
   * some engine claims (`CoverageJavaRunConfigurationExtension.isApplicableFor` asks exactly that), and then reads
   * and writes it as a `JavaCoverageEnabledConfiguration`, which an Elide configuration's coverage settings are
   * not: an `ExternalSystemRunConfiguration` runs those extensions, so claiming a run made saving the
   * configuration fail.
   */
  override fun isApplicableTo(configuration: RunConfigurationBase<*>): Boolean = false

  /**
   * Reached only for a configuration this engine claims, of which there are none; the platform declares it
   * abstract, so the answer is the settings an Elide run would use rather than an exception.
   */
  override fun createCoverageEnabledConfiguration(configuration: RunConfigurationBase<*>): CoverageEnabledConfiguration {
    return ElideCoverageEnabledConfiguration(configuration)
  }

  override fun createCoverageSuite(
    name: String,
    project: Project,
    runner: CoverageRunner,
    fileProvider: CoverageFileProvider,
    timestamp: Long,
  ): CoverageSuite = ElideCoverageSuite(name, project, runner, fileProvider, timestamp, this)

  override fun createCoverageSuite(
    name: String,
    project: Project,
    runner: CoverageRunner,
    fileProvider: CoverageFileProvider,
    timestamp: Long,
    config: CoverageEnabledConfiguration,
  ): CoverageSuite? {
    if (config !is ElideCoverageEnabledConfiguration) return null

    return ElideCoverageSuite(name, project, runner, fileProvider, timestamp, this)
  }

  override fun createEmptyCoverageSuite(coverageRunner: CoverageRunner): CoverageSuite = ElideCoverageSuite(this)

  override fun getCoverageAnnotator(project: Project): CoverageAnnotator = ElideCoverageAnnotator.getInstance(project)

  /**
   * Coverage is reported for source files, whichever language they are written in, so any file the IDE can show is
   * one this engine may have data for. A file the report does not mention simply has no highlighting.
   */
  override fun coverageEditorHighlightingApplicableTo(psiFile: PsiFile): Boolean = psiFile.virtualFile != null

  /**
   * The name a coverage record is keyed by: the file's own path, normalized the way the LCOV reader normalizes the
   * paths it reads, so the two match on every platform.
   *
   * A `VirtualFile` path already uses forward slashes, which leaves the reader's other rule: paths are compared
   * case-insensitively on Windows, where the file system is.
   */
  override fun getQualifiedNames(sourceFile: PsiFile): Set<String> {
    val path = sourceFile.virtualFile?.path ?: return emptySet()

    return setOf(if (SystemInfoRt.isWindows) path.lowercase(Locale.ROOT) else path)
  }

  override fun coverageProjectViewStatisticsApplicableTo(fileOrDir: VirtualFile): Boolean = !fileOrDir.isDirectory

  /**
   * The coverage tool window shows the project's directory tree: an Elide run spans several languages, and only the
   * directories they live in are common to all of them.
   */
  override fun createCoverageViewExtension(project: Project, suiteBundle: CoverageSuitesBundle): CoverageViewExtension {
    return ElideCoverageViewExtension(project, getCoverageAnnotator(project), suiteBundle)
  }
}
