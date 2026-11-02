package dev.lemonadecode.platform.localization

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import java.util.Locale

/** The default locale from before the app changed it; read before anything sets another. */
private object SystemLocale {
  val initial: Locale = Locale.getDefault()
}

public actual fun systemLanguageTag(): String = SystemLocale.initial.toLanguageTag()

@Composable
public actual fun ProvideAppLanguage(languageTag: String?, content: @Composable () -> Unit) {
  val system = SystemLocale.initial
  val locale = languageTag?.let(Locale::forLanguageTag) ?: system
  // Compose resources read the default locale, so it changes before anything below draws.
  if (Locale.getDefault() != locale) Locale.setDefault(locale)
  key(locale.toLanguageTag()) { content() }
}
