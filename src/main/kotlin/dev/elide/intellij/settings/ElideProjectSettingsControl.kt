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

import com.intellij.openapi.externalSystem.service.settings.AbstractExternalProjectSettingsControl
import com.intellij.openapi.externalSystem.util.ExternalSystemUiUtil
import com.intellij.openapi.externalSystem.util.PaintAwarePanel
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.TextComponentAccessor
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.installFileCompletionAndBrowseDialog
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.selectedValueIs
import com.intellij.ui.layout.selectedValueMatches
import dev.elide.intellij.Constants
import dev.elide.intellij.project.ElideWorkspaces
import java.awt.Component
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.ListCellRenderer

/**
 * UI manager for [project-level][ElideProjectSettings] Elide settings panel.
 *
 * @see ElideConfigurable
 */
class ElideProjectSettingsControl(
  initialSettings: ElideProjectSettings
) : AbstractExternalProjectSettingsControl<ElideProjectSettings>(initialSettings) {
  private lateinit var projectControls: DialogPanel
  private lateinit var workspaceScope: Cell<JEditorPane>

  private var distributionType: ElideDistributionSetting = initialSettings.elideDistributionType
  private var distributionPath: String = initialSettings.elideDistributionPath
  private var nativeDebugger: ElideNativeDebuggerSetting = initialSettings.nativeDebugger
  private var gdbPath: String = initialSettings.gdbPath

  private fun controlsPanel(): DialogPanel = panel {
    group(Constants.Strings["settings.project.execution.title"]) {
      // the panel configures one linked project, but a workspace root's distribution and debugger are what every
      // member is synced and built with, and nothing else here admits that. The platform only hands the control its
      // project once the panel exists, so how many projects there are arrives with the reset that follows.
      row { workspaceScope = comment("").visible(false) }

      row(Constants.Strings["settings.project.distribution.label"]) {
        val distributionTypeBox = comboBox(ElideDistributionSetting.entries, DistributionTypeRenderer)
          .bindItem(::distributionType) { distributionType = it ?: ElideDistributionSetting.AutoDetect }

        // this is what the `Row.textFieldWithBrowseButton` shorthand does; that shorthand is experimental on every
        // supported build, while the component API behind it is stable (see `docs/PLATFORM_APIS.md`)
        val distributionPathField = TextFieldWithBrowseButton().apply {
          isOpaque = false
          textField.isOpaque = false

          installFileCompletionAndBrowseDialog(
            /* project = */ null,
            /* component = */ this,
            /* textField = */ textField,
            /* fileChooserDescriptor = */ Constants.sdkFileChooser(),
            /* textComponentAccessor = */ TextComponentAccessor.TEXT_FIELD_WHOLE_TEXT,
            /* fileChosen = */ { it.path },
          )
        }

        cell(distributionPathField)
          .bindText(getter = { distributionPath }, setter = { distributionPath = it })
          .visibleIf(distributionTypeBox.component.selectedValueIs(ElideDistributionSetting.Custom))

        rowComment(Constants.Strings["settings.project.distribution.comment"])
      }
    }

    group(Constants.Strings["settings.project.nativeDebug.title"]) {
      lateinit var debuggerBox: ComboBox<ElideNativeDebuggerSetting>

      row(Constants.Strings["settings.project.nativeDebug.debugger.label"]) {
        debuggerBox = comboBox(ElideNativeDebuggerSetting.entries, NativeDebuggerRenderer)
          .bindItem(::nativeDebugger) { nativeDebugger = it ?: ElideNativeDebuggerSetting.Auto }
          .component
      }

      row(Constants.Strings["settings.project.nativeDebug.gdbPath.label"]) {
        val gdbPathField = TextFieldWithBrowseButton().apply {
          isOpaque = false
          textField.isOpaque = false

          installFileCompletionAndBrowseDialog(
            /* project = */ null,
            /* component = */ this,
            /* textField = */ textField,
            /* fileChooserDescriptor = */ Constants.executableFileChooser(),
            /* textComponentAccessor = */ TextComponentAccessor.TEXT_FIELD_WHOLE_TEXT,
            /* fileChosen = */ { it.path },
          )
        }

        cell(gdbPathField).bindText(getter = { gdbPath }, setter = { gdbPath = it })
        rowComment(Constants.Strings["settings.project.nativeDebug.gdbPath.comment"])
        // an LLDB session takes the IDE's LLDB, or the system one when it ships none, so there is nothing to point at
      }.visibleIf(debuggerBox.selectedValueMatches { it != ElideNativeDebuggerSetting.Lldb })
    }
  }

  override fun fillExtraControls(canvas: PaintAwarePanel, indent: Int) {
    projectControls = controlsPanel()
    canvas.add(projectControls, ExternalSystemUiUtil.getFillLineConstraints(indent))
  }

  override fun showUi(show: Boolean) {
    super.showUi(show)
    projectControls.isVisible = show
  }

  override fun isExtraSettingModified(): Boolean {
    projectControls.apply()

    if (distributionPath != initialSettings.elideDistributionPath) return true
    if (distributionType != initialSettings.elideDistributionType) return true
    if (nativeDebugger != initialSettings.nativeDebugger) return true
    if (gdbPath != initialSettings.gdbPath) return true

    return false
  }

  override fun resetExtraSettings(isDefaultModuleCreation: Boolean) {
    distributionPath = initialSettings.elideDistributionPath
    distributionType = initialSettings.elideDistributionType
    nativeDebugger = initialSettings.nativeDebugger
    gdbPath = initialSettings.gdbPath

    updateWorkspaceScope()
    projectControls.reset()
  }

  /**
   * Discloses how far these settings reach when the project being configured owns a workspace: one distribution and
   * one debugger serve every member, because a member is never linked on its own and is only ever synced, built and
   * run through the root that declares it. A standalone project has nothing to disclose and keeps the row hidden.
   */
  private fun updateWorkspaceScope() {
    // the row is created with the panel, which a control asked to reset before it is filled does not have yet
    if (!::workspaceScope.isInitialized) return

    val ideProject = project
    val externalProjectPath = initialSettings.externalProjectPath

    // a control filling a wizard's panel has no project yet, and so no index to ask about membership
    val members = when {
      ideProject == null || externalProjectPath == null -> emptyList()
      else -> ElideWorkspaces.members(ideProject, externalProjectPath)
    }

    if (members.isEmpty()) {
      workspaceScope.visible(false)
      return
    }

    // the count covers the root itself, which Elide resolves and builds alongside the members it declares
    val comment = Constants.Strings["settings.project.execution.workspace.comment", members.size + 1]
    workspaceScope.component.text = comment
    workspaceScope.visible(true)
  }

  override fun updateInitialExtraSettings() {
    projectControls.apply()

    initialSettings.elideDistributionPath = distributionPath
    initialSettings.elideDistributionType = distributionType
    initialSettings.nativeDebugger = nativeDebugger
    initialSettings.gdbPath = gdbPath
  }

  override fun applyExtraSettings(settings: ElideProjectSettings) {
    projectControls.apply()

    settings.elideDistributionPath = distributionPath
    settings.elideDistributionType = distributionType
    settings.nativeDebugger = nativeDebugger
    settings.gdbPath = gdbPath
  }

  override fun validate(settings: ElideProjectSettings): Boolean {
    projectControls.apply()
    return true
  }

  private data object DistributionTypeRenderer : ListCellRenderer<ElideDistributionSetting?> {
    override fun getListCellRendererComponent(
      list: JList<out ElideDistributionSetting>,
      value: ElideDistributionSetting?,
      index: Int,
      isSelected: Boolean,
      cellHasFocus: Boolean
    ): Component {
      val text = when (value) {
        ElideDistributionSetting.Custom -> Constants.Strings["settings.project.distribution.type.custom"]
        else -> Constants.Strings["settings.project.distribution.type.auto"]
      }

      return JLabel(text)
    }
  }

  private data object NativeDebuggerRenderer : ListCellRenderer<ElideNativeDebuggerSetting?> {
    override fun getListCellRendererComponent(
      list: JList<out ElideNativeDebuggerSetting>,
      value: ElideNativeDebuggerSetting?,
      index: Int,
      isSelected: Boolean,
      cellHasFocus: Boolean
    ): Component {
      val text = when (value) {
        ElideNativeDebuggerSetting.Gdb -> Constants.Strings["settings.project.nativeDebug.debugger.gdb"]
        ElideNativeDebuggerSetting.Lldb -> Constants.Strings["settings.project.nativeDebug.debugger.lldb"]
        else -> Constants.Strings["settings.project.nativeDebug.debugger.auto"]
      }

      return JLabel(text)
    }
  }
}
