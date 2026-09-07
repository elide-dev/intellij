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

import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.ide.wizard.GeneratorNewProjectWizard
import com.intellij.ide.wizard.GeneratorNewProjectWizardBuilderAdapter
import com.intellij.ide.wizard.NewProjectWizardChainStep.Companion.nextStep
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.ide.wizard.RootNewProjectWizardStep
import com.intellij.ide.wizard.newProjectWizardBaseStepWithoutGap
import dev.elide.intellij.Constants
import javax.swing.Icon

/**
 * Generator backing the *Elide* entry in the New Project wizard, offering the project templates the configured Elide
 * distribution ships (the same ones `elide init` lists).
 *
 * The step chain is the platform's own: the root step carries the wizard context, the base step contributes the project
 * name and location, and [ElideNewProjectWizardStep] adds the distribution, template, and template options.
 */
class ElideNewProjectWizard : GeneratorNewProjectWizard {
  override val id: String = "Elide"

  override val name: String = Constants.Strings["elide"]

  override val icon: Icon = Constants.Icons.ELIDE

  override val description: String = Constants.Strings["wizard.description"]

  override fun createStep(context: WizardContext): NewProjectWizardStep =
    RootNewProjectWizardStep(context)
      .nextStep(::newProjectWizardBaseStepWithoutGap)
      .nextStep(::ElideNewProjectWizardStep)
}

/**
 * Adapter registering [ElideNewProjectWizard] as a module builder, which is how the New Project wizard discovers
 * generators.
 *
 * The adapter derives a builder id of `NPW.Elide`, which is what tells the platform to skip its own generic project
 * settings step in favour of the wizard's own chain.
 */
class ElideNewProjectWizardBuilder : GeneratorNewProjectWizardBuilderAdapter(ElideNewProjectWizard())
