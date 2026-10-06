// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleInfoTest {
    @Test
    fun pathMatchesModule() {
        assertEquals(":feature:settings", ModuleInfo.PATH)
    }
}
