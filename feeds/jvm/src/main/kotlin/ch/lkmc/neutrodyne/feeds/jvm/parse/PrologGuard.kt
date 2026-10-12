// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.parse

/**
 * The prolog guard (03 Parser setup and charset step 1): the whole document, decoded with the
 * detected or override encoding, is walked through the real prolog constructs — whitespace,
 * comments, processing instructions and markup declarations — until the root element. `<!ENTITY`
 * inside a declaration means the document is hostile. The scan covers the entire prolog;
 * `prologScanBytes` bounds only the declaration sniff/decode step, never this scan.
 * `FEATURE_PROCESS_DOCDECL` stays off and no external DTD is ever fetched (N9).
 *
 * Every boundary comes from [PrologLexer], the tokenizer [TagBounds] walks, so a `<?xml`
 * declaration ends where the parser ends it (`?` plus one char through the attribute lexer, not a
 * literal `?>`), a `<![CDATA[` payload is text and not a declaration, and a DOCTYPE's internal
 * subset is covered by one flat span check. The entity marker is checked over the declaration
 * span the lexer returns — including its own `<!ENTITY` opener — and conservatively also inside
 * quoted literals and DTD-internal comments, where a real entity cannot hide anyway. The prolog
 * ends at the first `</`-`-`/`!`-`?`-less `<x`: relaxed kxml2 accepts any name-start character
 * there (digits included). A DOCTYPE's internal subset is not re-walked construct by construct —
 * it is covered by the declaration-span marker check, which is the stronger position anyway.
 */
internal object PrologGuard {
    private const val ENTITY_MARKER = "<!entity"

    /** Whether the prolog of [text] declares an entity, which would enable entity expansion. */
    fun isHostileText(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            when {
                // kxml2's skip() treats every char `<= ' '` as tag whitespace, not Unicode's rule.
                text[i] <= ' ' -> {
                    i++
                }

                text[i] == '&' -> {
                    // pushEntity eats one terminator: `&<` swallows the construct opener after it.
                    i = PrologLexer.entityRefEnd(text, i)
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

                text.startsWith(PrologLexer.DECL_OPEN, i) -> {
                    val end = PrologLexer.markupDeclEnd(text, i)
                    if (declaresEntity(text, i, end)) return true
                    i = end
                }

                // The root element: relaxed kxml2 starts a tag on any char that is not `!`, `?` or
                // `/`, so the prolog ends there whatever the name looks like.
                PrologLexer.isTagOpen(text, i) -> {
                    return false
                }

                text[i] == '<' && i + 1 < text.length && text[i + 1] == '/' -> {
                    i = PrologLexer.endTagEnd(text, i)
                }

                else -> {
                    i++
                }
            }
        }
        return false
    }

    /**
     * Whether the declaration span [from, to) — the opener `<!` through the character past its
     * closing `>` — contains `<!ENTITY`, case-insensitive. The check covers the whole span the
     * lexer attributed to the declaration, so a nested declaration inside a DOCTYPE subset and
     * the declaration's own marker are both seen.
     */
    private fun declaresEntity(
        text: String,
        from: Int,
        to: Int,
    ): Boolean {
        var i = from
        while (i <= to - ENTITY_MARKER.length) {
            if (text.startsWith(ENTITY_MARKER, i, ignoreCase = true)) return true
            i++
        }
        return false
    }
}
