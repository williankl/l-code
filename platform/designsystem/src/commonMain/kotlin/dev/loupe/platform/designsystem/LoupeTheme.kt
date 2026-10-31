package dev.loupe.platform.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.teya.lemonade.LemonadeDarkTheme
import com.teya.lemonade.LemonadeLightTheme
import com.teya.lemonade.LemonadeTheme

/** The appearance the user picked in settings. */
public enum class LoupeThemeMode { Dark, Light, System }

/**
 * Root theme for Loupe: Lemonade with the dark or light semantic palette, plus the few
 * Loupe-only roles (code syntax, monospace type, scrim) that Lemonade has no token for.
 */
@Composable
public fun LoupeTheme(
  mode: LoupeThemeMode = LoupeThemeMode.Dark,
  content: @Composable () -> Unit,
) {
  val dark = when (mode) {
    LoupeThemeMode.Dark -> true
    LoupeThemeMode.Light -> false
    LoupeThemeMode.System -> isSystemInDarkTheme()
  }
  LemonadeTheme(
    colors = if (dark) LemonadeDarkTheme else LemonadeLightTheme,
    content = content,
  )
}

public object LoupeTheme {
  public val code: LoupeCodeColors
    @Composable
    @ReadOnlyComposable
    get() {
      val themed = LemonadeTheme.themed
      val content = LemonadeTheme.colors.content
      return LoupeCodeColors(
        keyword = themed.violet.content,
        string = themed.yellowLime.content,
        number = themed.orange.content,
        function = themed.blue.content,
        type = themed.teal.content,
        comment = content.contentTertiary,
        punctuation = content.contentSecondary,
        plain = content.contentPrimary,
        path = themed.blue.content,
        branch = content.contentBrand,
      )
    }

  public val typography: LoupeMonoTypography
    @Composable
    @ReadOnlyComposable
    get() {
      val base = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Normal)
      return LoupeMonoTypography(
        code = base.copy(fontSize = 13.sp, lineHeight = 20.sp),
        codeSmall = base.copy(fontSize = 12.sp, lineHeight = 18.sp),
      )
    }

  /** Flat 40% black behind drawers, per Lemonade's "scrims are flat, never blurred". */
  public val scrim: Color
    @Composable
    @ReadOnlyComposable
    get() = Color.Black.copy(alpha = LemonadeTheme.opacities.base.opacity40)
}

@Immutable
public data class LoupeCodeColors(
  val keyword: Color,
  val string: Color,
  val number: Color,
  val function: Color,
  val type: Color,
  val comment: Color,
  val punctuation: Color,
  val plain: Color,
  val path: Color,
  val branch: Color,
)

@Immutable
public data class LoupeMonoTypography(
  val code: TextStyle,
  val codeSmall: TextStyle,
)
