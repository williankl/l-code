package dev.lemonadecode.platform.designsystem

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
public enum class LemonadeCodeThemeMode { Dark, Light, System }

/**
 * Root theme for Lemonade Code: Lemonade with the dark or light semantic palette, plus the few
 * Lemonade Code-only roles (code syntax, monospace type, scrim) that Lemonade has no token for.
 */
@Composable
public fun LemonadeCodeTheme(
  mode: LemonadeCodeThemeMode = LemonadeCodeThemeMode.Dark,
  content: @Composable () -> Unit,
) {
  val dark = when (mode) {
    LemonadeCodeThemeMode.Dark -> true
    LemonadeCodeThemeMode.Light -> false
    LemonadeCodeThemeMode.System -> isSystemInDarkTheme()
  }
  LemonadeTheme(
    colors = if (dark) LemonadeDarkTheme else LemonadeLightTheme,
    content = content,
  )
}

public object LemonadeCodeTheme {
  public val code: LemonadeCodeCodeColors
    @Composable
    @ReadOnlyComposable
    get() {
      val themed = LemonadeTheme.themed
      val content = LemonadeTheme.colors.content
      return LemonadeCodeCodeColors(
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

  public val typography: LemonadeCodeMonoTypography
    @Composable
    @ReadOnlyComposable
    get() {
      val base = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Normal)
      return LemonadeCodeMonoTypography(
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
public data class LemonadeCodeCodeColors(
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
public data class LemonadeCodeMonoTypography(
  val code: TextStyle,
  val codeSmall: TextStyle,
)
