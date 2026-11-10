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

import io.flowtree.workstream.Workstream;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests that one Slack message produces exactly one job no matter how many
 * times Slack delivers it, while two genuinely distinct messages — even with
 * identical text — each produce a job.
 *
 * <p>Slack redelivers an event whose acknowledgement it did not receive within
 * its deadline (Events API retries carry {@code X-Slack-Retry-Num}; Socket Mode
 * redelivers an un-acknowledged envelope). Every redelivery of a message
 * carries the same message timestamp, so {@link SlackListener} deduplicates on
 * that stable identifier via {@link SlackEventDeduplicator}. The retry markers
 * ride alongside the event and do not change the timestamp, so a replay with or
 * without them is recognised as the same message.</p>
 *
 * @see SlackListener
 * @see SlackEventDeduplicator
 */
public class SlackDuplicateSubmissionTest extends TestSuiteBase {

    /**
     * A {@link SlackListener} that records how many times a submission actually
     * reached {@link #submitJob}, standing in for the FlowTree server so the
     * test observes submissions without a live agent network.
     */
    private static final class CountingListener extends SlackListener {
        /** Number of times a submission reached {@link #submitJob}. */
        private final AtomicInteger submissions = new AtomicInteger();

        /**
         * Creates a counting listener.
         *
         * @param notifier the notifier passed to the superclass
         */
        CountingListener(SlackNotifier notifier) {
            super(notifier);
        }

        @Override
        protected boolean submitJob(Workstream workstream, String prompt,
                                    String messageTs, String threadTs) {
            submissions.incrementAndGet();
            return true;
        }
    }

    /**
     * Builds a counting listener with one registered workstream bound to the
     * given channel, so {@link SlackListener#handleMessage} routes to it.
     *
     * @param channelId the channel to register a workstream for
     * @return the ready-to-use counting listener
     */
    private static CountingListener listenerWithChannel(String channelId) {
        CountingListener listener = new CountingListener(new SlackNotifier(null));
        Workstream ws = new Workstream(channelId, "#test");
        ws.setDefaultBranch("main");
        listener.registerWorkstream(ws);
        return listener;
    }

    /**
     * The original delivery plus three retries of the same message — the shape
     * of the 2026-11-09 incident — submits exactly one job. Delivery 0 is the
     * original; deliveries 1..3 stand in for Slack's redeliveries, which in
     * production carry {@code X-Slack-Retry-Num}. The listener keys on the
     * message timestamp, identical across all four, so only the first submits.
     */
    @Test(timeout = 10000)
    public void redeliveredMessageSubmitsExactlyOneJob() {
        CountingListener listener = listenerWithChannel("C_DUP");

        String messageTs = "1731000000.000100";
        String text = "<@UBOT> please resolve issues highlighted by PR comments";

        boolean first = listener.handleMessage("C_DUP", "U1", text, messageTs, null);
        boolean secondRetry = listener.handleMessage("C_DUP", "U1", text, messageTs, null);
        boolean thirdRetry = listener.handleMessage("C_DUP", "U1", text, messageTs, null);
        boolean fourthRetry = listener.handleMessage("C_DUP", "U1", text, messageTs, null);

        assertTrue("First delivery must submit the job", first);
        assertFalse("A redelivery must not submit again", secondRetry);
        assertFalse("A redelivery must not submit again", thirdRetry);
        assertFalse("A redelivery must not submit again", fourthRetry);
        assertEquals("One message, however many deliveries, must submit exactly one job",
                1, listener.submissions.get());
    }

    /**
     * Two distinct messages that happen to carry identical text — distinct
     * timestamps — each submit a job. De-duplication keys on the message
     * identity, never on text, so a person may legitimately repeat themselves.
     */
    @Test(timeout = 10000)
    public void distinctMessagesWithIdenticalTextSubmitTwoJobs() {
        CountingListener listener = listenerWithChannel("C_SAME_TEXT");

        String text = "<@UBOT> run the benchmarks";

        boolean firstMessage = listener.handleMessage(
                "C_SAME_TEXT", "U1", text, "1731000100.000200", null);
        boolean secondMessage = listener.handleMessage(
                "C_SAME_TEXT", "U1", text, "1731000200.000300", null);

        assertTrue("First message must submit a job", firstMessage);
        assertTrue("A second, distinct message with identical text must also submit", secondMessage);
        assertEquals("Two distinct messages must submit two jobs",
                2, listener.submissions.get());
    }

    /**
     * A redelivery of a message whose original submission produced no job is
     * allowed to try again, because nothing was actually submitted the first
     * time. The listener's first submission fails (standing in for no agents
     * connected) and its second succeeds.
     */
    @Test(timeout = 10000)
    public void failedSubmissionIsRetriedOnRedelivery() {
        AtomicInteger attempts = new AtomicInteger();
        SlackListener listener = new SlackListener(new SlackNotifier(null)) {
            @Override
            protected boolean submitJob(Workstream workstream, String prompt,
                                        String messageTs, String threadTs) {
                return attempts.incrementAndGet() > 1;
            }
        };
        Workstream ws = new Workstream("C_RETRY", "#test");
        ws.setDefaultBranch("main");
        listener.registerWorkstream(ws);

        String messageTs = "1731000300.000400";
        String text = "<@UBOT> do the thing";

        boolean first = listener.handleMessage("C_RETRY", "U1", text, messageTs, null);
        boolean second = listener.handleMessage("C_RETRY", "U1", text, messageTs, null);

        assertFalse("First delivery produced no job", first);
        assertTrue("Redelivery must be allowed to submit when the first produced nothing", second);
        assertEquals("Both deliveries should have attempted submission", 2, attempts.get());
    }

    /** The deduplicator claims an identifier once and rejects redeliveries of it. */
    @Test(timeout = 10000)
    public void deduplicatorClaimsEachIdentifierOnce() {
        SlackEventDeduplicator dedup = new SlackEventDeduplicator();

        assertTrue("First claim of an id succeeds", dedup.claim("Ev123"));
        assertFalse("Second claim of the same id is rejected", dedup.claim("Ev123"));
        assertTrue("A different id is claimed independently", dedup.claim("Ev456"));
        assertEquals("Both distinct ids are now claimed", 2, dedup.size());
    }

    /** A released identifier may be claimed again; a null id is never a redelivery. */
    @Test(timeout = 10000)
    public void deduplicatorReleaseAndNullHandling() {
        SlackEventDeduplicator dedup = new SlackEventDeduplicator();

        assertTrue(dedup.claim("Ev789"));
        dedup.release("Ev789");
        assertTrue("A released id can be claimed again", dedup.claim("Ev789"));

        assertTrue("A null id is always claimable (never a redelivery)", dedup.claim(null));
        assertTrue("A null id is always claimable (never a redelivery)", dedup.claim(null));
    }

    /**
     * An identifier older than the retention window is pruned, so a genuine
     * resend long after the redelivery window is treated as new rather than
     * suppressed forever.
     */
    @Test(timeout = 10000)
    public void deduplicatorPrunesExpiredClaims() throws InterruptedException {
        SlackEventDeduplicator dedup = new SlackEventDeduplicator(20);

        assertTrue(dedup.claim("Ev-expiring"));
        assertFalse("Still within the window, it is a redelivery", dedup.claim("Ev-expiring"));

        Thread.sleep(40);

        assertTrue("Past the window, the same id is treated as a new message",
                dedup.claim("Ev-expiring"));
    }

    /** The in-message /task command path is also guarded against redelivery. */
    @Test(timeout = 10000)
    public void inMessageTaskCommandIsDeduplicated() {
        CountingListener listener = listenerWithChannel("C_CMD");

        String messageTs = "1731000400.000500";
        String text = "/task fix the flaky test";

        List<Boolean> results = List.of(
                listener.handleMessage("C_CMD", "U1", text, messageTs, null),
                listener.handleMessage("C_CMD", "U1", text, messageTs, null));

        assertTrue("First /task delivery submits", results.get(0));
        assertFalse("Redelivered /task does not submit again", results.get(1));
        assertEquals("One /task message submits one job", 1, listener.submissions.get());
    }
}
