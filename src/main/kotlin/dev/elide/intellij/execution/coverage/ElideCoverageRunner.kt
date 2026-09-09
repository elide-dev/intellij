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

import com.intellij.coverage.CoverageEngine
import com.intellij.coverage.CoverageLoadErrorReporter
import com.intellij.coverage.CoverageLoadingResult
import com.intellij.coverage.CoverageRunner
import com.intellij.coverage.CoverageSuite
import com.intellij.coverage.FailedCoverageLoadingResult
import com.intellij.coverage.SuccessCoverageLoadingResult
import com.intellij.coverage.lcov.LcovSerializationUtils
import dev.elide.intellij.Constants
import java.io.File
import java.io.IOException

/**
 * Reads the coverage report the plugin merges out of an Elide run, which is LCOV.
 *
 * Both halves of an Elide run arrive here in the same format: guest coverage is written as LCOV by the CLI, and JVM
 * coverage is converted from JaCoCo execution data by [ElideCoverageReports] before the report is written. A record
 * is keyed by absolute source path, which is what [ElideCoverageEngine] answers for a file in the editor.
 */
class ElideCoverageRunner : CoverageRunner() {
  override fun getId(): String = ID

  override fun getPresentableName(): String = Constants.Strings["coverage.runner.name"]

  override fun getDataFileExtension(): String = DATA_FILE_EXTENSION

  override fun acceptsCoverageEngine(engine: CoverageEngine): Boolean = engine is ElideCoverageEngine

  override fun loadCoverageData(
    sessionDataFile: File,
    baseCoverageSuite: CoverageSuite?,
    reporter: CoverageLoadErrorReporter,
  ): CoverageLoadingResult {
    val report = try {
      LcovSerializationUtils.readLCOV(listOf(sessionDataFile))
    } catch (cause: IOException) {
      return FailedCoverageLoadingResult(cause, false)
    } catch (cause: RuntimeException) {
      // the reader answers a malformed report with a plain runtime exception; a coverage file the plugin did not
      // write (an LCOV report imported by hand, say) is the realistic way to reach it
      return FailedCoverageLoadingResult(cause, false)
    }

    // paths in the report are already local and absolute: the CLI writes the sources it compiled, and the JVM half
    // is resolved against the project's source roots when the report is merged
    return SuccessCoverageLoadingResult(LcovSerializationUtils.convertToProjectData(report) { it })
  }

  internal companion object {
    /** Identifier persisted in run configurations and coverage suites; changing it orphans both. */
    const val ID: String = "ElideCoverage"

    /** Extension of the merged report, the one LCOV conventionally uses. */
    private const val DATA_FILE_EXTENSION = "info"

    /** The registered instance of this runner. */
    val instance: ElideCoverageRunner get() = getInstance(ElideCoverageRunner::class.java)
  }
}
