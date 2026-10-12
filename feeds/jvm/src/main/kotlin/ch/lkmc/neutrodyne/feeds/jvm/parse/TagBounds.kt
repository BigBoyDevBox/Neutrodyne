// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.parse

import ch.lkmc.neutrodyne.feeds.parse.ParseLimits
import org.xmlpull.v1.XmlPullParserException

/**
 * A single encoding-aware pass over the raw document that bounds start-tag work *before* the pull
 * parser sees any tag (03 Limits and version policy). kxml2 grows its attribute and namespace arrays
 * quadratically while processing a start tag, so checking `attributeCount` after `next()` returns is
 * already too late: a million `xmlns:pN` attributes would copy trillions of entries. Over the limit
 * the document is reported as `MALFORMED`.
 *
 * The scan must cut markup exactly where relaxed kxml2 does — a construct this guard over-reads hides
 * real start tags from the bound, while one it under-reads only costs false positives. Every boundary
 * therefore comes from [PrologLexer], the same tokenizer [PrologGuard] walks: the two guards cannot
 * disagree about where a comment, CDATA section, PI, XML declaration, markup declaration or tag ends.
 */
internal object TagBounds {
    /**
     * Throws [XmlPullParserException] when any start tag carries more attributes than the limit.
     * [text] is the document decoded exactly as the pass' pull parser will read it — the sniffed
     * view for the first pass, the override charset for the re-parse.
     */
    fun checkText(
        text: String,
        limits: ParseLimits,
    ) {
        var i = 0
        while (i < text.length) {
            when {
                text[i] == '&' -> {
                    // pushEntity eats one terminator: `&x<` swallows the would-be tag opener.
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
                    i = PrologLexer.piEnd(text, i, limits.maxTagAttributes)
                }

                i + 1 < text.length && text[i + 1] == '/' -> {
                    i = PrologLexer.endTagEnd(text, i)
                }

                text.startsWith(PrologLexer.DECL_OPEN, i) -> {
                    i = PrologLexer.markupDeclEnd(text, i)
                }

                // Relaxed kxml2 pushes any first name char: `<0 …>` is a start tag to it.
                else -> {
                    i = PrologLexer.tagEnd(text, i + 1, xmlDecl = false, limits.maxTagAttributes)
                }
            }
        }
    }
}
