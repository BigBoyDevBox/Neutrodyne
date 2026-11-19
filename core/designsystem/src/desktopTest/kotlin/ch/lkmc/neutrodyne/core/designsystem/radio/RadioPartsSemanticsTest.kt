// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.radio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class RadioPartsSemanticsTest {
    /** Page titles and the Menu sign pass heading(); it must land on the node that reads the title. */
    @Test
    fun anEnamelSignHeadingIsAnnouncedWithItsLettering() =
        runDesktopComposeUiTest(width = 320, height = 120) {
            setContent {
                NeutrodyneTheme(SystemUiState.DEFAULT) {
                    EnamelSign("Library", Modifier.semantics { heading() })
                }
            }
            onNode(isHeading() and hasText("Library")).assertExists()
        }

    /** With reduced motion the dial jumps to the new station instead of sweeping to it. */
    @Test
    fun reducedMotionTunesTheDialWithoutASweep() =
        runDesktopComposeUiTest(width = 300, height = 120) {
            var tuned by mutableIntStateOf(0)
            setContent {
                NeutrodyneTheme(SystemUiState.DEFAULT.copy(reducedMotion = true)) {
                    TuningDial(STATIONS, tuned, onTune = {}, tag = { "station_$it" })
                }
            }
            waitForIdle()
            mainClock.autoAdvance = false
            tuned = STATIONS.lastIndex
            repeat(3) { mainClock.advanceTimeByFrame() }
            onNodeWithTag("station_${STATIONS.lastIndex}").assertIsDisplayed()
        }

    private companion object {
        val STATIONS = (1..20).map { "Station $it" }
    }
}
