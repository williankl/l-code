package dev.lemonadecode.platform.localization

import androidx.compose.runtime.Composable

/** The computer's own language, e.g. `pt-BR`, as it was before Lemonade Code picked another. */
public expect fun systemLanguageTag(): String

/**
 * Draws [content] in the language [languageTag] names (`de`, `pt`…), or the computer's own when
 * it's null. Changing it draws everything below again, so all text follows at once.
 */
@Composable
public expect fun ProvideAppLanguage(languageTag: String?, content: @Composable () -> Unit)
