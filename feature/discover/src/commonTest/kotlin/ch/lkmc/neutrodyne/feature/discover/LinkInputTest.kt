// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.discover

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Discover sends search text to the directory providers, so anything that could be an address
 * stays out of it, while programme names that merely use punctuation still search.
 */
class LinkInputTest {
    @Test
    fun addressesHandlesAndPathsStayLinks() {
        for (input in listOf(
            "https://private.test/feed?token=secret",
            "feed:private",
            "itpc://example.org/rss",
            "@handle",
            "Listen https://private.test/x",
            "192.168.1.2",
            "192.168.1.2:8080/feed",
            "localhost",
            "localhost/feed",
            "myserver:8080/feed",
            "example.com",
            "feeds.example.co.uk/podcast",
            "(example.com).",
            "`private.example`",
            "*private.example*",
            "|localhost|",
            "__bücher.example__",
            "[feed](private.example)",
            "private.example#payroll",
            "`192.168.1.2`",
            "pod.link/1234",
            "user@example.com",
            "myserver/feed",
            "NAS/HR",
            "Listen on bücher.example",
            "例え。テスト",
            "nas/feed.xml",
            "x/rss?id=1",
            "/Users/me/feed.xml",
            "~/feed.xml",
            "C:\\feeds\\show.xml",
            "[::1]",
            "2001:db8:0:0:0:0:0:1",
            "UC" + "a".repeat(22),
            // Pasted prose: no-break spaces and typographic punctuation around an address.
            "Listen on\u00A0example.com",
            "check\u202Fhttps://private.test/x",
            "\u201Cexample.com\u201D",
            "\u2026https://private.test/x",
            "\u00ABpod.link/1234\u00BB",
            "Listen https://private.test/x.",
        )) {
            assertTrue(isLinkInput(input), "expected a link: $input")
        }
    }

    @Test
    fun programmeNamesWithPunctuationSearch() {
        for (input in listOf(
            "Star Wars: The Clone Wars",
            "Radiolab: Extra",
            "Web 2.0",
            "The A.I. Show",
            "U.S.A. history",
            "Dr. Death",
            "Ep.42",
            ".NET Rocks",
            "C++ Weekly",
            "99% Invisible",
            "Q&A",
            "News at 10:30",
            "What's new?",
            "\u201CStar Wars: The Clone Wars\u201D",
            "\u201CQ&A: Live\u201D",
            "Star\u00A0Wars: The Clone Wars",
        )) {
            assertFalse(isLinkInput(input), "expected a search: $input")
        }
    }

    /**
     * Word-local rules cannot separate every address from every title, so these are the accepted
     * costs, pinned so a refactor does not shift them unnoticed. A single-label host with a bare
     * path has the shape of "AC/DC". Slash names, colon codes, time ranges and "~" approximations
     * read as links and are kept from the directory.
     */
    @Test
    fun acceptedTradeOffsStayAsDecided() {
        for (input in listOf("AC/DC", "9/11 stories", "\u201CAC/DC\u201D", "S1:E5", "9:00-10:00", "~50 episodes")) {
            assertTrue(isLinkInput(input), "kept from the directory: $input")
        }
    }
}
