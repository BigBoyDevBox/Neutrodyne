// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleInfoTest {
    @Test
    fun pathMatchesModule() {
        assertEquals(":core:domain", ModuleInfo.PATH)
    }
}
