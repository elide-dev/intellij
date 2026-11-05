package dev.elide.intellij.android.compose

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiElement
import dev.elide.intellij.service.elideProjectIndex

/** Uses Kotlin's parsed annotation ancestry without binding to its compiler/analysis implementation. */
class ComposePreviewLineMarker : LineMarkerProvider {
  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
    if (element.firstChild != null || !element.text.contains("Preview") || element.textLength > 128) return null
    val file = element.containingFile?.virtualFile ?: return null
    if (file.extension != "kt" || element.project.elideProjectIndex.entries.none { it.value.android != null }) return null
    val annotation = generateSequence(element.parent) { it.parent }.take(5).firstOrNull {
      it.javaClass.simpleName == "KtAnnotationEntry"
    } ?: return null
    if (!annotation.text.startsWith('@')) return null
    return LineMarkerInfo(element, element.textRange, AllIcons.Actions.Preview,
      { "Render live Compose previews with Komodo" },
      { _, target ->
        target.project.service<ComposePreviewService>().open(file)
        ToolWindowManager.getInstance(target.project).getToolWindow(COMPOSE_TOOL_WINDOW)?.activate(null)
      }, GutterIconRenderer.Alignment.RIGHT, { "Compose Preview" })
  }
}
