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
package dev.elide.intellij.execution.nativeimage

import com.intellij.execution.ui.CommonProgramParametersPanel
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.TextComponentAccessor
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.installFileCompletionAndBrowseDialog
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import dev.elide.intellij.Constants
import javax.swing.JComponent

/** Editor for a [ElideNativeImageRunConfiguration]: which artifact of which project, and how to start its binary. */
class ElideNativeImageSettingsEditor(private val project: Project) :
  SettingsEditor<ElideNativeImageRunConfiguration>() {
  private var externalProjectPath: String = ""
  private var artifact: String = ""

  /**
   * Program arguments, working directory and environment; the same component every local run configuration uses.
   *
   * The project-taking constructor, which would widen the macros the fields complete, is not declared on 253.
   */
  @Suppress("DEPRECATION")
  private val parameters = CommonProgramParametersPanel()

  private val controls: DialogPanel = panel {
    row(Constants.Strings["execution.nativeImage.editor.project"]) {
      // this is what the `Row.textFieldWithBrowseButton` shorthand does; that shorthand is experimental on every
      // supported build, while the component API behind it is stable (see `docs/PLATFORM_APIS.md`)
      val projectPathField = TextFieldWithBrowseButton().apply {
        installFileCompletionAndBrowseDialog(
          /* project = */ project,
          /* component = */ this,
          /* textField = */ textField,
          /* fileChooserDescriptor = */ Constants.sdkFileChooser(),
          /* textComponentAccessor = */ TextComponentAccessor.TEXT_FIELD_WHOLE_TEXT,
          /* fileChosen = */ { it.path },
        )
      }

      cell(projectPathField)
        .align(AlignX.FILL)
        .bindText(getter = { externalProjectPath }, setter = { externalProjectPath = it })
    }

    row(Constants.Strings["execution.nativeImage.editor.artifact"]) {
      textField()
        .align(AlignX.FILL)
        .bindText(getter = { artifact }, setter = { artifact = it })
    }

    row {
      cell(parameters).align(AlignX.FILL)
    }
  }

  override fun createEditor(): JComponent = controls

  override fun resetEditorFrom(configuration: ElideNativeImageRunConfiguration) {
    externalProjectPath = configuration.externalProjectPath.orEmpty()
    artifact = configuration.artifact.orEmpty()

    controls.reset()
    parameters.reset(configuration)
  }

  override fun applyEditorTo(configuration: ElideNativeImageRunConfiguration) {
    controls.apply()
    parameters.applyTo(configuration)

    configuration.externalProjectPath = externalProjectPath.takeUnless { it.isBlank() }
    configuration.artifact = artifact.takeUnless { it.isBlank() }
  }
}
