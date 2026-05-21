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
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;
import io.flowtree.JsonFieldExtractor;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Handles the workspace config endpoint ({@code POST /api/workspaces/{id}/config})
 * for {@link FlowTreeApiEndpoint}.
 *
 * <p>The endpoint exposes a deliberately narrow set of {@link
 * WorkstreamConfig.SlackWorkspaceEntry} fields:</p>
 * <ul>
 *   <li>{@code name} — human-readable label.</li>
 *   <li>{@code defaultChannel} — fallback Slack channel ID.</li>
 *   <li>{@code runners} — JSON object keyed by phase wire name; an optional
 *       {@code "default"} key sets the workspace-level
 *       {@code defaultRunner}. Replaces (does not merge with) the existing
 *       per-phase map, mirroring the workstream behaviour in
 *       {@link SubmissionRunnerResolver#applyToWorkstream}.</li>
 * </ul>
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

    /** Resolves a Slack workspace (team) ID to its config entry, or {@code null}. */
    private final Function<String, WorkstreamConfig.SlackWorkspaceEntry> workspaceLookup;
    /** Slack listener used to persist YAML edits after each mutation; may be {@code null}. */
    private final SlackListener listener;
    /** Reads the POST body from a NanoHTTPD session; reused from the parent endpoint. */
    private final Function<IHTTPSession, String> readBody;
    /** Builds a 400 error response with the given message. */
    private final Function<String, Response> errorResponse;
    /** Emits a log line via the parent endpoint's logger. */
    private final Consumer<String> log;

    /**
     * Constructs a new handler.
     *
     * @param workspaceLookup workspace-ID to entry function; never {@code null}
     * @param listener        Slack listener for YAML persistence (may be {@code null})
     * @param readBody        body reader supplied by the parent endpoint
     * @param errorResponse   400-error response factory
     * @param log             log line consumer
     */
    WorkspaceConfigHandler(Function<String, WorkstreamConfig.SlackWorkspaceEntry> workspaceLookup,
                           SlackListener listener,
                           Function<IHTTPSession, String> readBody,
                           Function<String, Response> errorResponse,
                           Consumer<String> log) {
        this.workspaceLookup = workspaceLookup;
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
     * @param workspaceId the Slack workspace (team) ID from the URL path
     * @return JSON response confirming the update, or a 4xx with an error
     */
    Response handle(IHTTPSession session, String workspaceId) {
        String body = readBody.apply(session);
        if (body == null) {
            return errorResponse.apply("Failed to read request body");
        }

        WorkstreamConfig.SlackWorkspaceEntry entry = workspaceLookup.apply(workspaceId);
        if (entry == null) {
            String json = "{\"ok\":false,\"error\":\"Unknown Slack workspace: "
                    + JsonFieldExtractor.escapeJson(workspaceId) + "\"}";
            return NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND,
                    "application/json", json);
        }

        String name = JsonFieldExtractor.extractString(body, "name");
        String defaultChannel = JsonFieldExtractor.extractString(body, "defaultChannel");
        Map<String, String> runnersObj = JsonFieldExtractor.extractStringObject(body, "runners");

        String runnersErr = SubmissionRunnerResolver.applyToWorkspace(entry, runnersObj);
        if (runnersErr != null) return errorResponse.apply(runnersErr);

        if (name != null && !name.isEmpty()) {
            entry.setName(name);
        }
        if (defaultChannel != null && !defaultChannel.isEmpty()) {
            entry.setDefaultChannel(defaultChannel);
        }

        if (listener != null) {
            listener.persistConfig();
        }

        log.accept("Updated workspace config via API: " + workspaceId);

        StringBuilder json = new StringBuilder();
        json.append("{\"ok\":true,\"workspaceId\":\"")
                .append(JsonFieldExtractor.escapeJson(workspaceId))
                .append("\"");
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
        if (entry.getDefaultRunner() != null) {
            json.append(",\"defaultRunner\":\"")
                    .append(JsonFieldExtractor.escapeJson(entry.getDefaultRunner()))
                    .append("\"");
        }
        Map<String, String> entryRunners = entry.getRunners();
        if (entryRunners != null && !entryRunners.isEmpty()) {
            json.append(",\"runners\":{");
            boolean first = true;
            for (Map.Entry<String, String> e : entryRunners.entrySet()) {
                if (!first) json.append(",");
                first = false;
                json.append("\"").append(JsonFieldExtractor.escapeJson(e.getKey()))
                        .append("\":\"")
                        .append(JsonFieldExtractor.escapeJson(e.getValue()))
                        .append("\"");
            }
            json.append("}");
        }
        json.append("}");
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK,
                "application/json", json.toString());
    }
}
