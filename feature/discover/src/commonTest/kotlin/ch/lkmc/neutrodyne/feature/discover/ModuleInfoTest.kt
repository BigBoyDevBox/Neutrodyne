// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.discover

import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleInfoTest {
    @Test
    fun pathMatchesModule() {
        assertEquals(":feature:discover", ModuleInfo.PATH)
    }
}
