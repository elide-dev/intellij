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
package dev.elide.intellij.cli

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.externalSystem.service.ui.command.line.CommandLineInfo
import com.intellij.openapi.externalSystem.service.ui.command.line.CompletionTableInfo
import com.intellij.openapi.externalSystem.service.ui.completion.TextCompletionInfo
import com.intellij.openapi.externalSystem.service.ui.project.path.WorkingDirectoryField
import com.intellij.openapi.observable.util.createTextModificationTracker
import com.intellij.openapi.observable.util.whenCaretMoved
import com.intellij.openapi.observable.util.whenTextChanged
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.SimpleModificationTracker
import dev.elide.intellij.Constants
import dev.elide.intellij.project.model.ElideEntrypointInfo
import dev.elide.intellij.service.elideProjectIndex
import javax.swing.Icon
import javax.swing.text.JTextComponent

/**
 * Completion and assistance for the command line of an Elide run configuration.
 *
 * Suggestions are context-sensitive: the tables offer the commands, positional arguments and flags that the Elide CLI
 * accepts at the caret, according to the [ElideCli] schema. [attach] must be called with the field the tables belong
 * to, so they can see what has been typed so far.
 */
class ElideCommandLineInfo(
  private val project: Project,
  private val workingDirectoryField: WorkingDirectoryField,
) : CommandLineInfo {
  override val fieldEmptyState: String = Constants.Strings["execution.cmdline.empty"]

  override val dialogTitle: String = Constants.Strings["execution.dialog.title"]
  override val dialogTooltip: String = Constants.Strings["execution.dialog.tooltip"]

  override val settingsHint: String = Constants.Strings["execution.settings.hint"]
  override val settingsName: String = Constants.Strings["execution.settings.name"]

  /**
   * Tokens preceding the word under the caret, snapshotted on the EDT by [attach].
   *
   * Completion is collected on a background thread, which must not touch the field's Swing state.
   */
  @Volatile private var typedTokens: List<String> = emptyList()

  private val typedTokensTracker = SimpleModificationTracker()

  private val workingDirectoryTracker = workingDirectoryField.createTextModificationTracker()

  private val completionTracker = ModificationTracker {
    workingDirectoryTracker.modificationCount + typedTokensTracker.modificationCount
  }

  override val tablesInfo: List<CompletionTableInfo> = listOf(
    TaskCompletionTableInfo(),
    FlagCompletionTableInfo(),
  )

  /**
   * Binds this info to the [field] it provides completion for, so suggestions can account for what is already typed.
   *
   * Listeners are removed when [parentDisposable] is disposed.
   */
  fun attach(field: JTextComponent, parentDisposable: Disposable) {
    fun snapshot() {
      typedTokens = ElideCliCompletion.context(field.text, field.caretPosition)
      typedTokensTracker.incModificationCount()
    }

    field.whenTextChanged(parentDisposable) { snapshot() }
    field.whenCaretMoved(parentDisposable) { snapshot() }
    snapshot()
  }

  private fun entrypoints(): List<ElideEntrypointInfo> {
    return project.elideProjectIndex[workingDirectoryField.workingDirectory]?.entrypoints.orEmpty()
  }

  private fun ElideCliCompletion.Variant.toCompletionInfo(): TextCompletionInfo {
    val description = when (descriptionArg) {
      null -> Constants.Strings[descriptionKey]
      else -> Constants.Strings[descriptionKey, descriptionArg]
    }

    return TextCompletionInfo(text, description)
  }

  /** Commands, entrypoints and positional arguments accepted at the caret. */
  private inner class TaskCompletionTableInfo : CompletionTableInfo {
    override val emptyState: String = Constants.Strings["execution.completion.tasks.emptyState"]

    override val dataColumnIcon: Icon = AllIcons.General.Gear
    override val dataColumnName: String = Constants.Strings["execution.completion.table.tasks.name"]

    override val descriptionColumnIcon: Icon = AllIcons.General.BalloonInformation
    override val descriptionColumnName: String = Constants.Strings["execution.completion.table.tasks.description"]

    override val completionModificationTracker: ModificationTracker = completionTracker

    override suspend fun collectCompletionInfo(): List<TextCompletionInfo> {
      return ElideCliCompletion.tasks(typedTokens, entrypoints()).map { it.toCompletionInfo() }
    }

    override suspend fun collectTableCompletionInfo(): List<TextCompletionInfo> {
      return collectCompletionInfo()
    }
  }

  /** Flags accepted at the caret, for the command already named on the command line. */
  private inner class FlagCompletionTableInfo : CompletionTableInfo {
    override val emptyState: String = Constants.Strings["execution.completion.flags.emptyState"]

    override val dataColumnIcon: Icon = AllIcons.General.Settings
    override val dataColumnName: String = Constants.Strings["execution.completion.table.flags.name"]

    override val descriptionColumnIcon: Icon = AllIcons.General.BalloonInformation
    override val descriptionColumnName: String = Constants.Strings["execution.completion.table.tasks.description"]

    override val completionModificationTracker: ModificationTracker = completionTracker

    override suspend fun collectCompletionInfo(): List<TextCompletionInfo> {
      return ElideCliCompletion.flags(typedTokens, includeShort = true).map { it.toCompletionInfo() }
    }

    // the table renders one row per variant, so the short forms are left out to keep it half as long
    override suspend fun collectTableCompletionInfo(): List<TextCompletionInfo> {
      return ElideCliCompletion.flags(typedTokens, includeShort = false).map { it.toCompletionInfo() }
    }
  }
}
