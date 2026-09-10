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
import com.intellij.psi.util.PsiTreeUtil
import org.pkl.intellij.psi.*

/**
 * Returns the [PklStringLiteral] for which this element is the raw content; if not null, the returned element will be
 * this element's grandfather.
 */
inline val PsiElement.parentStringLiteral: PsiElement?
  get() = parent?.takeIf { it is PklStringContent }?.parent?.takeIf { it is PklStringLiteral }

/**
 * Returns the [PklUnqualifiedAccessExpr] for which this element is the raw content; if not null, the returned element
 * will be this element's grandfather. Only references that resolve to [PklProperty] are accepted.
 */
inline val PsiElement.parentPropertyReference: PsiElement?
  get() = parent?.takeIf { it is PklUnqualifiedAccessName }
    ?.parent?.takeIf { it is PklUnqualifiedAccessExpr }
    .takeIf { parent?.reference?.resolve() is PklProperty }

/**
 * Returns the mapping entry this element is the key of, when that mapping is the value of the module-level property
 * named [property].
 *
 * Only the mapping's own entries qualify. A nested mapping is rejected even though it sits under the same
 * module-level property: the `resources` of an artifact declaration are keyed by file path, not by artifact name, and
 * a caret there names nothing the CLI can be asked to build.
 */
fun PsiElement.moduleMappingKey(property: String): PklObjectEntry? {
  val anchor = parentStringLiteral ?: parentPropertyReference ?: return null

  val entry = anchor.parent as? PklObjectEntry ?: return null
  if (entry.keyExpr != anchor) return null

  val owner = (entry.parent as? PklObjectBody)?.parent as? PklClassProperty ?: return null
  if (!owner.propertyName.textMatches(property)) return null
  if (owner.parent !is PklModuleMemberList) return null

  return entry
}

/**
 * Returns whether the manifest containing this element declares at least one explicit `entrypoint`.
 *
 * The CLI resolves the manifest's `entrypoint` before falling back to `jvm.main`, so a JVM main class declared
 * alongside one is not what a bare `elide run` starts.
 */
val PsiElement.manifestDeclaresEntrypoint: Boolean
  get() {
    val file = containingFile ?: return false

    return PsiTreeUtil.findChildrenOfType(file, PklClassProperty::class.java).any { property ->
      property.parent is PklModuleMemberList &&
        property.propertyName.textMatches("entrypoint") &&
        PsiTreeUtil.findChildrenOfType(property, PklObjectElement::class.java).isNotEmpty()
    }
  }
