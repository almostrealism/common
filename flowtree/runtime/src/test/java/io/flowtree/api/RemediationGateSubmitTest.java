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

package io.flowtree.api;

import io.flowtree.controller.JobStatsStore;
import io.flowtree.controller.RemediationStateStore;
import io.flowtree.workstream.Workstream;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * HTTP-level tests for where {@link FlowTreeApiEndpoint#handleSubmit} runs the
 * per-PR remediation gate. The gate counts a pass and records it as dispatched,
 * so it must run only after the submission has cleared the pre-dispatch
 * rejections (no server configured, no agents connected): a pass recorded but
 * never queued would make a retry on the same commit look like an already-run
 * repeat and be skipped even though nothing ran. These tests pin that ordering
 * by submitting a remediation pass that is rejected before dispatch and
 * asserting the gate left no trace, and a complementary one that is dispatched
 * and asserting the gate counted it.
 */
public class RemediationGateSubmitTest extends FlowTreeApiSubmitTestBase {

    /** Workstream every submission in this class targets. */
    private static final String WORKSTREAM_ID = "ws-remediation-gate";

    /** The commit that triggered the remediation pass. */
    private static final String TRIGGER_COMMIT = "c1";

    /** The per-PR remediation record store wired into the endpoint. */
    private RemediationStateStore remediationStore;

    /** Wires the stats and remediation stores and registers the target workstream. */
    @Before
    public void wireStores() throws IOException {
        File dir = Files.createTempDirectory("remediation-gate").toFile();
        JobStatsStore statsStore = new JobStatsStore(new File(dir, "stats").getAbsolutePath());
        statsStore.initialize();
        endpoint.setStatsStore(statsStore);
        remediationStore = new RemediationStateStore(new File(dir, "remediation").getAbsolutePath());
        remediationStore.initialize();
        endpoint.setRemediationStore(remediationStore);

        Workstream ws = new Workstream(WORKSTREAM_ID, "C_remediation", "#remediation");
        ws.setDefaultBranch("fix/some-branch");
        ws.setGitUserName("Test User");
        ws.setGitUserEmail("test@example.com");
        notifier.registerWorkstream(ws);
    }

    /** Closes the remediation store after each test. */
    @After
    public void closeStore() {
        if (remediationStore != null) remediationStore.close();
    }

    /**
     * With no server configured, the submission is refused before dispatch, and
     * the gate must not have counted the pass or recorded it as dispatched —
     * otherwise a retry would be misread as an already-run repeat.
     */
    @Test(timeout = 15000)
    public void aRejectedPassIsNotRecordedAsDispatched() throws IOException {
        HttpURLConnection conn = postRemediationPass();
        assertEquals("no server is configured, so the submission is refused", 400, conn.getResponseCode());
        assertTrue(readErrorBody(conn).contains("No FlowTree server configured"));

        assertEquals("a pass that never dispatched is not counted",
                0, remediationStore.get(WORKSTREAM_ID).getPassCount());
        assertNull("a pass that never dispatched records no last-dispatched commit",
                remediationStore.get(WORKSTREAM_ID).getLastDispatchedCommit());
    }

    /**
     * Once a server with a connected peer is present, the same submission
     * dispatches and the gate counts it, recording the triggering commit — so the
     * move below the rejection checks did not disable gating on the happy path.
     */
    @Test(timeout = 15000)
    public void aDispatchedPassIsCountedAndRecorded() throws IOException {
        endpoint.setServer(new QueueCapturingServer());

        HttpURLConnection conn = postRemediationPass();
        assertEquals(200, conn.getResponseCode());

        assertEquals("a dispatched pass is counted once",
                1, remediationStore.get(WORKSTREAM_ID).getPassCount());
        Assert.assertEquals("the dispatched pass records its triggering commit",
                TRIGGER_COMMIT, remediationStore.get(WORKSTREAM_ID).getLastDispatchedCommit());
    }

    /**
     * Posts a remediation-pass submission for the registered workstream.
     *
     * @return the open connection
     * @throws IOException if the request cannot be sent
     */
    private HttpURLConnection postRemediationPass() throws IOException {
        return openPost("/api/submit",
                "{\"prompt\":\"Review branch changes.\",\"workstreamId\":\"" + WORKSTREAM_ID + "\","
                + "\"remediationPass\":true,\"triggerCommit\":\"" + TRIGGER_COMMIT + "\","
                + "\"description\":\"Review branch changes\"}");
    }
}
