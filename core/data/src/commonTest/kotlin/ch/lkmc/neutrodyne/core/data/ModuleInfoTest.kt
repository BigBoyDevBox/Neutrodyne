// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.data

import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleInfoTest {
    @Test
    fun pathMatchesModule() {
        assertEquals(":core:data", ModuleInfo.PATH)
    }
}
