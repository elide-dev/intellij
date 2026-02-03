/*
 * Copyright (c) 2025 Elide Technologies, Inc. All Rights Reserved.
 *
 * PROPRIETARY AND CONFIDENTIAL. This software may contain trade secrets and
 * confidential information of Elide Technologies, Inc.
 *
 * UNAUTHORIZED USE, COPYING, DISTRIBUTION, OR DISCLOSURE IS STRICTLY PROHIBITED.
 * No part of this software may be shared without prior written consent from
 * Elide Technologies, Inc.
 *
 * Contact: engineering@elide.dev
 */

@file:Suppress("unused")

package dev.elide.tooling.web

import dev.elide.tooling.web.Browsers.Companion.defaults
import kotlinx.serialization.Serializable

/**
 * # Browsers
 *
 * Specifies a type hierarchy for understanding browser support requirements within the context of
 * an Elide project. Such strings are expressed in the "Browserslist" format, which is a widely used
 * (loose) standard for specifying browsers.
 *
 * The format looks like this:
 * - A browser: `chrome`
 * - A browser version: `chrome 100`
 * - A browser version range: `chrome 100-120`
 * - A browser with a specific support level: `chrome 100-120 support: es6`
 *
 * And so on. The full specification for this format is available at the
 * [Browserslist documentation](https://browsersl.ist/).
 *
 * ## Usage
 *
 * The default suite of (empty) browser settings can be obtained either via the [defaults] method or
 * the [Defaults] object. Other types exist for specifying browsers, in versioned or unqualified
 * form, and, as applicable, with other support tokens specified.
 */
@Serializable
sealed interface Browsers {
  /**
   * Produce a sequence of constituent [Browser] instances.
   *
   * @return A sequence of [Browser] instances that this [Browsers] instance represents.
   */
  fun asSequence(): Sequence<Browser>

  /**
   * Return the suite of Browserslist tokens that this instance represents.
   *
   * @return An array of Browserslist tokens, which may be empty if no browsers are specified (the
   *   default state).
   */
  fun asTokens(): Set<String>

  /**
   * Check whether this [Browsers] instance "contains" the specified typed [browser] object; this
   * checks whether the specified browser is declared.
   *
   * @param browser Browser to check for.
   * @return `true` if this [Browsers] instance contains the specified browser, `false` otherwise.
   */
  operator fun contains(browser: Browsers): Boolean

  /**
   * Check whether this [Browsers] instance "contains" the specified Browserslist specification
   * string; this checks whether the specified browser is declared.
   *
   * @param spec Browserslist specification to check for.
   * @return `true` if this [Browsers] instance contains the specified Browserslist specification,
   *   `false` otherwise.
   */
  operator fun contains(spec: String): Boolean = browser(spec).let { parsed -> parsed in this }

  /**
   * ## Browser
   *
   * Specifies a type hierarchy representing a single browser instance, potentially with additional
   * metadata (versions or ranges, and so on).
   */
  @Serializable sealed interface Browser : Browsers

  /**
   * Default implementation of a [Browser] instance, which just represents the spec via a string.
   */
  @Serializable
  data class DefaultBrowser(private val token: String) : Browser {
    override fun asTokens(): Set<String> = setOf(token)

    override fun asSequence(): Sequence<Browser> = sequenceOf(this)

    // Expected to be a single token when compared this way.
    override fun contains(browser: Browsers): Boolean = browser.asTokens().all { it == token }
  }

  /**
   * ## Browser List
   *
   * Specifies a suite of browser information, i.e., collectively, a suite of [Browser] instances;
   * browser lists can be parsed from a single or multiple string, consisting of Browserslist
   * tokens and expressions.
   */
  @Serializable sealed interface BrowserList : Browsers

  /** Default implementation of a [BrowserList] instance, which holds more than one [Browser]. */
  @Serializable
  data class DefaultBrowserList(private val suite: List<Browser>) :
    BrowserList {
    override fun asTokens(): Set<String> = suite.flatMap { it.asTokens() }.toSet()

    override fun asSequence(): Sequence<Browser> = suite.asSequence()

    override fun contains(browser: Browsers): Boolean {
      val other = browser.asTokens().sorted()
      val self = asTokens().sorted()
      return other.all { it in self }
    }
  }

  /**
   * ## Browser List Builder
   *
   * Context used for static builder methods which assemble a browser list from typed objects. The
   * [specify] method is used to append browsers to the builder, and the [build] method is used to
   * finalize the builder and produce an immutable [BrowserList] instance.
   *
   * The browser list builder is itself a [Browsers] instance, so it can be queried as it is being
   * built.
   */
  interface BrowserListBuilder {
    /**
     * Add a [Browser] to this builder.
     *
     * @param browser Browser to add to this builder.
     * @return This builder, for chaining.
     */
    fun specify(browser: Browser): BrowserListBuilder

    /**
     * Add a [BrowserList] to this builder.
     *
     * @param browsers Browsers to add to this builder.
     * @return This builder, for chaining.
     */
    fun specify(browsers: BrowserList): BrowserListBuilder

    /**
     * Add a [Browser] to this builder.
     *
     * @param browser Browser to add to this builder.
     * @return This builder, for chaining.
     */
    fun specify(browser: String): BrowserListBuilder =
      when (',' in browser) {
        true -> specify(parse(browser.split(',').map { it.trim() }.asSequence()))
        false -> specify(parse(browser))
      }

    /**
     * Add a [Browser] to this builder.
     *
     * @return This builder, for chaining.
     * @receiver Browser to add to this builder.
     */
    operator fun Browser.unaryPlus(): BrowserListBuilder = specify(this)

    /**
     * Add a [BrowserList] to this builder.
     *
     * @return This builder, for chaining.
     * @receiver Browsers to add to this builder.
     */
    operator fun BrowserList.unaryPlus(): BrowserListBuilder = specify(this)

    /**
     * Add a parsed Browser list string to this builder.
     *
     * @return This builder, for chaining.
     * @receiver Browsers to add to this builder.
     */
    operator fun String.unaryPlus(): BrowserListBuilder = specify(this)

    /**
     * Build this builder into an immutable [BrowserList] instance.
     *
     * @return A new [BrowserList] instance containing the browsers specified by this builder.
     */
    fun build(): BrowserList
  }

  /**
   * Default implementation of a [BrowserListBuilder] instance, which can be used to build a
   * [BrowserList] from scratch.
   */
  class DefaultBrowserListBuilder
  internal constructor(private val browsers: MutableList<Browser> = mutableListOf()) :
    BrowserListBuilder {
    override fun specify(browser: Browser): BrowserListBuilder = apply { browsers.add(browser) }

    override fun specify(browsers: BrowserList): BrowserListBuilder = apply {
      browsers.asSequence().forEach { browser -> specify(browser) }
    }

    override fun build(): BrowserList = DefaultBrowserList(suite = browsers)
  }

  /**
   * ## Default Browsers
   *
   * Default settings for [Browsers] when no other settings are specified; typically, an empty set
   * is passed, which allows lower-level layers to set defaults.
   */
  @Serializable
  data object Defaults : Browsers {
    override fun asTokens(): Set<String> = emptySet()

    override fun asSequence(): Sequence<Browser> = emptySequence()

    override fun contains(browser: Browsers): Boolean = false
  }

  /** Methods for specifying, or obtaining, [Browsers] instances. */
  companion object {
    /** @return Default [Browsers] configuration. */
    @JvmStatic fun defaults(): Browsers = Defaults

    /** @return Builder of [BrowserList] instances. */
    @JvmStatic fun builder(): BrowserListBuilder = DefaultBrowserListBuilder()

    /** @return Built [BrowserList] specification. */
    @JvmStatic
    fun build(builder: BrowserListBuilder.() -> Unit): BrowserList =
      builder().apply(builder).build()

    /** @return Built [BrowserList] specification from the specified string or strings. */
    @JvmStatic
    fun browser(single: String): Browser {
      require(',' !in single) {
        "Single-browser token or expression must not contain a comma: $single"
      }
      return DefaultBrowser(single)
    }

    /** @return Built [BrowserList] specification from the specified string or strings. */
    @JvmStatic
    fun parse(suite: Sequence<String>): BrowserList =
      sequence {
        suite.forEach { token ->
          if (',' in token) {
            yieldAll(token.split(',').map { it.trim() })
          } else {
            yield(token)
          }
        }
      }
        .let { seq -> build { seq.forEach { token -> specify(browser(token)) } } }

    /** @return Built [BrowserList] specification from the specified string or strings. */
    @JvmStatic fun parse(suite: Iterable<String>): BrowserList = parse(suite.asSequence())

    /** @return Built [BrowserList] specification from the specified string or strings. */
    @JvmStatic
    fun parse(first: String, vararg additional: String): BrowserList =
      sequence {
        yield(first)
        yieldAll(additional.asSequence())
      }
        .let { parse(it) }
  }
}
