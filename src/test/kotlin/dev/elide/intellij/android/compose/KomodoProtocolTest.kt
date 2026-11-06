package dev.elide.intellij.android.compose

import com.google.gson.JsonParser
import com.intellij.openapi.application.ReadAction
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.jetbrains.kotlin.psi.KtPsiFactory

@TestApplication
class KomodoProtocolTest {
  private val projectFixture = projectFixture()

  private fun discoveryKey(text: String): String {
    val project = projectFixture.get()
    return ReadAction.compute<String, RuntimeException> {
      KomodoBackend.discoveryKey(KtPsiFactory(project).createFile("Screen.kt", text))
    }
  }

  @Test fun `runtime string edits reuse discovery but multiline annotations imports and constants invalidate it`() {
    val text = """
      package sample
      import androidx.compose.ui.tooling.preview.Preview
      const val TITLE = "Title"
      @Preview(
        name = "Card",
        widthDp = 200,
      )
      fun Card() {
        Column { Text("Before") }
      }
    """.trimIndent()
    val key = discoveryKey(text)
    assertEquals(key, discoveryKey(text.replace("Before", "After")))
    for ((before, after) in listOf(
      "\"Card\"" to "\"Renamed\"",
      "widthDp = 200" to "widthDp = 400",
      "import androidx.compose.ui.tooling.preview.Preview" to "import other.Preview",
      "\"Title\"" to "\"Another title\"",
      "fun Card()" to "fun Renamed()",
      "package sample" to "package other",
    )) assertNotEquals(key, discoveryKey(text.replace(before, after)), before)
    val malformed = text.replace("Text(\"Before\")", "Text(\"Before)")
    assertNotEquals(key, discoveryKey(malformed))
    assertEquals(malformed, discoveryKey(malformed))
  }

  @Test fun `local annotations properties and interpolated expressions are not hidden by discovery cache`() {
    val text = """
      fun Card() {
        @Label(
          "Before"
        )
        fun nested() {}
        val label = "Before"
        Text("${'$'}{label}")
      }
    """.trimIndent()
    assertNotEquals(discoveryKey(text), discoveryKey(text.replace("\"Before\"", "\"After\"")))
    assertNotEquals(discoveryKey(text), discoveryKey(text.replace("${'$'}{label}", "${'$'}{other}")))
    assertNotEquals(discoveryKey("fun Card() = Text(\"Before\")"), discoveryKey("fun Card() = Text(\"After\")"))
  }

  @Test fun `rehosting retains pixels but discards stale input layout and errors`() {
    val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
    val previous = ComposeCard("id", "old", "old.method", image, "previous failure",
      listOf(ComposeLayoutNode("node", "Text", 0, 0, 1, 1, null)), live = true, keyboard = true, layoutError = "old layout")
    val base = ComposeCard("id", "new", "new.method", null, null, emptyList())
    val hosted = KomodoBackend.rehost(base, previous)
    assertSame(image, hosted.image)
    assertEquals("new", hosted.name)
    assertEquals("new.method", hosted.configuration)
    assertNull(hosted.error)
    assertNull(hosted.layoutError)
    assertTrue(hosted.nodes.isEmpty())
    assertFalse(hosted.live)
    assertFalse(hosted.keyboard)
    assertNull(KomodoBackend.rehost(base, null).image)
  }

  @Test fun `JSON lines decode UTF8 and reject truncated or oversized frames`() {
    val input = KomodoTransport.ProtocolInput(ByteArrayInputStream("{\"message\":\"é 😀\"}\r\n".toByteArray()))
    assertEquals("{\"message\":\"é 😀\"}", input.line())
    assertNull(input.line())
    assertFailsWith<IllegalStateException> { KomodoTransport.ProtocolInput(ByteArrayInputStream("partial".toByteArray())).line() }
    assertFailsWith<IllegalArgumentException> { KomodoTransport.ProtocolInput(ByteArrayInputStream("12345\n".toByteArray())).line(4) }
  }

  @Test fun `buffered protocol lines preserve coalesced messages UTF8 boundaries and LSP bodies`() {
    val text = "x".repeat(8191) + "é 😀" + "y".repeat(8192)
    val input = KomodoTransport.ProtocolInput(ByteArrayInputStream("$text\r\nnext\n".toByteArray()))
    assertEquals(text, input.line())
    assertEquals("next", input.line())
    assertNull(input.line())
    val body = """{"message":"é 😀"}""".toByteArray()
    val rpc = KomodoTransport.ProtocolInput(ByteArrayInputStream(
      "Content-Length: ${body.size}\r\n\r\n".toByteArray() + body + "after\n".toByteArray()))
    assertEquals("Content-Length: ${body.size}", rpc.line(8192))
    assertEquals("", rpc.line(8192))
    assertTrue(body.contentEquals(rpc.readNBytes(body.size)))
    assertEquals("after", rpc.line())
    assertNull(rpc.line())
  }

  @Test fun `protocol byte limits hold across refills and allow exactly the maximum`() {
    for (size in listOf(0, 1, 8191, 8192, 8193, 16384)) {
      val text = "x".repeat(size)
      val input = KomodoTransport.ProtocolInput(ByteArrayInputStream("$text\n".toByteArray()))
      assertEquals(text, input.line(size))
      if (size > 0) assertFailsWith<IllegalArgumentException> {
        KomodoTransport.ProtocolInput(ByteArrayInputStream("$text\n".toByteArray())).line(size - 1)
      }
      if (size > 0) assertFailsWith<IllegalStateException> {
        KomodoTransport.ProtocolInput(ByteArrayInputStream(text.toByteArray())).line(size)
      }
    }
  }

  @Test fun `layout source matching uses Compose absolute package hashes and unknown sentinel`() {
    val source = ComposeLayoutSource("Screen.kt", 49835523, 23, 0, 0) // abs("com.example".hashCode())
    assertTrue(source.matchesPackage("package com.example\nfun screen() {}"))
    assertTrue(source.matchesPackage("package `com`.example\n"))
    assertFalse(source.matchesPackage("package another.app"))
    assertTrue(source.copy(packageHash = -1).matchesPackage("package another.app"))
  }

  @Test fun `provider discovery remains unexpanded until real daemon entries response`() {
    val plain = JsonParser.parseString("""{"previewId":"plain","methodFQN":"sample.Example","name":"Example","previewParams":{"widthDp":"100","showBackground":"true","locale":"fr"},"methodParams":[]}""").asJsonObject
    val parameterized = JsonParser.parseString("""{"previewId":"provider","methodFQN":"sample.Parameters","name":"Parameters","previewParams":{},"methodParams":[{"provider":"sample.Values","limit":"3"}]}""").asJsonObject
    val entries = KomodoBackend.previewEntries(listOf(plain, parameterized))
    assertEquals("plain:0", entries[0]["id"])
    assertNull(entries[0]["methodParameter"])
    assertEquals(mapOf("widthDp" to 100.0, "showBackground" to true, "locale" to "fr"), entries[0]["parameters"])
    assertEquals("provider", entries[1]["id"])
    assertNotNull(entries[1]["methodParameter"])
  }

  @Test fun `real PNG frames retain measured source bounds and capability flags`() {
    val image = BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB)
    image.setRGB(1, 1, 0xff123456.toInt())
    val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    val message = JsonParser.parseString("""{"width":3,"height":2,"nodes":[{"id":"a","name":"Text","x":1,"y":0,"width":2,"height":2,"source":{"fileName":"Screen.kt","packageHash":123,"lineNumber":7,"offset":20,"length":4}}]}""").asJsonObject
    message.addProperty("png", Base64.getEncoder().encodeToString(bytes))
    val before = ComposeCard("id", "name", "method", null, "old error", emptyList())
    val frame = KomodoBackend.frame(before, message, true)
    assertTrue(frame.live)
    assertTrue(frame.keyboard)
    assertFalse(frame.scroll)
    assertNull(frame.error)
    assertEquals(0xff123456.toInt(), frame.image!!.getRGB(1, 1))
    assertEquals(7, frame.nodes.single().source!!.lineNumber)
    val identical = KomodoBackend.frame(frame, message, true, message.string("png"))
    assertSame(frame.image, identical.image)
    assertEquals(frame, identical)
    message.getAsJsonArray("nodes")[0].asJsonObject.addProperty("x", 0)
    val moved = KomodoBackend.frame(frame, message, true, message.string("png"))
    assertSame(frame.image, moved.image)
    assertEquals(0, moved.nodes.single().x)
    assertNotEquals(frame.nodes, moved.nodes)
    message.addProperty("width", 4)
    assertFailsWith<IllegalArgumentException> { KomodoBackend.frame(before, message, true) }
  }

  @Test fun `malformed layout does not invent bounds or discard a valid frame`() {
    val bytes = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), "png", it) }.toByteArray()
    val message = JsonParser.parseString("""{"width":1,"height":1,"nodes":[{"id":"a","x":0,"y":0,"width":-1,"height":1}]}""").asJsonObject
    message.addProperty("png", Base64.getEncoder().encodeToString(bytes))
    val frame = KomodoBackend.frame(ComposeCard("a", "a", "", null, null, emptyList()), message, false)
    assertNotNull(frame.image)
    assertTrue(frame.nodes.isEmpty())
    assertNotNull(frame.layoutError)
    assertFalse(frame.keyboard)
  }
}
