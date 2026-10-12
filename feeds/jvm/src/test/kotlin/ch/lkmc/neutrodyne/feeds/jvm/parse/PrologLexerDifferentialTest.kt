// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.parse

import org.junit.Test
import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.util.Locale
import java.util.Random
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * A deterministic, seeded subset of the external differential harness: generated prolog-shaped
 * inputs are lexed by raw relaxed kxml2 2.3.0 and compared against the shared [PrologLexer] walk
 * behind [PrologGuard] and [TagBounds]. Two invariants may never break — a pre-root DOCDECL
 * whose source span carries `<!ENTITY` must be hostile, and no start tag may carry more
 * attributes for kxml2 than the guard-side walk counted (the attribute bound exists to gate
 * kxml2's quadratic attribute work, so an under-count is a real bypass). Over-reads in the
 * guard's favour are pinned too: parity means the conservative direction stays empty here.
 */
class PrologLexerDifferentialTest {
    private companion object {
        const val RELAXED = "http://xmlpull.org/v1/doc/features.html#relaxed"
        const val ENTITY_MARK = "<!entity"
        const val EVENT_CAP = 20_000
        const val SEED = 0xC0FFEE
        const val FUZZ_COUNT = 40_000
        const val RSS = "<rss><channel><title>T</title></channel></rss>"
        const val ENT = "<!DOCTYPE rss [<!ENTITY x 'boom'>]>"
        const val RSS8 = "<rss a='1' b='2' c='3' d='4' e='5' f='6' g='7' h='8'><channel/></rss>"

        val COMMENTS =
            arrayOf(
                "<!--x-->",
                "<!---->",
                "<!-->",
                "<!--->",
                "<!-- -->",
                "<!--x--!>",
                "<!--x-- >",
                "<!--x--->",
                "<!--x---->",
                "<!-",
                "<!->",
                "<!--",
                "<!--x",
                "<!--x-",
                "<!--x--",
                "<!-x-->",
                "<!-'-->",
                "<!-x'-->",
                "<!-'x'-->",
                "<!--'-->",
                "<!--'-->'",
                "<!- -- -->",
                "<!-- - -->",
                "<!--a<!ENTITY x 'v'>-->",
                "<!--x--><!--y-->",
                "<!-x-!->",
                "<!-x<!--y-->",
            )
        val CDATAS =
            arrayOf(
                "<![CDATA[x]]>",
                "<![CDATA[]]>",
                "<![CDATA[",
                "<![CDATA[x",
                "<![CDATA[x]]",
                "<![x]]>",
                "<![]]>",
                "<![",
                "<![C",
                "<![CDATA'x']]>",
                "<![CDATA[x]>",
                "<![CDATA['<'>]]>",
                "<![CDATA[a]]>b",
                "<![CDATA[]]]>",
                "<![CDATA[]]>]>",
            )
        val DECLS =
            arrayOf(
                "<!DOCTYPE r>",
                "<!DOCTYPE r [<!ENTITY x 'v'>]>",
                "<!DOCTYPE r SYSTEM 'x'>",
                "<!ENTITY x 'v'>",
                "<!ELEMENT e (x)>",
                "<!ATTLIST a b CDATA #IMPLIED>",
                "<!NOTATION n 'x'>",
                "<!x>",
                "<!>",
                "<! >",
                "<!'>'",
                "<!''>",
                "<!''>'>",
                "<!\"\">",
                "<!<",
                "<!<>",
                "<!<>>",
                "<!a'b'c>",
                "<!a'b>",
                "<!ab'cd>",
                "<!'>",
                "<!'>x'",
                "<!x'<y>'>",
                "<![foo[bar]]>",
                "<!DOCTYPE [<!ENTITY x 'v'>]>",
                "<!DOCTYPE r ['<x>' <!ENTITY x 'v'>]>",
                "<!DOCTYPE r [<!-- <!ENTITY x 'v'> -->]>",
                "<!DOCTYPE r [<?p <!ENTITY x 'v'> ?>]>",
                "<!DOCTYPE r [<!ENTITY x 'x]>'>]>",
                "<!DOCTYPE r [<!ENTITY x \"x'>y\">]>",
                "<!DOCTYPE r [",
                "<!DOCTYPE r [<!ENTITY x 'v'",
                "<!DOCTYPE r [<!ENTITY x 'v'>]",
                "<!DOCTYPE r %pe;>",
                "<!DOCTYPE r [&x;]>",
                "<!DOCTYPE r [<!ENTITY % p 'v'>%p;]>",
                "<!!ENTITY x 'v'>",
                "<!-ENTITY x 'v'>",
                "<!''><!ENTITY x 'v'>",
                "<!'><!ENTITY x 'v'>",
                "<!a>'<!ENTITY x 'v'>",
                "<!''><!-- <!ENTITY x 'v'> -->",
                "<!>-<!-- <!ENTITY x 'v'> -->",
                "<!''><?p <!ENTITY x 'v'> ?>",
                "<!x'<a>'<!ENTITY x 'v'>",
                "<!-'-->",
                "<!-'-->>",
                "<!-'-->'",
                "<!ab'cd]]><x a='v'>",
            )
        val TAGS =
            arrayOf(
                "<r a=v>",
                "<r a='v'>",
                "<r a>",
                "<r =v>",
                "<r a=v b=w>",
                "<r a=&x;>",
                "<r a=&x>",
                "<r a=\"&x\">",
                "<r a=v/>",
                "<r a=v / >",
                "<r a=v ",
                "<r 0=1>",
                "<r '=x'>",
                "<r a==v>",
                "<r a=v>t<c d=e>",
                "<r a=&x> b=c>",
                "<r a=\"&x\" b=c>",
                "<r a='&x'>",
                "<r a=v'>w'>",
                "<r a=\"v'\">",
                "<r a='v\"'>",
                "<r>",
                "<r/>",
                "<r />",
                "<r a=v b='w' c=z>",
                "<r a=&x b=v>",
                "<r a=v&x>",
                "<r a=v&x;>",
                "<r a='&x'",
                "<r a=&x;y>",
                "<r a=%>",
                "<r%pe;>",
                "<r %pe;='x'>",
                "<r a=v?>",
                "<r a=?v>",
            )
        val ROOTS =
            arrayOf(
                RSS,
                RSS8,
                "<0/>",
                "<r " + (0 until 20).joinToString("") { i -> "a$i='v' " } + ">",
                "</x>",
                "text$RSS",
                "&x;$RSS",
                "",
                "<",
                "<!",
                "<?",
                "'",
            )

        val FUZZ_PARTS =
            arrayOf(
                "<!",
                "<!--",
                "<!-",
                "<![",
                "<![CDATA[",
                "<?",
                "<?xml",
                "<?xml ",
                "<?xm",
                "<?>",
                "<!DOCTYPE",
                "<!ENTITY",
                "</",
                "<x",
                "<0",
                "'",
                "\"",
                ">",
                "<",
                "?",
                "-",
                "[",
                "]",
                "=",
                "&",
                "&x",
                "&x;",
                "&#x;",
                " ",
                "a",
                "x",
                "rss",
                "e 'v'",
                "-->",
                "?>",
                "]]>",
                "/",
                "e \"v\"",
                "x=",
                "v'",
                "DATA",
                "TYPE",
                "ENTITY",
                " '",
                "' ",
                "!E",
                "%p;",
            )
    }

    /** One kxml2 token: type, consumed span and attribute count for START_TAG. */
    private class Ev(
        val type: Int,
        val start: Int,
        val end: Int,
        val text: String?,
        val attrs: Int,
    )

    /** Logical line starts for kxml2's `\r\n|\r|\n` to `\n` normalisation. */
    private fun lineOffsets(text: String): IntArray {
        val offsets = ArrayList<Int>()
        offsets.add(0)
        var i = 0
        while (i < text.length) {
            when (text[i]) {
                '\r' -> {
                    if (i + 1 < text.length && text[i + 1] == '\n') i++
                    offsets.add(i + 1)
                }

                '\n' -> {
                    offsets.add(i + 1)
                }
            }
            i++
        }
        return offsets.toIntArray()
    }

    private fun posOf(
        lineOff: IntArray,
        line: Int,
        col: Int,
        len: Int,
    ): Int {
        if (line < 1 || line > lineOff.size) return -1
        val pos = lineOff[line - 1] + col - 1
        return if (pos <= len) pos else -1
    }

    /** The whole-document `nextToken()` stream over the decoded text, namespaces off, relaxed on. */
    private fun kxmlTokens(text: String): List<Ev> {
        val parser = KXmlParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setFeature(RELAXED, true)
        parser.setInput(StringReader(text))
        val lines = lineOffsets(text)
        val out = ArrayList<Ev>()
        var prevEnd = 0
        repeat(EVENT_CAP) {
            val ev = nextEv(parser, lines, text.length, prevEnd)
            out.add(ev)
            if (ev.end >= 0) prevEnd = ev.end
            if (ev.type == -1 || ev.type == XmlPullParser.END_DOCUMENT) return out
        }
        return out
    }

    private fun nextEv(
        parser: KXmlParser,
        lines: IntArray,
        textLen: Int,
        prevEnd: Int,
    ): Ev {
        val type =
            try {
                parser.nextToken()
            } catch (e: Throwable) {
                return Ev(-1, prevEnd, prevEnd, e.toString(), -1)
            }
        val end = posOf(lines, parser.lineNumber, parser.columnNumber, textLen)
        return Ev(
            type,
            prevEnd,
            if (end < 0) prevEnd else end,
            runCatching { parser.text }.getOrNull(),
            if (type == XmlPullParser.START_TAG) parser.attributeCount else -1,
        )
    }

    /**
     * The [TagBounds.checkText] walk, kept in step with it deliberately: per-tag attribute
     * counts exactly as the shared lexer cuts the document.
     */
    private fun guardAttrCounts(text: String): List<Int> {
        val counts = ArrayList<Int>()
        var i = 0
        while (i < text.length) {
            when {
                text[i] == '&' -> {
                    i = PrologLexer.entityRefEnd(text, i)
                }

                text[i] != '<' -> {
                    i++
                }

                PrologLexer.isCommentStart(text, i) -> {
                    i = PrologLexer.commentEnd(text, i)
                }

                PrologLexer.isCdataStart(text, i) -> {
                    i = PrologLexer.cdataEnd(text, i)
                }

                text.startsWith(PrologLexer.PI_OPEN, i) -> {
                    i = PrologLexer.piEnd(text, i, Int.MAX_VALUE)
                }

                i + 1 < text.length && text[i + 1] == '/' -> {
                    i = PrologLexer.endTagEnd(text, i)
                }

                text.startsWith(PrologLexer.DECL_OPEN, i) -> {
                    i = PrologLexer.markupDeclEnd(text, i)
                }

                else -> {
                    counts.add(tagCount(text, i))
                    i = PrologLexer.tagEnd(text, i + 1, xmlDecl = false, maxAttributes = Int.MAX_VALUE)
                }
            }
        }
        return counts
    }

    /** The attribute count of the start tag at [i]: smallest bound for which `tagEnd` does not throw. */
    private fun tagCount(
        text: String,
        i: Int,
    ): Int {
        for (k in 0..1_200) {
            try {
                PrologLexer.tagEnd(text, i + 1, xmlDecl = false, maxAttributes = k)
                return k
            } catch (_: Exception) {
            }
        }
        return 1_201
    }

    @Test
    fun seededPrologsMatchKxml2sBoundaries() {
        val inputs = corpus() + fuzz(FUZZ_COUNT, SEED)
        var hostileMisses = 0
        var underCounts = 0
        var overCounts = 0
        var crashBoundedOvers = 0
        for (text in inputs) {
            val events = kxmlTokens(text)
            if (kxmlDeclaresEntity(events, text) && !PrologGuard.isHostileText(text)) {
                hostileMisses++
                if (hostileMisses <= 5) fail("guard missed entity DOCDECL in $text")
            }

            val kxmlCounts = events.filter { it.type == XmlPullParser.START_TAG }.map { it.attrs }.sorted()
            val guardCounts = guardAttrCounts(text).sorted()
            if (kxmlCounts != guardCounts) {
                val kmax = kxmlCounts.lastOrNull() ?: -1
                val gmax = guardCounts.lastOrNull() ?: -1
                if (gmax < kmax) {
                    underCounts++
                    if (underCounts <= 5) {
                        fail("guard under-counted attributes kxml=$kxmlCounts guard=$guardCounts in $text")
                    }
                } else if (events.last().type == -1) {
                    // An over-read is only legitimate where kxml2 died mid-stream — e.g. the
                    // NumberFormatException `&#x;` raises inside pushEntity — so it never
                    // emitted the tags the lexer counted.
                    crashBoundedOvers++
                } else {
                    overCounts++
                }
            }
        }
        assertEquals(0, hostileMisses, "hostile misses")
        assertEquals(0, underCounts, "attribute under-counts")
        assertEquals(0, overCounts, "over-reads on a live kxml2 stream ($crashBoundedOvers crash-bounded)")
    }

    /** Whether kxml2 emitted a pre-root DOCDECL whose source span carries `<!ENTITY`. */
    private fun kxmlDeclaresEntity(
        events: List<Ev>,
        text: String,
    ): Boolean =
        events
            .takeWhile { it.type != XmlPullParser.START_TAG && it.type != XmlPullParser.END_DOCUMENT && it.type != -1 }
            .any { it.type == XmlPullParser.DOCDECL && it.entitySpanContainsMark(text) }

    private fun Ev.entitySpanContainsMark(text: String): Boolean =
        end > start && end <= text.length &&
            text.substring(start, end).lowercase(Locale.ROOT).contains(ENTITY_MARK)

    // ---------------------------------------------------------------------------
    // Generators — a shrink of the review harness' corpus + seeded fuzz, deterministic.
    // ---------------------------------------------------------------------------

    private fun corpus(): List<String> {
        val inputs = ArrayList<String>()
        for (c in COMMENTS) for (r in ROOTS) inputs.add(c + r)
        for (c in COMMENTS) inputs.add(c + ENT + RSS)
        for (c in CDATAS) for (r in ROOTS) inputs.add(c + r)
        for (c in CDATAS) inputs.add(c + ENT + RSS)
        for (d in DECLS) for (r in ROOTS) inputs.add(d + r)
        for (t in TAGS) for (r in ROOTS) inputs.add(t + r)
        for (t in TAGS) inputs.add(ENT + t + RSS)
        val entityFrag = "<!ENTITY x 'v'>"
        for (open in arrayOf("<!''>", "<!>", "<!x'>", "<!-'", "<![a'", "<!x'y'")) {
            for (
            mid in
            arrayOf(
                "<!-- $entityFrag -->",
                "<?p $entityFrag ?>",
                "<![CDATA[$entityFrag]]>",
                entityFrag,
                "'<!-- $entityFrag -->'",
                "> <!-- $entityFrag -->",
                "'>$entityFrag",
                ENT,
            )
            ) {
                for (r in arrayOf(RSS, RSS8, "")) inputs.add(open + mid + r)
            }
        }
        return inputs
    }

    private fun flood(n: Int): String = (0 until n).joinToString("") { i -> "a$i='v' " }

    private fun fuzz(
        n: Int,
        seed: Int,
    ): List<String> {
        val random = Random(seed.toLong())
        return List(n) {
            buildString {
                repeat(1 + random.nextInt(14)) {
                    append(FUZZ_PARTS[random.nextInt(FUZZ_PARTS.size)])
                }
            }
        }
    }
}
