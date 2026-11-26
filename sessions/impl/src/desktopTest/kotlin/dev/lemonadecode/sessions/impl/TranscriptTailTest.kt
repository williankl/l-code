package dev.lemonadecode.sessions.impl

import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TranscriptTailTest {
  private val folder = Files.createTempDirectory("lemonade-code-transcript").toFile().canonicalFile
  private val launch = File(folder, "storefront").apply { mkdirs() }
  private val worktree = File(folder, "storefront/.claude/worktrees/rate-limit").apply { mkdirs() }
  private val file = File(folder, "conversation.jsonl")

  /** When this run of the conversation began. */
  private val started = 1_795_000_000_000L

  /**
   * A line as Claude Code writes one, made up: written in [cwd] at [at], by a sub-agent if [sidechain],
   * long by [padding], and naming [inside] somewhere inside it.
   */
  private fun line(cwd: File, at: Long = started + 1_000, sidechain: Boolean = false, padding: Int = 0, inside: String = "/somewhere/else"): String {
    val who = """"parentUuid":null,"isSidechain":$sidechain,"type":"user","cwd":"${cwd.path}","sessionId":"0b7c3a2e-1111-4222-8333-944455556666""""
    val what = """"message":{"role":"user","content":"hello${"x".repeat(padding)}"},"toolUseResult":{"cwd":"$inside"}"""
    return """{$who,$what,"timestamp":"${Instant.ofEpochMilli(at)}"}""" + "\n"
  }

  private fun write(vararg lines: String) = file.appendText(lines.joinToString(""))

  private fun tail(firstLook: Long = 1L shl 20, maxRead: Long = 4L shl 20, maxLine: Int = 1 shl 20) = TranscriptTail(file, since = started, firstLook, maxRead, maxLine)

  @Test
  fun `the newest line says where the conversation works, and it follows as lines come`() {
    write(line(launch), line(worktree))
    val tail = tail()
    assertEquals(worktree.path, tail.workingDirectory())
    write(line(launch))
    assertEquals(launch.path, tail.workingDirectory())
  }

  @Test
  fun `lines from before this run count for nothing`() {
    write(line(worktree, at = started - 60_000))
    val tail = tail()
    assertNull(tail.workingDirectory(), "a resumed conversation starts where it's launched")
    write(line(launch, at = started + 5_000))
    assertEquals(launch.path, tail.workingDirectory())
  }

  @Test
  fun `a sub-agent's lines are passed over`() {
    write(line(launch), line(worktree, sidechain = true))
    assertEquals(launch.path, tail().workingDirectory())
  }

  @Test
  fun `only the folder a line is written in counts, not one named inside it`() {
    write(line(launch, inside = worktree.path))
    assertEquals(launch.path, tail().workingDirectory())
  }

  @Test
  fun `a folder that's gone counts for nothing until it's back`() {
    val removed = File(folder, "storefront.worktrees/removed")
    write(line(launch), line(removed))
    val tail = tail()
    assertNull(tail.workingDirectory(), "not the folder before it either: that's older news")
    removed.mkdirs()
    assertEquals(removed.path, tail.workingDirectory())
  }

  @Test
  fun `a line cut off where the reading starts is never trusted`() {
    // From where the first look starts, the rest of this line reads like a line of its own.
    val hidden = line(worktree)
    write("not-a-line-on-its-own$hidden")
    val tail = tail(firstLook = hidden.length.toLong())
    assertNull(tail.workingDirectory())
    write(line(launch))
    assertEquals(launch.path, tail.workingDirectory())
  }

  @Test
  fun `a line too long to read is passed over for the one before it`() {
    write(line(launch), line(worktree, padding = 2_000))
    assertEquals(launch.path, tail(maxLine = 1_000).workingDirectory())
  }

  @Test
  fun `a line still being written counts once it ends`() {
    write(line(launch))
    val tail = tail()
    assertEquals(launch.path, tail.workingDirectory())
    val next = line(worktree)
    write(next.dropLast(30))
    assertEquals(launch.path, tail.workingDirectory())
    write(next.takeLast(30))
    assertEquals(worktree.path, tail.workingDirectory())
  }

  @Test
  fun `only the end of a long transcript is read, and then only what's added`() {
    // A line of this run far back, and a long history after it: the first look doesn't reach it.
    write(line(worktree))
    repeat(200) { write(line(launch, at = started - 1)) }
    val tail = tail(firstLook = 4_096, maxRead = 4_096)
    assertNull(tail.workingDirectory())
    // Much more at once than is read: the newest line is in what is.
    repeat(200) { write(line(launch, at = started - 1)) }
    write(line(worktree))
    assertEquals(worktree.path, tail.workingDirectory())
  }

  @Test
  fun `a transcript written again from the start is read again`() {
    write(line(launch), line(launch), line(worktree))
    val tail = tail()
    assertEquals(worktree.path, tail.workingDirectory())
    file.writeText(line(launch))
    assertEquals(launch.path, tail.workingDirectory())
  }

  @Test
  fun `no transcript, or nothing in it yet, says nothing`() {
    val tail = tail()
    assertNull(tail.workingDirectory())
    file.writeText("")
    assertNull(tail.workingDirectory())
    write(line(launch))
    assertEquals(launch.path, tail.workingDirectory())
  }
}
