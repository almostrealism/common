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
import io.flowtree.JsonFieldExtractor;
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
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

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
 *   <tr><td>GET</td><td>/api/workstreams/{id}/jobs</td><td>--</td>
 *       <td>List recent jobs for a workstream (newest first); optional {@code limit} query param</td></tr>
 *   <tr><td>GET</td><td>/api/jobs/{jobId}</td><td>--</td>
 *       <td>Look up a specific job event by ID</td></tr>
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

    /** Slack notifier used to send job-status messages to Slack channels. */
    private final SlackNotifier notifier;
    /** Maps tool names to the local filesystem paths of their definition files. */
    private final Map<String, Path> toolFiles = new HashMap<>();
    /** Maps GitHub organisation names to their API access tokens. */
    private Map<String, String> githubOrgTokens = new HashMap<>();

    /** Tracks which jobs should have a PR auto-created on success. */
    private final Map<String, AutoPrContext> autoCreatePrJobs = new HashMap<>();

    /** Handles all {@code /api/github/proxy} requests and GitHub PR creation. */
    private final GitHubProxyHandler githubProxyHandler;

    /** Handles all {@code /api/stats} requests. */
    private StatsQueryHandler statsQueryHandler;

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

    /** Local FlowTree server used to submit jobs received via the HTTP API. */
    private Server server;
    /** Slack listener that receives inbound messages and dispatches them to jobs. */
    private SlackListener listener;

    /** Base URL of the ar-memory HTTP server (e.g., "http://localhost:8020"). */
    private String memoryServerUrl;

    /** Base URL of the ar-manager HTTP server (e.g., "http://ar-manager:8010"). */
    private String arManagerUrl;

    /**
     * Creates a new API endpoint on the specified port.
     *
     * @param port     the port to listen on
     * @param notifier the notifier to delegate message operations to
     */
    public FlowTreeApiEndpoint(int port, SlackNotifier notifier) {
        super(port);
        this.notifier = notifier;
        this.githubProxyHandler = new GitHubProxyHandler(githubOrgTokens);
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
        githubProxyHandler.setGithubOrgTokens(this.githubOrgTokens);
    }

    /**
     * Sets the base URL of the ar-memory HTTP server used for
     * storing messages as memories.
     *
     * @param url the memory server base URL (e.g., "http://localhost:8020")
     */
    public void setMemoryServerUrl(String url) {
        this.memoryServerUrl = url;
    }

    /**
     * Sets the base URL of the ar-manager HTTP server used for
     * workstream management and token-based authentication.
     *
     * @param url the ar-manager base URL (e.g., "http://ar-manager:8010")
     */
    public void setArManagerUrl(String url) {
        this.arManagerUrl = url;
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
        this.statsQueryHandler = new StatsQueryHandler(statsStore);
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

        if (Method.GET.equals(method) && uri.startsWith("/api/workstreams/") && uri.endsWith("/jobs")) {
            String workstreamId = uri.substring("/api/workstreams/".length(),
                    uri.length() - "/jobs".length());
            String limitParam = session.getParameters().getOrDefault("limit",
                    List.of("10")).get(0);
            int limit = 10;
            try { limit = Integer.parseInt(limitParam); } catch (NumberFormatException ignored) { }
            return handleListJobs(workstreamId, limit);
        }

        if (Method.GET.equals(method) && uri.startsWith("/api/jobs/")) {
            String jobId = uri.substring("/api/jobs/".length());
            return handleGetJob(jobId);
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

        Workstream workstream = notifier.getWorkstream(workstreamId);
        if (workstream == null) {
            return errorResponse("Unknown workstream: " + workstreamId);
        }

        log("Message [" + workstreamId + (jobId != null ? "/" + jobId : "") + "]: " + truncate(text, 80));

        // Store as memory — hard error if memory server is configured but fails,
        // warning if memory server is not configured at all (minimal deployment)
        String repoUrl = workstream.getRepoUrl();
        String branch = workstream.getDefaultBranch();
        String storeError = storeMessageAsMemory(text, repoUrl, branch);
        if (storeError != null) {
            if (memoryServerUrl != null && !memoryServerUrl.isEmpty()) {
                // Memory server is configured but storage failed — hard error
                warn("Failed to store message as memory: " + storeError);
                return errorResponse("Failed to store message: " + storeError);
            }
            // Memory server not configured — warn but continue
            log("Message not archived (memory server not configured): " + storeError);
        }

        // Secondary: forward to notification channel (best-effort)
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
     * Stores a message in the ar-memory server's "messages" namespace.
     *
     * @param text    the message content
     * @param repoUrl the repository URL for the memory entry
     * @param branch  the branch name for the memory entry
     * @return null on success, or an error description on failure
     */
    private String storeMessageAsMemory(String text, String repoUrl, String branch) {
        if (memoryServerUrl == null || memoryServerUrl.isEmpty()) {
            return "memory server URL not configured";
        }
        if (repoUrl == null || repoUrl.isEmpty() || branch == null || branch.isEmpty()) {
            return "workstream missing repoUrl or defaultBranch";
        }

        String url = memoryServerUrl.replaceAll("/+$", "") + "/api/memory/store";
        String payload = "{\"content\":" + escapeJsonValue(text)
            + ",\"repo_url\":" + escapeJsonValue(repoUrl)
            + ",\"branch\":" + escapeJsonValue(branch)
            + ",\"namespace\":\"messages\""
            + ",\"tags\":[\"message\"]"
            + ",\"source\":\"ar-messages\"}";

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
                    return "memory server returned " + status + ": " + truncate(errBody, 200);
                }
            }
            return "memory server returned " + status;
        } catch (IOException e) {
            return "memory server unreachable: " + e.getMessage();
        }
    }

    /**
     * Escapes a string as a JSON string value (with surrounding quotes).
     */
    private static String escapeJsonValue(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
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
        Map<String, String> requiredLabels = extractJsonObjectFields(body, "requiredLabels");
        List<String> dependentRepos = extractJsonArrayField(body, "dependentRepos");

        // Check for an existing workstream with the same branch and repo
        Workstream existing = notifier.findWorkstreamByBranchAndRepo(defaultBranch, repoUrl);
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

        Workstream workstream;
        if (channelId != null) {
            workstream = new Workstream(channelId, "#" + channelName);
        } else {
            workstream = new Workstream(null, channelName);
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

        if (!requiredLabels.isEmpty()) {
            workstream.setRequiredLabels(requiredLabels);
        }

        if (dependentRepos != null && !dependentRepos.isEmpty()) {
            workstream.setDependentRepos(dependentRepos);
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

        Workstream workstream = notifier.getWorkstream(workstreamId);
        if (workstream == null) {
            return errorResponse("Unknown workstream: " + workstreamId);
        }

        String channelId = extractJsonField(body, "channelId");
        String channelName = extractJsonField(body, "channelName");
        String defaultBranch = extractJsonField(body, "defaultBranch");
        String baseBranch = extractJsonField(body, "baseBranch");
        String repoUrl = extractJsonField(body, "repoUrl");
        String planningDocument = extractJsonField(body, "planningDocument");
        Map<String, String> requiredLabels = extractJsonObjectFields(body, "requiredLabels");
        List<String> dependentRepos = extractJsonArrayField(body, "dependentRepos");

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
        if (!requiredLabels.isEmpty()) {
            workstream.setRequiredLabels(requiredLabels);
        }
        if (dependentRepos != null && !dependentRepos.isEmpty()) {
            workstream.setDependentRepos(dependentRepos);
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

        Workstream workstream = null;
        String resolvedWorkstreamId = pathWorkstreamId;

        if (bodyWorkstreamId != null && !bodyWorkstreamId.isEmpty()) {
            workstream = notifier.getWorkstream(bodyWorkstreamId);
            if (workstream != null) {
                resolvedWorkstreamId = bodyWorkstreamId;
                log("Workstream resolved from request body: " + resolvedWorkstreamId);
            }
        }

        if (workstream == null && targetBranch != null && !targetBranch.isEmpty()) {
            Workstream branchMatch = notifier.findWorkstreamByBranch(targetBranch);
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

        // Retry channel creation if the workstream has a channel name but
        // no channel ID.  This handles the case where the initial channel
        // creation at registration time failed (e.g., due to permissions)
        // and has since been resolved.
        if ((workstream.getChannelId() == null || workstream.getChannelId().isEmpty())
                && workstream.getChannelName() != null && !workstream.getChannelName().isEmpty()) {
            String name = workstream.getChannelName();
            if (name.startsWith("#")) {
                name = name.substring(1);
            }
            log("Workstream " + workstreamId + " has no channel ID; retrying channel creation for " + name);
            String channelId = notifier.createChannel(name);
            if (channelId != null) {
                workstream.setChannelId(channelId);
                log("Channel resolved for workstream " + workstreamId + ": " + channelId);
                // Re-register so channelToWorkstream picks up the new channelId,
                // then persist so the YAML reflects it too.
                if (listener != null) {
                    listener.registerWorkstream(workstream);
                    listener.persistConfig();
                }
            }
        }

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
        String deduplicationMode = extractJsonField(body, "deduplicationMode");
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

        // ar-manager config is set below after workstream URL

        // Planning document
        if (workstream.getPlanningDocument() != null) {
            factory.setPlanningDocument(workstream.getPlanningDocument());
        }

        // Dependent repos
        if (workstream.getDependentRepos() != null && !workstream.getDependentRepos().isEmpty()) {
            factory.setDependentRepos(workstream.getDependentRepos());
        }

        // Test file protection
        if (protectTestFiles) {
            factory.setProtectTestFiles(true);
        }

        // Enforcement mode (require code changes or loop)
        if (enforceChanges) {
            factory.setEnforceChanges(true);
        }

        // Deduplication mode (local inline session or spawn follow-up job)
        if (deduplicationMode != null && !deduplicationMode.isEmpty()) {
            factory.setDeduplicationMode(deduplicationMode);
        }

        // Required labels for Node routing (e.g., {"platform": "macos"}).
        // Job-level labels take precedence; fall back to workstream-level defaults when absent.
        Map<String, String> requiredLabels = extractJsonObjectFields(body, "requiredLabels");
        if (requiredLabels.isEmpty() && workstream.getRequiredLabels() != null
                && !workstream.getRequiredLabels().isEmpty()) {
            requiredLabels = workstream.getRequiredLabels();
        }
        for (Map.Entry<String, String> entry : requiredLabels.entrySet()) {
            factory.setRequiredLabel(entry.getKey(), entry.getValue());
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

        // Generate temporary ar-manager auth token
        String sharedSecret = FlowTreeController.loadSharedSecret();
        if (sharedSecret != null && !sharedSecret.isEmpty() && arManagerUrl != null) {
            String arToken = generateTemporaryToken(
                workstreamId, factory.getTaskId(), sharedSecret, 43200); // 12 hours
            if (arToken != null) {
                factory.setArManagerUrl(arManagerUrl);
                factory.setArManagerToken(arToken);
            }
        }

        // Notify that the job has been submitted (not yet executing)
        String displaySummary = jobDescription != null && !jobDescription.isEmpty()
            ? jobDescription : ClaudeCodeJob.summarizePrompt(prompt);
        JobCompletionEvent startEvent = JobCompletionEvent.started(factory.getTaskId(), displaySummary);
        startEvent.withGitInfo(effectiveBranch, null, null, null, false);
        notifier.onJobSubmitted(workstream.getWorkstreamId(), startEvent);

        // Queue locally — the NodeGroup relay mechanism distributes
        // the job to a Node whose labels match the job's requirements
        server.addTask(factory);

        log("Submitted job via API: " + factory.getTaskId());

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
                    effectiveOrg = GitHubProxyHandler.extractOrgFromRepoUrl(prCtx.repoUrl);
                }
                String token = githubProxyHandler.resolveGithubToken(effectiveOrg);
                if (token != null) {
                    String ownerRepo = GitHubProxyHandler.extractOwnerRepo(prCtx.repoUrl);
                    if (ownerRepo != null) {
                        String base = prCtx.baseBranch != null ? prCtx.baseBranch : "master";
                        String prUrl = githubProxyHandler.createGitHubPullRequest(
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
        return JsonFieldExtractor.extractString(json, field);
    }

    /**
     * Delegates to {@link io.flowtree.JsonFieldExtractor#extractBoolean(String, String)}.
     */
    static boolean extractJsonBooleanField(String json, String field) {
        return JsonFieldExtractor.extractBoolean(json, field);
    }

    /**
     * Delegates to {@link io.flowtree.JsonFieldExtractor#extractInt(String, String)}.
     */
    static int extractJsonIntField(String json, String field) {
        return JsonFieldExtractor.extractInt(json, field);
    }

    /**
     * Delegates to {@link io.flowtree.JsonFieldExtractor#extractLong(String, String)}.
     */
    static long extractJsonLongField(String json, String field) {
        return JsonFieldExtractor.extractLong(json, field);
    }

    /**
     * Delegates to {@link io.flowtree.JsonFieldExtractor#extractDouble(String, String)}.
     */
    static double extractJsonDoubleField(String json, String field) {
        return JsonFieldExtractor.extractDouble(json, field);
    }

    /**
     * Delegates to {@link io.flowtree.JsonFieldExtractor#extractStringArray(String, String)}.
     */
    static List<String> extractJsonArrayField(String json, String field) {
        return JsonFieldExtractor.extractStringArray(json, field);
    }

    /**
     * Delegates to {@link io.flowtree.JsonFieldExtractor#extractStringObject(String, String)}.
     */
    static Map<String, String> extractJsonObjectFields(String json, String field) {
        return JsonFieldExtractor.extractStringObject(json, field);
    }

    /**
     * Generates an HMAC-based temporary token for ar-manager authentication.
     *
     * <p>The token format is {@code armt_tmp_{base64url(hmac)}:{base64url(payload)}}
     * where the payload contains the workstream ID, job ID, and expiry timestamp
     * separated by colons. The HMAC is computed using SHA-256 with the shared secret.</p>
     *
     * @param workstreamId the workstream identifier
     * @param jobId        the job identifier
     * @param sharedSecret the shared secret (from AR_MANAGER_SHARED_SECRET env var)
     * @param ttlSeconds   token time-to-live in seconds
     * @return the token string, or null if the shared secret is not configured
     */
    public static String generateTemporaryToken(String workstreamId, String jobId,
                                                 String sharedSecret, long ttlSeconds) {
        if (sharedSecret == null || sharedSecret.isEmpty()) return null;

        long expiry = System.currentTimeMillis() / 1000 + ttlSeconds;
        String payload = workstreamId + ":" + jobId + ":" + expiry;

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                sharedSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hmacBytes = mac.doFinal(
                payload.getBytes(StandardCharsets.UTF_8));

            String hmacB64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(hmacBytes);
            String payloadB64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));

            return "armt_tmp_" + hmacB64 + ":" + payloadB64;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Escapes a string for safe inclusion in a JSON string literal.
     *
     * @param s  the string to escape, or {@code null}
     * @return   the escaped string, or an empty string if {@code s} is {@code null}
     */
    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    /**
     * Truncates a string to at most {@code maxLength} characters, appending
     * {@code "..."} when the string is shortened.
     *
     * @param s          the string to truncate, or {@code null}
     * @param maxLength  maximum number of characters to retain (including ellipsis)
     * @return           the (possibly truncated) string, never {@code null}
     */
    private static String truncate(String s, int maxLength) {
        if (s == null) return "";
        if (s.length() <= maxLength) return s;
        return s.substring(0, maxLength - 3) + "...";
    }

    /**
     * Serialises a {@link JobCompletionEvent} to a JSON object string.
     *
     * @param event the event to serialise
     * @return JSON object string
     */
    private String jobEventToJson(JobCompletionEvent event) {
        StringBuilder j = new StringBuilder();
        j.append("{");
        j.append("\"jobId\":\"").append(escapeJson(event.getJobId())).append("\"");
        j.append(",\"status\":\"").append(event.getStatus().name()).append("\"");
        j.append(",\"description\":\"").append(escapeJson(event.getDescription())).append("\"");
        j.append(",\"timestamp\":\"").append(event.getTimestamp().toString()).append("\"");
        if (event.getTargetBranch() != null) {
            j.append(",\"targetBranch\":\"").append(escapeJson(event.getTargetBranch())).append("\"");
        }
        if (event.getCommitHash() != null) {
            j.append(",\"commitHash\":\"").append(escapeJson(event.getCommitHash())).append("\"");
        }
        if (event.getPullRequestUrl() != null) {
            j.append(",\"pullRequestUrl\":\"").append(escapeJson(event.getPullRequestUrl())).append("\"");
        }
        if (event.getErrorMessage() != null) {
            j.append(",\"errorMessage\":\"").append(escapeJson(event.getErrorMessage())).append("\"");
        }
        if (event.getCostUsd() > 0) {
            j.append(String.format(",\"costUsd\":%.4f", event.getCostUsd()));
        }
        j.append("}");
        return j.toString();
    }

    /**
     * Handles {@code GET /api/workstreams/{id}/jobs?limit=N}.
     * Returns the most recent jobs for the workstream, newest first.
     *
     * @param workstreamId the workstream identifier
     * @param limit        maximum number of jobs to return
     * @return JSON array of job events
     */
    private Response handleListJobs(String workstreamId, int limit) {
        List<JobCompletionEvent> page = notifier.getRecentJobs(workstreamId, limit);

        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        for (JobCompletionEvent event : page) {
            if (!first) json.append(",");
            first = false;
            json.append(jobEventToJson(event));
        }
        json.append("]");

        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString());
    }

    /**
     * Handles {@code GET /api/jobs/{jobId}}.
     * Returns the most recent event for the specified job.
     *
     * @param jobId the job identifier
     * @return JSON object for the job event, or 404 if not found
     */
    private Response handleGetJob(String jobId) {
        JobCompletionEvent event = notifier.getJob(jobId);
        if (event == null) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND,
                    "application/json", "{\"ok\":false,\"error\":\"Job not found\"}");
        }
        return newFixedLengthResponse(Response.Status.OK,
                "application/json", jobEventToJson(event));
    }

    /**
     * Handles {@code GET /api/workstreams}. Returns a JSON array of all
     * registered workstreams with their configuration and capabilities.
     *
     * @return an HTTP 200 response containing a JSON array of workstream objects
     */
    private Response handleListWorkstreams() {
        Map<String, Workstream> workstreams = notifier.getWorkstreams();

        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        for (Workstream ws : workstreams.values()) {
            if (!first) json.append(",");
            first = false;
            json.append(ws.toSummaryJson());
        }
        json.append("]");

        return newFixedLengthResponse(Response.Status.OK,
                "application/json", json.toString());
    }

    /**
     * Handles {@code GET /api/stats}. Delegates to {@link StatsQueryHandler}.
     *
     * @param session the HTTP session supplying query parameters
     * @return an HTTP response containing weekly stats JSON
     */
    private Response handleStatsQuery(IHTTPSession session) {
        StatsQueryHandler handler = statsQueryHandler != null
            ? statsQueryHandler : new StatsQueryHandler(null);
        return handler.handle(session, this::errorResponse);
    }

    /**
     * Handles requests to {@code /api/github/proxy} by forwarding them to
     * {@link GitHubProxyHandler}.
     *
     * @param session the HTTP session
     * @param method  the HTTP method of the incoming request
     * @return JSON response wrapping the GitHub API result
     */
    private Response handleGitHubProxy(IHTTPSession session, Method method) {
        return githubProxyHandler.handle(session, method, this::readBody, this::errorResponse);
    }

    /**
     * Context stored at job submission time for auto-creating a pull request
     * when the job completes successfully.
     */
    private static class AutoPrContext {
        /** URL of the git repository for which a pull request should be created. */
        final String repoUrl;
        /** Base branch against which the pull request will be opened. */
        final String baseBranch;
        /** GitHub organisation name, used to look up the API access token. */
        final String githubOrg;
        /** Human-readable description of the job, used as the PR title/body. */
        final String description;

        /**
         * Constructs a new {@link AutoPrContext}.
         *
         * @param repoUrl     URL of the git repository
         * @param baseBranch  base branch for the pull request
         * @param githubOrg   GitHub organisation name
         * @param description human-readable job description
         */
        AutoPrContext(String repoUrl, String baseBranch, String githubOrg, String description) {
            this.repoUrl = repoUrl;
            this.baseBranch = baseBranch;
            this.githubOrg = githubOrg;
            this.description = description;
        }
    }
}
