// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class NdScrollbarMinThumbDrawTest {
    private val style = NdScrollbarStyle(enabled = true)

    @Test
    fun shortList() =
        assertShortTrackDraws {
            val state = rememberLazyListState()
            LazyColumn(Modifier.fillMaxSize().ndScrollbar(state, style, Color.Red), state = state) {
                items(5) { Spacer(Modifier.height(20.dp)) }
            }
        }

    @Test
    fun shortGrid() =
        assertShortTrackDraws {
            val state = rememberLazyGridState()
            LazyVerticalGrid(
                GridCells.Fixed(1),
                Modifier.fillMaxSize().ndScrollbar(state, style, Color.Red),
                state = state,
            ) {
                items(5) { Spacer(Modifier.height(20.dp)) }
            }
        }

    @Test
    fun shortScrollable() =
        assertShortTrackDraws {
            val state = rememberScrollState()
            Box(Modifier.fillMaxSize().ndScrollbar(state, style, Color.Red).verticalScroll(state)) {
                Spacer(Modifier.height(100.dp))
            }
        }

    private fun assertShortTrackDraws(content: @Composable () -> Unit) =
        runDesktopComposeUiTest(width = 40, height = 16) {
            setContent(content)
            val pixels = onRoot().captureToImage().toPixelMap()
            assertEquals(Color.Red, pixels[pixels.width - 4, pixels.height / 2])
        }
}
