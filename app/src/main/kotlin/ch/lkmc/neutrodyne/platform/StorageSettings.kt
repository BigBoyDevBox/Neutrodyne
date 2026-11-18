// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.platform

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.storage.StorageManager
import android.provider.Settings

/**
 * Opens a storage settings screen for the start-up gate's "Manage storage" (disk full). OEM builds
 * differ in which screens they ship, so this tries, in order, the internal storage settings (01's
 * documented deviation), the platform's manage-storage screen and finally this app's details page,
 * which every build has; [start] is the activity's `startActivity`. Returns whether one opened, so
 * the recovery screen never crashes on a missing screen.
 */
internal fun openStorageSettings(
    packageName: String,
    start: (Intent) -> Unit,
): Boolean =
    storageSettingsIntents(packageName).any { intent ->
        try {
            start(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

private fun storageSettingsIntents(packageName: String): List<Intent> =
    listOf(
        Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
        Intent(StorageManager.ACTION_MANAGE_STORAGE),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
    )
