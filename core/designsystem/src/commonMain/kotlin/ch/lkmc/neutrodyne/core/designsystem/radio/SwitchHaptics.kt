// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.radio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Wraps a switch row's [onValueChange] so the listener feels the bat lever land: Android's
 * ToggleOn or ToggleOff for the new value (which follows the system's touch-feedback setting),
 * nothing on the desktop. Only the listener's own toggles are felt, never a value that changes
 * underneath. [BatToggle] does the same for a lever that is its own switch.
 */
@Composable
public fun withSwitchHaptics(onValueChange: (Boolean) -> Unit): (Boolean) -> Unit {
    val haptics = LocalHapticFeedback.current
    val current by rememberUpdatedState(onValueChange)
    return remember(haptics) {
        { on ->
            haptics.feelSwitch(on)
            current(on)
        }
    }
}

internal fun HapticFeedback.feelSwitch(on: Boolean) {
    performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
}
