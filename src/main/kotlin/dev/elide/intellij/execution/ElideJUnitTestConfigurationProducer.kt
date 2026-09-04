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
import com.intellij.execution.PsiLocation
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.ConfigurationFromContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.junit.JUnitUtil
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.io.toCanonicalPath
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.ClassUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.execution.ParametersListUtil
import dev.elide.intellij.cli.ElideCli
import dev.elide.intellij.project.model.ElideEntrypointInfo.Kind
import dev.elide.intellij.service.elideProjectIndex
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.regex.Pattern
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.asJava.toLightMethods
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType

/**
 * Extension responsible for providing "run from gutter icon" configurations for JUnit test classes and methods in
 * linked Elide projects, running `elide test` narrowed to the selected class or method via `--test-name-pattern`.
 */
class ElideJUnitTestConfigurationProducer : LazyRunConfigurationProducer<ElideRunConfiguration>() {
  /** A JUnit test class or method resolved from a PSI location. */
  private data class TestTarget(
    /** Containing (or clicked) test class, as Java PSI or a Kotlin light class. */
    val psiClass: PsiClass,
    /** Selected test method, or `null` for a class-level target. */
    val method: PsiMethod?,
    /** JVM binary class name, e.g. `pkg.Outer$Inner`. */
    val jvmClassName: String,
  ) {
    /** Stable identity for run configuration matching: `pkg.Class` or `pkg.Class#method`. */
    val entrypointValue: String get() = method?.let { "$jvmClassName#${it.name}" } ?: jvmClassName
  }

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
    val element = context.location?.psiElement ?: return false
    val target = findTestTarget(context.location) ?: return false
    val externalProject = findElideProject(context, element) ?: return false

    configuration.name = target.method?.let { "${target.psiClass.name}.${it.name}" } ?: target.psiClass.name.orEmpty()
    configuration.rawCommandLine = ParametersListUtil.join(
      ElideCli.TEST.name,
      ElideCli.TEST_NAME_PATTERN.option,
      testNamePattern(target.jvmClassName, target.method?.name),
    )
    configuration.settings.externalProjectPath = externalProject

    configuration.entrypointKind = Kind.JvmTest
    configuration.entrypointValue = target.entrypointValue

    return true
  }

  override fun isConfigurationFromContext(
    configuration: ElideRunConfiguration,
    context: ConfigurationContext
  ): Boolean {
    val target = findTestTarget(context.location) ?: return false
    return configuration.entrypointKind == Kind.JvmTest && configuration.entrypointValue == target.entrypointValue
  }

  override fun findExistingConfiguration(context: ConfigurationContext): RunnerAndConfigurationSettings? {
    val target = findTestTarget(context.location) ?: return null

    ProgressManager.checkCanceled()
    return getConfigurationSettingsList(RunManager.getInstance(context.project)).find { configurationSettings ->
      val configuration = (configurationSettings.configuration as ElideRunConfiguration)
      configuration.entrypointKind == Kind.JvmTest && configuration.entrypointValue == target.entrypointValue
    }
  }

  /** Resolves a JUnit test class or method at [location], covering both Java and Kotlin sources. */
  private fun findTestTarget(location: Location<*>?): TestTarget? {
    val element = location?.psiElement ?: return null

    // resolve a candidate method/class pair; Kotlin declarations go through their light-class counterparts
    var method = element.getParentOfType<KtNamedFunction>(strict = false)?.toLightMethods()?.firstOrNull()
      ?: PsiTreeUtil.getParentOfType(element, PsiMethod::class.java, false)

    val psiClass = method?.containingClass
      ?: element.getParentOfType<KtClassOrObject>(strict = false)?.toLightClass()
      ?: PsiTreeUtil.getParentOfType(element, PsiClass::class.java, false)
      ?: return null

    if (!JUnitUtil.isTestClass(psiClass)) return null
    if (method != null && !JUnitUtil.isTestMethod(PsiLocation.fromPsiElement(method))) {
      // clicks on non-test members inside a test class fall back to a class-level target
      method = null
    }

    // anonymous and local classes have no JVM class name usable in a test id
    val jvmClassName = ClassUtil.getJVMClassName(psiClass) ?: return null
    return TestTarget(psiClass, method, jvmClassName)
  }

  /** Returns the external project path of the linked Elide project containing [element], or `null` if none does. */
  private fun findElideProject(context: ConfigurationContext, element: PsiElement): String? {
    // index keys are canonicalized at every write site; apply the same transform before the prefix comparison
    val filePath = element.containingFile?.virtualFile?.toNioPath()?.toCanonicalPath()?.let(Path::of) ?: return null

    return context.project.elideProjectIndex.entries
      .filter { (path, _) ->
        try {
          filePath.startsWith(Path.of(path))
        } catch (_: InvalidPathException) {
          false
        }
      }
      .maxByOrNull { (path, _) -> path.length }
      ?.key
  }

  internal companion object {
    /**
     * Builds the `--test-name-pattern` regex matching the JVM test id `pkg.Class#method` for [jvmClassName] and an
     * optional [methodName]; class-level patterns also cover `@Nested` classes via the `$` separator. Verified
     * against Elide 1.5.1.
     */
    fun testNamePattern(jvmClassName: String, methodName: String?): String = when (methodName) {
      null -> "^" + Pattern.quote(jvmClassName) + "[#$]"
      else -> "^" + Pattern.quote(jvmClassName) + "#" + Pattern.quote(methodName) + "$"
    }
  }
}
