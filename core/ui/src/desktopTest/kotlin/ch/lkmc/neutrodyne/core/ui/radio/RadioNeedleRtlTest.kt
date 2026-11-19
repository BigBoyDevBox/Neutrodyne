// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.radio

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.LayoutDirection
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The needle keeps its physical left-to-right reading (08 Accessibility, RTL): the canvas ticks,
 * progress line and pointer mapping run from the physical left, so the printed quarter labels must
 * do the same in RTL instead of mirroring with the layout.
 */
@OptIn(ExperimentalTestApi::class)
class RadioNeedleRtlTest {
    @Test
    fun rtlKeepsQuarterLabelsChronologicalAndSeekMappedFromTheLeft() =
        runDesktopComposeUiTest(width = 390, height = 844) {
            val episode = RadioEpisode(1, "Schedule", "Station", durationMs = 38 * 60_000L)
            val seeks = mutableListOf<Long>()
            setContent {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    NeutrodyneTheme(SystemUiState.DEFAULT) {
                        RadioPlayer(
                            RadioState(episodes = listOf(episode), selectedId = episode.id),
                            RadioActions(seek = { seeks += it }),
                            compact = false,
                            short = false,
                        )
                    }
                }
            }
            val seekBounds = onNodeWithTag("radio_seek").fetchSemanticsNode().boundsInRoot
            val labels = listOf("00:00", "09:30", "19:00", "28:30", "38:00")
            // The full duration is also printed under the needle; the quarter labels are the
            // copies above the seek canvas.
            val labelBounds =
                labels.map { label ->
                    onAllNodesWithText(label, useUnmergedTree = true)
                        .fetchSemanticsNodes()
                        .single { it.boundsInRoot.bottom <= seekBounds.top + 1f }
                        .boundsInRoot
                }
            // A tap a quarter of the way across the physical width is a quarter of the duration.
            onNodeWithTag("radio_seek").performTouchInput {
                click(Offset(width * 0.25f, height * 0.5f))
            }
            waitForIdle()
            assertEquals(listOf(570_000L), seeks)

            for (index in 1 until labelBounds.size) {
                assertTrue(
                    labelBounds[index].left > labelBounds[index - 1].left,
                    "Quarter labels must run chronologically from the physical left in RTL: " +
                        labels.zip(labelBounds),
                )
            }
            assertEquals(seekBounds.left, labelBounds.first().left, 1f)
            assertEquals(seekBounds.right, labelBounds.last().right, 1f)
        }
}
