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
import io.flowtree.jobs.ClaudeCodeJobEvent;
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
 *   <tr><td>POST</td><td>/api/workstreams</td>
 *       <td>{@code {"defaultBranch":"...","baseBranch":"...","planningDocument":"..."}}</td>
 *       <td>Register a new workstream (auto-creates Slack channel)</td></tr>
 *   <tr><td>POST</td><td>/api/workstreams/{id}/update</td>
 *       <td>{@code {"channelId":"...","channelName":"..."}}</td>
 *       <td>Update an existing workstream</td></tr>
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
 *   <tr><td>PUT</td><td>/api/github/proxy?url=...</td>
 *       <td><i>raw JSON payload</i></td>
 *       <td>Proxy a PUT request to the GitHub API</td></tr>
 *   <tr><td>GET</td><td>/api/workstreams</td><td>--</td>
 *       <td>List all registered workstreams with capabilities</td></tr>
 *   <tr><td>GET</td><td>/api/config/accept-automated-jobs</td><td>--</td>
 *       <td>Check whether automated job submissions are accepted</td></tr>
 *   <tr><td>POST</td><td>/api/config/accept-automated-jobs</td>
 *       <td>{@code {"accept":true}}</td>
 *       <td>Enable or disable automated job submissions</td></tr>
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

    /** Matches /api/workstreams/{wsId} with optional /jobs/{jobId} and optional /messages, /submit, or /update suffix. */
    private static final Pattern WORKSTREAM_PATTERN = Pattern.compile(
        "/api/workstreams/([^/]+)(?:/jobs/([^/]+))?(/messages|/submit|/update)?"
    );

    private final SlackNotifier notifier;
    private final Map<String, Path> toolFiles = new HashMap<>();
    private JobStatsStore statsStore;
    private Map<String, String> githubOrgTokens = new HashMap<>();

    /** Tracks which jobs should have a PR auto-created on success. */
    private final Map<String, AutoPrContext> autoCreatePrJobs = new HashMap<>();

    /**
     * Controls whether jobs that self-identify as automated (e.g., from CI
     * pipelines) are accepted. When {@code false}, submissions with
     * {@code "automated": true} in the request body are rejected.
     *
     * <p>Defaults to {@code false}. Toggle via
     * {@code POST /api/config/accept-automated-jobs} or
     * {@code GET /api/config/accept-automated-jobs}.</p>
     */
    private volatile boolean acceptAutomatedJobs = false;

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
     * Sets per-organization GitHub tokens for the proxy endpoint.
     *
     * <p>When a proxy request includes an {@code org} query parameter,
     * the matching token from this map is used instead of the default
     * instance-level token.</p>
     *
     * @param githubOrgTokens map of organization name to token
     */
    public void setGithubOrgTokens(Map<String, String> githubOrgTokens) {
        this.githubOrgTokens = githubOrgTokens != null ? githubOrgTokens : new HashMap<>();
    }

    /**
     * Registers a pushed tool file that can be served via
     * {@code GET /api/tools/{name}}.
     *
     * @param name     the tool server name (e.g., "ar-messages")
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

    /**
     * Returns whether automated job submissions are currently accepted.
     *
     * @return {@code true} if automated jobs are accepted, {@code false} otherwise
     */
    public boolean isAcceptAutomatedJobs() {
        return acceptAutomatedJobs;
    }

    /**
     * Sets whether automated job submissions are accepted.
     *
     * @param accept {@code true} to accept automated jobs, {@code false} to reject them
     */
    public void setAcceptAutomatedJobs(boolean accept) {
        this.acceptAutomatedJobs = accept;
        log("Automated job acceptance " + (accept ? "enabled" : "disabled"));
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

        if (Method.GET.equals(method) && "/api/workstreams".equals(uri)) {
            return handleListWorkstreams();
        }

        if ("/api/config/accept-automated-jobs".equals(uri)) {
            if (Method.GET.equals(method)) {
                log("Config query: acceptAutomatedJobs=" + acceptAutomatedJobs);
                String json = "{\"acceptAutomatedJobs\":" + acceptAutomatedJobs + "}";
                return newFixedLengthResponse(Response.Status.OK,
                        "application/json", json);
            }
            if (Method.POST.equals(method)) {
                String configBody = readBody(session);
                if (configBody != null && configBody.contains("\"accept\"")) {
                    boolean accept = extractJsonBooleanField(configBody, "accept");
                    log("Config update request: accept-automated-jobs="
                            + accept + " (was " + acceptAutomatedJobs + ")");
                    setAcceptAutomatedJobs(accept);
                } else {
                    log("Config update request for accept-automated-jobs"
                            + " missing 'accept' field in body");
                }
                String json = "{\"ok\":true,\"acceptAutomatedJobs\":" + acceptAutomatedJobs + "}";
                return newFixedLengthResponse(Response.Status.OK,
                        "application/json", json);
            }
        }

        if ("/api/github/proxy".equals(uri)
                && (Method.GET.equals(method) || Method.POST.equals(method)
                    || Method.PUT.equals(method))) {
            return handleGitHubProxy(session, method);
        }

        if (Method.POST.equals(method)) {
            if ("/api/submit".equals(uri)) {
                return handleSubmit(session, null);
            }

            if ("/api/workstreams".equals(uri)) {
                return handleRegisterWorkstream(session);
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
                } else if ("/update".equals(suffix)) {
                    return handleUpdateWorkstream(session, workstreamId);
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

        // Route to job's thread if one exists, otherwise post to channel.
        // Notification delivery is best-effort -- always return ok so the
        // agent does not treat a missing channel as a hard failure.
        String threadTs = jobId != null ? notifier.getThreadTs(jobId) : null;
        String resultTs;
        if (threadTs != null) {
            resultTs = notifier.postMessageInThread(workstream.getChannelId(), text, threadTs);
        } else {
            resultTs = notifier.postMessage(workstream.getChannelId(), text);
        }

        if (resultTs == null) {
            log("Message received for workstream " + workstreamId
                + " but no notification channel is configured");
        }

        return newFixedLengthResponse(Response.Status.OK,
                "application/json",
                resultTs != null
                    ? "{\"ok\":true}"
                    : "{\"ok\":true,\"warning\":\"no notification channel configured\"}");
    }

    /**
     * Handles {@code POST /api/workstreams} to register a new workstream.
     *
     * <p>Request body:</p>
     * <pre>{@code
     * {
     *   "defaultBranch": "project/plan-20260223-foo",
     *   "baseBranch": "master",
     *   "repoUrl": "https://github.com/org/repo.git",
     *   "planningDocument": "docs/plans/PLAN-20260223-foo.md",
     *   "channelName": "project-plan-20260223-foo"
     * }
     * }</pre>
     *
     * <p>If a {@code channelName} is provided and Slack is available, a new
     * channel is created automatically. If Slack is not available (simulation
     * mode), the workstream is registered without a channel.</p>
     *
     * @param session the HTTP session
     * @return JSON response with {@code workstreamId}, {@code channelId},
     *         and {@code channelName}
     */
    private Response handleRegisterWorkstream(IHTTPSession session) {
        String body = readBody(session);
        if (body == null) {
            return errorResponse("Failed to read request body");
        }

        String defaultBranch = extractJsonField(body, "defaultBranch");
        if (defaultBranch == null || defaultBranch.isEmpty()) {
            return errorResponse("Missing required field: defaultBranch");
        }

        String baseBranch = extractJsonField(body, "baseBranch");
        String repoUrl = extractJsonField(body, "repoUrl");
        String planningDocument = extractJsonField(body, "planningDocument");
        String channelName = extractJsonField(body, "channelName");

        // Check for an existing workstream with the same branch and repo
        SlackWorkstream existing = notifier.findWorkstreamByBranchAndRepo(defaultBranch, repoUrl);
        if (existing != null) {
            log("Workstream already exists for branch " + defaultBranch
                + ": " + existing.getWorkstreamId() + " — returning existing");

            StringBuilder json = new StringBuilder();
            json.append("{\"ok\":true,\"existing\":true");
            json.append(",\"workstreamId\":\"").append(escapeJson(existing.getWorkstreamId())).append("\"");
            json.append("}");

            return newFixedLengthResponse(Response.Status.OK,
                    "application/json", json.toString());
        }

        // Auto-create Slack channel if a name is provided
        String channelId = null;
        if (channelName != null && !channelName.isEmpty()) {
            channelId = notifier.createChannel(channelName);
        }

        SlackWorkstream workstream;
        if (channelId != null) {
            workstream = new SlackWorkstream(channelId, "#" + channelName);
        } else {
            workstream = new SlackWorkstream(null, channelName);
        }

        workstream.setDefaultBranch(defaultBranch);

        if (baseBranch != null && !baseBranch.isEmpty()) {
            workstream.setBaseBranch(baseBranch);
        }

        if (repoUrl != null && !repoUrl.isEmpty()) {
            workstream.setRepoUrl(repoUrl);
        }

        if (planningDocument != null && !planningDocument.isEmpty()) {
            workstream.setPlanningDocument(planningDocument);
        }

        workstream.setPushToOrigin(true);

        if (listener != null) {
            listener.registerAndPersistWorkstream(workstream);
        } else {
            notifier.registerWorkstream(workstream);
        }

        log("Registered workstream via API: " + workstream.getWorkstreamId()
            + " (branch=" + defaultBranch + ", channel=" + channelName + ")");

        StringBuilder json = new StringBuilder();
        json.append("{\"ok\":true");
        json.append(",\"workstreamId\":\"").append(escapeJson(workstream.getWorkstreamId())).append("\"");
        if (channelId != null) {
            json.append(",\"channelId\":\"").append(escapeJson(channelId)).append("\"");
        }
        if (channelName != null) {
            json.append(",\"channelName\":\"").append(escapeJson(channelName)).append("\"");
        }
        json.append("}");

        return newFixedLengthResponse(Response.Status.OK,
                "application/json", json.toString());
    }

    /**
     * Handles {@code POST /api/workstreams/{id}/update} to update an existing workstream.
     *
     * <p>Supports updating any combination of: {@code channelId}, {@code channelName},
     * {@code defaultBranch}, {@code baseBranch}, {@code repoUrl},
     * {@code planningDocument}.</p>
     *
     * @param session      the HTTP session
     * @param workstreamId the workstream identifier from the URL path
     * @return JSON response confirming the update
     */
    private Response handleUpdateWorkstream(IHTTPSession session, String workstreamId) {
        String body = readBody(session);
        if (body == null) {
            return errorResponse("Failed to read request body");
        }

        SlackWorkstream workstream = notifier.getWorkstream(workstreamId);
        if (workstream == null) {
            return errorResponse("Unknown workstream: " + workstreamId);
        }

        String channelId = extractJsonField(body, "channelId");
        String channelName = extractJsonField(body, "channelName");
        String defaultBranch = extractJsonField(body, "defaultBranch");
        String baseBranch = extractJsonField(body, "baseBranch");
        String repoUrl = extractJsonField(body, "repoUrl");
        String planningDocument = extractJsonField(body, "planningDocument");

        if (channelId != null && !channelId.isEmpty()) {
            workstream.setChannelId(channelId);
        }
        if (channelName != null && !channelName.isEmpty()) {
            workstream.setChannelName(channelName);
        }
        if (defaultBranch != null && !defaultBranch.isEmpty()) {
            workstream.setDefaultBranch(defaultBranch);
        }
        if (baseBranch != null && !baseBranch.isEmpty()) {
            workstream.setBaseBranch(baseBranch);
        }
        if (repoUrl != null && !repoUrl.isEmpty()) {
            workstream.setRepoUrl(repoUrl);
        }
        if (planningDocument != null && !planningDocument.isEmpty()) {
            workstream.setPlanningDocument(planningDocument);
        }

        if (listener != null) {
            listener.registerAndPersistWorkstream(workstream);
        }

        log("Updated workstream via API: " + workstreamId);

        return newFixedLengthResponse(Response.Status.OK,
                "application/json", "{\"ok\":true,\"workstreamId\":\"" + escapeJson(workstreamId) + "\"}");
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
     *   "baseBranch": "master",
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

        // Reject automated jobs when the gate is closed
        boolean automated = extractJsonBooleanField(body, "automated");
        if (automated && !acceptAutomatedJobs) {
            log("Rejected automated job submission (automated jobs are currently disabled)");
            String json = "{\"ok\":false,\"error\":\"Automated job submissions are currently disabled\","
                + "\"automated\":true}";
            return newFixedLengthResponse(Response.Status.OK,
                    "application/json", json);
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

        // Timestamp guard: skip submission if a newer job already exists
        // on this workstream. This prevents stale auto-resolve jobs from
        // CI pipelines that ran hours ago from colliding with explicitly
        // submitted work.
        String startedAfterStr = extractJsonField(body, "startedAfter");
        if (startedAfterStr != null && !startedAfterStr.isEmpty()) {
            try {
                long startedAfter = Long.parseLong(startedAfterStr);
                if (notifier.hasJobStartedAfter(workstreamId, startedAfter)) {
                    log("Skipping job submission — newer job exists on workstream "
                        + workstreamId + " (startedAfter=" + startedAfter + ")");
                    String json = "{\"ok\":true,\"skipped\":true,"
                        + "\"reason\":\"Newer job exists on this workstream\"}";
                    return newFixedLengthResponse(Response.Status.OK,
                            "application/json", json);
                }
            } catch (NumberFormatException e) {
                log("Invalid startedAfter value: " + startedAfterStr);
            }
        }

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
        String repoUrl = extractJsonField(body, "repoUrl");
        String baseBranch = extractJsonField(body, "baseBranch");
        String jobDescription = extractJsonField(body, "description");
        int maxTurns = extractJsonIntField(body, "maxTurns");
        double maxBudgetUsd = extractJsonDoubleField(body, "maxBudgetUsd");
        boolean protectTestFiles = extractJsonBooleanField(body, "protectTestFiles");
        boolean enforceChanges = extractJsonBooleanField(body, "enforceChanges");
        boolean autoCreatePr = extractJsonBooleanField(body, "autoCreatePr");

        // Create job factory with workstream defaults, overridden by request values
        ClaudeCodeJob.Factory factory = new ClaudeCodeJob.Factory(prompt);
        if (jobDescription != null && !jobDescription.isEmpty()) {
            factory.setDescription(jobDescription);
        }
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

        // Repository URL for automatic checkout (request body overrides workstream default)
        String effectiveRepoUrl = repoUrl != null ? repoUrl : workstream.getRepoUrl();
        if (effectiveRepoUrl != null) {
            factory.setRepoUrl(effectiveRepoUrl);
        }

        // Default workspace path from listener (global config)
        if (listener != null && listener.getDefaultWorkspacePath() != null) {
            factory.setDefaultWorkspacePath(listener.getDefaultWorkspacePath());
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

        // GitHub organization for token selection
        if (workstream.getGithubOrg() != null) {
            factory.setGithubOrg(workstream.getGithubOrg());
        }

        // Test file protection
        if (protectTestFiles) {
            factory.setProtectTestFiles(true);
        }

        // Enforcement mode (require code changes or loop)
        if (enforceChanges) {
            factory.setEnforceChanges(true);
        }

        // Auto-create PR on successful completion
        if (autoCreatePr) {
            factory.setAutoCreatePr(true);
            autoCreatePrJobs.put(factory.getTaskId(), new AutoPrContext(
                effectiveRepoUrl, effectiveBase, workstream.getGithubOrg(),
                jobDescription != null ? jobDescription : ClaudeCodeJob.summarizePrompt(prompt)));
        }

        // Build workstream URL for status reporting
        int listeningPort = getListeningPort();
        if (listeningPort > 0) {
            String baseUrl = "http://0.0.0.0:" + listeningPort
                + "/api/workstreams/" + workstream.getWorkstreamId()
                + "/jobs/" + factory.getTaskId();
            factory.setWorkstreamUrl(baseUrl);
        }

        // Notify that the job has been submitted (not yet executing)
        String displaySummary = jobDescription != null && !jobDescription.isEmpty()
            ? jobDescription : ClaudeCodeJob.summarizePrompt(prompt);
        JobCompletionEvent startEvent = JobCompletionEvent.started(factory.getTaskId(), displaySummary);
        startEvent.withGitInfo(effectiveBranch, null, null, null, false);
        notifier.onJobSubmitted(workstream.getWorkstreamId(), startEvent);

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

        // Populate Claude Code info
        String prompt = extractJsonField(body, "prompt");
        String sessionId = extractJsonField(body, "sessionId");
        int exitCode = extractJsonIntField(body, "exitCode");

        // Create the appropriate event type based on whether Claude-specific fields are present
        boolean isClaudeCodeEvent = prompt != null || sessionId != null;

        JobCompletionEvent event;
        if (isClaudeCodeEvent) {
            if (eventStatus == JobCompletionEvent.Status.FAILED) {
                String errorMessage = extractJsonField(body, "errorMessage");
                event = ClaudeCodeJobEvent.failed(jobId, description, errorMessage, null);
            } else {
                event = new ClaudeCodeJobEvent(jobId, eventStatus, description);
            }
        } else {
            if (eventStatus == JobCompletionEvent.Status.FAILED) {
                String errorMessage = extractJsonField(body, "errorMessage");
                event = JobCompletionEvent.failed(jobId, description, errorMessage, null);
            } else {
                event = new JobCompletionEvent(jobId, eventStatus, description);
            }
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

        // Populate Claude Code-specific fields
        if (event instanceof ClaudeCodeJobEvent) {
            ClaudeCodeJobEvent ccEvent = (ClaudeCodeJobEvent) event;
            ccEvent.withClaudeCodeInfo(prompt, sessionId, exitCode);

            // Populate timing info
            long durationMs = extractJsonLongField(body, "durationMs");
            long durationApiMs = extractJsonLongField(body, "durationApiMs");
            double costUsd = extractJsonDoubleField(body, "costUsd");
            int numTurns = extractJsonIntField(body, "numTurns");
            ccEvent.withTimingInfo(durationMs, durationApiMs, costUsd, numTurns);

            // Populate session details
            String subtype = extractJsonField(body, "subtype");
            boolean sessionIsError = extractJsonBooleanField(body, "sessionIsError");
            int permissionDenials = extractJsonIntField(body, "permissionDenials");
            List<String> deniedToolNames = extractJsonArrayField(body, "deniedToolNames");
            ccEvent.withSessionDetails(subtype, sessionIsError, permissionDenials, deniedToolNames);
        }

        log("Status event: " + eventStatus + " for job " + jobId + " in workstream " + workstreamId);

        // Auto-create PR on successful completion if requested at submission time
        if (eventStatus == JobCompletionEvent.Status.SUCCESS && jobId != null) {
            AutoPrContext prCtx = autoCreatePrJobs.remove(jobId);
            if (prCtx != null && event.getTargetBranch() != null
                    && event.getPullRequestUrl() == null) {
                // Resolve org: explicit config first, then derive from repo URL
                String effectiveOrg = prCtx.githubOrg;
                if (effectiveOrg == null || effectiveOrg.isEmpty()) {
                    effectiveOrg = extractOrgFromRepoUrl(prCtx.repoUrl);
                }
                String token = resolveGithubToken(effectiveOrg);
                if (token != null) {
                    String ownerRepo = extractOwnerRepo(prCtx.repoUrl);
                    if (ownerRepo != null) {
                        String base = prCtx.baseBranch != null ? prCtx.baseBranch : "master";
                        String prUrl = createGitHubPullRequest(
                            ownerRepo, event.getTargetBranch(), base,
                            prCtx.description, prCtx.description, token);
                        if (prUrl != null) {
                            event.withPullRequestUrl(prUrl);
                        }
                    } else {
                        log("Cannot auto-create PR: unable to extract owner/repo from " + prCtx.repoUrl);
                    }
                } else {
                    log("Cannot auto-create PR: no GitHub token available for org " + effectiveOrg);
                }
            }
        } else if (jobId != null) {
            // Clean up context for non-success completions
            autoCreatePrJobs.remove(jobId);
        }

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
     * Handles {@code GET /api/workstreams}. Returns a JSON array of all
     * registered workstreams with their configuration and capabilities.
     *
     * <p>Sensitive fields (git credentials, env vars, tokens) are omitted.
     * Each entry includes a {@code pipelineCapable} boolean indicating
     * whether the workstream has the configuration needed for Tier 2
     * pipeline operations (requires {@code repoUrl}).</p>
     *
     * @return JSON response with an array of workstream summaries
     */
    private Response handleListWorkstreams() {
        Map<String, SlackWorkstream> workstreams = notifier.getWorkstreams();

        StringBuilder json = new StringBuilder();
        json.append("[");

        boolean first = true;
        for (SlackWorkstream ws : workstreams.values()) {
            if (!first) json.append(",");
            first = false;

            String repoUrl = ws.getRepoUrl();
            String planningDoc = ws.getPlanningDocument();
            boolean pipelineCapable = repoUrl != null && !repoUrl.isEmpty();

            json.append("{");
            json.append("\"workstreamId\":\"").append(escapeJson(ws.getWorkstreamId())).append("\"");

            if (ws.getChannelName() != null) {
                json.append(",\"channelName\":\"").append(escapeJson(ws.getChannelName())).append("\"");
            }
            if (ws.getDefaultBranch() != null) {
                json.append(",\"defaultBranch\":\"").append(escapeJson(ws.getDefaultBranch())).append("\"");
            }
            if (ws.getBaseBranch() != null) {
                json.append(",\"baseBranch\":\"").append(escapeJson(ws.getBaseBranch())).append("\"");
            }
            if (repoUrl != null) {
                json.append(",\"repoUrl\":\"").append(escapeJson(repoUrl)).append("\"");
            }

            if (ws.getGithubOrg() != null) {
                json.append(",\"githubOrg\":\"").append(escapeJson(ws.getGithubOrg())).append("\"");
            }

            if (planningDoc != null && !planningDoc.isEmpty()) {
                json.append(",\"planningDocument\":\"").append(escapeJson(planningDoc)).append("\"");
            }
            json.append(",\"hasPlanningDocument\":").append(planningDoc != null && !planningDoc.isEmpty());
            json.append(",\"pipelineCapable\":").append(pipelineCapable);
            json.append(",\"agentCount\":").append(ws.getAgents().size());
            json.append("}");
        }

        json.append("]");

        return newFixedLengthResponse(Response.Status.OK,
                "application/json", json.toString());
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
     * GitHub API using per-organization tokens from workstreams.yaml.
     *
     * <p>This endpoint allows agents to make GitHub API calls without needing
     * their own token. The controller acts as an authenticated proxy using
     * per-org tokens configured in the {@code githubOrgs} section of
     * workstreams.yaml.</p>
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
        // Resolve token from per-org tokens in workstreams.yaml
        String org = session.getParms().get("org");
        String token = resolveGithubToken(org);
        if (token == null) {
            String detail = (org != null && !org.isEmpty())
                    ? "No GitHub token configured for org '" + org
                      + "' (configured orgs: " + githubOrgTokens.keySet() + ")"
                    : "No GitHub org token available (configured orgs: "
                      + githubOrgTokens.keySet()
                      + "; pass ?org= or configure githubOrgs in workstreams.yaml)";
            warn("GitHub proxy token resolution failed: " + detail);
            return errorResponse(detail);
        }

        String urlOrPath = session.getParms().get("url");
        if (urlOrPath == null || urlOrPath.isEmpty()) {
            return errorResponse("Missing required query parameter: url");
        }

        String githubMethod;
        if (Method.POST.equals(method)) {
            githubMethod = "POST";
        } else if (Method.PUT.equals(method)) {
            githubMethod = "PUT";
        } else {
            githubMethod = "GET";
        }

        // Read body for POST/PUT requests (forwarded to GitHub as-is)
        String payload = null;
        if (Method.POST.equals(method) || Method.PUT.equals(method)) {
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
            conn.setRequestProperty("Authorization", "Bearer " + token.trim());
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);

            if (("POST".equals(githubMethod) || "PUT".equals(githubMethod))
                    && payload != null && !payload.isEmpty()) {
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

    /**
     * Context stored at job submission time for auto-creating a pull request
     * when the job completes successfully.
     */
    private static class AutoPrContext {
        final String repoUrl;
        final String baseBranch;
        final String githubOrg;
        final String description;

        AutoPrContext(String repoUrl, String baseBranch, String githubOrg, String description) {
            this.repoUrl = repoUrl;
            this.baseBranch = baseBranch;
            this.githubOrg = githubOrg;
            this.description = description;
        }
    }

    /**
     * Extracts the GitHub {@code owner/repo} from a git repository URL.
     *
     * <p>Handles both HTTPS ({@code https://github.com/owner/repo.git})
     * and SSH ({@code git@github.com:owner/repo.git}) URL formats.</p>
     *
     * @param repoUrl the repository URL
     * @return the {@code owner/repo} string, or null if the URL is not recognized
     */
    private static String extractOwnerRepo(String repoUrl) {
        if (repoUrl == null) return null;
        // SSH format: git@github.com:owner/repo.git
        Matcher ssh = Pattern.compile("git@github\\.com:([^/]+/[^/]+?)(?:\\.git)?$").matcher(repoUrl);
        if (ssh.find()) return ssh.group(1);
        // HTTPS format: https://github.com/owner/repo.git
        Matcher https = Pattern.compile("github\\.com/([^/]+/[^/]+?)(?:\\.git)?$").matcher(repoUrl);
        if (https.find()) return https.group(1);
        return null;
    }

    /**
     * Creates a GitHub pull request using the controller's token.
     *
     * @param ownerRepo the {@code owner/repo} string
     * @param head      the head branch name
     * @param base      the base branch name
     * @param title     the PR title
     * @param body      the PR body text
     * @param token     the GitHub API token
     * @return the pull request URL, or null on failure
     */
    private String createGitHubPullRequest(String ownerRepo, String head, String base,
                                            String title, String body, String token) {
        try {
            String apiUrl = "https://api.github.com/repos/" + ownerRepo + "/pulls";
            String payload = "{\"title\":\"" + escapeJson(title)
                    + "\",\"head\":\"" + escapeJson(head)
                    + "\",\"base\":\"" + escapeJson(base)
                    + "\",\"body\":\"" + escapeJson(body) + "\"}";

            URL url = URI.create(apiUrl).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + token.trim());
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setDoOutput(true);
            OutputStream os = conn.getOutputStream();
            os.write(payload.getBytes(StandardCharsets.UTF_8));
            os.close();

            int status = conn.getResponseCode();
            InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String responseBody = is != null
                    ? new String(is.readAllBytes(), StandardCharsets.UTF_8) : "";
            if (is != null) is.close();

            if (status == 201) {
                Matcher m = Pattern.compile("\"html_url\"\\s*:\\s*\"([^\"]+)\"").matcher(responseBody);
                if (m.find()) {
                    String prUrl = m.group(1);
                    log("Auto-created PR: " + prUrl);
                    return prUrl;
                }
            }

            log("GitHub PR creation returned HTTP " + status + ": " + responseBody.substring(0,
                    Math.min(200, responseBody.length())));
        } catch (Exception e) {
            log("GitHub PR creation failed: " + e.getMessage());
        }
        return null;
    }

    /**
     * Resolves the GitHub API token for the given organization.
     *
     * <p>Looks up the token from the per-org token map populated from
     * the {@code githubOrgs} section of workstreams.yaml. If the org
     * is not specified but only one org token is configured, that token
     * is used as the default.</p>
     *
     * @param org the GitHub organization name (may be null)
     * @return the resolved token, or null if no token is available
     */
    private String resolveGithubToken(String org) {
        if (org != null && !org.isEmpty()) {
            // Case-insensitive lookup — GitHub org names are case-insensitive
            for (Map.Entry<String, String> entry : githubOrgTokens.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(org)) {
                    return entry.getValue();
                }
            }
        }

        // When there is exactly one configured org, use it as the default
        if (githubOrgTokens.size() == 1) {
            return githubOrgTokens.values().iterator().next();
        }

        return null;
    }

    /**
     * Extracts the GitHub organization (owner) from a repository URL.
     *
     * @param repoUrl the repository URL (HTTPS or SSH format)
     * @return the organization name, or null if not parseable
     */
    private static String extractOrgFromRepoUrl(String repoUrl) {
        String ownerRepo = extractOwnerRepo(repoUrl);
        if (ownerRepo == null) return null;
        int slash = ownerRepo.indexOf('/');
        return slash > 0 ? ownerRepo.substring(0, slash) : null;
    }
}
