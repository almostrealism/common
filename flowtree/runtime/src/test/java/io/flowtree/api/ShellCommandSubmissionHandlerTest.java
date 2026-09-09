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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fi.iki.elonen.NanoHTTPD.Response;
import io.flowtree.Server;
import io.flowtree.job.JobFactory;
import io.flowtree.jobs.ShellCommandJob;
import io.flowtree.slack.NotifierRegistry;
import io.flowtree.slack.SlackListener;
import io.flowtree.slack.SlackNotifier;
import io.flowtree.workstream.Workstream;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link ShellCommandSubmissionHandler}: the working-directory
 * propagation from the listener's default workspace path, and the self-notify
 * flag reaching both the dispatched {@link ShellCommandJob.Factory} and the
 * JSON submission response.
 */
public class ShellCommandSubmissionHandlerTest extends TestSuiteBase {

    /** JSON parser for response bodies. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Builds a handler bound to a fresh {@link RecordingServer} and the
     * given listener (may be {@code null}), immediate dispatch (no delay
     * executor scheduling occurs in these tests). Self-notify job IDs are
     * discarded; use the four-argument overload to observe them.
     */
    private static ShellCommandSubmissionHandler handler(NotifierRegistry notifiers,
            SlackListener listener, RecordingServer server) {
        return handler(notifiers, listener, server, ConcurrentHashMap.newKeySet());
    }

    /**
     * Builds a handler bound to a fresh {@link RecordingServer} and the
     * given listener (may be {@code null}), recording self-notify-eligible
     * job IDs into {@code selfNotifyJobs} exactly as
     * {@link FlowTreeApiEndpoint#shellCommandSubmissionHandler()} wires it.
     */
    private static ShellCommandSubmissionHandler handler(NotifierRegistry notifiers,
            SlackListener listener, RecordingServer server, Set<String> selfNotifyJobs) {
        Map<String, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        return new ShellCommandSubmissionHandler(notifiers, listener, server,
                pending, executor, () -> 7780, msg -> { }, selfNotifyJobs::add);
    }

    /** Builds and registers a minimal workstream with the given ID on {@code notifier}. */
    private static Workstream ws(String id, SlackNotifier notifier) {
        Workstream w = new Workstream(id, "C_" + id, "#" + id);
        w.setDefaultBranch("feature/" + id);
        notifier.registerWorkstream(w);
        return w;
    }

    /**
     * The listener's {@code defaultWorkspacePath} is propagated onto the
     * factory exactly as the coding-agent submission path propagates it,
     * so a shell job resolves to the same checkout location as every other
     * job on the workstream instead of falling back to
     * {@code WorkspaceResolver}'s default.
     */
    @Test(timeout = 10000)
    public void defaultWorkspacePathIsPropagatedFromListener() throws IOException {
        SlackNotifier notifier = new SlackNotifier(null);
        Workstream workstream = ws("ws-shell-1", notifier);
        SlackListener listener = new SlackListener(notifier);
        listener.setDefaultWorkspacePath("/workspace/project");
        RecordingServer server = new RecordingServer();

        handler(new NotifierRegistry(notifier, Collections.emptyMap()), listener, server)
                .handle("{}", workstream, "ws-shell-1", "echo hi", null, null, 0, false);

        assertEquals(1, server.added.size());
        ShellCommandJob.Factory factory = (ShellCommandJob.Factory) server.added.get(0);
        assertEquals("/workspace/project", factory.getDefaultWorkspacePath());
    }

    /** With no listener configured, the default workspace path is left unset. */
    @Test(timeout = 10000)
    public void defaultWorkspacePathIsUnsetWithoutListener() throws IOException {
        SlackNotifier notifier = new SlackNotifier(null);
        Workstream workstream = ws("ws-shell-2", notifier);
        RecordingServer server = new RecordingServer();

        handler(new NotifierRegistry(notifier, Collections.emptyMap()), null, server)
                .handle("{}", workstream, "ws-shell-2", "echo hi", null, null, 0, false);

        assertEquals(1, server.added.size());
        ShellCommandJob.Factory factory = (ShellCommandJob.Factory) server.added.get(0);
        assertNull(factory.getDefaultWorkspacePath());
    }

    /**
     * {@code selfNotify=true} reaches the dispatched factory and is echoed
     * back in the submission response, so a caller can confirm what it got
     * (per {@code flowtree/CLAUDE.md}'s "the job is the source of truth" rule).
     */
    @Test(timeout = 10000)
    public void selfNotifyReachesFactoryAndResponse() throws IOException {
        SlackNotifier notifier = new SlackNotifier(null);
        Workstream workstream = ws("ws-shell-3", notifier);
        RecordingServer server = new RecordingServer();

        Response response = handler(new NotifierRegistry(notifier, Collections.emptyMap()),
                null, server)
                .handle("{}", workstream, "ws-shell-3", "sleep 600", null, null, 0, true);

        ShellCommandJob.Factory factory = (ShellCommandJob.Factory) server.added.get(0);
        assertTrue(factory.isSelfNotify());

        JsonNode json = MAPPER.readTree(readBody(response));
        assertTrue(json.get("ok").asBoolean());
        assertTrue("submission response must echo selfNotify:true",
                json.get("selfNotify").asBoolean());
    }

    /**
     * A {@code selfNotify=true} submission registers the dispatched job's
     * task ID into the self-notify registry, and an ordinary submission does
     * not. {@link FlowTreeApiEndpoint#handleStatusEvent} resolves the
     * self-notify flag on a completion event by membership in this registry
     * rather than trusting the posted status body, so a caller able to POST a
     * completion event cannot forge {@code selfNotify=true} for a job that was
     * never validated as an eligible shell job.
     */
    @Test(timeout = 10000)
    public void selfNotifySubmissionRegistersJobIdForCompletionValidation() throws IOException {
        SlackNotifier notifier = new SlackNotifier(null);
        Workstream workstream = ws("ws-shell-5", notifier);
        RecordingServer server = new RecordingServer();
        Set<String> selfNotifyJobs = ConcurrentHashMap.newKeySet();

        handler(new NotifierRegistry(notifier, Collections.emptyMap()), null, server, selfNotifyJobs)
                .handle("{}", workstream, "ws-shell-5", "sleep 600", null, null, 0, true);

        ShellCommandJob.Factory factory = (ShellCommandJob.Factory) server.added.get(0);
        assertTrue("selfNotify=true submission must register the task ID",
                selfNotifyJobs.contains(factory.getTaskId()));
    }

    /**
     * An ordinary submission (selfNotify=false) never registers its task ID,
     * so a later forged {@code selfNotify=true} on that job's completion POST
     * is ignored by {@link FlowTreeApiEndpoint#handleStatusEvent}.
     */
    @Test(timeout = 10000)
    public void ordinarySubmissionDoesNotRegisterSelfNotifyJobId() throws IOException {
        SlackNotifier notifier = new SlackNotifier(null);
        Workstream workstream = ws("ws-shell-6", notifier);
        RecordingServer server = new RecordingServer();
        Set<String> selfNotifyJobs = ConcurrentHashMap.newKeySet();

        handler(new NotifierRegistry(notifier, Collections.emptyMap()), null, server, selfNotifyJobs)
                .handle("{}", workstream, "ws-shell-6", "echo hi", null, null, 0, false);

        ShellCommandJob.Factory factory = (ShellCommandJob.Factory) server.added.get(0);
        assertTrue("ordinary submission must not register a self-notify job ID",
                selfNotifyJobs.isEmpty() && !selfNotifyJobs.contains(factory.getTaskId()));
    }

    /** An ordinary submission (selfNotify=false) omits the field from the response. */
    @Test(timeout = 10000)
    public void ordinarySubmissionOmitsSelfNotifyFromResponse() throws IOException {
        SlackNotifier notifier = new SlackNotifier(null);
        Workstream workstream = ws("ws-shell-4", notifier);
        RecordingServer server = new RecordingServer();

        Response response = handler(new NotifierRegistry(notifier, Collections.emptyMap()),
                null, server)
                .handle("{}", workstream, "ws-shell-4", "echo hi", null, null, 0, false);

        JsonNode json = MAPPER.readTree(readBody(response));
        assertTrue(json.get("ok").asBoolean());
        assertTrue("selfNotify must be absent (not merely false) when unset",
                json.get("selfNotify") == null);
    }

    /** Reads the full response body from a NanoHTTPD {@link Response}. */
    private static String readBody(Response response) throws IOException {
        return new String(response.getData().readAllBytes(), StandardCharsets.UTF_8);
    }

    /**
     * A test-only fake {@link Server} that records every {@code addTask}
     * invocation, mirroring the fake used by {@code CompletionListenerFanoutTest}.
     */
    private static class RecordingServer extends Server {
        /** Captured job factories in submission order. */
        final List<JobFactory> added = new ArrayList<>();

        /**
         * Constructs the recording server with port 0 so the superclass
         * skips binding a real listening socket.
         */
        RecordingServer() throws IOException {
            super(ephemeralPortProperties());
        }

        @Override
        public boolean addTask(JobFactory task) {
            added.add(task);
            return true;
        }
    }

    /**
     * Builds a {@link Properties} bag configured to skip server-socket
     * binding (port 0) so {@link RecordingServer} can be constructed any
     * number of times in the same JVM without port collisions.
     */
    private static Properties ephemeralPortProperties() {
        Properties p = new Properties();
        p.setProperty("server.port", "0");
        return p;
    }
}
