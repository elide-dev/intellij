package dev.elide.intellij.execution.build

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ElideBuildProgressRecordTest {
  @Test fun `all wire event families decode without leaking records to the console`() {
    listOf(
      "\"type\":\"build-started\",\"plannedTasks\":2",
      "\"type\":\"build-configured\",\"totalTasks\":2",
      "\"type\":\"build-progress\",\"completedTasks\":1",
      "\"type\":\"task-started\",\"task\":\"app:compile\"",
      "\"type\":\"task-progress\",\"task\":\"app:compile\",\"progress\":1",
      "\"type\":\"task-finished\",\"task\":\"app:compile\",\"outcome\":\"success\"",
      "\"type\":\"step-started\",\"task\":\"app:compile\",\"step\":\"resolve\"",
      "\"type\":\"step-progress\",\"task\":\"app:compile\",\"step\":\"resolve\"",
      "\"type\":\"step-finished\",\"task\":\"app:compile\",\"step\":\"resolve\"",
      "\"type\":\"message\",\"level\":\"warning\",\"text\":\"diagnostic\"",
      "\"type\":\"build-finished\",\"outcome\":\"failure\"",
    ).forEach { fields ->
      val line = "{$fields,\"time\":1700000000123}"
      val decoded = ElideBuildProgressParser().line(line)
      assertTrue(decoded.claimed)
      val record = assertNotNull(decoded.record)
      assertEquals(1_700_000_000_123L, record.time)
      assertNull(decoded.step)
      record.console()?.let {
        assertFalse(it.contains("\"type\""))
        assertTrue(it.endsWith('\n'))
      }
    }
  }

  @Test fun `message output preserves multiline diagnostics and source excerpts verbatim`() {
    val record = assertNotNull(ElideBuildProgressRecord.parse(
      """{"type":"message","level":"error","text":"error: Missing symbol\nIn file: src/App.kt:8:3\n→ 8 │ boom()\n","time":1}""",
    ))
    assertEquals("error: Missing symbol\nIn file: src/App.kt:8:3\n→ 8 │ boom()\n", record.console())
  }

  @Test fun `invalid optional values cannot throw or corrupt task pairing`() {
    val record = assertNotNull(ElideBuildProgressRecord.parse(
      """{"type":"task-finished","task":"a","outcome":"success","durationMs":{},"message":[],"project":false,"time":1}""",
    ))
    assertEquals("a", record.task)
    assertNull(record.durationMillis)
    assertNotNull(record.console())
  }

  @Test fun `unknown records and invalid required fields stay outside the protocol`() {
    listOf(
      "null", "[]", "{", "{}", "{\"type\":\"future\",\"time\":1}",
      """{"type":"task-started","task":"a","time":-1}""",
      """{"type":"task-started","task":"a","time":1.5}""",
      """{"type":"task-started","task":"a","time":9223372036854775808}""",
      """{"type":"step-started","task":"a","time":1}""",
      """{"type":"build-configured","totalTasks":"2","time":1}""",
      """{"type":"message","level":"fatal","text":"bad","time":1}""",
    ).forEach { assertNull(ElideBuildProgressRecord.parse(it)) }
  }
}
