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

import com.intellij.coverage.BaseCoverageAnnotator.DirCoverageInfo
import com.intellij.coverage.CoverageDataManager
import com.intellij.coverage.CoverageSuitesBundle
import com.intellij.coverage.SimpleCoverageAnnotator
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.rt.coverage.data.ProjectData

/**
 * Turns a merged Elide report into the per-file and per-directory percentages shown in the project view and the
 * coverage tool window.
 *
 * The platform's file-based annotator is used as it is for everything inside a content root: coverage is keyed by
 * source path, so directories aggregate the files they hold without any language-specific grouping. What it is not
 * asked to do is walk above those roots, which is where an Elide project differs from most: its modules are rooted
 * at the source folders the manifest declares, so `src`, and the project directory itself, are in no content root.
 * The coverage tool window is a tree of the project directory, and lists only directories with coverage of their
 * own, so without the figures added here it shows an empty tree for a report the project view happily annotates.
 */
@Service(Service.Level.PROJECT)
class ElideCoverageAnnotator(project: Project) : SimpleCoverageAnnotator(project) {
  /** Dependencies are not part of a project's coverage, and walking them costs a directory scan per suite. */
  override fun shouldCollectCoverageInsideLibraryDirs(): Boolean = false

  override fun annotate(
    contentRoot: VirtualFile,
    suite: CoverageSuitesBundle,
    dataManager: CoverageDataManager,
    data: ProjectData,
    project: Project,
    annotator: CoverageAnnotatorRunner,
  ) {
    super.annotate(contentRoot, suite, dataManager, data, project, annotator)

    annotateAbove(contentRoot, data, project, annotator)
  }

  /**
   * Records coverage for the directories between [contentRoot] and the root of the Elide project holding it.
   *
   * Their figures come from the report rather than from a directory walk: a directory outside every content root is
   * one the platform's walk refuses to enter, and the files below it that the report names are exactly the ones its
   * coverage is the sum of. Content roots sharing an ancestor each record the same total for it, which is the total
   * over all of them, so the order they are annotated in does not matter.
   */
  private fun annotateAbove(
    contentRoot: VirtualFile,
    data: ProjectData,
    project: Project,
    annotator: CoverageAnnotatorRunner,
  ) {
    val top = ElideCoverageRoots.of(project, contentRoot) ?: return

    var directory = contentRoot.parent
    while (directory != null && VfsUtilCore.isAncestor(top, directory, false)) {
      val path = normalizeFilePath(directory.path)
      val coverage = coverageBelow(path, data) ?: return

      annotator.annotateSourceDirectory(path, coverage)
      if (directory == top) return

      directory = directory.parent
    }
  }

  /** The coverage of every file the report names under [directoryPath], or `null` when it names none. */
  private fun coverageBelow(directoryPath: String, data: ProjectData): DirCoverageInfo? {
    val prefix = "$directoryPath/"
    val coverage = DirCoverageInfo()

    for ((file, classData) in data.classes) {
      if (!normalizeFilePath(file).startsWith(prefix)) continue
      val fileCoverage = fileInfoForCoveredFile(classData) ?: continue

      coverage.totalLineCount += fileCoverage.totalLineCount
      coverage.totalFilesCount++
      if (fileCoverage.coveredLineCount > 0) {
        coverage.coveredLineCount += fileCoverage.coveredLineCount
        coverage.coveredFilesCount++
      }
    }

    return coverage.takeIf { it.totalFilesCount > 0 }
  }

  internal companion object {
    fun getInstance(project: Project): ElideCoverageAnnotator = project.service()
  }
}
