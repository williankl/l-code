package dev.loupe.platform.localization

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Text a presenter puts in a Model without resolving it, so the UI shows it in the user's
 * language. Arguments may themselves be [LoupeString]s.
 */
@Immutable
public sealed interface LoupeString {
  /** Text that isn't translated: names, paths, branches. */
  public data class Literal(val value: String) : LoupeString

  public data class Resource(val resource: StringResource, val args: List<Any> = emptyList()) : LoupeString

  public data class Plural(
    val resource: PluralStringResource,
    val quantity: Int,
    val args: List<Any> = listOf(quantity),
  ) : LoupeString
}

public fun String.asLoupeString(): LoupeString = LoupeString.Literal(this)

public fun StringResource.asLoupeString(vararg args: Any): LoupeString = LoupeString.Resource(this, args.toList())

public fun PluralStringResource.asLoupeString(quantity: Int, vararg args: Any): LoupeString = LoupeString.Plural(this, quantity, if (args.isEmpty()) listOf(quantity) else args.toList())

@Composable
public fun LoupeString.resolve(): String = when (this) {
  is LoupeString.Literal -> value
  is LoupeString.Resource -> stringResource(resource, *resolveArgs(args))
  is LoupeString.Plural -> pluralStringResource(resource, quantity, *resolveArgs(args))
}

@Composable
private fun resolveArgs(args: List<Any>): Array<Any> = args.map { if (it is LoupeString) it.resolve() else it }.toTypedArray()

/** Resolves outside composition, for text handed to the platform (dialog titles, notifications). */
public suspend fun LoupeString.load(): String = when (this) {
  is LoupeString.Literal -> value
  is LoupeString.Resource -> org.jetbrains.compose.resources.getString(resource, *loadArgs(args))
  is LoupeString.Plural -> org.jetbrains.compose.resources.getPluralString(resource, quantity, *loadArgs(args))
}

private suspend fun loadArgs(args: List<Any>): Array<Any> = args.map { if (it is LoupeString) it.load() else it }.toTypedArray()
