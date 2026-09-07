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
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import java.awt.Container
import javax.swing.JComboBox
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
