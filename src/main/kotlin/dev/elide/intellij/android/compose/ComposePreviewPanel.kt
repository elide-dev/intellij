package dev.elide.intellij.android.compose

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.LayoutManager
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.HierarchyEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTextArea
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/** Native preview tooling. Frames replace card data, never the focused Swing component. */
internal class ComposePreviewPanel(project: Project, private val callbacks: Callbacks) : JPanel(BorderLayout()), Disposable {
  interface Callbacks {
    fun refresh()
    fun cancel()
    fun applicationSelected(value: String)
    fun autoRefreshChanged(enabled: Boolean)
    fun navigate(location: ComposeLayoutSource)
    fun pointer(cardId: String, action: String, x: Int, y: Int, deltaX: Double, deltaY: Double)
    fun key(cardId: String, action: String, key: String, modifiers: Int)
    fun focus(cardId: String?)
    fun installKomodo()
  }

  private var busy = false
  private var stale = false
  private var failed = false
  private var disposed = false
  private var fitting = true
  private var inspecting = false
  private var autoRefresh = true
  private var logsVisible = false
  private var updatingApplications = false
  private var inputCard: CardView? = null
  private val heldKeys = mutableMapOf<Int, String>()
  private val cards = linkedMapOf<String, CardView>()
  private val applications = JComboBox<String>()
  private val status = JLabel("Open a Kotlin file and choose Compose Preview.")
  private val state = JLabel()
  private val install = JButton("Set up Komodo…")
  private val emptyMessage = textArea("Open a Kotlin file containing @Preview composables, then choose Compose Preview.", 3)
  private val emptyRefresh = JButton("Refresh previews").apply { addActionListener { refresh() } }
  private val empty = JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(8))).apply {
    border = JBUI.Borders.empty(24)
    add(JLabel("Compose previews", SwingConstants.CENTER), BorderLayout.NORTH)
    add(emptyMessage, BorderLayout.CENTER)
    add(JPanel().apply { add(emptyRefresh) }, BorderLayout.SOUTH)
  }
  private val gallery = Gallery()
  private val galleryScroll = JBScrollPane(gallery).apply {
    border = JBUI.Borders.empty()
    horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
    verticalScrollBar.unitIncrement = JBUI.scale(24)
    accessibleContext.accessibleName = "Compose preview gallery"
  }
  private val content = JPanel(BorderLayout()).apply { add(empty, BorderLayout.CENTER) }
  private val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
  private val logPanel = JPanel(BorderLayout())
  private val split = JSplitPane(JSplitPane.VERTICAL_SPLIT, content, null).apply {
    border = JBUI.Borders.empty()
    resizeWeight = 0.75
    isContinuousLayout = true
    dividerSize = 0
  }
  private val keyDispatcher = KeyEventDispatcher { event -> dispatchKey(event) }

  init {
    Disposer.register(this, console)
    minimumSize = Dimension(0, 0)
    isFocusable = true
    getAccessibleContext().accessibleName = "Compose previews"
    val actions = DefaultActionGroup().apply {
      add(action("Refresh previews", "Rebuild previews with the current editor contents", AllIcons.Actions.Refresh, { !busy }) { refresh() })
      add(action("Cancel preview build", "Cancel the build and keep the last rendered images", AllIcons.Actions.Cancel, { busy }) {
        setStatus("Preview build cancelled", stale = cards.isNotEmpty())
        callbacks.cancel()
      })
      addSeparator()
      add(toggle("Auto refresh", "Rebuild previews automatically when relevant sources change", AllIcons.Actions.RerunAutomatically, { autoRefresh }) {
        autoRefresh = it
        callbacks.autoRefreshChanged(it)
      })
      add(toggle("Fit previews", "Fit images to card width; turn off for actual pixel size", AllIcons.Actions.Expandall, { fitting }) {
        fitting = it
        cards.values.forEach { card -> card.resizeImage() }
        gallery.revalidate()
      })
      add(toggle("Inspect layout", "Inspect measured component bounds; arrows select, Alt+Up selects parent, Enter opens source", AllIcons.Actions.Preview, { inspecting }) {
        inspecting = it
        releaseInput()
        cards.values.forEach { card -> card.updateTools() }
        gallery.revalidate()
      })
      add(toggle("Preview logs", "Show or hide retained Komodo build and runtime output", AllIcons.Debugger.Console, { logsVisible }, ::showLogs))
    }
    val toolbar = ActionManager.getInstance().createActionToolbar("ElideComposePreview", actions, true).apply {
      targetComponent = this@ComposePreviewPanel
      setLayoutPolicy(ActionToolbar.AUTO_LAYOUT_POLICY)
      component.accessibleContext.accessibleName = "Compose preview controls"
    }
    applications.apply {
      minimumSize = Dimension(0, preferredSize.height)
      prototypeDisplayValue = "Android application"
      toolTipText = "Android application whose resources and dependencies are used for previews"
      accessibleContext.accessibleName = "Preview application"
      addActionListener {
        if (!updatingApplications) (selectedItem as? String)?.let {
          setStatus("Switching preview application", busy = true, stale = cards.isNotEmpty())
          callbacks.applicationSelected(it)
        }
      }
    }
    val applicationRow = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
      border = JBUI.Borders.empty(2, 8)
      add(JLabel("Application:").apply { labelFor = applications }, BorderLayout.WEST)
      add(applications, BorderLayout.CENTER)
    }
    val statusRow = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
      border = JBUI.Borders.empty(4, 8)
      add(state, BorderLayout.WEST)
      add(status, BorderLayout.CENTER)
    }
    install.apply {
      isVisible = false
      toolTipText = "Open Komodo setup instructions or its independent installation location"
      addActionListener { callbacks.installKomodo() }
    }
    val header = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      add(toolbar.component)
      add(applicationRow)
      add(statusRow)
      add(JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(0, 8, 4, 8)
        add(install, BorderLayout.CENTER)
      })
    }
    val consoleActions = DefaultActionGroup().apply {
      console.createConsoleActions().forEach(::add)
      addSeparator()
      add(ActionManager.getInstance().getAction(IdeActions.ACTION_COPY))
    }
    val consoleToolbar = ActionManager.getInstance().createActionToolbar("ElideComposePreviewConsole", consoleActions, true).apply {
      targetComponent = console.component
    }
    logPanel.add(JPanel(BorderLayout()).apply {
      add(JLabel("Preview output").apply { border = JBUI.Borders.emptyLeft(8) }, BorderLayout.WEST)
      add(consoleToolbar.component, BorderLayout.CENTER)
    }, BorderLayout.NORTH)
    logPanel.add(console.component, BorderLayout.CENTER)
    logPanel.minimumSize = Dimension(0, JBUI.scale(80))
    console.component.accessibleContext?.accessibleName = "Komodo preview output"
    add(header, BorderLayout.NORTH)
    add(split, BorderLayout.CENTER)
    KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keyDispatcher)
    addHierarchyListener {
      if (it.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && !isShowing) releaseInput()
    }
  }

  fun setApplications(values: List<String>, selected: String) {
    updatingApplications = true
    try {
      if (applications.itemCount != values.size || values.indices.any { applications.getItemAt(it) != values[it] }) {
        applications.model = DefaultComboBoxModel(values.toTypedArray())
      }
      applications.selectedItem = selected
      applications.isEnabled = values.size > 1
      applications.toolTipText = selected.ifBlank { "No owning Android application found" }
    } finally {
      updatingApplications = false
    }
  }

  fun setStatus(text: String, busy: Boolean = false, stale: Boolean = false, failed: Boolean = false) {
    val toolsChanged = this.busy != busy || this.stale != stale || this.failed != failed
    this.busy = busy
    this.stale = stale
    this.failed = failed
    if (busy || stale || failed) releaseInput()
    state.text = when { failed -> "Error"; busy -> "Building…"; stale -> "Stale"; else -> "Ready" }
    state.foreground = if (failed) JBColor.RED else UIUtil.getLabelForeground()
    status.text = if (stale && !text.contains("stale", ignoreCase = true)) "$text — last rendered images are stale" else text
    status.toolTipText = status.text
    status.accessibleContext.accessibleDescription = status.text
    emptyRefresh.isEnabled = !busy
    emptyMessage.text = when {
      install.isVisible -> "Komodo is not available. Expected executable: ~/.local/share/komodo/komodo. Set up its independent installation, then refresh."
      failed -> "Previews could not be built. Open Preview logs for diagnostics, fix the reported problem, then refresh."
      busy -> "Preparing previews. You can continue editing while Komodo builds and renders."
      else -> "No previews to display. Open a Kotlin file with @Preview composables, select its Android application, and refresh."
    }
    if (toolsChanged) cards.values.forEach { it.updateTools() }
    if (failed) showLogs(true)
  }

  fun showCards(values: List<ComposeCard>) {
    // Runtime frames normally keep the same card order; avoid touching the gallery hierarchy.
    if (gallery.componentCount == values.size && values.indices.all {
        (gallery.getComponent(it) as CardView).model.id == values[it].id
      }) {
      values.forEachIndexed { index, model -> (gallery.getComponent(index) as CardView).update(model) }
      return
    }
    val ids = values.mapTo(hashSetOf()) { it.id }
    val iterator = cards.iterator()
    var structureChanged = false
    while (iterator.hasNext()) {
      val (id, card) = iterator.next()
      if (id !in ids) {
        if (inputCard === card) releaseInput()
        if (card.isAncestorOf(KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner)) requestFocusInWindow()
        gallery.remove(card)
        iterator.remove()
        structureChanged = true
      }
    }
    values.forEachIndexed { index, model ->
      val card = cards[model.id] ?: CardView(model).also {
        cards[model.id] = it
        gallery.add(it)
        structureChanged = true
      }
      if (gallery.getComponentZOrder(card) != index) {
        gallery.setComponentZOrder(card, index)
        structureChanged = true
      }
      card.update(model)
    }
    val wanted = if (cards.isEmpty()) empty else galleryScroll
    if (wanted.parent !== content) {
      content.removeAll()
      content.add(wanted, BorderLayout.CENTER)
      content.revalidate()
      content.repaint()
    }
    if (structureChanged) { gallery.revalidate(); gallery.repaint() }
  }

  fun appendLog(text: String, error: Boolean = false) {
    console.print(text, if (error) ConsoleViewContentType.ERROR_OUTPUT else ConsoleViewContentType.NORMAL_OUTPUT)
    if (error) showLogs(true)
  }

  fun showInstallAction(visible: Boolean) {
    install.isVisible = visible
    if (visible) emptyMessage.text = "Komodo is not available. Expected executable: ~/.local/share/komodo/komodo. Use Set up Komodo to locate or install it independently, then refresh."
    revalidate()
  }

  /** Clear source-specific cards, not the console's useful build history. */
  fun clear() {
    releaseInput()
    showCards(emptyList())
  }

  override fun dispose() {
    disposed = true
    releaseInput()
    KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(keyDispatcher)
  }

  private fun refresh() {
    setStatus("Refreshing previews", busy = true, stale = cards.isNotEmpty())
    callbacks.refresh()
  }

  private fun showLogs(visible: Boolean) {
    if (logsVisible == visible) return
    logsVisible = visible
    if (!visible && logPanel.isAncestorOf(KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner)) requestFocusInWindow()
    split.bottomComponent = if (visible) logPanel else null
    split.dividerSize = if (visible) JBUI.scale(6) else 0
    if (visible) split.setDividerLocation(0.72)
    split.revalidate()
    split.repaint()
  }

  private fun releaseInput() {
    heldKeys.clear()
    val previous = inputCard
    inputCard = null
    previous?.pressed = false
    if (previous != null) callbacks.focus(null)
  }

  private fun focusInput(card: CardView) {
    if (!card.acceptsInput() || disposed || inputCard === card) return
    releaseInput()
    inputCard = card
    callbacks.focus(card.model.id)
  }

  private fun dispatchKey(event: KeyEvent): Boolean {
    if (disposed) return false
    val focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
    val card = cards.values.firstOrNull { it.canvas === focused } ?: return false
    if (event.keyCode == KeyEvent.VK_F6 && !event.isAltDown && !event.isControlDown && !event.isMetaDown) {
      if (event.id == KeyEvent.KEY_PRESSED) {
        releaseInput()
        // A single-card gallery may have no other enabled focus target; never cycle back into capture.
        this@ComposePreviewPanel.requestFocusInWindow()
      }
      event.consume()
      return true
    }
    if (inspecting) return event.id == KeyEvent.KEY_PRESSED && card.inspectKey(event)
    if (inputCard !== card || !card.acceptsInput() || !card.model.keyboard) return false
    if (event.id == KeyEvent.KEY_TYPED) { event.consume(); return true }
    val code = event.keyCode * 8 + event.keyLocation
    val key = when (event.id) {
      KeyEvent.KEY_PRESSED -> heldKeys[code] ?: browserKey(event)?.also { heldKeys[code] = it }
      KeyEvent.KEY_RELEASED -> heldKeys.remove(code)
      else -> null
    } ?: return false
    callbacks.key(card.model.id, if (event.id == KeyEvent.KEY_PRESSED) "down" else "up", key, modifiers(event))
    event.consume()
    return true
  }

  private fun action(text: String, description: String, icon: Icon, enabled: () -> Boolean, perform: () -> Unit): AnAction =
    object : AnAction(text, description, icon) {
      override fun getActionUpdateThread() = ActionUpdateThread.EDT
      override fun update(e: AnActionEvent) { e.presentation.isEnabled = !disposed && enabled() }
      override fun actionPerformed(e: AnActionEvent) { perform() }
    }

  private fun toggle(text: String, description: String, icon: Icon, selected: () -> Boolean, change: (Boolean) -> Unit): ToggleAction =
    object : ToggleAction(text, description, icon) {
      override fun getActionUpdateThread() = ActionUpdateThread.EDT
      override fun isSelected(e: AnActionEvent) = selected()
      override fun setSelected(e: AnActionEvent, state: Boolean) { change(state) }
    }

  private inner class CardView(var model: ComposeCard) : JPanel(BorderLayout()) {
    private val title = JLabel()
    private val configuration = JLabel()
    private val availability = JLabel()
    private val error = textArea("", 2)
    private val component = JLabel("Select a component")
    private val dimensions = JLabel()
    private val source = JLabel()
    private val picker = JComboBox<String>()
    private val parentButton = JButton("Parent")
    private val sourceButton = JButton("Open source")
    private val inspector = JPanel()
    private var nodesById = emptyMap<String, ComposeLayoutNode>()
    private var initialized = false
    private var selectedId: String? = null
    private var hoveredId: String? = null
    private var updatingPicker = false
    private var availableWidth = JBUI.scale(320)
    private var imageWidth = 0
    private var imageHeight = 0
    var pressed = false
    val canvas = PreviewCanvas()
    private val viewport = JBScrollPane(canvas).apply {
      border = JBUI.Borders.empty()
      verticalScrollBar.unitIncrement = JBUI.scale(20)
      horizontalScrollBar.unitIncrement = JBUI.scale(20)
      isWheelScrollingEnabled = false
    }

    init {
      minimumSize = Dimension(0, 0)
      border = BorderFactory.createCompoundBorder(JBUI.Borders.customLine(JBColor.border()), JBUI.Borders.empty(8))
      val heading = JPanel(GridLayout(0, 1, 0, JBUI.scale(3))).apply {
        add(title)
        add(configuration)
        add(availability)
        border = JBUI.Borders.emptyBottom(8)
      }
      title.font = title.font.deriveFont(java.awt.Font.BOLD)
      configuration.foreground = UIUtil.getContextHelpForeground()
      error.foreground = JBColor.RED
      error.isVisible = false
      picker.apply {
        minimumSize = Dimension(0, preferredSize.height)
        prototypeDisplayValue = "Choose a component"
        accessibleContext.accessibleName = "Layout component picker"
        toolTipText = "Choose a measured layout component; indentation shows enclosing components"
        renderer = object : DefaultListCellRenderer() {
          override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, selected: Boolean, focused: Boolean): Component {
            val label = super.getListCellRendererComponent(list, value, index, selected, focused) as JLabel
            val node = (value as? String)?.let(nodesById::get)
            label.text = node?.let { "${"  ".repeat(depth(it))}${it.name} — ${it.width} × ${it.height}" } ?: "Choose a component"
            return label
          }
        }
        addActionListener { if (!updatingPicker) select(selectedItem as? String) }
      }
      parentButton.apply {
        toolTipText = "Select the enclosing component (Alt+Up in preview)"
        addActionListener { select(selectedNode()?.let(::parentNode)?.id) }
      }
      sourceButton.apply {
        toolTipText = "Open selected component source (Enter in preview)"
        addActionListener { navigate() }
      }
      inspector.apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.emptyTop(8)
        add(component)
        add(dimensions)
        add(source)
        add(JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
          border = JBUI.Borders.emptyTop(6)
          add(picker, BorderLayout.CENTER)
          add(parentButton, BorderLayout.EAST)
        })
        add(JPanel(BorderLayout()).apply { add(sourceButton, BorderLayout.WEST) })
      }
      add(heading, BorderLayout.NORTH)
      add(viewport, BorderLayout.CENTER)
      add(JPanel(BorderLayout()).apply {
        add(error, BorderLayout.NORTH)
        add(inspector, BorderLayout.CENTER)
      }, BorderLayout.SOUTH)
      viewport.addMouseWheelListener { event ->
        val point = SwingUtilities.convertPoint(viewport, event.point, canvas)
        wheel(event, point)
      }
      update(model)
    }

    fun update(value: ComposeCard) {
      if (initialized && model == value) return
      val previous = model
      val firstUpdate = !initialized
      val nodesChanged = firstUpdate || previous.nodes != value.nodes
      val toolsChanged = firstUpdate || nodesChanged || previous.name != value.name ||
        previous.error != value.error || previous.layoutError != value.layoutError ||
        previous.live != value.live || previous.keyboard != value.keyboard ||
        (previous.image == null) != (value.image == null)
      model = value
      initialized = true
      if (inputCard === this && (!acceptsInput() || previous.keyboard && !value.keyboard)) releaseInput()
      if (firstUpdate || previous.name != value.name) {
        title.text = value.name
        title.toolTipText = value.name
      }
      if (firstUpdate || previous.configuration != value.configuration) {
        configuration.text = value.configuration.replace('\n', ' ')
        configuration.toolTipText = value.configuration
      }
      val message = if (firstUpdate || previous.error != value.error) {
        value.error?.let { "${it.lineSequence().first().take(300)}\nSee Preview logs for full diagnostics." }.orEmpty()
      } else error.text
      if (error.text != message) {
        error.text = message
        error.isVisible = message.isNotEmpty()
        if (message.isNotEmpty()) appendLog("\n${value.name}: ${value.error}\n", error = true)
        revalidate()
      }
      if (nodesChanged) installNodes()
      val resized = firstUpdate || previous.image?.width != value.image?.width || previous.image?.height != value.image?.height
      if (resized) resizeImage()
      if (toolsChanged) updateTools()
      else if (previous.image !== value.image) canvas.repaint()
    }

    private fun installNodes() {
      nodesById = model.nodes.associateBy { it.id }
      if (selectedId !in nodesById) selectedId = null
      if (hoveredId !in nodesById) hoveredId = null
      updatingPicker = true
      try {
        if (picker.itemCount != model.nodes.size || model.nodes.indices.any { picker.getItemAt(it) != model.nodes[it].id }) {
          picker.model = DefaultComboBoxModel(model.nodes.map { it.id }.toTypedArray())
        }
        picker.selectedItem = selectedId
        picker.repaint()
      } finally { updatingPicker = false }
    }

    fun acceptsInput() = !busy && !stale && !failed && !inspecting && model.live && model.image != null && model.error == null

    fun updateTools() {
      val selected = selectedNode()
      val hovered = hoveredId?.let(nodesById::get)
      val node = selected ?: hovered
      val fresh = !busy && !stale && !failed && model.error == null
      availability.text = when {
        model.error != null -> "Render error — retained image is not interactive"
        !fresh -> "Last rendered image — input paused"
        model.live && model.keyboard -> "Live · F6 leaves preview keyboard input"
        model.live -> "Live · pointer only; runtime keyboard input unavailable"
        else -> "Rendered image · not interactive"
      }
      availability.toolTipText = availability.text
      inspector.isVisible = inspecting
      component.text = node?.name ?: if (model.nodes.isEmpty()) "No layout information" else "Select a component"
      component.toolTipText = component.text
      dimensions.text = node?.let { "${it.width} × ${it.height} px · x ${it.x}, y ${it.y}" }
        ?: model.layoutError ?: "${model.nodes.size} components · click an outline"
      dimensions.toolTipText = dimensions.text
      source.text = node?.source?.let { "${it.path ?: it.fileName}:${it.lineNumber}" } ?: "No source location available"
      source.toolTipText = source.text
      parentButton.isEnabled = selected?.let(::parentNode) != null
      sourceButton.isEnabled = fresh && selected?.source != null
      picker.isEnabled = model.nodes.isNotEmpty()
      canvas.isFocusable = inspecting || acceptsInput()
      canvas.focusTraversalKeysEnabled = inspecting || !model.keyboard || !acceptsInput()
      canvas.cursor = Cursor.getPredefinedCursor(if (inspecting) Cursor.CROSSHAIR_CURSOR else Cursor.DEFAULT_CURSOR)
      canvas.name = "Compose preview: ${model.name}"
      canvas.getAccessibleContext().accessibleName = "${model.name}: ${if (inspecting) "layout inspector" else availability.text}"
      canvas.getAccessibleContext().accessibleDescription = if (inspecting) {
        "Arrows select components, Home and End select first and last, Alt+Up selects parent, Enter opens source, Escape clears selection. ${node?.name.orEmpty()}"
      } else "Click or Tab here to interact. Tab moves between Compose controls; F6 or Shift+F6 leaves the preview. IME composition and clipboard paste are not supported."
      canvas.toolTipText = canvas.getAccessibleContext().accessibleDescription
      canvas.repaint()
    }

    fun widthChanged(width: Int) {
      val next = (width - insets.left - insets.right).coerceAtLeast(1)
      if (availableWidth != next) { availableWidth = next; resizeImage() }
    }

    fun resizeImage() {
      val image = model.image
      val scale = if (image != null && fitting) minOf(1.0, (availableWidth - JBUI.scale(18)).coerceAtLeast(1).toDouble() / image.width, JBUI.scale(420).toDouble() / image.height) else 1.0
      imageWidth = image?.let { (it.width * scale).toInt().coerceAtLeast(1) } ?: 0
      imageHeight = image?.let { (it.height * scale).toInt().coerceAtLeast(1) } ?: 0
      canvas.preferredSize = if (image == null) Dimension(JBUI.scale(160), JBUI.scale(160)) else Dimension(imageWidth, imageHeight)
      viewport.preferredSize = Dimension(availableWidth, if (image == null) JBUI.scale(180) else minOf(imageHeight + JBUI.scale(20), JBUI.scale(460)))
      canvas.revalidate()
      revalidate()
    }

    private fun selectedNode() = selectedId?.let(nodesById::get)

    private fun parentNode(node: ComposeLayoutNode): ComposeLayoutNode? {
      node.parentId?.let { id -> nodesById[id]?.takeIf { it.id != node.id }?.let { return it } }
      val index = model.nodes.indexOf(node)
      var parent: ComposeLayoutNode? = null
      var parentArea = Long.MAX_VALUE
      val nodeArea = area(node)
      model.nodes.forEachIndexed { candidateIndex, candidate ->
        val candidateArea = area(candidate)
        if (candidate.id != node.id && candidateArea < parentArea &&
          candidate.x <= node.x && candidate.y <= node.y &&
          candidate.x.toLong() + candidate.width >= node.x.toLong() + node.width &&
          candidate.y.toLong() + candidate.height >= node.y.toLong() + node.height &&
          (candidateArea > nodeArea || candidateArea == nodeArea && candidateIndex > index)) {
          parent = candidate
          parentArea = candidateArea
        }
      }
      return parent
    }

    private fun depth(node: ComposeLayoutNode): Int {
      var current = node
      val seen = hashSetOf(node.id)
      var depth = 0
      while (depth < 12) {
        current = current.parentId?.let(nodesById::get) ?: break
        if (!seen.add(current.id)) break
        depth++
      }
      return depth
    }

    private fun select(id: String?) {
      selectedId = id?.takeIf(nodesById::containsKey)
      hoveredId = null
      updatingPicker = true
      try { picker.selectedItem = selectedId } finally { updatingPicker = false }
      updateTools()
    }

    private fun navigate() {
      if (busy || stale || failed || model.error != null) return
      selectedNode()?.source?.let(callbacks::navigate)
    }

    fun inspectKey(event: KeyEvent): Boolean {
      if (model.nodes.isEmpty()) return false
      val index = selectedNode()?.let(model.nodes::indexOf) ?: -1
      when {
        event.keyCode == KeyEvent.VK_ESCAPE -> select(null)
        event.keyCode == KeyEvent.VK_ENTER || event.keyCode == KeyEvent.VK_SPACE -> navigate()
        event.isAltDown && event.keyCode == KeyEvent.VK_UP -> select(selectedNode()?.let(::parentNode)?.id)
        event.keyCode == KeyEvent.VK_HOME -> select(model.nodes.first().id)
        event.keyCode == KeyEvent.VK_END -> select(model.nodes.last().id)
        event.keyCode == KeyEvent.VK_RIGHT || event.keyCode == KeyEvent.VK_DOWN -> select(model.nodes[(index + 1) % model.nodes.size].id)
        event.keyCode == KeyEvent.VK_LEFT || event.keyCode == KeyEvent.VK_UP -> select(model.nodes[if (index <= 0) model.nodes.lastIndex else index - 1].id)
        else -> return false
      }
      event.consume()
      return true
    }

    private fun imagePoint(point: Point, clamp: Boolean = false): Point? {
      val image = model.image ?: return null
      if (imageWidth <= 0 || imageHeight <= 0) return null
      if (!clamp && (point.x !in 0 until imageWidth || point.y !in 0 until imageHeight)) return null
      return Point((point.x.toLong() * image.width / imageWidth).toInt().coerceIn(0, image.width - 1),
        (point.y.toLong() * image.height / imageHeight).toInt().coerceIn(0, image.height - 1))
    }

    private fun hit(point: Point): ComposeLayoutNode? {
      val pixel = imagePoint(point) ?: return null
      return hitComposeLayout(model.nodes, pixel.x, pixel.y)
    }

    private fun forward(action: String, point: Point, clamp: Boolean = false) {
      if (!acceptsInput()) return
      val pixel = imagePoint(point, clamp) ?: return
      callbacks.pointer(model.id, action, pixel.x, pixel.y, 0.0, 0.0)
    }

    private fun wheel(event: MouseWheelEvent, point: Point) {
      val pixel = imagePoint(point)
      if (acceptsInput() && model.scroll && pixel != null) {
        val delta = event.preciseWheelRotation * event.scrollAmount * 16.0
        callbacks.pointer(model.id, "scroll", pixel.x, pixel.y, if (event.isShiftDown) delta else 0.0, if (event.isShiftDown) 0.0 else delta)
      } else {
        val bar = if (event.isShiftDown) viewport.horizontalScrollBar else viewport.verticalScrollBar
        val canScroll = bar.isVisible && bar.maximum > bar.visibleAmount &&
          (event.preciseWheelRotation < 0 && bar.value > bar.minimum || event.preciseWheelRotation > 0 && bar.value < bar.maximum - bar.visibleAmount)
        val target = if (canScroll) bar else galleryScroll.verticalScrollBar
        target.value += (event.preciseWheelRotation * event.scrollAmount * target.unitIncrement).toInt()
      }
      event.consume()
    }

    inner class PreviewCanvas : JPanel() {
      init {
        isOpaque = true
        background = UIUtil.getPanelBackground()
        enableInputMethods(false)
        addFocusListener(object : FocusAdapter() {
          override fun focusGained(e: FocusEvent) { focusInput(this@CardView); repaint() }
          override fun focusLost(e: FocusEvent) { if (inputCard === this@CardView) releaseInput(); repaint() }
        })
        val mouse = object : MouseAdapter() {
          override fun mousePressed(e: MouseEvent) {
            if (!SwingUtilities.isLeftMouseButton(e)) return
            requestFocusInWindow()
            if (inspecting) select(hit(e.point)?.id)
            else if (acceptsInput() && imagePoint(e.point) != null) {
              focusInput(this@CardView)
              pressed = true
              forward("down", e.point)
            }
          }
          override fun mouseReleased(e: MouseEvent) {
            if (pressed && SwingUtilities.isLeftMouseButton(e)) { forward("up", e.point, clamp = true); pressed = false }
          }
          override fun mouseClicked(e: MouseEvent) { if (inspecting && e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e)) navigate() }
          override fun mouseMoved(e: MouseEvent) {
            if (inspecting) {
              val id = hit(e.point)?.id
              if (hoveredId != id) { hoveredId = id; updateTools() }
            }
          }
          override fun mouseDragged(e: MouseEvent) { if (pressed) forward("move", e.point, clamp = true) }
          override fun mouseExited(e: MouseEvent) { if (hoveredId != null) { hoveredId = null; updateTools() } }
          override fun mouseWheelMoved(e: MouseWheelEvent) { wheel(e, e.point) }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
        addMouseWheelListener(mouse)
      }

      override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val g = graphics.create() as Graphics2D
        try {
          val image = model.image
          if (image == null) {
            g.color = UIUtil.getContextHelpForeground()
            g.drawString(if (model.error != null) "Preview unavailable — see logs" else "Waiting for preview…", JBUI.scale(8), JBUI.scale(28))
            return
          }
          g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
          g.drawImage(image, 0, 0, imageWidth, imageHeight, null)
          if (inspecting) {
            val sx = imageWidth.toDouble() / image.width
            val sy = imageHeight.toDouble() / image.height
            for (node in model.nodes) {
              val emphasized = node.id == selectedId || node.id == hoveredId
              val x = (node.x * sx).toInt()
              val y = (node.y * sy).toInt()
              val width = (node.width * sx).toInt().coerceAtLeast(1)
              val height = (node.height * sy).toInt().coerceAtLeast(1)
              g.color = if (emphasized) outlineColor else subduedOutlineColor
              g.stroke = if (emphasized) selectedStroke else outlineStroke
              g.drawRect(x, y, width, height)
              if (emphasized) {
                g.color = outlineFillColor
                g.fillRect(x, y, width, height)
              }
            }
          }
          if (hasFocus()) {
            g.color = JBColor.BLUE
            g.stroke = selectedStroke
            g.drawRect(1, 1, (minOf(width, imageWidth) - 3).coerceAtLeast(0), (minOf(height, imageHeight) - 3).coerceAtLeast(0))
          }
        } finally { g.dispose() }
      }
    }
  }

  /** Width-tracking grid; each row measures its existing cards at the available column width. */
  private inner class Gallery : JPanel(), Scrollable {
    private val gap = JBUI.scale(12)
    init {
      border = JBUI.Borders.empty(12)
      layout = object : LayoutManager {
        override fun addLayoutComponent(name: String?, comp: Component?) = Unit
        override fun removeLayoutComponent(comp: Component?) = Unit
        override fun minimumLayoutSize(parent: java.awt.Container?) = Dimension(0, 0)
        override fun preferredLayoutSize(parent: java.awt.Container?) = arrange(false)
        override fun layoutContainer(parent: java.awt.Container?) { arrange(true) }
      }
    }
    private fun arrange(place: Boolean): Dimension {
      val available = (width.takeIf { it > 0 } ?: JBUI.scale(340)) - insets.left - insets.right
      val columns = ((available + gap) / (JBUI.scale(320) + gap)).coerceAtLeast(1)
      val cellWidth = ((available - gap * (columns - 1)) / columns).coerceAtLeast(1)
      var y = insets.top
      var row = 0
      while (row < componentCount) {
        val end = minOf(row + columns, componentCount)
        var height = 0
        for (index in row until end) {
          val card = getComponent(index) as CardView
          card.widthChanged(cellWidth)
          height = maxOf(height, card.preferredSize.height)
        }
        if (place) for (index in row until end) getComponent(index).setBounds(insets.left + (index - row) * (cellWidth + gap), y, cellWidth, height)
        y += height + gap
        row = end
      }
      return Dimension(available + insets.left + insets.right, y + insets.bottom)
    }
    override fun getPreferredScrollableViewportSize() = Dimension(JBUI.scale(360), JBUI.scale(500))
    override fun getScrollableTracksViewportWidth() = true
    override fun getScrollableTracksViewportHeight() = false
    override fun getScrollableUnitIncrement(visibleRect: Rectangle?, orientation: Int, direction: Int) = JBUI.scale(24)
    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = (visibleRect.height - JBUI.scale(24)).coerceAtLeast(1)
  }

  companion object {
    private val outlineColor = JBColor(Color(0x2474B5), Color(0x70B8FF))
    private val subduedOutlineColor = JBColor(Color(0x24, 0x74, 0xB5, 110), Color(0x70, 0xB8, 0xFF, 110))
    private val outlineFillColor = JBColor(Color(0x24, 0x74, 0xB5, 30), Color(0x70, 0xB8, 0xFF, 30))
    private val outlineStroke = BasicStroke(1f)
    private val selectedStroke = BasicStroke(2f)
    private fun textArea(value: String, rows: Int) = JTextArea(value, rows, 0).apply {
      isEditable = false
      lineWrap = true
      wrapStyleWord = true
      isOpaque = false
      font = UIUtil.getLabelFont()
      border = JBUI.Borders.empty()
    }
    private fun area(node: ComposeLayoutNode) = node.width.toLong() * node.height
    private fun modifiers(event: KeyEvent) = (if (event.isShiftDown) 1 else 0) or (if (event.isControlDown) 2 else 0) or
      (if (event.isAltDown) 4 else 0) or (if (event.isMetaDown) 8 else 0)

    private fun browserKey(event: KeyEvent): String? = when (event.keyCode) {
      KeyEvent.VK_ENTER -> "Enter"
      KeyEvent.VK_TAB -> "Tab"
      KeyEvent.VK_BACK_SPACE -> "Backspace"
      KeyEvent.VK_DELETE -> "Delete"
      KeyEvent.VK_ESCAPE -> "Escape"
      KeyEvent.VK_LEFT -> "ArrowLeft"
      KeyEvent.VK_RIGHT -> "ArrowRight"
      KeyEvent.VK_UP -> "ArrowUp"
      KeyEvent.VK_DOWN -> "ArrowDown"
      KeyEvent.VK_HOME -> "Home"
      KeyEvent.VK_END -> "End"
      KeyEvent.VK_PAGE_UP -> "PageUp"
      KeyEvent.VK_PAGE_DOWN -> "PageDown"
      KeyEvent.VK_INSERT -> "Insert"
      KeyEvent.VK_SHIFT -> "Shift"
      KeyEvent.VK_CONTROL -> "Control"
      KeyEvent.VK_ALT -> "Alt"
      KeyEvent.VK_ALT_GRAPH -> "AltGraph"
      KeyEvent.VK_META -> "Meta"
      KeyEvent.VK_CAPS_LOCK -> "CapsLock"
      KeyEvent.VK_NUM_LOCK -> "NumLock"
      KeyEvent.VK_SCROLL_LOCK -> "ScrollLock"
      in KeyEvent.VK_F1..KeyEvent.VK_F12 -> "F${event.keyCode - KeyEvent.VK_F1 + 1}"
      else -> {
        val character = event.keyChar
        when {
          character != KeyEvent.CHAR_UNDEFINED && !character.isISOControl() && !character.isSurrogate() -> character.toString()
          event.keyCode in KeyEvent.VK_A..KeyEvent.VK_Z -> (('a'.code + event.keyCode - KeyEvent.VK_A).toChar()).let { if (event.isShiftDown) it.uppercase() else it.toString() }
          event.keyCode in KeyEvent.VK_0..KeyEvent.VK_9 -> ('0'.code + event.keyCode - KeyEvent.VK_0).toChar().toString()
          event.keyCode == KeyEvent.VK_SPACE -> " "
          else -> null
        }
      }
    }
  }
}
