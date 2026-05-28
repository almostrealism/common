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
import io.flowtree.jobs.CodingAgentJob;
import io.flowtree.jobs.CodingAgentJobEvent;
import io.flowtree.jobs.JobCompletionEvent;
import io.flowtree.jobs.McpConfigBuilder;
import io.flowtree.jobs.agent.PhaseConfigBundle;
import io.flowtree.msg.NodeProxy;
import org.almostrealism.io.ConsoleFeatures;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
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
 *   <tr><td>POST</td><td>/api/workstreams/{id}/messages</td><td>{@code {"text":"..."}}</td><td>Post a message to the workstream's channel</td></tr>
 *   <tr><td>POST</td><td>/api/workstreams/{id}/jobs/{jobId}/messages</td><td>{@code {"text":"..."}}</td><td>Post a message to the job's thread</td></tr>
 *   <tr><td>POST</td><td>/api/workstreams/{id}/submit</td><td>{@code {"prompt":"..."}}</td><td>Submit a new job to connected agents</td></tr>
 *   <tr><td>POST</td><td>/api/submit</td><td>{@code {"prompt":"...","targetBranch":"..."}}</td><td>Submit a job, resolving the workstream from the request body</td></tr>
 *   <tr><td>POST</td><td>/api/workstreams</td><td>{@code {"defaultBranch":"...","baseBranch":"...","planningDocument":"..."}}</td><td>Register a new workstream (auto-creates Slack channel)</td></tr>
 *   <tr><td>POST</td><td>/api/workstreams/{id}/update</td><td>{@code {"channelId":"...","channelName":"..."}}</td><td>Update an existing workstream</td></tr>
 *   <tr><td>POST</td><td>/api/workstreams/{id}/archive|/unarchive|/delete</td><td>{@code {"archiveSlackChannel":true}} or {@code {"force":false}}</td><td>Archive, unarchive, or delete a workstream — see {@link WorkstreamLifecycleHandler}</td></tr>
 *   <tr><td>POST</td><td>/api/workstreams/{id}</td><td>{@code {"jobId":"...","status":"..."}}</td><td>Receive a status event for the workstream</td></tr>
 *   <tr><td>POST</td><td>/api/workstreams/{id}/jobs/{jobId}</td><td>{@code {"jobId":"...","status":"..."}}</td><td>Receive a job status event</td></tr>
 *   <tr><td>GET</td><td>/api/github/proxy?url=...</td><td>--</td><td>Proxy a GET request to the GitHub API</td></tr>
 *   <tr><td>POST</td><td>/api/github/proxy?url=...</td><td><i>raw JSON payload</i></td><td>Proxy a POST request to the GitHub API</td></tr>
 *   <tr><td>PUT</td><td>/api/github/proxy?url=...</td><td><i>raw JSON payload</i></td><td>Proxy a PUT request to the GitHub API</td></tr>
 *   <tr><td>GET</td><td>/api/workstreams</td><td>--</td><td>List registered workstreams (archived hidden unless {@code ?includeArchived=true})</td></tr>
 *   <tr><td>GET</td><td>/api/workstreams/{id}/jobs</td><td>--</td><td>List recent jobs for a workstream; optional {@code limit} query param</td></tr>
 *   <tr><td>GET</td><td>/api/jobs/{jobId}</td><td>--</td><td>Look up a specific job event by ID</td></tr>
 *   <tr><td>GET</td><td>/api/config/accept-automated-jobs</td><td>--</td><td>Check whether automated job submissions are accepted</td></tr>
 *   <tr><td>POST</td><td>/api/config/accept-automated-jobs</td><td>{@code {"accept":true}}</td><td>Enable or disable automated job submissions</td></tr>
 *   <tr><td>GET</td><td>/api/health</td><td>--</td><td>Health check; response includes {@code server_time} (ISO-8601 UTC)</td></tr>
 *   <tr><td>GET</td><td>/api/agents</td><td>--</td><td>Enumerate available runners, phases, model names, and the built-in default runner</td></tr>
 * </table>
 *
 * @author Michael Murray
 * @see SlackNotifier
 * @see FlowTreeController
 */
public class FlowTreeApiEndpoint extends NanoHTTPD implements ConsoleFeatures {

    /** Default port for the API endpoint. */
    public static final int DEFAULT_PORT = 7780;
    /**
     * Matches {@code /api/workstreams/{wsId}} with optional {@code /jobs/{jobId}}
     * and optional action suffix ({@code /messages}, {@code /submit},
     * {@code /update}, {@code /archive}, {@code /unarchive}, {@code /delete}).
     */
    private static final Pattern WORKSTREAM_PATTERN = Pattern.compile(
        "/api/workstreams/([^/]+)(?:/jobs/([^/]+))?(/messages|/submit|/update|/archive|/unarchive|/delete)?"
    );

    /**
     * Aggregation over every configured per-workspace {@link SlackNotifier}.
     * Handlers resolve the owning notifier for a workstream (or the primary
     * notifier when there is no workspace context) by going through the
     * registry. In single-workspace mode the registry wraps a single primary
     * notifier; in multi-workspace mode it carries one notifier per Slack
     * team ID plus the primary for operations with no workspace context yet.
     */
    private final NotifierRegistry notifiers;

    /** Maps tool names to the local filesystem paths of their definition files. */
    private final Map<String, Path> toolFiles = new HashMap<>();
    /** Maps GitHub organisation names to their API access tokens. */
    private Map<String, String> githubOrgTokens = new HashMap<>();

    /** Maps GitHub org names to the workspace ID that owns them; used by registration routing. */
    private Map<String, String> orgToWorkspaceId = new HashMap<>();

    /** Resolves a Slack workspace ID to its entry for the workspace runner layer. */
    private Function<String, WorkstreamConfig.WorkspaceEntry> workspaceLookup = id -> null;
    /** Tracks which jobs should have a PR auto-created on success. */
    private final Map<String, AutoPrContext> autoCreatePrJobs = new HashMap<>();

    /** Handles all {@code /api/github/proxy} requests and GitHub PR creation. */
    private final GitHubProxyHandler githubProxyHandler;

    /** Handles all {@code /api/secrets/*} endpoint requests and token generation. */
    private final SecretsRequestHandler secretsHandler;

    /** Handles all {@code /api/stats} requests. */
    private StatsQueryHandler statsQueryHandler;

    /** Handles {@code GET /api/agents} metadata requests. */
    private final AgentsQueryHandler agentsQueryHandler = new AgentsQueryHandler();

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
    /** Pushed-tools configuration JSON forwarded to every submitted job. */
    private String pushedToolsConfig;
    /** Executor for delayed job submissions. Daemon thread so it never blocks JVM shutdown. */
    private final ScheduledExecutorService delayedJobExecutor = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "flowtree-delay"); t.setDaemon(true); return t; });
    /** Job ID -> pending future, removed when task runs/cancels. Concurrent for NanoHTTPD workers. */
    final Map<String, ScheduledFuture<?>> pendingDelayedJobs = new ConcurrentHashMap<>();

    /**
     * Creates a new API endpoint on the specified port, using a single
     * primary notifier (legacy single-workspace mode).
     *
     * @param port     the port to listen on
     * @param notifier the notifier to delegate message operations to
     */
    public FlowTreeApiEndpoint(int port, SlackNotifier notifier) {
        this(port, notifier, null);
    }

    /**
     * Creates a new API endpoint on the specified port with explicit
     * per-workspace notifiers. Handlers resolve the owning workspace for
     * each workstream ID and route operations to the matching notifier;
     * list operations aggregate across every workspace.
     *
     * @param port                 the port to listen on
     * @param primaryNotifier      the notifier used when a workstream has no
     *                             known workspace (e.g. during registration
     *                             before a workspace has been chosen)
     * @param notifiersByWorkspace Slack team ID to notifier, or {@code null}
     *                             / empty for single-workspace mode
     */
    public FlowTreeApiEndpoint(int port, SlackNotifier primaryNotifier,
            Map<String, SlackNotifier> notifiersByWorkspace) {
        super(port);
        this.notifiers = new NotifierRegistry(primaryNotifier, notifiersByWorkspace);
        this.githubProxyHandler = new GitHubProxyHandler(githubOrgTokens);
        this.secretsHandler = new SecretsRequestHandler(notifiers);
    }

    /** Sets the FlowTree server used for job submission. */
    public void setServer(Server server) { this.server = server; }

    /** Sets the SlackListener used for job creation. */
    public void setListener(SlackListener listener) { this.listener = listener; }

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
     * Sets the GitHub-org → Slack-workspace mapping used by
     * {@link WorkstreamRegistrationHandler#handleRegister} to derive the target workspace from
     * the submitted {@code repoUrl} when no explicit {@code slackWorkspaceId}
     * is provided.
     *
     * @param orgToWorkspaceId map of org name to Slack workspace ID
     */
    public void setOrgToWorkspaceId(Map<String, String> orgToWorkspaceId) {
        this.orgToWorkspaceId = orgToWorkspaceId != null
                ? new HashMap<>(orgToWorkspaceId) : new HashMap<>();
    }

    /** Sets the workspace lookup feeding the workspace layer of {@link SubmissionConfigResolver}; {@code null} disables it. */
    public void setWorkspaceLookup(Function<String, WorkstreamConfig.WorkspaceEntry> lookup) {
        this.workspaceLookup = lookup != null ? lookup : id -> null;
    }

    /** Rename hook for {@code newId} on workspace config; {@code null} disables. */
    private BiFunction<String, String, Boolean> workspaceRenameHook;
    /** Sets the workspace rename hook. */
    public void setWorkspaceRenameHook(BiFunction<String, String, Boolean> hook) { this.workspaceRenameHook = hook; }

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
     * Sets the pushed-tools configuration JSON forwarded to every submitted
     * job. Format matches {@link FlowTreeController#getPushedToolsConfig()}.
     *
     * @param config the JSON configuration; may be {@code null}
     */
    public void setPushedToolsConfig(String config) {
        this.pushedToolsConfig = config;
    }

    /**
     * Registers a pushed tool file that can be served via
     * {@code GET /api/tools/{name}}.
     *
     * @param name     the tool server name as declared in {@code workstreams.yaml}
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
     * Sets the shared secret used to validate HMAC temporary tokens.
     *
     * <p>When set, the secrets retrieve endpoint validates inbound workstream HMAC
     * tokens. The secret also serves as the admin token for management endpoints.</p>
     *
     * @param sharedSecret the shared secret string
     */
    public void setSharedSecret(String sharedSecret) {
        secretsHandler.setSharedSecret(sharedSecret);
    }

    /**
     * Sets the in-memory secrets index used by the {@code /api/secrets/*} endpoints.
     *
     * @param cache workspace-ID → (secret-name → entry) map
     */
    public void setSecretsCache(
            Map<String, Map<String, WorkstreamConfig.WorkspaceSecretEntry>> cache) {
        secretsHandler.setSecretsCache(cache);
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
            String serverTime = Instant.now().toString();
            return newFixedLengthResponse(Response.Status.OK,
                    "application/json",
                    "{\"status\":\"ok\",\"server_time\":\"" + serverTime + "\"}");
        }

        if (Method.GET.equals(method) && "/api/agents".equals(uri)) {
            return agentsQueryHandler.handle();
        }

        if (Method.GET.equals(method) && uri.startsWith("/api/stats")) {
            return handleStatsQuery(session);
        }

        if (Method.GET.equals(method) && "/api/workstreams".equals(uri)) {
            boolean includeArchived = "true".equalsIgnoreCase(
                    session.getParameters().getOrDefault("includeArchived",
                            List.of("false")).get(0));
            return handleListWorkstreams(includeArchived);
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
                return workstreamRegistrationHandler().handleRegister(session);
            }
            Response workspaceConfigResp = workspaceConfigHandler().handleIfMatches(uri, session);
            if (workspaceConfigResp != null) return workspaceConfigResp;
            Matcher m = WORKSTREAM_PATTERN.matcher(uri);
            if (m.matches()) {
                String workstreamId = m.group(1);
                String jobId = m.group(2);       // null if no /jobs/{id}
                String suffix = m.group(3);      // /messages, /submit, or null

                if ("/messages".equals(suffix)) {
                    return messageEndpointHandler().handle(session, workstreamId, jobId);
                } else if ("/submit".equals(suffix)) {
                    return handleSubmit(session, workstreamId);
                } else if ("/update".equals(suffix)) {
                    return workstreamRegistrationHandler().handleUpdate(session, workstreamId);
                } else if ("/archive".equals(suffix)) {
                    return lifecycleHandler().handleArchive(session, workstreamId);
                } else if ("/unarchive".equals(suffix)) {
                    return lifecycleHandler().handleUnarchive(session, workstreamId);
                } else if ("/delete".equals(suffix)) {
                    return lifecycleHandler().handleDelete(session, workstreamId);
                } else {
                    return handleStatusEvent(session, workstreamId);
                }
            }
        }

        if (Method.GET.equals(method) && uri.startsWith("/api/tools/")) {
            String toolName = uri.substring("/api/tools/".length());
            return handleToolDownload(toolName);
        }

        if (uri.startsWith("/api/secrets") && (Method.GET.equals(method)
                || Method.PUT.equals(method) || Method.DELETE.equals(method))) {
            return secretsHandler.handle(session, method, uri, this::readBody, this::errorResponse);
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
     * Handles {@code POST /api/workstreams/{id}/submit} for programmatic job submission.
     *
     * <p>Workstream resolution: explicit {@code workstreamId} in body takes priority, then
     * {@code targetBranch} match, then the URL path parameter. Supports optional per-job
     * overrides for {@code model}, {@code effort}, {@code maxTurns}, {@code maxBudgetUsd},
     * {@code postCompletionCommand}, {@code postCompletionWorkingDir},
     * {@code postCompletionTimeoutSeconds}, {@code maxDeduplicationPasses}, and {@code maxPostCompletionPasses}.</p>
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
        String legacyConfigErr = PhaseConfigResolver.rejectLegacyRequestFields(body);
        if (legacyConfigErr != null) return errorResponse(legacyConfigErr);

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

        // Workstream resolution: explicit ID wins, then branch/repo, then path ID.
        String targetBranch = extractJsonField(body, "targetBranch");
        String bodyWorkstreamId = extractJsonField(body, "workstreamId");
        String bodyRepoUrl = extractJsonField(body, "repoUrl");
        Workstream workstream = null;
        String resolvedWorkstreamId = pathWorkstreamId;
        if (bodyWorkstreamId != null && !bodyWorkstreamId.isEmpty()) {
            SlackNotifier n = notifiers.notifierFor(bodyWorkstreamId);
            workstream = n != null ? n.getWorkstream(bodyWorkstreamId) : null;
            if (workstream != null) {
                resolvedWorkstreamId = bodyWorkstreamId;
                log("Workstream resolved from request body: " + resolvedWorkstreamId);
            }
        }
        if (workstream == null && targetBranch != null && !targetBranch.isEmpty()) {
            NotifierRegistry.BranchResolution res = notifiers.resolveBranch(targetBranch, bodyRepoUrl);
            if (res.error() != null) return errorResponse(res.error());
            if (res.match() != null) {
                workstream = res.match();
                resolvedWorkstreamId = workstream.getWorkstreamId();
                log("Workstream resolved by branch=" + targetBranch
                    + (bodyRepoUrl != null && !bodyRepoUrl.isEmpty() ? "/repo=" + bodyRepoUrl : "")
                    + ": " + resolvedWorkstreamId);
            }
        }
        if (workstream == null && pathWorkstreamId != null) {
            SlackNotifier n = notifiers.notifierFor(pathWorkstreamId);
            workstream = n != null ? n.getWorkstream(pathWorkstreamId) : null;
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
            String channelId = notifiers.notifierFor(workstreamId).createChannel(name);
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
                if (notifiers.notifierFor(workstreamId).hasJobStartedAfter(workstreamId, startedAfter)) {
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

        String repoUrl = bodyRepoUrl;
        String baseBranch = extractJsonField(body, "baseBranch");
        String jobDescription = extractJsonField(body, "description");
        int maxTurns = extractJsonIntField(body, "maxTurns");
        double maxBudgetUsd = extractJsonDoubleField(body, "maxBudgetUsd");
        boolean protectTestFiles = extractJsonBooleanField(body, "protectTestFiles");
        boolean enforceChanges = extractJsonBooleanField(body, "enforceChanges");
        String deduplicationMode = extractJsonField(body, "deduplicationMode");
        int maxDeduplicationPasses = extractJsonIntField(body, "maxDeduplicationPasses");
        boolean autoCreatePr = extractJsonBooleanField(body, "autoCreatePr");
        String postCompletionCommand = extractJsonField(body, "postCompletionCommand");
        String postCompletionWorkingDir = extractJsonField(body, "postCompletionWorkingDir");
        int postCompletionTimeoutSeconds = extractJsonIntField(body, "postCompletionTimeoutSeconds");
        int maxPostCompletionPasses = extractJsonIntField(body, "maxPostCompletionPasses");
        int delaySeconds = extractJsonIntField(body, "delaySeconds");
        // Create job factory with workstream defaults, overridden by request values
        CodingAgentJob.Factory factory = new CodingAgentJob.Factory(prompt);
        if (jobDescription != null && !jobDescription.isEmpty()) {
            factory.setDescription(jobDescription);
        }
        factory.setAllowedTools(workstream.getAllowedTools());
        factory.setAgentEnv(mergeAgentEnv(workstream.getAgentEnv(), body));
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
        if (maxDeduplicationPasses > 0) {
            factory.setMaxDeduplicationPasses(maxDeduplicationPasses);
        }
        // Organizational placement — disabled by default; opt in explicitly
        if (extractJsonHasField(body, "enforceOrganizationalPlacement"))
            factory.setEnforceOrganizationalPlacement(
                    extractJsonBooleanField(body, "enforceOrganizationalPlacement"));
        if (extractJsonHasField(body, "reviewEnabled"))
            factory.setReviewEnabled(extractJsonBooleanField(body, "reviewEnabled"));
        int maxReviewPasses = extractJsonIntField(body, "maxReviewPasses");
        if (maxReviewPasses > 0) factory.setMaxReviewPasses(maxReviewPasses);
        if (postCompletionCommand != null && !postCompletionCommand.isEmpty()) {
            factory.setPostCompletionCommand(postCompletionCommand);
            if (postCompletionWorkingDir != null && !postCompletionWorkingDir.isEmpty()) {
                factory.setPostCompletionWorkingDir(postCompletionWorkingDir);
            }
            if (postCompletionTimeoutSeconds > 0) {
                factory.setPostCompletionTimeoutSeconds(postCompletionTimeoutSeconds);
            }
            if (maxPostCompletionPasses > 0) {
                factory.setMaxPostCompletionPasses(maxPostCompletionPasses);
            }
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

        // Resolve runner and Phase config layers (request / workstream /
        // workspace / controller default) through the shared submission
        // resolver. Same code path the Slack listener uses, so workspace-level
        // settings are honoured uniformly regardless of submission entrypoint.
        String wsId = workstream.getWorkspaceId();
        WorkstreamConfig.WorkspaceEntry wsEntry = (wsId != null && !wsId.isEmpty()) ? workspaceLookup.apply(wsId) : null;
        if (wsId != null && !wsId.isEmpty() && wsEntry == null) log("submitWorkspaceMissing workspaceId=" + wsId);
        PhaseConfigBundle requestBundle = PhaseConfigResolver.bundleFromRequest(body);
        SubmissionConfigResolver configResolver = SubmissionConfigResolver.resolve(
                requestBundle, workstream, wsEntry);
        if (configResolver.error() != null) return errorResponse(configResolver.error());
        configResolver.applyTo(factory);

        // Auto-create PR on successful completion
        if (autoCreatePr) {
            factory.setAutoCreatePr(true);
            autoCreatePrJobs.put(factory.getTaskId(), new AutoPrContext(
                effectiveRepoUrl, effectiveBase, workstream.getGithubOrg(),
                jobDescription != null ? jobDescription : CodingAgentJob.summarizePrompt(prompt)));
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
            String arToken = SecretsRequestHandler.generateTemporaryToken(
                workstreamId, factory.getTaskId(), sharedSecret, 43200);
            if (arToken != null) {
                factory.setArManagerUrl(arManagerUrl);
                factory.setArManagerToken(arToken);
            }
        }
        if (pushedToolsConfig != null && !pushedToolsConfig.isEmpty()) {
            factory.setPushedToolsConfig(pushedToolsConfig);
        } else {
            warn("no pushedToolsConfig to forward to " + factory.getTaskId()
                + " (value: " + McpConfigBuilder.pushedToolsConfigPreview(pushedToolsConfig) + ")");
        }

        // Notify that the job has been submitted (not yet executing)
        String displaySummary = jobDescription != null && !jobDescription.isEmpty()
            ? jobDescription : CodingAgentJob.summarizePrompt(prompt);
        JobCompletionEvent startEvent = JobCompletionEvent.started(factory.getTaskId(), displaySummary);
        startEvent.withGitInfo(effectiveBranch, null, null, null, false);
        notifiers.notifierFor(workstream.getWorkstreamId()).onJobSubmitted(workstream.getWorkstreamId(), startEvent);
        if (delaySeconds > 0) {
            pendingDelayedJobs.put(factory.getTaskId(), delayedJobExecutor.schedule(
                    () -> { try { server.addTask(factory); } finally { pendingDelayedJobs.remove(factory.getTaskId()); } },
                    delaySeconds, TimeUnit.SECONDS));
            log("Delayed job via API: " + factory.getTaskId() + " (delaySeconds=" + delaySeconds + ")");
        } else {
            server.addTask(factory);
            log("Submitted job via API: " + factory.getTaskId());
        }

        StringBuilder json = new StringBuilder();
        json.append("{\"ok\":true,\"jobId\":\"")
                .append(factory.getTaskId())
                .append("\",\"workstreamId\":\"")
                .append(workstreamId)
                .append("\"");
        // Echo back the fully-resolved phase configuration (job overrides
        // layered through workstream / workspace / controller defaults) under
        // the same field names the config input uses, so the caller sees the
        // config the job will actually run with.
        PhaseConfigResolver.appendBundleJson(json, configResolver.phaseConfigResolver().resolvedBundle());
        // Report which optional phases are active so callers can confirm
        // the effective job configuration without inspecting phase bundles.
        String effectiveDedupMode = factory.getDeduplicationMode();
        boolean dedupEnabled = effectiveDedupMode != null
                && !effectiveDedupMode.isEmpty()
                && !CodingAgentJob.DEDUP_NONE.equals(effectiveDedupMode);
        json.append(",\"deduplicationEnabled\":").append(dedupEnabled);
        json.append(",\"organizationalPlacementEnabled\":")
                .append(factory.isEnforceOrganizationalPlacement());
        json.append("}");
        return newFixedLengthResponse(Response.Status.OK,
                "application/json", json.toString());
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
                event = CodingAgentJobEvent.failed(jobId, description, errorMessage, null);
            } else {
                event = new CodingAgentJobEvent(jobId, eventStatus, description);
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

        if (event instanceof CodingAgentJobEvent) {
            CodingAgentJobEvent ccEvent = (CodingAgentJobEvent) event;
            ccEvent.withClaudeCodeInfo(prompt, sessionId, exitCode);
            long durationMs = extractJsonLongField(body, "durationMs");
            long durationApiMs = extractJsonLongField(body, "durationApiMs");
            double costUsd = extractJsonDoubleField(body, "costUsd");
            int numTurns = extractJsonIntField(body, "numTurns");
            ccEvent.withTimingInfo(durationMs, durationApiMs, costUsd, numTurns);
            String subtype = extractJsonField(body, "subtype");
            boolean sessionIsError = extractJsonBooleanField(body, "sessionIsError");
            int permissionDenials = extractJsonIntField(body, "permissionDenials");
            List<String> deniedToolNames = extractJsonArrayField(body, "deniedToolNames");
            ccEvent.withSessionDetails(subtype, sessionIsError, permissionDenials, deniedToolNames);

            String commitMessageSource = extractJsonField(body, "commitMessageSource");
            if (commitMessageSource != null) {
                ccEvent.withCommitMessageSource(commitMessageSource);
            }
            ccEvent.withCostByRunner(JsonFieldExtractor.extractDoubleObject(body, "costByRunner"));
            ccEvent.withCostByModel(JsonFieldExtractor.extractDoubleObject(body, "costByModel"));
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

        SlackNotifier targetNotifier = notifiers.notifierFor(workstreamId);
        if (eventStatus == JobCompletionEvent.Status.STARTED) {
            targetNotifier.onJobStarted(workstreamId, event);
        } else {
            targetNotifier.onJobCompleted(workstreamId, event);
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
            // POST bodies are stored inline under "postData"; PUT bodies are stored
            // as a temp-file path under "content" — handle both shapes.
            if (bodyMap.containsKey("postData")) {
                return bodyMap.get("postData");
            }
            String contentFile = bodyMap.get("content");
            if (contentFile != null && !contentFile.isEmpty()) {
                return Files.readString(Path.of(contentFile), StandardCharsets.UTF_8);
            }
            return null;
        } catch (IOException | ResponseException e) {
            warn("Error reading body: " + e.getMessage());
            return null;
        }
    }

    /**
     * Applies {@code value} to {@code setter} and converts any
     * {@link IllegalArgumentException} thrown by the setter into a 400
     * response built by {@code errorBuilder} so request handlers can
     * short-circuit with a single {@code return}.  Returns {@code null}
     * (no error) when the value is empty or successfully applied.
     *
     * @param value        the candidate value, or {@code null}/empty for a no-op
     * @param setter       the setter to invoke on the candidate value
     * @param errorBuilder factory that wraps a message into a 400 response
     * @return an error response, or {@code null} on success
     */
    static Response applyValidated(String value, Consumer<String> setter,
                                   Function<String, Response> errorBuilder) {
        if (value == null || value.isEmpty()) return null;
        try {
            setter.accept(value);
            return null;
        } catch (IllegalArgumentException e) {
            return errorBuilder.apply(e.getMessage());
        }
    }

    /**
     * Convenience overload of {@link #applyValidated(String, Consumer, Function)}
     * that uses this endpoint's own 400-response factory.
     *
     * @param value  the candidate value
     * @param setter the setter to invoke on the candidate value
     * @return an error response, or {@code null} on success
     */
    private Response applyValidated(String value, Consumer<String> setter) {
        return applyValidated(value, setter, this::errorResponse);
    }

    /**
     * Creates an error response with the specified message.
     */
    private Response errorResponse(String message) {
        String json = "{\"ok\":false,\"error\":\"" + JsonFieldExtractor.escapeJson(message) + "\"}";
        return newFixedLengthResponse(Response.Status.BAD_REQUEST,
                "application/json", json);
    }

    /**
     * Delegates to {@link io.flowtree.JsonFieldExtractor#hasField(String, String)}.
     */
    static boolean extractJsonHasField(String json, String field) {
        return JsonFieldExtractor.hasField(json, field);
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
     * Computes the effective agent environment for a submission: the workstream's static
     * {@code agentEnv} with any per-submission {@code agentEnv} from the request body merged on top
     * (submission wins on key collisions). This is what lets a caller inject dynamic, per-job
     * environment — e.g. a short-lived bearer token minted per job — that cannot live in static
     * workstream config. Either input may be {@code null}/absent.
     *
     * @param workstreamEnv the workstream's configured agentEnv, or {@code null}
     * @param body          the raw submission JSON body (may contain an {@code agentEnv} object)
     * @return a new merged map (never {@code null})
     */
    static Map<String, String> mergeAgentEnv(Map<String, String> workstreamEnv, String body) {
        Map<String, String> effective = new HashMap<>();
        if (workstreamEnv != null) {
            effective.putAll(workstreamEnv);
        }
        Map<String, String> submissionAgentEnv = extractJsonObjectFields(body, "agentEnv");
        if (submissionAgentEnv != null) {
            effective.putAll(submissionAgentEnv);
        }
        return effective;
    }

    /**
     * Escapes a string as a JSON string value (with surrounding quotes).
     * Shared by every handler in this package so the JSON shape produced by
     * one endpoint is identical to the next.
     *
     * @param s the string to escape
     * @return the JSON-escaped quoted string
     */
    static String escapeJsonValue(String s) {
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
     * Serialises a {@link JobCompletionEvent} to a JSON object string.
     *
     * @param event the event to serialise
     * @return JSON object string
     */
    private String jobEventToJson(JobCompletionEvent event) {
        return jobEventToJson(event, null);
    }

    /**
     * Serialises a {@link JobCompletionEvent} to a JSON object string,
     * optionally including the owning workstream identifier.
     *
     * @param event         the event to serialise
     * @param workstreamId  the workstream that owns this job, or {@code null}
     * @return JSON object string
     */
    private String jobEventToJson(JobCompletionEvent event, String workstreamId) {
        StringBuilder j = new StringBuilder();
        j.append("{");
        j.append("\"jobId\":\"").append(JsonFieldExtractor.escapeJson(event.getJobId())).append("\"");
        j.append(",\"status\":\"").append(event.getStatus().name()).append("\"");
        j.append(",\"description\":\"").append(JsonFieldExtractor.escapeJson(event.getDescription())).append("\"");
        j.append(",\"timestamp\":\"").append(event.getTimestamp().toString()).append("\"");
        if (workstreamId != null) {
            j.append(",\"workstreamId\":\"").append(JsonFieldExtractor.escapeJson(workstreamId)).append("\"");
        }
        if (event.getTargetBranch() != null) {
            j.append(",\"targetBranch\":\"").append(JsonFieldExtractor.escapeJson(event.getTargetBranch())).append("\"");
        }
        if (event.getCommitHash() != null) {
            j.append(",\"commitHash\":\"").append(JsonFieldExtractor.escapeJson(event.getCommitHash())).append("\"");
        }
        if (event.getPullRequestUrl() != null) {
            j.append(",\"pullRequestUrl\":\"").append(JsonFieldExtractor.escapeJson(event.getPullRequestUrl())).append("\"");
        }
        if (event.getErrorMessage() != null) {
            j.append(",\"errorMessage\":\"").append(JsonFieldExtractor.escapeJson(event.getErrorMessage())).append("\"");
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
        SlackNotifier n = notifiers.notifierFor(workstreamId);
        List<JobCompletionEvent> page = n != null
                ? n.getRecentJobs(workstreamId, limit) : new ArrayList<>();

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
        JobCompletionEvent event = notifiers.findJob(jobId);
        if (event == null) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND,
                    "application/json", "{\"ok\":false,\"error\":\"Job not found\"}");
        }
        String workstreamId = notifiers.findWorkstreamIdForJob(jobId);
        return newFixedLengthResponse(Response.Status.OK,
                "application/json", jobEventToJson(event, workstreamId));
    }

    /**
     * Handles {@code GET /api/workstreams}. Returns a JSON array of all
     * registered workstreams with their configuration and capabilities.
     *
     * <p>By default, workstreams flagged as archived are omitted. Pass
     * {@code ?includeArchived=true} to include them; archived entries
     * carry an {@code "archived": true} field in the response.</p>
     *
     * @param includeArchived when {@code false} archived workstreams are skipped
     * @return an HTTP 200 response containing a JSON array of workstream objects
     */
    private Response handleListWorkstreams(boolean includeArchived) {
        Map<String, Workstream> workstreams = notifiers.allWorkstreams();
        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        for (Workstream ws : workstreams.values()) {
            if (!includeArchived && ws.isArchived()) continue;
            if (!first) json.append(",");
            first = false;
            json.append(ws.toSummaryJson());
        }
        json.append("]");
        return newFixedLengthResponse(Response.Status.OK,
                "application/json", json.toString());
    }

    /**
     * Builds the archive/unarchive/delete handler bound to this endpoint's
     * notifiers and listener. See {@link WorkstreamLifecycleHandler}.
     */
    private WorkstreamLifecycleHandler lifecycleHandler() {
        return new WorkstreamLifecycleHandler(notifiers, listener, this::readBody,
                this::errorResponse, this::log);
    }

    /** Builds the {@code /api/workspaces/{id}/config} handler bound to this endpoint. */
    private WorkspaceConfigHandler workspaceConfigHandler() {
        return new WorkspaceConfigHandler(workspaceLookup, workspaceRenameHook,
                listener, this::readBody, this::errorResponse, this::log);
    }

    /**
     * Builds the workstream-message endpoint handler bound to this endpoint.
     * See {@link MessageEndpointHandler}.
     *
     * @return a fresh handler reflecting the current memory-server URL and
     *         notifier registry
     */
    private MessageEndpointHandler messageEndpointHandler() {
        return new MessageEndpointHandler(notifiers, memoryServerUrl,
                this::readBody, this::errorResponse, this::log, this::warn);
    }

    /**
     * Builds the workstream registration/update endpoint handler bound to this endpoint.
     * See {@link WorkstreamRegistrationHandler}.
     *
     * @return a fresh handler reflecting the current notifier registry,
     *         org-to-workspace map, and listener
     */
    private WorkstreamRegistrationHandler workstreamRegistrationHandler() {
        return new WorkstreamRegistrationHandler(notifiers, orgToWorkspaceId,
                listener, this::readBody, this::errorResponse, this::log);
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

}
