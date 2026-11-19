// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.radio

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The valves warm up while the radio is ON: the glass above each filament brightens over about
 * two seconds and dims over one, at once with reduced motion, and the unlit deck stays the
 * artwork as rendered.
 */
@OptIn(ExperimentalTestApi::class)
class ValveDeckGlowTest {
    @Test
    fun theValvesWarmUpGraduallyWhenTheRadioIsSwitchedOn() =
        runDesktopComposeUiTest(width = DECK_WIDTH, height = DECK_HEIGHT) {
            var lit by mutableStateOf(false)
            setDeck({ false }) { lit }
            val cold = glassBrightness()

            mainClock.autoAdvance = false
            lit = true
            mainClock.advanceTimeBy(FRAME_MS * 3)
            val warming = glassBrightness()
            mainClock.advanceTimeBy(WARM_UP_MS)
            val warm = glassBrightness()

            assertTrue(warming < warm - 1f, "warming up takes time: cold $cold, warming $warming, warm $warm")
            assertTrue(warm > cold + MIN_GLOW, "a lit valve glows: cold $cold, warm $warm")
        }

    @Test
    fun reducedMotionLightsTheValvesAtOnce() =
        runDesktopComposeUiTest(width = DECK_WIDTH, height = DECK_HEIGHT) {
            var lit by mutableStateOf(false)
            setDeck({ true }) { lit }
            val cold = glassBrightness()

            mainClock.autoAdvance = false
            lit = true
            mainClock.advanceTimeBy(FRAME_MS * 2)

            assertTrue(glassBrightness() > cold + MIN_GLOW, "reduced motion skips the warm-up")
        }

    @Test
    fun turningOnReducedMotionFinishesAWarmUpAtOnce() =
        runDesktopComposeUiTest(width = DECK_WIDTH, height = DECK_HEIGHT) {
            var lit by mutableStateOf(false)
            var reduced by mutableStateOf(false)
            setDeck({ reduced }) { lit }

            mainClock.autoAdvance = false
            lit = true
            mainClock.advanceTimeBy(FRAME_MS * 3)
            val warming = glassBrightness()
            reduced = true
            mainClock.advanceTimeBy(FRAME_MS * 2)
            val switched = glassBrightness()
            mainClock.advanceTimeBy(WARM_UP_MS)
            val warm = glassBrightness()

            assertTrue(
                switched > warm - 1f,
                "reduced motion ends a warm-up in flight: warming $warming, switched $switched, warm $warm",
            )
        }

    @Test
    fun theValvesCoolDownGraduallyWhenTheRadioIsSwitchedOff() =
        runDesktopComposeUiTest(width = DECK_WIDTH, height = DECK_HEIGHT) {
            var lit by mutableStateOf(true)
            setDeck({ false }) { lit }
            val warm = glassBrightness()

            mainClock.autoAdvance = false
            lit = false
            mainClock.advanceTimeBy(FRAME_MS * 3)
            val cooling = glassBrightness()
            mainClock.advanceTimeBy(COOL_DOWN_MS)
            val dark = glassBrightness()

            assertTrue(cooling > dark + 1f, "cooling takes time: warm $warm, cooling $cooling, dark $dark")
            assertTrue(dark < warm - MIN_GLOW, "a switched-off valve goes dark: warm $warm, dark $dark")
        }

    private fun DesktopComposeUiTest.setDeck(
        reducedMotion: () -> Boolean,
        lit: () -> Boolean,
    ) {
        setContent {
            NeutrodyneTheme(SystemUiState.DEFAULT.copy(reducedMotion = reducedMotion())) {
                ValveDeck(Modifier.testTag(TAG).size(DECK_WIDTH.dp, DECK_HEIGHT.dp), lit = lit())
            }
        }
        waitForIdle()
    }

    /** Mean red plus green in the glass just above the left filament (art pixels 250..290, 160..190). */
    private fun DesktopComposeUiTest.glassBrightness(): Float {
        val pixels = onNodeWithTag(TAG).captureToImage().toPixelMap()
        val scale = pixels.width / ART_WIDTH
        var sum = 0f
        var count = 0
        for (x in (250 * scale).toInt()..(290 * scale).toInt()) {
            for (y in (160 * scale).toInt()..(190 * scale).toInt()) {
                val colour = pixels[x, y]
                sum += (colour.red + colour.green) * 255f
                count++
            }
        }
        return sum / count
    }

    private companion object {
        const val TAG = "deck"
        const val ART_WIDTH = 1100f
        const val DECK_WIDTH = 550
        const val DECK_HEIGHT = 212
        const val FRAME_MS = 16L

        // Past the 1.8 s warm-up and 0.9 s cool-down, so each tween has certainly finished.
        const val WARM_UP_MS = 2_000L
        const val COOL_DOWN_MS = 1_000L
        const val MIN_GLOW = 15f
    }
}
