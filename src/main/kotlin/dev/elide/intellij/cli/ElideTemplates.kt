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

import dev.elide.intellij.Constants
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A single answerable question in a project template, corresponding to a `parameters` entry in the template's
 * `metadata.yaml`.
 */
@Serializable data class ElideTemplateParameter(
  val id: String,
  val title: String,
  val description: String = "",
  val default: String = "",
  val attributes: List<String> = emptyList(),
) {
  /** Whether this parameter is a flag rather than free text, and so renders as a checkbox. */
  val isBoolean: Boolean get() = ElideTemplates.ATTRIBUTE_BOOLEAN in attributes
}

/**
 * A project template, or an optional block within one: the CLI models both with the same structure, a template being
 * the root block of its own tree.
 *
 * A block is generated only when [default] (or the user's answer) is `true`, and its [parameters] are then asked in
 * turn; a disabled block contributes nothing, including its nested [blocks].
 */
@Serializable data class ElideTemplate(
  val id: String,
  val title: String,
  val description: String = "",
  val default: Boolean = false,
  val parameters: List<ElideTemplateParameter> = emptyList(),
  val actions: List<String> = emptyList(),
  val blocks: List<ElideTemplate> = emptyList(),
)

/**
 * Decoding entrypoint for the project templates a distribution ships.
 *
 * The templates themselves are embedded in the CLI binary (from `project/samples` at build time), so the plugin never
 * carries a copy: it runs `elide init --templates --json` and decodes the catalog here. Unknown keys are ignored, so a
 * newer Elide that adds fields to the listing still decodes.
 */
object ElideTemplates {
  /** Attribute marking a parameter that must hold a valid Java (or Kotlin) package name. */
  const val ATTRIBUTE_JAVA_PACKAGE = "JavaPackage"

  /** Attribute marking a parameter that must hold a valid Java (or Kotlin) class name. */
  const val ATTRIBUTE_JAVA_CLASS = "JavaClass"

  /** Attribute marking a parameter that holds a boolean. */
  const val ATTRIBUTE_BOOLEAN = "Boolean"

  /** Parameter every template uses for the project's own name, pre-filled from the wizard's project name. */
  const val PROJECT_NAME_PARAMETER = "project_name"

  // mirrors `crates/init/src/validate.rs`; the CLI rejects the same values, only after the wizard has closed
  private val JAVA_PACKAGE = Regex("^[a-z][a-z0-9_]*(\\.[a-z0-9_]+)*[a-z0-9_]*$")
  private val JAVA_CLASS = Regex("^[a-zA-Z_$][a-zA-Z\\d_$]*$")

  private val TemplateJson = Json { ignoreUnknownKeys = true }

  /** Decode the JSON output of `elide init --templates --json` into the template catalog. */
  @JvmStatic fun parse(input: String): List<ElideTemplate> {
    return runCatching { TemplateJson.decodeFromString<List<ElideTemplate>>(input) }
      .getOrElse { throw IllegalStateException("Failed to parse Elide template listing: ${it.message}", it) }
  }

  /**
   * Returns a user-facing description of why [value] is not acceptable for [parameter], or `null` when it is valid.
   *
   * Attributes the plugin does not know are ignored rather than rejected, so a template from a newer Elide remains
   * usable (the CLI validates the answers again anyway).
   */
  @JvmStatic fun validate(parameter: ElideTemplateParameter, value: String): String? {
    for (attribute in parameter.attributes) when (attribute) {
      ATTRIBUTE_JAVA_PACKAGE -> if (!JAVA_PACKAGE.matches(value)) {
        return Constants.Strings["wizard.validation.javaPackage"]
      }
      ATTRIBUTE_JAVA_CLASS -> if (!JAVA_CLASS.matches(value)) {
        return Constants.Strings["wizard.validation.javaClass"]
      }
      ATTRIBUTE_BOOLEAN -> if (value != "true" && value != "false") {
        return Constants.Strings["wizard.validation.boolean"]
      }
    }

    return null
  }
}
