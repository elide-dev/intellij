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
 * Resolves this expression to the text it stands for: the value of the property a bare reference names, or the
 * expression's own text, with the quotes of a string literal removed.
 */
fun PklExpr.resolvedText(): String {
  return ((this as? PklUnqualifiedAccessExpr)?.memberName
    ?.reference?.resolve()
    ?.let { (it as? PklProperty)?.expr?.resolvedText() } ?: text)
    .trim('"')
}

/**
 * A Native Image artifact producing a runnable binary, as declared in a manifest.
 *
 * @param artifact Key the artifact is declared under, which is what `elide build` takes as a target.
 * @param outputName The `name` the declaration carries, or `null` when it names none.
 */
data class ManifestNativeImage(val artifact: String, val outputName: String?)

/**
 * Returns the binary Native Image this `artifacts` entry declares, or `null` for anything else: another kind of
 * artifact, a library image, or a value that is not a declaration at all.
 *
 * The type is matched on its simple name, so both the qualified `new NativeImage.NativeImage {}` and an import that
 * makes `new NativeImage {}` legal are recognised — the same rule the VS Code extension applies.
 */
fun PklObjectEntry.nativeImage(): ManifestNativeImage? {
  val declaration = valueExpr as? PklNewExpr ?: return null
  val typeName = (declaration.type as? PklDeclaredType)?.typeName?.simpleName?.identifier?.text ?: return null
  if (typeName != NATIVE_IMAGE_TYPE) return null

  val body = declaration.objectBody
  // a library image is a shared object, which is nothing the IDE can launch
  if (body?.getConstantStringProperty("type") == LIBRARY_IMAGE_TYPE) return null

  val artifact = keyExpr?.resolvedText() ?: return null

  return ManifestNativeImage(artifact = artifact, outputName = body?.getConstantStringProperty("name"))
}

/** Simple name of the manifest class declaring a Native Image artifact. */
private const val NATIVE_IMAGE_TYPE = "NativeImage"

/** Value of a Native Image's `type` marking it as a library rather than an executable. */
private const val LIBRARY_IMAGE_TYPE = "library"

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
