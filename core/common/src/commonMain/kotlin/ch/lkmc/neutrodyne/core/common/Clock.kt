// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common

/**
 * Both clocks a service might need (01 Coroutines and threading): `System.currentTimeMillis` and
 * `SystemClock`/`System.nanoTime` style elapsed time. The device implementation is bound
 * `@SingleIn(AppScope::class)` by the shells; tests substitute `TestClock` from `:core:testing`.
 */
interface Clock {
    /** Wall clock — epoch milliseconds; only for timestamps meant to survive a reboot. */
    fun now(): Long

    /** Monotonic elapsed milliseconds; for durations, timeouts and timeouts bookkeeping. */
    fun elapsedRealtime(): Long
}
