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

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.ConfigurationFromContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.io.toCanonicalPath
import com.intellij.psi.PsiElement
import dev.elide.intellij.execution.ElideRunConfiguration
import dev.elide.intellij.psi.ManifestNativeImage
import dev.elide.intellij.psi.moduleMappingKey
import dev.elide.intellij.psi.nativeImage
import org.pkl.intellij.PklLanguage

/**
 * Offers a run of the binary at the key of a Native Image artifact in the manifest.
 *
 * The key already carries the "Build" configuration every artifact gets; this adds the second one, which starts what
 * that build produced. Both stay in the context, so the gutter of an image lists a build, a run and a debug.
 */
class ElideNativeImageConfigurationProducer : LazyRunConfigurationProducer<ElideNativeImageRunConfiguration>() {
  override fun isDumbAware(): Boolean = true

  override fun getConfigurationFactory(): ConfigurationFactory = ElideNativeImageConfigurationType.factory

  /** Preferred over any foreign configuration, but never over the "Build" of the same artifact. */
  override fun isPreferredConfiguration(self: ConfigurationFromContext?, other: ConfigurationFromContext?): Boolean {
    return other.isForeign()
  }

  override fun shouldReplace(self: ConfigurationFromContext, other: ConfigurationFromContext): Boolean {
    return other.isForeign()
  }

  override fun setupConfigurationFromContext(
    configuration: ElideNativeImageRunConfiguration,
    context: ConfigurationContext,
    sourceElement: Ref<PsiElement?>,
  ): Boolean {
    val element = sourceElement.get() ?: return false
    val image = element.declaredNativeImage() ?: return false
    val projectPath = element.projectPath() ?: return false

    ElideNativeImageRunConfigurations.configure(configuration, projectPath, image.artifact)

    return true
  }

  override fun isConfigurationFromContext(
    configuration: ElideNativeImageRunConfiguration,
    context: ConfigurationContext,
  ): Boolean {
    val element = context.location?.psiElement ?: return false
    val image = element.declaredNativeImage() ?: return false
    val projectPath = element.projectPath() ?: return false

    return ElideNativeImageRunConfigurations.matches(configuration, projectPath, image.artifact)
  }

  /** The Native Image declared by the `artifacts` entry this element is the key of, if any. */
  private fun PsiElement.declaredNativeImage(): ManifestNativeImage? {
    if (language != PklLanguage) return null

    return moduleMappingKey("artifacts")?.nativeImage()
  }

  /** The manifest being edited identifies the linked project, which is not necessarily the project base directory. */
  private fun PsiElement.projectPath(): String? {
    return containingFile?.originalFile?.virtualFile?.parent?.toNioPath()?.toCanonicalPath()
  }

  private fun ConfigurationFromContext?.isForeign(): Boolean {
    return this?.configuration !is ElideRunConfiguration && this?.configuration !is ElideNativeImageRunConfiguration
  }
}
