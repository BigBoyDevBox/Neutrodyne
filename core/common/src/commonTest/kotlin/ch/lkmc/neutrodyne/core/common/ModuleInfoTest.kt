// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.common

import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleInfoTest {
    @Test
    fun pathMatchesModule() {
        assertEquals(":core:common", ModuleInfo.PATH)
    }
}
