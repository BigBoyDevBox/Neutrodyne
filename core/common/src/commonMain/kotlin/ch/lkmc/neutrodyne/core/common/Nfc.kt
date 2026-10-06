// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common

/**
 * Unicode NFC normalization, small per-platform shim (01 Source sets and JVM islands): Java
 * `java.text.Normalizer` on both targets — the `expect` exists to keep `java.*` out of
 * `commonMain`. Podcast titles/search keys must compare NFC-equal (05/06 use it).
 */
expect object Nfc {
    /** Returns [text] in NFC — canonical composed form. */
    fun normalize(text: String): String
}
