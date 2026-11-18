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
            "pod.link/1234",
            "user@example.com",
            "nas/feed.xml",
            "x/rss?id=1",
            "/Users/me/feed.xml",
            "~/feed.xml",
            "C:\\feeds\\show.xml",
            "[::1]",
            "2001:db8:0:0:0:0:0:1",
            "UC" + "a".repeat(22),
        )) {
            assertTrue(isLinkInput(input), "expected a link: $input")
        }
    }

    @Test
    fun programmeNamesWithPunctuationSearch() {
        for (input in listOf(
            "Star Wars: The Clone Wars",
            "Radiolab: Extra",
            "AC/DC",
            "9/11 stories",
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
        )) {
            assertFalse(isLinkInput(input), "expected a search: $input")
        }
    }
}
