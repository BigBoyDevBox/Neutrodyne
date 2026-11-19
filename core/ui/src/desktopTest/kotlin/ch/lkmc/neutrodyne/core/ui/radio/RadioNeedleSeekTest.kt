// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.radio

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.chapter_seek
import ch.lkmc.neutrodyne.core.ui.resources.radio_elapsed_full
import ch.lkmc.neutrodyne.core.ui.resources.remote_session_minutes
import ch.lkmc.neutrodyne.core.ui.resources.remote_session_seconds
import ch.lkmc.neutrodyne.core.ui.resources.remote_session_time_join
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@OptIn(ExperimentalTestApi::class)
class RadioNeedleSeekTest {
    @Test fun needlePreviewsDragAndSeeksOnlyOnRelease() =
        runDesktopComposeUiTest(width = 390, height = 844) {
            var state by mutableStateOf(radioFixture())
            val seeks = mutableListOf<Long>()
            setContent {
                NeutrodyneTheme(SystemUiState.DEFAULT) {
                    RadioScreen(
                        state,
                        RadioActions(seek = {
                            seeks += it
                            state = state.copy(elapsedMs = it)
                        }),
                    )
                }
            }
            val needle = onNodeWithTag("radio_seek")
            needle.performTouchInput {
                down(Offset(width / 4f, centerY))
                moveTo(center)
                moveTo(Offset(width * 3 / 4f, centerY))
            }
            assertEquals(emptyList(), seeks)
            needle.assertContentDescriptionEquals(getString(Res.string.chapter_seek, "28:30"))
            val previewTime =
                getString(
                    Res.string.remote_session_time_join,
                    getPluralString(Res.plurals.remote_session_minutes, 28, 28),
                    getPluralString(Res.plurals.remote_session_seconds, 30, 30),
                )
            onNodeWithContentDescription(getString(Res.string.radio_elapsed_full, previewTime))
                .assertIsDisplayed()
            needle.performTouchInput { up() }
            waitForIdle()
            assertEquals(listOf(1_710_000L), seeks)
        }

    @Test fun aNewSelectionCancelsTheNeedleDrag() =
        runDesktopComposeUiTest(width = 390, height = 844) {
            val fixture = radioFixture()
            val duration = fixture.episodes.first().durationMs
            val episodes = fixture.episodes.map { it.copy(durationMs = duration) }
            var state by mutableStateOf(fixture.copy(episodes = episodes))
            val seeks = mutableListOf<Long>()
            setContent {
                NeutrodyneTheme(SystemUiState.DEFAULT) {
                    RadioScreen(state, RadioActions(seek = { seeks += it }))
                }
            }
            val needle = onNodeWithTag("radio_seek")
            needle.performTouchInput {
                down(Offset(width / 4f, centerY))
                moveTo(center)
            }
            needle.assertContentDescriptionEquals(getString(Res.string.chapter_seek, "19:00"))
            runOnIdle { state = state.copy(selectedId = 2L) }
            needle.performTouchInput { up() }
            waitForIdle()
            assertEquals(emptyList(), seeks)
            needle.assertContentDescriptionEquals(getString(Res.string.chapter_seek, "00:00"))
        }

    @Test fun needleTapAndAccessibleProgressSeekImmediately() =
        runDesktopComposeUiTest(width = 390, height = 844) {
            val seeks = mutableListOf<Long>()
            setContent {
                NeutrodyneTheme(SystemUiState.DEFAULT) {
                    RadioScreen(radioFixture(), RadioActions(seek = { seeks += it }))
                }
            }
            val needle = onNodeWithTag("radio_seek")
            needle.performTouchInput { click(center) }
            needle.performSemanticsAction(SemanticsActions.SetProgress) { setProgress -> setProgress(0.25f) }
            assertEquals(listOf(1_140_000L, 570_000L), seeks)
        }

    @Test fun needleVerticalSwipeScrollsWithoutSeeking() =
        runDesktopComposeUiTest(width = 320, height = 568) {
            val seeks = mutableListOf<Long>()
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1.3f)) {
                    NeutrodyneTheme(SystemUiState.DEFAULT) {
                        RadioScreen(radioFixture(), RadioActions(seek = { seeks += it }))
                    }
                }
            }
            val needle = onNodeWithTag("radio_seek").performScrollTo()
            val before = needle.fetchSemanticsNode().positionInRoot.y
            needle.performTouchInput { swipeUp() }
            assertEquals(0, seeks.size)
            assertNotEquals(before, needle.fetchSemanticsNode().positionInRoot.y)
        }

    @Test fun interceptedDragRestoresTheNeedleWithoutSeeking() =
        runDesktopComposeUiTest(width = 390, height = 844) {
            var intercept = false
            val seeks = mutableListOf<Long>()
            setContent {
                NeutrodyneTheme(SystemUiState.DEFAULT) {
                    RadioScreen(
                        radioFixture(),
                        RadioActions(seek = { seeks += it }),
                        Modifier.pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (intercept) event.changes.forEach { it.consume() }
                                }
                            }
                        },
                    )
                }
            }
            val needle = onNodeWithTag("radio_seek")
            needle.performTouchInput {
                down(Offset(width * 0.2f, height / 2f))
                moveBy(Offset(width * 0.3f, 0f))
            }
            needle.assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ProgressBarRangeInfo,
                    ProgressBarRangeInfo(0.5f, 0f..1f),
                ),
            )
            runOnIdle { intercept = true }
            needle.performTouchInput {
                moveBy(Offset(10f, 0f))
                up()
            }
            needle.assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ProgressBarRangeInfo,
                    ProgressBarRangeInfo(0f, 0f..1f),
                ),
            )
            assertEquals(0, seeks.size)
        }

    @Test fun needleDragCancelsWhenTheEpisodeChangesMidDrag() =
        runDesktopComposeUiTest(width = 390, height = 844) {
            var state by mutableStateOf(radioFixture())
            val seeks = mutableListOf<Long>()
            setContent {
                NeutrodyneTheme(SystemUiState.DEFAULT) {
                    RadioScreen(state, RadioActions(seek = { seeks += it }))
                }
            }
            val needle = onNodeWithTag("radio_seek")
            needle.performTouchInput {
                down(Offset(width * 0.2f, height / 2f))
                moveBy(Offset(width * 0.3f, 0f))
            }
            runOnIdle { state = state.copy(selectedId = 2L) }
            assertEquals(0, seeks.size)
            needle.performTouchInput { up() }
            onNodeWithTag("radio_seek").assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ProgressBarRangeInfo,
                    ProgressBarRangeInfo(0f, 0f..1f),
                ),
            )
            assertEquals(0, seeks.size)
        }

    @Test fun needleDragCancelsWhenTheDurationChangesMidDrag() =
        runDesktopComposeUiTest(width = 390, height = 844) {
            var state by mutableStateOf(radioFixture())
            val seeks = mutableListOf<Long>()
            setContent {
                NeutrodyneTheme(SystemUiState.DEFAULT) {
                    RadioScreen(state, RadioActions(seek = { seeks += it }))
                }
            }
            val needle = onNodeWithTag("radio_seek")
            needle.performTouchInput {
                down(Offset(width * 0.2f, height / 2f))
                moveBy(Offset(width * 0.3f, 0f))
            }
            runOnIdle {
                state =
                    state.copy(
                        episodes =
                            state.episodes.map {
                                if (it.id == 1L) it.copy(durationMs = 10 * 60_000L) else it
                            },
                    )
            }
            assertEquals(0, seeks.size)
            needle.performTouchInput { up() }
            onNodeWithTag("radio_seek").assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ProgressBarRangeInfo,
                    ProgressBarRangeInfo(0f, 0f..1f),
                ),
            )
            assertEquals(0, seeks.size)
        }
}
