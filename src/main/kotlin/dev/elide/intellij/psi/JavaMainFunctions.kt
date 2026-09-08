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

import com.intellij.psi.PsiAnonymousClass
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiParameter
import com.intellij.psi.util.PsiTreeUtil

/**
 * Locate the Java class declaring the `main` method that covers [element] within [file], or return `null` when the
 * file declares no JVM entrypoint.
 *
 * The lookup is a PSI-only heuristic: nothing is resolved, so it also answers while indices are unavailable. Only
 * `main` methods declared by the class itself are considered, matching the launcher's `public static void main` shape
 * (`String[]` and `String...` parameters alike); an inherited `main` is left to the platform's own producer.
 */
internal fun findJavaMainClass(file: PsiJavaFile, element: PsiElement): PsiClass? {
  var candidate = PsiTreeUtil.getParentOfType(element, PsiClass::class.java, false)
  while (candidate != null) {
    if (candidate.hasMainMethod()) return candidate
    candidate = PsiTreeUtil.getParentOfType(candidate, PsiClass::class.java, true)
  }

  // the caret needs not sit inside the entrypoint class: the platform's own Java producer offers a file's `main`
  // class from anywhere in that file, and an Elide entrypoint has to win the same locations
  return PsiTreeUtil.findChildrenOfType(file, PsiClass::class.java).firstOrNull { it.hasMainMethod() }
}

private fun PsiClass.hasMainMethod(): Boolean {
  // the launcher starts classes only: an interface (or annotation) never holds a runnable entrypoint, and an
  // anonymous class has no name to start
  if (isInterface || isAnnotationType || this is PsiAnonymousClass) return false

  // `checkBases` would resolve the supertypes, which is unavailable in dumb mode
  return findMethodsByName(MAIN_METHOD_NAME, false).any { it.isMainMethod() }
}

private fun PsiMethod.isMainMethod(): Boolean {
  if (isConstructor || typeParameters.isNotEmpty()) return false
  if (!hasModifierProperty(PsiModifier.PUBLIC) || !hasModifierProperty(PsiModifier.STATIC)) return false

  // the declared type element is read instead of the resolved return type, to keep the check index-free; `void` is a
  // keyword, so it can never be spelled differently
  if (returnTypeElement?.text != VOID_KEYWORD) return false

  return parameterList.parameters.singleOrNull()?.isMainParameter() == true
}

private fun PsiParameter.isMainParameter(): Boolean {
  // types are built from the declaration's own syntax, so the array shape and the component's name are available
  // without resolving anything; `String...` is a `PsiEllipsisType`, itself an array type
  val componentType = (type as? PsiArrayType)?.componentType ?: return false
  return (componentType as? PsiClassType)?.className == STRING_CLASS_NAME
}

private const val MAIN_METHOD_NAME = "main"
private const val STRING_CLASS_NAME = "String"
private const val VOID_KEYWORD = "void"
