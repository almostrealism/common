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

import io.flowtree.JsonFieldExtractor;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link HarnessHeartbeatSender}: the job-scoped heartbeat URL is
 * derived from the workstream URL, each beat carries the node/pid/phase the
 * harness supplies, and a sender with no URL is a silent no-op.
 */
public class HarnessHeartbeatSenderTest extends TestSuiteBase {

    /** The heartbeat URL is the API root with the job route, not nested under the workstream. */
    @Test(timeout = 10000)
    public void derivesJobScopedHeartbeatUrl() {
        Assert.assertEquals("http://host:7780/api/jobs/job-1/heartbeat",
                HarnessHeartbeatSender.heartbeatUrl(
                        "http://host:7780/api/workstreams/ws-7", "job-1"));
        Assert.assertEquals("a base without /workstreams/ has the job route appended",
                "http://host/api/jobs/job-1/heartbeat",
                HarnessHeartbeatSender.heartbeatUrl("http://host/api/", "job-1"));
        assertNull("a missing base URL yields no heartbeat URL",
                HarnessHeartbeatSender.heartbeatUrl(null, "job-1"));
        assertNull("a missing job id yields no heartbeat URL",
                HarnessHeartbeatSender.heartbeatUrl("http://host/api/workstreams/ws", ""));
    }

    /** A single beat posts the node, pid, and current phase to the heartbeat URL. */
    @Test(timeout = 10000)
    public void beatCarriesNodePidAndPhase() {
        List<String> urls = new ArrayList<>();
        List<String> bodies = new ArrayList<>();
        HarnessHeartbeatSender sender = new HarnessHeartbeatSender(
                "http://host/api/jobs/job-1/heartbeat",
                (url, body) -> { urls.add(url); bodies.add(body); },
                "halo (10.0.0.2)", 4242L, () -> "review", Duration.ofMinutes(3));

        assertTrue(sender.isEnabled());
        sender.beatOnce();

        Assert.assertEquals(1, bodies.size());
        Assert.assertEquals("http://host/api/jobs/job-1/heartbeat", urls.get(0));
        String body = bodies.get(0);
        Assert.assertEquals("halo (10.0.0.2)", JsonFieldExtractor.extractString(body, "node"));
        Assert.assertEquals(4242L, JsonFieldExtractor.extractLong(body, "pid"));
        Assert.assertEquals("review", JsonFieldExtractor.extractString(body, "phase"));
    }

    /** With no phase supplier the beat still carries node and pid and omits phase. */
    @Test(timeout = 10000)
    public void beatWithoutPhaseOmitsPhase() {
        List<String> bodies = new ArrayList<>();
        HarnessHeartbeatSender sender = new HarnessHeartbeatSender(
                "http://host/api/jobs/job-1/heartbeat",
                (url, body) -> bodies.add(body),
                "node-a", 7L, null, null);
        sender.beatOnce();
        Assert.assertEquals(1, bodies.size());
        assertNull("no phase field when none is supplied",
                JsonFieldExtractor.extractString(bodies.get(0), "phase"));
        Assert.assertEquals("node-a", JsonFieldExtractor.extractString(bodies.get(0), "node"));
    }

    /** A sender with no URL is disabled and never posts. */
    @Test(timeout = 10000)
    public void disabledSenderNeverPosts() {
        List<String> bodies = new ArrayList<>();
        HarnessHeartbeatSender sender = new HarnessHeartbeatSender(
                null, (url, body) -> bodies.add(body), "node", 1L, null, null);
        assertFalse(sender.isEnabled());
        sender.beatOnce();
        sender.start();
        sender.stop();
        Assert.assertTrue("a disabled sender posts nothing", bodies.isEmpty());
    }
}
