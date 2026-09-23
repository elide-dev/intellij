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
package dev.elide.intellij.settings

import com.intellij.openapi.externalSystem.util.PaintAwarePanel
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import dev.elide.intellij.Constants
import dev.elide.intellij.project.model.ElideProjectInfo
import dev.elide.intellij.service.elideProjectIndex
import java.awt.Container
import javax.swing.JComboBox
import javax.swing.JEditorPane
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the behaviour of the project settings panel: the distribution path field only applies to a custom
 * distribution, and what it holds is what gets applied to the settings.
 *
 * The browse dialog and path completion the field installs are not covered: the platform installs both only when an
 * `Application` is running, which this suite deliberately does not start.
 */
class ElideProjectSettingsControlTest {
  private fun <T> find(root: Container, type: Class<T>): T? {
    for (component in root.components) {
      if (type.isInstance(component)) return type.cast(component)
      if (component is Container) find(component, type)?.let { return it }
    }
    return null
  }

  @Test fun `distribution path is editable and applied only for a custom distribution`() {
    val settings = ElideProjectSettings().apply {
      externalProjectPath = "/tmp/elide-project"
      elideDistributionType = ElideDistributionSetting.AutoDetect
      elideDistributionPath = "/opt/elide"
    }

    val control = ElideProjectSettingsControl(settings)
    val canvas = PaintAwarePanel()
    control.fillUi(canvas, 0)

    val pathField = assertNotNull(find(canvas, TextFieldWithBrowseButton::class.java), "distribution path field")
    val distributionType = assertNotNull(find(canvas, JComboBox::class.java), "distribution type combo box")

    assertEquals("/opt/elide", pathField.text)
    assertFalse(pathField.isVisible, "path field is irrelevant while the distribution is auto-detected")

    distributionType.selectedItem = ElideDistributionSetting.Custom
    assertTrue(pathField.isVisible, "path field must appear for a custom distribution")

    pathField.text = "/usr/local/elide"

    val applied = ElideProjectSettings()
    control.apply(applied)

    assertEquals("/usr/local/elide", applied.elideDistributionPath)
    assertEquals(ElideDistributionSetting.Custom, applied.elideDistributionType)
  }
}

/**
 * Covers what the panel says about how far the settings it edits reach.
 *
 * A workspace member is never a linked project, so the root's panel is the only place its distribution and debugger
 * can be chosen; a user given no hint of that is left looking for a panel of the member's own. Unlike
 * [ElideProjectSettingsControlTest] this needs an application: the workspace structure lives in a project service,
 * and the panel only learns which project it configures when the dialog resets it.
 */
@TestApplication
class ElideProjectSettingsWorkspaceScopeTest {
  private val projectFixture = projectFixture()

  /** Fills a panel for the linked project at [path], in the order the settings dialog fills it. */
  private fun panelFor(project: Project, path: String): PaintAwarePanel {
    val control = ElideProjectSettingsControl(ElideProjectSettings().apply { externalProjectPath = path })
    val canvas = PaintAwarePanel()

    control.fillUi(canvas, 0)
    control.reset(project)

    return canvas
  }

  /** The comments the panel actually shows, as the user reads them; a hidden row discloses nothing. */
  private fun visibleComments(root: Container): List<String> = buildList {
    for (component in root.components) {
      if (!component.isVisible) continue
      if (component is JEditorPane) {
        add(component.document.getText(0, component.document.length).replace(WHITESPACE, " ").trim())
      }
      if (component is Container) addAll(visibleComments(component))
    }
  }

  @Test fun `only a workspace root says that its settings govern every project of the workspace`() {
    val project = projectFixture.get()
    project.elideProjectIndex.update(ROOT, ElideProjectInfo(members = listOf(MEMBER, OTHER_MEMBER)))
    project.elideProjectIndex.update(STANDALONE, ElideProjectInfo())

    val workspace = visibleComments(panelFor(project, ROOT))
    val standalone = visibleComments(panelFor(project, STANDALONE))

    // three projects: the root builds alongside the two members it declares, and is configured here with them
    val disclosure = Constants.Strings["settings.project.execution.workspace.comment", 3]

    assertTrue(disclosure in workspace, "the workspace root discloses nothing: $workspace")
    assertEquals(workspace - disclosure, standalone, "a standalone project has no workspace to disclose")
  }

  private companion object {
    private val WHITESPACE = Regex("\\s+")

    private const val ROOT = "/projects/demo"
    private const val MEMBER = "/projects/demo/app"
    private const val OTHER_MEMBER = "/projects/shared"
    private const val STANDALONE = "/projects/solo"
  }
}
