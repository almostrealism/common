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
import io.flowtree.Server;
import io.flowtree.jobs.ClaudeCodeJob;
import io.flowtree.jobs.JobCompletionEvent;
import io.flowtree.msg.NodeProxy;
import org.almostrealism.io.ConsoleFeatures;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP API endpoint for FlowTree orchestration.
 *
 * <p>This server uses NanoHTTPD to expose a REST API for agent communication
 * and programmatic job submission. MCP tools running inside Claude Code sessions
 * call this endpoint to send messages back to channels, and external systems
 * (such as GitHub Actions) use it to submit new jobs.</p>
 *
 * <h2>URL Pattern</h2>
 * <table>
 *   <tr><th>Method</th><th>Path</th><th>Body</th><th>Description</th></tr>
 *   <tr><td>POST</td><td>/api/workstreams/{id}/messages</td>
 *       <td>{@code {"text":"..."}}</td>
 *       <td>Post a message to the workstream's channel</td></tr>
 *   <tr><td>POST</td><td>/api/workstreams/{id}/jobs/{jobId}/messages</td>
 *       <td>{@code {"text":"..."}}</td>
 *       <td>Post a message to the job's thread</td></tr>
 *   <tr><td>POST</td><td>/api/workstreams/{id}/submit</td>
 *       <td>{@code {"prompt":"..."}}</td>
 *       <td>Submit a new job to connected agents</td></tr>
 *   <tr><td>POST</td><td>/api/submit</td>
 *       <td>{@code {"prompt":"...","targetBranch":"..."}}</td>
 *       <td>Submit a job, resolving the workstream from the request body</td></tr>
 *   <tr><td>POST</td><td>/api/workstreams/{id}</td>
 *       <td>{@code {"jobId":"...","status":"..."}}</td>
 *       <td>Receive a status event for the workstream</td></tr>
 *   <tr><td>POST</td><td>/api/workstreams/{id}/jobs/{jobId}</td>
 *       <td>{@code {"jobId":"...","status":"..."}}</td>
 *       <td>Receive a job status event</td></tr>
 *   <tr><td>GET</td><td>/api/github/proxy?url=...</td><td>--</td>
 *       <td>Proxy a GET request to the GitHub API</td></tr>
 *   <tr><td>POST</td><td>/api/github/proxy?url=...</td>
 *       <td><i>raw JSON payload</i></td>
 *       <td>Proxy a POST request to the GitHub API</td></tr>
 *   <tr><td>GET</td><td>/api/health</td><td>--</td>
 *       <td>Health check</td></tr>
 * </table>
 *
 * @author Michael Murray
 * @see SlackNotifier
 * @see FlowTreeController
 */
public class FlowTreeApiEndpoint extends NanoHTTPD implements ConsoleFeatures {

    /** Default port for the API endpoint. */
    public static final int DEFAULT_PORT = 7780;

    /** Matches /api/workstreams/{wsId} with optional /jobs/{jobId} and optional /messages or /submit suffix. */
    private static final Pattern WORKSTREAM_PATTERN = Pattern.compile(
        "/api/workstreams/([^/]+)(?:/jobs/([^/]+))?(/messages|/submit)?"
    );

    private final SlackNotifier notifier;
    private final Map<String, Path> toolFiles = new HashMap<>();
    private JobStatsStore statsStore;

    private Server server;
    private SlackListener listener;

    /**
     * Creates a new API endpoint on the specified port.
     *
     * @param port     the port to listen on
     * @param notifier the notifier to delegate message operations to
     */
    public FlowTreeApiEndpoint(int port, SlackNotifier notifier) {
        super(port);
        this.notifier = notifier;
    }

    /**
     * Sets the FlowTree {@link Server} used for job submission.
     *
     * @param server the server accepting inbound agent connections
     */
    public void setServer(Server server) {
        this.server = server;
    }

    /**
     * Sets the {@link SlackListener} used for job creation.
     *
     * @param listener the listener that manages workstream-to-job mapping
     */
    public void setListener(SlackListener listener) {
        this.listener = listener;
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

    /**
     * Sets the stats store for the {@code /api/stats} endpoint.
     *
     * @param statsStore the stats store, or null to disable stats queries
     */
    public void setStatsStore(JobStatsStore statsStore) {
        this.statsStore = statsStore;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();

        if (Method.GET.equals(method) && "/api/health".equals(uri)) {
            return newFixedLengthResponse(Response.Status.OK,
                    "application/json", "{\"status\":\"ok\"}");
        }

        if (Method.GET.equals(method) && uri.startsWith("/api/stats")) {
            return handleStatsQuery(session);
        }

        if ("/api/github/proxy".equals(uri)
                && (Method.GET.equals(method) || Method.POST.equals(method))) {
            return handleGitHubProxy(session, method);
        }

        if (Method.POST.equals(method)) {
            if ("/api/submit".equals(uri)) {
                return handleSubmit(session, null);
            }

            Matcher m = WORKSTREAM_PATTERN.matcher(uri);
            if (m.matches()) {
                String workstreamId = m.group(1);
                String jobId = m.group(2);       // null if no /jobs/{id}
                String suffix = m.group(3);      // /messages, /submit, or null

                if ("/messages".equals(suffix)) {
                    return handleMessage(session, workstreamId, jobId);
                } else if ("/submit".equals(suffix)) {
                    return handleSubmit(session, workstreamId);
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
     * <p>Posts a message to the workstream's channel. When a
     * {@code jobId} is present, the controller can optionally thread the
     * message under that job's thread.</p>
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

        // Route to job's thread if one exists, otherwise post to channel
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
     * Handles {@code POST /api/workstreams/{id}/submit} for programmatic
     * job submission from external systems (e.g., GitHub Actions).
     *
     * <p>Request body:</p>
     * <pre>{@code
     * {
     *   "prompt": "Post a comment on PR #42...",
     *   "targetBranch": "feature/pipeline-agents",
     *   "baseBranch": "develop",
     *   "workstreamId": "ws-rings",
     *   "maxTurns": 30,
     *   "maxBudgetUsd": 5.0
     * }
     * }</pre>
     *
     * <p><b>Workstream resolution order:</b></p>
     * <ol>
     *   <li>If the request body contains a {@code workstreamId} field, use that workstream</li>
     *   <li>If the request body contains a {@code targetBranch}, search all registered
     *       workstreams for one whose {@code defaultBranch} matches exactly</li>
     *   <li>Fall back to the workstream identified by the URL path parameter</li>
     * </ol>
     *
     * <p>This allows pipeline jobs to automatically inherit the context (env vars,
     * MCP tools, allowed tools, budget) of an active workstream when one is
     * configured for the target branch.</p>
     *
     * @param session          the HTTP session
     * @param pathWorkstreamId the workstream identifier from the URL path (fallback)
     * @return JSON response with {@code ok}, {@code jobId}, and {@code workstreamId}
     */
    private Response handleSubmit(IHTTPSession session, String pathWorkstreamId) {
        String body = readBody(session);
        if (body == null) {
            return errorResponse("Failed to read request body");
        }

        String prompt = extractJsonField(body, "prompt");
        if (prompt == null || prompt.isEmpty()) {
            return errorResponse("Missing required field: prompt");
        }

        // Branch-to-workstream resolution:
        // 1. Explicit workstreamId in request body takes priority
        // 2. Search for a workstream whose defaultBranch matches targetBranch
        // 3. Fall back to the URL path workstream ID
        String targetBranch = extractJsonField(body, "targetBranch");
        String bodyWorkstreamId = extractJsonField(body, "workstreamId");

        SlackWorkstream workstream = null;
        String resolvedWorkstreamId = pathWorkstreamId;

        if (bodyWorkstreamId != null && !bodyWorkstreamId.isEmpty()) {
            workstream = notifier.getWorkstream(bodyWorkstreamId);
            if (workstream != null) {
                resolvedWorkstreamId = bodyWorkstreamId;
                log("Workstream resolved from request body: " + resolvedWorkstreamId);
            }
        }

        if (workstream == null && targetBranch != null && !targetBranch.isEmpty()) {
            SlackWorkstream branchMatch = notifier.findWorkstreamByBranch(targetBranch);
            if (branchMatch != null) {
                workstream = branchMatch;
                resolvedWorkstreamId = branchMatch.getWorkstreamId();
                log("Workstream resolved from branch match (" + targetBranch + "): "
                    + resolvedWorkstreamId);
            }
        }

        if (workstream == null && pathWorkstreamId != null) {
            workstream = notifier.getWorkstream(pathWorkstreamId);
        }

        if (workstream == null) {
            String detail = pathWorkstreamId != null
                ? "Unknown workstream: " + pathWorkstreamId
                : "No workstream found for branch: " + targetBranch;
            return errorResponse(detail);
        }

        // Validate git identity before submitting - commits will fail without it
        String gitUserName = workstream.getGitUserName();
        String gitUserEmail = workstream.getGitUserEmail();
        if (gitUserName == null || gitUserName.isEmpty()
                || gitUserEmail == null || gitUserEmail.isEmpty()) {
            return errorResponse("Git identity not configured for workstream "
                + resolvedWorkstreamId + ". Set gitUserName and gitUserEmail "
                + "in the workstream config or via /flowtree config.");
        }

        String workstreamId = resolvedWorkstreamId;

        if (server == null) {
            return errorResponse("No FlowTree server configured");
        }

        NodeProxy[] peers = server.getNodeGroup().getServers();
        if (peers.length == 0) {
            String json = "{\"ok\":false,\"error\":\"No agents connected\"}";
            return newFixedLengthResponse(Response.Status.OK,
                    "application/json", json);
        }

        // Apply optional overrides from the request body
        // (targetBranch was already extracted during workstream resolution above)
        String baseBranch = extractJsonField(body, "baseBranch");
        int maxTurns = extractJsonIntField(body, "maxTurns");
        double maxBudgetUsd = extractJsonDoubleField(body, "maxBudgetUsd");
        boolean protectTestFiles = extractJsonBooleanField(body, "protectTestFiles");

        // Create job factory with workstream defaults, overridden by request values
        ClaudeCodeJob.Factory factory = new ClaudeCodeJob.Factory(prompt);
        factory.setAllowedTools(workstream.getAllowedTools());
        factory.setMaxTurns(maxTurns > 0 ? maxTurns : workstream.getMaxTurns());
        factory.setMaxBudgetUsd(maxBudgetUsd > 0 ? maxBudgetUsd : workstream.getMaxBudgetUsd());

        String effectiveBranch = targetBranch != null ? targetBranch : workstream.getDefaultBranch();
        if (effectiveBranch != null) {
            factory.setTargetBranch(effectiveBranch);
            factory.setPushToOrigin(workstream.isPushToOrigin());
        }

        String effectiveBase = baseBranch != null ? baseBranch : workstream.getBaseBranch();
        if (effectiveBase != null) {
            factory.setBaseBranch(effectiveBase);
        }

        if (workstream.getWorkingDirectory() != null) {
            factory.setWorkingDirectory(workstream.getWorkingDirectory());
        }

        // Git identity
        if (workstream.getGitUserName() != null) {
            factory.setGitUserName(workstream.getGitUserName());
        }
        if (workstream.getGitUserEmail() != null) {
            factory.setGitUserEmail(workstream.getGitUserEmail());
        }

        // MCP configs from listener
        if (listener != null) {
            if (listener.getCentralizedMcpConfig() != null) {
                factory.setCentralizedMcpConfig(listener.getCentralizedMcpConfig());
            }
            if (listener.getPushedToolsConfig() != null) {
                factory.setPushedToolsConfig(listener.getPushedToolsConfig());
            }
        }

        // Per-workstream env vars
        if (workstream.getEnv() != null && !workstream.getEnv().isEmpty()) {
            factory.setWorkstreamEnv(workstream.getEnv());
        }

        // Planning document
        if (workstream.getPlanningDocument() != null) {
            factory.setPlanningDocument(workstream.getPlanningDocument());
        }

        // Test file protection
        if (protectTestFiles) {
            factory.setProtectTestFiles(true);
        }

        // Build workstream URL for status reporting
        int listeningPort = getListeningPort();
        if (listeningPort > 0) {
            String baseUrl = "http://0.0.0.0:" + listeningPort
                + "/api/workstreams/" + workstream.getWorkstreamId()
                + "/jobs/" + factory.getTaskId();
            factory.setWorkstreamUrl(baseUrl);
        }

        // Notify that work is starting
        String description = prompt.length() > 100 ? prompt.substring(0, 97) + "..." : prompt;
        JobCompletionEvent startEvent = JobCompletionEvent.started(factory.getTaskId(), description);
        startEvent.withGitInfo(effectiveBranch, null, null, null, false);
        notifier.onJobStarted(workstream.getWorkstreamId(), startEvent);

        // Round-robin to connected agents
        int index = peers.length > 1 ? (int) (System.currentTimeMillis() % peers.length) : 0;
        server.sendTask(factory, index);

        log("Submitted job via API: " + factory.getTaskId() + " to agent " + index);

        String json = "{\"ok\":true,\"jobId\":\"" + factory.getTaskId()
            + "\",\"workstreamId\":\"" + workstreamId + "\"}";
        return newFixedLengthResponse(Response.Status.OK,
                "application/json", json);
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

        // Populate timing info
        long durationMs = extractJsonLongField(body, "durationMs");
        long durationApiMs = extractJsonLongField(body, "durationApiMs");
        double costUsd = extractJsonDoubleField(body, "costUsd");
        int numTurns = extractJsonIntField(body, "numTurns");
        event.withTimingInfo(durationMs, durationApiMs, costUsd, numTurns);

        // Populate session details
        String subtype = extractJsonField(body, "subtype");
        boolean sessionIsError = extractJsonBooleanField(body, "sessionIsError");
        int permissionDenials = extractJsonIntField(body, "permissionDenials");
        List<String> deniedToolNames = extractJsonArrayField(body, "deniedToolNames");
        event.withSessionDetails(subtype, sessionIsError, permissionDenials, deniedToolNames);

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
     * Delegates to {@link io.flowtree.JsonFieldExtractor#extractString(String, String)}.
     */
    static String extractJsonField(String json, String field) {
        return io.flowtree.JsonFieldExtractor.extractString(json, field);
    }

    /**
     * Delegates to {@link io.flowtree.JsonFieldExtractor#extractBoolean(String, String)}.
     */
    static boolean extractJsonBooleanField(String json, String field) {
        return io.flowtree.JsonFieldExtractor.extractBoolean(json, field);
    }

    /**
     * Delegates to {@link io.flowtree.JsonFieldExtractor#extractInt(String, String)}.
     */
    static int extractJsonIntField(String json, String field) {
        return io.flowtree.JsonFieldExtractor.extractInt(json, field);
    }

    /**
     * Delegates to {@link io.flowtree.JsonFieldExtractor#extractLong(String, String)}.
     */
    static long extractJsonLongField(String json, String field) {
        return io.flowtree.JsonFieldExtractor.extractLong(json, field);
    }

    /**
     * Delegates to {@link io.flowtree.JsonFieldExtractor#extractDouble(String, String)}.
     */
    static double extractJsonDoubleField(String json, String field) {
        return io.flowtree.JsonFieldExtractor.extractDouble(json, field);
    }

    /**
     * Delegates to {@link io.flowtree.JsonFieldExtractor#extractStringArray(String, String)}.
     */
    static List<String> extractJsonArrayField(String json, String field) {
        return io.flowtree.JsonFieldExtractor.extractStringArray(json, field);
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

    /**
     * Handles {@code GET /api/stats}. Returns weekly job statistics as JSON.
     *
     * <p>Query parameters:</p>
     * <ul>
     *   <li>{@code workstream} - optional workstream ID filter</li>
     *   <li>{@code period} - reporting period; only {@code "weekly"} is
     *       currently supported (default). Unsupported values return a
     *       400 error.</li>
     * </ul>
     */
    private Response handleStatsQuery(IHTTPSession session) {
        if (statsStore == null) {
            return newFixedLengthResponse(Response.Status.OK,
                "application/json", "{\"error\":\"Stats not configured\"}");
        }

        String period = session.getParms().get("period");
        if (period != null && !period.isEmpty() && !"weekly".equals(period)) {
            return errorResponse("Unsupported period: " + period + ". Only 'weekly' is supported.");
        }

        String workstreamFilter = session.getParms().get("workstream");

        LocalDate today = LocalDate.now(ZoneId.of("UTC"));
        LocalDate thisWeekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate lastWeekStart = thisWeekStart.minusWeeks(1);

        StringBuilder json = new StringBuilder();
        json.append("{\"thisWeek\":");
        json.append(formatWeekJson(thisWeekStart, workstreamFilter));
        json.append(",\"lastWeek\":");
        json.append(formatWeekJson(lastWeekStart, workstreamFilter));
        json.append("}");

        return newFixedLengthResponse(Response.Status.OK,
            "application/json", json.toString());
    }

    /**
     * Handles requests to {@code /api/github/proxy} by forwarding them to the
     * GitHub API using the controller's {@code GITHUB_TOKEN}.
     *
     * <p>This endpoint allows agents to make GitHub API calls without needing
     * their own token. The controller acts as an authenticated proxy, so the
     * token only needs to be configured in one place.</p>
     *
     * <p>The HTTP method of the incoming request determines the method used
     * for the GitHub API call (GET or POST).</p>
     *
     * <p>Query parameters:</p>
     * <ul>
     *   <li>{@code url} &ndash; GitHub API path (e.g.,
     *       {@code /repos/owner/repo/pulls}) or a full URL starting with
     *       {@code https://}. Required.</li>
     * </ul>
     *
     * <p>For POST requests, the request body is forwarded as-is to the
     * GitHub API.</p>
     *
     * <p>Response body format:</p>
     * <pre>{@code {"status": 200, "link": "<pagination>", "body": <github-json>}}</pre>
     *
     * @param session the HTTP session
     * @param method  the HTTP method (determines GET or POST to GitHub)
     * @return JSON response wrapping the GitHub API response
     */
    private Response handleGitHubProxy(IHTTPSession session, Method method) {
        String githubToken = System.getenv("GITHUB_TOKEN");
        if (githubToken == null || githubToken.trim().isEmpty()) {
            return errorResponse("GITHUB_TOKEN not configured on controller");
        }

        String urlOrPath = session.getParms().get("url");
        if (urlOrPath == null || urlOrPath.isEmpty()) {
            return errorResponse("Missing required query parameter: url");
        }

        String githubMethod = Method.POST.equals(method) ? "POST" : "GET";

        // Read body for POST requests (forwarded to GitHub as-is)
        String payload = null;
        if (Method.POST.equals(method)) {
            payload = readBody(session);
        }

        // Resolve full GitHub API URL
        String fullUrl;
        if (urlOrPath.startsWith("https://")) {
            fullUrl = urlOrPath;
        } else {
            fullUrl = "https://api.github.com" + urlOrPath;
        }

        log("GitHub proxy " + githubMethod + " " + urlOrPath);

        try {
            URL url = URI.create(fullUrl).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(githubMethod);
            conn.setRequestProperty("Authorization", "Bearer " + githubToken.trim());
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);

            if ("POST".equals(githubMethod) && payload != null && !payload.isEmpty()) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                OutputStream os = conn.getOutputStream();
                os.write(payload.getBytes(StandardCharsets.UTF_8));
                os.close();
            }

            int status = conn.getResponseCode();
            String linkHeader = conn.getHeaderField("Link");

            InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String responseBody = "";
            if (is != null) {
                responseBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                is.close();
            }

            // Wrap response: {"status":N,"link":"...","body":<raw-github-json>}
            StringBuilder json = new StringBuilder();
            json.append("{\"status\":").append(status);
            json.append(",\"link\":\"")
                .append(escapeJson(linkHeader != null ? linkHeader : ""))
                .append("\"");
            json.append(",\"body\":")
                .append(responseBody.isEmpty() ? "null" : responseBody);
            json.append("}");

            return newFixedLengthResponse(Response.Status.OK,
                    "application/json", json.toString());
        } catch (Exception e) {
            log("GitHub proxy error: " + e.getMessage());
            return errorResponse("GitHub proxy error: " + e.getMessage());
        }
    }

    /**
     * Formats a week's stats as JSON.
     */
    private String formatWeekJson(LocalDate weekStart, String workstreamFilter) {
        StringBuilder json = new StringBuilder();
        json.append("{\"weekStart\":\"").append(weekStart).append("\",\"stats\":{");

        if (workstreamFilter != null && !workstreamFilter.isEmpty()) {
            JobStatsStore.WeeklyStats stats = statsStore.getWeeklyStats(workstreamFilter, weekStart);
            json.append("\"").append(escapeJson(workstreamFilter)).append("\":");
            appendStatsJson(json, stats);
        } else {
            Map<String, JobStatsStore.WeeklyStats> byWs = statsStore.getWeeklyStatsByWorkstream(weekStart);
            boolean first = true;
            for (Map.Entry<String, JobStatsStore.WeeklyStats> entry : byWs.entrySet()) {
                if (!first) json.append(",");
                first = false;
                json.append("\"").append(escapeJson(entry.getKey())).append("\":");
                appendStatsJson(json, entry.getValue());
            }
        }

        json.append("}}");
        return json.toString();
    }

    /**
     * Appends a single {@link JobStatsStore.WeeklyStats} as a JSON object.
     */
    private static void appendStatsJson(StringBuilder json, JobStatsStore.WeeklyStats stats) {
        json.append("{\"jobCount\":").append(stats.jobCount);
        json.append(",\"successCount\":").append(stats.successCount);
        json.append(",\"failedCount\":").append(stats.failedCount);
        json.append(",\"cancelledCount\":").append(stats.cancelledCount);
        json.append(",\"totalWallClockMs\":").append(stats.totalWallClockMs);
        json.append(",\"totalDurationMs\":").append(stats.totalDurationMs);
        json.append(",\"totalCostUsd\":").append(stats.totalCostUsd);
        json.append(",\"totalTurns\":").append(stats.totalTurns);
        json.append("}");
    }
}
