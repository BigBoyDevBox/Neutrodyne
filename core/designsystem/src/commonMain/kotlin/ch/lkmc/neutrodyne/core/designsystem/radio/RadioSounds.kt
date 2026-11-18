// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.radio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The radio's control sounds (08 Control sounds, PO-56): short public-domain recordings of real
 * mechanisms, credited in THIRD_PARTY_NOTICES.md and the Licences screen. A control plays its
 * sound when the user works it (touch, mouse, keyboard or an accessibility action), never when
 * the app changes its state. [file] is the Compose resource: 48 kHz mono 16-bit PCM WAV.
 */
public enum class RadioSound(
    internal val file: String,
) {
    /** A bat toggle thrown up to ON. */
    SWITCH_ON("files/sounds/switch_on.wav"),

    /** A bat toggle thrown down to OFF. */
    SWITCH_OFF("files/sounds/switch_off.wav"),

    /** A piano key pushed in until it latches: a band key, a latching key or a station. */
    KEY_LATCH("files/sounds/key_latch.wav"),

    /** A key that springs back: momentary and action keys, round ivory keys, a latched key let go. */
    KEY_PRESS("files/sounds/key_press.wav"),

    /** One notch of a counter's number wheels. */
    DIAL_TICK("files/sounds/dial_tick.wav"),

    /** A paper slip pushed off the radio (discard). */
    SLIP_DISCARD("files/sounds/slip_discard.wav"),
}

/**
 * Plays [RadioSound]s. Implementations return at once, never throw, and drop a sound they
 * cannot play (still loading, no audio device): the sounds are feedback, not information, so
 * nothing the user needs depends on hearing one.
 */
public fun interface RadioSounds {
    public fun play(sound: RadioSound)

    public companion object {
        /** Plays nothing: tests, previews and the Control sounds setting switched off. */
        public val Silent: RadioSounds = RadioSounds {}
    }
}

/** Provided by each shell from `appearance.control_sounds` and [rememberRadioSounds]. */
public val LocalRadioSounds: ProvidableCompositionLocal<RadioSounds> = staticCompositionLocalOf { RadioSounds.Silent }

/**
 * The platform's player while [enabled], loading the sounds in the background and releasing them
 * when it leaves the composition; [RadioSounds.Silent] otherwise. Android plays through
 * `SoundPool` as sonification (the system volume applies, silent and vibrate modes mute it, and
 * podcast audio focus is untouched); the desktop through Java Sound clips on the default output.
 */
@Composable
public expect fun rememberRadioSounds(enabled: Boolean): RadioSounds

/** [onValueChange] for a switch that sounds its lever: [RadioSound.SWITCH_ON] or `SWITCH_OFF`. */
@Composable
public fun rememberSwitchSound(onValueChange: (Boolean) -> Unit): (Boolean) -> Unit {
    val sounds = LocalRadioSounds.current
    return remember(sounds, onValueChange) {
        { on ->
            sounds.play(if (on) RadioSound.SWITCH_ON else RadioSound.SWITCH_OFF)
            onValueChange(on)
        }
    }
}

/** [onClick] for a control that plays [sound] as it is worked. */
@Composable
public fun rememberClickSound(
    sound: RadioSound,
    onClick: () -> Unit,
): () -> Unit {
    val sounds = LocalRadioSounds.current
    return remember(sounds, sound, onClick) {
        {
            sounds.play(sound)
            onClick()
        }
    }
}
