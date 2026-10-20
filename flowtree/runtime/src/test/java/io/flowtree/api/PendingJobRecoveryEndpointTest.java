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

import fi.iki.elonen.NanoHTTPD;
import io.flowtree.controller.JobStatsStore;
import io.flowtree.job.JobFactory;
import io.flowtree.jobs.JobCompletionEvent;
import io.flowtree.slack.SlackNotifier;
import io.flowtree.workstream.Workstream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * HTTP-level tests for the endpoint's half of controller-side job recovery:
 * {@code POST /api/submit} persists the accepted factory, the terminal choke
 * point {@link FlowTreeApiEndpoint#completeJob} clears it,
 * {@link FlowTreeApiEndpoint#recoverPendingJobs()} re-dispatches only jobs still
 * {@code SUBMITTED}, and {@code GET /api/jobs/in-flight} is reachable rather
 * than shadowed by the {@code /api/jobs/{id}} route.
 */
public class PendingJobRecoveryEndpointTest extends FlowTreeApiSubmitTestBase {

    /** Workstream every test submits to. */
    private static final String WORKSTREAM_ID = "ws-recover";

    /** Server that captures the factories the endpoint dispatches. */
    private QueueCapturingServer server;
    /** Controller data directory holding the recovery and stats databases. */
    private File dataDir;
    /** Stats store backing the endpoint's job lifecycle records. */
    private JobStatsStore stats;

    /** Wires a capturing server, a stats store and a data directory, and registers a workstream. */
    @Before
    public void attachRecovery() throws IOException {
        dataDir = Files.createTempDirectory("endpoint-recovery").toFile();
        dataDir.deleteOnExit();
        stats = new JobStatsStore(new File(dataDir, "stats").getAbsolutePath());
        stats.initialize();
        server = wire(endpoint, notifier);
    }

    /** Closes the stats store. */
    @After
    public void closeStats() {
        stats.close();
    }

    /** A submitted job that never started is re-dispatched with the same identity. */
    @Test(timeout = 30000)
    public void submittedJobIsRedispatched() throws IOException {
        String jobId = submit();

        endpoint.recoverPendingJobs();

        assertEquals("the recovered job is dispatched a second time", 2, server.queued.size());
        assertEquals(jobId, server.queued.get(1).getTaskId());
    }

    /** A job that has heartbeated (and so been promoted to STARTED) is not re-dispatched. */
    @Test(timeout = 30000)
    public void startedJobIsNotRedispatched() throws IOException {
        String jobId = submit();
        stats.recordHeartbeat(jobId, Instant.now(), "worker (10.0.0.4)");

        endpoint.recoverPendingJobs();

        assertEquals("a running job is never dispatched twice", 1, server.queued.size());
    }

    /**
     * The recovery blob survives the endpoint shutting down, and a fresh
     * endpoint rooted in the same data directory re-dispatches it — the
     * controller-restart path end to end.
     */
    @Test(timeout = 30000)
    public void submittedJobSurvivesEndpointRestart() throws IOException {
        String jobId = submit();
        endpoint.stop();

        endpoint = new FlowTreeApiEndpoint(0, new SlackNotifier(null));
        QueueCapturingServer restarted = wire(endpoint, new SlackNotifier(null));
        endpoint.recoverPendingJobs();

        assertEquals("the restarted endpoint re-dispatches the job", 1, restarted.queued.size());
        assertEquals(jobId, restarted.queued.get(0).getTaskId());
    }

    /**
     * Completing a job clears its recovery blob: once the endpoint has shut
     * down, the recovery store no longer holds a factory for the job even when
     * asked for it explicitly.
     */
    @Test(timeout = 30000)
    public void completedJobBlobIsCleared() throws IOException {
        String jobId = submit();
        endpoint.completeJob(WORKSTREAM_ID, JobCompletionEvent.success(jobId, "done"));
        endpoint.stop();

        PendingJobRecovery recovery = new PendingJobRecovery(
                new File(dataDir, PendingJobRecovery.DATABASE_NAME).getAbsolutePath());
        recovery.initialize();
        try {
            List<JobFactory> dispatched = new ArrayList<>();
            int count = recovery.recover(Collections.singleton(jobId), dispatched::add);
            assertEquals("a completed job's factory was cleared", 0, count);
        } finally {
            recovery.close();
        }
    }

    /** The in-flight route answers with the counts, not as a lookup of a job named "in-flight". */
    @Test(timeout = 30000)
    public void inFlightRouteCountsSubmittedJob() throws IOException {
        submit();

        HttpURLConnection conn = (HttpURLConnection) new URL(
                "http://localhost:" + port + "/api/jobs/in-flight").openConnection();
        assertEquals(200, conn.getResponseCode());
        String body = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals("{\"active\":0,\"pending\":1,\"total\":1}", body);
    }

    /**
     * Wires {@code target} to a fresh capturing server, the shared stats store
     * and the shared data directory, registers the test workstream on
     * {@code workstreams}, and starts it if it is not yet listening.
     */
    private QueueCapturingServer wire(FlowTreeApiEndpoint target, SlackNotifier workstreams)
            throws IOException {
        QueueCapturingServer capturing = new QueueCapturingServer();
        target.setServer(capturing);
        target.setStatsStore(stats);
        target.setDataDirectory(dataDir);
        Workstream ws = new Workstream(WORKSTREAM_ID, "C_recover", "#recover");
        ws.setDefaultBranch("project/task-recover");
        ws.setGitUserName("Test User");
        ws.setGitUserEmail("test@example.com");
        workstreams.registerWorkstream(ws);
        if (!target.isAlive()) {
            target.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            port = target.getListeningPort();
        }
        return capturing;
    }

    /** Submits a job to the test workstream and returns the dispatched job's id. */
    private String submit() throws IOException {
        HttpURLConnection conn = openPost("/api/submit",
                "{\"prompt\":\"Recover me.\",\"workstreamId\":\"" + WORKSTREAM_ID + "\"}");
        assertEquals(200, conn.getResponseCode());
        assertEquals("the submission is dispatched once", 1, server.queued.size());
        return server.queued.get(0).getTaskId();
    }
}
