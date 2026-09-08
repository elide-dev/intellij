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
package dev.elide.intellij.execution

import com.intellij.execution.Location
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.ConfigurationFromContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import dev.elide.intellij.project.model.ElideEntrypointInfo
import dev.elide.intellij.project.model.ElideEntrypointInfo.Kind
import dev.elide.intellij.project.model.fullCommandLine
import dev.elide.intellij.psi.findJvmMainClassName
import dev.elide.intellij.service.elideProjectIndex

/**
 * Extension responsible for providing "run from gutter icon" configurations for main JVM entrypoints, in both Java and
 * Kotlin sources.
 */
class ElideJvmMainConfigurationProducer : LazyRunConfigurationProducer<ElideRunConfiguration>() {
  override fun isDumbAware(): Boolean = true

  override fun isPreferredConfiguration(self: ConfigurationFromContext?, other: ConfigurationFromContext?): Boolean {
    return self?.configuration is ElideRunConfiguration && other?.configuration !is ElideRunConfiguration
  }

  override fun shouldReplace(self: ConfigurationFromContext, other: ConfigurationFromContext): Boolean {
    return self.configuration is ElideRunConfiguration && other.configuration !is ElideRunConfiguration
  }

  override fun getConfigurationFactory(): ConfigurationFactory = ElideExternalTaskConfigurationType.configurationFactory

  override fun setupConfigurationFromContext(
    configuration: ElideRunConfiguration,
    context: ConfigurationContext,
    sourceElement: Ref<PsiElement?>
  ): Boolean {
    val startClassFQName = findMainClassName(context.location) ?: return false

    val (externalProject, entrypoint) = findJvmEntrypoint(context, startClassFQName) ?: return false

    configuration.name = entrypoint.displayName
    configuration.rawCommandLine = entrypoint.fullCommandLine
    configuration.settings.externalProjectPath = externalProject

    configuration.entrypointKind = entrypoint.kind
    configuration.entrypointValue = entrypoint.value

    return true
  }

  override fun isConfigurationFromContext(
    configuration: ElideRunConfiguration,
    context: ConfigurationContext
  ): Boolean {
    val startClassFQName = findMainClassName(context.location) ?: return false

    return configuration.entrypointKind == Kind.JvmMainClass && configuration.entrypointValue == startClassFQName
  }

  override fun findExistingConfiguration(context: ConfigurationContext): RunnerAndConfigurationSettings? {
    val startClassFQName = findMainClassName(context.location) ?: return null

    ProgressManager.checkCanceled()
    return getConfigurationSettingsList(RunManager.getInstance(context.project)).find { configurationSettings ->
      val configuration = (configurationSettings.configuration as ElideRunConfiguration)
      configuration.entrypointKind == Kind.JvmMainClass && configuration.entrypointValue == startClassFQName
    }
  }

  private fun findMainClassName(location: Location<*>?): String? {
    val element = location?.psiElement ?: return null
    return findJvmMainClassName(element)
  }

  private fun findJvmEntrypoint(
    context: ConfigurationContext,
    qualifiedName: String
  ): Pair<String, ElideEntrypointInfo>? {
    for ((path, project) in context.project.elideProjectIndex.entries) {
      val jvmMain = project.entrypoints.find {
        it.kind == Kind.JvmMainClass && it.value == qualifiedName
      } ?: continue

      return path to jvmMain
    }

    return null
  }
}
