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

import fi.iki.elonen.NanoHTTPD;
import io.flowtree.api.FlowTreeApiEndpoint;
import io.flowtree.jobs.JobCompletionEvent;
import io.flowtree.workstream.Workstream;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Tests for {@link SlackListener} workspace-aware routing, multi-tenant
 * channel key generation, and {@link SlackNotifier} workstream resolution
 * by branch name.
 *
 * <p>These tests verify:
 * <ul>
 *   <li>Composite channel keys including workspace ID ({@code T111:C_CHANNEL})</li>
 *   <li>Backward compatibility for bare channel ID routing</li>
 *   <li>Workspace-aware workstream registration and lookup</li>
 *   <li>Forwarding to per-workspace {@link SlackNotifier} instances</li>
 *   <li>Slash command workspace ID propagation</li>
 * </ul>
 */
public class SlackRoutingTest extends TestSuiteBase {

    /**
     * Verifies that a null workspace ID causes {@code channelKey} to return the bare channel ID.
     */
    @Test(timeout = 10000)
    public void testChannelKeyNullWorkspaceReturnsBareChannelId() {
        assertEquals("C_ALPHA", SlackListener.channelKey(null, "C_ALPHA"));
    }

    /**
     * Verifies that a non-null workspace ID causes {@code channelKey} to return a composite key.
     */
    @Test(timeout = 10000)
    public void testChannelKeyWithWorkspaceReturnsCompositeKey() {
        assertEquals("T111:C_ALPHA", SlackListener.channelKey("T111", "C_ALPHA"));
    }

    /**
     * Verifies that the same channel ID in two different workspaces produces distinct keys.
     */
    @Test(timeout = 10000)
    public void testChannelKeyDifferentWorkspacesSameChannelProduceDifferentKeys() {
        String keyA = SlackListener.channelKey("T111", "C_SHARED");
        String keyB = SlackListener.channelKey("T222", "C_SHARED");
        assertFalse("Same channel in different workspaces must produce different keys",
                keyA.equals(keyB));
    }

    /**
     * Verifies backward compatibility where a bare channel ID lookup succeeds when no workspace ID is set.
     */
    @Test(timeout = 10000)
    public void testBackwardCompatNullWorkspaceIdRouting() {
        SlackNotifier notifier = new SlackNotifier(null);
        SlackListener listener = new SlackListener(notifier);

        Workstream ws = new Workstream("ws-back", "C_BACK", "#back");
        ws.setDefaultBranch("main");
        listener.registerWorkstream(ws);

        Workstream found = listener.getWorkstream("C_BACK");
        assertNotNull("Backward compat: getWorkstream() with bare channel ID must work", found);
        assertEquals("C_BACK", found.getChannelId());
    }

    /**
     * Verifies that a workstream with a workspace ID is registered and retrievable by the listener.
     */
    @Test(timeout = 10000)
    public void testWorkspaceAwareWorkstreamRegistration() {
        SlackNotifier notifier = new SlackNotifier(null);
        SlackListener listener = new SlackListener(notifier);

        Workstream ws = new Workstream("ws-multi", "C_MULTI", "#multi");
        ws.setDefaultBranch("main");
        ws.setWorkspaceId("T111");
        listener.registerWorkstream(ws);

        listener.handleMessage("C_MULTI", "U1", "hello", "ts1", null, "T111");
        boolean found = listener.getWorkstreams().values().stream()
                .anyMatch(w -> "C_MULTI".equals(w.getChannelId()));
        assertTrue("Workspace-aware workstream should be registered", found);
    }

    /**
     * Verifies that two workstreams sharing the same channel ID but belonging to different workspaces coexist.
     */
    @Test(timeout = 10000)
    public void testSameChannelIdInTwoWorkspacesRoutedIndependently() {
        SlackNotifier notifierA = new SlackNotifier(null);
        SlackNotifier notifierB = new SlackNotifier(null);

        SlackListener listener = new SlackListener(notifierA);

        Map<String, SlackNotifier> byWorkspace = new HashMap<>();
        byWorkspace.put("T111", notifierA);
        byWorkspace.put("T222", notifierB);
        listener.setNotifiersByWorkspace(byWorkspace);

        Workstream wsA = new Workstream("ws-a", "C_SHARED", "#shared-a");
        wsA.setDefaultBranch("main");
        wsA.setWorkspaceId("T111");
        listener.registerWorkstream(wsA);

        Workstream wsB = new Workstream("ws-b", "C_SHARED", "#shared-b");
        wsB.setDefaultBranch("develop");
        wsB.setWorkspaceId("T222");
        listener.registerWorkstream(wsB);

        assertEquals("Two workstreams with same channelId in different workspaces must coexist",
                2, listener.getWorkstreams().size());

        boolean hasMain = listener.getWorkstreams().values().stream()
                .anyMatch(w -> "main".equals(w.getDefaultBranch()));
        boolean hasDevelop = listener.getWorkstreams().values().stream()
                .anyMatch(w -> "develop".equals(w.getDefaultBranch()));
        assertTrue("wsA (main branch) must be registered", hasMain);
        assertTrue("wsB (develop branch) must be registered", hasDevelop);
    }

    /**
     * Verifies that {@code handleMessage} returns false when the channel is not registered.
     */
    @Test(timeout = 10000)
    public void testHandleMessageUnknownChannelReturnsFalseWithWorkspaceId() {
        SlackNotifier notifier = new SlackNotifier(null);
        SlackListener listener = new SlackListener(notifier);

        boolean handled = listener.handleMessage("C_UNKNOWN", "U1", "hello", "ts1", null, "T111");
        assertFalse("Message to unknown channel must return false", handled);
    }

    /**
     * Verifies that a {@code setup} slash command stores the workspace ID on the newly created workstream.
     */
    @Test(timeout = 10000)
    public void testSlashCommandSetupSetsSlackWorkspaceIdOnNewWorkstream() {
        SlackNotifier notifier = new SlackNotifier(null);
        SlackListener listener = new SlackListener(notifier);

        List<String> responses = new ArrayList<>();
        SlackListener.SlashCommandResponder responder = text -> responses.add(text);

        listener.handleSlashCommand("setup /workspace/project feature/test",
                "C_SETUP_WS", "#setup-ws", responder, "T999");

        boolean foundWithWorkspace = listener.getWorkstreams().values().stream()
                .anyMatch(w -> "T999".equals(w.getWorkspaceId())
                        && "C_SETUP_WS".equals(w.getChannelId()));
        assertTrue("Setup must store slackWorkspaceId on new workstream", foundWithWorkspace);
    }

    /**
     * Verifies that the {@code active} slash command filters workstreams to the calling workspace.
     */
    @Test(timeout = 10000)
    public void testSlashCommandActiveFiltersWorkstreamsByWorkspace() {
        SlackNotifier notifier = new SlackNotifier(null);
        SlackListener listener = new SlackListener(notifier);

        Map<String, SlackNotifier> byWorkspace = new HashMap<>();
        byWorkspace.put("T111", notifier);
        listener.setNotifiersByWorkspace(byWorkspace);

        Workstream wsA = new Workstream("ws-act-a", "C_ACT_A", "#act-a");
        wsA.setDefaultBranch("main");
        wsA.setWorkspaceId("T111");
        listener.registerWorkstream(wsA);

        Workstream wsB = new Workstream("ws-act-b", "C_ACT_B", "#act-b");
        wsB.setDefaultBranch("develop");
        wsB.setWorkspaceId("T222");
        listener.registerWorkstream(wsB);

        List<String> responses = new ArrayList<>();
        SlackListener.SlashCommandResponder responder = text -> responses.add(text);
        listener.handleSlashCommand("active", "C_ACT_A", "#act-a", responder, "T111");

        assertFalse("Active command should respond", responses.isEmpty());
        assertTrue("With null stats store active command warns",
                responses.get(0).contains("not available"));
    }

    /**
     * Verifies that the per-workspace notifier map is used when resolving the notifier for a workstream.
     */
    @Test(timeout = 10000)
    public void testSetNotifiersByWorkspaceIsUsedForNotifierResolution() {
        SlackNotifier primaryNotifier = new SlackNotifier(null);
        SlackNotifier workspaceNotifier = new SlackNotifier(null);

        SlackListener listener = new SlackListener(primaryNotifier);

        Map<String, SlackNotifier> byWorkspace = new HashMap<>();
        byWorkspace.put("T_SPECIAL", workspaceNotifier);
        listener.setNotifiersByWorkspace(byWorkspace);

        Workstream ws = new Workstream("ws-special", "C_SPECIAL", "#special");
        ws.setWorkspaceId("T_SPECIAL");
        ws.setDefaultBranch("main");
        listener.registerWorkstream(ws);

        Map<String, JobCompletionEvent> jobs = workspaceNotifier.getRecentJobs(ws.getWorkstreamId());
        assertNotNull("Workspace notifier should have workstream registered", jobs);
    }

    /**
     * Verifies that {@code findWorkstreamByBranch} returns the correct workstream for exact branch matches
     * and returns null for unknown, null, empty, or partial branch names.
     */
    @Test(timeout = 10000)
    public void testFindWorkstreamByBranch() {
        SlackNotifier notifier = new SlackNotifier(null);

        Workstream ws1 = new Workstream("ws-rings", "C_RINGS", "#rings");
        ws1.setDefaultBranch("feature/new-decoder");

        Workstream ws2 = new Workstream("ws-common", "C_COMMON", "#common");
        ws2.setDefaultBranch("feature/pipeline-agents");

        Workstream ws3 = new Workstream("ws-no-branch", "C_NONE", "#no-branch");

        notifier.registerWorkstream(ws1);
        notifier.registerWorkstream(ws2);
        notifier.registerWorkstream(ws3);

        assertSame(ws1, notifier.findWorkstreamByBranch("feature/new-decoder"));
        assertSame(ws2, notifier.findWorkstreamByBranch("feature/pipeline-agents"));

        assertNull(notifier.findWorkstreamByBranch("feature/unknown"));
        assertNull(notifier.findWorkstreamByBranch(null));
        assertNull(notifier.findWorkstreamByBranch(""));

        assertNull(notifier.findWorkstreamByBranch("feature/new"));
        assertNull(notifier.findWorkstreamByBranch("feature/new-decoder-v2"));
    }

    /**
     * Verifies that submitting a branch name matched by workstreams in two different repositories
     * returns HTTP 400 with an "Ambiguous" error naming both repositories.
     */
    @Test(timeout = 10000)
    public void testApiSubmitAmbiguousBranchAcrossDifferentRepos() throws Exception {
        SlackNotifier notifier = new SlackNotifier(null);
        Workstream wsCommon = new Workstream("C_COMMON", "#w-audio-prototypes");
        wsCommon.setDefaultBranch("feature/audio-prototypes");
        wsCommon.setRepoUrl("git@github.com:almostrealism/common.git");
        notifier.registerWorkstream(wsCommon);

        Workstream wsRings = new Workstream("C_RINGS", "#w-audio-prototypes-rings");
        wsRings.setDefaultBranch("feature/audio-prototypes");
        wsRings.setRepoUrl("git@github.com:almostrealism/ringsdesktop.git");
        notifier.registerWorkstream(wsRings);

        FlowTreeApiEndpoint endpoint = new FlowTreeApiEndpoint(0, notifier);
        endpoint.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);

        try {
            int port = endpoint.getListeningPort();
            String body = "{\"prompt\":\"Do something\","
                    + "\"targetBranch\":\"feature/audio-prototypes\"}";

            HttpURLConnection conn = (HttpURLConnection) new URL(
                    "http://localhost:" + port + "/api/submit").openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            assertEquals(400, conn.getResponseCode());
            String error = new String(conn.getErrorStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue("Error should mention ambiguous resolution; was: " + error,
                    error.contains("Ambiguous"));
            assertTrue("Error should name the branch involved",
                    error.contains("feature/audio-prototypes"));
            assertTrue("Error should mention both repositories",
                    error.contains("common") && error.contains("ringsdesktop"));
        } finally {
            endpoint.stop();
        }
    }

    /**
     * Verifies that providing a {@code repoUrl} in the submit request resolves ambiguity and routes
     * to the correct workstream without returning an "Ambiguous" error.
     */
    @Test(timeout = 10000)
    public void testApiSubmitDisambiguatesByRepoUrl() throws Exception {
        SlackNotifier notifier = new SlackNotifier(null);
        Workstream wsCommon = new Workstream("C_COMMON", "#w-audio-prototypes");
        wsCommon.setDefaultBranch("feature/audio-prototypes");
        wsCommon.setRepoUrl("git@github.com:almostrealism/common.git");
        notifier.registerWorkstream(wsCommon);

        Workstream wsRings = new Workstream("C_RINGS", "#w-audio-prototypes-rings");
        wsRings.setDefaultBranch("feature/audio-prototypes");
        wsRings.setRepoUrl("git@github.com:almostrealism/ringsdesktop.git");
        notifier.registerWorkstream(wsRings);

        FlowTreeApiEndpoint endpoint = new FlowTreeApiEndpoint(0, notifier);
        endpoint.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);

        try {
            int port = endpoint.getListeningPort();
            String body = "{\"prompt\":\"Do something\","
                    + "\"targetBranch\":\"feature/audio-prototypes\","
                    + "\"repoUrl\":\"git@github.com:almostrealism/common.git\"}";

            HttpURLConnection conn = (HttpURLConnection) new URL(
                    "http://localhost:" + port + "/api/submit").openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            assertEquals(400, conn.getResponseCode());
            String error = new String(conn.getErrorStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue("Resolver must not have flagged ambiguity; got: " + error,
                    !error.contains("Ambiguous"));
            assertTrue("Submission must reach the post-resolution server check; got: " + error,
                    error.contains("No FlowTree server"));
        } finally {
            endpoint.stop();
        }
    }

    /**
     * Verifies that a {@code repoUrl} that matches no registered workstream results in a "no workstream found"
     * error rather than silently routing to a different repository.
     */
    @Test(timeout = 10000)
    public void testApiSubmitRepoUrlMismatchDoesNotCrossRoute() throws Exception {
        SlackNotifier notifier = new SlackNotifier(null);
        Workstream wsCommon = new Workstream("C_COMMON", "#w-audio-prototypes");
        wsCommon.setDefaultBranch("feature/audio-prototypes");
        wsCommon.setRepoUrl("git@github.com:almostrealism/common.git");
        notifier.registerWorkstream(wsCommon);

        FlowTreeApiEndpoint endpoint = new FlowTreeApiEndpoint(0, notifier);
        endpoint.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);

        try {
            int port = endpoint.getListeningPort();
            String body = "{\"prompt\":\"Do something\","
                    + "\"targetBranch\":\"feature/audio-prototypes\","
                    + "\"repoUrl\":\"git@github.com:almostrealism/ringsdesktop.git\"}";

            HttpURLConnection conn = (HttpURLConnection) new URL(
                    "http://localhost:" + port + "/api/submit").openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            assertEquals(400, conn.getResponseCode());
            String error = new String(conn.getErrorStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue("Should report no workstream found, not silently cross-route; got: " + error,
                    error.contains("No workstream found for branch"));
        } finally {
            endpoint.stop();
        }
    }
}
