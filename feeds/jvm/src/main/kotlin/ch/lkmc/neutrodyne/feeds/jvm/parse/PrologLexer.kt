// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.parse

import org.xmlpull.v1.XmlPullParserException

/**
 * One shared tokenizer over the prolog constructs relaxed kxml2 recognizes, used by both
 * [PrologGuard] and [TagBounds] so the entity guard and the attribute bound cut markup exactly
 * where the pull parser does (03 Parser setup step 1, Limits and version policy). A construct
 * one consumer over-reads hides real markup from the other; one it under-reads only costs a
 * false positive.
 *
 * Boundaries model the relaxed lexer one-to-one:
 * - `<!--` ends at the next `-->`; `<![CDATA[` ends at the next `]]>` — both are payloads, never
 *   markup, wherever they appear.
 * - `<?xml` followed by whitespace runs through kxml2's attribute lexer and ends on `?` plus the
 *   next consumed char; any other `<?` ends at the next literal `?>` or EOF.
 * - `<!` starts a markup declaration cut like `parseDoctype`: only `'` toggles the quoted state
 *   (a `"` inside a DOCTYPE hides nothing), an unquoted `<` nests, an unquoted `>` at depth zero
 *   ends the declaration — internal subsets are scanned, not swallowed as one literal.
 * - `</` reads a name, tag whitespace, then exactly one more character.
 * - A `<` before anything other than `!`, `?` or `/` opens a start tag whose first name char is
 *   unrestricted (`<0 …>` is a tag to kxml2), and a declaration's own marker is visible to the
 *   caller that scans the returned span.
 *
 * Every walk is disjoint from the next, so a prolog scan stays linear in its characters.
 */
internal object PrologLexer {
    const val COMMENT_OPEN = "<!--"
    const val CDATA_OPEN = "<![CDATA["
    const val PI_OPEN = "<?"
    const val DECL_OPEN = "<!"
    private const val COMMENT_CLOSE = "-->"
    private const val CDATA_CLOSE = "]]>"
    private const val PI_CLOSE = "?>"
    private const val XML_DECL_NAME = "xml"

    /** A `<` that opens a tag to relaxed kxml2: anything but `!`, `?`, `/` starts a name. */
    fun isTagOpen(
        text: String,
        i: Int,
    ): Boolean =
        text[i] == '<' && i + 1 < text.length &&
            text[i + 1] != '!' && text[i + 1] != '?' && text[i + 1] != '/'

    /** Past the `<!--` comment at [i] — the next `-->` or the document end. */
    fun commentEnd(
        text: String,
        i: Int,
    ): Int = skipTo(text, COMMENT_CLOSE, i + COMMENT_OPEN.length)

    /** Past the `<![CDATA[` section at [i] — the next `]]>` or the document end. */
    fun cdataEnd(
        text: String,
        i: Int,
    ): Int = skipTo(text, CDATA_CLOSE, i + CDATA_OPEN.length)

    /**
     * `<?`: kxml2 runs `<?xml` followed by whitespace through its attribute loop (the xmldecl
     * path) and treats any other target as a plain processing instruction. The decl form gets
     * the same attribute bound as a start tag — a giant `<?xml a1="v" a2="v" … ?>` floods
     * kxml2's attribute array the same way.
     */
    fun piEnd(
        text: String,
        i: Int,
        maxAttributes: Int,
    ): Int {
        val nameStart = i + PI_OPEN.length
        if (text.regionMatches(nameStart, XML_DECL_NAME, 0, XML_DECL_NAME.length, ignoreCase = true)) {
            val afterName = nameStart + XML_DECL_NAME.length
            // kxml2 reads `x`, `m`, `l` then requires the next char to be `<= ' '` (EOF qualifies).
            if (afterName >= text.length || text[afterName] <= ' ') {
                return tagEnd(text, nameStart, xmlDecl = true, maxAttributes)
            }
        }
        return skipTo(text, PI_CLOSE, nameStart)
    }

    /**
     * Past one `<!…>` markup declaration at [i], exactly as kxml2's `parseDoctype` sees it: a
     * single quote `'` toggles the quoted state (double quotes mean nothing there), an unquoted
     * `<` nests and an unquoted `>` at depth zero ends the declaration. External identifiers and
     * internal subsets are therefore bounded the same way the parser consumes them — a `"`
     * inside a DOCTYPE cannot hide markup, and a `]>`/`>` inside a quoted literal does not end
     * it early. Returns the document end when the declaration never closes.
     */
    fun markupDeclEnd(
        text: String,
        i: Int,
    ): Int {
        var pos = i + DECL_OPEN.length
        // The `<` that opened the declaration counts towards the nesting depth, like kxml2's `1`.
        var depth = 1
        var quoted = false
        while (pos < text.length) {
            when (text[pos]) {
                '\'' -> {
                    quoted = !quoted
                }

                '<' -> {
                    if (!quoted) depth++
                }

                '>' -> {
                    if (!quoted) {
                        depth--
                        if (depth == 0) return pos + 1
                    }
                }
            }
            pos++
        }
        return text.length
    }

    /**
     * End (exclusive) of an end tag as relaxed kxml2's `parseEndTag` reads it: the name, tag
     * whitespace, then exactly one more character, whatever it is (`</x !` ends after `!`).
     * Skipping to the next `>` instead would jump over a start tag kxml2 then processes.
     */
    fun endTagEnd(
        text: String,
        i: Int,
    ): Int {
        var pos = nameEnd(text, i + 2)
        while (pos < text.length && text[pos] <= ' ') pos++
        return minOf(pos + 1, text.length)
    }

    /**
     * End (exclusive) of one start tag or `<?xml` declaration at [start] — positioned at the
     * name, after `<` or `<?`. The walk mirrors kxml2's relaxed `parseStartTag`: names are one
     * unconditional first char plus name chars, whitespace inside a tag is `<= ' '`, an absent
     * `=` costs one attribute, and quoted or unquoted values may contain `&…` references that
     * swallow their terminator — an `&x>` in an unquoted value eats the `>` kxml2 would have
     * closed on. Throws past [maxAttributes]; [xmlDecl] ends on `?` instead of `>`.
     */
    fun tagEnd(
        text: String,
        start: Int,
        xmlDecl: Boolean,
        maxAttributes: Int,
    ): Int {
        var i = nameEnd(text, start)
        var attributes = 0
        while (i < text.length) {
            i = skipTagWhitespace(text, i)
            if (i >= text.length) return text.length
            val c = text[i]
            when {
                xmlDecl && c == '?' -> {
                    // xmldecl ends on `?`; kxml2 then consumes one more char expecting `>`.
                    return minOf(i + 2, text.length)
                }

                !xmlDecl && c == '>' -> {
                    return i + 1
                }

                !xmlDecl && c == '/' -> {
                    // Degenerated tag: `/`, whitespace, then a single char read expecting `>`.
                    i = skipTagWhitespace(text, i + 1)
                    return minOf(i + 1, text.length)
                }

                else -> {
                    // Attribute name, then an optional =value.
                    i = nameEnd(text, i)
                    attributes++
                    if (attributes > maxAttributes) {
                        throw XmlPullParserException("start tag over $maxAttributes attributes")
                    }
                    i = skipTagWhitespace(text, i)
                    if (i < text.length && text[i] == '=') {
                        i = skipTagWhitespace(text, i + 1)
                        if (i < text.length) {
                            val q = text[i]
                            if (q == '"' || q == '\'') {
                                i = valueEnd(text, i + 1, quote = q)
                            } else {
                                // Unquoted value: kxml2's delimiter is `' '` — whitespace or `>` ends it.
                                i = valueEnd(text, i, quote = ' ')
                            }
                        }
                    }
                }
            }
        }
        return text.length
    }

    private fun skipTo(
        text: String,
        marker: String,
        from: Int,
    ): Int {
        val close = text.indexOf(marker, from)
        return if (close < 0) text.length else close + marker.length
    }

    /**
     * kxml2's `readName`: the first char is consumed unconditionally in relaxed mode, then chars
     * from its name set — letters, digits, `_`, `-`, `:`, `.`, and anything `>= 0xB7`.
     */
    private fun nameEnd(
        text: String,
        start: Int,
    ): Int {
        var i = start
        if (i < text.length) i++
        while (i < text.length && isNameChar(text[i])) i++
        return i
    }

    /** Tag-context whitespace is every char `<= ' '` — kxml2's `skip()` rule, not Unicode's. */
    private fun skipTagWhitespace(
        text: String,
        start: Int,
    ): Int {
        var i = start
        while (i < text.length && text[i] <= ' ') i++
        return i
    }

    /**
     * Past an attribute value: a quoted one ends on its quote, an unquoted one on whitespace or
     * `>`. Inside either, `&` starts an entity reference whose terminator — even a quote or `>`
     * — is consumed by the reference scan, exactly as `pushEntity` swallows it into the value.
     */
    private fun valueEnd(
        text: String,
        start: Int,
        quote: Char,
    ): Int {
        var i = start
        while (i < text.length) {
            val c = text[i]
            when {
                c == '&' -> i = SupplementaryRefs.referenceEnd(text, i)
                quote == ' ' && (c <= ' ' || c == '>') -> return i
                c == quote -> return i + 1
                else -> i++
            }
        }
        return i
    }

    private fun isNameChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' ||
            c == '_' || c == '-' || c == ':' || c == '.' || c.code >= 0xB7
}
