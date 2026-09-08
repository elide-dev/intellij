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
package dev.elide.intellij.execution.test

import com.intellij.execution.Executor
import com.intellij.execution.Location
import com.intellij.execution.testframework.JavaAwareTestConsoleProperties
import com.intellij.execution.testframework.JavaTestLocator
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration
import com.intellij.pom.Navigatable
import dev.elide.intellij.Constants
import javax.swing.tree.TreeSelectionModel

/**
 * Settings of the test tree an `elide test` run publishes into: how its nodes are navigated to, and how many of
 * them can be selected at once.
 *
 * The framework name keys the tree's persisted settings (splitter position, sorting, "hide passed tests"), so it
 * has to stay stable across releases.
 */
internal class ElideTestConsoleProperties(
  configuration: ExternalSystemRunConfiguration,
  executor: Executor,
) : SMTRunnerConsoleProperties(configuration, FRAMEWORK_NAME, executor) {
  init {
    // `hidePassedTests` is on in the platform's own defaults, which would leave a green run showing nothing but a
    // root node and the run's log: the whole point here is that the results are visible, so the tree starts out
    // showing them and the toolbar toggle is left to the user from then on
    setIfUndefined(HIDE_PASSED_TESTS, false)
  }

  /**
   * Nodes are addressed by id, not by name: [ElideTestSession] keys them on the numbers TAP tags a test's start and
   * output with, so two tests may share a display name and a run may interleave them.
   */
  override fun isIdBasedTestTree(): Boolean = true

  /**
   * Resolves a node's location hint to its declaration.
   *
   * Elide's JVM engine reports tests as binary class names and JUnit display names, which is what
   * [ElideTestSession] builds `java:suite` and `java:test` hints from; guest tests carry no hint and resolve to
   * nothing.
   */
  override fun getTestLocator(): SMTestLocator = JavaTestLocator.INSTANCE

  /** Jumps to the deepest frame of a failure's stack trace that belongs to the project. */
  override fun getErrorNavigatable(location: Location<*>, stacktrace: String): Navigatable? =
    JavaAwareTestConsoleProperties.getStackTraceErrorNavigatable(location, stacktrace)

  override fun getSelectionMode(): Int = TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION

  internal companion object {
    /** Name the test tree files its settings and its splitter position under. */
    val FRAMEWORK_NAME: String = Constants.SYSTEM_ID.readableName
  }
}
