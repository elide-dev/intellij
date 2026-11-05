package dev.elide.intellij.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ElideInvocationDefaultsTest {
  @Test fun `explicit test options override defaults by alias without consuming program flags`() {
    val result = ElideInvocationDefaults.apply(
      listOf("test", "-t", "Example#one", "--reporter=tap", "--", "--bail=99"),
      mapOf("test" to """{"test-name-pattern":"Default", "reporter":"console", "bail":3}"""),
    )
    assertEquals(listOf("test", "--bail=3", "-t", "Example#one", "--reporter=tap", "--", "--bail=99"), result)
  }

  @Test fun `build target options follow targets and precede passthrough`() {
    assertEquals(
      listOf("build", "app:run", "--select=class:Example", "--select=class:Other", "--", "arg"),
      ElideInvocationDefaults.apply(
        listOf("build", "app:run", "--", "arg"),
        mapOf("build" to """{"select":["class:Example","class:Other"],"no-cache":false}"""),
      ),
    )
  }

  @Test fun `string boolean remains a value and short options keep their argument boundary`() {
    assertEquals(
      listOf("run", "--coverage=false", "-p", "a project", "main.ts"),
      ElideInvocationDefaults.apply(listOf("run", "main.ts"), mapOf("run" to """{"coverage":"false","p":"a project"}""")),
    )
  }

  @Test fun `nested and null options are rejected instead of silently executing a different command`() {
    for (text in listOf("[]", """{"reporter":null}""", """{"bail":{"value":3}}""", """{"--":true}""")) {
      assertFailsWith<IllegalArgumentException> { ElideInvocationDefaults.parse(text) }
    }
  }
}
