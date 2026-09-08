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

import com.intellij.psi.PsiElement
import com.intellij.psi.util.ClassUtil
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.fileClasses.javaFileFacadeFqName
import org.jetbrains.kotlin.idea.base.psi.KotlinPsiHeuristics
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclarationContainer
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf

/**
 * Locate the Kotlin file or class declaring the `main` function that covers [element] within [file], or return `null`
 * when the file declares no JVM entrypoint.
 *
 * The lookup is a PSI-only heuristic: no resolution is performed, so it also answers while indices are unavailable,
 * and it accepts every JVM `main` shape the compiler does (`fun main()`, `fun main(args: Array<String>)`,
 * `fun main(vararg args: String)`, `Array<String>.main()`, `suspend` forms, and `@JvmStatic` mains in an object or a
 * companion). `@JvmName` on the function is honored.
 */
internal fun findKotlinMainOwner(file: KtFile, element: PsiElement): KtDeclarationContainer? {
  for (parent in element.parentsWithSelf) {
    if (parent is KtClassOrObject && parent.hasMainFunction()) return parent
  }

  if (file.hasMainFunction()) return file

  // the caret needs not sit inside the entrypoint declaration: the platform's own producers offer a file's `main`
  // from anywhere in that file, and an Elide entrypoint has to win the same locations
  return PsiTreeUtil.findChildrenOfType(file, KtClassOrObject::class.java).firstOrNull { it.hasMainFunction() }
}

/**
 * The JVM binary name of the class the entrypoint in [container] is compiled into: the file facade for a top-level
 * `main` (respecting `@file:JvmName`), and the class or object itself otherwise.
 */
internal fun kotlinMainClassJvmName(container: KtDeclarationContainer): String? = when (container) {
  is KtFile -> container.javaFileFacadeFqName.asString()
  is KtClassOrObject -> when {
    !container.isValid -> null
    // a `@JvmStatic main` in a companion is compiled into the class declaring the companion
    container is KtObjectDeclaration && container.isCompanion() ->
      container.getParentOfType<KtClass>(strict = true)?.jvmClassName()

    else -> container.jvmClassName()
  }

  else -> null
}

private fun KtClassOrObject.jvmClassName(): String? = toLightClass()?.let(ClassUtil::getJVMClassName)

private fun KtDeclarationContainer.hasMainFunction(): Boolean = when (this) {
  // an object literal has no name to run, so it can never hold an entrypoint
  is KtObjectDeclaration -> !isObjectLiteral() && declaresMainFunction()
  // for a class, only a `main` in its companion is reachable from the JVM class itself
  is KtClassOrObject -> companionObjects.any { it.hasMainFunction() }
  else -> declaresMainFunction()
}

private fun KtDeclarationContainer.declaresMainFunction(): Boolean {
  return declarations.any { it is KtNamedFunction && it.isMainFunction() }
}

private fun KtNamedFunction.isMainFunction(): Boolean {
  if (isLocal || typeParameters.isNotEmpty()) return false

  // an extension receiver takes the place of the `args` parameter
  when (valueParameters.size + if (receiverTypeReference != null) 1 else 0) {
    // the parameterless form is only an entrypoint at the top level
    0 -> if (!isTopLevel) return false
    1 -> if (!hasMainParameter()) return false
    else -> return false
  }

  if ((KotlinPsiHeuristics.findJvmName(this) ?: name) != MAIN_FUNCTION_NAME) return false

  // a member function is only reachable as an entrypoint when it is compiled into a static method
  if (!isTopLevel && !KotlinPsiHeuristics.hasJvmStaticAnnotation(this)) return false

  // an omitted return type is inferred; `main` returning anything but `Unit` is not an entrypoint
  val returnType = typeReference ?: return true
  return KotlinPsiHeuristics.typeMatches(returnType, StandardClassIds.Unit)
}

private fun KtNamedFunction.hasMainParameter(): Boolean {
  receiverTypeReference?.let {
    return KotlinPsiHeuristics.typeMatches(it, StandardClassIds.Array, StandardClassIds.String)
  }

  val parameter = valueParameters.singleOrNull() ?: return false
  val parameterType = parameter.typeReference ?: return false

  return when {
    parameter.isVarArg -> KotlinPsiHeuristics.typeMatches(parameterType, StandardClassIds.String)
    else -> KotlinPsiHeuristics.typeMatches(parameterType, StandardClassIds.Array, StandardClassIds.String)
  }
}

private const val MAIN_FUNCTION_NAME = "main"
