package dev.lemonadecode.platform.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReportedFolderTest {
  @Test
  fun `an OSC 7 file URL gives its path, whatever the host`() {
    assertEquals("/Users/me/code", folderFromOsc7("file://macbook.local/Users/me/code", windows = false))
    assertEquals("/home/me", folderFromOsc7("file:///home/me", windows = false))
    assertEquals("/home/me", folderFromOsc7("FILE://box/home/me/", windows = false))
    assertEquals("/", folderFromOsc7("file://box/", windows = false))
  }

  @Test
  fun `percent escapes are decoded as UTF-8`() {
    assertEquals("/Users/me/my app", folderFromOsc7("file://h/Users/me/my%20app", windows = false))
    assertEquals("/Users/João/código", folderFromOsc7("file://h/Users/Jo%C3%A3o/c%c3%b3digo", windows = false))
    assertEquals("/Users/João", folderFromOsc7("file://h/Users/João", windows = false))
    assertEquals("/tmp/100%/x%2", folderFromOsc7("file://h/tmp/100%/x%2", windows = false))
    assertEquals("/tmp/%zz", folderFromOsc7("file://h/tmp/%zz", windows = false))
  }

  @Test
  fun `anything but a file URL is no folder`() {
    assertNull(folderFromOsc7("", windows = false))
    assertNull(folderFromOsc7("http://h/x", windows = false))
    assertNull(folderFromOsc7("file://host-only", windows = false))
    assertNull(folderFromOsc7("/Users/me", windows = false))
  }

  @Test
  fun `on Windows a drive path turns into a Windows path`() {
    assertEquals("C:\\Users\\me", folderFromOsc7("file://desktop/C:/Users/me", windows = true))
    assertEquals("C:\\Users\\me", folderFromOsc7("file:///C:/Users/me/", windows = true))
    assertEquals("d:\\My Code", folderFromOsc7("file:///d:/My%20Code", windows = true))
    assertEquals("C:\\Users\\me", folderFromOsc7("file:///C:\\Users\\me", windows = true))
    assertEquals("C:\\", folderFromOsc7("file:///C:/", windows = true))
    assertEquals("C:\\", folderFromOsc7("file:///C:", windows = true))
  }

  @Test
  fun `on Windows a path without a drive is no folder`() {
    assertNull(folderFromOsc7("file://h/home/me", windows = true))
    assertNull(folderFromOsc7("file://h/c/Users/me", windows = true))
    assertNull(folderFromOsc7("file://h/C:x", windows = true))
  }

  @Test
  fun `an OSC 9 9 report is the path as written, quotes removed`() {
    assertEquals("C:\\Users\\me", folderFromOsc9("C:\\Users\\me"))
    assertEquals("C:\\Program Files", folderFromOsc9("\"C:\\Program Files\""))
    assertEquals("\\\\server\\share\\dir", folderFromOsc9("\\\\server\\share\\dir"))
    assertEquals("C:\\", folderFromOsc9("C:\\"))
    assertNull(folderFromOsc9(""))
    assertNull(folderFromOsc9("\"\""))
  }
}
