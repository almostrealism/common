/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.util;

/**
 * The GSM-7 (GSM 03.38) text encoding, as it bears on SMS cost and framing.
 *
 * <p>An SMS whose characters all fall inside GSM-7 is sent seven bits to a
 * character and fits 160 characters in a single segment. A single character
 * outside GSM-7 - an em-dash, a curly quote, an emoji - forces the whole
 * message to UCS-2, which halves the per-segment capacity to 70 characters and
 * so doubles the segment count and the cost. The switch is silent: nothing in
 * the message text signals it, and a body that looks short can quietly become
 * two paid segments because of one stray glyph pasted from a word processor.</p>
 *
 * <p>{@link #normalize(String)} removes that hazard by folding the common
 * offenders to their ASCII equivalents and dropping anything still outside the
 * set, so the result is guaranteed single-encoding. {@link #septetLength(String)}
 * reports the length in GSM-7 septets, counting the handful of characters that
 * live in the extension table (such as {@code [ ] { } \ ^ ~ |} and the euro
 * sign) as the two septets they actually occupy, which is what a 160-septet
 * budget has to be measured against.</p>
 *
 * @author Michael Murray
 */
public final class Gsm7 {

    /**
     * Characters that GSM-7 can represent only through its extension table, at
     * a cost of two septets each. Every other character this class admits costs
     * one.
     */
    private static final String EXTENDED = "[]{}\\^~|€";

    /** Not instantiable; the encoding is expressed through static methods. */
    private Gsm7() { }

    /**
     * Rewrites text so that every character is representable in GSM-7, leaving
     * the message in a single, predictable encoding.
     *
     * <p>Common typographic punctuation is folded to its ASCII equivalent so
     * meaning survives: em and en dashes become {@code "-"}, curly quotes
     * become straight quotes, an ellipsis becomes {@code "..."}, and the
     * non-breaking space becomes an ordinary space. Any character still outside
     * the GSM-7 set after that folding is dropped, because keeping it would
     * force the entire message to UCS-2 - a worse outcome than losing one
     * glyph.</p>
     *
     * @param text the raw text, which may contain arbitrary Unicode; may be
     *             {@code null}
     * @return the GSM-7-safe text, or an empty string when {@code text} is
     *         {@code null}
     */
    public static String normalize(String text) {
        if (text == null) return "";

        String folded = text
                .replace('—', '-').replace('–', '-')
                .replace('‒', '-').replace('―', '-').replace('−', '-')
                .replace('‘', '\'').replace('’', '\'')
                .replace('‚', '\'').replace('′', '\'').replace('`', '\'')
                .replace('“', '"').replace('”', '"')
                .replace('„', '"').replace('″', '"')
                .replace(' ', ' ')
                .replace("…", "...");

        StringBuilder result = new StringBuilder(folded.length());
        for (int i = 0; i < folded.length(); i++) {
            char c = folded.charAt(i);
            if (isRepresentable(c)) result.append(c);
        }

        return result.toString();
    }

    /**
     * Returns the length of {@code text} in GSM-7 septets, the unit a 160- or
     * 70-character segment budget is actually denominated in.
     *
     * <p>Characters in the GSM-7 extension table occupy two septets; every
     * other representable character occupies one. A character that
     * {@link #normalize(String)} would drop contributes nothing, so this is
     * most meaningful when applied to already-normalized text.</p>
     *
     * @param text the text to measure; may be {@code null}
     * @return the septet count, zero for {@code null} or empty text
     */
    public static int septetLength(String text) {
        if (text == null) return 0;

        int septets = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!isRepresentable(c)) continue;
            septets += EXTENDED.indexOf(c) >= 0 ? 2 : 1;
        }

        return septets;
    }

    /**
     * Returns whether a character is representable in GSM-7, whether directly
     * or through the extension table.
     *
     * <p>Printable ASCII is representable with the single exception of the
     * backtick, which GSM-7 lacks; {@link #normalize(String)} folds the
     * backtick to an apostrophe before this is ever consulted. Newline and
     * carriage return are admitted so multi-line bodies survive; no other
     * control character is.</p>
     *
     * @param c the character to test
     * @return {@code true} when GSM-7 can carry the character
     */
    public static boolean isRepresentable(char c) {
        if (c == '\n' || c == '\r') return true;
        return c >= 0x20 && c <= 0x7E && c != '`';
    }
}
