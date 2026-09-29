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

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

/**
 * Handles {@code GET /api/tracker/claimable} for {@link FlowTreeApiEndpoint}.
 *
 * <p>CI decides whether to start a task-planning round by asking whether a
 * tracker release has a task an agent could claim. CI reaches the controller
 * but not the tracker, which is only reachable on the controller's own network,
 * so this endpoint asks on its behalf. It takes a {@code project} and a
 * {@code release} name and returns the tracker's own answer unchanged:
 * {@code {"ok":true,"release_id":...,"count":N}}. Passing the answer through,
 * rather than recomputing it, keeps the definition of a claimable task in one
 * place — the tracker — shared with the claim itself.</p>
 *
 * <p>The endpoint only reads: it never claims or changes a task.</p>
 *
 * @see FlowTreeApiEndpoint
 */
class TrackerQueryHandler {

    /** The tracker's base URL, e.g. {@code http://ar-tracker:8030}. */
    private final String trackerUrl;

    /** The tracker's bearer token, or {@code null} when it runs without one. */
    private final String authToken;

    /**
     * Creates a handler for the tracker at {@code trackerUrl}.
     *
     * @param trackerUrl the tracker's base URL
     * @param authToken  the tracker's bearer token, or {@code null}
     */
    TrackerQueryHandler(String trackerUrl, String authToken) {
        this.trackerUrl = trackerUrl.replaceAll("/+$", "");
        this.authToken = authToken == null || authToken.isEmpty() ? null : authToken;
    }

    /**
     * Handles {@code GET /api/tracker/claimable?project=<name>&release=<name>}.
     *
     * @param session       the HTTP session carrying the query parameters
     * @param errorResponse 400-error response factory
     * @return the tracker's response, a 400 when a name is missing, or a 503
     *         when the tracker cannot be reached
     */
    Response handleClaimable(IHTTPSession session, Function<String, Response> errorResponse) {
        String project = RequestParameters.first(session, "project", null);
        String release = RequestParameters.first(session, "release", null);
        if (project == null || release == null) {
            return errorResponse.apply("project and release are required");
        }
        String url = trackerUrl + "/v1/claimable?project="
                + URLEncoder.encode(project, StandardCharsets.UTF_8)
                + "&release=" + URLEncoder.encode(release, StandardCharsets.UTF_8);
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            if (authToken != null) {
                conn.setRequestProperty("Authorization", "Bearer " + authToken);
            }
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            int status = conn.getResponseCode();
            InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String body = "";
            if (in != null) {
                try (InputStream stream = in) {
                    body = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            Response.IStatus responseStatus = Response.Status.lookup(status);
            return NanoHTTPD.newFixedLengthResponse(
                    responseStatus == null ? Response.Status.SERVICE_UNAVAILABLE : responseStatus,
                    "application/json", body);
        } catch (IOException e) {
            return NanoHTTPD.newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "application/json",
                    "{\"ok\":false,\"error\":"
                            + FlowTreeApiEndpoint.escapeJsonValue("tracker unreachable: " + e.getMessage())
                            + "}");
        }
    }
}
