package dev.loupe.platform.syntax

import kotlin.test.Test
import kotlin.test.assertEquals

class SyntaxHighlighterTest {
  @Test
  fun `picks the language from the extension`() {
    assertEquals("TypeScript", SyntaxHighlighter.languageOf("src/middleware/rateLimit.ts").displayName)
    assertEquals("Kotlin", SyntaxHighlighter.languageOf("App.kt").displayName)
    assertEquals("Markdown", SyntaxHighlighter.languageOf("README.md").displayName)
    assertEquals("Text", SyntaxHighlighter.languageOf("LICENSE").displayName)
  }

  @Test
  fun `colours keywords strings numbers and calls`() {
    val tokens = SyntaxHighlighter.highlight(listOf("const limit = Number(process.env.MAX ?? 100);"), SyntaxHighlighter.languageOf("a.ts")).single()
    assertEquals(SyntaxKind.Keyword, tokens.first { it.text == "const" }.kind)
    assertEquals(SyntaxKind.Function, tokens.first { it.text == "Number" }.kind)
    assertEquals(SyntaxKind.Number, tokens.first { it.text == "100" }.kind)
  }

  @Test
  fun `block comments carry across lines`() {
    val lines = SyntaxHighlighter.highlight(listOf("/**", " * Builds the app", " */", "fun main() {}"), SyntaxHighlighter.languageOf("Main.kt"))
    assertEquals(listOf(SyntaxKind.Comment), lines[1].map { it.kind }.distinct())
    assertEquals(SyntaxKind.Keyword, lines[3].first().kind)
  }

  @Test
  fun `hash comments for python`() {
    val tokens = SyntaxHighlighter.highlight(listOf("x = 1  # one"), SyntaxHighlighter.languageOf("a.py")).single()
    assertEquals(SyntaxKind.Comment, tokens.last().kind)
  }
}
