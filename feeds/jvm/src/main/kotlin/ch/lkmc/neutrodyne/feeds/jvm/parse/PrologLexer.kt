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
 * Boundaries model kxml2 2.3.0's `parseLegacy` one-to-one:
 * - After `<!`, one peeked char picks the kind: `-` is a comment, `[` is CDATA, anything else a
 *   markup declaration. Each then consumes its fixed required literal *blindly*, character by
 *   character — `--`, `[CDATA[` or `DOCTYPE` — so a `>`, quote or opener inside that region is
 *   swallowed and cannot end or redirect the construct. Only then does the payload scan run.
 * - A comment ends at the next `-->` past the required region, a CDATA section at the next
 *   `]]>` — payloads, never markup, wherever they appear.
 * - `<?xml` followed by whitespace runs through kxml2's attribute lexer and ends on `?` plus
 *   the next consumed char. Any other `<?` ends at the next `?>` that begins strictly after
 *   the first payload char: the lexer reads a char before it may look for the terminator, so
 *   a `?>` at the scan position is already consumed and can never close (`<??>` runs to EOF).
 * - A markup declaration is cut like `parseDoctype`: only `'` toggles the quoted state (a `"`
 *   inside a DOCTYPE hides nothing), an unquoted `<` nests, an unquoted `>` at depth zero ends
 *   the declaration — internal subsets are scanned, not swallowed as one literal.
 * - `</` reads a name, tag whitespace, then exactly one more character.
 * - `&` starts an entity reference read like `pushEntity`: name chars, then one terminator
 *   consumed whatever it is — `&<` or `&x<` eat the `<`, so the construct it opened is text.
 * - A `<` before anything other than `!`, `?` or `/` opens a start tag whose first name char is
 *   unrestricted (`<0 …>` is a tag to kxml2), and a declaration's own marker is visible to the
 *   caller that scans the returned span.
 *
 * Every walk is disjoint from the next, so a prolog scan stays linear in its characters.
 */
internal object PrologLexer {
    const val PI_OPEN = "<?"
    const val DECL_OPEN = "<!"
    private const val COMMENT_CLOSE = "-->"
    private const val CDATA_CLOSE = "]]>"
    private const val PI_CLOSE = "?>"
    private const val XML_DECL_NAME = "xml"

    /** `parseLegacy`'s required literal for a comment — consumed blindly after `<!`. */
    private const val COMMENT_REQUIRED = "--"

    /** `parseLegacy`'s required literal for CDATA — consumed blindly after `<!`. */
    private const val CDATA_REQUIRED = "[CDATA["

    /** `parseLegacy`'s required literal for any other markup declaration — consumed blindly. */
    private const val DOCTYPE_REQUIRED = "DOCTYPE"

    /** Chars the `<?xml` probe consumes speculatively before the `l` check can fail. */
    private const val XML_PROBE_CHARS = 2

    /** A `<` that opens a tag to relaxed kxml2: anything but `!`, `?`, `/` starts a name. */
    fun isTagOpen(
        text: String,
        i: Int,
    ): Boolean =
        text[i] == '<' && i + 1 < text.length &&
            text[i + 1] != '!' && text[i + 1] != '?' && text[i + 1] != '/'

    /**
     * Whether the `<!` at [i] dispatches to kxml2's comment path: one peeked `-`. The required
     * `--` is consumed before the payload scan even when it does not match, so `<!-'-->` ends at
     * the `-->` that follows — the same boundary the parser materialises.
     */
    fun isCommentStart(
        text: String,
        i: Int,
    ): Boolean = text.startsWith(DECL_OPEN, i) && i + 2 < text.length && text[i + 2] == '-'

    /** Whether the `<!` at [i] dispatches to kxml2's CDATA path: one peeked `[`. */
    fun isCdataStart(
        text: String,
        i: Int,
    ): Boolean = text.startsWith(DECL_OPEN, i) && i + 2 < text.length && text[i + 2] == '['

    /** Past the comment at [i] — the first `-->` after the required `--`, or the document end. */
    fun commentEnd(
        text: String,
        i: Int,
    ): Int = skipTo(text, COMMENT_CLOSE, i + DECL_OPEN.length + COMMENT_REQUIRED.length)

    /**
     * Past the CDATA section at [i] — the first `]]>` after the required `[CDATA[` region (also
     * consumed blindly, so `<![x'v']]>` runs to EOF), or the document end.
     */
    fun cdataEnd(
        text: String,
        i: Int,
    ): Int = skipTo(text, CDATA_CLOSE, i + DECL_OPEN.length + CDATA_REQUIRED.length)

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
        if (isXmlDeclAt(text, i)) {
            return tagEnd(text, i + PI_OPEN.length, xmlDecl = true, maxAttributes)
        }
        // The terminator loop reads a payload char before it may match `?>`: the pair must begin
        // after the first char, or it is part of what was already consumed. And a `<?xm` probe
        // ate two chars before the decl check failed, so that PI's scan starts two chars later —
        // `<?xm?>` is therefore an unterminated PI, not a PI closed at its `?>`.
        val payloadStart = i + PI_OPEN.length + if (isXmlProbeConsumed(text, i)) XML_PROBE_CHARS else 0
        return skipTo(text, PI_CLOSE, payloadStart + 1)
    }

    /**
     * Whether `parseLegacy`'s speculative `x`,`m` read fired after `<?`: it consumes both chars
     * before the `l` check can fail, so a non-decl `<?xm` PI's payload scan begins after them.
     */
    private fun isXmlProbeConsumed(
        text: String,
        i: Int,
    ): Boolean {
        val nameStart = i + PI_OPEN.length
        return nameStart + 1 < text.length &&
            (text[nameStart] == 'x' || text[nameStart] == 'X') &&
            (text[nameStart + 1] == 'm' || text[nameStart + 1] == 'M')
    }

    /** `<?xml` followed by whitespace (or EOF) takes kxml2's xmldecl attribute loop, not the PI one. */
    fun isXmlDeclAt(
        text: String,
        i: Int,
    ): Boolean {
        val nameStart = i + PI_OPEN.length
        if (!text.regionMatches(nameStart, XML_DECL_NAME, 0, XML_DECL_NAME.length, ignoreCase = true)) {
            return false
        }
        val afterName = nameStart + XML_DECL_NAME.length
        // kxml2 reads `x`, `m`, `l` then requires the next char to be `<= ' '` (EOF qualifies).
        return afterName >= text.length || text[afterName] <= ' '
    }

    /**
     * Past one `<!…>` markup declaration at [i], exactly as `parseLegacy` + `parseDoctype` see
     * it: the seven required `DOCTYPE` chars are consumed first — blindly, so a `>`, quote or
     * nested `<` inside them cannot end, nest or redirect the declaration — then a single quote
     * `'` toggles the quoted state (double quotes mean nothing there), an unquoted `<` nests and
     * an unquoted `>` at depth zero ends the declaration. External identifiers and internal
     * subsets are therefore bounded the same way the parser consumes them — a `"` inside a
     * DOCTYPE cannot hide markup, and a `]>`/`>` inside a quoted literal does not end it early.
     * Returns the document end when the declaration never closes.
     */
    fun markupDeclEnd(
        text: String,
        i: Int,
    ): Int {
        var pos = i + DECL_OPEN.length + DOCTYPE_REQUIRED.length
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

    /**
     * The index just past an `&…` reference at [amp] as relaxed `pushEntity` scans it: the name
     * chars `[0-9a-zA-Z_#-]` and anything `>= 0x80`, then one terminator char — `;` for a
     * well-formed reference, or whatever char ended the name otherwise, consumed into the value
     * either way. `&<` and `&x<` therefore eat the `<` that would have opened the next construct.
     */
    fun entityRefEnd(
        text: String,
        amp: Int,
    ): Int {
        var i = amp + 1
        while (i < text.length && isRefChar(text[i])) i++
        return if (i < text.length) i + 1 else i
    }

    /**
     * kxml2's `readName`: the first char is consumed unconditionally in relaxed mode, then chars
     * from its name set — letters, digits, `_`, `-`, `:`, `.`, and anything `>= 0xB7`.
     */
    fun nameEnd(
        text: String,
        start: Int,
    ): Int {
        var i = start
        if (i < text.length) i++
        while (i < text.length && isNameChar(text[i])) i++
        return i
    }

    /** Tag-context whitespace is every char `<= ' '` — kxml2's `skip()` rule, not Unicode's. */
    fun skipTagWhitespace(
        text: String,
        start: Int,
    ): Int {
        var i = start
        while (i < text.length && text[i] <= ' ') i++
        return i
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
                c == '&' -> i = entityRefEnd(text, i)
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

    /** The char set `pushEntity` collects before its terminator: letters, digits, `_`, `#`, `-`, `>= 0x80`. */
    private fun isRefChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '#' || c == '-' || c.code >= 0x80
}
