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
import fi.iki.elonen.NanoHTTPD.Response;
import io.flowtree.JsonFieldExtractor;
import io.flowtree.jobs.agent.PhaseConfigBundle;

import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import io.flowtree.submission.PhaseConfigResolver;
import io.flowtree.workstream.WorkstreamConfig;
import io.flowtree.slack.SlackListener;
import io.flowtree.workstream.WorkspaceEntry;

/**
 * Handles the workspace config endpoint ({@code POST /api/workspaces/{id}/config})
 * for {@link FlowTreeApiEndpoint}.
 *
 * <p>The endpoint exposes a deliberately narrow set of {@link
 * WorkspaceEntry} fields:</p>
 * <ul>
 *   <li>{@code name} — human-readable label.</li>
 *   <li>{@code defaultChannel} — fallback Slack channel ID.</li>
 *   <li>{@code defaultPhaseConfig} / {@code phaseConfigs} — the per-phase
 *       configuration shape (runner / model / effort / provider), overlaid
 *       on the entry's existing bundle via
 *       {@link PhaseConfigResolver#applyToWorkspace}.</li>
 * </ul>
 *
 * <p>The legacy {@code runners} / {@code defaultRunner} fields are no longer
 * accepted; a request carrying any removed legacy field is rejected with a
 * 400 by {@link PhaseConfigResolver#rejectLegacyRequestFields(String)}.</p>
 *
 * <p>Credential and ACL fields ({@code tokensFile}, {@code botToken},
 * {@code appToken}, {@code githubOrgs}, {@code channelOwnerUserId}) are
 * intentionally NOT settable here — operators must edit the YAML directly
 * for those, where the change can be code-reviewed before it lands.</p>
 *
 * <p>Persistence is delegated to {@link SlackListener#persistConfig()},
 * which writes the in-memory {@link WorkstreamConfig} back to disk; mutating
 * the entry returned from the workspace lookup is enough because the lookup
 * returns the same object held by the loaded config.</p>
 *
 * @author Michael Murray
 * @see FlowTreeApiEndpoint
 */
final class WorkspaceConfigHandler {

    /** Resolves a workspace ID to its config entry, or {@code null}. */
    private final Function<String, WorkspaceEntry> workspaceLookup;
    /**
     * Renames a workspace: applies {@code (oldId, newId)} to the loaded
     * config. Returns {@code true} on success, {@code false} when the old ID
     * was not found, or throws {@link IllegalArgumentException} if the new ID
     * collides. {@code null} when no rename hook is wired and the handler
     * should respond 400 to rename attempts.
     */
    private final BiFunction<String, String, Boolean> renameWorkspace;
    /** Slack listener used to persist YAML edits after each mutation; may be {@code null}. */
    private final SlackListener listener;
    /** Reads the POST body from a NanoHTTPD session; reused from the parent endpoint. */
    private final Function<IHTTPSession, String> readBody;
    /** Builds a 400 error response with the given message. */
    private final Function<String, Response> errorResponse;
    /** Emits a log line via the parent endpoint's logger. */
    private final Consumer<String> log;

    /**
     * Constructs a new handler without a rename hook. Used in tests and in
     * paths that do not need to rename workspaces.
     *
     * @param workspaceLookup workspace-ID to entry function; never {@code null}
     * @param listener        Slack listener for YAML persistence (may be {@code null})
     * @param readBody        body reader supplied by the parent endpoint
     * @param errorResponse   400-error response factory
     * @param log             log line consumer
     */
    WorkspaceConfigHandler(Function<String, WorkspaceEntry> workspaceLookup,
                           SlackListener listener,
                           Function<IHTTPSession, String> readBody,
                           Function<String, Response> errorResponse,
                           Consumer<String> log) {
        this(workspaceLookup, null, listener, readBody, errorResponse, log);
    }

    /**
     * Constructs a new handler with a rename hook so the {@code newId} body
     * field can rename the workspace and update every workstream that
     * referenced the old ID.
     *
     * @param workspaceLookup workspace-ID to entry function; never {@code null}
     * @param renameWorkspace optional rename callback; {@code null} disables
     *                        rename attempts via this handler
     * @param listener        Slack listener for YAML persistence (may be {@code null})
     * @param readBody        body reader supplied by the parent endpoint
     * @param errorResponse   400-error response factory
     * @param log             log line consumer
     */
    WorkspaceConfigHandler(Function<String, WorkspaceEntry> workspaceLookup,
                           BiFunction<String, String, Boolean> renameWorkspace,
                           SlackListener listener,
                           Function<IHTTPSession, String> readBody,
                           Function<String, Response> errorResponse,
                           Consumer<String> log) {
        this.workspaceLookup = workspaceLookup;
        this.renameWorkspace = renameWorkspace;
        this.listener = listener;
        this.readBody = readBody;
        this.errorResponse = errorResponse;
        this.log = log;
    }

    /** URL prefix portion of the workspace config endpoint. */
    static final String URL_PREFIX = "/api/workspaces/";
    /** URL suffix portion of the workspace config endpoint. */
    static final String URL_SUFFIX = "/config";

    /**
     * Returns a handled response when {@code uri} matches
     * {@code /api/workspaces/{id}/config}; {@code null} when the URI is not
     * for this handler. Callers chain this in their request dispatch.
     *
     * @param uri     the HTTP request URI
     * @param session the HTTP session
     * @return a response when the URI matched, {@code null} otherwise
     */
    Response handleIfMatches(String uri, IHTTPSession session) {
        if (uri == null || !uri.startsWith(URL_PREFIX) || !uri.endsWith(URL_SUFFIX)
                || uri.length() <= URL_PREFIX.length() + URL_SUFFIX.length()) {
            return null;
        }
        return handle(session, uri.substring(URL_PREFIX.length(),
                uri.length() - URL_SUFFIX.length()));
    }

    /**
     * Handles {@code POST /api/workspaces/{id}/config}.
     *
     * @param session     the HTTP session
     * @param workspaceId the operator-chosen workspace id from the URL path
     *                    (the value of {@code WorkspaceEntry.id} — note that
     *                    this is distinct from the Slack team id, which lives
     *                    on the entry as {@code slackTeamId} and may differ
     *                    or be absent entirely)
     * @return JSON response confirming the update, or a 4xx with an error
     */
    Response handle(IHTTPSession session, String workspaceId) {
        String body = readBody.apply(session);
        if (body == null) {
            return errorResponse.apply("Failed to read request body");
        }
        String legacyErr = PhaseConfigResolver.rejectLegacyRequestFields(body);
        if (legacyErr != null) return errorResponse.apply(legacyErr);

        WorkspaceEntry entry = workspaceLookup.apply(workspaceId);
        if (entry == null) {
            String json = "{\"ok\":false,\"error\":\"Unknown workspace: "
                    + JsonFieldExtractor.escapeJson(workspaceId) + "\"}";
            return NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND,
                    "application/json", json);
        }

        String name = JsonFieldExtractor.extractString(body, "name");
        String defaultChannel = JsonFieldExtractor.extractString(body, "defaultChannel");
        String newId = JsonFieldExtractor.extractString(body, "newId");
        // slackTeamId is special: the body may set it to an empty string to
        // clear the Slack connection. Detect the difference between "absent"
        // and "empty-string" by checking key presence in the JSON.
        boolean slackTeamIdPresent = body.contains("\"slackTeamId\"");
        String slackTeamId = JsonFieldExtractor.extractString(body, "slackTeamId");

        String phaseConfigErr = PhaseConfigResolver.applyToWorkspace(entry, body);
        if (phaseConfigErr != null) return errorResponse.apply(phaseConfigErr);

        if (name != null && !name.isEmpty()) {
            entry.setName(name);
        }
        if (defaultChannel != null && !defaultChannel.isEmpty()) {
            entry.setDefaultChannel(defaultChannel);
        }
        if (slackTeamIdPresent) {
            // Empty string clears the connection; non-empty sets it.
            entry.setSlackTeamId(slackTeamId == null ? null : slackTeamId);
        }

        // Handle the rename last so that the response can emit the final ID.
        String resolvedId = workspaceId;
        if (newId != null && !newId.isEmpty() && !newId.equals(workspaceId)) {
            if (renameWorkspace == null) {
                return errorResponse.apply(
                        "Workspace rename is not enabled on this controller");
            }
            try {
                Boolean ok = renameWorkspace.apply(workspaceId, newId);
                if (ok == null || !ok) {
                    return errorResponse.apply(
                            "Could not rename workspace '" + workspaceId
                                    + "' to '" + newId + "'");
                }
                resolvedId = newId;
            } catch (IllegalArgumentException ex) {
                return errorResponse.apply(ex.getMessage());
            }
        }

        if (listener != null) {
            listener.persistConfig();
        }

        log.accept("Updated workspace config via API: " + resolvedId
                + (resolvedId.equals(workspaceId) ? ""
                        : " (renamed from " + workspaceId + ")"));

        StringBuilder json = new StringBuilder();
        json.append("{\"ok\":true,\"workspaceId\":\"")
                .append(JsonFieldExtractor.escapeJson(resolvedId))
                .append("\"");
        if (entry.getSlackTeamId() != null) {
            json.append(",\"slackTeamId\":\"")
                    .append(JsonFieldExtractor.escapeJson(entry.getSlackTeamId()))
                    .append("\"");
        }
        if (entry.getName() != null) {
            json.append(",\"name\":\"")
                    .append(JsonFieldExtractor.escapeJson(entry.getName()))
                    .append("\"");
        }
        if (entry.getDefaultChannel() != null) {
            json.append(",\"defaultChannel\":\"")
                    .append(JsonFieldExtractor.escapeJson(entry.getDefaultChannel()))
                    .append("\"");
        }
        PhaseConfigBundle bundle = entry.toPhaseConfigBundle();
        PhaseConfigResolver.appendBundleJson(json, bundle);
        json.append("}");
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK,
                "application/json", json.toString());
    }
}
