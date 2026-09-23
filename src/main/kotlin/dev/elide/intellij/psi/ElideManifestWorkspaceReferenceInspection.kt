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
package dev.elide.intellij.psi

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import dev.elide.intellij.Constants
import org.pkl.intellij.psi.PklStringContent

/**
 * Reports workspace references in a manifest that name nothing: member paths without a manifest, unknown projects,
 * and artifacts their project does not declare.
 *
 * Project names are only checked in a manifest that is part of a workspace — the root, or a member the root declares.
 * Anywhere else the IDE cannot know which projects exist, and every reference would be reported.
 */
class ElideManifestWorkspaceReferenceInspection : LocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean) = object : PsiElementVisitor() {
    override fun visitElement(element: PsiElement) {
      if (element !is PklStringContent) return

      for (reference in workspaceReferences(element)) when (reference) {
        is ElideMemberPathReference -> checkMemberPath(reference, holder)
        is ElideProjectNameReference -> checkProject(reference, holder)
        is ElideArtifactReference -> checkArtifact(reference, holder)
      }
    }
  }

  private fun checkMemberPath(reference: ElideMemberPathReference, holder: ProblemsHolder) {
    // the path is reported once, as a whole, from its last segment: a missing directory already leaves every segment
    // below it unresolved
    if (!reference.isLast) return

    val path = reference.fileReferenceSet.pathString
    val range = TextRange(0, reference.rangeInElement.endOffset)

    when (val directory = reference.resolve()) {
      !is PsiDirectory -> holder.registerProblem(
        reference.element,
        range,
        Constants.Strings["elide.inspection.manifest.workspace.unresolvedMember", path],
      )

      else -> if (directory.findFile(Constants.MANIFEST_NAME) == null) holder.registerProblem(
        reference.element,
        range,
        Constants.Strings["elide.inspection.manifest.workspace.memberWithoutManifest", path, Constants.MANIFEST_NAME],
      )
    }
  }

  private fun checkProject(reference: ElideProjectNameReference, holder: ProblemsHolder) {
    val workspace = reference.element.containingFile.manifestWorkspace() ?: return
    if (!workspace.isWorkspace || reference.resolve() != null) return

    holder.registerProblem(
      reference,
      Constants.Strings["elide.inspection.manifest.workspace.unresolvedProject", reference.projectName],
      ProblemHighlightType.LIKE_UNKNOWN_SYMBOL,
    )
  }

  private fun checkArtifact(reference: ElideArtifactReference, holder: ProblemsHolder) {
    // an unknown project is reported on its own name; its artifacts cannot be checked
    val project = reference.targetProject() ?: return
    if (reference.resolve() != null) return

    holder.registerProblem(
      reference,
      Constants.Strings["elide.inspection.manifest.workspace.unresolvedArtifact", reference.artifactName, project.name],
      ProblemHighlightType.LIKE_UNKNOWN_SYMBOL,
    )
  }
}
