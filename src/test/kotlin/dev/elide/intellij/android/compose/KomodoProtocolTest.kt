package dev.elide.intellij.android.compose

import com.google.gson.JsonParser
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KomodoProtocolTest {
  @Test fun `JSON lines decode UTF8 and reject truncated or oversized frames`() {
    val input = ByteArrayInputStream("{\"message\":\"é 😀\"}\r\n".toByteArray())
    assertEquals("{\"message\":\"é 😀\"}", KomodoTransport.line(input))
    assertNull(KomodoTransport.line(input))
    assertFailsWith<IllegalStateException> { KomodoTransport.line(ByteArrayInputStream("partial".toByteArray())) }
    assertFailsWith<IllegalArgumentException> { KomodoTransport.line(ByteArrayInputStream("12345\n".toByteArray()), 4) }
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
