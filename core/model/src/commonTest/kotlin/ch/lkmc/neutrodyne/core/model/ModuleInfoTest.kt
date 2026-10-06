// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleInfoTest {
    @Test
    fun pathMatchesModule() {
        assertEquals(":core:model", ModuleInfo.PATH)
    }
}
