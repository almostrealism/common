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
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Method;
import fi.iki.elonen.NanoHTTPD.Response;
import io.flowtree.JsonFieldExtractor;
import org.almostrealism.io.ConsoleFeatures;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import io.flowtree.workstream.Workstream;
import io.flowtree.slack.SlackNotifier;
import io.flowtree.slack.NotifierRegistry;
import io.flowtree.workstream.WorkstreamConfig;
import io.flowtree.github.GitHubProxyHandler;

/**
 * Handles all {@code /api/secrets} and {@code /api/secrets/{name}} requests
 * for {@link FlowTreeApiEndpoint}.
 *
 * <p>Routes dispatched by this handler:</p>
 * <ul>
 *   <li>{@code GET /api/secrets?workstream_id=...} — list secret names (workstream token)</li>
 *   <li>{@code GET /api/secrets/{name}?workstream_id=...} — retrieve payload (workstream token)</li>
 *   <li>{@code GET /api/secrets?workspace_id=...} — list names (admin token)</li>
 *   <li>{@code PUT /api/secrets/{name}?workspace_id=...} — create/update (admin token)</li>
 *   <li>{@code DELETE /api/secrets/{name}?workspace_id=...} — delete (admin token)</li>
 * </ul>
 *
 * <p>Also exposes {@link #generateTemporaryToken} for use by
 * {@link FlowTreeApiEndpoint} and {@link SlackListener} when issuing
 * HMAC-signed temporary tokens for agent authentication.</p>
 *
 * @author Michael Murray
 * @see FlowTreeApiEndpoint
 */
public class SecretsRequestHandler implements ConsoleFeatures {

    /** Registry used to resolve the Slack workspace for a given workstream. */
    private final NotifierRegistry notifiers;

    /** Shared secret for HMAC token validation and admin-token authentication. */
    private String sharedSecret;

    /** In-memory secrets index, keyed by workspace ID then secret name. */
    private Map<String, Map<String, WorkstreamConfig.WorkspaceSecretEntry>> secretsCache =
            new HashMap<>();

    /**
     * Constructs a handler backed by the supplied notifier registry.
     *
     * <p>The registry is used to resolve the owning Slack workspace for
     * workstream-scoped secret requests.</p>
     *
     * @param notifiers the notifier registry; must not be {@code null}
     */
    SecretsRequestHandler(NotifierRegistry notifiers) {
        this.notifiers = notifiers;
    }

    /**
     * Sets the shared secret used to validate HMAC temporary tokens and to
     * authenticate admin-level requests.
     *
     * @param sharedSecret the shared secret string
     */
    public void setSharedSecret(String sharedSecret) {
        this.sharedSecret = sharedSecret;
    }

    /**
     * Replaces the in-memory secrets index used by all {@code /api/secrets/*}
     * endpoints. Passing {@code null} clears the cache.
     *
     * @param cache workspace-ID → (secret-name → entry) map
     */
    public void setSecretsCache(
            Map<String, Map<String, WorkstreamConfig.WorkspaceSecretEntry>> cache) {
        this.secretsCache = cache != null ? cache : new HashMap<>();
    }

    /**
     * Returns an in-process secret-lookup function bound to a single workspace.
     *
     * <p>The returned function maps a secret name (e.g. {@code "openrouter-api-key"})
     * to the corresponding string value, reading the JSON payload file declared
     * for that secret in {@code workstreams.yaml}. The lookup convention is:
     * the payload's value for the key matching the secret name is returned when
     * present; otherwise, when the payload contains exactly one key, that single
     * value is returned (lets operators store {@code {"value": "sk-..."}} without
     * matching the name). Any other shape yields {@code null}.</p>
     *
     * <p>This entry point is intended for in-JVM callers on the controller
     * (e.g. an admin tool resolving a provider API key for a colocated
     * subprocess). Out-of-process callers continue to use the HTTP secrets
     * endpoints exclusively. Returned values must NEVER be logged, echoed
     * in tool output, or persisted outside the subprocess they configure;
     * the controller emits a {@code secret_access} audit line for every
     * retrieve but deliberately never records the payload itself.</p>
     *
     * @param workspaceId the workspace that owns the secrets to look up
     * @return a name → value function; missing-secret lookups return {@code null}
     */
    public Function<String, String> workspaceSecretLookup(String workspaceId) {
        return secretName -> readSecretValue(workspaceId, secretName);
    }

    /**
     * Resolves a single secret value, used by {@link #workspaceSecretLookup}.
     * Returns {@code null} when the workspace, the secret, or the file is
     * missing or unreadable. Errors are logged via {@link #warn} rather than
     * thrown so the caller can fall back to env-var resolution.
     *
     * @param workspaceId the workspace owning the secret
     * @param secretName  the declared secret name
     * @return the resolved value, or {@code null}
     */
    private String readSecretValue(String workspaceId, String secretName) {
        if (workspaceId == null || workspaceId.isEmpty()
                || secretName == null || secretName.isEmpty()) {
            return null;
        }
        Map<String, WorkstreamConfig.WorkspaceSecretEntry> wsSecrets =
                secretsCache.get(workspaceId);
        if (wsSecrets == null) return null;
        WorkstreamConfig.WorkspaceSecretEntry entry = wsSecrets.get(secretName);
        if (entry == null) return null;
        Map<String, String> payload;
        try {
            payload = readSecretPayload(entry.getFile());
        } catch (IOException e) {
            warn("Failed to read secret file for in-process lookup of "
                    + secretName + ": " + e.getMessage());
            return null;
        }
        if (payload == null || payload.isEmpty()) return null;
        String direct = payload.get(secretName);
        if (direct != null && !direct.isEmpty()) return direct;
        if (payload.size() == 1) {
            String singleton = payload.values().iterator().next();
            if (singleton != null && !singleton.isEmpty()) return singleton;
        }
        return null;
    }

    /**
     * Generates an HMAC-based temporary token for ar-manager authentication.
     *
     * <p>The token format is {@code armt_tmp_{base64url(hmac)}:{base64url(payload)}}
     * where the payload is {@code {workstreamId}:{jobId}:{expiry_epoch}} and the
     * HMAC is computed over the payload with SHA-256 using the shared secret.</p>
     *
     * @param workstreamId the workstream identifier
     * @param jobId        the job identifier
     * @param sharedSecret the shared secret (from {@code AR_MANAGER_SHARED_SECRET})
     * @param ttlSeconds   token time-to-live in seconds
     * @return the token string, or {@code null} if the shared secret is absent
     */
    public static String generateTemporaryToken(String workstreamId, String jobId,
                                         String sharedSecret, long ttlSeconds) {
        if (sharedSecret == null || sharedSecret.isEmpty()) return null;

        long expiry = System.currentTimeMillis() / 1000 + ttlSeconds;
        String payload = workstreamId + ":" + jobId + ":" + expiry;

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                sharedSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hmacBytes = mac.doFinal(
                payload.getBytes(StandardCharsets.UTF_8));

            String hmacB64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(hmacBytes);
            String payloadB64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));

            return "armt_tmp_" + hmacB64 + ":" + payloadB64;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Routes an incoming secrets request to the appropriate sub-handler based
     * on the HTTP method, URI path, and query parameters.
     *
     * @param session  the HTTP session
     * @param method   the HTTP method
     * @param uri      the full request URI (must start with {@code /api/secrets})
     * @param readBody callback to read the raw request body (used for PUT)
     * @param error    callback to build a 400 error response
     * @return a JSON response
     */
    public Response handle(IHTTPSession session, Method method, String uri,
                    GitHubProxyHandler.BodyReader readBody,
                    GitHubProxyHandler.ErrorResponder error) {
        String afterPrefix = uri.substring("/api/secrets".length());
        String secretName = null;
        if (afterPrefix.startsWith("/") && afterPrefix.length() > 1) {
            secretName = afterPrefix.substring(1);
        }

        Map<String, List<String>> params = session.getParameters();
        String workstreamId = getFirstParam(params, "workstream_id");
        String workspaceId = getFirstParam(params, "workspace_id");

        if (workstreamId != null && !workstreamId.isEmpty()) {
            String tokenWorkstreamId = extractWorkstreamIdFromTempToken(session);
            if (tokenWorkstreamId == null) {
                log("event=secret_retrieve secret=" + secretName
                        + " workstream_id=" + workstreamId + " result=AUTH_FAILED");
                return secretForbidden("Invalid or expired workstream token");
            }
            if (!tokenWorkstreamId.equals(workstreamId)) {
                log("event=secret_retrieve secret=" + secretName
                        + " workstream_id=" + workstreamId + " result=WORKSTREAM_MISMATCH");
                return secretForbidden("Token workstream does not match request workstream");
            }

            SlackNotifier notifier = notifiers.notifierFor(workstreamId);
            Workstream workstream = notifier != null ? notifier.getWorkstream(workstreamId) : null;
            if (workstream == null) {
                log("event=secret_retrieve secret=" + secretName
                        + " workstream_id=" + workstreamId + " result=UNKNOWN_WORKSTREAM");
                return secretForbidden("Unknown workstream: " + workstreamId);
            }
            String resolvedWorkspaceId = workstream.getWorkspaceId();

            if (Method.GET.equals(method) && secretName == null) {
                return handleListSecretNames(resolvedWorkspaceId);
            }

            if (Method.GET.equals(method) && secretName != null) {
                log("event=secret_retrieve secret=" + secretName
                        + " workstream_id=" + workstreamId
                        + " workspace_id=" + resolvedWorkspaceId + " result=OK");
                return handleRetrieveSecret(secretName, resolvedWorkspaceId, workstreamId);
            }
        }

        if (!isAdminToken(session)) {
            return secretForbidden("Admin token required");
        }

        if (Method.GET.equals(method) && workspaceId != null && secretName == null) {
            return handleListSecretNames(workspaceId);
        }

        if (Method.PUT.equals(method) && secretName != null && workspaceId != null) {
            return handleCreateOrUpdateSecret(session, secretName, workspaceId, readBody, error);
        }

        if (Method.DELETE.equals(method) && secretName != null && workspaceId != null) {
            return handleDeleteSecret(secretName, workspaceId);
        }

        return NanoHTTPD.newFixedLengthResponse(Response.Status.BAD_REQUEST,
                "application/json", "{\"ok\":false,\"error\":\"Invalid request\"}");
    }

    /**
     * Validates a Bearer token from the session as an HMAC temporary token.
     *
     * <p>Returns the workstream ID embedded in the token on success, or
     * {@code null} when the token is absent, malformed, expired, or has an
     * invalid signature. Does not verify workstream-ID equality — callers must
     * do that themselves.</p>
     *
     * @param session the incoming HTTP session
     * @return the workstream ID from the token payload, or {@code null}
     */
    private String extractWorkstreamIdFromTempToken(IHTTPSession session) {
        if (sharedSecret == null || sharedSecret.isEmpty()) {
            return null;
        }
        String auth = session.getHeaders().get("authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            return null;
        }
        String token = auth.substring(7).trim();
        if (!token.startsWith("armt_tmp_")) {
            return null;
        }
        String rest = token.substring("armt_tmp_".length());
        int colonIdx = rest.indexOf(':');
        if (colonIdx < 0) {
            return null;
        }
        String hmacB64 = rest.substring(0, colonIdx);
        String payloadB64 = rest.substring(colonIdx + 1);
        byte[] payloadBytes;
        byte[] tokenHmac;
        try {
            payloadBytes = Base64.getUrlDecoder().decode(padBase64(payloadB64));
            tokenHmac = Base64.getUrlDecoder().decode(padBase64(hmacB64));
        } catch (IllegalArgumentException e) {
            return null;
        }
        String payload = new String(payloadBytes, StandardCharsets.UTF_8);

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    sharedSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            if (!MessageDigest.isEqual(tokenHmac, expected)) {
                return null;
            }
        } catch (Exception e) {
            return null;
        }

        String[] parts = payload.split(":", 3);
        if (parts.length != 3) {
            return null;
        }
        long expiry;
        try {
            expiry = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (System.currentTimeMillis() / 1000 > expiry) {
            return null;
        }
        return parts[0];
    }

    /**
     * Pads a Base64URL-encoded string to a multiple of 4 characters for
     * standard Base64 decoding.
     *
     * @param s the Base64URL string to pad
     * @return the padded string
     */
    private static String padBase64(String s) {
        int pad = (4 - s.length() % 4) % 4;
        return s + "=".repeat(pad);
    }

    /**
     * Returns {@code true} when the request carries the admin shared secret as
     * a Bearer token. When no shared secret is configured the request cannot be
     * authenticated as admin and this returns {@code false}: the controller
     * fails closed rather than treating an unconfigured deployment as
     * implicitly trusted. A request with no shared secret was previously
     * granted admin access, which let a caller act with no identity at all —
     * the same tokenless escape hatch the ar-manager HTTP-only model removes.
     *
     * @param session the incoming HTTP session
     * @return {@code true} if the request is authenticated as admin
     */
    private boolean isAdminToken(IHTTPSession session) {
        if (sharedSecret == null || sharedSecret.isEmpty()) {
            return false;
        }
        String auth = session.getHeaders().get("authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            return false;
        }
        String token = auth.substring(7).trim();
        return MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8),
                sharedSecret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Returns the list of declared secret names for the given workspace.
     *
     * @param workspaceId the Slack workspace ID
     * @return JSON response with {@code {"names":[...]}}
     */
    private Response handleListSecretNames(String workspaceId) {
        Map<String, WorkstreamConfig.WorkspaceSecretEntry> wsSecrets =
                secretsCache.getOrDefault(workspaceId, new HashMap<>());
        StringBuilder json = new StringBuilder("{\"names\":[");
        boolean first = true;
        for (String name : wsSecrets.keySet()) {
            if (!first) json.append(",");
            first = false;
            json.append("\"").append(JsonFieldExtractor.escapeJson(name)).append("\"");
        }
        json.append("]}");
        return NanoHTTPD.newFixedLengthResponse(
                Response.Status.OK, "application/json", json.toString());
    }

    /**
     * Retrieves the payload of a single named secret and returns it as JSON.
     *
     * @param secretName   the secret name
     * @param workspaceId  the owning workspace ID
     * @param workstreamId the requesting workstream ID (for audit log only)
     * @return JSON response with the payload map, or an error response
     */
    private Response handleRetrieveSecret(
            String secretName, String workspaceId, String workstreamId) {
        Map<String, WorkstreamConfig.WorkspaceSecretEntry> wsSecrets =
                secretsCache.get(workspaceId);
        if (wsSecrets == null || !wsSecrets.containsKey(secretName)) {
            log("event=secret_retrieve secret=" + secretName
                    + " workstream_id=" + workstreamId
                    + " workspace_id=" + workspaceId + " result=NOT_FOUND");
            return NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND,
                    "application/json",
                    "{\"ok\":false,\"error\":\"Secret not found: "
                            + JsonFieldExtractor.escapeJson(secretName) + "\"}");
        }

        WorkstreamConfig.WorkspaceSecretEntry entry = wsSecrets.get(secretName);
        Map<String, String> payload;
        try {
            payload = readSecretPayload(entry.getFile());
        } catch (IOException e) {
            warn("Failed to read secret file for " + secretName + ": " + e.getMessage());
            log("event=secret_retrieve secret=" + secretName
                    + " workstream_id=" + workstreamId
                    + " workspace_id=" + workspaceId + " result=FILE_ERROR");
            return NanoHTTPD.newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                    "application/json", "{\"ok\":false,\"error\":\"Failed to read secret\"}");
        }

        StringBuilder json = new StringBuilder();
        json.append("{\"name\":\"").append(JsonFieldExtractor.escapeJson(secretName)).append("\"");
        json.append(",\"workspace_id\":\"").append(JsonFieldExtractor.escapeJson(workspaceId)).append("\"");
        json.append(",\"payload\":{");
        boolean first = true;
        for (Map.Entry<String, String> kv : payload.entrySet()) {
            if (!first) json.append(",");
            first = false;
            json.append("\"").append(JsonFieldExtractor.escapeJson(kv.getKey())).append("\"");
            json.append(":\"").append(JsonFieldExtractor.escapeJson(kv.getValue())).append("\"");
        }
        json.append("}}");
        return NanoHTTPD.newFixedLengthResponse(
                Response.Status.OK, "application/json", json.toString());
    }

    /**
     * Creates or fully replaces a secret payload, writing it atomically to the
     * declared file path with {@code 0600} permissions.
     *
     * @param session     the HTTP session carrying the JSON body
     * @param secretName  the secret name
     * @param workspaceId the owning workspace ID
     * @param readBody    callback to read the raw request body
     * @param error       callback to build an error response
     * @return JSON response
     */
    private Response handleCreateOrUpdateSecret(IHTTPSession session,
                                                String secretName,
                                                String workspaceId,
                                                GitHubProxyHandler.BodyReader readBody,
                                                GitHubProxyHandler.ErrorResponder error) {
        String body = readBody.read(session);
        if (body == null || body.isBlank()) {
            return error.respond("Missing request body");
        }
        Map<String, String> payload;
        try {
            ObjectMapper mapper = new ObjectMapper();
            payload = mapper.readValue(body,
                    new TypeReference<Map<String, String>>() { });
        } catch (Exception e) {
            return error.respond("Invalid JSON payload: " + e.getMessage());
        }

        Map<String, WorkstreamConfig.WorkspaceSecretEntry> wsSecrets =
                secretsCache.get(workspaceId);
        if (wsSecrets == null || !wsSecrets.containsKey(secretName)) {
            return NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND,
                    "application/json",
                    "{\"ok\":false,\"error\":\"Secret not declared in config: "
                            + JsonFieldExtractor.escapeJson(secretName) + "\"}");
        }
        WorkstreamConfig.WorkspaceSecretEntry entry = wsSecrets.get(secretName);
        try {
            writeSecretPayload(entry.getFile(), payload);
        } catch (IOException e) {
            warn("Failed to write secret " + secretName + ": " + e.getMessage());
            return NanoHTTPD.newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                    "application/json", "{\"ok\":false,\"error\":\"Failed to write secret\"}");
        }
        log("Secret updated: name=" + secretName + " workspace=" + workspaceId);
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK,
                "application/json",
                "{\"ok\":true,\"name\":\"" + JsonFieldExtractor.escapeJson(secretName) + "\"}");
    }

    /**
     * Deletes the on-disk file for a named secret and removes it from the cache.
     *
     * @param secretName  the secret name
     * @param workspaceId the owning workspace ID
     * @return JSON response
     */
    private Response handleDeleteSecret(String secretName, String workspaceId) {
        Map<String, WorkstreamConfig.WorkspaceSecretEntry> wsSecrets =
                secretsCache.get(workspaceId);
        if (wsSecrets == null || !wsSecrets.containsKey(secretName)) {
            return NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND,
                    "application/json",
                    "{\"ok\":false,\"error\":\"Secret not found: "
                            + JsonFieldExtractor.escapeJson(secretName) + "\"}");
        }
        WorkstreamConfig.WorkspaceSecretEntry entry = wsSecrets.get(secretName);
        try {
            Files.deleteIfExists(Path.of(entry.getFile()));
        } catch (IOException e) {
            warn("Failed to delete secret file for " + secretName + ": " + e.getMessage());
            return NanoHTTPD.newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                    "application/json",
                    "{\"ok\":false,\"error\":\"Failed to delete secret\"}");
        }
        wsSecrets.remove(secretName);
        log("Secret deleted: name=" + secretName + " workspace=" + workspaceId);
        return NanoHTTPD.newFixedLengthResponse(
                Response.Status.OK, "application/json", "{\"ok\":true}");
    }

    /**
     * Reads a secret payload from a JSON file on disk.
     *
     * @param filePath absolute path to the JSON file
     * @return parsed key-value payload map
     * @throws IOException if the file cannot be read or parsed as JSON
     */
    private static Map<String, String> readSecretPayload(String filePath) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(new File(filePath),
                new TypeReference<Map<String, String>>() { });
    }

    /**
     * Writes a secret payload to a JSON file atomically.
     *
     * <p>Writes to a sibling {@code .tmp} file, applies {@code 0600} permissions,
     * then atomically moves the temporary file to the target path.</p>
     *
     * @param filePath absolute path to the target file
     * @param payload  the key-value map to serialise as JSON
     * @throws IOException if the file cannot be written
     */
    private static void writeSecretPayload(String filePath,
                                            Map<String, String> payload) throws IOException {
        Path target = Path.of(filePath);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(payload);
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(tmp, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystem; permissions will be set after move
        }
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.setPosixFilePermissions(target, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystem
        }
    }

    /**
     * Returns a 403 Forbidden JSON response with the given error message.
     *
     * @param message the error description
     * @return a 403 response
     */
    private Response secretForbidden(String message) {
        String json = "{\"ok\":false,\"error\":\"" + JsonFieldExtractor.escapeJson(message) + "\"}";
        return NanoHTTPD.newFixedLengthResponse(
                Response.Status.FORBIDDEN, "application/json", json);
    }

    /**
     * Returns the first value for the named query parameter, or {@code null}.
     *
     * @param params the query parameter map from the HTTP session
     * @param key    the parameter name
     * @return the first value, or {@code null} if the parameter is absent
     */
    private static String getFirstParam(Map<String, List<String>> params, String key) {
        List<String> values = params.get(key);
        return (values != null && !values.isEmpty()) ? values.get(0) : null;
    }

}
