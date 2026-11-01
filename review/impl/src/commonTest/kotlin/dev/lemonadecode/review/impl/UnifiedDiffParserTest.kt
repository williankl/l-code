package dev.lemonadecode.review.impl

import dev.lemonadecode.domain.review.DiffLineKind
import dev.lemonadecode.domain.review.FileChange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnifiedDiffParserTest {
  private val diff = """
    diff --git a/src/app.ts b/src/app.ts
    index 1111111..2222222 100644
    --- a/src/app.ts
    +++ b/src/app.ts
    @@ -1,4 +1,4 @@
     import express from 'express';
    -import { throttle } from './throttle';
    +import { rateLimit } from './rateLimit';
    --- a removed sql comment
     export const app = express();
    diff --git a/src/throttle.ts b/src/throttle.ts
    deleted file mode 100644
    index 3333333..0000000
    --- a/src/throttle.ts
    +++ /dev/null
    @@ -1,2 +0,0 @@
    -export function throttle() {
    -}
    diff --git a/old name.md b/new name.md
    similarity index 90%
    rename from old name.md
    rename to new name.md
    diff --git a/logo.png b/logo.png
    new file mode 100644
    Binary files /dev/null and b/logo.png differ
  """.trimIndent() + "\n"

  @Test
  fun `numbers lines on both sides`() {
    val app = UnifiedDiffParser.parse(diff).first()
    assertEquals("src/app.ts", app.path)
    assertEquals(FileChange.Modified, app.change)
    assertEquals(listOf(DiffLineKind.Context, DiffLineKind.Removed, DiffLineKind.Added, DiffLineKind.Removed, DiffLineKind.Context), app.lines.map { it.kind })
    assertEquals(2, app.lines[1].oldNumber)
    assertEquals(2, app.lines[2].newNumber)
    assertEquals("-- a removed sql comment", app.lines[3].text)
    assertEquals(4 to 3, app.lines[4].oldNumber to app.lines[4].newNumber)
  }

  @Test
  fun `reads deletions, renames and binaries`() {
    val files = UnifiedDiffParser.parse(diff)
    assertEquals(FileChange.Deleted, files[1].change)
    assertEquals("src/throttle.ts", files[1].path)
    assertEquals("new name.md", files[2].path)
    assertEquals("old name.md", files[2].previousPath)
    assertEquals(FileChange.Renamed, files[2].change)
    assertTrue(files[3].isBinary)
    assertEquals(FileChange.Added, files[3].change)
  }
}
