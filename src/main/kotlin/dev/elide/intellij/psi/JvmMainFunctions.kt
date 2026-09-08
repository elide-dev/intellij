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

import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.util.ClassUtil
import org.jetbrains.kotlin.psi.KtFile

/**
 * The JVM binary name of the class holding the `main` entrypoint covering [element], or `null` when the element is not
 * part of a JVM entrypoint. Both Java and Kotlin sources are recognized.
 *
 * Detection is a PSI-only heuristic in either language: no resolution is performed, so it also answers while indices
 * are unavailable, which run configuration producers require (they are queried on the EDT during action updates, and
 * this one is dumb-aware).
 */
fun findJvmMainClassName(element: PsiElement): String? {
  val file = element.containingFile ?: return null

  // entrypoints are run from the project's own sources; library and generated files are not offered
  val virtualFile = file.virtualFile ?: return null
  if (!ProjectFileIndex.getInstance(file.project).isInSourceContent(virtualFile)) return null

  return when (file) {
    is KtFile -> findKotlinMainOwner(file, element)?.let(::kotlinMainClassJvmName)
    // anonymous and local classes have no JVM class name the launcher can start, and `getJVMClassName` returns null
    // for them
    is PsiJavaFile -> findJavaMainClass(file, element)?.let(ClassUtil::getJVMClassName)
    else -> null
  }
}
