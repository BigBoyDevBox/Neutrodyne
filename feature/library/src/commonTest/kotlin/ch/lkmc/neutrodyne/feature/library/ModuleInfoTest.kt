// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.library

import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleInfoTest {
    @Test
    fun pathMatchesModule() {
        assertEquals(":feature:library", ModuleInfo.PATH)
    }
}
