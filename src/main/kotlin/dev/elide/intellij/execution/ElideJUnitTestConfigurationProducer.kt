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
import com.intellij.openapi.util.io.toCanonicalPath
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
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
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
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
    // resolving the target walks PSI, so the (usually empty) candidate list is checked first
    val candidates = getConfigurationSettingsList(RunManager.getInstance(context.project))
    if (candidates.isEmpty()) return null

    val target = findTestTarget(context.location) ?: return null

    ProgressManager.checkCanceled()
    return candidates.find { configurationSettings ->
      val configuration = (configurationSettings.configuration as ElideRunConfiguration)
      configuration.entrypointKind == Kind.JvmTest && configuration.entrypointValue == target.entrypointValue
    }
  }

  /**
   * Resolves a JUnit test class or method at [location], covering both Java and Kotlin sources.
   *
   * Test detection reads annotation names off the source declarations instead of resolving them through
   * [com.intellij.execution.junit.JUnitUtil]: the platform resolves configurations from context on the EDT during
   * action updates, and any index or resolve access there trips the "Slow operations are prohibited on EDT"
   * assertion (and would fail outright while indexing, despite this producer being dumb-aware).
   */
  private fun findTestTarget(location: Location<*>?): TestTarget? {
    val element = location?.psiElement ?: return null

    // Kotlin declarations are inspected as Kotlin PSI and only converted to their light counterparts once the target
    // is known to be a test, since light members are what carry the JVM names used in test ids
    val ktFunction = element.getParentOfType<KtNamedFunction>(strict = false)
    val ktClass = element.getParentOfType<KtClassOrObject>(strict = false)
    val javaMethod = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java, false)
    val javaClass = javaMethod?.containingClass ?: PsiTreeUtil.getParentOfType(element, PsiClass::class.java, false)

    val isTestClass = ktClass?.let(::isTestClassDeclaration) ?: javaClass?.let(::isTestClassDeclaration) ?: false
    if (!isTestClass) return null

    // clicks on non-test members inside a test class fall back to a class-level target
    val method = when {
      ktFunction != null -> ktFunction.takeIf(::isTestDeclaration)?.toLightMethods()?.firstOrNull()
      else -> javaMethod?.takeIf(::isTestDeclaration)
    }

    val psiClass = method?.containingClass
      ?: ktClass?.toLightClass()
      ?: javaClass
      ?: return null

    // anonymous and local classes have no JVM class name usable in a test id
    val jvmClassName = ClassUtil.getJVMClassName(psiClass) ?: return null
    return TestTarget(psiClass, method, jvmClassName)
  }

  /** True if the Kotlin [function] declares a JUnit test annotation. */
  private fun isTestDeclaration(function: KtNamedFunction): Boolean =
    function.annotationEntries.any { it.shortName?.asString() in TEST_ANNOTATIONS }

  /** True if the Java [method] declares a JUnit test annotation. */
  private fun isTestDeclaration(method: PsiMethod): Boolean =
    method.annotations.any { it.nameReferenceElement?.referenceName in TEST_ANNOTATIONS }

  /** True if the Kotlin [declaration] is a runnable class holding test methods, directly or in a nested class. */
  private fun isTestClassDeclaration(declaration: KtClassOrObject): Boolean {
    if (declaration is KtClass && declaration.isInterface()) return false
    if (declaration.hasModifier(KtTokens.ABSTRACT_KEYWORD)) return false

    return declaration.declarations.any {
      when (it) {
        is KtNamedFunction -> isTestDeclaration(it)
        is KtClassOrObject -> isTestClassDeclaration(it)
        else -> false
      }
    }
  }

  /** True if the Java [psiClass] is a runnable class holding test methods, directly or in a nested class. */
  private fun isTestClassDeclaration(psiClass: PsiClass): Boolean {
    if (psiClass.isInterface || psiClass.hasModifierProperty(PsiModifier.ABSTRACT)) return false
    return psiClass.methods.any(::isTestDeclaration) || psiClass.innerClasses.any(::isTestClassDeclaration)
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
     * Simple names of the annotations marking a JUnit test method, covering JUnit 4 (`org.junit.Test`) and the
     * JUnit 5 method kinds. Names are matched textually: resolving them would pull in the stub indices, which is
     * prohibited on the EDT where the platform resolves configurations from context.
     */
    private val TEST_ANNOTATIONS = setOf(
      "Test",
      "ParameterizedTest",
      "RepeatedTest",
      "TestFactory",
      "TestTemplate",
    )

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
