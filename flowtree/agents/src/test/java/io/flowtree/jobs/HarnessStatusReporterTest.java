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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.flowtree.JsonFieldExtractor;
import io.flowtree.jobs.agent.AgentRunResult;
import io.flowtree.jobs.agent.Phase;
import io.flowtree.jobs.agent.PhaseConfig;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link HarnessStatusReporter} verifying that harness-authored
 * status messages carry the distinctive gear prefix, describe phase entry/exit
 * and inactivity events, are tagged with the {@code harness_status} activity,
 * and are silently suppressed when no controller URL is configured.
 */
public class HarnessStatusReporterTest extends TestSuiteBase {

    /** A captured post: the URL and JSON body handed to the poster sink. */
    private static final class Posted {
        /** The URL of the post. */
        private final String url;
        /** The JSON body of the post. */
        private final String body;

        /**
         * Creates a Posted with the given URL and body.
         */
        private Posted(String url, String body) {
            this.url = url;
            this.body = body;
        }
    }

    /** Builds a reporter whose posts accumulate into {@code sink}. */
    private static HarnessStatusReporter reporter(List<Posted> sink) {
        return new HarnessStatusReporter("http://controller/api/workstreams/ws-1",
                (url, body) -> sink.add(new Posted(url, body)));
    }

    /** Builds a successful session result with the given duration and cost. */
    private static AgentRunResult successResult(long durationMs, double costUsd) {
        return new AgentRunResult(0, false, "", "sess", durationMs, 0,
                3, costUsd, "success", false, Collections.emptyList(),
                Collections.emptyMap());
    }

    /** phaseEntry message carries gear prefix, phase-entry emoji, phase name, runner, model, and activity tag. */
    @Test(timeout = 30000)
    public void phaseEntryMessageCarriesDistinctivePrefix() {
        List<Posted> posts = new ArrayList<>();
        reporter(posts).phaseEntry(Phase.PRIMARY, "claude",
                new PhaseConfig("claude", "opus", "high", "anthropic"));

        assertEquals(1, posts.size());
        Posted post = posts.get(0);
        assertTrue("phase-entry message must lead with the gear system prefix",
                post.body.contains(HarnessStatusReporter.SYSTEM_PREFIX));
        assertTrue("phase-entry message must carry the phase-entry emoji",
                post.body.contains(HarnessStatusReporter.PHASE_ENTRY_EMOJI));
        assertTrue("phase-entry names the phase", post.body.contains("PRIMARY"));
        assertTrue("phase-entry names the runner and model",
                post.body.contains("claude") && post.body.contains("opus"));
        assertTrue("phase-entry names the provider", post.body.contains("anthropic"));
        assertTrue("posts to the workstream messages endpoint",
                post.url.endsWith("/messages"));
        assertTrue("tagged with the harness_status activity",
                post.body.contains(HarnessStatusReporter.ACTIVITY));
    }

    /** phaseExit message carries gear prefix, phase-exit emoji, phase name, and outcome. */
    @Test(timeout = 30000)
    public void phaseExitMessageSummarisesOutcome() {
        List<Posted> posts = new ArrayList<>();
        reporter(posts).phaseExit(Phase.PRIMARY, successResult(125000, 0.42));

        assertEquals(1, posts.size());
        String body = posts.get(0).body;
        assertTrue("phase-exit message must lead with the gear system prefix",
                body.contains(HarnessStatusReporter.SYSTEM_PREFIX));
        assertTrue("phase-exit message must carry the phase-exit emoji",
                body.contains(HarnessStatusReporter.PHASE_EXIT_EMOJI));
        assertTrue("phase-exit names the phase", body.contains("PRIMARY"));
        assertTrue("phase-exit reports success", body.contains("success"));
    }

    /** inactivitySuspended posts a message with gear prefix, pause emoji, runner name, and activity tag. */
    @Test(timeout = 30000)
    public void inactivityMessagePostedOnSuspension() {
        List<Posted> posts = new ArrayList<>();
        reporter(posts).inactivitySuspended("opencode", 0, 3);

        assertEquals(1, posts.size());
        String body = posts.get(0).body;
        assertTrue("inactivity message must lead with the gear system prefix",
                body.contains(HarnessStatusReporter.SYSTEM_PREFIX));
        assertTrue("inactivity message must carry the pause emoji",
                body.contains(HarnessStatusReporter.INACTIVITY_EMOJI));
        assertTrue("inactivity message names the runner", body.contains("opencode"));
        assertTrue("inactivity message tagged with the harness_status activity",
                body.contains(HarnessStatusReporter.ACTIVITY));
    }

    /** formatInactivity announces relaunch for non-final attempt and abandoning for final attempt. */
    @Test(timeout = 30000)
    public void inactivityMessageDistinguishesRestartFromAbandon() {
        String relaunch = HarnessStatusReporter.formatInactivity("opencode", 0, 3);
        String abandon = HarnessStatusReporter.formatInactivity("opencode", 3, 3);

        assertTrue("a non-final attempt announces a relaunch",
                relaunch.contains("relaunching"));
        assertTrue("the final attempt announces abandonment",
                abandon.contains("abandoning"));
    }

    /** unusual posts carry gear prefix and warning emoji. */
    @Test(timeout = 30000)
    public void unusualMessageCarriesWarningEmoji() {
        List<Posted> posts = new ArrayList<>();
        reporter(posts).unusual("Git tampering detected — restarting session");

        assertEquals(1, posts.size());
        String body = posts.get(0).body;
        assertTrue(body.contains(HarnessStatusReporter.SYSTEM_PREFIX));
        assertTrue(body.contains(HarnessStatusReporter.UNUSUAL_EMOJI));
        assertTrue(body.contains("Git tampering"));
    }

    /** null or empty URL disables the reporter; no posts are made when disabled. */
    @Test(timeout = 30000)
    public void disabledReporterIsSilentNoOp() {
        List<Posted> posts = new ArrayList<>();
        HarnessStatusReporter nullUrl = new HarnessStatusReporter(null,
                (url, body) -> posts.add(new Posted(url, body)));
        HarnessStatusReporter emptyUrl = new HarnessStatusReporter("",
                (url, body) -> posts.add(new Posted(url, body)));

        assertFalse("null URL disables the reporter", nullUrl.isEnabled());
        assertFalse("empty URL disables the reporter", emptyUrl.isEnabled());

        nullUrl.phaseEntry(Phase.PRIMARY, "claude", null);
        emptyUrl.unusual("should not post");
        assertTrue("a disabled reporter must never post", posts.isEmpty());
    }

    /** formatDuration produces compact m/s representations for typical durations. */
    @Test(timeout = 30000)
    public void durationFormatsCompactly() {
        assertEquals("2m 5s", HarnessStatusReporter.formatDuration(125000));
        assertEquals("45s", HarnessStatusReporter.formatDuration(45000));
        assertEquals("0s", HarnessStatusReporter.formatDuration(-10));
    }

    /** No formatted message contains Unicode replacement character U+FFFD. */
    @Test(timeout = 30000)
    public void noUnicodeReplacementCharactersInAnyMessage() {
        String replacementChar = "\uFFFD";

        String entry = HarnessStatusReporter.formatPhaseEntry(
                Phase.PRIMARY, "claude",
                new PhaseConfig("claude", "sonnet", "medium", "anthropic"));
        String exit = HarnessStatusReporter.formatPhaseExit(
                Phase.PRIMARY,
                successResult(151000, 0.10));
        String inactivityRelaunch = HarnessStatusReporter.formatInactivity("opencode", 0, 3);
        String inactivityAbandon = HarnessStatusReporter.formatInactivity("opencode", 3, 3);
        String unusual = HarnessStatusReporter.SYSTEM_PREFIX
                + HarnessStatusReporter.UNUSUAL_EMOJI + " some description";

        assertFalse("phase-entry must not contain Unicode replacement char",
                entry.contains(replacementChar));
        assertFalse("phase-exit must not contain Unicode replacement char",
                exit.contains(replacementChar));
        assertFalse("inactivity-relaunch must not contain Unicode replacement char",
                inactivityRelaunch.contains(replacementChar));
        assertFalse("inactivity-abandon must not contain Unicode replacement char",
                inactivityAbandon.contains(replacementChar));
        assertFalse("unusual must not contain Unicode replacement char",
                unusual.contains(replacementChar));
    }

    /** Em-dash character is preserved in all formatted messages. */
    @Test(timeout = 30000)
    public void emDashPreservedInFormattedMessages() {
        String emDash = "\u2014";

        String exit = HarnessStatusReporter.formatPhaseExit(
                Phase.PRIMARY, successResult(31000, 0.05));
        String relaunch = HarnessStatusReporter.formatInactivity("opencode", 0, 3);
        String abandon = HarnessStatusReporter.formatInactivity("opencode", 3, 3);

        assertTrue("phase-exit must contain em-dash separator", exit.contains(emDash));
        assertTrue("inactivity-relaunch must contain em-dash separator", relaunch.contains(emDash));
        assertTrue("inactivity-abandon must contain em-dash separator", abandon.contains(emDash));
    }

    /** Unicode emoji markers are preserved in phase entry/exit and inactivity messages. */
    @Test(timeout = 30000)
    public void unicodeMarkersPreservedInFormattedMessages() {
        String entry = HarnessStatusReporter.formatPhaseEntry(
                Phase.PRIMARY, "claude",
                new PhaseConfig("claude", "sonnet", "medium", "anthropic"));
        String exit = HarnessStatusReporter.formatPhaseExit(
                Phase.PRIMARY, successResult(31000, 0.05));
        String relaunch = HarnessStatusReporter.formatInactivity("opencode", 0, 3);

        assertTrue("phase-entry must lead with gear emoji",
                entry.startsWith(HarnessStatusReporter.SYSTEM_PREFIX));
        assertTrue("phase-entry must contain play-button emoji",
                entry.contains(HarnessStatusReporter.PHASE_ENTRY_EMOJI));
        assertTrue("phase-exit must contain stop-button emoji",
                exit.contains(HarnessStatusReporter.PHASE_EXIT_EMOJI));
        assertTrue("inactivity message must contain pause emoji",
                relaunch.contains(HarnessStatusReporter.INACTIVITY_EMOJI));
        assertTrue("SYSTEM_PREFIX must be non-empty non-ASCII Unicode",
                !HarnessStatusReporter.SYSTEM_PREFIX.isEmpty()
                && HarnessStatusReporter.SYSTEM_PREFIX.chars().anyMatch(c -> c > 127));
    }

    /** JSON round-trip through JsonFieldExtractor preserves all Unicode markers exactly. */
    @Test(timeout = 30000)
    public void jsonRoundTripPreservesUnicodeMarkers() {
        ObjectMapper mapper = new ObjectMapper();

        String phaseEntry = HarnessStatusReporter.formatPhaseEntry(
                Phase.PRIMARY, "claude",
                new PhaseConfig("claude", "sonnet", "medium", "anthropic"));
        String phaseExit = HarnessStatusReporter.formatPhaseExit(
                Phase.PRIMARY, successResult(90000, 0.07));

        for (String original : new String[]{phaseEntry, phaseExit}) {
            ObjectNode node = mapper.createObjectNode();
            node.put("text", original);
            String json = node.toString();

            String extracted = JsonFieldExtractor.extractString(json, "text");
            assertFalse("round-tripped JSON body must not contain U+FFFD replacement char",
                    extracted != null && extracted.contains("\uFFFD"));
            assertTrue("round-trip through JSON must preserve Unicode markers exactly"
                    + " — original=<" + original + "> extracted=<" + extracted + ">",
                    original.equals(extracted));
        }
    }

    /** formatPhaseExit preserves phase name, success status, and formatted duration. */
    @Test(timeout = 30000)
    public void phaseExitPreservesPhaseNameStatusAndDuration() {
        AgentRunResult success = successResult(151000, 0.20);
        String msg = HarnessStatusReporter.formatPhaseExit(Phase.PRIMARY, success);

        assertTrue("phase-exit must name the phase", msg.contains("PRIMARY"));
        assertTrue("phase-exit must report success", msg.contains("success"));
        assertTrue("phase-exit must report duration", msg.contains("2m 31s"));
    }
}
