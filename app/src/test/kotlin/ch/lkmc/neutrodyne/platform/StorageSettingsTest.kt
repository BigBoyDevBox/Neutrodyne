// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.platform

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.storage.StorageManager
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The start-up gate's "Manage storage" must not crash the recovery screen on an OEM build. */
@RunWith(RobolectricTestRunner::class)
class StorageSettingsTest {
    @Test
    fun theInternalStorageScreenOpensFirst() {
        val started = mutableListOf<String?>()

        assertTrue(openStorageSettings(PACKAGE) { started += it.action })
        assertEquals(listOf(Settings.ACTION_INTERNAL_STORAGE_SETTINGS), started)
    }

    @Test
    fun aBuildWithoutStorageScreensFallsBackToTheAppDetails() {
        val missing = setOf(Settings.ACTION_INTERNAL_STORAGE_SETTINGS, StorageManager.ACTION_MANAGE_STORAGE)
        val started = mutableListOf<Intent>()

        val opened =
            openStorageSettings(PACKAGE) { intent ->
                if (intent.action in missing) throw ActivityNotFoundException(intent.action)
                started += intent
            }

        assertTrue(opened)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, started.single().action)
        assertEquals("package:$PACKAGE", started.single().dataString)
    }

    @Test
    fun noSettingsScreenAtAllReportsFailureInsteadOfThrowing() {
        assertFalse(openStorageSettings(PACKAGE) { throw ActivityNotFoundException(it.action) })
    }

    private companion object {
        const val PACKAGE = "ch.lkmc.neutrodyne.debug"
    }
}
