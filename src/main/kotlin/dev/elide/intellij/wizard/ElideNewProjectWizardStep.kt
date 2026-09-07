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
package dev.elide.intellij.wizard

import com.intellij.ide.wizard.AbstractNewProjectWizardStep
import com.intellij.ide.wizard.NewProjectWizardBaseStep
import com.intellij.openapi.components.service
import com.intellij.openapi.observable.properties.GraphProperty
import com.intellij.openapi.observable.util.transform
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextComponentAccessor
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.io.toCanonicalPath
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.installFileCompletionAndBrowseDialog
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.Placeholder
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.selected
import com.intellij.ui.dsl.builder.whenItemSelectedFromUi
import com.intellij.ui.dsl.builder.whenStateChangedFromUi
import com.intellij.ui.dsl.builder.whenTextChangedFromUi
import com.intellij.util.ui.UIUtil
import dev.elide.intellij.Constants
import dev.elide.intellij.cli.ElideTemplate
import dev.elide.intellij.cli.ElideTemplateParameter
import dev.elide.intellij.cli.ElideTemplates
import dev.elide.intellij.service.ElideDistributionResolver
import dev.elide.intellij.settings.ElideDistributionSetting
import dev.elide.intellij.settings.ElideProjectSettings
import java.awt.Component
import javax.swing.DefaultComboBoxModel
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.ListCellRenderer
import javax.swing.event.DocumentEvent
import kotlinx.coroutines.Job
import kotlin.io.path.Path
import kotlin.io.path.isRegularFile

/**
 * Wizard step choosing an Elide distribution, one of its project templates, and the answers to that template's
 * questionnaire.
 *
 * Answers are written into [parameterAnswers] and [blockAnswers] as they are typed rather than bound through the UI
 * DSL: the step validates them before the platform applies any binding, and the option controls are rebuilt from
 * scratch whenever the selected template changes.
 *
 * The template's own project-name parameter is not offered as an option; it is answered with the wizard's project name,
 * which the base step above already asks for.
 */
class ElideNewProjectWizardStep(
  private val baseStep: NewProjectWizardBaseStep,
) : AbstractNewProjectWizardStep(baseStep) {
  private val distributionProperty: GraphProperty<String> = propertyGraph.lazyProperty {
    ElideDistributionResolver.defaultDistributionPath().toCanonicalPath()
  }

  private val templatesProperty: GraphProperty<List<ElideTemplate>> = propertyGraph.property(emptyList())

  private val templateProperty: GraphProperty<ElideTemplate?> = propertyGraph.property(null)

  /** Answers to text and flag parameters, keyed by parameter id; an absent entry takes the template's default. */
  private val parameterAnswers = mutableMapOf<String, String>()

  /** Whether each optional block is generated, keyed by block id. */
  private val blockAnswers = mutableMapOf<String, GraphProperty<Boolean>>()

  private val statusLabel = JBLabel().apply { isVisible = false }

  private var loading: Job? = null

  override fun setupUI(builder: Panel) {
    with(builder) {
      row(Constants.Strings["wizard.distribution.label"]) {
        // this is what the `Row.textFieldWithBrowseButton` shorthand does; that shorthand is experimental on every
        // supported build, while the component API behind it is stable (see `docs/PLATFORM_APIS.md`)
        val field = TextFieldWithBrowseButton().apply {
          text = distributionProperty.get()

          installFileCompletionAndBrowseDialog(
            /* project = */ null,
            /* component = */ this,
            /* textField = */ textField,
            /* fileChooserDescriptor = */ Constants.sdkFileChooser(),
            /* textComponentAccessor = */ TextComponentAccessor.TEXT_FIELD_WHOLE_TEXT,
            /* fileChosen = */ { it.path },
          )
        }

        field.textField.document.addDocumentListener(object : DocumentAdapter() {
          override fun textChanged(event: DocumentEvent) = distributionProperty.set(field.text)
        })

        cell(field)
          .align(AlignX.FILL)
          .validationOnApply {
            if (hasElideBinary(distributionProperty.get())) null
            else error(Constants.Strings["wizard.distribution.invalid", distributionProperty.get()])
          }
      }

      row(Constants.Strings["wizard.template.label"]) {
        val model = DefaultComboBoxModel<ElideTemplate>()

        val combo = comboBox(model, TemplateRenderer)
          .align(AlignX.FILL)
          .whenItemSelectedFromUi { templateProperty.set(it) }
          .validationOnApply {
            val problem = firstProblem()
            if (problem == null) null else error(problem)
          }
          .component

        templatesProperty.afterChange { templates ->
          model.removeAllElements()
          templates.forEach(model::addElement)
          combo.selectedItem = templates.firstOrNull()
          templateProperty.set(templates.firstOrNull())
        }
      }

      row { cell(statusLabel) }

      group(Constants.Strings["wizard.parameters.title"]) {
        row {
          val options = placeholder().align(AlignX.FILL)
          templateProperty.afterChange { template -> renderOptions(options, template) }
        }
      }.visibleIf(templateProperty.transform { it != null })

      distributionProperty.afterChange { reloadTemplates() }
      reloadTemplates()
    }
  }

  /**
   * Hand the answered questionnaire to [ElideProjectGenerator], which generates and imports the project.
   *
   * Nothing is written here: this runs while the project is still being created, and the CLI call plus the import that
   * follows it belong on the project's own coroutine scope, where they can report progress and failures.
   */
  override fun setupProject(project: Project) {
    val template = templateProperty.get() ?: return
    val projectDir = Path(baseStep.path).resolve(baseStep.name)
    val elideHome = Path(distributionProperty.get())

    val answers = linkedMapOf(ElideTemplates.PROJECT_NAME_PARAMETER to baseStep.name)
    collectAnswers(template, answers)

    val settings = ElideProjectSettings().apply {
      externalProjectPath = projectDir.toCanonicalPath()

      if (elideHome != ElideDistributionResolver.defaultDistributionPath()) {
        elideDistributionType = ElideDistributionSetting.Custom
        elideDistributionPath = elideHome.toCanonicalPath()
      }
    }

    project.service<ElideProjectGenerator>().generate(projectDir, elideHome, template.id, answers, settings)
  }

  /** Whether [home] holds an Elide CLI binary; the same check [dev.elide.intellij.cli.ElideCommandLine] makes. */
  private fun hasElideBinary(home: String): Boolean =
    home.isNotBlank() &&
      Path(home).resolve(Constants.ELIDE_BINARIES_DIR).resolve(Constants.ELIDE_BINARY).isRegularFile()

  private fun reloadTemplates() {
    loading?.cancel()
    loading = null

    val distribution = distributionProperty.get()

    if (!hasElideBinary(distribution)) {
      templatesProperty.set(emptyList())
      showStatus(Constants.Strings["wizard.distribution.invalid", distribution], error = true)
      return
    }

    showStatus(Constants.Strings["wizard.template.loading"], error = false)

    loading = service<ElideTemplateLoader>().load(Path(distribution)) { result ->
      result
        .onSuccess { templates ->
          templatesProperty.set(templates)
          showStatus(null, error = false)
        }
        .onFailure { failure ->
          templatesProperty.set(emptyList())
          showStatus(
            Constants.Strings["wizard.template.loadFailed", distribution, failure.message.orEmpty()],
            error = true,
          )
        }
    }
  }

  private fun showStatus(message: String?, error: Boolean) {
    statusLabel.text = message.orEmpty()
    statusLabel.isVisible = message != null
    statusLabel.foreground = if (error) JBColor.RED else UIUtil.getContextHelpForeground()
  }

  /** Rebuild the option controls for [template], discarding the answers given to the previous one. */
  private fun renderOptions(options: Placeholder, template: ElideTemplate?) {
    parameterAnswers.clear()
    blockAnswers.clear()

    if (template == null) {
      options.component = null
      return
    }

    registerBlocks(template)

    options.component = panel {
      if (template.description.isNotEmpty()) row { comment(template.description) }
      renderBlock(template)
    }
  }

  /** Create the toggle state of every optional block below [block], so nested rows can be enabled against it. */
  private fun registerBlocks(block: ElideTemplate) {
    for (nested in block.blocks) {
      blockAnswers[nested.id] = propertyGraph.property(nested.default)
      registerBlocks(nested)
    }
  }

  private fun Panel.renderBlock(block: ElideTemplate) {
    for (parameter in block.parameters) {
      if (parameter.id == ElideTemplates.PROJECT_NAME_PARAMETER) continue
      renderParameter(parameter)
    }

    for (nested in block.blocks) {
      val enabled = blockAnswers.getValue(nested.id)

      row {
        val checkBox = checkBox(nested.title)
          .selected(enabled.get())
          .whenStateChangedFromUi { enabled.set(it) }

        if (nested.description.isNotEmpty()) checkBox.comment(nested.description)
      }

      indent { renderBlock(nested) }.enabledIf(enabled)
    }
  }

  private fun Panel.renderParameter(parameter: ElideTemplateParameter) {
    if (parameter.isBoolean) {
      row {
        val checkBox = checkBox(parameter.title)
          .selected(parameter.default.toBoolean())
          .whenStateChangedFromUi { parameterAnswers[parameter.id] = it.toString() }

        if (parameter.description.isNotEmpty()) checkBox.comment(parameter.description)
      }
    } else {
      row(parameter.title) {
        val textField = textField()
          .columns(COLUMNS_MEDIUM)
          .applyToComponent { text = parameter.default }
          .whenTextChangedFromUi { parameterAnswers[parameter.id] = it }

        if (parameter.description.isNotEmpty()) textField.comment(parameter.description)
      }
    }
  }

  /**
   * Collect the answers to send to `elide init` for [block] and every enabled block below it.
   *
   * Parameters of a disabled block are left out on purpose: the CLI validates every answer it is given, so a value
   * typed and then switched off would fail generation over a control the user can no longer see.
   */
  private fun collectAnswers(block: ElideTemplate, into: MutableMap<String, String>) {
    for (parameter in block.parameters) {
      if (parameter.id == ElideTemplates.PROJECT_NAME_PARAMETER) continue
      into[parameter.id] = parameterAnswers[parameter.id] ?: parameter.default
    }

    for (nested in block.blocks) {
      val enabled = blockAnswers[nested.id]?.get() ?: nested.default
      into[nested.id] = enabled.toString()
      if (enabled) collectAnswers(nested, into)
    }
  }

  /** Message describing the first unusable answer of the selected template, or `null` when it can be generated. */
  private fun firstProblem(): String? {
    val template = templateProperty.get() ?: return Constants.Strings["wizard.template.required"]
    return firstProblem(template)
  }

  private fun firstProblem(block: ElideTemplate): String? {
    for (parameter in block.parameters) {
      if (parameter.id == ElideTemplates.PROJECT_NAME_PARAMETER) continue
      val value = parameterAnswers[parameter.id] ?: parameter.default
      val problem = ElideTemplates.validate(parameter, value) ?: continue
      return Constants.Strings["wizard.parameters.invalid", parameter.title, problem]
    }

    for (nested in block.blocks) {
      val enabled = blockAnswers[nested.id]?.get() ?: nested.default
      if (!enabled) continue
      firstProblem(nested)?.let { return it }
    }

    return null
  }

  private data object TemplateRenderer : ListCellRenderer<ElideTemplate?> {
    override fun getListCellRendererComponent(
      list: JList<out ElideTemplate?>,
      value: ElideTemplate?,
      index: Int,
      isSelected: Boolean,
      cellHasFocus: Boolean,
    ): Component = JLabel(value?.title.orEmpty())
  }
}
