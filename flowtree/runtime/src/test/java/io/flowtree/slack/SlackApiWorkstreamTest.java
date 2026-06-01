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
import io.flowtree.workstream.Workstream;
import io.flowtree.workstream.WorkstreamConfig;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import io.flowtree.api.FlowTreeApiEndpoint;

import static org.junit.Assert.*;

/**
 * Tests for the FlowTree HTTP API: workstream registration, update,
 * channel-name auto-generation, collision handling, and submit routing.
 *
 * <p>These tests exercise {@link FlowTreeApiEndpoint} directly via real HTTP
 * connections to a locally bound NanoHTTPD server and do not require a live
 * Slack connection or connected agents.</p>
 */
public class SlackApiWorkstreamTest extends TestSuiteBase {

    /**
     * POST /api/workstreams registers a new workstream and returns its ID.
     * Uses a null bot token so no real Slack channel is created.
     */
    @Test(timeout = 10000)
    public void testApiRegisterWorkstream() throws Exception {
        SlackNotifier notifier = new SlackNotifier(null);
        SlackListener listener = new SlackListener(notifier);

        FlowTreeApiEndpoint endpoint = new FlowTreeApiEndpoint(0, notifier);
        endpoint.setListener(listener);
        endpoint.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);

        try {
            int port = endpoint.getListeningPort();
            String body = "{\"defaultBranch\":\"project/plan-20260223-test\","
                + "\"baseBranch\":\"master\","
                + "\"planningDocument\":\"docs/plans/PLAN-20260223-test.md\","
                + "\"channelName\":\"w-project-plan-20260223-test\"}";

            HttpURLConnection conn = (HttpURLConnection) new URL(
                    "http://localhost:" + port + "/api/workstreams").openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            assertEquals(200, conn.getResponseCode());

            String response = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(response.contains("\"ok\":true"));
            assertTrue(response.contains("\"workstreamId\""));
            assertTrue(response.contains("\"channelName\":\"w-project-plan-20260223-test\""));

            Workstream registered = notifier.findWorkstreamByBranch("project/plan-20260223-test");
            assertNotNull("Workstream should be findable by branch", registered);
            assertEquals("master", registered.getBaseBranch());
            assertEquals("docs/plans/PLAN-20260223-test.md", registered.getPlanningDocument());
            assertTrue(registered.isPushToOrigin());
        } finally {
            endpoint.stop();
        }
    }

    /** POST /api/workstreams requires defaultBranch and returns 400 when absent. */
    @Test(timeout = 10000)
    public void testApiRegisterWorkstreamMissingBranch() throws Exception {
        SlackNotifier notifier = new SlackNotifier(null);

        FlowTreeApiEndpoint endpoint = new FlowTreeApiEndpoint(0, notifier);
        endpoint.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);

        try {
            int port = endpoint.getListeningPort();
            String body = "{\"baseBranch\":\"master\"}";

            HttpURLConnection conn = (HttpURLConnection) new URL(
                    "http://localhost:" + port + "/api/workstreams").openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            assertEquals(400, conn.getResponseCode());

            String error = new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(error.contains("defaultBranch"));
        } finally {
            endpoint.stop();
        }
    }

    /** POST /api/workstreams/{id}/update updates an existing workstream in place. */
    @Test(timeout = 10000)
    public void testApiUpdateWorkstream() throws Exception {
        SlackNotifier notifier = new SlackNotifier(null);
        SlackListener listener = new SlackListener(notifier);

        Workstream workstream = new Workstream(null, "w-test");
        workstream.setDefaultBranch("project/test");
        notifier.registerWorkstream(workstream);

        FlowTreeApiEndpoint endpoint = new FlowTreeApiEndpoint(0, notifier);
        endpoint.setListener(listener);
        endpoint.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);

        try {
            int port = endpoint.getListeningPort();
            String body = "{\"channelId\":\"C_NEW_123\",\"channelName\":\"#w-test-updated\","
                + "\"planningDocument\":\"docs/plans/PLAN-updated.md\"}";

            URL url = URI.create("http://localhost:" + port
                + "/api/workstreams/" + workstream.getWorkstreamId() + "/update").toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));

            assertEquals(200, conn.getResponseCode());
            String response = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(response.contains("\"ok\":true"));

            assertEquals("C_NEW_123", workstream.getChannelId());
            assertEquals("#w-test-updated", workstream.getChannelName());
            assertEquals("docs/plans/PLAN-updated.md", workstream.getPlanningDocument());
        } finally {
            endpoint.stop();
        }
    }

    /** POST /api/workstreams/{id}/update returns 400 for an unknown workstream ID. */
    @Test(timeout = 10000)
    public void testApiUpdateWorkstreamNotFound() throws Exception {
        SlackNotifier notifier = new SlackNotifier(null);

        FlowTreeApiEndpoint endpoint = new FlowTreeApiEndpoint(0, notifier);
        endpoint.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);

        try {
            int port = endpoint.getListeningPort();
            String body = "{\"channelId\":\"C_NEW\"}";

            URL url = URI.create("http://localhost:" + port
                + "/api/workstreams/nonexistent/update").toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));

            assertEquals(400, conn.getResponseCode());
            String response = new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(response.contains("Unknown workstream"));
        } finally {
            endpoint.stop();
        }
    }

    /**
     * Verifies the full lifecycle of the accept-automated-jobs controller config:
     * default is false, POST with accept:true enables it, GET reflects the change,
     * and POST with accept:false disables it again.
     */
    @Test(timeout = 10000)
    public void testAcceptAutomatedJobsConfig() throws Exception {
        SlackNotifier notifier = new SlackNotifier(null);
        FlowTreeApiEndpoint endpoint = new FlowTreeApiEndpoint(0, notifier);
        endpoint.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);

        try {
            int port = endpoint.getListeningPort();
            String configUrl = "http://localhost:" + port
                    + "/api/config/accept-automated-jobs";

            assertFalse("Default should reject automated jobs",
                    endpoint.isAcceptAutomatedJobs());

            HttpURLConnection getConn = (HttpURLConnection)
                    new URL(configUrl).openConnection();
            getConn.setRequestMethod("GET");
            assertEquals(200, getConn.getResponseCode());
            String getResponse = new String(
                    getConn.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue("GET should report false",
                    getResponse.contains("\"acceptAutomatedJobs\":false"));

            HttpURLConnection postConn = (HttpURLConnection)
                    new URL(configUrl).openConnection();
            postConn.setRequestMethod("POST");
            postConn.setDoOutput(true);
            postConn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = postConn.getOutputStream()) {
                os.write("{\"accept\":true}".getBytes(StandardCharsets.UTF_8));
            }
            assertEquals(200, postConn.getResponseCode());
            String postResponse = new String(
                    postConn.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue("POST response should confirm true",
                    postResponse.contains("\"acceptAutomatedJobs\":true"));
            assertTrue("Endpoint field should now be true",
                    endpoint.isAcceptAutomatedJobs());

            HttpURLConnection getConn2 = (HttpURLConnection)
                    new URL(configUrl).openConnection();
            getConn2.setRequestMethod("GET");
            assertEquals(200, getConn2.getResponseCode());
            String getResponse2 = new String(
                    getConn2.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue("GET should report true after update",
                    getResponse2.contains("\"acceptAutomatedJobs\":true"));

            HttpURLConnection postConn2 = (HttpURLConnection)
                    new URL(configUrl).openConnection();
            postConn2.setRequestMethod("POST");
            postConn2.setDoOutput(true);
            postConn2.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = postConn2.getOutputStream()) {
                os.write("{\"accept\":false}".getBytes(StandardCharsets.UTF_8));
            }
            assertEquals(200, postConn2.getResponseCode());
            String postResponse2 = new String(
                    postConn2.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue("POST response should confirm false",
                    postResponse2.contains("\"acceptAutomatedJobs\":false"));
            assertFalse("Endpoint field should now be false",
                    endpoint.isAcceptAutomatedJobs());
        } finally {
            endpoint.stop();
        }
    }

    /** channelNameFromBranch: {@code feature/foo} produces {@code w-foo}. */
    @Test(timeout = 10000)
    public void testChannelNameFromBranchStandardCase() {
        assertEquals("w-foo", SlackNotifier.channelNameFromBranch("feature/foo"));
    }

    /** channelNameFromBranch: {@code release/v2/bar} uses last path segment, producing {@code w-bar}. */
    @Test(timeout = 10000)
    public void testChannelNameFromBranchMultipleSlashes() {
        assertEquals("w-bar", SlackNotifier.channelNameFromBranch("release/v2/bar"));
    }

    /** channelNameFromBranch: {@code singlename} (no slash) produces {@code w-singlename}. */
    @Test(timeout = 10000)
    public void testChannelNameFromBranchNoSlash() {
        assertEquals("w-singlename", SlackNotifier.channelNameFromBranch("singlename"));
    }

    /** channelNameFromBranch: branch ending with {@code /} is malformed and returns null. */
    @Test(timeout = 10000)
    public void testChannelNameFromBranchMalformed() {
        assertNull(SlackNotifier.channelNameFromBranch("feature/"));
    }

    /** channelNameFromBranch sanitizes uppercase letters, spaces, and underscores to lowercase hyphens. */
    @Test(timeout = 10000)
    public void testChannelNameFromBranchSanitization() {
        assertEquals("w-my-xyz", SlackNotifier.channelNameFromBranch("feature/My XYZ!"));
        assertEquals("w-audio-loop", SlackNotifier.channelNameFromBranch("feature/audio_loop"));
        assertEquals("w-foo-bar", SlackNotifier.channelNameFromBranch("feature/foo--bar"));
    }

    /** channelNameFromBranch truncates names exceeding 80 characters. */
    @Test(timeout = 10000)
    public void testChannelNameFromBranchLengthTruncation() {
        String longSuffix = "a".repeat(79);
        String result = SlackNotifier.channelNameFromBranch("feature/" + longSuffix);
        assertNotNull(result);
        assertTrue("Channel name must be at most 80 chars", result.length() <= 80);
        assertTrue(result.startsWith("w-"));
    }

    /**
     * When channelName is omitted from the registration body, the controller
     * auto-generates one from the branch name.
     */
    @Test(timeout = 10000)
    public void testApiRegisterWorkstreamAutoChannel() throws Exception {
        SlackNotifier notifier = new SlackNotifier(null);
        SlackListener listener = new SlackListener(notifier);

        FlowTreeApiEndpoint endpoint = new FlowTreeApiEndpoint(0, notifier);
        endpoint.setListener(listener);
        endpoint.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);

        try {
            int port = endpoint.getListeningPort();
            String body = "{\"defaultBranch\":\"feature/auto-gen\","
                    + "\"baseBranch\":\"master\"}";

            HttpURLConnection conn = (HttpURLConnection) new URL(
                    "http://localhost:" + port + "/api/workstreams").openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            assertEquals(200, conn.getResponseCode());
            String response = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue("Response must be ok", response.contains("\"ok\":true"));
            assertTrue("Response must contain workstreamId", response.contains("\"workstreamId\""));
            assertTrue("Response must contain auto-generated channelName",
                    response.contains("\"channelName\":\"w-auto-gen\""));

            Workstream registered = notifier.findWorkstreamByBranch("feature/auto-gen");
            assertNotNull("Workstream should be registered", registered);
            String cn = registered.getChannelName();
            assertNotNull("Channel name should be set", cn);
            assertTrue("Channel name should be w-auto-gen", cn.equals("w-auto-gen"));
        } finally {
            endpoint.stop();
        }
    }

    /** An explicit channelName in the request body overrides auto-generation. */
    @Test(timeout = 10000)
    public void testApiRegisterWorkstreamExplicitChannelOverrides() throws Exception {
        SlackNotifier notifier = new SlackNotifier(null);
        SlackListener listener = new SlackListener(notifier);

        FlowTreeApiEndpoint endpoint = new FlowTreeApiEndpoint(0, notifier);
        endpoint.setListener(listener);
        endpoint.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);

        try {
            int port = endpoint.getListeningPort();
            String body = "{\"defaultBranch\":\"feature/some-branch\","
                    + "\"baseBranch\":\"master\","
                    + "\"channelName\":\"my-custom-channel\"}";

            HttpURLConnection conn = (HttpURLConnection) new URL(
                    "http://localhost:" + port + "/api/workstreams").openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            assertEquals(200, conn.getResponseCode());
            String response = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(response.contains("\"channelName\":\"my-custom-channel\""));

            Workstream registered = notifier.findWorkstreamByBranch("feature/some-branch");
            assertNotNull(registered);
            assertEquals("my-custom-channel", registered.getChannelName());
        } finally {
            endpoint.stop();
        }
    }

    /**
     * When two registrations would produce the same auto-generated channel name,
     * the second gets a {@code -2} suffix.
     */
    @Test(timeout = 10000)
    public void testApiRegisterWorkstreamCollisionHandling() throws Exception {
        SlackNotifier notifier = new SlackNotifier(null);
        SlackListener listener = new SlackListener(notifier);

        Workstream existing = new Workstream(null, "w-foo");
        existing.setDefaultBranch("feature/foo");
        notifier.registerWorkstream(existing);

        FlowTreeApiEndpoint endpoint = new FlowTreeApiEndpoint(0, notifier);
        endpoint.setListener(listener);
        endpoint.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);

        try {
            int port = endpoint.getListeningPort();
            String body = "{\"defaultBranch\":\"bugfix/foo\","
                    + "\"baseBranch\":\"master\"}";

            HttpURLConnection conn = (HttpURLConnection) new URL(
                    "http://localhost:" + port + "/api/workstreams").openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            assertEquals(200, conn.getResponseCode());
            String response = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(response.contains("\"ok\":true"));
            assertTrue("Second registration should get -2 suffix",
                    response.contains("\"channelName\":\"w-foo-2\""));

            Workstream second = notifier.findWorkstreamByBranch("bugfix/foo");
            assertNotNull(second);
            assertEquals("w-foo-2", second.getChannelName());
        } finally {
            endpoint.stop();
        }
    }

    /**
     * Two workstreams sharing a defaultBranch but pointing at different repositories
     * must not be cross-routed: a /submit request without a repoUrl disambiguator
     * is rejected as ambiguous.
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
            String error = new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
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
     * When two workstreams share a defaultBranch and the caller supplies a matching
     * repoUrl, the controller routes to the correct workstream without ambiguity.
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
            String error = new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue("Resolver must not have flagged ambiguity; got: " + error,
                    !error.contains("Ambiguous"));
            assertTrue("Submission must reach the post-resolution server check; got: "
                    + error, error.contains("No FlowTree server"));
        } finally {
            endpoint.stop();
        }
    }

    /**
     * When the caller supplies a repoUrl that matches no registered workstream on the
     * named branch, resolution fails with an explicit error rather than silently
     * cross-routing to a workstream that merely shares the branch name.
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
            String error = new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue("Should report no workstream found, not silently cross-route; got: "
                    + error, error.contains("No workstream found for branch"));
        } finally {
            endpoint.stop();
        }
    }

    /** POST /api/workstreams with a branch ending in {@code /} is rejected as malformed. */
    @Test(timeout = 10000)
    public void testApiRegisterWorkstreamMalformedBranch() throws Exception {
        SlackNotifier notifier = new SlackNotifier(null);

        FlowTreeApiEndpoint endpoint = new FlowTreeApiEndpoint(0, notifier);
        endpoint.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);

        try {
            int port = endpoint.getListeningPort();
            String body = "{\"defaultBranch\":\"feature/\","
                    + "\"baseBranch\":\"master\"}";

            HttpURLConnection conn = (HttpURLConnection) new URL(
                    "http://localhost:" + port + "/api/workstreams").openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            assertEquals(400, conn.getResponseCode());
            String error = new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue("Error should mention malformed branch", error.contains("malformed"));
        } finally {
            endpoint.stop();
        }
    }

    /**
     * Tests that registerAndPersistWorkstream adds the workstream to both
     * the in-memory registry and the configuration model.
     */
    @Test(timeout = 10000)
    public void testRegisterAndPersistWorkstream() throws IOException {
        SlackNotifier notifier = new SlackNotifier(null);
        SlackListener listener = new SlackListener(notifier);

        String yaml = "workstreams:\n"
            + "  - channelId: \"C_EXISTING\"\n"
            + "    channelName: \"#existing\"\n"
            + "    defaultBranch: \"feature/existing\"\n";

        File tempFile = File.createTempFile("workstream-test", ".yaml");
        tempFile.deleteOnExit();
        Files.write(tempFile.toPath(), yaml.getBytes());

        WorkstreamConfig config = WorkstreamConfig.loadFromYaml(tempFile);
        config.ensureWorkstreamIds();
        listener.setWorkstreamConfig(config, tempFile);

        for (Workstream ws : config.toWorkstreams()) {
            listener.registerWorkstream(ws);
        }

        Workstream newWs = new Workstream(null, "w-new-project");
        newWs.setDefaultBranch("project/plan-test");
        newWs.setBaseBranch("master");
        newWs.setPlanningDocument("docs/plans/PLAN-test.md");

        listener.registerAndPersistWorkstream(newWs);

        Workstream found = notifier.findWorkstreamByBranch("project/plan-test");
        assertNotNull("New workstream should be registered", found);
        assertEquals("docs/plans/PLAN-test.md", found.getPlanningDocument());

        WorkstreamConfig reloaded = WorkstreamConfig.loadFromYaml(tempFile);
        assertEquals(2, reloaded.getWorkstreams().size());
    }
}
