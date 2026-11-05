package dev.elide.intellij.psi

import com.intellij.codeInsight.highlighting.HighlightedReference
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.project.BaseProjectDirectories.Companion.getBaseDirectories
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import dev.elide.intellij.Constants
import dev.elide.intellij.authoring.ElideExtensionPaths
import org.pkl.intellij.psi.*

/** Reads only a literal element in the current editor's top-level extensions listing. */
internal fun extensionReferences(content: PklStringContent): Array<out PsiReference> {
  if (content.containingFile.originalFile.virtualFile?.name != Constants.MANIFEST_NAME) return PsiReference.EMPTY_ARRAY
  if (content.exprList.isNotEmpty() || !TrustedProjects.isProjectTrusted(content.project)) return PsiReference.EMPTY_ARRAY
  val literal = content.parent as? PklStringLiteral ?: return PsiReference.EMPTY_ARRAY
  val item = literal.parent as? PklObjectElement ?: return PsiReference.EMPTY_ARRAY
  val property = (item.parent as? PklObjectBody)?.parent as? PklClassProperty ?: return PsiReference.EMPTY_ARRAY
  if (property.parent !is PklModuleMemberList || !property.propertyName.textMatches("extensions")) return PsiReference.EMPTY_ARRAY
  val path = literal.content.escapedText() ?: return PsiReference.EMPTY_ARRAY
  val reference = ElideExtensionReference(content, path)
  return if (reference.resolve() != null) arrayOf(reference) else PsiReference.EMPTY_ARRAY
}

private class ElideExtensionReference(element: PklStringContent, private val path: String) :
  PsiReferenceBase<PklStringContent>(element, TextRange(0, element.textLength), true), HighlightedReference {
  override fun resolve(): PsiElement? {
    if (!TrustedProjects.isProjectTrusted(element.project)) return null
    val root = element.containingFile.originalFile.virtualFile?.parent ?: return null
    val workspace = element.containingFile.manifestWorkspace()?.root?.directory ?: return null
    val target = ElideExtensionPaths.resolve(path, root.toNioPath(), workspace.toNioPath(),
      element.project.getBaseDirectories().map { it.toNioPath() }) ?: return null
    val file = LocalFileSystem.getInstance().findFileByNioFile(target) ?: return null
    return PsiManager.getInstance(element.project).findFile(file)
  }
}

/** Task names are not always the artifact key: two packaging providers prepend their task family. */
internal fun PklObjectEntry.artifactBuildTarget(): String? {
  val key = keyExpr?.resolvedText() ?: return null
  val type = ((valueExpr as? PklNewExpr)?.type as? PklDeclaredType)?.typeName?.simpleName?.identifier?.text
  return when (type) {
    "ExtensionArchive" -> "package-extension-$key"
    "AndroidFeature" -> "android-module-$key"
    else -> key
  }
}
