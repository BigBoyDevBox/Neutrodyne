// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.parse

/**
 * kxml2 pushes a numeric character reference through a single `char` cast, so a supplementary code
 * point like `&#x1F600;` arrives truncated to U+F600 — in text and in attribute values alike (it
 * also means the literal `😀` and the reference form compare differently downstream). AOSP's parser
 * forks the same code, so the Android binding shares the defect.
 *
 * [rewrite] repairs the decoded document before the parser sees it: supplementary numeric
 * references are replaced by their literal characters wherever the parser's own entity decoding
 * applies — element text and attribute values — while regions it leaves alone (comments, CDATA
 * sections, processing instructions, markup declarations) pass through verbatim. The scan mirrors
 * the relaxed kxml2 lexer, so a reference the parser would never decode is never rewritten: an
 * unterminated `&name<` swallows its terminator as text exactly as `pushEntity` does, and `&#` in a
 * tag name position stays a name. Returns `null` when nothing needs rewriting, so the common feed
 * costs one index probe and no copy.
 */
internal object SupplementaryRefs {
    private const val REPLACEMENT_PREFIX = "&#"
    private const val MAX_CODE_POINT = 0x10FFFF
    private const val SUPPLEMENTARY_FLOOR = 0xFFFF
    private const val HEX_RADIX = 16

    /** [text] with supplementary numeric references rewritten to literals, or null when unchanged. */
    fun rewrite(text: String): String? {
        if (text.indexOf(REPLACEMENT_PREFIX) < 0) return null
        var edits: ArrayList<Triple<Int, Int, Int>>? = null
        var i = 0
        while (i < text.length) {
            when (text[i]) {
                '&' -> {
                    val end = PrologLexer.entityRefEnd(text, i)
                    val codePoint = supplementaryOf(text, i, end)
                    if (codePoint != null) {
                        (edits ?: ArrayList<Triple<Int, Int, Int>>().also { edits = it })
                            .add(Triple(i, end, codePoint))
                    }
                    i = end
                }

                '<' -> {
                    i = skipMarkup(text, i, edits ?: ArrayList<Triple<Int, Int, Int>>().also { edits = it })
                }

                else -> {
                    i++
                }
            }
        }
        val found = edits ?: return null
        if (found.isEmpty()) return null
        return buildString(text.length) {
            var pos = 0
            for ((start, end, codePoint) in found) {
                append(text, pos, start)
                appendCodePoint(codePoint)
                pos = end
            }
            append(text, pos, text.length)
        }
    }

    /**
     * The code point of a `;`-terminated numeric reference spanning `&#…;`, when it is a supplementary
     * character. Decimal digits or lowercase-`x` hex — kxml2 accepts only `#x`, a `#X` fails its
     * `parseInt` the same way it fails ours, and the verbatim bytes then crash the parser identically.
     */
    private fun supplementaryOf(
        text: String,
        amp: Int,
        end: Int,
    ): Int? {
        if (end - amp < 4 || text[end - 1] != ';' || text[amp + 1] != '#') return null
        val codePoint =
            runCatching {
                if (text[amp + 2] == 'x') {
                    text.substring(amp + 3, end - 1).toInt(HEX_RADIX)
                } else {
                    text.substring(amp + 2, end - 1).toInt()
                }
            }.getOrNull() ?: return null
        return codePoint.takeIf { it > SUPPLEMENTARY_FLOOR && it <= MAX_CODE_POINT }
    }

    /**
     * Past the markup construct at `&lt;`-position [lt]; attribute values inside still get
     * scanned. Every boundary is [PrologLexer]'s, so this walk desynchronises exactly the way
     * the pull parser — and the prolog guards — do.
     */
    private fun skipMarkup(
        text: String,
        lt: Int,
        edits: MutableList<Triple<Int, Int, Int>>,
    ): Int =
        when {
            PrologLexer.isCommentStart(text, lt) -> {
                PrologLexer.commentEnd(text, lt)
            }

            PrologLexer.isCdataStart(text, lt) -> {
                PrologLexer.cdataEnd(text, lt)
            }

            text.startsWith(PrologLexer.PI_OPEN, lt) -> {
                if (PrologLexer.isXmlDeclAt(text, lt)) {
                    scanTag(text, lt + PrologLexer.PI_OPEN.length, edits, xmlDecl = true)
                } else {
                    PrologLexer.piEnd(text, lt, Int.MAX_VALUE)
                }
            }

            lt + 1 < text.length && text[lt + 1] == '/' -> {
                PrologLexer.endTagEnd(text, lt)
            }

            text.startsWith(PrologLexer.DECL_OPEN, lt) -> {
                PrologLexer.markupDeclEnd(text, lt)
            }

            else -> {
                scanTag(text, lt + 1, edits, xmlDecl = false)
            }
        }

    /**
     * The tag starting at [nameStart], entity-decoding inside attribute values like the parser does.
     * In [xmlDecl] form the tag ends on `?` (kxml2's decl path), otherwise on `>` or a degenerate `/`.
     */
    private fun scanTag(
        text: String,
        nameStart: Int,
        edits: MutableList<Triple<Int, Int, Int>>,
        xmlDecl: Boolean,
    ): Int {
        var i = PrologLexer.nameEnd(text, nameStart)
        while (i < text.length) {
            i = PrologLexer.skipTagWhitespace(text, i)
            if (i >= text.length) return text.length
            val c = text[i]
            when {
                xmlDecl && c == '?' -> {
                    return minOf(i + 2, text.length)
                }

                !xmlDecl && c == '>' -> {
                    return i + 1
                }

                !xmlDecl && c == '/' -> {
                    return minOf(PrologLexer.skipTagWhitespace(text, i + 1) + 1, text.length)
                }

                else -> {
                    i = PrologLexer.skipTagWhitespace(text, PrologLexer.nameEnd(text, i))
                    if (i < text.length && text[i] == '=') {
                        i = PrologLexer.skipTagWhitespace(text, i + 1)
                        if (i < text.length) {
                            val q = text[i]
                            i =
                                if (q == '"' || q == '\'') {
                                    valueEnd(text, i + 1, quote = q, edits)
                                } else {
                                    valueEnd(text, i, quote = ' ', edits)
                                }
                        }
                    }
                }
            }
        }
        return text.length
    }

    /** An attribute value ends on its quote — or on whitespace / `>` when unquoted. */
    private fun valueEnd(
        text: String,
        start: Int,
        quote: Char,
        edits: MutableList<Triple<Int, Int, Int>>,
    ): Int {
        var i = start
        while (i < text.length) {
            val c = text[i]
            when {
                c == '&' -> {
                    val end = PrologLexer.entityRefEnd(text, i)
                    supplementaryOf(text, i, end)?.let { edits.add(Triple(i, end, it)) }
                    i = end
                }

                quote == ' ' && (c <= ' ' || c == '>') -> {
                    return i
                }

                c == quote -> {
                    return i + 1
                }

                else -> {
                    i++
                }
            }
        }
        return i
    }
}
