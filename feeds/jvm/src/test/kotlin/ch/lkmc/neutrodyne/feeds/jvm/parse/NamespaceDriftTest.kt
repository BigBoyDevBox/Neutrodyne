// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.parse

import ch.lkmc.neutrodyne.feeds.model.ParsedFeed
import ch.lkmc.neutrodyne.feeds.model.WarningCode
import ch.lkmc.neutrodyne.feeds.parse.ParseResult
import okio.Buffer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The namespace registry tolerates the URI drift real feeds ship (03 Namespace registry) — a
 * dropped trailing slash or an `https` scheme must not lose `dc:creator`, `sy:updatePeriod` or
 * `content:encoded`, and a *declared-but-drifted* URI is not an undeclared prefix.
 */
class NamespaceDriftTest {
    private fun feedOf(xml: String): ParsedFeed =
        XmlPullFeedParser
            .discovered()
            .parse({ Buffer().write(xml.encodeToByteArray()) }, null, "https://feeds.test/feed.xml")
            .let { assertIs<ParseResult.Ok>(it).feed }

    @Test
    fun dcElementsWithoutTheTrailingSlashStillMap() {
        val feed =
            feedOf(
                "<rss version=\"2.0\" xmlns:dc=\"http://purl.org/dc/elements/1.1\">" +
                    "<channel><dc:creator>Ada</dc:creator></channel></rss>",
            )
        assertEquals("Ada", feed.author)
    }

    @Test
    fun dcElementsOverHttpsStillMap() {
        val feed =
            feedOf(
                "<rss version=\"2.0\" xmlns:dc=\"https://purl.org/dc/elements/1.1/\">" +
                    "<channel><dc:creator>Ada</dc:creator></channel></rss>",
            )
        assertEquals("Ada", feed.author)
    }

    @Test
    fun syModuleWithoutTheTrailingSlashStillMaps() {
        val feed =
            feedOf(
                "<rss version=\"2.0\" xmlns:sy=\"http://purl.org/rss/1.0/modules/syndication\">" +
                    "<channel><sy:updatePeriod>hourly</sy:updatePeriod>" +
                    "<sy:updateFrequency>2</sy:updateFrequency></channel></rss>",
            )
        assertEquals(30, feed.ttlMinutes)
    }

    /** A drifted but declared `content` URI resolves — and is not an undeclared prefix. */
    @Test
    fun contentModuleWithoutTheTrailingSlashIsNotUndeclared() {
        val feed =
            feedOf(
                "<rss version=\"2.0\" xmlns:content=\"http://purl.org/rss/1.0/modules/content\">" +
                    "<channel><item><content:encoded><![CDATA[<p>body</p>]]></content:encoded>" +
                    "</item></channel></rss>",
            )
        assertEquals("<p>body</p>", feed.items.single().descriptionHtml)
        assertTrue(feed.warnings.none { it.code == WarningCode.UNDECLARED_PREFIX }, feed.warnings.toString())
    }
}
