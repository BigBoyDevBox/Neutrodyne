// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.queue

import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleInfoTest {
    @Test
    fun pathMatchesModule() {
        assertEquals(":feature:queue", ModuleInfo.PATH)
    }
}
