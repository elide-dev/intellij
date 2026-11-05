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
import org.jacoco.core.instr.Instrumenter
import org.jacoco.core.runtime.LoggerRuntime
import org.jacoco.core.runtime.RuntimeData
import org.jacoco.core.tools.ExecFileLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
import kotlin.io.path.setLastModifiedTime
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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

  @Test fun `JVM execution data is found where Elide 1_6 writes it`() {
    // 1.6 moved the JaCoCo files from `artifacts/coverage` to `reports/coverage`, beside the guest reports
    val exec = write("reports/coverage/jvm/jvm-test/jvm-test.exec", "not really jacoco")
    write("reports/coverage/jvm/jvm-test/jvm-test.xml", "<report/>")

    val artifacts = ElideCoverageReports.discover(root)

    assertEquals(listOf(exec), artifacts.exec)
    assertEquals(emptyList(), artifacts.lcov)
    assertEquals(emptyList(), artifacts.xml)
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

  @Test fun `XML-only reports are discovered in both current and legacy layouts`() {
    val current = write("reports/coverage/jvm/current/current.xml", "<report/>")
    val legacy = write("artifacts/coverage/jvm/legacy/legacy.xml", "<report/>")
    val lcov = write("artifacts/coverage/js/lcov.info", "TN:\n")

    val artifacts = ElideCoverageReports.discover(root)

    assertEquals(listOf(current, legacy), artifacts.xml)
    assertEquals(listOf(lcov), artifacts.lcov)
    assertTrue(!artifacts.isEmpty)
    val fingerprint = artifacts.fingerprint()
    current.writeText("<report name=\"changed\"/>")
    assertNotEquals(fingerprint, artifacts.fingerprint())
  }

  @Test fun `execution data wins over XML copies even across report layouts`() {
    val exec = write("reports/coverage/jvm/test/test.exec", "execution data")
    write("reports/coverage/jvm/test/test.xml", "<report/>")
    write("artifacts/coverage/jvm/test/test.xml", "<report/>")
    val independent = write("reports/coverage/jvm/run/run.xml", "<report/>")

    val artifacts = ElideCoverageReports.discover(root)

    assertEquals(listOf(exec), artifacts.exec)
    assertEquals(listOf(independent), artifacts.xml)
  }

  @Test fun `only the newest copy of a task report is retained across layouts`() {
    val older = write("artifacts/coverage/jvm/test/test.xml", "<report/>")
    val newer = write("reports/coverage/jvm/test/test.xml", "<report/>")
    older.setLastModifiedTime(FileTime.fromMillis(1_000))
    newer.setLastModifiedTime(FileTime.fromMillis(2_000))

    assertEquals(listOf(newer), ElideCoverageReports.discover(root).xml)
  }

  @Test fun `an old exec does not suppress XML from the current run`() {
    write("reports/coverage/jvm/test/test.exec", "old execution data")
      .setLastModifiedTime(FileTime.fromMillis(1_000))
    val xml = write("reports/coverage/jvm/test/test.xml", "<report/>")
    xml.setLastModifiedTime(FileTime.fromMillis(3_000))

    val artifacts = ElideCoverageReports.discover(root, modifiedSince = 2_000)

    assertEquals(emptyList(), artifacts.exec)
    assertEquals(listOf(xml), artifacts.xml)
    assertTrue(ElideCoverageReports.discover(root, modifiedSince = 4_000).isEmpty)
  }

  @Test fun `a current exec never falls back to stale XML when classes are absent`() {
    val exec = write("reports/coverage/jvm/test/test.exec", "")
    ExecFileLoader().save(exec.toFile(), false)
    write("reports/coverage/jvm/test/test.xml", xml("Example.kt"))
      .setLastModifiedTime(FileTime.fromMillis(1_000))
    val sources = root.resolve("src").createDirectories()
    sources.resolve("Example.kt").writeText("fun example() = 1")

    assertNull(ElideCoverageReports.merge(root, ElideCoverageReports.discover(root), listOf(sources)))
  }

  @Test fun `XML resolves package paths and preserves covered missed and partial branch counts`() {
    val sources = root.resolve("src").createDirectories()
    val source = sources.resolve("example/A&B.kt")
    source.parent.createDirectories()
    source.writeText("fun example() = 1")
    write(
      "reports/coverage/jvm/test/test.xml",
      """
      <?xml version="1.0"?>
      <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "https://invalid.example/report.dtd">
      <report name="tests"><group name="module"><package name="example">
        <sourcefile name="A&amp;B.kt">
          <line nr="1" mi="0" ci="4" mb="0" cb="2"/>
          <line nr="2" mi="4" ci="0" mb="2" cb="0"/>
          <line nr="3" mi="2" ci="2" mb="1" cb="1"/>
          <line nr="0" mi="0" ci="1" mb="0" cb="0"/>
        </sourcefile>
        <sourcefile name="Missing.kt"><line nr="1" ci="1"/></sourcefile>
      </package></group></report>
      """.trimIndent(),
    )

    val report = ElideCoverageReports.merge(root, ElideCoverageReports.discover(root), listOf(root, sources))

    assertEquals(
      "SF:${source.absolutePathString()}\n" +
        "DA:1,1\nBRDA:1,0,0,1\nBRDA:1,0,1,1\n" +
        "DA:2,0\nBRDA:2,0,0,0\nBRDA:2,0,1,0\n" +
        "DA:3,1\nBRDA:3,0,0,1\nBRDA:3,0,1,0\nend_of_record\n",
      report,
    )
  }

  @Test fun `XML-only legacy reports resolve the default package without compiled classes`() {
    val sources = root.resolve("src").createDirectories()
    val source = sources.resolve("Example.kt").also { it.writeText("fun example() = 1") }
    write("artifacts/coverage/jvm/test/test.xml", xml("Example.kt"))

    assertEquals(
      "SF:${source.absolutePathString()}\nDA:1,1\nend_of_record\n",
      ElideCoverageReports.merge(root, ElideCoverageReports.discover(root), listOf(sources)),
    )
  }

  @Test fun `XML cannot resolve source paths outside the configured roots`() {
    val sources = root.resolve("src").createDirectories()
    root.resolve("Outside.kt").writeText("fun example() = 1")
    write("reports/coverage/jvm/test/test.xml", xml("../Outside.kt"))

    assertNull(ElideCoverageReports.merge(root, ElideCoverageReports.discover(root), listOf(sources)))
  }

  @Test fun `XML does not expand internal or external entities`() {
    val sources = root.resolve("src").createDirectories()
    sources.resolve("Example.kt").writeText("fun example() = 1")
    val secret = root.resolve("secret.txt").also { it.writeText("Example.kt") }
    for (entity in listOf("\"Example.kt\"", "SYSTEM \"${secret.toUri()}\"")) {
      write(
        "reports/coverage/jvm/test/test.xml",
        "<!DOCTYPE report [<!ENTITY source $entity>]>" + xml("&source;"),
      )
      assertNull(ElideCoverageReports.merge(root, ElideCoverageReports.discover(root), listOf(sources)))
    }
  }

  @Test fun `malformed XML cannot leak a partially read source record`() {
    val sources = root.resolve("src").createDirectories()
    sources.resolve("Example.kt").writeText("fun example() = 1")
    write("reports/coverage/jvm/test/test.xml", xml("Example.kt").removeSuffix("</report>"))

    assertNull(ElideCoverageReports.merge(root, ElideCoverageReports.discover(root), listOf(sources)))
  }

  @Test fun `execution data still supplies line and branch coverage instead of its XML rendering`() {
    val relative = "dev/elide/intellij/execution/coverage"
    val sources = root.resolve("src").createDirectories()
    sources.resolve("$relative/ElideCoverageReportsTest.kt").also {
      it.parent.createDirectories()
      it.writeText("class CoverageSubject")
    }
    val classes = root.resolve(".dev/jvm/classes/$relative").createDirectories()
    val className = "ElideCoverageReportsTest\$CoverageSubject.class"
    CoverageSubject::class.java.getResourceAsStream(className).use { input ->
      Files.copy(checkNotNull(input), classes.resolve(className))
    }
    val exec = write("reports/coverage/jvm/test/test.exec", "")
    ExecFileLoader().save(exec.toFile(), false)
    write("reports/coverage/jvm/test/test.xml", xml("ElideCoverageReportsTest.kt", relative))

    val report = checkNotNull(ElideCoverageReports.merge(root, ElideCoverageReports.discover(root), listOf(sources)))

    assertEquals(1, report.lines().count { it == "end_of_record" })
    assertTrue(report.lineSequence().filter { it.startsWith("DA:") }.all { it.endsWith(",0") })
    assertTrue(report.lineSequence().any { it.startsWith("BRDA:") && it.endsWith(",0") })
  }

  @Test fun `opposite branches from two execution tasks yield full aggregate coverage`() {
    val (sources, reports) = branchReports()
    for (exec in reports) {
      val individual = checkNotNull(
        ElideCoverageReports.merge(root, ElideCoverageReports.Artifacts(emptyList(), listOf(exec), emptyList()), listOf(sources)),
      )
      val branches = individual.lineSequence().filter { it.startsWith("BRDA:") }.toList()
      assertEquals(2, branches.size)
      assertEquals(1, branches.count { it.endsWith(",1") })
    }

    val combined = checkNotNull(ElideCoverageReports.merge(root, ElideCoverageReports.discover(root), listOf(sources)))

    assertEquals(1, combined.lines().count { it == "end_of_record" })
    val branches = combined.lineSequence().filter { it.startsWith("BRDA:") }.toList()
    assertEquals(2, branches.size)
    assertTrue(branches.all { it.endsWith(",1") }, combined)
    assertTrue(combined.lineSequence().filter { it.startsWith("DA:") }.all { it.endsWith(",1") }, combined)
  }

  @Test fun `corrupt execution report cannot contribute probes parsed before its invalid suffix`() {
    val (sources, reports) = branchReports()
    Files.write(reports.last(), byteArrayOf(0x7f), StandardOpenOption.APPEND)

    val combined = checkNotNull(ElideCoverageReports.merge(root, ElideCoverageReports.discover(root), listOf(sources)))

    assertEquals(1, combined.lines().count { it == "end_of_record" })
    val branches = combined.lineSequence().filter { it.startsWith("BRDA:") }.toList()
    assertEquals(2, branches.size)
    assertEquals(1, branches.count { it.endsWith(",1") })
  }

  @Test fun `execution probe merging retains freshness and newest layout selection`() {
    val (sources, reports) = branchReports()
    reports.first().setLastModifiedTime(FileTime.fromMillis(3_000))
    reports.last().setLastModifiedTime(FileTime.fromMillis(1_000))
    val staleCopy = write("artifacts/coverage/jvm/task0/task0.exec", "")
    staleCopy.writeBytes(reports.last().readBytes())
    staleCopy.setLastModifiedTime(FileTime.fromMillis(2_000))

    val artifacts = ElideCoverageReports.discover(root, modifiedSince = 2_000)
    assertEquals(listOf(reports.first()), artifacts.exec)
    val combined = checkNotNull(ElideCoverageReports.merge(root, artifacts, listOf(sources)))

    val branches = combined.lineSequence().filter { it.startsWith("BRDA:") }.toList()
    assertEquals(2, branches.size)
    assertEquals(1, branches.count { it.endsWith(",1") })
  }

  @Test fun `XML branch counts union conservatively without inventing branch identities`() {
    val sources = root.resolve("src").createDirectories()
    sources.resolve("Example.kt").writeText("fun example() = 1")
    fun report(covered: Int, missed: Int) =
      xml("Example.kt").replace("""mb="0" cb="0"""", """mb="$missed" cb="$covered"""")
    write("reports/coverage/jvm/first/first.xml", report(1, 1))
    val second = write("reports/coverage/jvm/second/second.xml", report(1, 1))

    for (covered in 1..2) {
      second.writeText(report(covered, 2 - covered))
      val combined = checkNotNull(ElideCoverageReports.merge(root, ElideCoverageReports.discover(root), listOf(sources)))
      assertEquals(1, combined.lines().count { it == "end_of_record" })
      val branches = combined.lineSequence().filter { it.startsWith("BRDA:") }.toList()
      assertEquals(2, branches.size)
      assertEquals(covered, branches.count { it.endsWith(",1") })
    }
  }

  @Test fun `malformed XML cannot contaminate branch counts from a valid report`() {
    val sources = root.resolve("src").createDirectories()
    sources.resolve("Example.kt").writeText("fun example() = 1")
    val partial = xml("Example.kt").replace("""mb="0" cb="0"""", """mb="1" cb="1"""")
    val full = xml("Example.kt").replace("""mb="0" cb="0"""", """mb="0" cb="2"""")
    write("reports/coverage/jvm/first/first.xml", partial)
    write("reports/coverage/jvm/second/second.xml", full.removeSuffix("</report>"))

    val combined = checkNotNull(ElideCoverageReports.merge(root, ElideCoverageReports.discover(root), listOf(sources)))

    assertEquals(1, combined.lines().count { it == "end_of_record" })
    val branches = combined.lineSequence().filter { it.startsWith("BRDA:") }.toList()
    assertEquals(2, branches.size)
    assertEquals(1, branches.count { it.endsWith(",1") })
  }

  /** Exercises the same compiled bytecode in two tasks with disjoint branch probes. */
  private fun branchReports(): Pair<Path, List<Path>> {
    val relative = "dev/elide/intellij/execution/coverage"
    val sources = root.resolve("src").createDirectories()
    sources.resolve("$relative/ElideCoverageReportsTest.kt").also {
      it.parent.createDirectories()
      it.writeText("class CoverageSubject")
    }
    val name = "ElideCoverageReportsTest\$CoverageSubject.class"
    val original = CoverageSubject::class.java.getResourceAsStream(name).use { checkNotNull(it).readBytes() }
    val classes = root.resolve(".dev/jvm/classes/$relative").createDirectories()
    classes.resolve(name).writeBytes(original)
    val runtime = LoggerRuntime()
    val data = RuntimeData()
    runtime.startup(data)
    try {
      val instrumented = Instrumenter(runtime).instrument(original, name)
      val subjectClass = object : ClassLoader(CoverageSubject::class.java.classLoader) {
        fun loadSubject(): Class<*> = defineClass(null, instrumented, 0, instrumented.size)
      }.loadSubject()
      val constructor = subjectClass.getDeclaredConstructor().apply { isAccessible = true }
      val choose = subjectClass.getDeclaredMethod("choose", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
      val reports = listOf(true, false).mapIndexed { index, value ->
        choose.invoke(constructor.newInstance(), value)
        val report = ExecFileLoader()
        // The loader retains RuntimeData's probe arrays; serialize before resetting them for the next task.
        data.collect(report.executionDataStore, report.sessionInfoStore, false)
        write("reports/coverage/jvm/task$index/task$index.exec", "").also {
          report.save(it.toFile(), false)
          data.reset()
        }
      }
      return sources to reports
    } finally {
      runtime.shutdown()
    }
  }

  private class CoverageSubject {
    fun choose(value: Boolean): Int = if (value) 1 else 0
  }

  private fun xml(name: String, packageName: String = ""): String =
    """<report name="test"><package name="$packageName"><sourcefile name="$name">""" +
      """<line nr="1" mi="0" ci="1" mb="0" cb="0"/></sourcefile></package></report>"""

  private fun write(relative: String, content: String): Path {
    val path = root.resolve(".dev").resolve(relative)
    path.parent.createDirectories()
    path.writeText(content)

    return path
  }
}
