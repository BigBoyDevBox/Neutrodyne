// SPDX-License-Identifier: Unlicense
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/** [01 Testing]: allowed and rejected SPDX expressions, the OR/`elected` rule and the pbs-patches case. */
class PythonLicencePolicyTest {
    private fun lock(
        licence: String,
        elected: String? = null,
        kind: String = "native",
        aboutLibrariesId: String = "x",
        extraTopLevel: String = "",
    ): PythonLicencePolicy.Lock =
        PythonLicencePolicy.parse(
            buildString {
                append("schema = 1\n")
                append(extraTopLevel)
                append("\n[[component]]\n")
                append("name = \"comp\"\nversion = \"1.0\"\norigin = \"maven:x:y\"\n")
                append("licence = \"$licence\"\n")
                if (elected != null) append("elected = \"$elected\"\n")
                append("kind = \"$kind\"\naboutLibrariesId = \"$aboutLibrariesId\"\n")
            },
        )

    private fun violations(
        l: PythonLicencePolicy.Lock,
        desktop: Boolean = false,
        ids: Set<String> = setOf("x"),
    ) = PythonLicencePolicy.violations(l, desktop, null, null, null, ids)

    @Test
    fun `allow-listed licences pass`() {
        for (licence in PythonLicencePolicy.ALLOWED_LICENCES) {
            assertEquals(emptyList(), violations(lock(licence)), "expected $licence to pass")
        }
    }

    @Test
    fun `restricted licences fail`() {
        for (licence in listOf("GPL-2.0-only", "GPL-3.0-or-later", "LGPL-2.1-or-later", "AGPL-3.0-only", "Sleepycat")) {
            assertTrue(violations(lock(licence)).isNotEmpty(), "expected $licence to fail")
        }
    }

    @Test
    fun `mpl only for data kind`() {
        assertEquals(emptyList(), violations(lock("MPL-2.0", kind = "data")))
        assertTrue(violations(lock("MPL-2.0", kind = "native")).isNotEmpty())
        assertTrue(violations(lock("MPL-2.0", kind = "runtime")).isNotEmpty())
    }

    @Test
    fun `or expression requires elected allowed alternative`() {
        assertEquals(
            emptyList(),
            violations(lock("BSD-3-Clause OR GPL-2.0-only", elected = "BSD-3-Clause")),
        )
        assertTrue(violations(lock("BSD-3-Clause OR GPL-2.0-only")).isNotEmpty()) // no elected
        assertTrue(violations(lock("BSD-3-Clause OR GPL-2.0-only", elected = "GPL-2.0-only")).isNotEmpty())
        assertTrue(violations(lock("BSD-3-Clause OR GPL-2.0-only", elected = "MIT")).isNotEmpty()) // not an alternative
    }

    @Test
    fun `pbs-patches is the desktop lock's only mpl code case`() {
        val topLevel =
            "pbs = \"20250918\"\n[pbsSource]\ntag = \"20250918\"\n" +
                "name = \"python-build-standalone-20250918-src.tar.gz\"\nurl = \"https://x\"\n" +
                "sha256 = \"${"0".repeat(64)}\"\n"
        val good = lock("MPL-2.0", kind = "pbs-patches", extraTopLevel = topLevel)
        assertEquals(emptyList(), violations(good, desktop = true))
        assertTrue(violations(good, desktop = false).isNotEmpty()) // Android lock never has pbs-patches

        val wrongTag =
            lock(
                "MPL-2.0",
                kind = "pbs-patches",
                extraTopLevel = "pbs = \"20250918\"\n[pbsSource]\ntag = \"other\"\nsha256 = \"${"0".repeat(64)}\"\n",
            )
        assertTrue(violations(wrongTag, desktop = true).isNotEmpty())

        val noSource = lock("MPL-2.0", kind = "pbs-patches", extraTopLevel = "pbs = \"20250918\"\n")
        assertTrue(violations(noSource, desktop = true).isNotEmpty())

        val asData = lock("MPL-2.0", kind = "data", extraTopLevel = topLevel)
        // patches recorded as kind=data pass the licence check (MPL data is legal), but pbs-patches
        // entries must never be recorded as data — enforced by the kind mechanism on real entries
        assertEquals(emptyList(), violations(asData, desktop = true))
    }

    @Test
    fun `missing fields and unknown kinds fail`() {
        // aboutLibrariesId is mandatory — parse throws before violations run
        assertFails {
            PythonLicencePolicy.parse(
                "schema = 1\n[[component]]\nname = \"c\"\nversion = \"1\"\norigin = \"o\"\n" +
                    "licence = \"MIT\"\nkind = \"native\"\n",
            )
        }
        assertTrue(violations(lock("MIT", kind = "bogus")).isNotEmpty())
        assertTrue(violations(lock("MIT"), ids = emptySet()).isNotEmpty()) // no AboutLibraries definition
    }

    @Test
    fun `schema must be 1`() {
        assertFails { PythonLicencePolicy.parse("schema = 2\n") }
    }

    @Test
    fun `build cross-checks`() {
        val l =
            PythonLicencePolicy.parse(
                "schema = 1\nchaquopy = \"17.1.0\"\npython = \"3.14.0\"\npip = [\"yt-dlp\"]\n",
            )
        assertEquals(
            emptyList(),
            PythonLicencePolicy.violations(l, false, "17.1.0", "3.14", listOf("yt-dlp"), emptySet()),
        )
        assertTrue(
            PythonLicencePolicy.violations(l, false, "17.0.0", "3.14", listOf("yt-dlp"), emptySet()).isNotEmpty(),
        )
        assertTrue(
            PythonLicencePolicy.violations(l, false, "17.1.0", "3.13", listOf("yt-dlp"), emptySet()).isNotEmpty(),
        )
        assertTrue(
            PythonLicencePolicy.violations(l, false, "17.1.0", "3.14", emptyList(), emptySet()).isNotEmpty(),
        )
    }
}
