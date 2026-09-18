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

import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.lineMarker.ExecutorAction
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.firstLeaf
import dev.elide.intellij.psi.manifestDeclaresEntrypoint
import dev.elide.intellij.psi.moduleMappingKey
import dev.elide.intellij.psi.nativeImage
import dev.elide.intellij.psi.parentPropertyReference
import dev.elide.intellij.psi.parentStringLiteral
import org.pkl.intellij.PklLanguage
import org.pkl.intellij.psi.*

class ElideManifestLineMarkerContributor : RunLineMarkerContributor() {
  override fun getInfo(element: PsiElement): Info? {
    if (element.language != PklLanguage) return null

    return detectJvmEntrypoint(element)
      ?: detectGenericEntrypoint(element)
      ?: detectScript(element)
      ?: detectArtifact(element)
  }

  @Suppress("ReturnCount")
  private fun detectJvmEntrypoint(element: PsiElement): Info? {
    if (!element.textMatches("main")) return null
    if (element.parent !is PklPropertyName) return null

    val prop = PsiTreeUtil.getParentOfType(element, PklClassProperty::class.java) ?: return null
    if (!prop.propertyName.textMatches("jvm")) return null
    if (prop.parent !is PklModuleMemberList) return null

    // no runnable configuration exists for a JVM main the manifest's own `entrypoint` shadows, so an icon here would
    // offer actions that resolve to nothing
    if (element.manifestDeclaresEntrypoint) return null

    return withExecutorActions(AllIcons.Actions.Execute)
  }

  @Suppress("ReturnCount")
  private fun detectGenericEntrypoint(element: PsiElement): Info? {
    // only simple string elements or references are currently supported, e.g:
    // local hello = "./hello.js"
    // entrypoint {
    //  "./hello.js"
    //  hello
    // }
    // simple references resolve to a property (otherwise we can't see their value)
    val anchor = element.parentStringLiteral
      ?: element.parentPropertyReference
      ?: return null

    val listingEntry = anchor.parent as? PklObjectElement ?: return null
    if (listingEntry.parent !is PklObjectBody) return null

    // only select if it's the first leaf, to avoid stacked action tooltips
    if (element.parent.firstLeaf() != element) return null

    val listingElement = PsiTreeUtil.getParentOfType(element, PklClassProperty::class.java) ?: return null
    if (listingElement.propertyName.text != "entrypoint") return null
    if (listingElement.parent !is PklModuleMemberList) return null

    return withExecutorActions(AllIcons.Actions.Execute)
  }

  private fun detectScript(element: PsiElement): Info? {
    // only simple string keys or references are currently supported, e.g:
    // local bye = "bye"
    // scripts {
    //  ["hello"] = "./hello.js"
    //  [bye] = "./bye.js"
    // }
    if (!element.namesMappingKey("scripts")) return null

    return withExecutorActions(AllIcons.Actions.Execute)
  }

  private fun detectArtifact(element: PsiElement): Info? {
    // every artifact the manifest declares is a build target named after itself, e.g:
    // artifacts {
    //  ["app"] = new Jvm.Jar { main = "app.MainKt" }
    //  ["image"] = new Container.ContainerImage { from { "app" } }
    // }
    if (!element.namesMappingKey("artifacts")) return null

    // a Native Image binary is the one artifact the IDE can also start, so its key gets the run icon and the
    // executors of the configuration running it on top of the build's own actions
    if (element.moduleMappingKey("artifacts")?.nativeImage() != null) {
      return Info(AllIcons.Actions.Execute, buildActions() + nativeImageActions())
    }

    return Info(AllIcons.Actions.Compile, buildActions())
  }

  /**
   * Returns whether this element carries the icon for the key of an entry in the module-level mapping named
   * [property].
   */
  private fun PsiElement.namesMappingKey(property: String): Boolean {
    if (moduleMappingKey(property) == null) return false

    // only the first leaf of the key gets the icon, to avoid stacked action tooltips
    return parent.firstLeaf() == this
  }

  /**
   * Actions offered by an artifact's gutter icon: every extra action, but only the run executor.
   *
   * `elide build <artifact>` assembles an artifact. It opens no JDWP server for the debugger to attach to, and the
   * CLI writes the coverage reports the IDE displays for `elide test` alone, so either executor would promise an
   * action this run cannot deliver.
   */
  private fun buildActions(): Array<AnAction> = ExecutorAction.getActionList(0)
    .filterNot { it is ExecutorAction && it.executor.id != DefaultRunExecutor.EXECUTOR_ID }
    .toTypedArray()

  /**
   * Actions offered by the binary a Native Image artifact produces: the run and debug executors of the second
   * configuration the context yields, which is the one starting that binary.
   *
   * Only the executors are taken. The extra actions — editing the configuration, saving it — are contributed by the
   * artifact's build configuration already, and would be duplicated by a second set pointing at the same menu.
   */
  private fun nativeImageActions(): Array<AnAction> = ExecutorAction.getActionList(NATIVE_IMAGE_CONFIGURATION_ORDER)
    .filter { it is ExecutorAction && it.executor.id in NATIVE_IMAGE_EXECUTORS }
    .toTypedArray()

  private companion object {
    /**
     * Position of the Native Image run in the configurations a Native Image key yields.
     *
     * The build of the artifact comes first: `ElideManifestRunConfigurationProducer` is preferred over the Native
     * Image producer, which in turn is preferred over everything foreign.
     */
    private const val NATIVE_IMAGE_CONFIGURATION_ORDER = 1

    /** Executors a Native Image binary is offered under; it collects no coverage. */
    private val NATIVE_IMAGE_EXECUTORS = setOf(
      DefaultRunExecutor.EXECUTOR_ID,
      DefaultDebugExecutor.EXECUTOR_ID,
    )
  }
}
