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

import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.target.LanguageRuntimeType
import com.intellij.execution.target.TargetEnvironmentAwareRunProfile
import com.intellij.execution.target.TargetEnvironmentConfiguration
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration
import com.intellij.openapi.project.Project
import com.intellij.util.execution.ParametersListUtil
import dev.elide.intellij.Constants
import dev.elide.intellij.project.model.ElideEntrypointInfo
import dev.elide.intellij.project.model.ElideEntrypointInfo.Kind
import org.jdom.Element
import javax.swing.Icon

/** Elide run configuration type. */
class ElideRunConfiguration(
  project: Project,
  factory: ConfigurationFactory,
  name: String
) : ExternalSystemRunConfiguration(
  /* externalSystemId = */ Constants.SYSTEM_ID,
  /* project = */ project,
  /* factory = */ factory,
  /* name = */ name,
), TargetEnvironmentAwareRunProfile {
  var entrypointKind: ElideEntrypointInfo.Kind? = null
  var entrypointValue: String? = null

  /**
   * Whether this configuration can be debugged over JDWP, which decides if the IDE offers the "Debug" action for it.
   *
   * Only a JVM entrypoint started by `elide run` exposes a JDWP server; for guest languages the same flag activates
   * the Chrome DevTools or Debug Adapter protocol instead, neither of which the IDE's Java debugger speaks.
   */
  val supportsDebugger: Boolean
    get() = supportsDebugger(settings.taskNames, entrypointKind, entrypointValue)

  /**
   * The Elide command line backing this configuration.
   *
   * Parsing goes through [ParametersListUtil] so quoted arguments (entrypoint paths or script arguments containing
   * spaces) survive a round trip through the editor.
   */
  var rawCommandLine: String
    get() = ParametersListUtil.join(settings.taskNames)
    set(value) {
      settings.taskNames = ParametersListUtil.parse(value)
    }

  override fun getIcon(): Icon {
    return Constants.Icons.ELIDE
  }

  override fun canRunOn(target: TargetEnvironmentConfiguration): Boolean {
    return true
  }

  override fun getDefaultLanguageRuntimeType(): LanguageRuntimeType<*>? {
    return LanguageRuntimeType.EXTENSION_NAME.findExtension(ElideRuntimeType::class.java)
  }

  override fun getDefaultTargetName(): String? {
    return options.remoteTarget
  }

  override fun setDefaultTargetName(targetName: String?) {
    options.remoteTarget = targetName
  }

  override fun getState(executor: Executor, env: ExecutionEnvironment): RunProfileState? {
    // keyed on the runner rather than the executor: only ElideDebugRunner knows how to attach to the JDWP server the
    // debugger flag starts, and the platform's own debug runner needs the state it builds itself
    if (ElideDebugRunner.RUNNER_ID != env.runner.runnerId) return super.getState(executor, env)

    // the debugger flag is added to a *copy* of the settings: the command line the user typed is persisted as-is, and
    // re-running the same configuration without the debugger must not inherit the flag
    val debugSettings = settings.clone().apply { taskNames = debuggerCommandLine(taskNames) }

    // `debug = false` keeps the platform from allocating the debug port and fork socket its own debug runner needs;
    // ElideDebugRunnableState brings the connection the CLI's JDWP server expects instead
    return ElideDebugRunnableState(debugSettings, project, this, env).also { copyUserDataTo(it) }
  }

  override fun readExternal(element: Element) {
    super.readExternal(element)
    element.readExternalString(ENTRYPOINT_VALUE_KEY) { entrypointValue = it }
    element.readExternalString(ENTRYPOINT_KIND_KEY) { kind ->
      // tolerate kinds written by a newer plugin version: an unknown name must not break workspace loading
      entrypointKind = ElideEntrypointInfo.Kind.entries.find { it.name == kind }
    }
  }

  override fun writeExternal(element: Element) {
    super.writeExternal(element)
    entrypointValue?.let { element.writeExternalString(ENTRYPOINT_VALUE_KEY, it) }
    entrypointKind?.let { element.writeExternalString(ENTRYPOINT_KIND_KEY, it.name) }
  }

  private fun Element.writeExternalString(key: String, value: String) {
    val childElement = Element(key)
    childElement.setText(value)
    this.addContent(childElement)
  }

  private fun Element.readExternalString(key: String, consumer: (String) -> Unit) {
    val childElement = getChild(key) ?: return
    val value = childElement.getText()
    consumer(value)
  }

  internal companion object {
    private const val ENTRYPOINT_VALUE_KEY = "elideEntrypointValue"
    private const val ENTRYPOINT_KIND_KEY = "elideEntrypointKind"

    /** Entrypoint file extensions that run on the JVM, and are therefore debuggable over JDWP. */
    private val JVM_ENTRYPOINT_EXTENSIONS = setOf("kt", "kts", "java", "jar", "class")

    /**
     * Returns [taskNames] with [Constants.FLAG_DEBUGGER] inserted right after the `run` command, where it precedes
     * both the entrypoint and any `--` separated script arguments.
     *
     * Command lines without a `run` command receive the flag in leading position, which the CLI also accepts.
     */
    fun debuggerCommandLine(taskNames: List<String>): List<String> {
      if (taskNames.contains(Constants.FLAG_DEBUGGER)) return taskNames

      return taskNames.toMutableList().apply {
        add(indexOf(Constants.COMMAND_RUN) + 1, Constants.FLAG_DEBUGGER)
      }
    }

    /** Returns whether the entrypoint described by [taskNames], [kind] and [value] runs on a debuggable JVM. */
    fun supportsDebugger(taskNames: List<String>, kind: Kind?, value: String?): Boolean {
      if (taskNames.firstOrNull { !it.startsWith('-') } != Constants.COMMAND_RUN) return false

      return when (kind) {
        Kind.JvmMainClass -> true
        // manifest scripts are shell commands, not guest code, so there is nothing to attach to
        Kind.Script -> false
        // `elide test` does not yet support --debugger; flip this branch when the CLI gains it
        Kind.JvmTest -> false
        Kind.Generic -> value?.substringAfterLast('.')?.lowercase() in JVM_ENTRYPOINT_EXTENSIONS
        // hand-written command lines carry no entrypoint metadata; the CLI resolves the manifest entrypoint, which
        // for a JVM project is a main class
        null -> true
      }
    }
  }
}
