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

import java.util.Locale;

/**
 * The toll-free SMS messaging program registered with the carriers, expressed
 * as the message contract that outbound and inbound traffic must honour.
 *
 * <p>Carriers approve a toll-free program against sample messages and then
 * compare live traffic to them; traffic that does not match the registered
 * shape is filtered. This type is the single definition of that shape, so both
 * the outbound sender and the inbound keyword responder draw the wording from
 * one place and cannot drift apart.</p>
 *
 * <h2>Outbound</h2>
 * <p>Every outbound body is the registered brand prefix, the alert text, and
 * the fixed opt-out instruction, in that order:</p>
 * <pre>
 * Almost Realism Media: &lt;alert text&gt; Reply STOP to unsubscribe, HELP for assistance.
 * </pre>
 * <p>{@link #formatAlert(String)} builds exactly that. It first makes the alert
 * text {@linkplain Gsm7#normalize(String) GSM-7 safe}, then, if the complete
 * message would exceed {@value #MAX_SEGMENT_SEPTETS} septets, truncates the
 * alert text (and only the alert text) so the brand prefix and the opt-out
 * suffix always survive intact.</p>
 *
 * <h2>Inbound</h2>
 * <p>{@link #classifyInbound(String)} maps an inbound body to the consent
 * action it requests, and {@link #replyFor(Keyword)} gives the registered reply
 * for that action. The keyword sets and the reply wording are the ones the
 * program was registered with and must not be edited casually.</p>
 *
 * @author Michael Murray
 * @see Gsm7
 * @see SignalWireDeliveryProvider
 */
public final class SmsComplianceProgram {

    /** The public brand name the program is registered under. */
    public static final String BRAND = "Almost Realism Media";

    /** Prepended to every outbound body; identifies the sender to the recipient. */
    public static final String PREFIX = BRAND + ": ";

    /** Appended to every outbound body; the opt-out instruction carriers require. */
    public static final String OPT_OUT_SUFFIX =
            " Reply STOP to unsubscribe, HELP for assistance.";

    /**
     * Septets available in a single GSM-7 segment. A message kept at or below
     * this length is one segment; beyond it the carrier bills additional
     * segments, which {@link #formatAlert(String)} avoids by truncation.
     */
    public static final int MAX_SEGMENT_SEPTETS = 160;

    /** The ellipsis that marks where an over-long alert text was cut. */
    private static final String ELLIPSIS = "...";

    /** The registered reply confirming an opt-out and how to reverse it. */
    public static final String STOP_REPLY = BRAND
            + ": You have been unsubscribed and will receive no further messages."
            + " Reply START to resubscribe.";

    /** The registered reply confirming a resubscribe. */
    public static final String START_REPLY = BRAND
            + ": You are resubscribed to work updates. Msg frequency varies."
            + " Msg&data rates may apply. Reply HELP for help, STOP to unsubscribe.";

    /** The registered reply describing the program and where to get help. */
    public static final String HELP_REPLY = BRAND
            + ": Automated work updates for developers."
            + " Help: https://www.almostrealism.com/privacy."
            + " Msg&data rates may apply. Reply STOP to unsubscribe.";

    /** Not instantiable; the program is expressed through static members. */
    private SmsComplianceProgram() { }

    /**
     * The consent action an inbound message requests.
     */
    public enum Keyword {
        /** The sender asked to stop receiving messages. */
        STOP,
        /** The sender asked to resume receiving messages. */
        START,
        /** The sender asked what the program is. */
        HELP,
        /** The message matched no registered keyword; no reply is owed. */
        NONE
    }

    /**
     * Builds the registered outbound body for an alert.
     *
     * <p>The alert text is made GSM-7 safe and, if the whole message would run
     * past {@link #MAX_SEGMENT_SEPTETS}, truncated with a trailing ellipsis so
     * that the brand prefix and opt-out suffix are never shortened. The result
     * is therefore always a single GSM-7 segment that carries the fixed framing
     * the carrier registered.</p>
     *
     * @param alertText the alert body supplied by the caller; may be
     *                  {@code null}
     * @return the complete, compliant outbound message body
     */
    public static String formatAlert(String alertText) {
        String text = Gsm7.normalize(alertText == null ? "" : alertText.trim());

        int fixed = Gsm7.septetLength(PREFIX) + Gsm7.septetLength(OPT_OUT_SUFFIX);
        int budget = MAX_SEGMENT_SEPTETS - fixed;

        if (Gsm7.septetLength(text) > budget) {
            text = truncate(text, budget);
        }

        return PREFIX + text + OPT_OUT_SUFFIX;
    }

    /**
     * Truncates already-normalized text to at most {@code maxSeptets} septets,
     * ending it with an ellipsis that is itself counted against the budget.
     *
     * @param text       the GSM-7-safe text to shorten
     * @param maxSeptets the septet ceiling the result must respect
     * @return the shortened text, ending in an ellipsis
     */
    private static String truncate(String text, int maxSeptets) {
        if (maxSeptets <= ELLIPSIS.length()) {
            return ELLIPSIS.substring(0, Math.max(0, maxSeptets));
        }

        int budget = maxSeptets - ELLIPSIS.length();
        StringBuilder kept = new StringBuilder();
        int used = 0;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int cost = Gsm7.septetLength(String.valueOf(c));
            if (used + cost > budget) break;
            kept.append(c);
            used += cost;
        }

        return kept.toString().stripTrailing() + ELLIPSIS;
    }

    /**
     * Maps an inbound message body to the consent action it requests.
     *
     * <p>The whole trimmed body is matched case-insensitively against the
     * registered keyword sets; a body that is not exactly one of them requests
     * nothing and yields {@link Keyword#NONE}.</p>
     *
     * @param body the inbound message body; may be {@code null}
     * @return the requested action, or {@link Keyword#NONE}
     */
    public static Keyword classifyInbound(String body) {
        if (body == null) return Keyword.NONE;

        switch (body.trim().toUpperCase(Locale.ROOT)) {
            case "STOP":
            case "STOPALL":
            case "UNSUBSCRIBE":
            case "CANCEL":
            case "END":
            case "QUIT":
                return Keyword.STOP;
            case "START":
            case "UNSTOP":
            case "YES":
                return Keyword.START;
            case "HELP":
            case "INFO":
                return Keyword.HELP;
            default:
                return Keyword.NONE;
        }
    }

    /**
     * Returns the registered reply for a consent action.
     *
     * @param keyword the classified action
     * @return the reply text, or {@code null} when no reply is owed
     *         ({@link Keyword#NONE})
     */
    public static String replyFor(Keyword keyword) {
        switch (keyword) {
            case STOP:
                return STOP_REPLY;
            case START:
                return START_REPLY;
            case HELP:
                return HELP_REPLY;
            default:
                return null;
        }
    }
}
