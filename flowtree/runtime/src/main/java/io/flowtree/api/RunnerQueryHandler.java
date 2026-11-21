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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Method;
import fi.iki.elonen.NanoHTTPD.Response;
import io.flowtree.github.GitHubRunner;
import io.flowtree.github.RunnerListing;
import io.flowtree.github.RunnerMonitor;
import io.flowtree.github.RunnerSelection;

import java.util.function.Function;

/**
 * Handles the controller's runner endpoints for CI, both read-only:
 * {@code GET /api/runners/select} and {@code GET /api/runners}.
 *
 * <p>{@code select} answers "which runner should this job use?". It takes a
 * required label (e.g. {@code ar-build}) and, optionally, an ordered preference
 * of fleet labels and an organization, and returns the {@code runs-on} label
 * list for the first preferred fleet with an online, idle runner carrying the
 * required label — or GitHub-hosted {@code ubuntu-latest} when none does. The
 * answer always carries {@code listed} and {@code reason}: when the controller's
 * token cannot list the organization's runners, the response says so plainly
 * rather than answering hosted as though every fleet were merely busy.</p>
 *
 * <p>{@code runners} lists the runners themselves, for diagnostics.</p>
 *
 * @see FlowTreeApiEndpoint
 * @see RunnerMonitor
 */
class RunnerQueryHandler {

    /** JSON builder for the responses. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The monitor that reads and caches the runner list and makes the choice. */
    private final RunnerMonitor monitor;

    /**
     * Creates a handler backed by {@code monitor}.
     *
     * @param monitor the runner monitor
     */
    RunnerQueryHandler(RunnerMonitor monitor) {
        this.monitor = monitor;
    }

    /**
     * Routes a {@code /api/runners...} request.
     *
     * @param session       the HTTP session
     * @param method        the HTTP method
     * @param uri           the request URI
     * @param errorResponse 400-error response factory
     * @return the response
     */
    Response handle(IHTTPSession session, Method method, String uri,
                    Function<String, Response> errorResponse) {
        if (!Method.GET.equals(method)) {
            return errorResponse.apply("GET only");
        }
        if ("/api/runners/select".equals(uri)) {
            return handleSelect(session, errorResponse);
        }
        if ("/api/runners".equals(uri)) {
            return handleList(session);
        }
        return errorResponse.apply("unknown runner endpoint: " + uri);
    }

    /**
     * Handles {@code GET /api/runners/select?required=<label>&fleets=<csv>&org=<org>}.
     *
     * @param session       the HTTP session
     * @param errorResponse 400-error response factory
     * @return the selection as JSON
     */
    private Response handleSelect(IHTTPSession session, Function<String, Response> errorResponse) {
        String required = RequestParameters.first(session, "required", null);
        if (required == null || required.isBlank()) {
            return errorResponse.apply("required is required");
        }
        String org = RequestParameters.first(session, "org", null);
        RunnerSelection selection = monitor.select(org, required.trim(),
                RunnerMonitor.splitLabels(RequestParameters.first(session, "fleets", null)));

        ObjectNode result = MAPPER.createObjectNode();
        result.put("ok", true);
        ArrayNode runsOn = result.putArray("runs_on");
        selection.runsOn().forEach(runsOn::add);
        if (selection.fleet() == null) {
            result.putNull("fleet");
        } else {
            result.put("fleet", selection.fleet());
        }
        result.put("hosted", selection.hosted());
        result.put("listed", selection.listed());
        result.put("reason", selection.reason());
        return json(result);
    }

    /**
     * Handles {@code GET /api/runners?org=<org>}.
     *
     * @param session the HTTP session
     * @return the runner list as JSON
     */
    private Response handleList(IHTTPSession session) {
        String org = RequestParameters.first(session, "org", null);
        RunnerListing listing = monitor.runners(org);
        ObjectNode result = MAPPER.createObjectNode();
        result.put("ok", listing.ok());
        result.put("listed", listing.ok());
        if (!listing.ok()) {
            result.put("error", listing.error());
        }
        ArrayNode runners = result.putArray("runners");
        for (GitHubRunner runner : listing.runners()) {
            ObjectNode node = runners.addObject();
            node.put("name", runner.name());
            node.put("status", runner.status());
            node.put("busy", runner.busy());
            node.put("idle", runner.idle());
            ArrayNode labels = node.putArray("labels");
            runner.labels().forEach(labels::add);
        }
        return json(result);
    }

    /**
     * Serialises {@code node} as a {@code 200} JSON response.
     *
     * @param node the response body
     * @return the HTTP response
     */
    private static Response json(ObjectNode node) {
        try {
            return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json",
                    MAPPER.writeValueAsString(node));
        } catch (JsonProcessingException e) {
            return NanoHTTPD.newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                    "application/json", "{\"ok\":false,\"error\":\"could not serialise response\"}");
        }
    }
}
