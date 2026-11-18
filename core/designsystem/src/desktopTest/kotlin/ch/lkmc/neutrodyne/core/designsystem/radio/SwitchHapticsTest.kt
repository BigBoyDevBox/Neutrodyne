// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.radio

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import kotlin.test.Test
import kotlin.test.assertEquals

/** The bat switches thunk under the listener's own toggles, never under a value set from outside. */
@OptIn(ExperimentalTestApi::class)
class SwitchHapticsTest {
    @Test
    fun theLeverIsFeltOnlyWhenTheListenerThrowsIt() =
        runDesktopComposeUiTest(width = 200, height = 200) {
            val haptics = RecordingHaptics()
            var on by mutableStateOf(false)
            setContent {
                NeutrodyneTheme(SystemUiState.DEFAULT) {
                    CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                        BatToggle(on, { on = !on }, "On", "Off", Modifier.testTag("lever").size(54.dp, 64.dp))
                    }
                }
            }

            onNodeWithTag("lever").performClick()
            onNodeWithTag("lever").performClick()
            assertEquals(listOf(HapticFeedbackType.ToggleOn, HapticFeedbackType.ToggleOff), haptics.felt)

            // Playback that stops on its own moves the lever silently.
            on = true
            waitForIdle()
            on = false
            waitForIdle()
            assertEquals(2, haptics.felt.size)
        }

    @Test
    fun aSwitchRowFeelsTheValueItTurnsTo() =
        runDesktopComposeUiTest(width = 200, height = 200) {
            val haptics = RecordingHaptics()
            var on by mutableStateOf(true)
            setContent {
                CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                    BasicText(
                        "Row",
                        Modifier
                            .testTag("row")
                            .toggleable(on, role = Role.Switch, onValueChange = withSwitchHaptics { on = it }),
                    )
                }
            }

            onNodeWithTag("row").performClick()
            assertEquals(false, on)
            assertEquals(listOf(HapticFeedbackType.ToggleOff), haptics.felt)
        }

    private class RecordingHaptics : HapticFeedback {
        val felt = mutableListOf<HapticFeedbackType>()

        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            felt += hapticFeedbackType
        }
    }
}
