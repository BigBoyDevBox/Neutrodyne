// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleInfoTest {
    @Test
    fun pathMatchesModule() {
        assertEquals(":core:designsystem", ModuleInfo.PATH)
    }
}
