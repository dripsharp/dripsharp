package com.example.kotlin

import java.net.URI

interface ModuleReader {
  val isLocal: Boolean
  val scheme: String

  fun read(uri: URI): String

  fun listElements(uri: URI): List<String>
}

object FixtureModuleReader : ModuleReader {
  override val isLocal: Boolean = true

  override val scheme: String = "foo"

  override fun read(uri: URI): String = "hello"

  override fun listElements(uri: URI): List<String> {
    throw NotImplementedError()
  }
}
