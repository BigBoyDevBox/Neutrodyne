// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The one writer for the Android LAN-guard bypass (01 Interceptors). Only 10's
 * `LocalNetworkPermissionGate` calls [setSyncAllowed]; the Android `:core:net` DNS bind reads
 * [syncAllowed] to unblock LAN sync endpoints when Android 16+ has granted Local Network
 * permission. Desktop binds an always-true equivalent — the gate never applies there.
 */
@SingleIn(AppScope::class)
@Inject
class LocalNetworkAccess {
    private val _syncAllowed = MutableStateFlow(false)

    /** Whether sync may reach private-range addresses; `false` until the gate grants it. */
    val syncAllowed: StateFlow<Boolean> = _syncAllowed.asStateFlow()

    fun setSyncAllowed(allowed: Boolean) {
        _syncAllowed.value = allowed
    }
}
