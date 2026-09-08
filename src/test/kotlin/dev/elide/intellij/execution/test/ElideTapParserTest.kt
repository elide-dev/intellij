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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Covers the TAP 13 framing `elide test --reporter=tap` writes, verified against Elide 1.5.2. */
class ElideTapParserTest {
  private fun decode(stream: String): List<ElideTapEvent> = buildList {
    ElideTapParser().apply {
      feed(stream, ::add)
      flush(::add)
    }
  }

  @Test fun `a start comment binds a start number to the label its result will carry`() {
    val events = decode("TAP version 13\n# start 3: adds numbers\nok 1 - adds numbers\n1..1\n")

    assertEquals(
      listOf(
        ElideTapEvent.Started(3, "adds numbers"),
        ElideTapEvent.Result("adds numbers", ElideTestOutcome.PASSED, null, null, emptyList()),
        ElideTapEvent.Plan(1),
      ),
      events,
    )
  }

  @Test fun `output is attributed by start number, not by position`() {
    // with tests in flight the line after a start may belong to another test entirely, which is why the writer tags
    // each one; an untagged comment is output no test owns
    val events = decode(
      """
      # start 1: slow one
      # start 2: fast one
      # out 2: from the fast one
      # out 1: from the slow one
      # launcher notice
      """.trimIndent() + "\n",
    )

    assertEquals(
      listOf(
        ElideTapEvent.Started(1, "slow one"),
        ElideTapEvent.Started(2, "fast one"),
        ElideTapEvent.Output(2, "from the fast one"),
        ElideTapEvent.Output(1, "from the slow one"),
        ElideTapEvent.Unowned("launcher notice"),
      ),
      events,
    )
  }

  @Test fun `a failure carries the diagnostic block that follows it`() {
    val events = decode(
      "not ok 2 - fails hard\n" +
        "  ---\n" +
        "  message: \"expected: <1> but was: <2>\"\n" +
        "  severity: \"ERR_ASSERTION\"\n" +
        "  detail: |\n" +
        "    \tAssertionError: expected: <1> but was: <2>\n" +
        "    \t\tat probe.ProbeTest.fails hard(ProbeTest.kt:9)\n" +
        "  ...\n" +
        "1..2\n",
    )

    val result = assertIs<ElideTapEvent.Result>(events.first())
    assertEquals("fails hard", result.label)
    assertEquals(ElideTestOutcome.FAILED, result.outcome)
    assertEquals("expected: <1> but was: <2>", result.message)
    assertEquals(
      listOf(
        "\tAssertionError: expected: <1> but was: <2>",
        "\t\tat probe.ProbeTest.fails hard(ProbeTest.kt:9)",
      ),
      result.detail,
    )
    // the plan still lands: a block ends at its terminator rather than running on
    assertEquals(ElideTapEvent.Plan(2), events.last())
  }

  @Test fun `an escaped newline in a message comes back as one`() {
    val events = decode(
      "not ok 1 - fails\n  ---\n  message: \"first line\\nsecond \\\"quoted\\\" line\"\n  ...\n",
    )

    assertEquals("first line\nsecond \"quoted\" line", assertIs<ElideTapEvent.Result>(events.single()).message)
  }

  @Test fun `directives are read off the first unescaped hash`() {
    val events = decode(
      "ok 1 - counts \\# of items\n" +
        "ok 2 - disabled one # SKIP Reason: is @Disabled\n" +
        "ok 3 - unfinished # TODO\n",
    )

    val results = events.map { assertIs<ElideTapEvent.Result>(it) }

    // a `#` a test's own name carries is escaped by the writer, so it is part of the label, not a directive
    assertEquals("counts # of items", results[0].label)
    assertEquals(ElideTestOutcome.PASSED, results[0].outcome)
    assertNull(results[0].reason)

    assertEquals("disabled one", results[1].label)
    assertEquals(ElideTestOutcome.SKIPPED, results[1].outcome)
    assertEquals("Reason: is @Disabled", results[1].reason)

    assertEquals("unfinished", results[2].label)
    assertEquals(ElideTestOutcome.TODO, results[2].outcome)
    assertNull(results[2].reason)
  }

  @Test fun `a result whose block the stream cut short still reports`() {
    // the process died mid-block: the failure is still known, and the message it did carry is kept
    val events = decode("not ok 1 - dies\n  ---\n  message: \"killed\"\n")

    val result = assertIs<ElideTapEvent.Result>(events.single())
    assertEquals(ElideTestOutcome.FAILED, result.outcome)
    assertEquals("killed", result.message)
  }

  @Test fun `a block the stream never terminated does not swallow the results after it`() {
    val events = decode("not ok 1 - dies\n  ---\n  message: \"killed\"\nok 2 - carries on\n")

    assertEquals(2, events.size)
    assertEquals("carries on", assertIs<ElideTapEvent.Result>(events.last()).label)
  }

  @Test fun `lines split across chunks are decoded once whole`() {
    // output arrives as the reader hands it over, which need not be on line boundaries
    val events = buildList {
      ElideTapParser().apply {
        feed("# start 1: adds n", ::add)
        feed("umbers\nok 1 - adds numbers\r\n", ::add)
        flush(::add)
      }
    }

    assertEquals(
      listOf(
        ElideTapEvent.Started(1, "adds numbers"),
        ElideTapEvent.Result("adds numbers", ElideTestOutcome.PASSED, null, null, emptyList()),
      ),
      events,
    )
  }

  @Test fun `text that is not TAP passes through instead of being dropped`() {
    val events = decode("Downloading dependencies\nok 1 - fine\nokay, done\n")

    assertEquals(ElideTapEvent.Unowned("Downloading dependencies"), events[0])
    assertEquals("fine", assertIs<ElideTapEvent.Result>(events[1]).label)
    assertEquals(ElideTapEvent.Unowned("okay, done"), events[2])
  }
}
