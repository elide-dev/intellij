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
import com.intellij.openapi.util.io.FileUtil
import com.intellij.rt.coverage.data.LineData
import com.intellij.rt.coverage.data.ProjectData
import dev.elide.intellij.Constants
import java.io.File
import java.io.IOException
import java.util.TreeMap

/**
 * Reads the coverage report the plugin merges out of an Elide run, which is LCOV.
 *
 * Guest coverage is written as LCOV by the CLI; JVM execution data and XML are converted by [ElideCoverageReports].
 * A record is keyed by absolute source path, which is what [ElideCoverageEngine] answers for a file in the editor.
 * Unlike the platform's line-only LCOV reader, this preserves branch counters for partial-line highlighting.
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
    return try {
      SuccessCoverageLoadingResult(readCoverageData(sessionDataFile))
    } catch (cause: IOException) {
      FailedCoverageLoadingResult(cause, false)
    } catch (cause: RuntimeException) {
      // Imported reports may be malformed; keep the failure in the coverage UI instead of throwing from loading.
      FailedCoverageLoadingResult(cause, false)
    }
  }

  /** Reads lines and LCOV branch identities together, merging duplicate source records without losing branches. */
  private fun readCoverageData(file: File): ProjectData {
    val files = linkedMapOf<String, TreeMap<Int, CoverageLine>>()
    var current: TreeMap<Int, CoverageLine>? = null
    var function: String? = null
    file.bufferedReader().useLines { records ->
      for (record in records) {
        when {
          record.startsWith("SF:") -> {
            val path = FileUtil.toCanonicalPath(record.substring(3))
            current = files.getOrPut(path) { TreeMap() }
            function = null
          }
          record.startsWith("FN:") -> function = record.substringAfter(',')
          record.startsWith("DA:") -> {
            val fields = record.substring(3).split(',')
            val number = fields[0].toInt()
            require(number > 0) { "Invalid coverage line: $number" }
            val line = checkNotNull(current) { "Line outside a source record" }
              .getOrPut(number) { CoverageLine(LineData(number, function)) }
            line.data.hits += fields[1].toInt().coerceAtLeast(0)
          }
          record.startsWith("BRDA:") -> {
            val fields = record.substring(5).split(',')
            require(fields.size == 4) { "Invalid branch record: $record" }
            val number = fields[0].toInt()
            require(number > 0) { "Invalid coverage line: $number" }
            val line = checkNotNull(current) { "Branch outside a source record" }
              .getOrPut(number) { CoverageLine(LineData(number, function)) }
            val branches = line.branches ?: linkedMapOf<String, Int>().also { line.branches = it }
            val key = "${fields[1]},${fields[2]}"
            val hits = if (fields[3] == "-") 0 else fields[3].toInt().coerceAtLeast(0)
            branches[key] = (branches[key] ?: 0) + hits
          }
          record == "end_of_record" -> current = null
        }
      }
    }
    check(current == null) { "Unterminated coverage source record" }

    val project = ProjectData()
    for ((path, sourceLines) in files) {
      val lines = arrayOfNulls<LineData>((sourceLines.lastEntry()?.key ?: 0) + 1)
      for ((number, line) in sourceLines) {
        val branches = line.branches
        if (!branches.isNullOrEmpty()) {
          // IntelliJ counts switch keys as branches, but excludes the default from BranchData. Represent every
          // LCOV outcome as a key; the synthetic default follows line hits so it cannot invent a missed branch.
          val counts = branches.values.iterator()
          line.data.addSwitch(0, IntArray(branches.size) { it }).apply {
            for (index in hits.indices) hits[index] = counts.next()
            defaultHits = line.data.hits
          }
          line.data.fillArrays()
        }
        lines[number] = line.data
      }
      project.getOrCreateClassData(path).setLines(lines)
    }
    return project
  }

  private class CoverageLine(val data: LineData) {
    var branches: MutableMap<String, Int>? = null
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
