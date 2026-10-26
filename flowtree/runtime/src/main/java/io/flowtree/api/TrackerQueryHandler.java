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
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;
import io.flowtree.slack.NotifierRegistry;
import io.flowtree.tracker.TrackerService;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

/**
 * Handles the controller's tracker endpoints for CI:
 * {@code GET /api/tracker/claimable} and {@code POST /api/tracker/claim}.
 *
 * <p>CI reaches the controller but not the tracker, which is only reachable on
 * the controller's own network, so these endpoints act on its behalf.</p>
 *
 * <p>{@code claimable} decides whether to start a task-planning round. It takes
 * a {@code project} and a {@code release} name and returns the tracker's own
 * answer unchanged: {@code {"ok":true,"release_id":...,"count":N}}. Passing the
 * answer through, rather than recomputing it, keeps the definition of a
 * claimable task in one place — the tracker — shared with the claim itself.
 * It only reads.</p>
 *
 * <p>{@code claim} claims the next ready task of a release for a registered
 * workstream, so CI can hand that task to the agent it is about to run rather
 * than having the agent claim it. See {@link #handleClaim(String, Function)}.</p>
 *
 * @see FlowTreeApiEndpoint
 */
class TrackerQueryHandler {

    /** JSON parser and builder for claim request and response bodies. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * HTTP 502, returned when the tracker fails a claim. NanoHTTPD's
     * {@link Response.Status} has no constant for it.
     */
    private static final Response.IStatus BAD_GATEWAY = new Response.IStatus() {
        @Override
        public String getDescription() {
            return "502 Bad Gateway";
        }

        @Override
        public int getRequestStatus() {
            return 502;
        }
    };

    /** The tracker the question is passed to. */
    private final TrackerService tracker;

    /** The registry a claiming workstream must be registered in. */
    private final NotifierRegistry notifiers;

    /**
     * Creates a handler that asks {@code tracker}.
     *
     * @param tracker   the tracker service
     * @param notifiers the registry of workstreams that may claim a task
     */
    TrackerQueryHandler(TrackerService tracker, NotifierRegistry notifiers) {
        this.tracker = tracker;
        this.notifiers = notifiers;
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
        try {
            TrackerService.Reply reply = tracker.get("/v1/claimable?project="
                    + URLEncoder.encode(project, StandardCharsets.UTF_8)
                    + "&release=" + URLEncoder.encode(release, StandardCharsets.UTF_8));
            Response.IStatus responseStatus = Response.Status.lookup(reply.status());
            return NanoHTTPD.newFixedLengthResponse(
                    responseStatus == null ? Response.Status.SERVICE_UNAVAILABLE : responseStatus,
                    "application/json", reply.body());
        } catch (IOException e) {
            return failure(Response.Status.SERVICE_UNAVAILABLE, "tracker unreachable: " + e.getMessage());
        }
    }

    /**
     * Handles {@code POST /api/tracker/claim} with a JSON body
     * {@code {"project":"<name>","release":"<name>","workstreamId":"<id>"}}.
     *
     * <p>The claim is forwarded to the tracker's {@code POST /v1/claim}, which
     * picks the next ready task of the release, links it to the workstream and
     * returns it. The response is {@code {"ok":true,"task":{...}}}, or
     * {@code {"ok":true,"task":null}} when the release has nothing to claim.
     * Errors are {@code {"ok":false,"error":"..."}} with status 400 when a
     * field is missing or blank, 404 when {@code workstreamId} names no
     * registered workstream, and 502 when the tracker cannot be reached or
     * refuses the claim.</p>
     *
     * @param body          the request body, or {@code null} when there was none
     * @param errorResponse 400-error response factory
     * @return the claimed task, or an error response
     */
    Response handleClaim(String body, Function<String, Response> errorResponse) {
        JsonNode request;
        try {
            request = body == null ? MAPPER.createObjectNode() : MAPPER.readTree(body);
        } catch (IOException e) {
            return errorResponse.apply("request body is not valid JSON");
        }
        String project = request.path("project").asText("").trim();
        String release = request.path("release").asText("").trim();
        String workstreamId = request.path("workstreamId").asText("").trim();
        if (project.isEmpty() || release.isEmpty() || workstreamId.isEmpty()) {
            return errorResponse.apply("project, release and workstreamId are required");
        }
        if (notifiers.workstreamFor(workstreamId) == null) {
            return failure(Response.Status.NOT_FOUND, "Unknown workstream: " + workstreamId);
        }
        try {
            TrackerService.Reply reply = tracker.claim(project, release, workstreamId);
            if (reply.status() >= 400) {
                return failure(BAD_GATEWAY,
                        "tracker returned HTTP " + reply.status() + ": " + reply.body());
            }
            JsonNode task = MAPPER.readTree(reply.body()).get("task");
            ObjectNode result = MAPPER.createObjectNode();
            result.put("ok", true);
            result.set("task", task == null ? NullNode.getInstance() : task);
            return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json",
                    MAPPER.writeValueAsString(result));
        } catch (IOException e) {
            return failure(BAD_GATEWAY, "tracker claim failed: " + e.getMessage());
        }
    }

    /**
     * Builds an {@code {"ok":false,"error":...}} response with the given status.
     *
     * @param status  the HTTP status
     * @param message the error message
     * @return the response
     */
    private static Response failure(Response.IStatus status, String message) {
        return NanoHTTPD.newFixedLengthResponse(status, "application/json",
                "{\"ok\":false,\"error\":" + FlowTreeApiEndpoint.escapeJsonValue(message) + "}");
    }
}
