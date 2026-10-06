// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.network

import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleInfoTest {
    @Test
    fun pathMatchesModule() {
        assertEquals(":core:network", ModuleInfo.PATH)
    }
}
