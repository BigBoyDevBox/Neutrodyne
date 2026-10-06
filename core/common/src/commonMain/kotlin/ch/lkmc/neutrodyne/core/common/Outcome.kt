// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common

/**
 * The success/failure type every suspend API that can fail returns (01 Errors). UI converts it
 * once per screen with the `whenOutcome` helper that lives in `:core:ui`.
 */
sealed interface Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>
    data class Err(val cause: Throwable) : Outcome<Nothing>
}

/** Maps the success value; errors pass through unchanged. */
inline fun <T, R> Outcome<T>.map(f: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Ok -> Outcome.Ok(f(value))
    is Outcome.Err -> this
}

/** The success value or `null`. */
fun <T> Outcome<T>.getOrNull(): T? = (this as? Outcome.Ok)?.value
