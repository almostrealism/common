/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.flowtree.tracker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.flowtree.github.PullRequest;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The task tracker, as the controller reaches it over its REST API.
 *
 * <p>The tracker runs on the controller's own network and is not reachable
 * from CI, so the controller both answers questions about it on CI's behalf
 * and acts on it itself. The rules that decide which tasks a request concerns
 * (what is claimable, what is awaiting close-out) live in the tracker; this
 * type only carries requests and replies.</p>
 */
public class TrackerService {

    /** JSON parser and builder for request and reply bodies. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The tracker's base URL without a trailing slash, e.g. {@code http://ar-tracker:8030}. */
    private final String baseUrl;

    /** The tracker's bearer token, or {@code null} when it runs without one. */
    private final String authToken;

    /**
     * Creates a client for the tracker at {@code baseUrl}.
     *
     * @param baseUrl   the tracker's base URL
     * @param authToken the tracker's bearer token, or {@code null}/empty when it has none
     */
    public TrackerService(String baseUrl, String authToken) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.authToken = authToken == null || authToken.isEmpty() ? null : authToken;
    }

    /**
     * An HTTP reply from the tracker.
     *
     * @param status the HTTP status code
     * @param body   the response body, empty when there was none
     */
    public record Reply(int status, String body) {

        /**
         * Reads a JSON array of strings from the body, requiring a successful status.
         *
         * @param field the body member holding the array; a missing member reads as empty
         * @return the array's elements
         * @throws IOException when the status is an error or the body is not JSON
         */
        List<String> strings(String field) throws IOException {
            if (status >= 400) {
                throw new IOException("tracker returned HTTP " + status + ": " + body);
            }
            List<String> values = new ArrayList<>();
            MAPPER.readTree(body).path(field).forEach(v -> values.add(v.asText()));
            return values;
        }
    }

    /**
     * What became of the branch a claimed task was linked to, as the tracker
     * names it in a close-out.
     */
    public enum CloseOutOutcome {
        /** The branch's pull request merged: the task is finished. */
        MERGED("merged"),
        /** The branch's pull request was closed without merging: the task is declined. */
        CLOSED("closed");

        /** The outcome's name in the tracker API. */
        private final String wireName;

        /**
         * Creates an outcome with its name in the tracker API.
         *
         * @param wireName the outcome's name in the tracker API
         */
        CloseOutOutcome(String wireName) {
            this.wireName = wireName;
        }

        /**
         * Returns what a branch's pull requests mean for the tasks linked to it.
         *
         * <p>A merge anywhere on the branch finishes the work, even when another
         * pull request was closed along the way. A branch is only declined when
         * nothing on it is still open: a closed pull request beside an open one
         * usually means the work moved to the new one.</p>
         *
         * @param prs the branch's pull requests, or {@code null} when they could not be listed
         * @return the outcome, or {@code null} when none is known yet
         */
        public static CloseOutOutcome of(List<PullRequest> prs) {
            if (prs == null) return null;
            if (prs.stream().anyMatch(PullRequest::isMerged)) return MERGED;
            if (prs.stream().anyMatch(PullRequest::isOpen)) return null;
            if (prs.stream().anyMatch(PullRequest::isClosedUnmerged)) return CLOSED;
            return null;
        }

        /**
         * Returns the outcome's name in the tracker API.
         *
         * @return {@code "merged"} or {@code "closed"}
         */
        public String wireName() {
            return wireName;
        }
    }

    /**
     * Sends {@code GET} for a path, with its query string already encoded.
     *
     * @param pathAndQuery the path below the base URL, e.g. {@code /v1/claimable?project=...}
     * @return the tracker's reply
     * @throws IOException when the tracker cannot be reached
     */
    public Reply get(String pathAndQuery) throws IOException {
        return send("GET", pathAndQuery, null);
    }

    /**
     * Sends {@code POST} with a JSON body.
     *
     * @param path the path below the base URL
     * @param body the JSON body
     * @return the tracker's reply
     * @throws IOException when the tracker cannot be reached
     */
    public Reply post(String path, JsonNode body) throws IOException {
        return send("POST", path, MAPPER.writeValueAsString(body));
    }

    /**
     * Claims the next ready task of a release for a workstream, through the
     * tracker's {@code POST /v1/claim}. Which task is next, and whether any is
     * claimable at all, is decided by the tracker; the reply is returned as the
     * tracker sent it, {@code {"ok":true,"task":{...}|null}} on success.
     *
     * @param project      the project name
     * @param release      the release name within the project
     * @param workstreamId the workstream the claimed task is linked to
     * @return the tracker's reply
     * @throws IOException when the tracker cannot be reached
     */
    public Reply claim(String project, String release, String workstreamId) throws IOException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("project", project);
        body.put("release", release);
        body.put("workstream_id", workstreamId);
        return post("/v1/claim", body);
    }

    /**
     * Lists the workstreams linked to a claimed task that is still open and
     * ready, i.e. the branches whose outcome has yet to be applied.
     *
     * @return the workstream ids
     * @throws IOException when the tracker cannot be reached or refuses
     */
    public List<String> workstreamsAwaitingCloseOut() throws IOException {
        return get("/v1/close-out").strings("workstream_ids");
    }

    /**
     * Applies a branch's outcome to the claimed tasks linked to a workstream.
     * The tracker changes only tasks still open and ready, so repeating a
     * close-out changes nothing.
     *
     * @param workstreamId the workstream whose branch reached the outcome
     * @param outcome      what became of the branch's pull request
     * @return the ids of the tasks changed
     * @throws IOException when the tracker cannot be reached or refuses
     */
    public List<String> closeOut(String workstreamId, CloseOutOutcome outcome) throws IOException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("workstream_id", workstreamId);
        body.put("outcome", outcome.wireName());
        return post("/v1/close-out", body).strings("task_ids");
    }

    /**
     * Sends one request and reads the whole reply.
     *
     * @param method HTTP method
     * @param path   path below the base URL
     * @param body   JSON body, or {@code null} for none
     * @return the reply
     * @throws IOException when the tracker cannot be reached
     */
    private Reply send(String method, String path, String body) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(baseUrl + path).toURL().openConnection();
        conn.setRequestMethod(method);
        conn.setRequestProperty("Accept", "application/json");
        if (authToken != null) {
            conn.setRequestProperty("Authorization", "Bearer " + authToken);
        }
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);
        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int status = conn.getResponseCode();
        InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (in == null) return new Reply(status, "");
        try (InputStream stream = in) {
            return new Reply(status, new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
