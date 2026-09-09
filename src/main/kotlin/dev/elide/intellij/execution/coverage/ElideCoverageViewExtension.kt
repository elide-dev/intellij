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
import com.intellij.coverage.CoverageSuitesBundle
import com.intellij.coverage.view.CoverageListNode
import com.intellij.coverage.view.CoverageListRootNode
import com.intellij.coverage.view.DirectoryCoverageViewExtension
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiManager

/**
 * The tree the coverage tool window shows for an Elide report.
 *
 * The platform's directory view roots itself at the IDE's guess of a project directory and lists only the children
 * the annotator has figures for. Neither holds for an Elide project: its modules are rooted at the source folders
 * the manifest declares, so the guess lands inside one of them, and the directories on the way down to them belong
 * to no content root, which is what the annotator walks. The tree is therefore rooted at the Elide project, and its
 * children are taken from the report, which names every file the run measured.
 */
internal class ElideCoverageViewExtension(
  project: Project,
  annotator: CoverageAnnotator,
  private val bundle: CoverageSuitesBundle,
) : DirectoryCoverageViewExtension(project, annotator, bundle) {
  override fun createRootNode(): AbstractTreeNode<*> {
    val root = ApplicationManager.getApplication().runReadAction(Computable {
      val covered = coveredPaths().firstNotNullOfOrNull { LocalFileSystem.getInstance().findFileByPath(it) }
      val directory = covered?.let { ElideCoverageRoots.of(myProject, it) }

      directory?.let { PsiManager.getInstance(myProject).findDirectory(it) }
    }) ?: return super.createRootNode()

    return CoverageListRootNode(myProject, root, bundle)
  }

  override fun getChildrenNodes(node: AbstractTreeNode<*>): List<AbstractTreeNode<*>> {
    val directory = node.value as? PsiDirectory ?: return emptyList()
    val covered = coveredPaths()
    if (covered.isEmpty()) return super.getChildrenNodes(node)

    return ApplicationManager.getApplication().runReadAction(Computable {
      val children = mutableListOf<AbstractTreeNode<*>>()

      for (subdirectory in directory.subdirectories) {
        val prefix = "${subdirectory.virtualFile.path}/"
        if (covered.none { it.startsWith(prefix) }) continue

        children += CoverageListNode(myProject, subdirectory, bundle)
      }

      for (file in directory.files) {
        if (file.virtualFile?.path !in covered) continue

        children += CoverageListNode(myProject, file, bundle)
      }

      children
    })
  }

  /** Paths of the files the active report names, which is what the tree is built from. */
  private fun coveredPaths(): Set<String> = bundle.coverageData?.classes?.keys.orEmpty().toSet()
}
