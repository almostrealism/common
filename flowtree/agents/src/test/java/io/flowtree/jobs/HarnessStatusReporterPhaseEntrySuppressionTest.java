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

package io.flowtree.jobs;

import io.flowtree.jobs.agent.AgentRunResult;
import io.flowtree.jobs.agent.Phase;
import io.flowtree.jobs.agent.PhaseConfig;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Verifies the phase-message verbosity policy:
 *
 * <ul>
 *   <li>Entering the {@link Phase#PRIMARY PRIMARY} phase posts a single
 *       entry message naming the runner, model, and provider.</li>
 *   <li>Entering any non-PRIMARY phase (review, deduplication, organizational
 *       placement, maven-dependency-protection, post-completion, commit-message,
 *       git-tampering-restart, push-conflict-resolution, retrospective) is a
 *       silent no-op — no entry message is published.</li>
 *   <li>{@link HarnessStatusReporter#phaseExit(Phase, AgentRunResult)} still
 *       publishes its completion message for every phase.</li>
 *   <li>The preserved message types (inactivity suspension, unusual
 *       termination) are unaffected by the entry-message gate.</li>
 * </ul>
 *
 * <p>This test class is branch-introduced (no equivalent exists on the base
 * branch) so that the new verbosity policy has explicit coverage alongside
 * updates to the existing reporter tests that previously asserted the more
 * verbose behavior.</p>
 */
public class HarnessStatusReporterPhaseEntrySuppressionTest extends TestSuiteBase {

    /** A captured post: the JSON body handed to the poster sink. */
    private static final class Posted {
        /** The JSON body of the post. */
        private final String body;

        /**
         * Creates a Posted with the given body.
         */
        private Posted(String body) {
            this.body = body;
        }
    }

    /** Builds a reporter whose posts accumulate into {@code sink}. */
    private static HarnessStatusReporter reporter(List<Posted> sink) {
        return new HarnessStatusReporter("http://controller/api/workstreams/ws-1",
                (url, body) -> sink.add(new Posted(body)));
    }

    /** Builds a successful session result with the given duration and cost. */
    private static AgentRunResult successResult(long durationMs, double costUsd) {
        return new AgentRunResult(0, false, "", "sess", durationMs, 0,
                3, costUsd, "success", false, Collections.emptyList(),
                Collections.emptyMap());
    }

    /** Entering PRIMARY posts a single entry message with the standard gear prefix. */
    @Test(timeout = 30000)
    public void primaryPhaseEntryPostsEntryMessage() {
        List<Posted> posts = new ArrayList<>();
        reporter(posts).phaseEntry(Phase.PRIMARY, "claude",
                new PhaseConfig("claude", "opus", "high", "anthropic"));

        assertEquals("entering PRIMARY must post exactly one message",
                1, posts.size());
        String body = posts.get(0).body;
        assertTrue("PRIMARY entry must lead with the gear system prefix",
                body.contains(HarnessStatusReporter.SYSTEM_PREFIX));
        assertTrue("PRIMARY entry must carry the phase-entry emoji",
                body.contains(HarnessStatusReporter.PHASE_ENTRY_EMOJI));
        assertTrue("PRIMARY entry must name the phase", body.contains("PRIMARY"));
        assertTrue("PRIMARY entry must name the runner", body.contains("claude"));
    }

    /** Entering REVIEW is silent — no entry message is posted. */
    @Test(timeout = 30000)
    public void reviewPhaseEntryIsSuppressed() {
        List<Posted> posts = new ArrayList<>();
        reporter(posts).phaseEntry(Phase.REVIEW, "opencode",
                new PhaseConfig("opencode", "sonnet", "medium", "anthropic"));

        assertTrue("entering REVIEW must NOT post an entry message (got "
                + posts.size() + ")", posts.isEmpty());
    }

    /** Entering DEDUPLICATION is silent — no entry message is posted. */
    @Test(timeout = 30000)
    public void deduplicationPhaseEntryIsSuppressed() {
        List<Posted> posts = new ArrayList<>();
        reporter(posts).phaseEntry(Phase.DEDUPLICATION, "claude",
                new PhaseConfig("claude", "opus", "high", "anthropic"));

        assertTrue("entering DEDUPLICATION must NOT post an entry message",
                posts.isEmpty());
    }

    /** Entering COMMIT_MESSAGE is silent — no entry message is posted. */
    @Test(timeout = 30000)
    public void commitMessagePhaseEntryIsSuppressed() {
        List<Posted> posts = new ArrayList<>();
        reporter(posts).phaseEntry(Phase.COMMIT_MESSAGE, "claude",
                new PhaseConfig("claude", "opus", "high", "anthropic"));

        assertTrue("entering COMMIT_MESSAGE must NOT post an entry message",
                posts.isEmpty());
    }

    /** Entering ORGANIZATIONAL_PLACEMENT is silent — no entry message is posted. */
    @Test(timeout = 30000)
    public void organizationalPlacementPhaseEntryIsSuppressed() {
        List<Posted> posts = new ArrayList<>();
        reporter(posts).phaseEntry(Phase.ORGANIZATIONAL_PLACEMENT, "claude",
                new PhaseConfig("claude", "opus", "high", "anthropic"));

        assertTrue("entering ORGANIZATIONAL_PLACEMENT must NOT post an entry message",
                posts.isEmpty());
    }

    /** Entering POST_COMPLETION is silent — no entry message is posted. */
    @Test(timeout = 30000)
    public void postCompletionPhaseEntryIsSuppressed() {
        List<Posted> posts = new ArrayList<>();
        reporter(posts).phaseEntry(Phase.POST_COMPLETION, "claude",
                new PhaseConfig("claude", "opus", "high", "anthropic"));

        assertTrue("entering POST_COMPLETION must NOT post an entry message",
                posts.isEmpty());
    }

    /** Entering RETROSPECTIVE is silent — no entry message is posted. */
    @Test(timeout = 30000)
    public void retrospectivePhaseEntryIsSuppressed() {
        List<Posted> posts = new ArrayList<>();
        reporter(posts).phaseEntry(Phase.RETROSPECTIVE, "claude",
                new PhaseConfig("claude", "opus", "high", "anthropic"));

        assertTrue("entering RETROSPECTIVE must NOT post an entry message",
                posts.isEmpty());
    }

    /** Entering GIT_TAMPERING_RESTART is silent — no entry message is posted. */
    @Test(timeout = 30000)
    public void gitTamperingRestartPhaseEntryIsSuppressed() {
        List<Posted> posts = new ArrayList<>();
        reporter(posts).phaseEntry(Phase.GIT_TAMPERING_RESTART, "claude",
                new PhaseConfig("claude", "opus", "high", "anthropic"));

        assertTrue("entering GIT_TAMPERING_RESTART must NOT post an entry message",
                posts.isEmpty());
    }

    /** Entering MAVEN_DEPENDENCY_PROTECTION and PUSH_CONFLICT_RESOLUTION is silent — no entry message is posted. */
    @Test(timeout = 30000)
    public void remainingNonPrimaryPhaseEntriesAreSuppressed() {
        Phase[] phases = {
                Phase.MAVEN_DEPENDENCY_PROTECTION,
                Phase.PUSH_CONFLICT_RESOLUTION
        };
        for (Phase phase : phases) {
            List<Posted> posts = new ArrayList<>();
            reporter(posts).phaseEntry(phase, "claude",
                    new PhaseConfig("claude", "opus", "high", "anthropic"));
            assertTrue("entering " + phase + " must NOT post an entry message",
                    posts.isEmpty());
        }
    }

    /** Completion messages are still emitted for every non-PRIMARY phase. */
    @Test(timeout = 30000)
    public void completionMessagesStillEmittedForEveryPhase() {
        List<Posted> posts = new ArrayList<>();
        HarnessStatusReporter r = reporter(posts);

        r.phaseExit(Phase.PRIMARY, successResult(125000, 0.42));
        r.phaseExit(Phase.REVIEW, successResult(60000, 0.10));
        r.phaseExit(Phase.DEDUPLICATION, successResult(30000, 0.05));
        r.phaseExit(Phase.COMMIT_MESSAGE, successResult(20000, 0.02));
        r.phaseExit(Phase.ORGANIZATIONAL_PLACEMENT, successResult(15000, 0.01));
        r.phaseExit(Phase.POST_COMPLETION, successResult(10000, 0.01));
        r.phaseExit(Phase.RETROSPECTIVE, successResult(8000, 0.01));

        assertEquals("every phase must still produce a completion message",
                7, posts.size());
        for (Posted post : posts) {
            assertTrue("completion message must carry the phase-exit emoji",
                    post.body.contains(HarnessStatusReporter.PHASE_EXIT_EMOJI));
            assertTrue("completion message must contain 'complete'",
                    post.body.contains("complete"));
        }
    }

    /** A full PR-like pipeline posts only one entry message (the PRIMARY one) and a series of completion messages. */
    @Test(timeout = 30000)
    public void fullPipelineProducesOneEntryAndCompletionMessagesOnly() {
        List<Posted> posts = new ArrayList<>();
        HarnessStatusReporter r = reporter(posts);

        Phase[] pipeline = {
                Phase.PRIMARY, Phase.REVIEW, Phase.DEDUPLICATION,
                Phase.COMMIT_MESSAGE, Phase.POST_COMPLETION
        };
        PhaseConfig config = new PhaseConfig("claude", "opus", "high", "anthropic");
        for (Phase phase : pipeline) {
            r.phaseEntry(phase, "claude", config);
            r.phaseExit(phase, successResult(10000, 0.01));
        }

        assertEquals("a 5-phase pipeline must produce 1 entry (PRIMARY) + 5 completions = 6 messages",
                pipeline.length + 1, posts.size());
        int entryCount = (int) posts.stream()
                .filter(p -> p.body.contains(HarnessStatusReporter.PHASE_ENTRY_EMOJI))
                .count();
        int exitCount = (int) posts.stream()
                .filter(p -> p.body.contains(HarnessStatusReporter.PHASE_EXIT_EMOJI))
                .count();
        assertEquals("only one entry message (for PRIMARY) must be posted",
                1, entryCount);
        assertEquals("one completion message per phase must be posted",
                pipeline.length, exitCount);
        assertTrue("the single entry message must be the PRIMARY one",
                posts.get(0).body.contains("Entering PRIMARY")
                        || posts.get(0).body.contains("PRIMARY"));
    }

    /** Inactivity suspension messages are unaffected by the entry-message gate. */
    @Test(timeout = 30000)
    public void inactivityMessagesUnaffectedByEntryGate() {
        List<Posted> posts = new ArrayList<>();
        HarnessStatusReporter r = reporter(posts);

        r.inactivitySuspended("opencode", 0, 3);
        r.inactivitySuspended("opencode", 3, 3);

        assertEquals("inactivity messages must still be emitted", 2, posts.size());
        assertTrue("first inactivity must announce relaunch",
                posts.get(0).body.contains("relaunching"));
        assertTrue("final inactivity must announce abandonment",
                posts.get(1).body.contains("abandoning"));
        for (Posted post : posts) {
            assertTrue("inactivity message must carry the pause emoji",
                    post.body.contains(HarnessStatusReporter.INACTIVITY_EMOJI));
        }
    }

    /** Unusual-termination messages are unaffected by the entry-message gate. */
    @Test(timeout = 30000)
    public void unusualMessagesUnaffectedByEntryGate() {
        List<Posted> posts = new ArrayList<>();
        HarnessStatusReporter r = reporter(posts);

        r.unusual("Git tampering detected — restarting session");
        r.unusual("Post-completion command timed out — abandoning");

        assertEquals("unusual messages must still be emitted", 2, posts.size());
        for (Posted post : posts) {
            assertTrue("unusual message must carry the warning emoji",
                    post.body.contains(HarnessStatusReporter.UNUSUAL_EMOJI));
        }
    }

    /** Entry suppression is silent: the suppression does not produce any partial JSON or empty post. */
    @Test(timeout = 30000)
    public void suppressedEntryProducesNoPartialOutput() {
        List<Posted> posts = new ArrayList<>();
        reporter(posts).phaseEntry(Phase.REVIEW, "claude",
                new PhaseConfig("claude", "opus", "high", "anthropic"));

        assertTrue("suppressed entry must produce zero posts", posts.isEmpty());
    }
}
