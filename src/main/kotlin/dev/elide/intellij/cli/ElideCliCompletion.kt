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

import com.intellij.util.execution.ParametersListUtil
import dev.elide.intellij.cli.ElideCli.FlagValue
import dev.elide.intellij.project.model.ElideEntrypointInfo
import dev.elide.intellij.project.model.fullCommandLine

/**
 * Completion variants for an Elide command line, derived from the [ElideCli] schema.
 *
 * The functions here are pure and take the tokens *preceding* the word being completed, so the run configuration
 * editor and the "run anything" popup offer the same suggestions for the same input.
 */
object ElideCliCompletion {
  /**
   * A single completion variant.
   *
   * @param text Text inserted when the variant is chosen.
   * @param descriptionKey Key of the variant's description in the plugin's string bundle.
   * @param descriptionArg Argument formatted into the description, if it takes one.
   */
  data class Variant(val text: String, val descriptionKey: String, val descriptionArg: String? = null)

  /**
   * Returns the tokens of [text] that precede the word at [caret], which is the context [tasks] and [flags] complete
   * against.
   *
   * The word under the caret is excluded: it is the prefix the completion popup filters variants by, and is not yet
   * a decided argument. A caret that sits right after whitespace starts a new word, so nothing is dropped.
   */
  fun context(text: String, caret: Int): List<String> {
    val offset = caret.coerceIn(0, text.length)
    val tokens = ParametersListUtil.parse(text.substring(0, offset))

    return if (text.getOrNull(offset - 1)?.isWhitespace() != false) tokens else tokens.dropLast(1)
  }

  /**
   * Returns the commands, entrypoints and positional arguments that may follow [typed].
   *
   * With nothing typed yet, the project's [entrypoints] come first, followed by every command. After a command, only
   * the arguments that command accepts are offered.
   */
  fun tasks(typed: List<String>, entrypoints: List<ElideEntrypointInfo>): List<Variant> {
    val invocation = ElideCli.parse(typed)
    if (invocation.passthroughIndex >= 0 || typed.endsWithFlagValue(invocation)) return emptyList()

    val command = invocation.command ?: return entrypoints.map { it.variant(it.fullCommandLine) } +
      ElideCli.COMMANDS.map { Variant(it.name, it.descriptionKey) }

    // `elide run` takes a file or a named manifest script as its positional; the project's JVM entrypoint is what a
    // bare `run` resolves to, so it has no positional form of its own
    if (command == ElideCli.RUN) {
      val positionals = entrypoints.filter {
        it.kind == ElideEntrypointInfo.Kind.Script || it.kind == ElideEntrypointInfo.Kind.Generic
      }

      return positionals.map { it.variant(ParametersListUtil.join(it.value)) } + passthroughVariant()
    }

    return command.positionals.map { Variant(it, "cli.positional.${command.name}.$it") } +
      if (command.passthrough) passthroughVariant() else emptyList()
  }

  /**
   * Returns the flags accepted at the end of [typed], omitting those already given unless they are repeatable.
   *
   * Short forms are only offered when [includeShort] is set; the completion table shows long forms alone, since it
   * lists one row per variant and the short forms would double its length.
   */
  fun flags(typed: List<String>, includeShort: Boolean): List<Variant> {
    val invocation = ElideCli.parse(typed)
    if (invocation.passthroughIndex >= 0 || typed.endsWithFlagValue(invocation)) return emptyList()

    return invocation.applicableFlags()
      .filter { flag -> flag.repeatable || typed.none(flag::matches) }
      .flatMap { it.variants(includeShort) }
  }

  /** Returns the flags accepted by this invocation's command, or the root ones when it names none. */
  private fun ElideCli.Invocation.applicableFlags(): List<ElideCli.Flag> {
    val declared = command?.flags ?: ElideCli.ROOT_ONLY_FLAGS
    return (declared + ElideCli.GLOBAL_FLAGS).distinct()
  }

  /** Returns whether the last token is a flag whose value is the word currently being completed. */
  private fun List<String>.endsWithFlagValue(invocation: ElideCli.Invocation): Boolean {
    val last = lastOrNull() ?: return false
    return invocation.applicableFlags().any { it.takesNextToken(last) }
  }

  /** Returns the ways this flag can be written, most specific first. */
  private fun ElideCli.Flag.variants(includeShort: Boolean): List<Variant> {
    val forms = buildList {
      if (includeShort) shortOption?.let { add(it) }

      when (value) {
        // a value-less flag and a `--flag value` flag are both written as the bare option
        FlagValue.NONE, FlagValue.REQUIRED, FlagValue.OPTIONAL_EQUALS -> add(option)
        // the CLI rejects `--flag value` for these, so the `=` is part of the variant
        FlagValue.REQUIRED_EQUALS -> add("$option=")
      }

      values.mapTo(this) { "$option=$it" }
    }

    return forms.map { Variant(it, descriptionKey) }
  }

  private fun ElideEntrypointInfo.variant(text: String): Variant =
    Variant(text, "execution.completion.tasks.entrypoint.description", descriptiveName)

  private fun passthroughVariant(): List<Variant> =
    listOf(Variant(ElideCli.PASSTHROUGH, "execution.completion.tasks.passthrough.description"))
}
