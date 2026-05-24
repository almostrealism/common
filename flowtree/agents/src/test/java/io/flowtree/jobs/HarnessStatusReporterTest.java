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
        private final String url;
        private final String body;

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

    @Test(timeout = 30000)
    public void phaseEntryMessageCarriesDistinctivePrefix() {
        List<Posted> posts = new ArrayList<>();
        reporter(posts).phaseEntry(Phase.REVIEW, "claude",
                new PhaseConfig("claude", "opus", "high", "anthropic"));

        assertEquals(1, posts.size());
        Posted post = posts.get(0);
        assertTrue("phase-entry message must lead with the gear system prefix",
                post.body.contains(HarnessStatusReporter.SYSTEM_PREFIX));
        assertTrue("phase-entry message must carry the phase-entry emoji",
                post.body.contains(HarnessStatusReporter.PHASE_ENTRY_EMOJI));
        assertTrue("phase-entry names the phase", post.body.contains("REVIEW"));
        assertTrue("phase-entry names the runner and model",
                post.body.contains("claude") && post.body.contains("opus"));
        assertTrue("phase-entry names the provider", post.body.contains("anthropic"));
        assertTrue("posts to the workstream messages endpoint",
                post.url.endsWith("/messages"));
        assertTrue("tagged with the harness_status activity",
                post.body.contains(HarnessStatusReporter.ACTIVITY));
    }

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

    @Test(timeout = 30000)
    public void inactivityMessageDistinguishesRestartFromAbandon() {
        String relaunch = HarnessStatusReporter.formatInactivity("opencode", 0, 3);
        String abandon = HarnessStatusReporter.formatInactivity("opencode", 3, 3);

        assertTrue("a non-final attempt announces a relaunch",
                relaunch.contains("relaunching"));
        assertTrue("the final attempt announces abandonment",
                abandon.contains("abandoning"));
    }

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

    @Test(timeout = 30000)
    public void durationFormatsCompactly() {
        assertEquals("2m 5s", HarnessStatusReporter.formatDuration(125000));
        assertEquals("45s", HarnessStatusReporter.formatDuration(45000));
        assertEquals("0s", HarnessStatusReporter.formatDuration(-10));
    }
}
