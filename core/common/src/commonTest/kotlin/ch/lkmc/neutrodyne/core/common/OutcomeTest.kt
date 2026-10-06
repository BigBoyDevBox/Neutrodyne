// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class OutcomeTest {
    @Test
    fun `map transforms the success value`() {
        assertEquals(Outcome.Ok(6), Outcome.Ok(3).map { it * 2 })
    }

    @Test
    fun `map leaves errors untouched`() {
        val err = Outcome.Err(IllegalStateException("boom"))
        val mapped = err.map { _: Int -> 0 }
        assertSame(err, mapped)
    }

    @Test
    fun `getOrNull unwraps Ok only`() {
        assertEquals("v", Outcome.Ok("v").getOrNull())
        assertNull(Outcome.Err(RuntimeException()).getOrNull())
    }

    @Test
    fun `Err carries the cause`() {
        val cause = RuntimeException("x")
        assertIs<Outcome.Err>(Outcome.Err(cause)).also { assertSame(cause, it.cause) }
    }
}
