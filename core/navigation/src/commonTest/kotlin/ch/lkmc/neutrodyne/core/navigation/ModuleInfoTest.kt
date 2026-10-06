// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.navigation

import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleInfoTest {
    @Test
    fun pathMatchesModule() {
        assertEquals(":core:navigation", ModuleInfo.PATH)
    }
}
