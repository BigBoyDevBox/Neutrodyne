// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.navigation

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import ch.lkmc.neutrodyne.core.designsystem.theme.LocalReducedMotion
import ch.lkmc.neutrodyne.core.navigation.DiscoverKey
import ch.lkmc.neutrodyne.core.navigation.DownloadsKey
import ch.lkmc.neutrodyne.core.navigation.EntryProviderInstaller
import ch.lkmc.neutrodyne.core.navigation.EpisodeKey
import ch.lkmc.neutrodyne.core.navigation.FeedsKey
import ch.lkmc.neutrodyne.core.navigation.LibraryKey
import ch.lkmc.neutrodyne.core.navigation.LicencesKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.NdSceneMetadata
import ch.lkmc.neutrodyne.core.navigation.PodcastKey
import ch.lkmc.neutrodyne.core.navigation.SettingsHomeKey
import ch.lkmc.neutrodyne.core.navigation.SettingsKey
import ch.lkmc.neutrodyne.core.navigation.SettingsPage
import ch.lkmc.neutrodyne.core.navigation.UpNextKey
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The owner-facing transition contract (08 Transitions and shared elements) exercised against the
 * real Android `NavDisplay` runtime under Robolectric native graphics: frames advance through the
 * test clock and [captureToImage] reads the pixels Skia actually drew, so mid-transition state is
 * observable here even though the desktop Skiko harness settles every spec instantly.
 *
 * `defaultsHarness*` reproduces the pre-fix wiring verbatim — `NavDisplay` without transition
 * specs, which is what shipped when the owner reported "everything seems to be fades" — as
 * pinned-down diagnostics of the runtime defaults the fix replaced. The fixed-host tests cover
 * the three owner requirements: tab taps show no transition, a real back pops with a horizontal
 * wipe that reveals the previous entry underneath, and a predictive-back gesture seeks that same
 * wipe and restores the current scene exactly on cancel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("TooManyFunctions") // test suites accumulate test + fixture functions
class NavHostTransitionHostTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var navigation: NavigationState
    private var dispatcher: NavigationEventDispatcher? = null

    // region fixed host (the real NeutrodyneNavHost wiring)

    @Test
    fun tabSwitchSettlesWithinASwapFrame() {
        setFixedHost()

        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { navigation.selectTab(LibraryKey) }

        // Instant: the outgoing tab's pixels are gone within a few frames and no blend ever
        // appears. The pre-fix 700 ms fade blended for ~28 frames and lingered ~47.
        var blendSeen = false
        val swapped =
            advanceFramesUntil(INSTANT_FRAME_BOUND) {
                val pixels = rule.onRoot().captureToImage().toPixelMap()
                if (hasBlendedPixels(pixels, FEEDS_COLOR, LIBRARY_COLOR)) blendSeen = true
                leftEdgeOf(pixels, FEEDS_COLOR) == -1 && leftEdgeOf(pixels, LIBRARY_COLOR) == 0
            }
        assertTrue(swapped, "tab switch still in flight after $INSTANT_FRAME_BOUND frames")
        assertTrue(!blendSeen, "tab switch cross-faded")

        // Composition teardown trails the visual swap by a couple of frames on this runtime.
        assertTrue(
            advanceFramesUntil(TEARDOWN_FRAME_BOUND) {
                rule.onAllNodesWithTag(FEEDS_TAG).fetchSemanticsNodes().isEmpty()
            },
            "feeds entry still composed",
        )
        assertTrue(
            rule.onAllNodesWithTag(LIBRARY_TAG).fetchSemanticsNodes().isNotEmpty(),
            "library entry not composed",
        )
    }

    @Test
    fun backPopWipesRightAndRevealsThePreviousEntry() {
        setFixedHost()
        rule.runOnUiThread { navigation.push(PodcastKey(3)) }
        settle()
        rule.mainClock.autoAdvance = false

        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }

        // A wipe: at some mid-transition frame the outgoing detail is translated right of its
        // rest edge, fully opaque, with the previous tab revealed underneath from the left.
        // A fade (the pre-fix default) never moves it and blends every pixel.
        val wiped =
            advanceFramesUntil(WIPE_FRAME_BOUND) {
                val pixels = rule.onRoot().captureToImage().toPixelMap()
                val left = leftEdgeOf(pixels, PODCAST_COLOR)
                left > 0 &&
                    leftEdgeOf(pixels, FEEDS_COLOR) == 0 &&
                    pixels[left + 2, pixels.height / 2].isCloseTo(PODCAST_COLOR)
            }
        assertTrue(wiped, "no frame showed the detail wiping right over the revealed feeds")

        settle()
        assertTrue(rule.onAllNodesWithTag(PODCAST_TAG).fetchSemanticsNodes().isEmpty())
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        assertEquals(-1, leftEdgeOf(pixels, PODCAST_COLOR), "detail still painted after the pop")
        assertEquals(0, leftEdgeOf(pixels, FEEDS_COLOR), "feeds does not fill the window")
    }

    @Test
    fun predictiveBackSeeksTheWipeAndCancelRestoresTheScene() {
        setFixedHost()
        rule.runOnUiThread { navigation.push(PodcastKey(3)) }
        settle()
        val input = DirectNavigationEventInput()
        val eventDispatcher =
            assertNotNull(dispatcher, "no NavigationEventDispatcher owner in the host")
        rule.runOnUiThread { eventDispatcher.addInput(input) }
        rule.mainClock.autoAdvance = false

        // The same event path a system predictive-back gesture drives: started, then progress.
        rule.runOnUiThread {
            input.backStarted(NavigationEvent(NavigationEvent.EDGE_LEFT, 0f, 16f, 400f))
            input.backProgressed(NavigationEvent(NavigationEvent.EDGE_LEFT, 0.45f, 300f, 400f))
        }

        val seeked =
            advanceFramesUntil(GESTURE_FRAME_BOUND) {
                val pixels = rule.onRoot().captureToImage().toPixelMap()
                leftEdgeOf(pixels, PODCAST_COLOR) > 0 && leftEdgeOf(pixels, FEEDS_COLOR) == 0
            }
        assertTrue(seeked, "predictive progress did not seek the wipe")

        rule.runOnUiThread { input.backCancelled() }

        // Cancel restores exactly: the detail returns to its rest edge and feeds is not visible.
        val restored =
            advanceFramesUntil(GESTURE_FRAME_BOUND) {
                val pixels = rule.onRoot().captureToImage().toPixelMap()
                leftEdgeOf(pixels, PODCAST_COLOR) == 0 && leftEdgeOf(pixels, FEEDS_COLOR) == -1
            }
        assertTrue(restored, "cancel did not restore the detail over feeds")

        settle()
        assertTrue(rule.onAllNodesWithTag(FEEDS_TAG).fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithTag(PODCAST_TAG).fetchSemanticsNodes().isNotEmpty())
    }

    // endregion

    // region expanded two-pane (Settings Home -> About -> Licences)

    /**
     * The owner's expanded-layout case: in a two-pane scaffold a Settings Home -> About -> Licences
     * chain keeps one `ThreePaneScaffoldScene`, so a single-entry pop swaps detail-pane content
     * inside the same scene — the outer scene transform cannot cover it. `navigation.pop()` is
     * the in-app back path here (a system back goes through the scaffold's
     * `PopUntilScaffoldValueChange` handler, which pops to a scaffold boundary instead). The pane
     * wipe must translate the outgoing detail toward the layout end and reveal the previous
     * detail underneath, while the list pane stays anchored (a whole-master swipe would move it).
     */
    @Test
    fun expandedBackPopWipesInsideTheDetailPane() {
        setFixedHost(directive = TwoPaneDirective)
        rule.runOnUiThread { pushSettingsChain() }
        settle()
        val rest = rule.onRoot().captureToImage().toPixelMap()
        val detailRest = leftEdgeOf(rest, LICENCES_COLOR)
        assertTrue(detailRest > 0, "expected a two-pane layout")

        rule.runOnUiThread { navigation.pop() }

        val wiped =
            advanceFramesUntil(WIPE_FRAME_BOUND) {
                val pixels = rule.onRoot().captureToImage().toPixelMap()
                leftEdgeOf(pixels, LICENCES_COLOR) > detailRest &&
                    leftEdgeOf(pixels, ABOUT_COLOR) == detailRest &&
                    leftEdgeOf(pixels, SETTINGS_COLOR) == 0
            }
        assertTrue(wiped, "no frame showed the detail wiping right inside its pane")

        settle()
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        assertEquals(-1, leftEdgeOf(pixels, LICENCES_COLOR), "detail still painted after the pop")
        assertEquals(detailRest, leftEdgeOf(pixels, ABOUT_COLOR))
        assertEquals(0, leftEdgeOf(pixels, SETTINGS_COLOR))
    }

    /** The covering half of the pair: a detail push slides the new pane content in from the end. */
    @Test
    fun expandedDetailPushCoversThePane() {
        setFixedHost(directive = TwoPaneDirective)
        rule.runOnUiThread {
            navigation.push(SettingsHomeKey)
            navigation.push(SettingsKey(SettingsPage.ABOUT))
        }
        settle()
        val rest = rule.onRoot().captureToImage().toPixelMap()
        val detailRest = leftEdgeOf(rest, ABOUT_COLOR)
        assertTrue(detailRest > 0, "expected a two-pane layout")

        rule.runOnUiThread { navigation.push(LicencesKey) }

        val covered =
            advanceFramesUntil(WIPE_FRAME_BOUND) {
                val pixels = rule.onRoot().captureToImage().toPixelMap()
                val entering = leftEdgeOf(pixels, LICENCES_COLOR)
                entering > detailRest &&
                    leftEdgeOf(pixels, ABOUT_COLOR) == detailRest &&
                    leftEdgeOf(pixels, SETTINGS_COLOR) == 0
            }
        assertTrue(covered, "no frame showed the new detail covering toward the pane start")

        settle()
        assertEquals(detailRest, leftEdgeOf(rule.onRoot().captureToImage().toPixelMap(), LICENCES_COLOR))
    }

    /**
     * Positive control: the same back action with plain (unroled) entries still wipes through the
     * outer scene transition — proving the fixture observes animation whenever one runs.
     */
    @Test
    fun backPopWithoutPaneRolesStillWipesTheScene() {
        setFixedHost(installers = DistinctInstallers, directive = TwoPaneDirective)
        rule.runOnUiThread { pushSettingsChain() }
        settle()

        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }

        val wiped =
            advanceFramesUntil(WIPE_FRAME_BOUND) {
                val pixels = rule.onRoot().captureToImage().toPixelMap()
                leftEdgeOf(pixels, LICENCES_COLOR) > 0 && leftEdgeOf(pixels, ABOUT_COLOR) == 0
            }
        assertTrue(wiped, "distinct-scene pop did not produce the outer wipe")
    }

    /**
     * A predictive-back gesture on this stack is scaffold-owned, not a pane wipe: the strategy's
     * `PopUntilScaffoldValueChange` lands on a scaffold-value boundary (here the detail pane
     * closes and the pop skips both detail entries), so the pane-content boundary must NOT
     * preview an in-pane wipe — About must never be revealed under the gesture — and cancelling
     * restores the scene exactly.
     */
    @Test
    fun expandedPredictiveBackIsScaffoldOwnedNotAPaneWipe() {
        setFixedHost(directive = TwoPaneDirective)
        rule.runOnUiThread { pushSettingsChain() }
        settle()
        val input = DirectNavigationEventInput()
        val eventDispatcher =
            assertNotNull(dispatcher, "no NavigationEventDispatcher owner in the host")
        rule.runOnUiThread { eventDispatcher.addInput(input) }
        rule.mainClock.autoAdvance = false

        rule.runOnUiThread {
            input.backStarted(NavigationEvent(NavigationEvent.EDGE_LEFT, 0f, 16f, 400f))
            input.backProgressed(NavigationEvent(NavigationEvent.EDGE_LEFT, 0.45f, 300f, 400f))
        }

        // The list pane stays anchored and no wrong landing is previewed underneath.
        var aboutSeen = false
        var settingsMoved = false
        repeat(GESTURE_FRAME_BOUND) {
            rule.mainClock.advanceTimeByFrame()
            val pixels = rule.onRoot().captureToImage().toPixelMap()
            if (leftEdgeOf(pixels, ABOUT_COLOR) != -1) aboutSeen = true
            if (leftEdgeOf(pixels, SETTINGS_COLOR) != 0) settingsMoved = true
        }
        assertTrue(!aboutSeen, "the gesture previewed a wrong in-pane landing")
        assertTrue(!settingsMoved, "the list pane moved during the gesture")
        assertTrue(
            rule.onAllNodesWithTag(LICENCES_TAG).fetchSemanticsNodes().isNotEmpty(),
            "licences left composition while the gesture was still cancellable",
        )

        rule.runOnUiThread { input.backCancelled() }
        settle()
        assertTrue(rule.onAllNodesWithTag(SETTINGS_TAG).fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithTag(LICENCES_TAG).fetchSemanticsNodes().isNotEmpty())
    }

    /**
     * The outgoing pane's owners are load-bearing: while the wipe draws it, the outgoing entry
     * must still be the same composed emission — same ViewModel, same ViewModelStoreOwner, same
     * `rememberSaveable` value — with no disappearance/re-entry frame (the destroyed-and-
     * recreated signature), and the popped entry's ViewModel must be cleared exactly once, after
     * the wipe exits. The single-pane case needs no assertion here: `NavDisplay`'s own scene
     * transition already holds the outgoing emission composed.
     */
    @Test
    fun expandedBackPopKeepsTheOutgoingOwnerUntilTheWipeExits() {
        OwnerProbe.reset()
        setFixedHost(installers = OwnerInstallers, directive = TwoPaneDirective)
        rule.runOnUiThread { pushSettingsChain() }
        settle()
        rule.onNodeWithTag("$LICENCES_TAG-inc").performClick()
        settle()
        val original = assertNotNull(OwnerProbe.snapshots[LICENCES_TAG], "licences page never composed")
        assertEquals(1, original.saved)

        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { navigation.pop() }

        var exited = false
        var outgoingSeen = false
        advanceFramesUntil(WIPE_FRAME_BOUND * 2) {
            val present = rule.onAllNodesWithTag(LICENCES_TAG).fetchSemanticsNodes().isNotEmpty()
            if (present) {
                assertTrue(!exited, "licences re-entered composition mid-wipe")
                outgoingSeen = true
                val now = OwnerProbe.snapshots[LICENCES_TAG]
                assertEquals(original.vm, now?.vm, "outgoing ViewModel was recreated")
                assertEquals(original.owner, now?.owner, "outgoing ViewModelStoreOwner changed")
                assertEquals(1, now?.saved, "outgoing rememberSaveable state was reset")
            } else {
                exited = true
            }
            assertTrue(
                exited || original.vm !in OwnerProbe.cleared,
                "outgoing ViewModel cleared while the wipe still drew it",
            )
            exited
        }
        assertTrue(outgoingSeen, "the wipe never drew the outgoing page")
        assertTrue(exited, "the outgoing emission never exited the wipe")

        settle()
        assertTrue(rule.onAllNodesWithTag(LICENCES_TAG).fetchSemanticsNodes().isEmpty())
        assertEquals(
            1,
            OwnerProbe.cleared.count { it == original.vm },
            "original ViewModel must clear exactly once after the wipe exits",
        )
        assertEquals(
            SettingsKey(SettingsPage.ABOUT),
            navigation.stack(FeedsKey).last(),
            "the pop must land on About in the same stack",
        )
        assertTrue(rule.onAllNodesWithTag(SETTINGS_TAG).fetchSemanticsNodes().isNotEmpty())
    }

    /** RTL resolves End to the left: the wipe recedes through the pane's start side. */
    @Test
    fun expandedBackPopWipesTowardTheLayoutEnd() {
        setFixedHost(directive = TwoPaneDirective, layoutDirection = LayoutDirection.Rtl)
        rule.runOnUiThread { pushSettingsChain() }
        settle()
        val rest = rule.onRoot().captureToImage().toPixelMap()
        val restRight = rightEdgeOf(rest, LICENCES_COLOR)
        assertTrue(restRight > 0, "expected licences painted")

        rule.runOnUiThread { navigation.pop() }

        val wiped =
            advanceFramesUntil(WIPE_FRAME_BOUND) {
                val pixels = rule.onRoot().captureToImage().toPixelMap()
                val receding = rightEdgeOf(pixels, LICENCES_COLOR)
                receding in 1 until restRight && leftEdgeOf(pixels, ABOUT_COLOR) != -1
            }
        assertTrue(wiped, "no frame showed the detail receding toward the layout end in RTL")
    }

    private fun pushSettingsChain() {
        navigation.push(SettingsHomeKey)
        navigation.push(SettingsKey(SettingsPage.ABOUT))
        navigation.push(LicencesKey)
    }

    // endregion

    // region pin interruption (retargeted swaps, scene back, recreation)

    /**
     * Re-pushing the detail four frames into its own wipe: the pop's pin and the push's pin
     * overlap, and both must drop rather than strand the still-live About entry — its ViewModel
     * and `rememberSaveable` state must survive the whole cycle.
     */
    @Test
    fun expandedRepushDuringWipeKeepsTheLiveStackIntact() {
        OwnerProbe.reset()
        setFixedHost(installers = ProbedInstallers, directive = TwoPaneDirective)
        rule.runOnUiThread {
            navigation.push(SettingsHomeKey)
            navigation.push(SettingsKey(SettingsPage.ABOUT))
        }
        settle()
        rule.onNodeWithTag("$ABOUT_PAGE_TAG-inc").performClick()
        settle()
        val originalAbout =
            assertNotNull(OwnerProbe.snapshots[ABOUT_PAGE_TAG], "about page never composed")
        rule.runOnUiThread { navigation.push(LicencesKey) }
        settle()

        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { navigation.pop() }
        advanceFrames(4)
        assertTrue(
            rule.onAllNodesWithTag(LICENCES_TAG).fetchSemanticsNodes().isNotEmpty(),
            "pin not active when the interrupt begins",
        )
        rule.runOnUiThread { navigation.push(LicencesKey) }
        advanceFrames(8)
        settle()

        assertEquals(LicencesKey, navigation.stack(FeedsKey).last())
        val about = OwnerProbe.snapshots[ABOUT_PAGE_TAG]
        assertEquals(originalAbout.vm, about?.vm, "live About ViewModel was cleared mid-swap")
        assertTrue(originalAbout.vm !in OwnerProbe.cleared, "live About VM was cleared")

        // The covered-page preview legitimately shows the initial `saved` value while About
        // emits inside the transport — the state itself must survive in the holder: popping
        // re-tops About at its callsite, where rememberSaveable restores it.
        rule.mainClock.autoAdvance = true
        rule.runOnUiThread { navigation.pop() }
        settle()
        assertEquals(SettingsKey(SettingsPage.ABOUT), navigation.stack(FeedsKey).last())
        assertEquals(
            1,
            OwnerProbe.snapshots[ABOUT_PAGE_TAG]?.saved,
            "live About rememberSaveable state was lost",
        )
    }

    /**
     * A push into a pane mid-wipe retargets the swap: the old pin must release rather than
     * overlap the covered entry's new emission — a second `SaveableStateProvider` for the same
     * key crashes the composition.
     */
    @Test
    fun expandedPushDuringWipeDoesNotDuplicatePaneEmissions() {
        setFixedHost(installers = ProbedInstallers, directive = TwoPaneDirective)
        // Push step by step so About emits at the detail callsite before Licences lands — a
        // batched push never tops About, leaving its contentKey out of the emitted set and
        // hiding the retargeted-swap defect.
        rule.runOnUiThread {
            navigation.push(SettingsHomeKey)
            navigation.push(SettingsKey(SettingsPage.ABOUT))
        }
        settle()
        rule.runOnUiThread { navigation.push(LicencesKey) }
        settle()

        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { navigation.pop() }
        advanceFrames(4)
        assertTrue(
            rule.onAllNodesWithTag(LICENCES_TAG).fetchSemanticsNodes().isNotEmpty(),
            "pin not active when the interrupt begins",
        )
        rule.runOnUiThread { navigation.push(SettingsKey(SettingsPage.APPEARANCE)) }
        advanceFrames(12)
        settle()

        assertEquals(SettingsKey(SettingsPage.APPEARANCE), navigation.stack(FeedsKey).last())
        assertTrue(
            rule.onAllNodesWithTag(APPEARANCE_PAGE_TAG).fetchSemanticsNodes().isNotEmpty(),
            "the retargeted detail never rendered",
        )
    }

    /**
     * The scaffold's back handling counts `allEntries - previousEntries`; the transient pin is
     * not a real back entry. A predictive commit mid-wipe must still pop exactly to the scaffold
     * boundary (About), not one entry further (SettingsHome).
     */
    @Test
    fun expandedPredictiveCommitDoesNotCountThePinnedEntry() {
        setFixedHost(installers = ProbedInstallers, directive = TwoPaneDirective)
        rule.runOnUiThread {
            navigation.push(SettingsHomeKey)
            navigation.push(SettingsKey(SettingsPage.ABOUT))
            navigation.push(EpisodeKey(1))
            navigation.push(EpisodeKey(2))
        }
        settle()

        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { navigation.pop() }
        advanceFrames(4)
        val input = DirectNavigationEventInput()
        val eventDispatcher =
            assertNotNull(dispatcher, "no NavigationEventDispatcher owner in the host")
        rule.runOnUiThread {
            eventDispatcher.addInput(input)
            input.backStarted(NavigationEvent(NavigationEvent.EDGE_LEFT, 0f, 16f, 400f))
            input.backProgressed(NavigationEvent(NavigationEvent.EDGE_LEFT, 0.4f, 300f, 400f))
        }
        advanceFrames(2)
        rule.runOnUiThread { input.backCompleted() }
        advanceFrames(12)
        settle()

        assertEquals(
            SettingsKey(SettingsPage.ABOUT),
            navigation.stack(FeedsKey).last(),
            "the scaffold counted the transient pin as a real back entry",
        )
    }

    /**
     * Instant back takes the same `repeat(count) { onBack() }` path as a completed predictive
     * gesture — a started+completed input with no progress events. On the extra-pane fixture
     * the scaffold boundary counts two entries (the departing ghost plus the live entry under
     * it), so the commit must drain the ghost and pop once, landing on About.
     */
    @Test
    fun expandedSystemBackDuringWipePopsOnlyRealEntries() {
        setFixedHost(installers = ProbedInstallers, directive = TwoPaneDirective)
        rule.runOnUiThread {
            navigation.push(SettingsHomeKey)
            navigation.push(SettingsKey(SettingsPage.ABOUT))
            navigation.push(EpisodeKey(1))
            navigation.push(EpisodeKey(2))
        }
        settle()

        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { navigation.pop() }
        advanceFrames(4)
        val input = DirectNavigationEventInput()
        val eventDispatcher =
            assertNotNull(dispatcher, "no NavigationEventDispatcher owner in the host")
        rule.runOnUiThread {
            eventDispatcher.addInput(input)
            input.backStarted(NavigationEvent(NavigationEvent.EDGE_LEFT, 0f, 16f, 400f))
            input.backCompleted()
        }
        advanceFrames(12)
        settle()

        assertEquals(
            SettingsKey(SettingsPage.ABOUT),
            navigation.stack(FeedsKey).last(),
            "system back counted the transient departing entry as a real back entry",
        )
    }

    /**
     * Recreation discards the remembered pin, but the decorators still counted the pinned key
     * as a stack member at disposal and skipped its pop cleanup — the popped ViewModel and its
     * saved state then orphaned into the activity-retained stores. Reopening must see a fresh
     * owner, and the original must have been cleared exactly once.
     */
    @Test
    fun expandedRecreationDuringWipeClearsThePoppedOwner() {
        OwnerProbe.reset()
        setFixedHost(installers = ProbedInstallers, directive = TwoPaneDirective)
        rule.runOnUiThread { pushSettingsChain() }
        settle()
        val original =
            assertNotNull(OwnerProbe.snapshots[LICENCES_TAG], "licences page never composed")

        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { navigation.pop() }
        advanceFrames(4)

        rule.activityRule.scenario.recreate()
        rule.activityRule.scenario.onActivity {
            it.setContent(content = requireNotNull(hostContent))
        }
        settle()

        assertEquals(SettingsKey(SettingsPage.ABOUT), navigation.stack(FeedsKey).last())
        assertTrue(LicencesKey !in navigation.stack(FeedsKey))
        advanceFrames(64)
        rule.runOnUiThread { navigation.push(LicencesKey) }
        settle()

        assertEquals(
            1,
            OwnerProbe.cleared.count { it == original.vm },
            "popped original ViewModel leaked across the configuration change",
        )
        assertTrue(
            original.vm != OwnerProbe.snapshots[LICENCES_TAG]?.vm,
            "the reopened licences entry reused the popped ViewModel",
        )
    }

    // endregion

    // region retargets, compact release and same-class replace

    /**
     * Mid-wipe retarget (the zombie-pin regression): popping Licences then pushing Podcast
     * three frames into the wipe commits Licences' departure immediately — it must never
     * ghost back while the Podcast pop wipes, and its owner must clear exactly once.
     */
    @Test
    fun expandedPushDuringWipeDoesNotGhostThePoppedEntry() {
        OwnerProbe.reset()
        setFixedHost(installers = ProbedInstallers, directive = TwoPaneDirective)
        rule.runOnUiThread { pushSettingsChain() }
        settle()
        val original =
            assertNotNull(OwnerProbe.snapshots[LICENCES_TAG], "licences page never composed")

        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { navigation.pop() }
        advanceFrames(3)
        rule.runOnUiThread { navigation.push(PodcastKey(9)) }
        advanceFrames(3)
        rule.runOnUiThread { navigation.pop() }

        val ghosted =
            advanceFramesUntil(GHOST_WATCH_FRAME_BOUND) {
                rule.onAllNodesWithTag(LICENCES_TAG).fetchSemanticsNodes().isNotEmpty()
            }
        assertTrue(!ghosted, "licences re-appeared after its wipe was retargeted")
        settle()

        assertEquals(SettingsKey(SettingsPage.ABOUT), navigation.stack(FeedsKey).last())
        assertTrue(LicencesKey !in navigation.stack(FeedsKey))
        assertEquals(
            1,
            OwnerProbe.cleared.count { it == original.vm },
            "licences ViewModel must clear exactly once",
        )
    }

    /**
     * A layout collapse before the pop leaves no pane slot to claim the popped entry: the
     * departure must commit at the first unslotted emission instead of pinning the popped
     * screen visibly on the real stack until the 800 ms expiry backstop.
     */
    @Test
    fun compactPopCommitsThePoppedDetailPromptly() {
        val directive = setFixedHost(installers = ProbedInstallers, directive = TwoPaneDirective)
        rule.runOnUiThread { pushSettingsChain() }
        settle()
        rule.runOnUiThread { directive.value = PaneScaffoldDirective.Default }
        settle()

        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { navigation.pop() }

        val framesToGone = framesUntilAbsent(LICENCES_TAG, PROMPT_RELEASE_FRAME_BOUND)
        assertTrue(
            framesToGone < PROMPT_RELEASE_FRAME_BOUND,
            "popped licences stayed visible ${framesToGone}f on the one-pane layout",
        )
        settle()
        assertEquals(SettingsKey(SettingsPage.ABOUT), navigation.stack(FeedsKey).last())
        assertTrue(LicencesKey !in navigation.stack(FeedsKey))
    }

    /**
     * The same release when the layout collapses mid-wipe: the in-flight departure commits
     * as soon as the one-pane scene emits it unslotted.
     */
    @Test
    fun compactResizeMidWipeCommitsTheDeparturePromptly() {
        val directive = setFixedHost(installers = ProbedInstallers, directive = TwoPaneDirective)
        rule.runOnUiThread { pushSettingsChain() }
        settle()

        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { navigation.pop() }
        advanceFrames(3)
        rule.runOnUiThread { directive.value = PaneScaffoldDirective.Default }

        // The deferred pop commits at the first unslotted emission; what remains visible is
        // only the ordinary collapse-cover and pop-wipe animations, not a dead pin.
        advanceFrames(4)
        assertTrue(
            LicencesKey !in navigation.stack(FeedsKey),
            "the departing entry stayed on the real stack after the collapse",
        )
        val framesToGone = framesUntilAbsent(LICENCES_TAG, COLLAPSE_EXIT_FRAME_BOUND)
        assertTrue(
            framesToGone < COLLAPSE_EXIT_FRAME_BOUND,
            "the departing entry stayed visible ${framesToGone}f after the collapse",
        )
        settle()
        assertTrue(LicencesKey !in navigation.stack(FeedsKey))
    }

    /**
     * Stale emitted marks must not hide a compact push: with no pane to cover, the pushed
     * detail appears as the scene's top immediately — a cover pin would only strand the
     * outgoing on a slot that does not exist.
     */
    @Test
    fun compactPushShowsThePushedDetailPromptly() {
        val directive = setFixedHost(installers = ProbedInstallers, directive = TwoPaneDirective)
        rule.runOnUiThread { pushSettingsChain() }
        settle()
        rule.runOnUiThread { directive.value = PaneScaffoldDirective.Default }
        settle()

        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { navigation.push(PodcastKey(9)) }

        val framesToShown = framesUntilPresent(PODCAST_TAG, PROMPT_RELEASE_FRAME_BOUND)
        assertTrue(
            framesToShown < PROMPT_RELEASE_FRAME_BOUND,
            "the pushed detail stayed hidden ${framesToShown}f under a one-pane layout",
        )
        settle()
        assertEquals(PodcastKey(9), navigation.stack(FeedsKey).last())
    }

    /**
     * `pushDetail` swaps a same-class detail in place as forward navigation: the newcomer
     * covers over the still-painted outgoing rather than wiping the outgoing out over it.
     */
    @Test
    fun expandedSameClassDetailReplaceCoversForward() {
        setFixedHost(installers = ProbedInstallers, directive = TwoPaneDirective)
        rule.runOnUiThread {
            // NeutrodyneRoot mirrors the scaffold partition count onto the state — the
            // same-class replace only applies under a multi-pane layout.
            navigation.panePartitions = 2
            navigation.push(SettingsHomeKey)
            navigation.push(PodcastKey(1))
        }
        settle()
        rule.mainClock.autoAdvance = false
        val detailRest = leftEdgeOf(rule.onRoot().captureToImage().toPixelMap(), PODCAST_COLOR)

        rule.runOnUiThread { navigation.pushDetail(PodcastKey(2)) }

        var wiped = false
        var covered = false
        repeat(WIPE_FRAME_BOUND) {
            rule.mainClock.advanceTimeByFrame()
            rule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
            val pixels = rule.onRoot().captureToImage().toPixelMap()
            if (
                leftEdgeOf(pixels, PODCAST_COLOR) > detailRest &&
                leftEdgeOf(pixels, PODCAST2_COLOR) == detailRest
            ) {
                wiped = true
            }
            if (
                leftEdgeOf(pixels, PODCAST2_COLOR) > detailRest &&
                leftEdgeOf(pixels, PODCAST_COLOR) == detailRest
            ) {
                covered = true
            }
        }
        assertTrue(!wiped, "a forward same-class replace animated as a back wipe")
        assertTrue(covered, "the replacement never covered over the outgoing detail")
        settle()
        assertEquals(PodcastKey(2), navigation.stack(FeedsKey).last())
        assertTrue(PodcastKey(1) !in navigation.stack(FeedsKey))
    }

    /**
     * A push landing mid-cover retargets the swap forward: the interrupted incoming drops
     * to the resting base and the newest top covers over it — never a backwards wipe.
     */
    @Test
    fun expandedPushDuringCoverCoversTheNewestTop() {
        setFixedHost(installers = ProbedInstallers, directive = TwoPaneDirective)
        rule.runOnUiThread {
            navigation.push(SettingsHomeKey)
            navigation.push(PodcastKey(1))
        }
        settle()
        rule.mainClock.autoAdvance = false

        rule.runOnUiThread { navigation.push(PodcastKey(2)) }
        advanceFrames(3)
        val detailRest = leftEdgeOf(rule.onRoot().captureToImage().toPixelMap(), PODCAST_COLOR)

        rule.runOnUiThread { navigation.push(PodcastKey(3)) }

        var covered = false
        var wiped = false
        repeat(WIPE_FRAME_BOUND) {
            rule.mainClock.advanceTimeByFrame()
            rule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
            val pixels = rule.onRoot().captureToImage().toPixelMap()
            val incoming = leftEdgeOf(pixels, PODCAST3_COLOR)
            if (incoming > detailRest) covered = true
            if (incoming == detailRest && leftEdgeOf(pixels, PODCAST2_COLOR) > detailRest) {
                wiped = true
            }
        }
        assertTrue(covered, "the retargeted push landing ran no cover motion")
        assertTrue(!wiped, "the retargeted push landing played backwards")
        settle()
        assertEquals(PodcastKey(3), navigation.stack(FeedsKey).last())
    }

    /**
     * 08 promises a 0 ms snap under reduced motion and the pane swap already honours it;
     * the outer scene cover/wipe must use the same spec — a default spring would still run.
     * Unroled entries isolate the outer transform: the cover slides in from the end edge,
     * so a mid-transit frame shows the incoming's left edge still inside the window.
     */
    @Test
    fun reducedMotionSnapsTheOuterCover() {
        setFixedHost(installers = DistinctInstallers, reducedMotion = true)
        rule.runOnUiThread { navigation.push(SettingsKey(SettingsPage.ABOUT)) }
        rule.mainClock.autoAdvance = false
        val windowEnd =
            rule
                .onRoot()
                .captureToImage()
                .toPixelMap()
                .width - 1

        val midTransit =
            advanceFramesUntil(WIPE_FRAME_BOUND) {
                leftEdgeOf(rule.onRoot().captureToImage().toPixelMap(), ABOUT_COLOR)
                    .let { it in 1 until windowEnd }
            }
        assertTrue(!midTransit, "the outer cover kept animating under reduced motion")
        settle()
        assertTrue(rule.onAllNodesWithTag(ABOUT_TAG).fetchSemanticsNodes().isNotEmpty())
    }

    // endregion

    // region platform defaults (pre-fix diagnostics)

    /**
     * The shipped runtime's `defaultTransitionSpec` on the identical entry swap: the outgoing
     * entry blends in place for hundreds of ms and never moves horizontally — the "everything
     * seems to be fades" the owner reported. Failing loudly when the platform default changes
     * keeps this an honest before-picture, not a strawman.
     */
    @Test
    fun platformDefaultsFadeTabSwitch() {
        val stack = NavBackStack<NavKey>(FeedsKey)
        setDefaultsHost(stack)
        rule.mainClock.autoAdvance = false

        rule.runOnUiThread {
            stack.removeAt(stack.lastIndex)
            stack.add(LibraryKey)
        }
        // Past the instant-swap bound the outgoing entry is still composed and blending.
        advanceFrames(TEARDOWN_FRAME_BOUND)
        assertTrue(
            rule.onAllNodesWithTag(FEEDS_TAG).fetchSemanticsNodes().isNotEmpty(),
            "feeds entry left immediately; the default spec changed",
        )
        var blendSeen = false
        val movedRight =
            advanceFramesUntil(FADE_FRAME_BOUND) {
                val pixels = rule.onRoot().captureToImage().toPixelMap()
                if (hasBlendedPixels(pixels, FEEDS_COLOR, LIBRARY_COLOR)) blendSeen = true
                leftEdgeOf(pixels, FEEDS_COLOR) > 0
            }
        assertTrue(blendSeen, "the default navigate spec produced no blend")
        assertTrue(!movedRight, "the default navigate spec moved the outgoing entry horizontally")

        settle()
        assertTrue(rule.onAllNodesWithTag(FEEDS_TAG).fetchSemanticsNodes().isEmpty())
    }

    /** The same for `defaultPopTransitionSpec`: a fade on back, not a horizontal wipe. */
    @Test
    fun platformDefaultsFadeBackPop() {
        val stack = NavBackStack<NavKey>(FeedsKey, PodcastKey(3))
        setDefaultsHost(stack)
        rule.mainClock.autoAdvance = false

        rule.runOnUiThread { stack.removeAt(stack.lastIndex) }
        advanceFrames(TEARDOWN_FRAME_BOUND)
        assertTrue(
            rule.onAllNodesWithTag(PODCAST_TAG).fetchSemanticsNodes().isNotEmpty(),
            "detail entry left immediately; the default pop spec changed",
        )
        var blendSeen = false
        val movedRight =
            advanceFramesUntil(FADE_FRAME_BOUND) {
                val pixels = rule.onRoot().captureToImage().toPixelMap()
                if (hasBlendedPixels(pixels, PODCAST_COLOR, FEEDS_COLOR)) blendSeen = true
                leftEdgeOf(pixels, PODCAST_COLOR) > 0
            }
        assertTrue(blendSeen, "the default pop spec produced no blend")
        assertTrue(!movedRight, "the default pop spec moved the outgoing entry horizontally")

        settle()
        assertTrue(rule.onAllNodesWithTag(PODCAST_TAG).fetchSemanticsNodes().isEmpty())
    }

    // endregion

    // region hosts

    private var hostContent: (@Composable () -> Unit)? = null

    /** Returns the directive state — assigning a new directive resizes the live host. */
    private fun setFixedHost(
        installers: Set<EntryProviderInstaller> = HostInstallers,
        directive: PaneScaffoldDirective? = null,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        reducedMotion: Boolean = false,
    ): MutableState<PaneScaffoldDirective?> {
        val directiveState = mutableStateOf(directive)
        hostContent = {
            val state = rememberNavigationState()
            navigation = state
            dispatcher = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher
            CompositionLocalProvider(
                LocalAppNavigator provides state,
                LocalLayoutDirection provides layoutDirection,
                LocalReducedMotion provides reducedMotion,
            ) {
                NeutrodyneNavHost(
                    state = state,
                    installers = installers,
                    directive =
                        directiveState.value
                            ?: calculatePaneScaffoldDirective(
                                currentWindowAdaptiveInfo(supportLargeAndXLargeWidth = true),
                            ),
                )
            }
        }
        rule.setContent(hostContent!!)
        settle()
        return directiveState
    }

    /**
     * The pre-fix call site verbatim: `NavDisplay` with no transition specs, so the Android
     * runtime's `defaultTransitionSpec`/`defaultPopTransitionSpec` (fadeIn/fadeOut 700 ms) apply.
     */
    private fun setDefaultsHost(backStack: NavBackStack<NavKey>) {
        rule.setContent {
            val entries =
                rememberDecoratedNavEntries(
                    backStack = backStack,
                    entryDecorators =
                        listOf(
                            rememberSaveableStateHolderNavEntryDecorator(),
                            rememberViewModelStoreNavEntryDecorator(),
                        ),
                    entryProvider = { key -> DefaultsProvider(key) },
                )
            SharedTransitionLayout {
                NavDisplay(
                    entries = entries,
                    onBack = { backStack.removeAt(backStack.lastIndex) },
                    sceneStrategies =
                        listOf(
                            rememberListDetailSceneStrategy(
                                directive =
                                    calculatePaneScaffoldDirective(
                                        currentWindowAdaptiveInfo(
                                            supportLargeAndXLargeWidth = true,
                                        ),
                                    ),
                            ),
                        ),
                    sharedTransitionScope = this,
                )
            }
        }
        settle()
    }

    // endregion

    // region clock and pixels

    private fun advanceFrames(frames: Int) {
        // The semantics fetch after each frame forces pending recompositions to apply — plain
        // advanceTimeByFrame leaves a queued pass un-run until the next sync point.
        repeat(frames) {
            rule.mainClock.advanceTimeByFrame()
            rule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
        }
    }

    /** Pumps one frame at a time and reports whether [condition] held within [maxFrames]. */
    private fun advanceFramesUntil(
        maxFrames: Int,
        condition: () -> Boolean,
    ): Boolean {
        repeat(maxFrames) {
            rule.mainClock.advanceTimeByFrame()
            if (condition()) return true
        }
        return false
    }

    /** Advances one frame at a time; frames until [tag] has no node left, or [maxFrames]. */
    private fun framesUntilAbsent(
        tag: String,
        maxFrames: Int,
    ): Int {
        repeat(maxFrames) { frame ->
            rule.mainClock.advanceTimeByFrame()
            rule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
            if (rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()) return frame + 1
        }
        return maxFrames
    }

    /** Advances one frame at a time; frames until [tag] first has a node, or [maxFrames]. */
    private fun framesUntilPresent(
        tag: String,
        maxFrames: Int,
    ): Int {
        repeat(maxFrames) { frame ->
            rule.mainClock.advanceTimeByFrame()
            rule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
            if (rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) return frame + 1
        }
        return maxFrames
    }

    private fun settle() {
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
    }

    /** Leftmost column whose pixels are mostly [sentinel], or -1 when the sentinel is gone. */
    private fun leftEdgeOf(
        pixels: PixelMap,
        sentinel: Color,
    ): Int {
        for (x in 0 until pixels.width) {
            var hits = 0
            for (y in 0 until pixels.height step PIXEL_STRIDE) {
                if (pixels[x, y].isCloseTo(sentinel)) hits++
            }
            if (hits * PIXEL_STRIDE > pixels.height / 2) return x
        }
        return -1
    }

    /** Rightmost column whose pixels are mostly [sentinel], or -1 when the sentinel is gone. */
    private fun rightEdgeOf(
        pixels: PixelMap,
        sentinel: Color,
    ): Int {
        for (x in pixels.width - 1 downTo 0) {
            var hits = 0
            for (y in 0 until pixels.height step PIXEL_STRIDE) {
                if (pixels[x, y].isCloseTo(sentinel)) hits++
            }
            if (hits * PIXEL_STRIDE > pixels.height / 2) return x
        }
        return -1
    }

    /** Any pixel that is a blend of two sentinels — a fade signature, never a wipe's. */
    private fun hasBlendedPixels(
        pixels: PixelMap,
        a: Color,
        b: Color,
    ): Boolean {
        for (y in 0 until pixels.height step PIXEL_STRIDE) {
            for (x in 0 until pixels.width step PIXEL_STRIDE) {
                val p = pixels[x, y]
                if (!p.isCloseTo(a) && !p.isCloseTo(b) && p.alpha > 0.9f) return true
            }
        }
        return false
    }

    private fun Color.isCloseTo(other: Color): Boolean =
        alpha > 0.9f &&
            abs(red - other.red) < COLOR_TOLERANCE &&
            abs(green - other.green) < COLOR_TOLERANCE &&
            abs(blue - other.blue) < COLOR_TOLERANCE

    // endregion

    private companion object {
        /** 128 ms — long past an instant swap, nowhere near a 700 ms fade. */
        const val INSTANT_FRAME_BOUND = 8

        /** Entry teardown can trail the visual swap by a few frames on this runtime. */
        const val TEARDOWN_FRAME_BOUND = 12

        /** 256 ms — the wipe motion measured ~13 frames here. */
        const val WIPE_FRAME_BOUND = 16
        const val GESTURE_FRAME_BOUND = 16

        /** 640 ms — still inside the 700 ms default fade. */
        const val FADE_FRAME_BOUND = 40

        /** A retargeted departure is dead by the next pass — this window only watches ghosts. */
        const val GHOST_WATCH_FRAME_BOUND = 60

        /** 384 ms — a no-slot release takes a few frames; the stale-emitted stall was ~544 ms. */
        const val PROMPT_RELEASE_FRAME_BOUND = 24

        /** 768 ms — the collapse cover plus pop wipe together, still under the 800 ms expiry. */
        const val COLLAPSE_EXIT_FRAME_BOUND = 48

        const val PIXEL_STRIDE = 4
        const val COLOR_TOLERANCE = 0.15f
    }
}

private const val FEEDS_TAG = "feeds-pane"
private const val LIBRARY_TAG = "library-pane"
private const val PODCAST_TAG = "podcast-pane"
private const val PODCAST2_TAG = "podcast-pane-2"
private const val PODCAST3_TAG = "podcast-pane-3"
private const val SETTINGS_TAG = "settings-pane"
private const val ABOUT_TAG = "about-pane"
private const val LICENCES_TAG = "licences-pane"
private const val EXTRA_TAG = "extra-pane"
private const val ABOUT_PAGE_TAG = "page-ABOUT"
private const val APPEARANCE_PAGE_TAG = "page-APPEARANCE"
private const val PODCAST2_ID = 2L
private const val PODCAST3_ID = 3L

private val FEEDS_COLOR = Color(0xFFD32F2F)
private val LIBRARY_COLOR = Color(0xFF2E7D32)
private val PODCAST_COLOR = Color(0xFF1565C0)
private val PODCAST2_COLOR = Color(0xFF004D40)
private val PODCAST3_COLOR = Color(0xFFBF360C)
private val SETTINGS_COLOR = Color(0xFFF9A825)
private val ABOUT_COLOR = Color(0xFF00695C)
private val LICENCES_COLOR = Color(0xFF4E342E)

/** Forces two partitions regardless of the test window size — the expanded scaffold case. */
private val TwoPaneDirective = PaneScaffoldDirective.Default.copy(maxHorizontalPartitions = 2)

private val HostInstallers: Set<EntryProviderInstaller> =
    buildSet {
        add { entry<FeedsKey>(metadata = NdSceneMetadata.paneList()) { Sentinel(FEEDS_COLOR, FEEDS_TAG) } }
        add {
            entry<LibraryKey>(metadata = NdSceneMetadata.paneList()) {
                Sentinel(LIBRARY_COLOR, LIBRARY_TAG)
            }
        }
        add {
            entry<UpNextKey>(metadata = NdSceneMetadata.paneList()) {
                Sentinel(Color(0xFF6A1B9A), "up-next-pane")
            }
        }
        add {
            entry<DownloadsKey>(metadata = NdSceneMetadata.paneList()) {
                Sentinel(Color(0xFF00838F), "downloads-pane")
            }
        }
        add {
            entry<DiscoverKey>(metadata = NdSceneMetadata.paneList()) {
                Sentinel(Color(0xFFEF6C00), "discover-pane")
            }
        }
        add {
            entry<PodcastKey>(metadata = NdSceneMetadata.paneDetail()) {
                Sentinel(PODCAST_COLOR, PODCAST_TAG)
            }
        }
        add {
            entry<SettingsHomeKey>(metadata = NdSceneMetadata.paneList()) {
                Sentinel(SETTINGS_COLOR, SETTINGS_TAG)
            }
        }
        add {
            entry<SettingsKey>(metadata = NdSceneMetadata.paneDetail()) {
                Sentinel(ABOUT_COLOR, ABOUT_TAG)
            }
        }
        add {
            entry<LicencesKey>(metadata = NdSceneMetadata.paneDetail()) {
                Sentinel(LICENCES_COLOR, LICENCES_TAG)
            }
        }
    }

/** The expanded settings chain with a real `viewModel()`/`rememberSaveable` licences page. */
private val OwnerInstallers: Set<EntryProviderInstaller> =
    buildSet {
        add { entry<FeedsKey>(metadata = NdSceneMetadata.paneList()) { Sentinel(FEEDS_COLOR, FEEDS_TAG) } }
        add {
            entry<LibraryKey>(metadata = NdSceneMetadata.paneList()) {
                Sentinel(LIBRARY_COLOR, LIBRARY_TAG)
            }
        }
        add {
            entry<UpNextKey>(metadata = NdSceneMetadata.paneList()) {
                Sentinel(Color(0xFF6A1B9A), "up-next-pane")
            }
        }
        add {
            entry<DownloadsKey>(metadata = NdSceneMetadata.paneList()) {
                Sentinel(Color(0xFF00838F), "downloads-pane")
            }
        }
        add {
            entry<DiscoverKey>(metadata = NdSceneMetadata.paneList()) {
                Sentinel(Color(0xFFEF6C00), "discover-pane")
            }
        }
        add {
            entry<SettingsHomeKey>(metadata = NdSceneMetadata.paneList()) {
                Sentinel(SETTINGS_COLOR, SETTINGS_TAG)
            }
        }
        add {
            entry<SettingsKey>(metadata = NdSceneMetadata.paneDetail()) {
                Sentinel(ABOUT_COLOR, ABOUT_TAG)
            }
        }
        add {
            entry<LicencesKey>(metadata = NdSceneMetadata.paneDetail()) { OwnerProbePage() }
        }
    }

/**
 * The expanded chain with owner probes on every roled page — Settings pages report under
 * `page-<page>` tags, episodes under [EXTRA_TAG] — so interrupted swaps can assert which
 * emission stayed composed.
 */
private val ProbedInstallers: Set<EntryProviderInstaller> =
    buildSet {
        add { entry<FeedsKey>(metadata = NdSceneMetadata.paneList()) { Sentinel(FEEDS_COLOR, FEEDS_TAG) } }
        add {
            entry<LibraryKey>(metadata = NdSceneMetadata.paneList()) {
                Sentinel(LIBRARY_COLOR, LIBRARY_TAG)
            }
        }
        add {
            entry<UpNextKey>(metadata = NdSceneMetadata.paneList()) {
                Sentinel(Color(0xFF6A1B9A), "up-next-pane")
            }
        }
        add {
            entry<DownloadsKey>(metadata = NdSceneMetadata.paneList()) {
                Sentinel(Color(0xFF00838F), "downloads-pane")
            }
        }
        add {
            entry<DiscoverKey>(metadata = NdSceneMetadata.paneList()) {
                Sentinel(Color(0xFFEF6C00), "discover-pane")
            }
        }
        add {
            entry<PodcastKey>(metadata = NdSceneMetadata.paneDetail()) { key ->
                when (key.podcastId) {
                    PODCAST2_ID -> Sentinel(PODCAST2_COLOR, PODCAST2_TAG)
                    PODCAST3_ID -> Sentinel(PODCAST3_COLOR, PODCAST3_TAG)
                    else -> Sentinel(PODCAST_COLOR, PODCAST_TAG)
                }
            }
        }
        add {
            entry<SettingsHomeKey>(metadata = NdSceneMetadata.paneList()) {
                Sentinel(SETTINGS_COLOR, SETTINGS_TAG)
            }
        }
        add {
            entry<SettingsKey>(metadata = NdSceneMetadata.paneDetail()) { key ->
                OwnerProbePage("page-${key.page}")
            }
        }
        add {
            entry<LicencesKey>(metadata = NdSceneMetadata.paneDetail()) {
                OwnerProbePage(LICENCES_TAG)
            }
        }
        add {
            entry<EpisodeKey>(metadata = NdSceneMetadata.paneExtra()) {
                OwnerProbePage(EXTRA_TAG)
            }
        }
    }

/** The same settings keys with no pane roles — each entry lands in its own scene. */
private val DistinctInstallers: Set<EntryProviderInstaller> =
    buildSet {
        add { entry<FeedsKey> { Sentinel(FEEDS_COLOR, FEEDS_TAG) } }
        add { entry<LibraryKey> { Sentinel(LIBRARY_COLOR, LIBRARY_TAG) } }
        add { entry<UpNextKey> { Sentinel(Color(0xFF6A1B9A), "up-next-pane") } }
        add { entry<DownloadsKey> { Sentinel(Color(0xFF00838F), "downloads-pane") } }
        add { entry<DiscoverKey> { Sentinel(Color(0xFFEF6C00), "discover-pane") } }
        add { entry<SettingsHomeKey> { Sentinel(SETTINGS_COLOR, SETTINGS_TAG) } }
        add { entry<SettingsKey> { Sentinel(ABOUT_COLOR, ABOUT_TAG) } }
        add { entry<LicencesKey> { Sentinel(LICENCES_COLOR, LICENCES_TAG) } }
    }

private val DefaultsProvider =
    entryProvider<NavKey> {
        entry<FeedsKey> { Sentinel(FEEDS_COLOR, FEEDS_TAG) }
        entry<LibraryKey> { Sentinel(LIBRARY_COLOR, LIBRARY_TAG) }
        entry<PodcastKey> { Sentinel(PODCAST_COLOR, PODCAST_TAG) }
    }

/** Records the live owner triple per page tag so frames can assert continuity. */
private object OwnerProbe {
    val nextIdentity = AtomicInteger(1)
    val cleared = mutableListOf<Int>()
    val snapshots = mutableMapOf<String, Snapshot>()

    data class Snapshot(
        val vm: Int,
        val owner: Int,
        val saved: Int,
    )

    fun reset() {
        cleared.clear()
        snapshots.clear()
    }
}

private class OwnerProbeVm : ViewModel() {
    val identity = OwnerProbe.nextIdentity.getAndIncrement()

    override fun onCleared() {
        OwnerProbe.cleared += identity
    }
}

@Suppress("FunctionNaming") // composables are PascalCase
@Composable
private fun OwnerProbePage(tag: String = LICENCES_TAG) {
    val vm = viewModel { OwnerProbeVm() }
    val owner = LocalViewModelStoreOwner.current
    val saved = rememberSaveable { mutableIntStateOf(0) }
    SideEffect {
        OwnerProbe.snapshots[tag] =
            OwnerProbe.Snapshot(vm.identity, System.identityHashCode(owner), saved.intValue)
    }
    Column(Modifier.fillMaxSize().testTag(tag)) {
        Text("$tag|saved=${saved.intValue}")
        Button(onClick = { saved.intValue++ }, modifier = Modifier.testTag("$tag-inc")) {
            Text("increment")
        }
    }
}

@Suppress("FunctionNaming") // composables are PascalCase
@Composable
private fun Sentinel(
    color: Color,
    tag: String,
) {
    Box(Modifier.fillMaxSize().background(color).testTag(tag))
}
