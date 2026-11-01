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
 * The record of which phone numbers have opted out of messages.
 *
 * <p>Opt-out is a carrier compliance obligation, not a preference: a number
 * that has texted STOP must never be sent another message until it texts START.
 * The obligation is keyed by phone number because that is the identity both
 * sides of the conversation share — the inbound STOP arrives from a number, and
 * the outbound send is addressed to one — so the sender and the inbound keyword
 * handler consult the same registry rather than tracking consent separately.</p>
 *
 * <p>Numbers are compared as given; callers are expected to use a single
 * canonical form (E.164, e.g. {@code "+18335551234"}) on both the inbound and
 * outbound sides so the two agree.</p>
 *
 * @author Michael Murray
 * @see SignalWireDeliveryProvider
 */
public interface SmsOptOutRegistry {

    /**
     * Returns whether the given number has opted out and must not be sent to.
     *
     * @param number the destination number in E.164 form
     * @return {@code true} when the number has opted out
     */
    boolean isOptedOut(String number);

    /**
     * Records that the given number has opted out. Idempotent: opting out a
     * number already opted out changes nothing.
     *
     * @param number the number to mark opted out, in E.164 form
     */
    void optOut(String number);

    /**
     * Clears any opt-out for the given number, so it may be sent to again.
     * Idempotent: opting in a number that was not opted out changes nothing.
     *
     * @param number the number to resubscribe, in E.164 form
     */
    void optIn(String number);
}
