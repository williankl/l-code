package dev.lemonadecode.platform.core

/**
 * Outcome of an operation that can fail, with a typed error. Used instead of `kotlin.Result`,
 * whose untyped `Throwable` hides what can actually go wrong.
 */
public sealed interface LemonadeCodeResult<out S, out E> {
  public data class Success<out S>(val value: S) : LemonadeCodeResult<S, Nothing>

  public data class Failure<out E>(val error: E) : LemonadeCodeResult<Nothing, E>

  public val isSuccess: Boolean get() = this is Success

  public val isFailure: Boolean get() = this is Failure

  public companion object {
    public fun <S> success(value: S): LemonadeCodeResult<S, Nothing> = Success(value)

    public fun <E> failure(error: E): LemonadeCodeResult<Nothing, E> = Failure(error)
  }
}

public inline fun <S, E, R> LemonadeCodeResult<S, E>.fold(onSuccess: (S) -> R, onFailure: (E) -> R): R = when (this) {
  is LemonadeCodeResult.Success -> onSuccess(value)
  is LemonadeCodeResult.Failure -> onFailure(error)
}

public inline fun <S, E, R> LemonadeCodeResult<S, E>.map(transform: (S) -> R): LemonadeCodeResult<R, E> = when (this) {
  is LemonadeCodeResult.Success -> LemonadeCodeResult.Success(transform(value))
  is LemonadeCodeResult.Failure -> this
}

public inline fun <S, E, R> LemonadeCodeResult<S, E>.flatMap(transform: (S) -> LemonadeCodeResult<R, E>): LemonadeCodeResult<R, E> = when (this) {
  is LemonadeCodeResult.Success -> transform(value)
  is LemonadeCodeResult.Failure -> this
}

public inline fun <S, E, F> LemonadeCodeResult<S, E>.mapError(transform: (E) -> F): LemonadeCodeResult<S, F> = when (this) {
  is LemonadeCodeResult.Success -> this
  is LemonadeCodeResult.Failure -> LemonadeCodeResult.Failure(transform(error))
}

@IgnorableReturnValue
public inline fun <S, E> LemonadeCodeResult<S, E>.onSuccess(action: (S) -> Unit): LemonadeCodeResult<S, E> = also {
  if (it is LemonadeCodeResult.Success) action(it.value)
}

@IgnorableReturnValue
public inline fun <S, E> LemonadeCodeResult<S, E>.onFailure(action: (E) -> Unit): LemonadeCodeResult<S, E> = also {
  if (it is LemonadeCodeResult.Failure) action(it.error)
}

public fun <S, E> LemonadeCodeResult<S, E>.getOrNull(): S? = (this as? LemonadeCodeResult.Success)?.value

public inline fun <S, E> LemonadeCodeResult<S, E>.getOrElse(fallback: (E) -> S): S = fold({ it }, fallback)

public fun <S, E : Throwable> LemonadeCodeResult<S, E>.getOrThrow(): S = fold({ it }, { throw it })
