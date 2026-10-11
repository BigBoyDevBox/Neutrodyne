// SPDX-License-Identifier: Unlicense

// The nlopez compositionlocal-allowlist check mis-evaluates declarations in the navigation
// package — names added to compose_allowed_composition_locals pass and fail
// nondeterministically while the same mechanism works elsewhere — so the file is suppressed
// like NeutrodyneNavHost.kt.
@file:Suppress("ktlint:compose:compositionlocal-allowlist")

package ch.lkmc.neutrodyne.core.ui.navigation

import androidx.compose.animation.core.animate
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.adaptive.layout.AnimatedPaneOverride
import androidx.compose.material3.adaptive.layout.AnimatedPaneOverrideScope
import androidx.compose.material3.adaptive.layout.PaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneScaffoldValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneMotion
import ch.lkmc.neutrodyne.core.navigation.NdSceneMetadata
import ch.lkmc.neutrodyne.core.navigation.TopLevelKey
import kotlinx.coroutines.delay
import kotlinx.serialization.KSerializer
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.builtins.SetSerializer

/** Metadata stamp carrying the entry's pane role ([NdSceneMetadata.PANE_LIST] and friends). */
internal const val ND_PANE_SLOT = "ndPaneSlot"

/** Metadata stamp carrying the entry's [NavKey], so a dropped stack key maps to a contentKey. */
internal const val ND_NAV_KEY = "ndNavKey"

/** Upper bound a pinned pane entry stays on the decorated list without a slot claiming it. */
private const val PINNED_ENTRY_TIMEOUT_MS = 800L

/**
 * Which pane-top entries stay at their pane's top while a swap animates, so the outgoing pane
 * content keeps drawing — and its decorated subtree stays composed — through the animation.
 * Filled by [beginPass] diffs and drained by [PaneSlot.settle] or [expireAll] on the host's
 * timeout.
 *
 * A pane swap cannot re-emit the outgoing entry at a second position: the pane's own
 * visibility machinery can keep the callsite emission composed through the swap without
 * re-invoking it, so a parallel emission would register a second `SaveableStateProvider` for
 * the same key and crash. Pinning avoids re-emission entirely — the outgoing entry keeps its
 * one callsite emission, and the incoming entry emits only inside the transition Box.
 *
 * A wipe's outgoing key is also kept in the real back stack as [departing] until the swap
 * commits: the decorated list the scene builds is then always the true stack contents, so
 * scene back bookkeeping never counts a phantom entry and the deferred pop still commits when
 * the swap settles, expires, or is completed by later navigation. [departing] is persisted by
 * the host so a departure interrupted by process or configuration recreation still finishes —
 * the restored composition re-pins it, replays the wipe, and the ordinary pop path clears its
 * ViewModel and saveable state exactly once.
 */
internal class PaneRetention(
    /** Wipe-outgoing keys held in their real stack until the swap commits; host-persisted. */
    private val departing: MutableState<Set<NavKey>>,
) {
    /**
     * [navKey] is presented directly above [coveredNavKey] while a pane swap animates, keeping
     * it as the pane's top entry so the scene keeps emitting it at its callsite. For a wipe
     * [navKey] is a departing stack member; for a cover it is still live and [augmentedStack]
     * lifts it above its covered key. The covered entry is resolved lazily by [coveredEntry]:
     * for a push it is decorated only in this pass, after the pin is created.
     */
    class Pin(
        val navKey: NavKey,
        val coveredNavKey: NavKey,
        val tab: TopLevelKey,
        val direction: NdSceneTransition,
    )

    /** Outgoing NavKey -> pin; snapshot state so releases recompose. */
    val pins = mutableStateMapOf<NavKey, Pin>()

    /** contentKeys emitted through a pane slot; pruned to mapped keys each host pass. */
    val emitted = mutableSetOf<Any>()

    /** NavKey -> contentKey from the latest decorated pass; maps stack keys to entries. */
    private var contentKeyOf: Map<NavKey, Any> = emptyMap()

    /** NavKey -> pane role from the latest decorated pass; drives the per-pane top diff. */
    private val roleOf = mutableMapOf<NavKey, String>()

    /** NavKey -> pane role, queried lazily — a pushed key has no decorated entry yet. */
    var roleResolver: (NavKey) -> String? = { null }

    /** contentKey -> latest decorated entry, so a covered incoming entry emits its content. */
    val entryFor = mutableMapOf<Any, NavEntry<NavKey>>()

    /** Last pass' stacks; lets the host diff per-pane tops across pushes and pops. */
    private var previousStacks: Map<TopLevelKey, List<NavKey>> = emptyMap()

    /** The live stacks, so a settle or expiry can commit a deferred pop. */
    private var liveStacks: Map<TopLevelKey, NavBackStack<NavKey>> = emptyMap()

    /**
     * Pane roles a departure committed out of this pass. A retargeted swap must snap, not pin
     * — the still-live outgoing's callsite emission only just left, and a cover that re-emitted
     * the incoming key would race its exit-retained provider registration.
     */
    private val snappedRoles = mutableMapOf<TopLevelKey, MutableSet<String>>()

    /**
     * Reconciles departures newer navigation resolved, then diffs [stacks]' per-pane top
     * entries against the previous pass: when a pane's top key changes, the pane is swapping
     * content, and the previous top becomes the swap's outgoing — restored to the stack as
     * departing for a pop (wipe) or lifted above its live successor for a push (cover). Runs
     * before decoration, so this pass' [augmentedStack] lists already see the new pins.
     */
    fun beginPass(stacks: Map<TopLevelKey, NavBackStack<NavKey>>) {
        emitted.retainAll(contentKeyOf.values)
        snappedRoles.clear()
        reconcileDepartures(stacks)
        for ((tab, liveStack) in stacks) {
            val previousStack = previousStacks[tab]
            if (previousStack != null) diffPaneTops(tab, previousStack, liveStack)
            // A pin whose covered key left the stack is dead: for a wipe the outgoing is
            // committed now; a cover's outgoing still lives in the stack so it just releases.
            pins.entries.removeIf { entry ->
                val pin = entry.value
                if (pin.tab != tab || pin.coveredNavKey in liveStack) return@removeIf false
                if (pin.direction == NdSceneTransition.SlideOutWipe) {
                    val role = roleOf[pin.navKey] ?: roleResolver(pin.navKey)
                    if (role != null) snappedRoles.getOrPut(tab) { mutableSetOf() } += role
                    commitDeparture(tab, pin.navKey)
                }
                true
            }
        }
        liveStacks = stacks
        // Roles only matter while a top diff can still reach the key; stale entries would
        // otherwise accumulate for every key ever decorated.
        val reachable =
            buildSet {
                stacks.values.forEach(::addAll)
                previousStacks.values.forEach(::addAll)
                pins.values.forEach { pin ->
                    add(pin.navKey)
                    add(pin.coveredNavKey)
                }
            }
        roleOf.keys.retainAll(reachable)
        // Snapshot the contents: the stacks are live mutable lists and a stored reference would
        // already show the change, leaving nothing to diff.
        previousStacks = stacks.mapValues { it.value.toList() }
    }

    /**
     * Completes departures later navigation already resolved — a repush of the outgoing key,
     * another entry committed above it, or a real pop of the departing ghost — and re-pins a
     * departure whose pin did not survive (saved-state restore), so its wipe replays and
     * commits through the ordinary settle path.
     */
    private fun reconcileDepartures(stacks: Map<TopLevelKey, NavBackStack<NavKey>>) {
        val dep = departing.value
        if (dep.isEmpty()) return
        dep.forEach { key ->
            val holder = stacks.entries.firstOrNull { key in it.value }
            when {
                // Really removed while departing — a real pop already committed it.
                holder == null -> {
                    dropDeparture(key)
                }

                // Another key — a repush of this key or a different push — committed above
                // it, so the deferred pop applies now.
                holder.value.indexOf(key) != holder.value.lastIndex -> {
                    val role = roleOf[key] ?: roleResolver(key)
                    holder.value.removeAt(holder.value.indexOf(key))
                    if (role != null) snappedRoles.getOrPut(holder.key) { mutableSetOf() } += role
                    dropDeparture(key, holder.key)
                }

                // Departure marker restored without its pin (saved-state restore): re-pin
                // toward the same-role predecessor so the wipe replays and settles.
                !pins.containsKey(key) -> {
                    repinDeparture(holder.key, holder.value, key)
                }
            }
        }
    }

    /** Re-pins a restored departure so its wipe completes through the ordinary settle path. */
    private fun repinDeparture(
        tab: TopLevelKey,
        live: NavBackStack<NavKey>,
        key: NavKey,
    ) {
        val idx = live.indexOf(key)
        val role = roleOf[key] ?: roleResolver(key)
        val covered = live.subList(0, idx).lastOrNull { (roleOf[it] ?: roleResolver(it)) == role }
        if (role == null || covered == null) {
            // Nothing to wipe toward — the departure simply stays as a live entry again.
            dropDeparture(key, tab)
            return
        }
        pins[key] = Pin(key, covered, tab, NdSceneTransition.SlideOutWipe)
    }

    /**
     * Drops [key]'s departure marker and pin and patches the recorded stack, so the top diff
     * does not see the already-handled removal as a fresh pop. The caller mutates the live
     * stack first when the ghost is still a member.
     */
    private fun dropDeparture(
        key: NavKey,
        tab: TopLevelKey? = null,
    ) {
        departing.value -= key
        pins.remove(key)
        if (tab != null) {
            previousStacks[tab]?.let { previousStacks = previousStacks + (tab to (it - key)) }
        } else {
            previousStacks.keys.forEach { t ->
                previousStacks[t]?.let { previousStacks = previousStacks + (t to (it - key)) }
            }
        }
    }

    /**
     * Handles one scaffold back call on [tab]. The scaffold repeats its back call once per
     * entry its boundary math counted as a removal, and a departing top is one of those
     * entries — draining it IS this call's removal, so it returns false to suppress the real
     * pop; any other top means the call removes a live entry (returns true).
     */
    fun onBackDrain(tab: TopLevelKey): Boolean {
        val live = liveStacks[tab] ?: return true
        val top = live.lastOrNull() ?: return true
        if (top !in departing.value) return true
        dropDeparture(live.removeAt(live.lastIndex), tab)
        return false
    }

    /** Commits a departing key's deferred pop: drop it from the live stack and release. */
    private fun commitDeparture(
        tab: TopLevelKey,
        key: NavKey,
    ) {
        liveStacks[tab]?.remove(key)
        dropDeparture(key, tab)
    }

    private fun diffPaneTops(
        tab: TopLevelKey,
        previousStack: List<NavKey>,
        liveStack: NavBackStack<NavKey>,
    ) {
        val live = liveStack.toSet()
        val roleAt = { key: NavKey -> roleOf[key] ?: roleResolver(key) }
        val roles = (previousStack + liveStack).mapNotNullTo(mutableSetOf()) { roleAt(it) }
        for (role in roles) {
            if (role in snappedRoles[tab].orEmpty()) continue
            val previousTop = previousStack.lastOrNull { roleAt(it) == role } ?: continue
            val currentTop = liveStack.lastOrNull { roleAt(it) == role } ?: continue
            if (previousTop == currentTop) continue
            val previousKey = contentKeyOf[previousTop]
            if (previousKey == null || previousKey !in emitted || previousTop in pins) continue
            when {
                // Back pop: keep the outgoing top in the real stack as departing — its
                // callsite keeps emitting it and the deferred pop commits on settle.
                previousTop !in live -> {
                    liveStack.add(previousTop)
                    departing.value += previousTop
                    pins[previousTop] =
                        Pin(previousTop, currentTop, tab, NdSceneTransition.SlideOutWipe)
                }

                // Forward push: the new top landed above a still-live previous top — pin the
                // previous top back on so its callsite emission survives the cover.
                liveStack.indexOf(currentTop) > liveStack.indexOf(previousTop) -> {
                    pins[previousTop] =
                        Pin(previousTop, currentTop, tab, NdSceneTransition.SlideInCover)
                }
            }
        }
    }

    /**
     * [stack] with every cover-pinned key lifted directly above its covered key, so a pinned
     * pane keeps its outgoing entry as the pane top — the scene's `lastList`/`lastDetail`
     * picks it and the callsite emission — and with it the saveable/ViewModel bookkeeping
     * inside — stays continuously composed through the swap. Wipe-pinned keys need no lift:
     * they are real stack members still at the top. Every live stack key appears exactly once,
     * so scene back bookkeeping always sees the true stack contents.
     */
    fun augmentedStack(
        tab: TopLevelKey,
        stack: List<NavKey>,
    ): List<NavKey> {
        val lifts =
            pins.values.filter {
                it.tab == tab && it.direction == NdSceneTransition.SlideInCover
            }
        if (lifts.isEmpty()) return stack
        val out = stack.toMutableList()
        for (pin in lifts) {
            val from = out.indexOf(pin.navKey)
            val to = out.indexOf(pin.coveredNavKey)
            if (from >= 0 && to >= 0 && from < to) {
                out.removeAt(from)
                out.add(to, pin.navKey)
            }
        }
        return out
    }

    /**
     * Rebuilds the lookup maps for the freshly decorated [entries]: [entryFor] keeps the newest
     * entry per contentKey, [contentKeyOf] and [roleOf] map each NavKey for the next top diff.
     */
    fun indexEntries(entries: List<NavEntry<NavKey>>) {
        entries.forEach { entry ->
            val role = entry.metadata[ND_PANE_SLOT] as? String ?: return@forEach
            entryFor[entry.contentKey] = entry
            (entry.metadata[ND_NAV_KEY] as? NavKey)?.let { roleOf[it] = role }
        }
        entryFor.keys.retainAll(entries.mapTo(mutableSetOf()) { it.contentKey } + emitted)
        contentKeyOf =
            entries
                .mapNotNull { entry ->
                    (entry.metadata[ND_NAV_KEY] as? NavKey)?.let { it to entry.contentKey }
                }.toMap()
    }

    /** The decorated entry covered by [pin] — its sole emission lives in the transition Box. */
    fun coveredEntry(pin: Pin): NavEntry<NavKey>? = entryFor[contentKeyOf[pin.coveredNavKey]]

    /** Drops pins [stamp] recorded before the timeout; releases them all at once. */
    fun expireAll(stamp: Set<NavKey>) {
        stamp.forEach(::unpin)
    }

    /** Drops [key]'s pin; a wipe pin also commits its deferred pop from the real stack. */
    fun unpin(key: NavKey) {
        val pin = pins.remove(key) ?: return
        if (pin.direction == NdSceneTransition.SlideOutWipe) {
            liveStacks[pin.tab]?.remove(pin.navKey)
            departing.value -= pin.navKey
            previousStacks[pin.tab]?.let {
                previousStacks = previousStacks + (pin.tab to (it - pin.navKey))
            }
        }
    }
}

/**
 * The intra-scene pane-content transition. A `ThreePaneScaffoldScene` swaps which entry a pane
 * renders without changing the scene, so `NavDisplay`'s transform never runs for e.g. Settings
 * About -> Licences -> back in a two-pane layout. `NavEntry.Content()` invokes a fresh lambda
 * per entry, so no position inside entry content survives the swap — the only persistent
 * position is the pane call itself, which the library exposes through
 * `LocalAnimatedPaneOverride`.
 *
 * This override delegates to the upstream pane (the platform default, so pane chrome and
 * visibility animations are unchanged) and wraps it in a [PaneSlot]: [PaneRetention] keeps the
 * outgoing entry pinned as the pane's top for the swap's duration, so the pane callsite keeps
 * emitting its decorated content — saveable state, ViewModel store and lifecycle ownership
 * inside never leave composition — while [PaneTransportContent] draws the swap around it:
 * outgoing content translated toward the layout end on a pop, or kept resting underneath while
 * the incoming pane covers from the end on a push. No entry is ever emitted at two positions.
 *
 * Predictive back does not reach this layer: under `PopUntilScaffoldValueChange` the gesture
 * lands on a scaffold-value boundary, never on a same-pane entry, so the scaffold's own pane
 * transition owns those gestures and a content seek would preview the wrong landing.
 */
@Composable
internal fun rememberPaneSwapOverride(
    upstream: AnimatedPaneOverride,
    retention: PaneRetention,
): AnimatedPaneOverride = remember(upstream, retention) { NdPaneSwapOverride(upstream, retention) }

private class NdPaneSwapOverride(
    private val upstream: AnimatedPaneOverride,
    private val retention: PaneRetention,
) : AnimatedPaneOverride {
    @Composable
    override fun <
        Role : PaneScaffoldRole,
        ScaffoldValue : PaneScaffoldValue<Role>,
    > AnimatedPaneOverrideScope<Role, ScaffoldValue>.AnimatedPane() {
        PaneSlotBody(upstream, retention)
    }
}

/**
 * Emits a roled entry's decorated content. While its key is pinned — it is the pane's
 * transitioning top — the same call also emits the incoming entry's decorated content and
 * translates the two through the wipe or cover.
 *
 * This is the only position that ever emits each entry: the pinned outgoing stays at its
 * callsite so its subtree — including Navigation3's saveable-state and ViewModel bookkeeping —
 * never leaves composition, and the incoming entry emits solely inside the transition Box
 * (the scene only renders the pane's pinned top, not the covered key). When the slot settles
 * the pin releases; the next pass renders the new top at the callsite in one ordinary
 * recomposition, and the outgoing's bookkeeping disposes it exactly once.
 */
@Composable
private fun PaneTransportContent(decorated: NavEntry<NavKey>) {
    val slot = LocalNdPaneSlot.current
    slot?.retention?.emitted?.add(decorated.contentKey)
    val navKey = decorated.metadata[ND_NAV_KEY] as? NavKey
    val pin = navKey?.let { slot?.retention?.pins?.get(it) }
    val incoming = pin?.let { slot?.retention?.coveredEntry(it) }
    if (slot != null && pin != null && incoming != null) {
        slot.arm(pin.navKey, pin.direction)
    }
    val wiping = incoming != null && pin?.direction == NdSceneTransition.SlideOutWipe
    val covering = incoming != null && pin?.direction == NdSceneTransition.SlideInCover
    // Three fixed positions: `decorated.Content()` keeps the same group path whether or not a
    // swap runs, so arming or settling a pin never restarts its subtree — moving it under the
    // swap Box would recreate the decorated ViewModelStoreOwner wrapper the test asserts on.
    Box(Modifier.fillMaxSize().clipToBounds()) {
        val towardEnd = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
        // Beneath: the incoming pane revealed as the outgoing wipes toward the layout end.
        if (wiping) incoming.Content()
        // `size` inside the graphicsLayer block is the pane's draw-time size, so the width is
        // known without measuring ahead of composition.
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                translationX =
                    if (wiping) size.width * (slot?.progress ?: 1f) * towardEnd else 0f
            },
        ) {
            decorated.Content()
        }
        // On top: the incoming pane covering from the layout end on a push.
        if (covering) {
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    translationX = size.width * (1f - (slot?.progress ?: 1f)) * towardEnd
                },
            ) {
                incoming.Content()
            }
        }
    }
}

/** Wraps pane-roled entries in the pane transport; other entries pass through. */
internal fun NavEntry<NavKey>.withPaneTransport(): NavEntry<NavKey> =
    if (metadata.containsKey(ND_PANE_SLOT)) {
        NavEntry(navEntry = this) { PaneTransportContent(this) }
    } else {
        this
    }

/** Per-pane-slot swap state. One instance spans every entry the pane renders. */
internal class PaneSlot(
    val retention: PaneRetention,
) {
    /** The pinned outgoing NavKey while a swap runs; null once the swap settles. */
    var outgoingKey by mutableStateOf<NavKey?>(null)
        private set

    /** Cover for a forward push, wipe for a back pop — mirrors the outer scene transform pair. */
    var direction by mutableStateOf(NdSceneTransition.SlideInCover)
        private set

    /** 0 -> 1 over the swap; written by the running animation, read in the draw phase. */
    var progress by mutableFloatStateOf(1f)

    /** Increments once per swap so the animation effect keys on the swap, not the emission. */
    var swapVersion by mutableIntStateOf(0)
        private set

    /** Arms the slot for [navKey]'s pin; a repeated emission of the same pin is a no-op. */
    fun arm(
        navKey: NavKey,
        direction: NdSceneTransition,
    ) {
        if (outgoingKey == navKey) return
        this.direction = direction
        outgoingKey = navKey
        progress = 0f
        swapVersion++
    }

    /** Drops the pinned outgoing emission and releases its stack pin. */
    fun settle() {
        outgoingKey?.let(retention::unpin)
        outgoingKey = null
    }
}

internal val LocalNdPaneSlot: ProvidableCompositionLocal<PaneSlot?> = compositionLocalOf { null }

/**
 * Provides the [PaneSlot] for one pane and runs its swap animation. The pane call delegates to
 * [upstream] unchanged; the transition drawing lives in [PaneTransportContent] where the
 * pinned callsite emission lands.
 */
@Composable
private fun <
    Role : PaneScaffoldRole,
    ScaffoldValue : PaneScaffoldValue<Role>,
> AnimatedPaneOverrideScope<Role, ScaffoldValue>.PaneSlotBody(
    upstream: AnimatedPaneOverride,
    retention: PaneRetention,
) {
    val slot = remember { PaneSlot(retention) }
    val swapSpec = NeutrodyneMotion.effects<Float>()

    CompositionLocalProvider(LocalNdPaneSlot provides slot) {
        with(upstream) { this@PaneSlotBody.AnimatedPane() }
    }

    LaunchedEffect(slot.swapVersion) {
        val version = slot.swapVersion
        val outgoing = slot.outgoingKey ?: return@LaunchedEffect
        try {
            animate(0f, 1f, animationSpec = swapSpec) { value, _ -> slot.progress = value }
        } finally {
            // A newer swap owns its own settle; only the version this effect ran for ends here.
            if (slot.swapVersion == version && slot.outgoingKey == outgoing) slot.settle()
        }
    }
}

/**
 * Expires pinned entries a pane never emitted (e.g. a pane that never re-composed after the
 * stack change) so they cannot linger on the decorated list past [PINNED_ENTRY_TIMEOUT_MS].
 */
@Composable
internal fun PinnedEntryExpiry(retention: PaneRetention) {
    val stamp = retention.pins.keys.toSet()
    LaunchedEffect(stamp) {
        if (stamp.isEmpty()) return@LaunchedEffect
        delay(PINNED_ENTRY_TIMEOUT_MS)
        retention.expireAll(stamp)
    }
}

/** NavKey serializer for the persisted departure set — polymorphic over every registered key. */
internal val DepartureSetSerializer: KSerializer<Set<NavKey>> =
    SetSerializer(PolymorphicSerializer(NavKey::class))
