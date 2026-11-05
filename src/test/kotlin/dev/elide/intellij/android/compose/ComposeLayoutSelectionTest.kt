package dev.elide.intellij.android.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ComposeLayoutSelectionTest {
  @Test fun `coincident wrappers select the enclosing source without swallowing smaller children`() {
    val internal = ComposeLayoutSource("Layout.kt", -1, 126, 0, 0)
    val application = ComposeLayoutSource("Screen.kt", -1, 23, 0, 0)
    val nodes = listOf(
      ComposeLayoutNode("inner", "ReusableLayoutElement", 10, 10, 80, 20, internal),
      ComposeLayoutNode("layout", "Layout", 10, 10, 80, 20, internal),
      ComposeLayoutNode("text", "BasicText", 10, 10, 80, 20, application),
      ComposeLayoutNode("anonymous", "Group", 10, 10, 80, 20, null),
      ComposeLayoutNode("column", "Column", 0, 0, 100, 100, application),
    )
    assertEquals("text", hitComposeLayout(nodes, 20, 15)?.id)
    assertEquals(application, hitComposeLayout(nodes, 20, 15)?.source)
    assertEquals("column", hitComposeLayout(nodes, 95, 15)?.id)
    assertEquals("column", hitComposeLayout(nodes, 90, 15)?.id)
    assertNull(hitComposeLayout(nodes, 100, 100))
    assertEquals("inner", hitComposeLayout(nodes.take(1), 20, 15)?.id)
  }
}
