package dev.lemonadecode.domain.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClaudeConversationTest {
  private val id = "0b6f3c2e-4a51-4f7d-9d8e-1c2b3a4d5e6f"

  @Test
  fun `only a session id is resumed`() {
    assertTrue(ClaudeConversation.isId(id))
    assertEquals("claude --resume $id", ClaudeConversation.resumeCommand(id))
    listOf("--dangerously-skip-permissions", "-p", "x; touch pwned", "0b6f3c2e-4a51", "$id --verbose", "", "../$id").forEach { value ->
      assertFalse(ClaudeConversation.isId(value), value)
      assertNull(ClaudeConversation.resumeCommand(value), value)
    }
  }

  @Test
  fun `transcripts sit in a project folder named after the path`() {
    assertEquals("projects/-Users-you-code-store-front/$id.jsonl", ClaudeConversation.transcript(id, "/Users/you/code/store.front"))
    assertEquals("/Users/you/.claude", ClaudeConversation.configFolder("/Users/you", null))
    assertEquals("/Users/you/.claude", ClaudeConversation.configFolder("/Users/you", ""))
    assertEquals("/elsewhere", ClaudeConversation.configFolder("/Users/you", "/elsewhere"))
  }
}
