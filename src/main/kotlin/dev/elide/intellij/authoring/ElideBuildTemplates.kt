package dev.elide.intellij.authoring

import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.codeInsight.template.TemplateContextType
import com.intellij.codeInsight.template.impl.DefaultLiveTemplatesProvider
import com.intellij.openapi.project.BaseProjectDirectories.Companion.getBaseDirectories
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiFile
import dev.elide.intellij.Constants

internal fun PsiFile.isElideAuthoringFile(): Boolean {
  val file = originalFile.virtualFile ?: return false
  val roots = project.getBaseDirectories()
  var directory = file.parent
  while (directory != null) {
    val current = directory
    if (roots.none { VfsUtilCore.isAncestor(it, current, false) }) break
    if (current.findChild(Constants.MANIFEST_NAME) != null) return true
    directory = current.parent
  }
  return false
}

class ElideTypeScriptTemplateContext : TemplateContextType("Elide TypeScript build extensions") {
  override fun isInContext(context: TemplateActionContext): Boolean =
    context.file.virtualFile?.extension in setOf("ts", "mts", "cts", "tsx") && context.file.isElideAuthoringFile()
}

class ElideJavaScriptTemplateContext : TemplateContextType("Elide JavaScript build extensions") {
  override fun isInContext(context: TemplateActionContext): Boolean =
    context.file.virtualFile?.extension in setOf("js", "mjs", "cjs", "jsx") && context.file.isElideAuthoringFile()
}

class ElidePklTemplateContext : TemplateContextType("Elide manifest") {
  override fun isInContext(context: TemplateActionContext): Boolean =
    context.file.name == Constants.MANIFEST_NAME && context.file.language.id.equals("pkl", ignoreCase = true)
}

class ElideJavaScriptLiveTemplates : DefaultLiveTemplatesProvider {
  override fun getDefaultLiveTemplateFiles(): Array<String> = arrayOf("liveTemplates/ElideJavaScript", "liveTemplates/ElideTypeScript")
  override fun getHiddenLiveTemplateFiles(): Array<String> = emptyArray()
}

class ElidePklLiveTemplates : DefaultLiveTemplatesProvider {
  override fun getDefaultLiveTemplateFiles(): Array<String> = arrayOf("liveTemplates/ElidePkl")
  override fun getHiddenLiveTemplateFiles(): Array<String> = emptyArray()
}
