package dev.elide.intellij.android.compose

import java.awt.image.BufferedImage

internal data class ComposeLayoutSource(val fileName: String, val packageHash: Int, val lineNumber: Int, val offset: Int, val length: Int, val path: String? = null) {
  fun matchesPackage(text: String): Boolean {
    if (packageHash == -1) return true
    val name = PACKAGE.find(text)?.groupValues?.get(1)?.replace("`", "").orEmpty()
    // Compose source information uses Kotlin abs; Int.MIN_VALUE deliberately stays negative.
    return kotlin.math.abs(name.hashCode()) == packageHash
  }
  private companion object {
    val PACKAGE = Regex("(?m)^\\s*package\\s+([\\p{L}\\p{N}_.`]+)")
  }
}
internal data class ComposeLayoutNode(val id: String, val name: String, val x: Int, val y: Int, val width: Int, val height: Int, val source: ComposeLayoutSource?, val parentId: String? = null)
internal data class ComposeCard(val id: String, val name: String, val configuration: String, val image: BufferedImage?, val error: String?, val nodes: List<ComposeLayoutNode>, val live: Boolean = false, val keyboard: Boolean = false, val layoutError: String? = null, val scroll: Boolean = false)

/** Komodo emits children first; co-located wrappers should resolve to their enclosing composable call site. */
internal fun hitComposeLayout(nodes: List<ComposeLayoutNode>, x: Int, y: Int): ComposeLayoutNode? {
  var hit: ComposeLayoutNode? = null
  var smallest = Long.MAX_VALUE
  for (node in nodes) {
    if (node.width <= 0 || node.height <= 0 || x < node.x || y < node.y ||
      x.toLong() >= node.x.toLong() + node.width || y.toLong() >= node.y.toLong() + node.height) continue
    val area = node.width.toLong() * node.height
    val previous = hit
    val sameBounds = previous != null && node.x == previous.x && node.y == previous.y &&
      node.width == previous.width && node.height == previous.height
    if (area < smallest || sameBounds && node.source != null) {
      hit = node
      smallest = area
    }
  }
  return hit
}
