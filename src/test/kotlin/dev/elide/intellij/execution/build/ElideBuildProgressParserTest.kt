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
package dev.elide.intellij.execution.build

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the progress log the Elide CLI writes on standard error, verified against the output of Elide 1.5.3.
 *
 * The fixtures under `cli/` are verbatim captures of that stream: a failing build with a compiler diagnostic, a
 * `elide run` whose steps were all up to date, and a build whose artifact task failed before the steps it needed
 * had even reported.
 */
class ElideBuildProgressParserTest {
  private fun fixture(name: String): String =
    checkNotNull(javaClass.getResourceAsStream("/cli/$name")) { "missing progress log fixture: $name" }
      .use { it.reader().readText() }

  /** Feeds [log] a line at a time, the way the CLI's output reaches the parser. */
  private fun decode(log: String): Pair<List<ElideBuildStep>, List<ElideBuildDiagnostic>> {
    val parser = ElideBuildProgressParser()
    val steps = log.lineSequence().mapNotNull { parser.line("$it\n").step }.toList()

    return steps to parser.flush()
  }

  /** Whether each line of [log] was decoded as one of the CLI's own, in order. */
  private fun claimed(log: String): List<Boolean> {
    val parser = ElideBuildProgressParser()

    return log.trimEnd('\n').lineSequence().map { parser.line("$it\n").claimed }.toList()
  }

  @Test fun `each reported step becomes one step, with its own duration`() {
    val (steps, orphans) = decode(fixture("build-run.txt"))

    assertEquals(
      listOf(
        "Dependencies ready" to ElideBuildStepStatus.SKIPPED,
        "Kotlin main compilation skipped" to ElideBuildStepStatus.SKIPPED,
        "Java main compilation skipped" to ElideBuildStepStatus.SKIPPED,
        "JVM main application completed successfully" to ElideBuildStepStatus.SUCCEEDED,
      ),
      steps.map { it.message to it.status },
    )

    // the log's own clock, in the order the steps completed
    assertEquals(listOf(14L, 15L, 15L, 103L), steps.map { it.elapsedMillis })

    // the reason a skipped step gives is not a duration, and only the step that ran has one
    assertEquals(listOf(null, null, null, 87L), steps.map { it.durationMillis })
    assertEquals(listOf("Up to date", "From cache", "No sources", "87ms"), steps.map { it.detail })
    assertTrue(orphans.isEmpty())
  }

  @Test fun `the closing line reports the build, not a step of it`() {
    val (steps, _) = decode(fixture("build-run.txt"))

    // `✓ Build successful in 90ms` would otherwise nest the build inside itself, under the root node the platform
    // already publishes for the run
    assertTrue(steps.none { it.message.startsWith("Build ") })
  }

  @Test fun `each diagnostic of a batch stands on its own, without the path it repeats`() {
    val (steps, orphans) = decode(fixture("build-javac-warnings.txt"))

    // the CLI stamps only the first diagnostic a tool reported in one go and names the tool again on each of the
    // rest, which are diagnostics of their own rather than more text under the first
    val diagnostics = steps.single { it.message == "Compiled 2 main Java sources" }.diagnostics
    assertEquals(
      listOf(
        "location of system modules is not set in conjunction with -source 21",
        // the path javac names inside the message is the one the diagnostic already reports, and saying it twice is
        // what used to leave the node reading as a path rather than as the warning
        "uses or overrides a deprecated API.",
        "Recompile with -Xlint:deprecation for details.",
      ),
      diagnostics.map { it.message },
    )
    assertTrue(diagnostics.all { it.severity == ElideBuildSeverity.WARNING && it.tool == "javac" })
    assertTrue(orphans.isEmpty())

    // the lines under the first warning are its own notes, and only it has any
    assertEquals(2, diagnostics.first().notes.size)
    assertTrue(diagnostics.drop(1).all { it.notes.isEmpty() && it.excerpt.isEmpty() })

    // a warning about a file as a whole names no line
    assertEquals(
      listOf(null, "/home/me/app/src/main/java/com/example/App.java", "/home/me/app/src/main/java/com/example/App.java"),
      diagnostics.map { it.file },
    )
    assertTrue(diagnostics.all { it.line == null })
  }

  @Test fun `a position the message opens with is read as one, whether a uri or a path`() {
    val (steps, _) = decode(fixture("build-kotlinc-uri.txt"))

    // `kotlinc` names the file it is about inside the message, as a URI, and prints no `In file:` line under it:
    // read as text the node would read as the path, and neither the file nor the line would be known
    val diagnostic = steps.single { it.message == "Kotlin main compilation failed" }.diagnostics.single()
    assertEquals("Unresolved reference 'Strin'.", diagnostic.message)
    assertEquals("kotlinc", diagnostic.tool)
    assertEquals("/home/me/app/src/main/kotlin/dev/example/App.kt", diagnostic.file)
    assertEquals(11, diagnostic.line)
    assertEquals(16, diagnostic.column)
  }

  @Test fun `a message opening with a qualified name keeps it`() {
    val (_, orphans) = decode(
      "[ 24ms] error: Task 'precompile-js-main' failed with an exception:\n" +
        "dev.elide.runtime.precompiler.PrecompilerNotice: Precompiler failed with 1 diagnostic(s)\n",
    )

    // a class name is not a position, and dropping it would leave the reason for the failure unreadable
    val diagnostic = orphans.single()
    assertEquals("Task 'precompile-js-main' failed with an exception:", diagnostic.message)
    assertEquals(
      listOf("dev.elide.runtime.precompiler.PrecompilerNotice: Precompiler failed with 1 diagnostic(s)"),
      diagnostic.notes,
    )
    assertNull(diagnostic.file)
  }

  @Test fun `a diagnostic is attributed to the step whose failure follows it`() {
    val (steps, orphans) = decode(fixture("build-failed.txt"))

    assertEquals(
      listOf("Resolved 19 Maven dependencies", "Kotlin main compilation failed"),
      steps.map { it.message },
    )
    assertTrue(steps.first().diagnostics.isEmpty())
    assertTrue(orphans.isEmpty())

    val failure = steps.last()
    assertEquals(ElideBuildStepStatus.FAILED, failure.status)

    val diagnostic = failure.diagnostics.single()
    assertEquals(ElideBuildSeverity.ERROR, diagnostic.severity)
    assertEquals("kotlinc", diagnostic.tool)
    assertEquals("Unresolved reference 'boom'.", diagnostic.message)
    assertEquals("src/main/com/example/Hello.kt", diagnostic.file)
    assertEquals(8, diagnostic.line)
    assertEquals(3, diagnostic.column)

    // the source excerpt the CLI rendered under the position is kept with the diagnostic, so the console can draw
    // it, and the position itself is not among those lines: it is reported as a position instead
    assertEquals(
      listOf("  6 │ fun main() {", "  7 │   println(old())", "→ 8 │   boom()", "  9 │ }"),
      diagnostic.excerpt,
    )
    assertTrue(diagnostic.notes.isEmpty())
  }

  @Test fun `a step that fails before the steps it needed have reported keeps its own diagnostic`() {
    val (steps, orphans) = decode(fixture("build-artifact.txt"))

    // the build runs its steps in parallel, so the artifact task fails first and the compilation it wanted reports
    // afterwards; the diagnostic belongs to the line that follows it, not to the step that finished last
    assertEquals("Failed to package jar 'app'", steps.first().message)
    assertEquals(
      "No compiled classes found for JAR packaging in artifact 'app'",
      steps.first().diagnostics.single().message,
    )
    assertNull(steps.first().diagnostics.single().file)
    assertTrue(steps.drop(1).all { it.diagnostics.isEmpty() })
    assertTrue(orphans.isEmpty())
  }

  @Test fun `a warning repeats its severity, and is not one of the step's failures`() {
    val (steps, _) = decode(
      "[179ms] warning: [warning] kotlinc: 'fun old(): Int' is deprecated. old.\n" +
        "In file: src/main/com/example/Hello.kt:8:11\n" +
        "[181ms] ✓ Compiled 1 main Kotlin source file (117ms)\n",
    )

    val diagnostic = steps.single().diagnostics.single()
    assertEquals(ElideBuildSeverity.WARNING, diagnostic.severity)
    assertEquals("kotlinc", diagnostic.tool)
    assertEquals("'fun old(): Int' is deprecated. old.", diagnostic.message)
    assertEquals(ElideBuildStepStatus.SUCCEEDED, steps.single().status)
  }

  @Test fun `a build that fails before running a step reports the reason on flush`() {
    val (steps, orphans) = decode(
      "[ 14ms] error: Artifact 'app' references unknown artifact 'main'\n" +
        "[ 14ms] ✗ Build failed in (1ms)\n",
    )

    // nothing ran, so the failure has no step to hang from: it is the whole account of the build, and reaches the
    // tree's root instead of being dropped with the closing line
    assertTrue(steps.isEmpty())
    assertEquals("Artifact 'app' references unknown artifact 'main'", orphans.single().message)
  }

  @Test fun `ordinary log lines are not steps`() {
    val (steps, orphans) = decode(
      "2026-09-10T18:18:32.110Z [debug] dev.elide.tooling.jvm.maven.MavenDependenciesTask install: total=19\n" +
        "[141ms] Loaded 23523 prefixes for remote repository elide\n",
    )

    assertTrue(steps.isEmpty())
    assertTrue(orphans.isEmpty())
  }

  @Test fun `the cli's own lines are told apart from what the program wrote on the same stream`() {
    // what a run shows as ordinary console text (the CLI's account of the build, its diagnostics and the source
    // excerpts that belong to them) and what it still shows as error output: a line the program under `elide run`
    // wrote on standard error while the CLI had nothing open
    assertEquals(
      listOf(false, true, true, true, true),
      claimed(
        "Exception in thread \"main\" java.lang.IllegalStateException\n" +
          "[169ms] error: kotlinc: Unresolved reference 'boom'.\n" +
          "In file: src/main/com/example/Hello.kt:5:3\n" +
          " 5 │   boom()\n" +
          "[170ms] ✗ Kotlin main compilation failed (108ms)\n",
      ),
    )
  }

  @Test fun `colour is stripped, so a run that was asked for it still reports steps`() {
    val (steps, _) = decode("[ 61ms] \u001B[32m✓\u001B[0m Resolved 19 Maven dependencies (39ms)\n")

    assertEquals("Resolved 19 Maven dependencies", steps.single().message)
    assertEquals(39L, steps.single().durationMillis)
  }

  @Test fun `durations are read in every unit the CLI prints, and reasons are not durations`() {
    assertEquals(39L, ElideBuildProgressParser.parseDuration("39ms"))
    assertEquals(3_600L, ElideBuildProgressParser.parseDuration("3.6s"))
    assertEquals(80_000L, ElideBuildProgressParser.parseDuration("1m 20s"))
    assertNull(ElideBuildProgressParser.parseDuration("Up to date"))
    assertNull(ElideBuildProgressParser.parseDuration("From cache"))
  }
}
