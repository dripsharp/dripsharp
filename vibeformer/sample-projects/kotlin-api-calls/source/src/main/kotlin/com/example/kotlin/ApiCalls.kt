package com.example.kotlin

import java.net.URI
import java.nio.file.Path

object KotlinApiCalls {
  fun message(name: String?): String {
    val raw = """
      hello
    """.trimIndent()
    return name?.let { raw + it } ?: raw
  }

  fun values(root: Path): List<URI> {
    return listOf(root.resolve("child").toUri(), URI("https://example.com"))
  }

  fun hasText(text: String): Boolean {
    return text.contains("value")
  }
}
