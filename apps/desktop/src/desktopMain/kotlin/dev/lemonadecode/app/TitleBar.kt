package dev.lemonadecode.app

import com.sun.jna.Function
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import java.awt.Window

/**
 * Before any window opens: lets macOS draw title bars dark while the system is, which Java
 * otherwise never does. [matchTitleBar] then follows the theme picked in settings.
 */
internal fun followSystemTitleBars() {
  if (isMac) System.setProperty("apple.awt.application.appearance", "system")
}

/**
 * Draws this window's title bar, with its close, minimise and zoom buttons, dark or light like
 * the app. macOS and Windows draw it themselves and go light unless told otherwise; Linux window
 * managers draw it in their own theme, so it stays theirs. Does nothing where the system can't.
 */
internal fun Window.matchTitleBar(dark: Boolean) {
  val _ = runCatching {
    when {
      isMac -> MacAppearance.set(dark)
      isWindows -> WindowsTitleBar.set(this, dark)
    }
  }
}

private val osName = System.getProperty("os.name").orEmpty()
private val isMac = osName.startsWith("Mac")
private val isWindows = osName.startsWith("Windows")

/** The whole app's appearance, so every window's title bar follows, through the Objective-C runtime. */
private object MacAppearance {
  private val objc by lazy { NativeLibrary.getInstance("objc") }
  private val appKit by lazy { NativeLibrary.getInstance("AppKit") }
  private val send by lazy { objc.getFunction("objc_msgSend") }

  fun set(dark: Boolean) {
    // AppKit's own constants, so nothing is allocated here.
    val name = appKit.getGlobalVariableAddress(if (dark) "NSAppearanceNameDarkAqua" else "NSAppearanceNameAqua").getPointer(0)
    val appearance = send.invokePointer(arrayOf(objcClass("NSAppearance"), selector("appearanceNamed:"), name)) ?: return
    val app = send.invokePointer(arrayOf(objcClass("NSApplication"), selector("sharedApplication"))) ?: return
    // AppKit wants its main thread, which isn't the one Compose runs on.
    send.invokeVoid(arrayOf(app, selector("performSelectorOnMainThread:withObject:waitUntilDone:"), selector("setAppearance:"), appearance, false))
  }

  private fun objcClass(name: String): Pointer = objc.getFunction("objc_getClass").invokePointer(arrayOf(name))

  private fun selector(name: String): Pointer = objc.getFunction("sel_registerName").invokePointer(arrayOf(name))
}

/** Desktop Window Manager's dark title bar, per window. */
private object WindowsTitleBar {
  private val dwm by lazy { NativeLibrary.getInstance("dwmapi") }
  private val user32 by lazy { NativeLibrary.getInstance("user32") }

  /** `DWMWA_USE_IMMERSIVE_DARK_MODE`, and its number before Windows 10 20H1. */
  private const val DarkModeAttribute = 20
  private const val DarkModeAttributeBefore20H1 = 19

  private const val SwpNoSize = 0x0001
  private const val SwpNoMove = 0x0002
  private const val SwpNoZOrder = 0x0004
  private const val SwpNoActivate = 0x0010
  private const val SwpFrameChanged = 0x0020
  private const val SwpAsyncWindowPos = 0x4000

  fun set(window: Window, dark: Boolean) {
    val hwnd = Native.getWindowPointer(window) ?: return
    val value = IntByReference(if (dark) 1 else 0)
    val setAttribute = dwm.getFunction("DwmSetWindowAttribute", Function.ALT_CONVENTION)
    val failed = setAttribute.invokeInt(arrayOf(hwnd, DarkModeAttribute, value, Int.SIZE_BYTES)) != 0
    if (failed) setAttribute.invokeInt(arrayOf(hwnd, DarkModeAttributeBefore20H1, value, Int.SIZE_BYTES))
    // Repaints the frame now rather than when the window next gains focus; posted, so this never waits on it.
    val flags = SwpNoSize or SwpNoMove or SwpNoZOrder or SwpNoActivate or SwpFrameChanged or SwpAsyncWindowPos
    user32.getFunction("SetWindowPos", Function.ALT_CONVENTION).invokeInt(arrayOf(hwnd, Pointer.NULL, 0, 0, 0, 0, flags))
  }
}
