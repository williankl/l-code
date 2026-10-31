package dev.loupe.platform.core

/**
 * Outcome of an operation that can fail, with a typed error. Used instead of `kotlin.Result`,
 * whose untyped `Throwable` hides what can actually go wrong.
 */
public sealed interface LoupeResult<out S, out E> {
  public data class Success<out S>(val value: S) : LoupeResult<S, Nothing>

  public data class Failure<out E>(val error: E) : LoupeResult<Nothing, E>

  public val isSuccess: Boolean get() = this is Success

  public val isFailure: Boolean get() = this is Failure

  public companion object {
    public fun <S> success(value: S): LoupeResult<S, Nothing> = Success(value)

    public fun <E> failure(error: E): LoupeResult<Nothing, E> = Failure(error)
  }
}

public inline fun <S, E, R> LoupeResult<S, E>.fold(onSuccess: (S) -> R, onFailure: (E) -> R): R = when (this) {
  is LoupeResult.Success -> onSuccess(value)
  is LoupeResult.Failure -> onFailure(error)
}

public inline fun <S, E, R> LoupeResult<S, E>.map(transform: (S) -> R): LoupeResult<R, E> = when (this) {
  is LoupeResult.Success -> LoupeResult.Success(transform(value))
  is LoupeResult.Failure -> this
}

public inline fun <S, E, R> LoupeResult<S, E>.flatMap(transform: (S) -> LoupeResult<R, E>): LoupeResult<R, E> = when (this) {
  is LoupeResult.Success -> transform(value)
  is LoupeResult.Failure -> this
}

public inline fun <S, E, F> LoupeResult<S, E>.mapError(transform: (E) -> F): LoupeResult<S, F> = when (this) {
  is LoupeResult.Success -> this
  is LoupeResult.Failure -> LoupeResult.Failure(transform(error))
}

@IgnorableReturnValue
public inline fun <S, E> LoupeResult<S, E>.onSuccess(action: (S) -> Unit): LoupeResult<S, E> = also {
  if (it is LoupeResult.Success) action(it.value)
}

@IgnorableReturnValue
public inline fun <S, E> LoupeResult<S, E>.onFailure(action: (E) -> Unit): LoupeResult<S, E> = also {
  if (it is LoupeResult.Failure) action(it.error)
}

public fun <S, E> LoupeResult<S, E>.getOrNull(): S? = (this as? LoupeResult.Success)?.value

public inline fun <S, E> LoupeResult<S, E>.getOrElse(fallback: (E) -> S): S = fold({ it }, fallback)

public fun <S, E : Throwable> LoupeResult<S, E>.getOrThrow(): S = fold({ it }, { throw it })
