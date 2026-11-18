// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.radio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runDesktopComposeUiTest
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import kotlin.test.Test
import kotlin.test.assertEquals

/** PO-56: each radio control plays its sound when worked, and only then. */
@OptIn(ExperimentalTestApi::class)
class RadioSoundsTest {
    private class Recorder : RadioSounds {
        val played = mutableListOf<RadioSound>()

        override fun play(sound: RadioSound) {
            played += sound
        }
    }

    private fun ComposeUiTest.radio(
        recorder: Recorder,
        content: @Composable () -> Unit,
    ) {
        setContent {
            NeutrodyneTheme(SystemUiState.DEFAULT) {
                CompositionLocalProvider(LocalRadioSounds provides recorder, content = content)
            }
        }
        waitForIdle()
    }

    @Test
    fun theBatToggleSoundsItsLeverEachWayAndNotWhenTheAppMovesIt() =
        runDesktopComposeUiTest(width = 320, height = 240) {
            val recorder = Recorder()
            var on by mutableStateOf(false)
            radio(recorder) {
                BatToggle(on, { on = !on }, "ON", "OFF", Modifier.testTag("switch"))
            }

            onNodeWithTag("switch").performClick()
            onNodeWithTag("switch").performClick()
            assertEquals(listOf(RadioSound.SWITCH_ON, RadioSound.SWITCH_OFF), recorder.played)

            // State the app sets, such as playback stopping elsewhere, flips the lever silently.
            on = true
            waitForIdle()
            assertEquals(2, recorder.played.size)
        }

    @Test
    fun aDisabledToggleStaysSilent() =
        runDesktopComposeUiTest(width = 320, height = 240) {
            val recorder = Recorder()
            radio(recorder) {
                BatToggle(false, {}, "ON", "OFF", Modifier.testTag("switch"), enabled = false)
            }

            onNodeWithTag("switch").performClick()
            assertEquals(emptyList(), recorder.played)
        }

    @Test
    fun aRowThatIsTheSwitchSoundsTheLeverThroughRememberSwitchSound() =
        runDesktopComposeUiTest(width = 320, height = 240) {
            val recorder = Recorder()
            radio(recorder) {
                var on by remember { mutableStateOf(true) }
                Box(
                    Modifier
                        .testTag("row")
                        .toggleable(on, role = Role.Switch, onValueChange = rememberSwitchSound { on = it }),
                ) {
                    BatToggleFace(on, "ON", "OFF")
                }
            }

            onNodeWithTag("row").performClick()
            onNodeWithTag("row").performClick()
            assertEquals(listOf(RadioSound.SWITCH_OFF, RadioSound.SWITCH_ON), recorder.played)
        }

    @Test
    fun bandKeysLatchAndAPressedKeyPushedAgainOnlyClicks() =
        runDesktopComposeUiTest(width = 400, height = 240) {
            val recorder = Recorder()
            radio(recorder) {
                var band by remember { mutableStateOf("AM") }
                PianoKeys(listOf("AM", "FM"), band, { band = it }, { it })
            }

            onNodeWithText("FM").performClick()
            onNodeWithText("FM").performClick()
            assertEquals(listOf(RadioSound.KEY_LATCH, RadioSound.KEY_PRESS), recorder.played)
        }

    @Test
    fun aLatchingKeyLatchesInAndClicksWhenLetGo() =
        runDesktopComposeUiTest(width = 320, height = 240) {
            val recorder = Recorder()
            radio(recorder) {
                var held by remember { mutableStateOf(false) }
                PianoKeySlot { PianoKey("Loud", held, { held = !held }, role = Role.Checkbox) }
            }

            onNodeWithText("Loud").performClick()
            onNodeWithText("Loud").performClick()
            assertEquals(listOf(RadioSound.KEY_LATCH, RadioSound.KEY_PRESS), recorder.played)
        }

    @Test
    fun momentaryActionFaceplateAndSelectorKeysClick() =
        runDesktopComposeUiTest(width = 600, height = 400) {
            val recorder = Recorder()
            val clicks = mutableListOf<String>()
            radio(recorder) {
                Column {
                    KeyBank { MomentaryKey("Clear", { clicks += "clear" }) }
                    ActionKeySlot {
                        ActionKey("Eject", { clicks += "eject" }) { Icon(NdIcons.Close, null) }
                    }
                    FaceplateKey(NdIcons.Close, "Save", { clicks += "save" })
                    EnamelSelector("Morning", { clicks += "sign" })
                }
            }

            listOf("Clear", "Eject", "Save", "Morning").forEach { onNodeWithText(it).performClick() }
            assertEquals(listOf("clear", "eject", "save", "sign"), clicks)
            assertEquals(List(4) { RadioSound.KEY_PRESS }, recorder.played)
        }

    @Test
    fun counterStepsTickAndTheEndOfTheRangeIsSilent() =
        runDesktopComposeUiTest(width = 320, height = 240) {
            val recorder = Recorder()
            radio(recorder) {
                StepCounter("Skip back", "10 s", onDecrease = null, onIncrease = {}, "Shorter", "Longer")
            }

            onNodeWithContentDescription("Longer").performClick()
            onNodeWithContentDescription("Shorter").performClick()
            assertEquals(listOf(RadioSound.DIAL_TICK), recorder.played)
        }

    @Test
    fun tuningAStationTicksTheKnob() =
        runDesktopComposeUiTest(width = 400, height = 240) {
            val recorder = Recorder()
            var tuned = -1
            radio(recorder) {
                TuningDial(listOf("Morning", "Evening"), 0, { tuned = it }, tag = { "station$it" })
            }

            onNodeWithTag("station1").performSemanticsAction(SemanticsActions.OnClick)
            assertEquals(1, tuned)
            assertEquals(listOf(RadioSound.DIAL_TICK), recorder.played)
        }

    @Test
    fun withoutAProviderTheControlsWorkSilently() =
        runDesktopComposeUiTest(width = 320, height = 240) {
            var on by mutableStateOf(false)
            setContent {
                NeutrodyneTheme(SystemUiState.DEFAULT) {
                    BatToggle(on, { on = !on }, "ON", "OFF", Modifier.testTag("switch"))
                }
            }

            onNodeWithTag("switch").performClick()
            assertEquals(true, on)
        }
}
