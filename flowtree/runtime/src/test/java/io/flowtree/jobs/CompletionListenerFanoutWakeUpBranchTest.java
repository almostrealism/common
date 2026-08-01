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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Asserts that wake-up jobs produced by {@link CompletionListenerFanout}
 * carry the LISTENER workstream's {@code defaultBranch} as their
 * {@code targetBranch} — not {@code null} and not the SOURCE (triggering)
 * job's branch.
 *
 * <p>Prior to the fix, {@link CompletionListenerFanout#buildWakeUpFactory}
 * never called {@code factory.setTargetBranch(listener.getDefaultBranch())}.
 * This caused {@link GitManagedJob#run()} to skip the entire git checkout /
 * commit block (guarded by {@code if (targetBranch != null)}), which in a
 * shared-repo topology left the agent on whatever branch the SOURCE child
 * had last checked out. These tests pin the fixed behaviour: the wake-up
 * factory must carry the listener's own branch, not the source's branch
 * and not {@code null}.</p>
 *
 * <p>This class lives in a new file to avoid the write-lock on
 * base-branch test files. The existing {@link CompletionListenerFanoutTest}
 * remains untouched.</p>
 */
public class CompletionListenerFanoutWakeUpBranchTest extends TestSuiteBase {

    /** Fixed workstream ID for the orchestrator listener under test. */
    private static final String LISTENER_ID     = "ws-listener-orchestrator";
    /** Branch the listener orchestrator commits work to. */
    private static final String LISTENER_BRANCH = "orchestration/eva-poc-driver";
    /** Base branch for the listener orchestrator. */
    private static final String LISTENER_BASE   = "master";
    /** Fixed workstream ID for the triggering source child under test. */
    private static final String SOURCE_ID       = "ws-source-child";
    /** Branch the source child is on — deliberately different from the listener's. */
    private static final String SOURCE_BRANCH   = "feature/met-367-e8-wod-generation-agent";

    /**
     * The wake-up factory built for the listener workstream carries the
     * listener's {@code defaultBranch} as {@code targetBranch}.
     *
     * <p>This test FAILS without the fix (targetBranch is null) and
     * PASSES with it (targetBranch equals the listener's defaultBranch).</p>
     */
    @Test(timeout = 10000)
    public void wakeUpFactoryCarriesListenerTargetBranch() throws IOException {
        Workstream listener = makeListener();
        Workstream source   = makeSource(listener.getWorkstreamId());

        RecordingServer server = new RecordingServer();
        CompletionListenerFanout fanout = buildFanout(server, listener, source);

        fanout.fanout(source.getWorkstreamId(),
                JobCompletionEvent.success("j-source-1", "child finished"));

        assertEquals("fanout must submit exactly one wake-up", 1, server.added.size());

        CodingAgentJob.Factory factory = (CodingAgentJob.Factory) server.added.get(0);

        assertNotNull("targetBranch on wake-up factory must not be null"
                + " — the git checkout block in GitManagedJob.run() is gated on it",
                factory.getTargetBranch());

        // targetBranch must equal the LISTENER's defaultBranch, not the source's branch.
        assertEquals(LISTENER_BRANCH, factory.getTargetBranch());

        // Regression guard: source branch must NOT leak into the wake-up factory.
        assertNotEquals(SOURCE_BRANCH, factory.getTargetBranch());
    }

    /**
     * The wake-up factory also carries the listener's {@code baseBranch},
     * mirroring what the normal submission path sets.
     *
     * <p>This test FAILS without the fix (baseBranch is null) and
     * PASSES with it (baseBranch equals the listener's baseBranch).</p>
     */
    @Test(timeout = 10000)
    public void wakeUpFactoryCarriesListenerBaseBranch() throws IOException {
        Workstream listener = makeListener();
        Workstream source   = makeSource(listener.getWorkstreamId());

        RecordingServer server = new RecordingServer();
        CompletionListenerFanout fanout = buildFanout(server, listener, source);

        fanout.fanout(source.getWorkstreamId(),
                JobCompletionEvent.success("j-source-2", "child finished again"));

        assertEquals(1, server.added.size());
        CodingAgentJob.Factory factory = (CodingAgentJob.Factory) server.added.get(0);

        assertNotNull("baseBranch on wake-up factory must not be null",
                factory.getBaseBranch());

        // baseBranch must equal the LISTENER's baseBranch.
        assertEquals(LISTENER_BASE, factory.getBaseBranch());
    }

    /**
     * Constructs the listener workstream: the orchestrator that wakes up
     * when a child finishes. Its {@code defaultBranch} is
     * {@value #LISTENER_BRANCH} and its {@code baseBranch} is
     * {@value #LISTENER_BASE}.
     */
    private static Workstream makeListener() {
        Workstream w = new Workstream(LISTENER_ID, "C_LISTENER", "#listener");
        w.setDefaultBranch(LISTENER_BRANCH);
        w.setBaseBranch(LISTENER_BASE);
        w.setAllowedTools("Read");
        w.setMaxTurns(50);
        w.setMaxBudgetUsd(10.0);
        return w;
    }

    /**
     * Constructs the source (triggering child) workstream. It is on a
     * deliberately different branch ({@value #SOURCE_BRANCH}) so any
     * regression that leaks the source branch into the wake-up factory
     * is caught by {@link #wakeUpFactoryCarriesListenerTargetBranch()}.
     */
    private static Workstream makeSource(String listenerId) {
        Workstream w = new Workstream(SOURCE_ID, "C_SOURCE", "#source");
        w.setDefaultBranch(SOURCE_BRANCH);
        w.setCompletionListeners(Arrays.asList(listenerId));
        w.setAllowedTools("Read");
        w.setMaxTurns(50);
        w.setMaxBudgetUsd(10.0);
        return w;
    }

    /**
     * Wires a {@link CompletionListenerFanout} backed by the given
     * recording server and the two workstreams under test.
     */
    private static CompletionListenerFanout buildFanout(
            RecordingServer server,
            Workstream listener,
            Workstream source) {
        Map<String, Workstream> workstreams = new HashMap<>();
        workstreams.put(listener.getWorkstreamId(), listener);
        workstreams.put(source.getWorkstreamId(), source);

        return new CompletionListenerFanout(
                () -> true,
                () -> workstreams,
                server,
                null,
                wsId -> "http://test/api/workstreams/" + wsId,
                null, null, null, id -> null, null, null,
                new AtomicLong(0L)::get);
    }

    /**
     * Minimal {@link Server} replacement that records every
     * {@link Server#addTask(JobFactory)} call without binding a socket.
     */
    private static class RecordingServer extends Server {

        /** All factories submitted via {@link #addTask}. */
        final List<JobFactory> added = new ArrayList<>();

        /** Constructs the server on an ephemeral port so tests do not conflict. */
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
     * Builds a {@link Properties} bag configured to skip server-socket
     * binding (port 0) so the recording-server fake can be constructed
     * any number of times in the same JVM without port collisions.
     */
    private static Properties serverPropertiesWithEphemeralPort() {
        Properties p = new Properties();
        p.setProperty("server.port", "0");
        return p;
    }
}
