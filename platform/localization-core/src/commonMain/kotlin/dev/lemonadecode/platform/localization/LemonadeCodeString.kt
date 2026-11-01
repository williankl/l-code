package dev.lemonadecode.platform.localization

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Text a presenter puts in a Model without resolving it, so the UI shows it in the user's
 * language. Arguments may themselves be [LemonadeCodeString]s.
 */
@Immutable
public sealed interface LemonadeCodeString {
  /** Text that isn't translated: names, paths, branches. */
  public data class Literal(val value: String) : LemonadeCodeString

  public data class Resource(val resource: StringResource, val args: List<Any> = emptyList()) : LemonadeCodeString

  public data class Plural(
    val resource: PluralStringResource,
    val quantity: Int,
    val args: List<Any> = listOf(quantity),
  ) : LemonadeCodeString
}

public fun String.asLemonadeCodeString(): LemonadeCodeString = LemonadeCodeString.Literal(this)

public fun StringResource.asLemonadeCodeString(vararg args: Any): LemonadeCodeString = LemonadeCodeString.Resource(this, args.toList())

public fun PluralStringResource.asLemonadeCodeString(quantity: Int, vararg args: Any): LemonadeCodeString = LemonadeCodeString.Plural(this, quantity, if (args.isEmpty()) listOf(quantity) else args.toList())

@Composable
public fun LemonadeCodeString.resolve(): String = when (this) {
  is LemonadeCodeString.Literal -> value
  is LemonadeCodeString.Resource -> stringResource(resource, *resolveArgs(args))
  is LemonadeCodeString.Plural -> pluralStringResource(resource, quantity, *resolveArgs(args))
}

@Composable
private fun resolveArgs(args: List<Any>): Array<Any> = args.map { if (it is LemonadeCodeString) it.resolve() else it }.toTypedArray()

/** Resolves outside composition, for text handed to the platform (dialog titles, notifications). */
public suspend fun LemonadeCodeString.load(): String = when (this) {
  is LemonadeCodeString.Literal -> value
  is LemonadeCodeString.Resource -> org.jetbrains.compose.resources.getString(resource, *loadArgs(args))
  is LemonadeCodeString.Plural -> org.jetbrains.compose.resources.getPluralString(resource, quantity, *loadArgs(args))
}

private suspend fun loadArgs(args: List<Any>): Array<Any> = args.map { if (it is LemonadeCodeString) it.load() else it }.toTypedArray()
