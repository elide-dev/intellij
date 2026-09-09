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
package dev.elide.intellij.execution.coverage

import com.intellij.coverage.BaseCoverageSuite
import com.intellij.coverage.CoverageEngine
import com.intellij.coverage.CoverageFileProvider
import com.intellij.coverage.CoverageRunner
import com.intellij.openapi.project.Project

/** One merged Elide coverage report, as the IDE's suite chooser and coverage view see it. */
class ElideCoverageSuite : BaseCoverageSuite {
  private val engine: ElideCoverageEngine

  /** Empty suite, restored from the IDE's persisted suite list. */
  constructor(engine: ElideCoverageEngine) : super() {
    this.engine = engine
  }

  constructor(
    name: String,
    project: Project,
    runner: CoverageRunner,
    fileProvider: CoverageFileProvider,
    timestamp: Long,
    engine: ElideCoverageEngine,
  ) : super(name, project, runner, fileProvider, timestamp) {
    this.engine = engine
  }

  override fun getCoverageEngine(): CoverageEngine = engine

  /**
   * An Elide run measures the tests it executes along with everything they exercise, and both are reported: leaving
   * test folders out would show the run's own files as covered by nothing.
   */
  override fun isTrackTestFolders(): Boolean = true
}
