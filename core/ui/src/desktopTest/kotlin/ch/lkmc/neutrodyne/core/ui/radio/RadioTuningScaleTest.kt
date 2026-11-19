// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.radio

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.radio_duration_unknown
import ch.lkmc.neutrodyne.core.ui.resources.radio_elapsed_full
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class RadioTuningScaleTest {
    @Test fun scaleFollowsTheSelectedEpisodesDuration() =
        runDesktopComposeUiTest {
            val episode = RadioEpisode(1, "Broadcast", "Station")
            var selected by mutableStateOf<RadioEpisode?>(episode)
            var programme by mutableStateOf(listOf(episode.copy(id = 2, durationMs = 600_000)))
            var seek: Long? = null
            val unknown = getString(Res.string.radio_duration_unknown)
            setContent {
                NeutrodyneTheme(SystemUiState.DEFAULT) {
                    RadioPlayer(
                        RadioState(
                            episodes = programme,
                            selectedId = selected?.id,
                            nowPlayingEpisode = selected,
                            elapsedMs = 12_000,
                        ),
                        RadioActions(seek = { seek = it }),
                        compact = false,
                        short = false,
                    )
                }
            }

            for (duration in listOf(null, 0L, -1L)) {
                runOnIdle { selected = episode.copy(durationMs = duration) }
                onAllNodes(hasText("00:00")).assertCountEquals(0)
                onNodeWithText(unknown).assertIsDisplayed()
                onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).assertCountEquals(0)
                onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
                onNodeWithContentDescription(getString(Res.string.radio_elapsed_full, 0L, 0L, 12L)).assertIsDisplayed()
            }

            runOnIdle { programme = listOf(episode.copy(durationMs = 120_000)) }
            onNodeWithText(unknown).assertDoesNotExist()
            onNodeWithText("00:30").assertIsDisplayed()
            runOnIdle { selected = episode.copy(durationMs = 60_000) }
            onNodeWithText(unknown).assertDoesNotExist()
            onNodeWithText("00:15").assertIsDisplayed()
            onNodeWithTag("radio_seek").performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(0.5f)) }
            assertEquals(30_000L, seek)

            runOnIdle { selected = null }
            onNodeWithText(unknown).assertDoesNotExist()
            onAllNodes(hasText("00:00")).assertCountEquals(0)
            onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).assertCountEquals(0)
        }

    @Test fun swipeFromAnUnknownDurationLegendScrollsThePanel() =
        runDesktopComposeUiTest {
            val unknown = getString(Res.string.radio_duration_unknown)
            lateinit var scroll: ScrollState
            setContent {
                NeutrodyneTheme(SystemUiState.DEFAULT) {
                    scroll = rememberScrollState()
                    Column(Modifier.height(220.dp).verticalScroll(scroll)) {
                        RadioPlayer(
                            RadioState(selectedId = 1, episodes = listOf(RadioEpisode(1, "Broadcast", "Station"))),
                            RadioActions(),
                            compact = true,
                            short = true,
                        )
                        Spacer(Modifier.height(400.dp))
                    }
                }
            }
            onNodeWithText(unknown).performTouchInput {
                down(center)
                repeat(3) { moveBy(Offset(0f, -14f)) }
                up()
            }
            runOnIdle { assertTrue(scroll.value > 0) }
        }
}
