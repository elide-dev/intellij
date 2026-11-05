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

import com.intellij.build.FileNavigatable
import com.intellij.build.events.BuildEvent
import com.intellij.build.events.FailureResult
import com.intellij.build.events.FileMessageEvent
import com.intellij.build.events.FinishEvent
import com.intellij.build.events.MessageEvent
import com.intellij.build.events.OutputBuildEvent
import com.intellij.build.events.ProgressBuildEvent
import com.intellij.build.events.SkippedResult
import com.intellij.build.events.StartEvent
import com.intellij.build.events.SuccessResult
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import dev.elide.intellij.Constants
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertIs
import kotlin.test.assertIsNot
import kotlin.test.assertTrue

/** Covers the build tree an Elide run publishes: one node per step, with the diagnostics that step produced. */
@TestApplication
class ElideBuildEventPublisherTest {
  private val projectFixture = projectFixture()

  private val taskId = ExternalSystemTaskId.create(Constants.SYSTEM_ID, ExternalSystemTaskType.EXECUTE_TASK, "elide")

  /** Wall clock time the run started, which the log's own elapsed times are counted from. */
  private val startedAt = 1_700_000_000_000L

  private fun publish(log: String): List<BuildEvent> = buildList {
    ElideBuildEventPublisher(taskId, PROJECT_PATH, startedAt, ::add).apply {
      log.lineSequence().forEach { accept("$it\n") }
      flush()
    }
  }

  @Test fun `a step becomes one node of the run, timed by the cli's own clock`() {
    val events = publish("[103ms] ✓ JVM main application completed successfully (87ms)\n")

    val start = assertIs<StartEvent>(events.first())
    val finish = assertIs<FinishEvent>(events.last())

    // one node, hanging from the run's root, and the same node opened and closed: the log reports a step only once
    // it is over, so both ends arrive together
    assertEquals(2, events.size)
    assertEquals(start.id, finish.id)
    assertEquals(taskId, start.parentId)
    assertEquals(taskId, finish.parentId)
    assertEquals("JVM main application completed successfully", finish.message)

    // the step ran for 87ms and ended 103ms into the run, which is what the tree shows as its duration
    assertEquals(startedAt + 103L, finish.eventTime)
    assertEquals(startedAt + 103L - 87L, start.eventTime)
    assertIs<SuccessResult>(finish.result)
  }

  @Test fun `a skipped step keeps the reason the cli gave for it`() {
    val events = publish("[ 15ms] ⇥ Kotlin main compilation skipped (From cache)\n")

    val finish = assertIs<FinishEvent>(events.last())
    assertIs<SkippedResult>(finish.result)
    assertEquals("Kotlin main compilation skipped", finish.message)

    // a reason is not a duration, and the tree has nowhere but the hint to show it
    assertEquals("From cache", finish.hint)
  }

  @Test fun `a compiler diagnostic is a navigable node reading as the message, not as the path`() {
    val project = projectFixture.get()
    val events = publish(
      "[171ms] error: kotlinc: Unresolved reference 'boom'.\n" +
        "In file: src/main/com/example/Hello.kt:8:3\n" +
        "[173ms] ✗ Kotlin main compilation failed (96ms)\n",
    )

    val start = assertIs<StartEvent>(events.first())
    val message = assertIs<MessageEvent>(events[1])
    val finish = assertIs<FinishEvent>(events.last())

    assertEquals(start.id, message.parentId)
    assertEquals(MessageEvent.Kind.ERROR, message.kind)
    assertEquals("kotlinc", message.group)

    // the node reads as what the tool said, with the file it said it about named beside it: the path itself is left
    // to the console, and a file message event — which the tree renders as a node per path — is deliberately unused
    assertEquals("Unresolved reference 'boom'.", message.message)
    assertEquals("Hello.kt:8", message.hint)
    assertIsNot<FileMessageEvent>(message)

    // the CLI counts lines and columns from one, the platform from zero, and a double click on the node opens the
    // file the diagnostic named
    val navigatable = assertIs<FileNavigatable>(message.getNavigatable(project))
    assertEquals(PROJECT_PATH.resolve("src/main/com/example/Hello.kt").toFile(), navigatable.filePosition.file)
    assertEquals(7, navigatable.filePosition.startLine)
    assertEquals(2, navigatable.filePosition.startColumn)

    // the diagnostic reaches the tree once: the step's result marks it failed, and adding the diagnostic to that
    // result as well would have the tree draw a second, unnavigable node for it and count the error twice
    val failure = assertIs<FailureResult>(finish.result)
    assertTrue(failure.failures.isEmpty())
  }

  @Test fun `the console of a diagnostic holds the block the cli rendered, coloured by severity`() {
    val events = publish(
      "[205ms] warning: [warning] kotlinc: 'fun old(): String' is deprecated.\n" +
        "In file: src/main/com/example/Hello.kt:8:11\n" +
        "\n" +
        "  7 │ fun greet() {\n" +
        "→ 8 │   println(old())\n" +
        "[207ms] ✓ Compiled 1 main Kotlin source file (139ms)\n",
    )

    val step = assertIs<StartEvent>(events.first())
    val message = assertIs<MessageEvent>(events[1])
    val blocks = events.filterIsInstance<OutputBuildEvent>()

    // one copy under the diagnostic's own node, one under the step that produced it, so selecting either shows the
    // CLI's account of it; the run's root node is the CLI's log in full and gets none
    assertEquals(listOf(message.id, step.id), blocks.map { it.parentId })
    assertTrue(blocks.all { it.outputType.isStdout })

    assertEquals(
      """
      src/main/com/example/Hello.kt:8:11
      'fun old(): String' is deprecated.

        7 │ fun greet() {
      → 8 │   println(old())
      """.trimIndent(),
      plain(blocks.first().message).trimEnd(),
    )

    // the message of a warning is drawn in the console's yellow and the line the excerpt points at with it, while
    // the frame the CLI drew around the source is dimmed
    assertTrue(blocks.first().message.contains("${YELLOW}'fun old(): String' is deprecated.$RESET"))
    assertTrue(blocks.first().message.contains("$GREY→ 8 │$RESET$YELLOW   println(old())$RESET"))
  }

  @Test fun `the console of a diagnostic names its file relative to the project`() {
    val absolute = PROJECT_PATH.resolve("src/main/com/example/Hello.kt")
    val events = publish(
      "[171ms] error: javac: incompatible types\n" +
        "In file: $absolute:5:13\n" +
        "[173ms] ✗ Java main compilation failed (96ms)\n",
    )

    // some tools print the path absolute, which says nothing the project does not and crowds out the message: the
    // console names the file the way the rest of the IDE does
    val block = events.filterIsInstance<OutputBuildEvent>().first()
    assertTrue(plain(block.message).startsWith("src/main/com/example/Hello.kt:5:13\n"))
    assertEquals("Hello.kt:5", assertIs<MessageEvent>(events[1]).hint)
  }

  @Test fun `a failure with no step of its own reaches the root of the tree`() {
    val events = publish(
      "[ 14ms] error: Artifact 'app' references unknown artifact 'main'\n" +
        "[ 14ms] ✗ Build failed in (1ms)\n",
    )

    // no step ran, so there is no node to hang the reason from, and the closing line is the platform's own root
    val message = assertIs<MessageEvent>(events.first())
    assertEquals(taskId, message.parentId)
    assertEquals(MessageEvent.Kind.ERROR, message.kind)
    assertEquals("Artifact 'app' references unknown artifact 'main'", message.message)

    // the reason is shown under the node it opened, and not a second time under the run's root, whose console is
    // the CLI's log
    val block = assertIs<OutputBuildEvent>(events.last())
    assertEquals(message.id, block.parentId)
    assertEquals(2, events.size)
  }

  @Test fun `structured tasks open immediately and concurrent finishes retain their identities`() {
    val events = mutableListOf<BuildEvent>()
    val publisher = ElideBuildEventPublisher(taskId, PROJECT_PATH, startedAt, events::add)
    val rendered = publisher.accept("""{"type":"task-started","task":"a","time":1700000000010}""")
    assertTrue(rendered.claimed)
    assertFalse(rendered.text!!.startsWith("{"))
    val a = assertIs<StartEvent>(events.single())
    assertEquals(startedAt + 10, a.eventTime)
    publisher.accept("""{"type":"task-started","task":"b","time":1700000000020}""")
    val b = assertIs<StartEvent>(events.last())
    assertTrue(a.id != b.id)
    publisher.accept("""{"type":"task-finished","task":"b","outcome":"skipped","durationMs":30,"time":1700000000050}""")
    val bEnd = assertIs<FinishEvent>(events.last())
    assertEquals(b.id, bEnd.id)
    assertEquals(startedAt + 50, bEnd.eventTime)
    assertIs<SkippedResult>(bEnd.result)
    publisher.accept("""{"type":"task-finished","task":"a","outcome":"success","durationMs":80,"time":1700000000090}""")
    val aEnd = assertIs<FinishEvent>(events.last())
    assertEquals(a.id, aEnd.id)
    assertEquals(80L, aEnd.eventTime - a.eventTime)
    publisher.flush()
    assertEquals(4, events.size)
  }

  @Test fun `missing starts recover measured duration and repeated records do not duplicate nodes`() {
    val events = mutableListOf<BuildEvent>()
    val publisher = ElideBuildEventPublisher(taskId, PROJECT_PATH, startedAt, events::add)
    val completion = """{"type":"task-finished","task":"a","outcome":"failure","durationMs":45,"time":1700000000090}"""
    publisher.accept(completion)
    publisher.accept(completion)
    publisher.accept("""{"type":"task-started","task":"a","time":1700000000045}""")
    publisher.flush()
    assertEquals(2, events.size)
    val start = assertIs<StartEvent>(events.first())
    val finish = assertIs<FinishEvent>(events.last())
    assertEquals(start.id, finish.id)
    assertEquals(45L, finish.eventTime - start.eventTime)
    assertIs<FailureResult>(finish.result)
  }

  @Test fun `structured steps and progress belong to the task that emitted them`() {
    val events = mutableListOf<BuildEvent>()
    val publisher = ElideBuildEventPublisher(taskId, PROJECT_PATH, startedAt, events::add)
    publisher.accept("""{"type":"task-started","task":"a","time":1700000000000}""")
    val task = assertIs<StartEvent>(events.last())
    publisher.accept("""{"type":"step-started","task":"a","step":"download","total":10,"time":1700000000010}""")
    val step = assertIs<StartEvent>(events.last())
    assertEquals(task.id, step.parentId)
    val rendered = publisher.accept("""{"type":"step-progress","task":"a","step":"download","progress":4,"time":1700000000020}""")
    assertNull(rendered.text)
    val progress = assertIs<ProgressBuildEvent>(events.last())
    assertEquals(step.id, progress.id)
    assertEquals(10L, progress.total)
    assertEquals(4L, progress.progress)
    publisher.accept("""{"type":"step-finished","task":"a","step":"download","time":1700000000030}""")
    assertEquals(step.id, assertIs<FinishEvent>(events.last()).id)
    publisher.accept("""{"type":"task-finished","task":"a","outcome":"success","time":1700000000040}""")
    assertEquals(task.id, assertIs<FinishEvent>(events.last()).id)
    publisher.flush()
    assertEquals(5, events.size)
  }

  @Test fun `structured diagnostic keeps its owner source location notes and excerpt`() {
    val events = mutableListOf<BuildEvent>()
    val publisher = ElideBuildEventPublisher(taskId, PROJECT_PATH, startedAt, events::add)
    publisher.accept("""{"type":"task-started","task":"a","time":1700000000000}""")
    val owner = assertIs<StartEvent>(events.last())
    publisher.accept("""{"type":"task-started","task":"b","time":1700000000001}""")
    val record = """{"type":"message","task":"a","time":1700000000020,"level":"error","file":"src/App.kt","line":8,"column":3,"text":"kotlinc: Missing symbol\n  note\nIn file: src/App.kt:8:3\n\n→ 8 │ boom()"}"""
    val rendered = publisher.accept(record)
    assertTrue(rendered.text!!.contains("→ 8 │ boom()"))
    val diagnostic = events.filterIsInstance<MessageEvent>().single()
    assertEquals(owner.id, diagnostic.parentId)
    assertEquals(MessageEvent.Kind.ERROR, diagnostic.kind)
    assertEquals(startedAt + 20, diagnostic.eventTime)
    val position = assertIs<FileNavigatable>(diagnostic.getNavigatable(projectFixture.get())).filePosition
    assertEquals(7, position.startLine)
    assertEquals(2, position.startColumn)
    val output = events.filterIsInstance<OutputBuildEvent>()
    assertEquals(listOf(diagnostic.id, owner.id), output.map { it.parentId })
    assertTrue(output.all { plain(it.message).contains("→ 8 │ boom()") && it.message.contains("note") })
  }

  @Test fun `unknown and malformed records are unchanged program output without tree events`() {
    val events = mutableListOf<BuildEvent>()
    val publisher = ElideBuildEventPublisher(taskId, PROJECT_PATH, startedAt, events::add)
    listOf(
      """{"type":"future-event","time":1}""",
      """{"type":"task-started","task":42,"time":1}""",
      """{"type":"task-finished","task":"a","outcome":"unknown","time":1}""",
      """{"type":"message","level":"error","text":{},"time":1}""",
      """{"type":"task-started","task":"a","time":"1"}""",
      """{"type":"task-started","task":"a","time":""",
    ).forEach {
      val output = publisher.accept(it)
      assertFalse(output.claimed)
      assertEquals(it, output.text)
    }
    publisher.flush()
    assertTrue(events.isEmpty())
  }

  @Test fun `build failure closes all running children once before their parents`() {
    val events = mutableListOf<BuildEvent>()
    val publisher = ElideBuildEventPublisher(taskId, PROJECT_PATH, startedAt, events::add)
    publisher.accept("""{"type":"task-started","task":"a","time":1700000000000}""")
    publisher.accept("""{"type":"step-started","task":"a","step":"work","time":1700000000010}""")
    publisher.accept("""{"type":"build-finished","outcome":"failure","time":1700000000100}""")
    publisher.flush()
    publisher.flush()
    val starts = events.filterIsInstance<StartEvent>()
    val finishes = events.filterIsInstance<FinishEvent>()
    assertEquals(starts.map { it.id }.reversed(), finishes.map { it.id })
    assertTrue(finishes.all { it.result is FailureResult && it.eventTime == startedAt + 100 })
  }

  private companion object {
    private val PROJECT_PATH: Path = Path.of("/tmp/elide-project")

    /** ANSI sequences the rendered block is coloured with, which the console resolves against its own scheme. */
    private const val YELLOW = "\u001B[33m"
    private const val GREY = "\u001B[90m"
    private const val RESET = "\u001B[0m"

    /** [text] without the colour in it, which is what the console draws rather than shows. */
    private fun plain(text: String): String = text.replace(Regex("\u001B\\[[0-9;]*m"), "")
  }
}
