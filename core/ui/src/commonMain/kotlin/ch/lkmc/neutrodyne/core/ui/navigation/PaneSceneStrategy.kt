// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.navigation

import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldDestinationItem
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldRole
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldValue
import androidx.compose.material3.adaptive.layout.calculateThreePaneScaffoldValue
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneMotion
import ch.lkmc.neutrodyne.core.navigation.NdSceneMetadata
import ch.lkmc.neutrodyne.core.navigation.TopLevelKey

private const val PANE_ENTRY_SOURCE = "ndPaneEntrySource"
internal const val PANE_NAV_KEY = "ndPaneNavKey"

/** Scene retention owns the animation; the real stacks remain the only navigation membership. */
internal class PaneSceneStrategy(
    private val navigation: NavigationState,
) : SceneStrategy<NavKey> {
    private lateinit var adaptive: ListDetailSceneStrategy<NavKey>
    private val rendering = mutableMapOf<TopLevelKey, PaneRendering>()
    private var activeKey by mutableStateOf<PaneSceneKey?>(null)
    private var selectedTab: TopLevelKey? = null
    private var paneAnimations: Map<String, Any> = emptyMap()

    /** Only actual stack emissions advance anchors; predictive prefixes merely query them. */
    internal fun updateCurrent(
        adaptive: ListDetailSceneStrategy<NavKey>,
        entriesByTab: Map<TopLevelKey, List<NavEntry<NavKey>>>,
        paneAnimations: Map<String, Any>,
    ) {
        this.adaptive = adaptive
        this.paneAnimations = paneAnimations
        val switchingTabs = selectedTab != null && selectedTab != navigation.selectedTab
        selectedTab = navigation.selectedTab

        for ((tab, entries) in entriesByTab) {
            // An overlay does not change the adaptive scene underneath its window.
            val underlying = entries.dropLastWhile { it.metadata.containsKey(NdSceneMetadata.KEY_OVERLAY) }
            val scope = SceneStrategyScope<NavKey>()
            val scene = with(adaptive) { scope.calculateScene(underlying) } ?: continue
            val snapshot = snapshot(scene.entries)
            val previous = rendering[tab]
            val current =
                when {
                    previous == null -> PaneRendering.initial(tab, snapshot)
                    previous.snapshot == snapshot -> previous
                    previous.snapshot.value != snapshot.value -> previous.changeAllocation(snapshot)
                    else -> previous.changeContent(tab, snapshot)
                }
            val entering =
                when {
                    switchingTabs || previous == null -> {
                        emptySet()
                    }

                    previous.snapshot == snapshot -> {
                        previous.enteringRoles
                    }

                    navigation.lastChangeWasPop || previous.snapshot.value != snapshot.value -> {
                        emptySet()
                    }

                    previous.snapshot.tops[PaneRole.List] != snapshot.tops[PaneRole.List] -> {
                        emptySet()
                    }

                    else -> {
                        PaneRole.entries.filterTo(mutableSetOf()) {
                            previous.snapshot.tops[it] != snapshot.tops[it]
                        }
                    }
                }
            rendering[tab] = current.copy(enteringRoles = entering)
        }

        val selected =
            entriesByTab
                .getValue(navigation.selectedTab)
                .dropLastWhile { it.metadata.containsKey(NdSceneMetadata.KEY_OVERLAY) }
        val selectedScene = with(adaptive) { SceneStrategyScope<NavKey>().calculateScene(selected) }
        activeKey = if (selectedScene == null) null else rendering[navigation.selectedTab]?.key
    }

    override fun SceneStrategyScope<NavKey>.calculateScene(entries: List<NavEntry<NavKey>>): Scene<NavKey>? {
        val prepared = entries.map(::prepareEntry)
        val delegate = with(adaptive) { calculateScene(prepared) } ?: return null
        val tab = delegate.entries.last().metadata[NdSceneMetadata.KEY_TAB] as TopLevelKey
        val snapshot = snapshot(delegate.entries)
        val render = rendering.getValue(tab)
        val key = render.keyFor(tab, snapshot)

        // Match the internal boundary target. Without one, Back removes one real entry, not
        // the entire selected tab's scaffold (issue #75).
        val internalBack = delegate.previousEntries.lastOrNull() in delegate.entries
        val previous = if (internalBack) delegate.previousEntries else prepared.dropLast(1)
        val claims =
            PaneRole.entries.mapNotNull { role ->
                delegate.entries.lastOrNull { paneRole(it) == role }
            }
        val entering = if (key == activeKey && snapshot == render.snapshot) render.enteringRoles else emptySet()

        return PaneScene(delegate, key, snapshot, claims, previous, entering) { activeKey }
    }

    private fun prepareEntry(entry: NavEntry<NavKey>): NavEntry<NavKey> {
        val original = (entry.metadata[PANE_ENTRY_SOURCE] as? PaneEntrySource)?.entry ?: entry
        val role = paneRole(original) ?: return original
        return NavEntry(
            key = original.metadata.getValue(PANE_NAV_KEY) as NavKey,
            contentKey = original.contentKey,
            metadata = original.metadata + paneAnimations + (PANE_ENTRY_SOURCE to PaneEntrySource(original)),
            content = { PaneEntryContent(original, role) },
        )
    }

    private fun snapshot(entries: List<NavEntry<NavKey>>): PaneSnapshot {
        val tops =
            PaneRole.entries.associateWith { role ->
                entries.lastOrNull { paneRole(it) == role }?.contentKey
            }
        val history =
            entries.mapNotNull { entry ->
                paneRole(entry)?.let { ThreePaneScaffoldDestinationItem(it.scaffoldRole, entry.contentKey) }
            }
        return PaneSnapshot(
            tops,
            calculateThreePaneScaffoldValue(
                maxHorizontalPartitions = adaptive.directive.maxHorizontalPartitions,
                maxVerticalPartitions = adaptive.directive.maxVerticalPartitions,
                adaptStrategies = adaptive.adaptStrategies,
                destinationHistory = history,
            ),
        )
    }
}

private enum class PaneRole(
    val marker: String,
    val scaffoldRole: ThreePaneScaffoldRole,
) {
    List(NdSceneMetadata.PANE_LIST, ThreePaneScaffoldRole.Secondary),
    Detail(NdSceneMetadata.PANE_DETAIL, ThreePaneScaffoldRole.Primary),
    Extra(NdSceneMetadata.PANE_EXTRA, ThreePaneScaffoldRole.Tertiary),
}

private fun paneRole(entry: NavEntry<NavKey>): PaneRole? =
    PaneRole.entries.firstOrNull { it.marker == entry.metadata[NdSceneMetadata.KEY_PANE] }

private data class PaneEntrySource(
    val entry: NavEntry<NavKey>,
)

private data class PaneSnapshot(
    val tops: Map<PaneRole, Any?>,
    val value: ThreePaneScaffoldValue,
)

private data class PaneSceneKey(
    val tab: TopLevelKey,
    val epoch: Int,
    val tops: Map<PaneRole, Any?>,
)

/** A render anchor preserves Material's remembered scaffold state across visibility boundaries. */
private data class PaneRendering(
    val snapshot: PaneSnapshot,
    val epoch: Int,
    val anchorTops: Map<PaneRole, Any?>,
    val anchorKey: PaneSceneKey,
    val key: PaneSceneKey,
    val enteringRoles: Set<PaneRole> = emptySet(),
) {
    fun keyFor(
        tab: TopLevelKey,
        target: PaneSnapshot,
    ): PaneSceneKey {
        if (target.value != snapshot.value) return key
        if (target.tops == anchorTops) return anchorKey
        return PaneSceneKey(tab, epoch, target.tops)
    }

    fun changeAllocation(target: PaneSnapshot): PaneRendering = PaneRendering(target, epoch + 1, target.tops, key, key)

    fun changeContent(
        tab: TopLevelKey,
        target: PaneSnapshot,
    ): PaneRendering = copy(snapshot = target, key = keyFor(tab, target))

    companion object {
        fun initial(
            tab: TopLevelKey,
            snapshot: PaneSnapshot,
        ): PaneRendering {
            val key = PaneSceneKey(tab, 0, snapshot.tops)
            return PaneRendering(snapshot, 0, snapshot.tops, key, key)
        }
    }
}

private class PaneScene(
    private val delegate: Scene<NavKey>,
    override val key: PaneSceneKey,
    val snapshot: PaneSnapshot,
    override val entries: List<NavEntry<NavKey>>,
    override val previousEntries: List<NavEntry<NavKey>>,
    private val enteringRoles: Set<PaneRole>,
    private val activeKey: () -> PaneSceneKey?,
) : Scene<NavKey> {
    override val metadata: Map<String, Any> get() = delegate.metadata

    override val content: @Composable () -> Unit = {
        val parent = LocalNavigationEventDispatcherOwner.current
        val owner =
            remember(parent) {
                parent?.let { PaneDispatcherOwner(NavigationEventDispatcher(it.navigationEventDispatcher)) }
            }
        DisposableEffect(owner) { onDispose { owner?.navigationEventDispatcher?.dispose() } }
        val active = key == activeKey()
        SideEffect { owner?.navigationEventDispatcher?.isEnabled = active }
        val exiting = !active && LocalNavAnimatedContentScope.current.transition.targetState == EnterExitState.PostExit

        // Exit scenes still draw, but their private scaffold handlers cannot pop live history.
        Box(Modifier.fillMaxSize().then(if (exiting) Modifier.clearAndSetSemantics {} else Modifier)) {
            // A committed exit is no longer an accessible screen; a cancellable source remains active.
            CompositionLocalProvider(LocalPaneSceneMotion provides PaneSceneMotion(key, enteringRoles)) {
                if (owner == null) {
                    delegate.content()
                } else {
                    CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) {
                        delegate.content()
                    }
                }
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        other is PaneScene && key == other.key && delegate == other.delegate &&
            entries == other.entries && previousEntries == other.previousEntries && enteringRoles == other.enteringRoles

    override fun hashCode(): Int =
        key.hashCode() * 31 + delegate.hashCode() * 31 + entries.hashCode() * 31 +
            previousEntries.hashCode() * 31 + enteringRoles.hashCode()
}

private class PaneDispatcherOwner(
    override val navigationEventDispatcher: NavigationEventDispatcher,
) : NavigationEventDispatcherOwner

private data class PaneSceneMotion(
    val key: PaneSceneKey,
    val entering: Set<PaneRole>,
)

private val LocalPaneSceneMotion = compositionLocalOf<PaneSceneMotion?> { null }

/** Material's focus ancestors change when a shared movable entry changes scenes. */
@Composable
internal fun rememberPaneFocusDecorator(): NavEntryDecorator<NavKey> =
    remember {
        NavEntryDecorator { entry -> PaneFocusContent(entry) }
    }

@Composable
private fun PaneFocusContent(entry: NavEntry<NavKey>) {
    val requester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val restore = focused
    val scene = LocalPaneSceneMotion.current?.key

    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(requester)
            .onFocusChanged {
                focused = it.hasFocus
                if (it.hasFocus) requester.saveFocusedChild()
            }.focusGroup(),
    ) { entry.Content() }

    LaunchedEffect(scene) {
        if (restore) {
            // The moved child must attach to its new Material focus ancestors first.
            withFrameNanos { }
            if (!requester.restoreFocusedChild()) requester.requestFocus()
        }
    }
}

@Composable
private fun PaneEntryContent(
    entry: NavEntry<NavKey>,
    role: PaneRole,
) {
    val entering = role in LocalPaneSceneMotion.current?.entering.orEmpty()
    if (!entering) {
        entry.Content()
        return
    }

    val animatedScope = LocalNavAnimatedContentScope.current
    val spec = NeutrodyneMotion.navigation<Float>()
    val offset by animatedScope.transition.animateFloat(transitionSpec = { spec }, label = "pane cover") {
        if (it == EnterExitState.PreEnter) 1f else 0f
    }
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1

    Box(Modifier.fillMaxSize().clipToBounds()) {
        Box(Modifier.fillMaxSize().graphicsLayer { translationX = size.width * offset * direction }) {
            // This is the fully SceneSetup-decorated entry, including its movable state scope.
            entry.Content()
        }
    }
}

internal fun paneSceneTransition(
    initial: Scene<NavKey>,
    target: Scene<NavKey>,
    ordinary: NdSceneTransition,
): NdSceneTransition {
    if (initial !is PaneScene || target !is PaneScene || initial.key.tab != target.key.tab) return ordinary
    if (initial.snapshot.value != target.snapshot.value) return NdSceneTransition.Instant
    if (ordinary == NdSceneTransition.SlideInCover &&
        initial.snapshot.tops[PaneRole.List] == target.snapshot.tops[PaneRole.List]
    ) {
        return NdSceneTransition.PaneCover
    }
    return ordinary
}

internal fun snappedPaneAnimations(): Map<String, Any> =
    ListDetailSceneStrategy.paneAnimation(
        enterTransition = EnterTransition.None,
        exitTransition = ExitTransition.None,
        boundsAnimationSpec = snap(),
    )
