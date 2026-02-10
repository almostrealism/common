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
import io.flowtree.jobs.JobCompletionEvent;
import org.almostrealism.io.ConsoleFeatures;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight HTTP API endpoint for receiving Slack message requests from
 * Claude Code agents running on Flowtree nodes.
 *
 * <p>This server uses NanoHTTPD to expose a simple REST API that MCP tools
 * (running inside Claude Code sessions) can call to send messages back to
 * Slack channels. It delegates all Slack operations to {@link SlackNotifier}.</p>
 *
 * <h2>Endpoints</h2>
 * <table>
 *   <tr><th>Method</th><th>Path</th><th>Body</th><th>Description</th></tr>
 *   <tr><td>POST</td><td>/api/slack/message</td>
 *       <td>{@code {"channel_id":"C...","text":"..."}}</td>
 *       <td>Post a message to a channel</td></tr>
 *   <tr><td>POST</td><td>/api/slack/thread</td>
 *       <td>{@code {"channel_id":"C...","thread_ts":"...","text":"..."}}</td>
 *       <td>Reply in a thread</td></tr>
 *   <tr><td>POST</td><td>/api/job/event</td>
 *       <td>{@code {"jobId":"...","status":"STARTED|SUCCESS|FAILED",...}}</td>
 *       <td>Receive a job status event from an agent</td></tr>
 *   <tr><td>GET</td><td>/api/slack/health</td><td>—</td>
 *       <td>Health check</td></tr>
 * </table>
 *
 * @author Michael Murray
 * @see SlackNotifier
 * @see SlackBotController
 */
public class SlackApiEndpoint extends NanoHTTPD implements ConsoleFeatures {

    /** Default port for the API endpoint. */
    public static final int DEFAULT_PORT = 7780;

    private final SlackNotifier notifier;

    /**
     * Creates a new API endpoint on the specified port.
     *
     * @param port     the port to listen on
     * @param notifier the notifier to delegate Slack operations to
     */
    public SlackApiEndpoint(int port, SlackNotifier notifier) {
        super(port);
        this.notifier = notifier;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();

        if (Method.GET.equals(method) && "/api/slack/health".equals(uri)) {
            return handleHealth();
        }

        if (Method.POST.equals(method)) {
            switch (uri) {
                case "/api/slack/message":
                    return handlePostMessage(session);
                case "/api/slack/thread":
                    return handlePostThread(session);
                case "/api/job/event":
                    return handleJobEvent(session);
            }
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND,
                MIME_PLAINTEXT, "Not found");
    }

    /**
     * Handles GET /api/slack/health.
     */
    private Response handleHealth() {
        return newFixedLengthResponse(Response.Status.OK,
                "application/json", "{\"status\":\"ok\"}");
    }

    /**
     * Handles POST /api/slack/message.
     */
    private Response handlePostMessage(IHTTPSession session) {
        String body = readBody(session);
        if (body == null) {
            return errorResponse("Failed to read request body");
        }

        String channelId = extractJsonField(body, "channel_id");
        String text = extractJsonField(body, "text");

        if (channelId == null || channelId.isEmpty()) {
            return errorResponse("Missing required field: channel_id");
        }
        if (text == null || text.isEmpty()) {
            return errorResponse("Missing required field: text");
        }

        log("API message to " + channelId + ": " + truncate(text, 80));
        notifier.postMessage(channelId, text);

        return newFixedLengthResponse(Response.Status.OK,
                "application/json", "{\"ok\":true}");
    }

    /**
     * Handles POST /api/slack/thread.
     */
    private Response handlePostThread(IHTTPSession session) {
        String body = readBody(session);
        if (body == null) {
            return errorResponse("Failed to read request body");
        }

        String channelId = extractJsonField(body, "channel_id");
        String threadTs = extractJsonField(body, "thread_ts");
        String text = extractJsonField(body, "text");

        if (channelId == null || channelId.isEmpty()) {
            return errorResponse("Missing required field: channel_id");
        }
        if (threadTs == null || threadTs.isEmpty()) {
            return errorResponse("Missing required field: thread_ts");
        }
        if (text == null || text.isEmpty()) {
            return errorResponse("Missing required field: text");
        }

        log("API thread reply to " + channelId + " (" + threadTs + "): " + truncate(text, 80));
        notifier.postThreadReply(channelId, threadTs, text);

        return newFixedLengthResponse(Response.Status.OK,
                "application/json", "{\"ok\":true}");
    }

    /**
     * Handles POST /api/job/event — receives a job status event from an agent
     * and delegates to the {@link SlackNotifier} for Slack messaging.
     */
    private Response handleJobEvent(IHTTPSession session) {
        String body = readBody(session);
        if (body == null) {
            return errorResponse("Failed to read request body");
        }

        String status = extractJsonField(body, "status");
        if (status == null || status.isEmpty()) {
            return errorResponse("Missing required field: status");
        }

        String jobId = extractJsonField(body, "jobId");
        String workstreamId = extractJsonField(body, "workstreamId");
        String description = extractJsonField(body, "description");

        JobCompletionEvent.Status eventStatus;
        try {
            eventStatus = JobCompletionEvent.Status.valueOf(status);
        } catch (IllegalArgumentException e) {
            return errorResponse("Invalid status: " + status);
        }

        JobCompletionEvent event;
        if (eventStatus == JobCompletionEvent.Status.FAILED) {
            String errorMessage = extractJsonField(body, "errorMessage");
            event = JobCompletionEvent.failed(jobId, workstreamId, description, errorMessage, null);
        } else {
            event = new JobCompletionEvent(jobId, workstreamId, eventStatus, description);
        }

        // Populate git info
        String targetBranch = extractJsonField(body, "targetBranch");
        String commitHash = extractJsonField(body, "commitHash");
        boolean pushed = extractJsonBooleanField(body, "pushed");
        List<String> stagedFiles = extractJsonArrayField(body, "stagedFiles");
        List<String> skippedFiles = extractJsonArrayField(body, "skippedFiles");
        event.withGitInfo(targetBranch, commitHash, stagedFiles, skippedFiles, pushed);

        // Populate Claude Code info
        String prompt = extractJsonField(body, "prompt");
        String sessionId = extractJsonField(body, "sessionId");
        int exitCode = extractJsonIntField(body, "exitCode");
        event.withClaudeCodeInfo(prompt, sessionId, exitCode);

        log("Job event: " + eventStatus + " for " + jobId);

        if (eventStatus == JobCompletionEvent.Status.STARTED) {
            notifier.onJobStarted(event);
        } else {
            notifier.onJobCompleted(event);
        }

        return newFixedLengthResponse(Response.Status.OK,
                "application/json", "{\"ok\":true}");
    }

    /**
     * Reads the POST body from a NanoHTTPD session.
     */
    private String readBody(IHTTPSession session) {
        try {
            Map<String, String> bodyMap = new HashMap<>();
            session.parseBody(bodyMap);
            return bodyMap.get("postData");
        } catch (IOException | ResponseException e) {
            warn("Error reading body: " + e.getMessage());
            return null;
        }
    }

    /**
     * Creates an error response with the specified message.
     */
    private Response errorResponse(String message) {
        String json = "{\"ok\":false,\"error\":\"" + escapeJson(message) + "\"}";
        return newFixedLengthResponse(Response.Status.BAD_REQUEST,
                "application/json", json);
    }

    /**
     * Simple JSON field extraction for lightweight parsing without
     * requiring a JSON library dependency.
     *
     * <p>Handles escaped quotes within values. For nested or complex JSON,
     * a proper JSON parser should be used instead.</p>
     *
     * @param json  the JSON string
     * @param field the field name to extract
     * @return the field value, or null if not found
     */
    static String extractJsonField(String json, String field) {
        if (json == null) return null;

        int fieldStart = json.indexOf("\"" + field + "\"");
        if (fieldStart < 0) return null;

        int colonPos = json.indexOf(":", fieldStart);
        if (colonPos < 0) return null;

        int valueStart = json.indexOf("\"", colonPos) + 1;
        if (valueStart <= 0) return null;

        // Handle escaped quotes
        StringBuilder sb = new StringBuilder();
        for (int i = valueStart; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                if (next == '"') {
                    sb.append('"');
                    i++;
                } else if (next == 'n') {
                    sb.append('\n');
                    i++;
                } else if (next == '\\') {
                    sb.append('\\');
                    i++;
                } else {
                    sb.append(c);
                }
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }

        return sb.toString();
    }

    /**
     * Extracts a boolean field from a JSON string.
     * Returns false if the field is not found.
     *
     * @param json  the JSON string
     * @param field the field name
     * @return the boolean value, or false if not found
     */
    static boolean extractJsonBooleanField(String json, String field) {
        if (json == null) return false;

        int fieldStart = json.indexOf("\"" + field + "\"");
        if (fieldStart < 0) return false;

        int colonPos = json.indexOf(":", fieldStart);
        if (colonPos < 0) return false;

        String rest = json.substring(colonPos + 1).trim();
        return rest.startsWith("true");
    }

    /**
     * Extracts an integer field from a JSON string.
     * Returns 0 if the field is not found or cannot be parsed.
     *
     * @param json  the JSON string
     * @param field the field name
     * @return the integer value, or 0 if not found
     */
    static int extractJsonIntField(String json, String field) {
        if (json == null) return 0;

        int fieldStart = json.indexOf("\"" + field + "\"");
        if (fieldStart < 0) return 0;

        int colonPos = json.indexOf(":", fieldStart);
        if (colonPos < 0) return 0;

        String rest = json.substring(colonPos + 1).trim();
        StringBuilder numStr = new StringBuilder();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '-' || (c >= '0' && c <= '9')) {
                numStr.append(c);
            } else if (numStr.length() > 0) {
                break;
            }
        }

        try {
            return Integer.parseInt(numStr.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Extracts a JSON array of strings from a JSON string.
     * Returns an empty list if the field is not found.
     *
     * @param json  the JSON string
     * @param field the field name
     * @return list of string values from the array
     */
    static List<String> extractJsonArrayField(String json, String field) {
        List<String> result = new ArrayList<>();
        if (json == null) return result;

        int fieldStart = json.indexOf("\"" + field + "\"");
        if (fieldStart < 0) return result;

        int colonPos = json.indexOf(":", fieldStart);
        if (colonPos < 0) return result;

        int arrayStart = json.indexOf("[", colonPos);
        if (arrayStart < 0) return result;

        int arrayEnd = json.indexOf("]", arrayStart);
        if (arrayEnd < 0) return result;

        String arrayContent = json.substring(arrayStart + 1, arrayEnd);
        if (arrayContent.trim().isEmpty()) return result;

        // Parse each quoted string in the array
        int i = 0;
        while (i < arrayContent.length()) {
            int quoteStart = arrayContent.indexOf("\"", i);
            if (quoteStart < 0) break;

            StringBuilder value = new StringBuilder();
            int j = quoteStart + 1;
            while (j < arrayContent.length()) {
                char c = arrayContent.charAt(j);
                if (c == '\\' && j + 1 < arrayContent.length()) {
                    char next = arrayContent.charAt(j + 1);
                    if (next == '"') {
                        value.append('"');
                        j += 2;
                    } else if (next == '\\') {
                        value.append('\\');
                        j += 2;
                    } else {
                        value.append(c);
                        j++;
                    }
                } else if (c == '"') {
                    break;
                } else {
                    value.append(c);
                    j++;
                }
            }

            result.add(value.toString());
            i = j + 1;
        }

        return result;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private static String truncate(String s, int maxLength) {
        if (s == null) return "";
        if (s.length() <= maxLength) return s;
        return s.substring(0, maxLength - 3) + "...";
    }
}
