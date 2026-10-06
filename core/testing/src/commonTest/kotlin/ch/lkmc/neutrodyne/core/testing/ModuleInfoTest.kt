// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.testing

import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleInfoTest {
    @Test
    fun pathMatchesModule() {
        assertEquals(":core:testing", ModuleInfo.PATH)
    }
}
