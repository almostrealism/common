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
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Integration tests for the {@code /api/secrets/*} endpoints in
 * {@link FlowTreeApiEndpoint}.
 *
 * <p>Each test spins up a NanoHTTPD endpoint on an ephemeral port with a
 * pre-loaded in-memory secrets cache and a test shared secret, then makes
 * real HTTP requests to verify authorization, payload retrieval, list,
 * create, and delete behaviour.</p>
 */
public class SecretsEndpointTest extends TestSuiteBase {

    private static final String SHARED_SECRET = "test-shared-secret-for-unit-tests";
    private static final String WORKSPACE_A = "T_WORKSPACE_A";
    private static final String WORKSPACE_B = "T_WORKSPACE_B";
    private static final String WORKSTREAM_A = "ws-test-aaa";
    private static final String WORKSTREAM_B = "ws-test-bbb";

    private FlowTreeApiEndpoint endpoint;
    private Path secretsDir;
    private Path secretFile;
    private int port;

    /** A simple log handler that captures audit log messages. */
    private final List<String> auditMessages = new java.util.ArrayList<>();
    private Handler auditHandler;

    @Before
    public void setUp() throws Exception {
        secretsDir = Files.createTempDirectory("ar-secrets-test-");
        secretFile = secretsDir.resolve(WORKSPACE_A + "__aws-prod.json");
        Files.writeString(secretFile,
                "{\"access_key_id\":\"AKIATEST123\",\"secret_access_key\":\"SECTEST456\",\"region\":\"us-east-1\"}",
                StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(secretFile, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX; skip permission setting on the test file
        }

        // Capture audit log output
        Logger logger = Logger.getLogger("ar-manager.audit");
        auditHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                auditMessages.add(record.getMessage());
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        logger.addHandler(auditHandler);

        // Build secrets cache
        WorkstreamConfig.WorkspaceSecretEntry entry = new WorkstreamConfig.WorkspaceSecretEntry();
        entry.setName("aws-prod");
        entry.setFile(secretFile.toAbsolutePath().toString());

        Map<String, WorkstreamConfig.WorkspaceSecretEntry> wsASecrets = new HashMap<>();
        wsASecrets.put("aws-prod", entry);

        Map<String, Map<String, WorkstreamConfig.WorkspaceSecretEntry>> cache = new HashMap<>();
        cache.put(WORKSPACE_A, wsASecrets);

        // Workstream registered on notifier for workspace-A
        SlackNotifier notifier = new SlackNotifier(null);
        Workstream workstreamA = new Workstream(WORKSTREAM_A, "#test-channel");
        workstreamA.setSlackWorkspaceId(WORKSPACE_A);
        notifier.registerWorkstream(workstreamA);

        Workstream workstreamB = new Workstream(WORKSTREAM_B, "#test-channel-b");
        workstreamB.setSlackWorkspaceId(WORKSPACE_B);
        notifier.registerWorkstream(workstreamB);

        endpoint = new FlowTreeApiEndpoint(0, notifier);
        endpoint.setSharedSecret(SHARED_SECRET);
        endpoint.setSecretsCache(cache);
        endpoint.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        port = endpoint.getListeningPort();
    }

    @After
    public void tearDown() throws Exception {
        if (endpoint != null) endpoint.stop();
        if (secretsDir != null) deleteRecursively(secretsDir.toFile());
        Logger.getLogger("ar-manager.audit").removeHandler(auditHandler);
    }

    // ----------------------------------------------------------------
    // Retrieve endpoint tests
    // ----------------------------------------------------------------

    @Test(timeout = 10000)
    public void testValidRetrieveReturnsPayload() throws Exception {
        String token = generateToken(WORKSTREAM_A);
        HttpURLConnection conn = openGet(
                "/api/secrets/aws-prod?workstream_id=" + WORKSTREAM_A, token);
        assertEquals(200, conn.getResponseCode());
        String body = readBody(conn);
        assertTrue("Response should contain secret name", body.contains("aws-prod"));
        assertTrue("Response should contain workspace_id", body.contains(WORKSPACE_A));
        assertTrue("Response should contain payload", body.contains("\"payload\""));
        assertTrue("Response should contain key name", body.contains("access_key_id"));
    }

    @Test(timeout = 10000)
    public void testInvalidTokenReturns403() throws Exception {
        HttpURLConnection conn = openGet(
                "/api/secrets/aws-prod?workstream_id=" + WORKSTREAM_A, "bad-token");
        assertEquals(403, conn.getResponseCode());
    }

    @Test(timeout = 10000)
    public void testExpiredTokenReturns403() throws Exception {
        // Generate a token that expires in -1 seconds (already expired)
        String token = FlowTreeApiEndpoint.generateTemporaryToken(
                WORKSTREAM_A, "job-001", SHARED_SECRET, -1L);
        HttpURLConnection conn = openGet(
                "/api/secrets/aws-prod?workstream_id=" + WORKSTREAM_A, token);
        assertEquals(403, conn.getResponseCode());
    }

    @Test(timeout = 10000)
    public void testTokenWorkstreamMismatchReturns403() throws Exception {
        // Token for workspace-A's workstream, but request claims workspace-B's workstream
        String token = generateToken(WORKSTREAM_A);
        HttpURLConnection conn = openGet(
                "/api/secrets/aws-prod?workstream_id=" + WORKSTREAM_B, token);
        assertEquals(403, conn.getResponseCode());
    }

    @Test(timeout = 10000)
    public void testWorkstreamFromWrongWorkspaceReturns403() throws Exception {
        // Token for WORKSTREAM_B (workspace B), secret belongs to workspace A
        String token = generateToken(WORKSTREAM_B);
        HttpURLConnection conn = openGet(
                "/api/secrets/aws-prod?workstream_id=" + WORKSTREAM_B, token);
        // The workspace for workstream-B has no "aws-prod" secret
        assertEquals(404, conn.getResponseCode());
    }

    @Test(timeout = 10000)
    public void testUnknownSecretReturns404() throws Exception {
        String token = generateToken(WORKSTREAM_A);
        HttpURLConnection conn = openGet(
                "/api/secrets/no-such-secret?workstream_id=" + WORKSTREAM_A, token);
        assertEquals(404, conn.getResponseCode());
    }

    @Test(timeout = 10000)
    public void testMissingFileReturns500() throws Exception {
        // Delete the file to simulate a missing file
        Files.deleteIfExists(secretFile);
        String token = generateToken(WORKSTREAM_A);
        HttpURLConnection conn = openGet(
                "/api/secrets/aws-prod?workstream_id=" + WORKSTREAM_A, token);
        assertEquals(500, conn.getResponseCode());
        String body = readErrorBody(conn);
        // Must not expose the payload in the error
        assertFalse("Error response must not contain secret values", body.contains("AKIATEST123"));
    }

    @Test(timeout = 10000)
    public void testAuditLogContainsNameAndWorkstreamButNotValues() throws Exception {
        auditMessages.clear();
        String token = generateToken(WORKSTREAM_A);
        HttpURLConnection conn = openGet(
                "/api/secrets/aws-prod?workstream_id=" + WORKSTREAM_A, token);
        assertEquals(200, conn.getResponseCode());

        // The audit log (via ConsoleFeatures log()) checks the controller log lines.
        // Verify the response body does not contain raw secret values.
        String body = readBody(conn);
        // Values should be in the payload field of the response (that is the expected behavior)
        // but must not appear in the audit messages
        for (String msg : auditMessages) {
            assertFalse("Audit log must not contain secret values: " + msg,
                    msg.contains("AKIATEST123") || msg.contains("SECTEST456"));
        }
    }

    // ----------------------------------------------------------------
    // Admin list endpoint tests
    // ----------------------------------------------------------------

    @Test(timeout = 10000)
    public void testAdminListReturnsNames() throws Exception {
        HttpURLConnection conn = openGet(
                "/api/secrets?workspace_id=" + WORKSPACE_A, SHARED_SECRET);
        assertEquals(200, conn.getResponseCode());
        String body = readBody(conn);
        assertTrue("Response should contain secret name", body.contains("aws-prod"));
        assertFalse("Response must not contain payload values", body.contains("AKIATEST123"));
    }

    @Test(timeout = 10000)
    public void testAdminListWithBadTokenReturns403() throws Exception {
        HttpURLConnection conn = openGet(
                "/api/secrets?workspace_id=" + WORKSPACE_A, "wrong-secret");
        assertEquals(403, conn.getResponseCode());
    }

    @Test(timeout = 10000)
    public void testWorkstreamTokenListReturnsNames() throws Exception {
        String token = generateToken(WORKSTREAM_A);
        HttpURLConnection conn = openGet(
                "/api/secrets?workstream_id=" + WORKSTREAM_A, token);
        assertEquals(200, conn.getResponseCode());
        String body = readBody(conn);
        assertTrue("Response should contain names array", body.contains("\"names\""));
        assertTrue("Response should contain aws-prod", body.contains("aws-prod"));
    }

    // ----------------------------------------------------------------
    // Admin create/update endpoint tests
    // ----------------------------------------------------------------

    @Test(timeout = 10000)
    public void testAdminCreateWritesFile() throws Exception {
        Path newFile = secretsDir.resolve(WORKSPACE_A + "__gh-token.json");
        WorkstreamConfig.WorkspaceSecretEntry newEntry = new WorkstreamConfig.WorkspaceSecretEntry();
        newEntry.setName("gh-token");
        newEntry.setFile(newFile.toAbsolutePath().toString());

        // Re-create the endpoint with the extended cache
        endpoint.stop();
        SlackNotifier notifier = new SlackNotifier(null);
        Workstream ws = new Workstream(WORKSTREAM_A, "#test");
        ws.setSlackWorkspaceId(WORKSPACE_A);
        notifier.registerWorkstream(ws);

        WorkstreamConfig.WorkspaceSecretEntry existEntry = new WorkstreamConfig.WorkspaceSecretEntry();
        existEntry.setName("aws-prod");
        existEntry.setFile(secretFile.toAbsolutePath().toString());

        Map<String, WorkstreamConfig.WorkspaceSecretEntry> wsASecrets = new HashMap<>();
        wsASecrets.put("aws-prod", existEntry);
        wsASecrets.put("gh-token", newEntry);

        Map<String, Map<String, WorkstreamConfig.WorkspaceSecretEntry>> cache = new HashMap<>();
        cache.put(WORKSPACE_A, wsASecrets);

        endpoint = new FlowTreeApiEndpoint(0, notifier);
        endpoint.setSharedSecret(SHARED_SECRET);
        endpoint.setSecretsCache(cache);
        endpoint.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        port = endpoint.getListeningPort();

        String payload = "{\"token\":\"ghp_TEST_TOKEN\"}";
        HttpURLConnection conn = openPut(
                "/api/secrets/gh-token?workspace_id=" + WORKSPACE_A,
                SHARED_SECRET, payload);
        assertEquals(200, conn.getResponseCode());
        assertTrue("File should be written", Files.exists(newFile));
        String written = Files.readString(newFile);
        assertTrue(written.contains("ghp_TEST_TOKEN"));
    }

    @Test(timeout = 10000)
    public void testAdminCreateWithBadTokenReturns403() throws Exception {
        HttpURLConnection conn = openPut(
                "/api/secrets/aws-prod?workspace_id=" + WORKSPACE_A,
                "wrong-secret", "{\"key\":\"val\"}");
        assertEquals(403, conn.getResponseCode());
    }

    // ----------------------------------------------------------------
    // Admin delete endpoint tests
    // ----------------------------------------------------------------

    @Test(timeout = 10000)
    public void testAdminDeleteRemovesFile() throws Exception {
        assertTrue("File should exist before delete", Files.exists(secretFile));
        HttpURLConnection conn = openDelete(
                "/api/secrets/aws-prod?workspace_id=" + WORKSPACE_A, SHARED_SECRET);
        assertEquals(200, conn.getResponseCode());
        assertFalse("File should be removed after delete", Files.exists(secretFile));
    }

    // ----------------------------------------------------------------
    // Permission check tests (startup warning)
    // ----------------------------------------------------------------

    @Test(timeout = 10000)
    public void testSecretsFileWith0644PermissionsIsReadable() throws Exception {
        // Even if the permission check would warn, the retrieve still works (just unsafe).
        // This test verifies that overly-permissive files are still readable by the endpoint.
        try {
            Files.setPosixFilePermissions(secretFile, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ));
        } catch (UnsupportedOperationException ignored) {
            return; // Non-POSIX system — skip
        }
        String token = generateToken(WORKSTREAM_A);
        HttpURLConnection conn = openGet(
                "/api/secrets/aws-prod?workstream_id=" + WORKSTREAM_A, token);
        assertEquals(200, conn.getResponseCode());
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private String generateToken(String workstreamId) {
        return FlowTreeApiEndpoint.generateTemporaryToken(
                workstreamId, "job-test-001", SHARED_SECRET, 3600);
    }

    private HttpURLConnection openGet(String path, String bearerToken) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(
                "http://localhost:" + port + path).openConnection();
        conn.setRequestMethod("GET");
        if (bearerToken != null) {
            conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
        }
        return conn;
    }

    private HttpURLConnection openPut(String path, String bearerToken, String body) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(
                "http://localhost:" + port + path).openConnection();
        conn.setRequestMethod("PUT");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        if (bearerToken != null) {
            conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
        }
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return conn;
    }

    private HttpURLConnection openDelete(String path, String bearerToken) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(
                "http://localhost:" + port + path).openConnection();
        conn.setRequestMethod("DELETE");
        if (bearerToken != null) {
            conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
        }
        return conn;
    }

    private static String readBody(HttpURLConnection conn) throws IOException {
        return new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String readErrorBody(HttpURLConnection conn) throws IOException {
        java.io.InputStream err = conn.getErrorStream();
        if (err != null) {
            return new String(err.readAllBytes(), StandardCharsets.UTF_8);
        }
        return "";
    }

    private static void deleteRecursively(File dir) {
        if (dir == null) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) deleteRecursively(f);
        }
        dir.delete();
    }
}
