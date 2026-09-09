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

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.setLastModifiedTime
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins which files an Elide run is understood to have produced, and what the IDE reads out of them.
 *
 * Both halves matter for correctness rather than presentation: a report picked up from an earlier run shows
 * coverage the current one never measured, and a merge that drops a record hides a file that was covered.
 */
class ElideCoverageReportsTest {
  @TempDir lateinit var root: Path

  @Test fun `both report formats are found under the output directory`() {
    val lcov = write("reports/coverage/js/lcov.info", "TN:\nSF:/src/app.ts\nDA:1,1\nend_of_record\n")
    val exec = write("artifacts/coverage/jvm/jvm-test/jvm-test.exec", "not really jacoco")
    // neither the CLI's other reports nor the merged one the plugin writes are coverage the CLI produced
    write("reports/tests/js/test/TEST-app.xml", "<testsuite/>")
    write("reports/coverage/ide.info", "TN:\nSF:/src/stale.ts\nend_of_record\n")

    val artifacts = ElideCoverageReports.discover(root)

    assertEquals(listOf(lcov), artifacts.lcov)
    assertEquals(listOf(exec), artifacts.exec)
  }

  @Test fun `reports older than the run that asked for them are ignored`() {
    val report = write("reports/coverage/js/lcov.info", "TN:\nSF:/src/app.ts\nDA:1,1\nend_of_record\n")
    report.setLastModifiedTime(FileTime.fromMillis(1_000L))

    assertTrue(ElideCoverageReports.discover(root, modifiedSince = 2_000L).isEmpty)
    assertEquals(listOf(report), ElideCoverageReports.discover(root, modifiedSince = 500L).lcov)
  }

  @Test fun `a project with no coverage yields nothing to attach`() {
    val artifacts = ElideCoverageReports.discover(root)

    assertTrue(artifacts.isEmpty)
    assertNull(ElideCoverageReports.merge(root, artifacts, sourceRoots = emptyList()))
  }

  @Test fun `guest reports are merged into one, whether or not they end in a newline`() {
    // one engine per file is what the CLI writes; a record cut off by the next one's `TN:` line is lost entirely
    write("reports/coverage/js/lcov.info", "TN:\nSF:/src/app.ts\nDA:1,1\nend_of_record")
    write("reports/coverage/python/lcov.info", "TN:\nSF:/src/app.py\nDA:2,0\nend_of_record\n")

    val report = checkNotNull(ElideCoverageReports.merge(root, ElideCoverageReports.discover(root), emptyList()))

    assertEquals(2, report.lines().count { it == "end_of_record" })
    assertTrue(report.contains("SF:/src/app.ts\nDA:1,1\nend_of_record\n"))
    assertTrue(report.contains("SF:/src/app.py\nDA:2,0\nend_of_record\n"))
  }

  @Test fun `sources are named under the project path the IDE opened`() {
    // the CLI resolves the project directory before recording a source, so a project reached through a symlink is
    // described in terms of the link's target: a record naming it matches no file the IDE knows
    val target = root.resolve("target").createDirectories()
    val link = root.resolve("link").also { Files.createSymbolicLink(it, target) }

    val report = link.resolve(".dev/reports/coverage/js/lcov.info")
    report.parent.createDirectories()
    report.writeText("TN:\nSF:${target.toRealPath().absolutePathString()}/src/app.ts\nDA:1,1\nend_of_record\n")

    val merged = checkNotNull(ElideCoverageReports.merge(link, ElideCoverageReports.discover(link), emptyList()))

    assertTrue(merged.contains("SF:${link.absolutePathString()}/src/app.ts\n"), merged)
  }

  private fun write(relative: String, content: String): Path {
    val path = root.resolve(".dev").resolve(relative)
    path.parent.createDirectories()
    path.writeText(content)

    return path
  }
}
