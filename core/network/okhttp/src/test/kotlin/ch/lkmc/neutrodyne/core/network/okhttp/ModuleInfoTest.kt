// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.network.okhttp

import org.junit.Assert.assertEquals
import org.junit.Test

class ModuleInfoTest {
    @Test
    fun pathMatchesModule() {
        assertEquals(":core:network:okhttp", ModuleInfo.PATH)
    }
}
