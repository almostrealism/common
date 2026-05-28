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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.function.Function;
import io.flowtree.workstream.Workstream;
import io.flowtree.slack.SlackNotifier;
import io.flowtree.slack.NotifierRegistry;

/**
 * Handles the workstream message endpoint
 * ({@code POST /api/workstreams/{id}/messages} and
 * {@code POST /api/workstreams/{id}/jobs/{jobId}/messages}) for
 * {@link FlowTreeApiEndpoint}.
 *
 * <p>Incoming messages are first stored as memories in the
 * ar-memory server (under the {@code messages} namespace) so the
 * activity record survives Slack outages or non-Slack deployments.
 * The message is then forwarded to the workstream's notification
 * channel on a best-effort basis: a missing or unconfigured channel
 * does not fail the request.</p>
 *
 * @author Michael Murray
 * @see FlowTreeApiEndpoint
 */
public final class MessageEndpointHandler {

    /** Aggregates the per-workspace notifiers used to resolve message routing. */
    private final NotifierRegistry notifiers;
    /** Base URL of the ar-memory HTTP server; {@code null}/empty disables memory storage. */
    private final String memoryServerUrl;
    /** Reads the POST body from a NanoHTTPD session; reused from the parent endpoint. */
    private final Function<IHTTPSession, String> readBody;
    /** Builds a 400 error response with the given message. */
    private final Function<String, Response> errorResponse;
    /** Emits a log line via the parent endpoint's logger. */
    private final Consumer<String> log;
    /** Emits a warning line via the parent endpoint's logger. */
    private final Consumer<String> warn;

    /**
     * Constructs a new handler bound to the given notifier registry.
     *
     * @param notifiers       the workspace notifier registry
     * @param memoryServerUrl base URL of the ar-memory HTTP server, or
     *                        {@code null}/empty to disable memory storage
     * @param readBody        body reader supplied by the parent endpoint
     * @param errorResponse   400-error response factory
     * @param log             log line consumer
     * @param warn            warn line consumer
     */
    MessageEndpointHandler(NotifierRegistry notifiers, String memoryServerUrl,
                           Function<IHTTPSession, String> readBody,
                           Function<String, Response> errorResponse,
                           Consumer<String> log, Consumer<String> warn) {
        this.notifiers = notifiers;
        this.memoryServerUrl = memoryServerUrl;
        this.readBody = readBody;
        this.errorResponse = errorResponse;
        this.log = log;
        this.warn = warn;
    }

    /**
     * Handles POST to {@code /api/workstreams/{id}/messages} or
     * {@code /api/workstreams/{id}/jobs/{jobId}/messages}.
     * An optional {@code activity} field in the body is stored as an
     * {@code activity:<value>} tag for enforcement-phase filtering.
     *
     * @param session      the HTTP session
     * @param workstreamId the workstream identifier from the URL path
     * @param jobId        the job identifier from the URL path, or {@code null}
     * @return a JSON response
     */
    public Response handle(IHTTPSession session, String workstreamId, String jobId) {
        String body = readBody.apply(session);
        if (body == null) {
            return errorResponse.apply("Failed to read request body");
        }

        String text = JsonFieldExtractor.extractString(body, "text");
        if (text == null || text.isEmpty()) {
            return errorResponse.apply("Missing required field: text");
        }

        String activity = JsonFieldExtractor.extractString(body, "activity");
        SlackNotifier targetNotifier = notifiers.notifierFor(workstreamId);
        Workstream workstream = targetNotifier != null
                ? targetNotifier.getWorkstream(workstreamId) : null;
        if (workstream == null) {
            return errorResponse.apply("Unknown workstream: " + workstreamId);
        }

        log.accept("Message [" + workstreamId + (jobId != null ? "/" + jobId : "") + "]: "
                + SlackNotifier.truncate(text, 80));

        // Store as memory — hard error if memory server is configured but fails,
        // warning if memory server is not configured at all (minimal deployment)
        String repoUrl = workstream.getRepoUrl();
        String branch = workstream.getDefaultBranch();
        String storeError = storeMessageAsMemory(text, repoUrl, branch, activity);
        if (storeError != null) {
            if (memoryServerUrl != null && !memoryServerUrl.isEmpty()) {
                // Memory server is configured but storage failed — hard error
                warn.accept("Failed to store message as memory: " + storeError);
                return errorResponse.apply("Failed to store message: " + storeError);
            }
            // Memory server not configured — warn but continue
            log.accept("Message not archived (memory server not configured): " + storeError);
        }

        // Secondary: forward to notification channel (best-effort)
        String threadTs = jobId != null ? targetNotifier.getThreadTs(jobId) : null;
        String resultTs;
        if (threadTs != null) {
            resultTs = targetNotifier.postMessageInThread(workstream.getChannelId(), text, threadTs);
        } else {
            if (jobId != null) warn.accept("send_message_no_thread_ts workstream_id=" + workstreamId
                    + " job_id=" + jobId + " activity=" + (activity != null ? activity : ""));
            resultTs = targetNotifier.postMessage(workstream.getChannelId(), text);
        }

        if (resultTs == null) {
            log.accept("Message received for workstream " + workstreamId
                + " but no notification channel is configured");
        }

        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK,
                "application/json",
                resultTs != null
                    ? "{\"ok\":true}"
                    : "{\"ok\":true,\"warning\":\"no notification channel configured\"}");
    }

    /**
     * Stores a message in the ar-memory server's "messages" namespace.
     * A non-empty {@code activity} is appended as an {@code activity:<value>} tag.
     *
     * @param text     the message text
     * @param repoUrl  repository URL of the owning workstream
     * @param branch   branch name of the owning workstream
     * @param activity enforcement phase name, or {@code null}/empty for primary work
     * @return {@code null} on success, or an error description on failure
     */
    private String storeMessageAsMemory(String text, String repoUrl, String branch, String activity) {
        if (memoryServerUrl == null || memoryServerUrl.isEmpty()) {
            return "memory server URL not configured";
        }
        if (repoUrl == null || repoUrl.isEmpty() || branch == null || branch.isEmpty()) {
            return "workstream missing repoUrl or defaultBranch";
        }
        String tagsJson = (activity != null && !activity.isEmpty())
            ? "[\"message\"," + FlowTreeApiEndpoint.escapeJsonValue("activity:" + activity) + "]"
            : "[\"message\"]";

        String url = memoryServerUrl.replaceAll("/+$", "") + "/api/memory/store";
        String payload = "{\"content\":" + FlowTreeApiEndpoint.escapeJsonValue(text)
            + ",\"repo_url\":" + FlowTreeApiEndpoint.escapeJsonValue(repoUrl)
            + ",\"branch\":" + FlowTreeApiEndpoint.escapeJsonValue(branch)
            + ",\"namespace\":\"messages\""
            + ",\"tags\":" + tagsJson
            + ",\"source\":\"ar-manager\"}";
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            if (status >= 200 && status < 300) {
                return null; // success
            }

            // Read error body
            try (InputStream is = conn.getErrorStream()) {
                if (is != null) {
                    String errBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    return "memory server returned " + status + ": " + SlackNotifier.truncate(errBody, 200);
                }
            }
            return "memory server returned " + status;
        } catch (IOException e) {
            return "memory server unreachable: " + e.getMessage();
        }
    }

}
