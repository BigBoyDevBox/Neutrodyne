// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.radio

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class NinePatchStopsTest {
    @Test
    fun edgesKeepTheirSizeWhenTheSideHasRoom() {
        assertContentEquals(floatArrayOf(0f, 8f, 92f, 100f), ninePatchStops(edge = 8f, length = 100f))
        assertContentEquals(floatArrayOf(0f, 8f, 8f, 16f), ninePatchStops(edge = 8f, length = 16f))
    }

    /** The mini radio's 14 dp needle window under its 8 dp dial bezel drew rows 0..8 and 6..14. */
    @Test
    fun aShortSideSplitsBetweenItsEdgesInsteadOfOverlapping() {
        val stops = ninePatchStops(edge = 8f, length = 14f)

        assertContentEquals(floatArrayOf(0f, 7f, 7f, 14f), stops)
        for (index in 1 until stops.size) {
            assertTrue(stops[index] >= stops[index - 1], "stops must not run backwards: ${stops.toList()}")
        }
    }
}
