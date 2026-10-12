// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.parse

import ch.lkmc.neutrodyne.feeds.parse.ParseResult
import okio.Buffer
import org.junit.Test
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * `InnerXml` re-serialisation rules (03 step 6): an empty non-void element keeps an explicit closing
 * tag — `<strong/>` re-serialised as bare `<strong>` would swallow the text that follows it; the HTML
 * void elements keep their bare form.
 */
class InnerXmlTest {
    private fun descriptionHtmlOf(inner: String): String? =
        XmlPullFeedParser
            .discovered()
            .parse(
                {
                    Buffer().write(
                        "<rss version=\"2.0\"><channel><item><description>$inner</description></item></channel></rss>"
                            .encodeToByteArray(),
                    )
                },
                null,
                "https://feeds.test/feed.xml",
            ).let {
                assertIs<ParseResult.Ok>(it)
                    .feed.items
                    .single()
                    .descriptionHtml
            }

    @Test
    fun emptyNonVoidElementGetsAnExplicitClose() {
        assertEquals("<strong></strong>plain", descriptionHtmlOf("<strong/>plain"))
    }

    @Test
    fun voidElementStaysBare() {
        assertEquals("a<br>b", descriptionHtmlOf("a<br/>b"))
    }

    /**
     * A CDATA run gets `stripRelaxedError` before the sink — `onText` must observe the same
     * stripped text the output carries, like the TEXT branch does.
     */
    @Test
    fun cdataReportsStrippedTextToOnText() {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true)
        parser.setInput(StringReader("<d><![CDATA[ERR: undefined prefix: atomhello]]></d>"))
        while (parser.eventType != XmlPullParser.START_TAG) parser.nextToken()
        val seen = mutableListOf<String>()
        val collected = InnerXml.collect(parser, onText = { seen += it })
        assertEquals("hello", collected.text)
        assertEquals(listOf("hello"), seen)
    }
}
