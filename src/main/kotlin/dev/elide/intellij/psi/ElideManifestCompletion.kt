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

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionConfidence
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ThreeState
import dev.elide.intellij.Constants
import org.pkl.intellij.psi.PklStringLiteral

/**
 * Lets completion pop up by itself inside a manifest's workspace strings, which the platform otherwise suppresses in
 * every string literal: names of projects and artifacts are exactly what the popup should offer while typing.
 *
 * Only a manifest is answered for. Workspace strings are recognized by their syntactic position alone, so any other
 * Pkl file may spell the same shapes without naming anything of a workspace, and there the platform is right.
 */
class ElideManifestCompletionConfidence : CompletionConfidence() {
  override fun shouldSkipAutopopup(
    editor: Editor,
    contextElement: PsiElement,
    psiFile: PsiFile,
    offset: Int,
  ): ThreeState {
    return if (psiFile.isManifest() && contextElement.isInWorkspaceString()) ThreeState.NO else ThreeState.UNSURE
  }
}

/**
 * Opens completion as soon as a workspace string is started or a path segment is begun: after the opening quote of
 * `project("`, and after each `/` of a `workspace.members` entry.
 */
class ElideManifestTypedHandler : TypedHandlerDelegate() {
  override fun checkAutoPopup(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
    if (charTyped != '"' && charTyped != '/') return Result.CONTINUE
    if (!file.isManifest()) return Result.CONTINUE

    // the condition runs once the character is in and the document committed, so it sees the string being typed
    AutoPopupController.getInstance(project).scheduleAutoPopup(editor, CompletionType.BASIC) { committed ->
      committed.findElementAt(editor.caretModel.offset)?.isInWorkspaceString() == true
    }

    return Result.CONTINUE
  }
}

/** Whether this element is part of a string literal naming something of the workspace. */
private fun PsiElement.isInWorkspaceString(): Boolean {
  val literal = PsiTreeUtil.getParentOfType(this, PklStringLiteral::class.java, false) ?: return false
  return literal.content.workspaceString() != null
}

/** Whether this file is a manifest, the only file the gates above answer for. */
private fun PsiFile.isManifest(): Boolean = originalFile.virtualFile?.name == Constants.MANIFEST_NAME
