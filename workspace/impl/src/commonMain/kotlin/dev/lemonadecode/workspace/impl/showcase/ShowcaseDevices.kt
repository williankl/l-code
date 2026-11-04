package dev.lemonadecode.workspace.impl.showcase

import dev.lemonadecode.domain.mobile.DeviceApp
import dev.lemonadecode.domain.mobile.DeviceCapability
import dev.lemonadecode.domain.mobile.DeviceConnection
import dev.lemonadecode.domain.mobile.DeviceId
import dev.lemonadecode.domain.mobile.DeviceLogLine
import dev.lemonadecode.domain.mobile.DeviceLogs
import dev.lemonadecode.domain.mobile.DeviceScreen
import dev.lemonadecode.domain.mobile.LogLevel
import dev.lemonadecode.domain.mobile.LogPackages
import dev.lemonadecode.domain.mobile.MobileDevice
import dev.lemonadecode.domain.mobile.MobilePlatform
import dev.lemonadecode.domain.mobile.MobileTools
import dev.lemonadecode.domain.mobile.NearbyDevice
import dev.lemonadecode.domain.mobile.NearbyKind
import dev.lemonadecode.domain.mobile.ScreenPicture
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Paint
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

/** The devices, logs and phone screen the Devices screens of the design show. */
internal object ShowcaseDevices {
  private val android = setOf(
    DeviceCapability.Mirror,
    DeviceCapability.Touch,
    DeviceCapability.Keys,
    DeviceCapability.Rotate,
    DeviceCapability.Screenshot,
    DeviceCapability.Logs,
    DeviceCapability.LogsByApp,
  )
  private val iosUsb = setOf(DeviceCapability.Mirror, DeviceCapability.Screenshot, DeviceCapability.Logs)

  val tools = MobileTools(adb = "/usr/bin/adb", libimobiledevice = "/usr/bin")

  val pixel = MobileDevice(DeviceId("38021FDH2000AB"), MobilePlatform.Android, "Pixel 8", "15", DeviceConnection.Usb, "38021FDH2000AB", screenWidth = 1080, screenHeight = 2400, capabilities = android)
  val galaxy = MobileDevice(
    DeviceId("192.168.1.42:41235"),
    MobilePlatform.Android,
    "Galaxy S23",
    "14",
    DeviceConnection.Wifi,
    "192.168.1.42:41235",
    screenWidth = 1080,
    screenHeight = 2340,
    capabilities = android + DeviceCapability.Disconnect,
  )
  val iphone = MobileDevice(DeviceId("00008130-001A2C3E"), MobilePlatform.Ios, "iPhone 15 Pro", "18.1", DeviceConnection.Usb, "00008130-001A2C3E", screenWidth = 1179, screenHeight = 2556, capabilities = iosUsb)
  val ipad = MobileDevice(DeviceId("00008112-000E3D1A"), MobilePlatform.Ios, "iPad Air", "18.1", DeviceConnection.Wifi, "192.168.1.61", capabilities = setOf(DeviceCapability.Logs))
  val connected = listOf(pixel, galaxy, iphone, ipad)

  val pixel7a = MobileDevice(DeviceId("192.168.1.57:41001"), MobilePlatform.Android, "Pixel 7a", "14", DeviceConnection.Wifi, "192.168.1.57:41001", capabilities = android + DeviceCapability.Disconnect)

  val nearby = listOf(NearbyDevice(MobilePlatform.Android, "Pixel 7a", "192.168.1.57:37099", NearbyKind.Connectable))

  private fun line(index: Int, level: LogLevel, tag: String, message: String): DeviceLogLine {
    val millis = 4 * 60_000 + 31_000 + index * 737
    val time = "10:%02d:%02d.%03d".format(14 + millis / 60_000 % 60, millis / 1000 % 60, millis % 1000)
    return DeviceLogLine(sequence = index.toLong(), time = time, level = level, tag = tag, message = message)
  }

  val androidLogs = DeviceLogs(
    source = "logcat · com.teya.storefront",
    lines = listOf(
      line(1, LogLevel.Debug, "OkHttp", "--> POST /api/cart/checkout"),
      line(2, LogLevel.Debug, "OkHttp", "<-- 200 OK /api/cart/checkout (184 ms)"),
      line(4, LogLevel.Warning, "RateLimiter", "18 of 20 requests used in the 60 s window"),
      line(5, LogLevel.Debug, "OkHttp", "--> POST /api/cart/checkout"),
      line(6, LogLevel.Error, "RateLimiter", "429 Too Many Requests · retry-after 30 s"),
      line(7, LogLevel.Error, "CheckoutViewModel", "Checkout failed: RateLimitException at RateLimitInterceptor.kt:42"),
      line(8, LogLevel.Warning, "Choreographer", "Skipped 3 frames. The app may be doing too much work on its main thread."),
      line(10, LogLevel.Debug, "OkHttp", "--> POST /api/cart/checkout"),
      line(11, LogLevel.Debug, "OkHttp", "<-- 200 OK /api/cart/checkout (171 ms)"),
    ),
  )

  /** The Pixel's apps to show the logs of: the store in front, the till running, and the rest. */
  val apps = listOf(
    DeviceApp("com.teya.storefront", running = true, inFront = true),
    DeviceApp("com.teya.pos", running = true),
    DeviceApp("com.teya.designsystem.sample"),
    DeviceApp("com.teya.kitchen"),
    DeviceApp("com.teya.loyalty"),
    DeviceApp("com.teya.storefront.dev"),
    DeviceApp("com.teya.terminal.sample"),
  )

  /** The store and the till, picked from [apps]. */
  val pickedApps = LogPackages.Only(setOf("com.teya.storefront", "com.teya.pos"))

  val iosLogs = DeviceLogs(
    source = "idevicesyslog · all processes",
    lines = listOf(
      line(0, LogLevel.Info, "runningboardd", "Launching com.teya.storefront"),
      line(1, LogLevel.Debug, "storefront(network)", "POST /api/cart/checkout"),
      line(2, LogLevel.Debug, "CFNetwork", "Task <A1F2> finished with status 200 (171 ms)"),
      line(3, LogLevel.Info, "SpringBoard", "Scene update for com.teya.storefront"),
      line(4, LogLevel.Warning, "storefront(ratelimit)", "18 of 20 requests used in the 60 s window"),
      line(5, LogLevel.Debug, "storefront(network)", "POST /api/cart/checkout"),
      line(6, LogLevel.Error, "storefront(ratelimit)", "429 Too Many Requests · retry-after 30 s"),
      line(7, LogLevel.Error, "storefront(checkout)", "CheckoutError.rateLimited at RateLimitMiddleware.swift:38"),
      line(8, LogLevel.Warning, "UIKitCore", "Hang detected: 310 ms on main thread"),
    ),
  )

  /** The store's cart with its rate-limit error, as the design's phone shows it. */
  fun screen(width: Int = 1080, height: Int = 2400, framesPerSecond: Float = 4f): DeviceScreen.Frame = DeviceScreen.Frame(ScreenPicture.Encoded(cartScreen(width, height)), width, height, framesPerSecond)

  private val cache = mutableMapOf<Pair<Int, Int>, ByteArray>()

  private fun cartScreen(width: Int, height: Int): ByteArray = cache.getOrPut(width to height) {
    val scale = width / 270f
    val surface = Surface.makeRasterN32Premul(width, height)
    val canvas = surface.canvas
    fun paint(color: Int) = Paint().apply {
      this.color = color
      isAntiAlias = true
    }
    fun font(size: Float, bold: Boolean = false) = Font(FontMgr.default.legacyMakeTypeface("sans-serif", if (bold) FontStyle.BOLD else FontStyle.NORMAL), size * scale)
    fun text(value: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false) = canvas.drawString(value, x * scale, y * scale, font(size, bold), paint(color))
    fun box(x: Float, y: Float, w: Float, h: Float, radius: Float, color: Int) = canvas.drawRRect(RRect.makeXYWH(x * scale, y * scale, w * scale, h * scale, radius * scale), paint(color))

    val primary = Color.makeRGB(0x15, 0x15, 0x13)
    val secondary = Color.makeRGB(0x6B, 0x6B, 0x66)
    canvas.drawRect(Rect.makeWH(width.toFloat(), height.toFloat()), paint(Color.makeRGB(0xF2, 0xF2, 0xEF)))
    val w = width / scale
    val h = height / scale
    text("9:41", 16f, 22f, 10f, primary, bold = true)
    text("5G · 82%", w - 62f, 22f, 10f, primary, bold = true)
    text("Storefront", 14f, 46f, 10f, secondary)
    text("Your cart", 14f, 66f, 16f, primary, bold = true)
    listOf("Oat flat white × 2" to "€9.00", "Sourdough loaf" to "€7.50", "Gift card" to "€25.50").forEachIndexed { index, (item, price) ->
      val top = 80f + index * 38f
      box(10f, top, w - 20f, 32f, 12f, Color.WHITE)
      text(item, 20f, top + 20f, 11f, primary)
      text(price, w - 60f, top + 20f, 11f, primary, bold = true)
    }
    box(10f, h - 96f, w - 20f, 36f, 12f, Color.makeRGB(0xFD, 0xE4, 0xE2))
    canvas.drawCircle(24f * scale, (h - 78f) * scale, 5f * scale, paint(Color.makeRGB(0xC8, 0x2A, 0x1F)))
    text("Too many requests. Try again in 30 s.", 34f, h - 74f, 10f, primary)
    box(10f, h - 50f, w - 20f, 34f, 10f, Color.makeRGB(0xE1, 0xE5, 0x1A))
    text("Pay €42.00", w / 2f - 30f, h - 29f, 12f, primary, bold = true)
    requireNotNull(surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)).bytes
  }
}
