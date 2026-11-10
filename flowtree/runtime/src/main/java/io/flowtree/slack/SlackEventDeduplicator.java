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

package io.flowtree.slack;

import java.util.HashMap;
import java.util.Map;

/**
 * Guards against acting more than once on a Slack delivery that Slack may
 * deliver more than once.
 *
 * <p>Slack's Events API redelivers an event when the receiver does not
 * acknowledge it within a few seconds, retrying up to three times and marking
 * each redelivery with {@code X-Slack-Retry-Num} / {@code X-Slack-Retry-Reason};
 * Socket Mode likewise redelivers an envelope that was never acknowledged. Every
 * redelivery carries the same stable identifier for the underlying message.
 * Without a guard, one message that is redelivered N times submits N jobs — the
 * incident this class prevents.</p>
 *
 * <p>A caller {@link #claim(String) claims} a delivery's identifier before it
 * acts. The first claim of an identifier succeeds; a concurrent or later claim
 * of the same identifier fails until the original is {@link #release(String)
 * released} or ages out of the retry window. A caller whose work did not
 * produce anything (for example because no agent was connected) releases the
 * claim so a genuine redelivery can take it; a caller whose work succeeded
 * leaves it claimed, and it is pruned once it is older than any redelivery
 * Slack would still send.</p>
 *
 * <p>Deduplication is keyed on the delivery identifier, never on message text,
 * so a person who sends the same words twice — two deliveries with two distinct
 * identifiers — is served twice. The design mirrors the retry window and atomic
 * check-and-record of {@link io.flowtree.workstream.WorkstreamMailbox}.</p>
 *
 * @author Michael Murray
 * @see SlackListener
 * @see io.flowtree.workstream.WorkstreamMailbox
 */
public class SlackEventDeduplicator {

    /**
     * Default retention for a claimed identifier. Slack stops redelivering an
     * event within a few minutes; fifteen minutes covers that window with room
     * to spare, matching
     * {@link io.flowtree.workstream.WorkstreamMailbox#DEDUPE_WINDOW_MILLIS}.
     */
    public static final long DEFAULT_WINDOW_MILLIS = 15L * 60 * 1000;

    /** How long a claimed identifier is retained before it is pruned. */
    private final long windowMillis;

    /** Claimed identifiers mapped to the wall-clock time they were claimed. Guarded by {@code this}. */
    private final Map<String, Long> claims = new HashMap<>();

    /** Creates a deduplicator that retains claims for {@link #DEFAULT_WINDOW_MILLIS}. */
    public SlackEventDeduplicator() {
        this(DEFAULT_WINDOW_MILLIS);
    }

    /**
     * Creates a deduplicator with an explicit retention window.
     *
     * @param windowMillis how long a claimed identifier is retained before it
     *                     is pruned; must comfortably exceed Slack's redelivery
     *                     window
     */
    public SlackEventDeduplicator(long windowMillis) {
        this.windowMillis = windowMillis;
    }

    /**
     * Claims a delivery identifier for its first delivery.
     *
     * <p>The check and the record happen under one lock, so two concurrent
     * redeliveries of the same identifier cannot both observe it as unclaimed.</p>
     *
     * @param id the stable Slack identifier of the delivery; {@code null} or
     *           empty is never a redelivery and is always claimed
     * @return {@code true} when this is the first live claim of {@code id} and
     *         the caller should act on the delivery; {@code false} when
     *         {@code id} is already claimed and the caller should ignore this
     *         redelivery
     */
    public synchronized boolean claim(String id) {
        if (id == null || id.isEmpty()) return true;
        pruneExpired();
        if (claims.containsKey(id)) return false;
        claims.put(id, System.currentTimeMillis());
        return true;
    }

    /**
     * Releases a claim so a later redelivery of the same identifier may be
     * acted on. A caller whose work failed calls this so the message is not
     * lost; a caller whose work succeeded does not, so the redelivery is
     * ignored.
     *
     * @param id the identifier to release; {@code null} or empty is ignored
     */
    public synchronized void release(String id) {
        if (id != null && !id.isEmpty()) claims.remove(id);
    }

    /**
     * Returns the number of live claims after pruning expired ones; exposed for
     * diagnostics and tests.
     *
     * @return the number of identifiers currently claimed
     */
    public synchronized int size() {
        pruneExpired();
        return claims.size();
    }

    /** Drops claims that have outlived the retention window. */
    private synchronized void pruneExpired() {
        long oldest = System.currentTimeMillis() - windowMillis;
        claims.values().removeIf(claimedAt -> claimedAt < oldest);
    }
}
