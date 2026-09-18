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

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.project.Project
import dev.elide.intellij.Constants
import dev.elide.intellij.Constants.Strings

/**
 * Configuration type for runs of the binary a Native Image artifact produces.
 *
 * This is a plain local run rather than an external system one: the CLI builds the image but never executes it, so
 * what runs here is the binary itself, with `elide build <artifact>` attached as a before-launch task.
 */
class ElideNativeImageConfigurationType : ConfigurationTypeBase(
  ID,
  Strings["execution.nativeImage.type.name"],
  Strings["execution.nativeImage.type.description"],
  Constants.Icons.ELIDE,
) {
  init {
    addFactory(Factory(this))
  }

  override fun isDumbAware(): Boolean = true

  /** Factory for [ElideNativeImageRunConfiguration] instances. */
  class Factory(type: ElideNativeImageConfigurationType) : ConfigurationFactory(type) {
    override fun getId(): String = FACTORY_ID
    override fun isEditableInDumbMode(): Boolean = true
    override fun getOptionsClass(): Class<out BaseState> = ElideNativeImageRunConfiguration.Options::class.java

    override fun createTemplateConfiguration(project: Project): RunConfiguration {
      return ElideNativeImageRunConfiguration(project, this, "")
    }
  }

  companion object {
    /** ID of the configuration type, as persisted in run configuration files. */
    const val ID: String = "ElideNativeImage"

    /** ID of the single factory of this type, as persisted in run configuration files. */
    const val FACTORY_ID: String = "Elide Native Image"

    /** Returns the registered Native Image configuration type. */
    @JvmStatic val instance: ElideNativeImageConfigurationType
      get() = ConfigurationTypeUtil.findConfigurationType(ElideNativeImageConfigurationType::class.java)

    /** Returns the factory used to create [ElideNativeImageRunConfiguration] instances. */
    @JvmStatic val factory: ConfigurationFactory get() = instance.configurationFactories.single()
  }
}
