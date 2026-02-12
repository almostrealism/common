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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight HTTP API endpoint for receiving Slack message requests from
 * Claude Code agents running on Flowtree nodes.
 *
 * <p>This server uses NanoHTTPD to expose a simple REST API that MCP tools
 * (running inside Claude Code sessions) can call to send messages back to
 * Slack channels. It delegates all Slack operations to {@link SlackNotifier}.</p>
 *
 * <h2>URL Pattern</h2>
 * <p>All endpoints use the workstream URL as a prefix:</p>
 * <table>
 *   <tr><th>Method</th><th>Path</th><th>Body</th><th>Description</th></tr>
 *   <tr><td>POST</td><td>/api/workstreams/{id}/messages</td>
 *       <td>{@code {"text":"..."}}</td>
 *       <td>Post a message to the workstream's Slack channel</td></tr>
 *   <tr><td>POST</td><td>/api/workstreams/{id}/jobs/{jobId}/messages</td>
 *       <td>{@code {"text":"..."}}</td>
 *       <td>Post a message to the job's Slack thread</td></tr>
 *   <tr><td>POST</td><td>/api/workstreams/{id}</td>
 *       <td>{@code {"jobId":"...","status":"..."}}</td>
 *       <td>Receive a status event for the workstream</td></tr>
 *   <tr><td>POST</td><td>/api/workstreams/{id}/jobs/{jobId}</td>
 *       <td>{@code {"jobId":"...","status":"..."}}</td>
 *       <td>Receive a job status event</td></tr>
 *   <tr><td>GET</td><td>/api/health</td><td>--</td>
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

    /** Matches /api/workstreams/{wsId} with optional /jobs/{jobId} and optional /messages suffix. */
    private static final Pattern WORKSTREAM_PATTERN = Pattern.compile(
        "/api/workstreams/([^/]+)(?:/jobs/([^/]+))?(/messages)?"
    );

    private final SlackNotifier notifier;
    private final Map<String, Path> toolFiles = new HashMap<>();

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

    /**
     * Registers a pushed tool file that can be served via
     * {@code GET /api/tools/{name}}.
     *
     * @param name     the tool server name (e.g., "ar-slack")
     * @param filePath the path to the Python source file on disk
     */
    public void registerToolFile(String name, Path filePath) {
        toolFiles.put(name, filePath);
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();

        if (Method.GET.equals(method) && "/api/health".equals(uri)) {
            return newFixedLengthResponse(Response.Status.OK,
                    "application/json", "{\"status\":\"ok\"}");
        }

        if (Method.POST.equals(method)) {
            Matcher m = WORKSTREAM_PATTERN.matcher(uri);
            if (m.matches()) {
                String workstreamId = m.group(1);
                String jobId = m.group(2);       // null if no /jobs/{id}
                boolean isMessages = m.group(3) != null;  // /messages suffix

                if (isMessages) {
                    return handleMessage(session, workstreamId, jobId);
                } else {
                    return handleStatusEvent(session, workstreamId);
                }
            }
        }

        if (Method.GET.equals(method) && uri.startsWith("/api/tools/")) {
            String toolName = uri.substring("/api/tools/".length());
            return handleToolDownload(toolName);
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND,
                MIME_PLAINTEXT, "Not found");
    }

    /**
     * Handles {@code GET /api/tools/{name}} by serving the registered
     * Python source file as {@code text/plain}.
     *
     * @param name the tool server name
     * @return the file content or a 404 response
     */
    private Response handleToolDownload(String name) {
        Path filePath = toolFiles.get(name);
        if (filePath == null || !Files.exists(filePath)) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND,
                    MIME_PLAINTEXT, "Tool not found: " + name);
        }

        try {
            String content = Files.readString(filePath, StandardCharsets.UTF_8);
            log("Served pushed tool: " + name + " (" + content.length() + " bytes)");
            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, content);
        } catch (IOException e) {
            warn("Failed to read tool file " + name + ": " + e.getMessage());
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                    MIME_PLAINTEXT, "Failed to read tool file");
        }
    }

    /**
     * Handles POST to {@code /api/workstreams/{id}/messages} or
     * {@code /api/workstreams/{id}/jobs/{jobId}/messages}.
     *
     * <p>Posts a Slack message to the workstream's channel. When a
     * {@code jobId} is present, the controller can optionally thread the
     * message under that job's Slack thread.</p>
     */
    private Response handleMessage(IHTTPSession session, String workstreamId, String jobId) {
        String body = readBody(session);
        if (body == null) {
            return errorResponse("Failed to read request body");
        }

        String text = extractJsonField(body, "text");
        if (text == null || text.isEmpty()) {
            return errorResponse("Missing required field: text");
        }

        SlackWorkstream workstream = notifier.getWorkstream(workstreamId);
        if (workstream == null) {
            return errorResponse("Unknown workstream: " + workstreamId);
        }

        log("Message [" + workstreamId + (jobId != null ? "/" + jobId : "") + "]: " + truncate(text, 80));

        // Route to job's Slack thread if one exists, otherwise post to channel
        String threadTs = jobId != null ? notifier.getThreadTs(jobId) : null;
        if (threadTs != null) {
            notifier.postMessageInThread(workstream.getChannelId(), text, threadTs);
        } else {
            notifier.postMessage(workstream.getChannelId(), text);
        }

        return newFixedLengthResponse(Response.Status.OK,
                "application/json", "{\"ok\":true}");
    }

    /**
     * Handles POST to {@code /api/workstreams/{id}} or
     * {@code /api/workstreams/{id}/jobs/{jobId}} -- receives a status event
     * from an agent and delegates to the {@link SlackNotifier}.
     */
    private Response handleStatusEvent(IHTTPSession session, String workstreamId) {
        String body = readBody(session);
        if (body == null) {
            return errorResponse("Failed to read request body");
        }

        String status = extractJsonField(body, "status");
        if (status == null || status.isEmpty()) {
            return errorResponse("Missing required field: status");
        }

        String jobId = extractJsonField(body, "jobId");
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
            event = JobCompletionEvent.failed(jobId, description, errorMessage, null);
        } else {
            event = new JobCompletionEvent(jobId, eventStatus, description);
        }

        // Populate git info
        String targetBranch = extractJsonField(body, "targetBranch");
        String commitHash = extractJsonField(body, "commitHash");
        boolean pushed = extractJsonBooleanField(body, "pushed");
        List<String> stagedFiles = extractJsonArrayField(body, "stagedFiles");
        List<String> skippedFiles = extractJsonArrayField(body, "skippedFiles");
        event.withGitInfo(targetBranch, commitHash, stagedFiles, skippedFiles, pushed);

        // Populate PR URL
        String pullRequestUrl = extractJsonField(body, "pullRequestUrl");
        if (pullRequestUrl != null) {
            event.withPullRequestUrl(pullRequestUrl);
        }

        // Populate Claude Code info
        String prompt = extractJsonField(body, "prompt");
        String sessionId = extractJsonField(body, "sessionId");
        int exitCode = extractJsonIntField(body, "exitCode");
        event.withClaudeCodeInfo(prompt, sessionId, exitCode);

        log("Status event: " + eventStatus + " for job " + jobId + " in workstream " + workstreamId);

        if (eventStatus == JobCompletionEvent.Status.STARTED) {
            notifier.onJobStarted(workstreamId, event);
        } else {
            notifier.onJobCompleted(workstreamId, event);
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
