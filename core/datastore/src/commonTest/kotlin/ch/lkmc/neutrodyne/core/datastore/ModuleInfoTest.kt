// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.datastore

import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleInfoTest {
    @Test
    fun pathMatchesModule() {
        assertEquals(":core:datastore", ModuleInfo.PATH)
    }
}
