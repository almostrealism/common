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

import io.flowtree.Server;
import io.flowtree.job.JobFactory;
import io.flowtree.slack.SlackNotifier;
import io.flowtree.workstream.Workstream;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Asserts that wake-up job submissions created by
 * {@link CompletionListenerFanout} post a Slack submission
 * notification on the listener's channel, exactly the way the
 * normal API submission path does. The submission message becomes
 * the wake-up's thread root and seeds
 * {@link SlackNotifier#getThreadTs(String)} so subsequent
 * messages from the wake-up (started / completed / send_message)
 * will thread as replies under it.
 *
 * <p>Prior to this fix, the fan-out's wake-up path called
 * {@link Server#addTask(JobFactory)} directly, bypassing the
 * submission-notification call that the normal
 * {@code FlowTreeApiEndpoint.submitJob} makes via
 * {@code notifiers.notifierFor(workstreamId).onJobSubmitted(...)}.
 * The wake-up therefore had no {@code thread_ts} and follow-up
 * messages from the wake-up could not thread under a submission
 * message. These tests pin the new behaviour: a wake-up submission
 * posts a thread-root message, and {@code getThreadTs} returns the
 * message timestamp of that root for the wake-up's job ID.</p>
 *
 * <p>The class lives in a separate file from
 * {@code CompletionListenerSafetyIntegrationTest} and
 * {@code CompletionListenerFanoutTest} so this new assertion does
 * not modify base-branch test files that the agent write-lock
 * would block. The existing tests stay focused on safety ceilings
 * and the documented unit cases; this class
 * covers the thread-root requirement explicitly.</p>
 */
public class CompletionListenerWakeUpSlackThreadTest extends TestSuiteBase {

    /**
     * A wake-up submission to a listener workstream posts a Slack
     * submission notification on the listener's channel. The
     * message text is the formatted &quot;Job submitted&quot; line
     * that {@code FlowTreeApiEndpoint.submitJob} would have
     * posted for a normal API submission, so the listener sees
     * the wake-up the same way it sees any other job.
     */
    @Test(timeout = 10000)
    public void wakeUpSubmissionPostsSlackThreadRoot() throws IOException {
        SlackNotifier notifier = new SlackNotifier(null);
        List<String> messages = new ArrayList<>();
        notifier.setMessageCallback(json -> messages.add(json));

        Workstream orchestrator = new Workstream("C_WAKEUP_ORCH", "#orch");
        orchestrator.setDefaultBranch("feature/orch");
        notifier.registerWorkstream(orchestrator);

        Workstream worker = new Workstream("C_WAKEUP_WORKER", "#worker");
        worker.setDefaultBranch("feature/worker");
        worker.setCompletionListeners(
                Arrays.asList(orchestrator.getWorkstreamId()));
        notifier.registerWorkstream(worker);

        RecordingServer server = new RecordingServer();
        AtomicLong clock = new AtomicLong(0L);
        CompletionListenerFanout fanout = new CompletionListenerFanout(
                () -> true,
                () -> {
                    Map<String, Workstream> map = new HashMap<>();
                    map.put(orchestrator.getWorkstreamId(), orchestrator);
                    map.put(worker.getWorkstreamId(), worker);
                    return map;
                },
                server,
                null,
                wsId -> "http://test/api/workstreams/" + wsId,
                null, null, null, id -> null, null,
                // The thread-root lookup: this is the single
                // piece of wiring that activates the new
                // submission-notification call inside
                // dispatchToListener. Without it, the wake-up
                // path bypasses onJobSubmitted and the listener
                // sees no thread root.
                wsId -> notifier.getWorkstream(wsId) != null ? notifier : null,
                clock::get);

        // Fire a completion on the worker; the fan-out should
        // dispatch a wake-up to the orchestrator AND post a
        // submission message on the orchestrator's channel.
        fanout.fanout(worker.getWorkstreamId(),
                JobCompletionEvent.success("j-source", "source job done"));

        assertEquals("fanout should submit exactly one wake-up",
                1, server.added.size());
        assertEquals("wake-up should post exactly one Slack message"
                        + " (the thread-root submission notification)",
                1, messages.size());

        String message = messages.get(0);
        // The submission message targets the orchestrator's
        // channel (the listener's), not the worker's.
        assertTrue("submission message must target the listener's"
                        + " channel (orchestrator), not the source worker's:"
                        + " " + message,
                message.contains("C_WAKEUP_ORCH"));
        // The submission text mirrors the normal API path:
        // "Job submitted:" + the wake-up description.
        assertTrue("submission message must include 'Job submitted':"
                        + " " + message,
                message.contains("Job submitted"));
        assertTrue("submission message must include the wake-up"
                        + " description prefix: " + message,
                message.contains("wake-up:"));
    }

    /**
     * The wake-up's job ID resolves through
     * {@link SlackNotifier#getThreadTs(String)} to the thread
     * timestamp the submission notification just posted. This is
     * the property the per-request thread_ts resolver in
     * {@code MessageEndpointHandler.handle} depends on: a
     * {@code send_message} call from a wake-up agent must find a
     * thread to attach to.
     */
    @Test(timeout = 10000)
    public void wakeUpJobReceivesAThreadTs() throws IOException {
        SlackNotifier notifier = new SlackNotifier(null);
        notifier.setMessageCallback(json -> { /* capture thread root */ });

        Workstream orchestrator = new Workstream("C_WAKEUP_TS_ORCH", "#orch-ts");
        orchestrator.setDefaultBranch("feature/orch-ts");
        notifier.registerWorkstream(orchestrator);

        Workstream worker = new Workstream("C_WAKEUP_TS_WORKER", "#worker-ts");
        worker.setDefaultBranch("feature/worker-ts");
        worker.setCompletionListeners(
                Arrays.asList(orchestrator.getWorkstreamId()));
        notifier.registerWorkstream(worker);

        RecordingServer server = new RecordingServer();
        AtomicLong clock = new AtomicLong(0L);
        CompletionListenerFanout fanout = new CompletionListenerFanout(
                () -> true,
                () -> {
                    Map<String, Workstream> map = new HashMap<>();
                    map.put(orchestrator.getWorkstreamId(), orchestrator);
                    map.put(worker.getWorkstreamId(), worker);
                    return map;
                },
                server,
                null,
                wsId -> "http://test/api/workstreams/" + wsId,
                null, null, null, id -> null, null,
                wsId -> notifier.getWorkstream(wsId) != null ? notifier : null,
                clock::get);

        fanout.fanout(worker.getWorkstreamId(),
                JobCompletionEvent.success("j-ts-source", "ts source"));

        assertEquals("fanout should submit exactly one wake-up",
                1, server.added.size());

        CodingAgentJob.Factory factory =
                (CodingAgentJob.Factory) server.added.get(0);
        String wakeUpJobId = factory.getTaskId();
        assertNotNull("wake-up factory must have a task id", wakeUpJobId);

        // The notifier's synthetic-ts test path (client == null &&
        // messageCallback != null) populates jobThreadTs when
        // onJobSubmitted posts the message. getThreadTs must
        // therefore return non-null for the wake-up's job id.
        String threadTs = notifier.getThreadTs(wakeUpJobId);
        assertNotNull("wake-up job must have a thread_ts after"
                + " onJobSubmitted posts the thread root; jobId="
                + wakeUpJobId, threadTs);
        assertTrue("thread_ts must look like a Slack ts (start with"
                        + " 'test-ts-' in the synthetic path or be a numeric"
                        + " ts in production): " + threadTs,
                threadTs.startsWith("test-ts-")
                        || threadTs.matches("\\d+\\.\\d+"));
    }

    /**
     * The wake-up's submission notification is posted on the
     * listener's channel, not the source's. A wake-up is &quot;the
     * listener waking up&quot;, so the message must land in the
     * listener's Slack channel where the listener's thread
     * resolution can find it. A submission to the source's
     * channel would orphan the wake-up's messages from the
     * listener's view of the conversation.
     */
    @Test(timeout = 10000)
    public void wakeUpSubmissionLandsOnListenerChannelNotSource() throws IOException {
        SlackNotifier notifier = new SlackNotifier(null);
        List<String> channelIds = new ArrayList<>();
        notifier.setMessageCallback(json -> {
            int start = json.indexOf("\"channel\":\"") + 11;
            int end = json.indexOf("\"", start);
            if (start > 10 && end > start) channelIds.add(json.substring(start, end));
        });

        Workstream listener = new Workstream("C_LISTENER_CHANNEL", "#listener");
        listener.setDefaultBranch("feature/listener");
        notifier.registerWorkstream(listener);

        Workstream source = new Workstream("C_SOURCE_CHANNEL", "#source");
        source.setDefaultBranch("feature/source");
        source.setCompletionListeners(
                Arrays.asList(listener.getWorkstreamId()));
        notifier.registerWorkstream(source);

        RecordingServer server = new RecordingServer();
        AtomicLong clock = new AtomicLong(0L);
        CompletionListenerFanout fanout = new CompletionListenerFanout(
                () -> true,
                () -> {
                    Map<String, Workstream> map = new HashMap<>();
                    map.put(listener.getWorkstreamId(), listener);
                    map.put(source.getWorkstreamId(), source);
                    return map;
                },
                server,
                null,
                wsId -> "http://test/api/workstreams/" + wsId,
                null, null, null, id -> null, null,
                wsId -> notifier.getWorkstream(wsId) != null ? notifier : null,
                clock::get);

        fanout.fanout(source.getWorkstreamId(),
                JobCompletionEvent.success("j-channel", "channel test"));

        assertEquals(1, channelIds.size());
        assertTrue("wake-up submission must be posted on the"
                        + " listener's channel, not the source's: got "
                        + channelIds.get(0),
                "C_LISTENER_CHANNEL".equals(channelIds.get(0)));
    }

    // ----- helpers -----

    /**
     * Test-only fake {@link Server} that records every
     * {@code addTask} invocation. Mirrors the helper in
     * {@code CompletionListenerFanoutTest} so this class stays
     * self-contained; the wake-up path needs the recorded
     * factory to read its assigned task id (the wake-up's job
     * id) for the {@code getThreadTs} assertion.
     */
    private static class RecordingServer extends Server {
        /** Captured job factories in submission order. */
        final List<JobFactory> added = new ArrayList<>();
        /**
         * Constructs the recording server with port 0 so the
         * superclass skips binding a real listening socket.
         */
        RecordingServer() throws IOException {
            super(serverPropertiesWithEphemeralPort());
        }
        @Override
        public boolean addTask(JobFactory task) {
            added.add(task);
            return true;
        }
    }

    /**
     * Builds a {@link Properties} bag configured to skip
     * server-socket binding (port 0) so the recording-server
     * fake can be constructed any number of times in the same
     * JVM without port collisions.
     */
    private static Properties serverPropertiesWithEphemeralPort() {
        Properties p = new Properties();
        p.setProperty("server.port", "0");
        return p;
    }
}
