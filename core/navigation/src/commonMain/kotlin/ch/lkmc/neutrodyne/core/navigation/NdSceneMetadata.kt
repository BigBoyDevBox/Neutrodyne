// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.navigation

/**
 * Overlay metadata understood by the host's scene strategies. Sheet and dialog keys are pushed on
 * the selected tab's stack and rendered above the root `PlayerSheet` through window-based surfaces.
 */
public object NdSceneMetadata {
    public const val KEY_OVERLAY: String = "nd.overlay"

    /** Metadata for bottom-sheet keys (`AddPodcastKey`, `SpeedKey`, …). */
    public fun bottomSheet(): Map<String, Any> = mapOf(KEY_OVERLAY to OVERLAY_SHEET)

    /** Metadata for dialog keys (`ExportKey`, `VerificationNoticeKey`, …). */
    public fun dialog(): Map<String, Any> = mapOf(KEY_OVERLAY to OVERLAY_DIALOG)

    private const val OVERLAY_SHEET: String = "sheet"
    private const val OVERLAY_DIALOG: String = "dialog"
}
