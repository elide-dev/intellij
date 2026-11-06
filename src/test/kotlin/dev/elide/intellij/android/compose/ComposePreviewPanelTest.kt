package dev.elide.intellij.android.compose

import com.intellij.openapi.application.EDT
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import java.awt.Component
import java.awt.Container
import java.awt.Point
import java.awt.image.BufferedImage
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.RepaintManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

@TestApplication
class ComposePreviewPanelTest {
  private val projectFixture = projectFixture()

  @Test fun `unchanged publications do not repaint or invalidate cards`() = withPanel { panel ->
    val model = card("one")
    panel.showCards(listOf(model))
    recordUpdates(panel) { updates ->
      panel.showCards(listOf(model.copy()))
      assertTrue(updates.painted.isEmpty())
      assertTrue(updates.invalidated.isEmpty())
    }
  }

  @Test fun `same size frames repaint only their existing canvas and retain inspector selection and scroll`() = withPanel { panel ->
    val first = card("one")
    val second = card("two")
    panel.showCards(listOf(first, second))
    val canvas = descendants(panel).single { it.name == "Compose preview: one" } as JComponent
    val picker = descendants(panel).filterIsInstance<JComboBox<*>>()
      .first { it.accessibleContext.accessibleName == "Layout component picker" }
    picker.selectedItem = "child"
    val pickerModel = picker.model
    val viewport = descendants(panel).filterIsInstance<JScrollPane>().single { it.viewport.view === canvas }.viewport
    viewport.setSize(40, 40)
    canvas.setSize(100, 100)
    viewport.viewPosition = Point(10, 15)
    val position = viewport.viewPosition
    recordUpdates(panel) { updates ->
      panel.showCards(listOf(first.copy(image = image()), second))
      assertEquals(setOf(canvas), updates.painted)
      assertTrue(updates.invalidated.isEmpty())
    }
    assertSame(canvas, descendants(panel).single { it.name == "Compose preview: one" })
    assertSame(pickerModel, picker.model)
    assertEquals("child", picker.selectedItem)
    assertEquals(position, viewport.viewPosition)
  }

  @Test fun `dimension layout and structural updates retain surviving components`() = withPanel { panel ->
    val first = card("one")
    val second = card("two")
    panel.showCards(listOf(first, second))
    val canvas = descendants(panel).single { it.name == "Compose preview: one" } as JComponent
    val picker = descendants(panel).filterIsInstance<JComboBox<*>>()
      .first { it.accessibleContext.accessibleName == "Layout component picker" }
    picker.selectedItem = "child"
    panel.showCards(listOf(second, first.copy(image = BufferedImage(80, 60, BufferedImage.TYPE_INT_ARGB),
      nodes = first.nodes.map { it.copy(width = 30) })))
    assertSame(canvas, descendants(panel).single { it.name == "Compose preview: one" })
    assertEquals(80, canvas.preferredSize.width)
    assertEquals(60, canvas.preferredSize.height)
    assertEquals("child", picker.selectedItem)
    panel.showCards(listOf(first.copy(nodes = emptyList())))
    assertEquals(null, picker.selectedItem)
    assertEquals(0, picker.itemCount)
    assertTrue(descendants(panel).none { it.name == "Compose preview: two" })
    assertSame(canvas, descendants(panel).single { it.name == "Compose preview: one" })
  }

  private fun withPanel(action: (ComposePreviewPanel) -> Unit) = runBlocking {
    withContext(Dispatchers.EDT) {
      val panel = ComposePreviewPanel(projectFixture.get(), object : ComposePreviewPanel.Callbacks {
        override fun refresh() = Unit
        override fun cancel() = Unit
        override fun applicationSelected(value: String) = Unit
        override fun autoRefreshChanged(enabled: Boolean) = Unit
        override fun navigate(location: ComposeLayoutSource) = Unit
        override fun pointer(cardId: String, action: String, x: Int, y: Int, deltaX: Double, deltaY: Double) = Unit
        override fun key(cardId: String, action: String, key: String, modifiers: Int) = Unit
        override fun focus(cardId: String?) = Unit
        override fun installKomodo() = Unit
      })
      try { action(panel) } finally { Disposer.dispose(panel) }
    }
  }

  private fun recordUpdates(panel: ComposePreviewPanel, action: (Updates) -> Unit) {
    val previous = RepaintManager.currentManager(panel)
    val updates = Updates(panel)
    RepaintManager.setCurrentManager(updates)
    try { action(updates) } finally { RepaintManager.setCurrentManager(previous) }
  }

  private class Updates(private val panel: ComposePreviewPanel) : RepaintManager() {
    val painted = linkedSetOf<JComponent>()
    val invalidated = linkedSetOf<JComponent>()
    override fun addDirtyRegion(component: JComponent, x: Int, y: Int, width: Int, height: Int) {
      if (component === panel || panel.isAncestorOf(component)) painted.add(component)
      super.addDirtyRegion(component, x, y, width, height)
    }
    override fun addInvalidComponent(component: JComponent) {
      if (component === panel || panel.isAncestorOf(component)) invalidated.add(component)
      super.addInvalidComponent(component)
    }
  }

  private fun descendants(component: Component): Sequence<Component> = sequence {
    yield(component)
    if (component is Container) component.components.forEach { yieldAll(descendants(it)) }
  }

  private fun image() = BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB)
  private fun card(id: String) = ComposeCard(id, id, "", image(), null,
    listOf(ComposeLayoutNode("child", "Child", 0, 0, 20, 20, null)))
}
