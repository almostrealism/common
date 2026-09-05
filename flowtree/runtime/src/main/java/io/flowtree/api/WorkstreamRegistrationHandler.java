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
import io.flowtree.jobs.agent.PhaseConfigBundle;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import io.flowtree.submission.PhaseConfigResolver;
import io.flowtree.workstream.ListenerCycleChecker;
import io.flowtree.workstream.Workstream;
import io.flowtree.slack.SlackListener;
import io.flowtree.slack.SlackNotifier;
import io.flowtree.slack.NotifierRegistry;
import io.flowtree.github.GitHubProxyHandler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Handles workstream registration and update endpoints
 * ({@code POST /api/workstreams} and
 * {@code POST /api/workstreams/{id}/update}) for {@link FlowTreeApiEndpoint}.
 *
 * <p>Both endpoints share the same field surface ({@code defaultBranch},
 * {@code baseBranch}, {@code repoUrl}, {@code planningDocument},
 * {@code channelName}, {@code requiredLabels}, {@code dependentRepos},
 * {@code defaultPhaseConfig}, {@code phaseConfigs}).
 * Registration additionally derives the target workspace from
 * {@code workspaceId}, the GitHub org of {@code repoUrl}, or the
 * primary notifier in single-workspace mode, then auto-creates a Slack
 * channel on that workspace when the workspace has Slack configured.
 * {@code slackWorkspaceId} is read as a legacy body alias for
 * {@code workspaceId} and is not part of the documented surface.</p>
 *
 * <p>Runner / model / effort configuration is supplied exclusively through
 * the per-phase shape ({@code defaultPhaseConfig} / {@code phaseConfigs}),
 * applied via {@link PhaseConfigResolver#applyToWorkstream}. The legacy
 * top-level {@code model} / {@code effort} / {@code runners} /
 * {@code defaultRunner} fields are no longer accepted: a request carrying
 * any of them is rejected with a 400 by
 * {@link PhaseConfigResolver#rejectLegacyRequestFields(String)}.</p>
 *
 * @author Michael Murray
 * @see FlowTreeApiEndpoint
 */
final class WorkstreamRegistrationHandler {

    /** Aggregates the per-workspace notifiers used for routing and lookups. */
    private final NotifierRegistry notifiers;
    /** Maps GitHub org names to the workspace ID that owns them; never {@code null}. */
    private final Map<String, String> orgToWorkspaceId;
    /** Slack listener used to persist YAML edits; may be {@code null} in tests. */
    private final SlackListener listener;
    /** Reads the POST body from a NanoHTTPD session; reused from the parent endpoint. */
    private final Function<IHTTPSession, String> readBody;
    /** Builds a 400 error response with the given message. */
    private final Function<String, Response> errorResponse;
    /** Emits a log line via the parent endpoint's logger. */
    private final Consumer<String> log;

    /**
     * Constructs a new handler bound to the given notifier registry.
     *
     * @param notifiers        the workspace notifier registry
     * @param orgToWorkspaceId GitHub-org to workspace-ID lookup; never {@code null}
     * @param listener         Slack listener for YAML persistence (may be {@code null})
     * @param readBody         body reader supplied by the parent endpoint
     * @param errorResponse    400-error response factory
     * @param log              log line consumer
     */
    WorkstreamRegistrationHandler(NotifierRegistry notifiers,
                                  Map<String, String> orgToWorkspaceId,
                                  SlackListener listener,
                                  Function<IHTTPSession, String> readBody,
                                  Function<String, Response> errorResponse,
                                  Consumer<String> log) {
        this.notifiers = notifiers;
        this.orgToWorkspaceId = orgToWorkspaceId;
        this.listener = listener;
        this.readBody = readBody;
        this.errorResponse = errorResponse;
        this.log = log;
    }

    /**
     * Handles {@code POST /api/workstreams} to register a new workstream.
     *
     * <p>All fields are optional except {@code defaultBranch}. When
     * {@code channelName} is absent the controller derives one from the
     * last path component of {@code defaultBranch} (e.g. {@code "feature/foo"}
     * → {@code "w-foo"}) and appends a numeric suffix if the name collides
     * with an existing workstream. Supply {@code channelName} to override.</p>
     *
     * <p>Runner / model / effort defaults are configured through
     * {@code defaultPhaseConfig} / {@code phaseConfigs}; the legacy
     * {@code model} / {@code effort} / {@code runners} / {@code defaultRunner}
     * fields are rejected with a 400.</p>
     *
     * @param session the HTTP session
     * @return JSON with {@code workstreamId}, {@code channelId}, {@code channelName}
     */
    Response handleRegister(IHTTPSession session) {
        String body = readBody.apply(session);
        if (body == null) {
            return errorResponse.apply("Failed to read request body");
        }
        String legacyErr = PhaseConfigResolver.rejectLegacyRequestFields(body);
        if (legacyErr != null) return errorResponse.apply(legacyErr);

        String defaultBranch = JsonFieldExtractor.extractString(body, "defaultBranch");
        if (defaultBranch == null || defaultBranch.isEmpty()) {
            return errorResponse.apply("Missing required field: defaultBranch");
        }

        Registration registration = register(body, defaultBranch);
        if (registration.failure() != null) return registration.failure();

        Workstream workstream = registration.workstream();
        if (registration.existing()) {
            return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json",
                    "{\"ok\":true,\"existing\":true,\"workstreamId\":\""
                    + JsonFieldExtractor.escapeJson(workstream.getWorkstreamId()) + "\"}");
        }

        StringBuilder json = new StringBuilder();
        json.append("{\"ok\":true");
        json.append(",\"workstreamId\":\"").append(JsonFieldExtractor.escapeJson(workstream.getWorkstreamId())).append("\"");
        if (registration.channelId() != null) {
            json.append(",\"channelId\":\"").append(JsonFieldExtractor.escapeJson(registration.channelId())).append("\"");
        }
        if (registration.channelName() != null) {
            json.append(",\"channelName\":\"").append(JsonFieldExtractor.escapeJson(registration.channelName())).append("\"");
        }
        PhaseConfigBundle registeredBundle = workstream.getPhaseConfigBundle();
        PhaseConfigResolver.appendBundleJson(json, registeredBundle);
        json.append("}");

        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK,
                "application/json", json.toString());
    }

    /**
     * Outcome of {@link #register(String, String)}: the workstream that now
     * exists for the requested branch, or the HTTP failure that stopped it
     * from being created.
     *
     * <p>Failures are carried as a ready-made {@link Response} rather than a
     * message because registration fails in three distinct ways — a 400 for a
     * malformed or unroutable request, and two 500s for a record that did not
     * reach disk or is not readable back afterwards — and every caller should
     * report the same status the registration endpoint would.</p>
     */
    static final class Registration {

        /** The registered or already-registered workstream; {@code null} on failure. */
        private final Workstream workstream;
        /** The Slack channel created for a new workstream, or {@code null}. */
        private final String channelId;
        /** The Slack channel name used for a new workstream, or {@code null}. */
        private final String channelName;
        /** Whether the workstream already existed rather than being created here. */
        private final boolean existing;
        /** The failure response, or {@code null} when registration succeeded. */
        private final Response failure;

        /**
         * Creates a registration outcome. Callers should use the static
         * factory methods rather than this constructor.
         *
         * @param workstream  the resolved workstream, or {@code null} on failure
         * @param channelId   the created Slack channel ID, or {@code null}
         * @param channelName the Slack channel name, or {@code null}
         * @param existing    whether the workstream already existed
         * @param failure     the failure response, or {@code null} on success
         */
        private Registration(Workstream workstream, String channelId, String channelName,
                             boolean existing, Response failure) {
            this.workstream = workstream;
            this.channelId = channelId;
            this.channelName = channelName;
            this.existing = existing;
            this.failure = failure;
        }

        /** Returns the registered workstream, or {@code null} when registration failed. */
        Workstream workstream() { return workstream; }

        /** Returns the Slack channel ID created for a new workstream, or {@code null}. */
        String channelId() { return channelId; }

        /** Returns the Slack channel name used for a new workstream, or {@code null}. */
        String channelName() { return channelName; }

        /** Returns whether the workstream already existed rather than being created. */
        boolean existing() { return existing; }

        /** Returns the failure response, or {@code null} when registration succeeded. */
        Response failure() { return failure; }

        /**
         * Returns an outcome describing a workstream created by this call.
         *
         * @param workstream  the newly registered workstream
         * @param channelId   the created Slack channel ID, or {@code null}
         * @param channelName the Slack channel name, or {@code null}
         * @return the success outcome
         */
        static Registration created(Workstream workstream, String channelId, String channelName) {
            return new Registration(workstream, channelId, channelName, false, null);
        }

        /**
         * Returns an outcome describing a workstream that was already
         * registered for the requested branch and repository.
         *
         * @param workstream the existing workstream
         * @return the success outcome
         */
        static Registration alreadyRegistered(Workstream workstream) {
            return new Registration(workstream, null, null, true, null);
        }

        /**
         * Returns an outcome describing a registration that failed.
         *
         * @param failure the response to return to the caller
         * @return the failure outcome
         */
        static Registration failed(Response failure) {
            return new Registration(null, null, null, false, failure);
        }
    }

    /**
     * Resolves the workstream a job submission targets, registering one when
     * the request asks for that and nothing matches.
     *
     * <p>The resolution ladder is: an explicit {@code workstreamId} in the
     * body, then the {@code targetBranch} (disambiguated by {@code repoUrl}
     * when several workstreams share the branch), then the workstream named
     * in the request path. When every rung misses and {@code createIfMissing}
     * is set, the workstream is registered here — so a branch nobody
     * registered in advance still gets its job — and the result reports
     * {@link Registration#existing()} as {@code false}.</p>
     *
     * <p>An ambiguous branch is an error rather than a reason to create:
     * two workstreams already share the branch, and adding a third would
     * make the ambiguity permanent.</p>
     *
     * @param body             the raw submission body JSON
     * @param targetBranch     the branch named by the submission; may be {@code null}
     * @param repoUrl          the repository named by the submission; may be {@code null}
     * @param pathWorkstreamId the workstream from the request path; may be {@code null}
     * @param createIfMissing  whether to register a workstream when none matches
     * @return the resolved, created, or failed {@link Registration}
     */
    Registration resolveOrRegister(String body, String targetBranch, String repoUrl,
                                   String pathWorkstreamId, boolean createIfMissing) {
        String bodyWorkstreamId = JsonFieldExtractor.extractString(body, "workstreamId");
        if (bodyWorkstreamId != null && !bodyWorkstreamId.isEmpty()) {
            Workstream match = workstreamById(bodyWorkstreamId);
            if (match != null) {
                log.accept("Workstream resolved from request body: " + bodyWorkstreamId);
                return Registration.alreadyRegistered(match);
            }
        }

        if (targetBranch != null && !targetBranch.isEmpty()) {
            NotifierRegistry.BranchResolution res = notifiers.resolveBranch(targetBranch, repoUrl);
            if (res.error() != null) return Registration.failed(errorResponse.apply(res.error()));
            if (res.match() != null) {
                log.accept("Workstream resolved by branch=" + targetBranch
                    + (repoUrl != null && !repoUrl.isEmpty() ? "/repo=" + repoUrl : "")
                    + ": " + res.match().getWorkstreamId());
                return Registration.alreadyRegistered(res.match());
            }
        }

        if (pathWorkstreamId != null) {
            Workstream match = workstreamById(pathWorkstreamId);
            if (match != null) return Registration.alreadyRegistered(match);
        }

        if (!createIfMissing) {
            String detail = pathWorkstreamId != null
                ? "Unknown workstream: " + pathWorkstreamId
                : "No workstream found for branch: " + targetBranch
                    + ". Register a workstream for this branch, or submit with"
                    + " createWorkstreamIfMissing and a repoUrl to have one created.";
            return Registration.failed(errorResponse.apply(detail));
        }

        // A workstream is identified by repository and branch together, so
        // creating one without either would produce a record that cannot be
        // matched again on the next submission — and, without a repository,
        // nothing for the agent to check out.
        if (targetBranch == null || targetBranch.isEmpty()) {
            return Registration.failed(errorResponse.apply("createWorkstreamIfMissing requires"
                + " targetBranch — a workstream cannot be created without the branch it tracks."));
        }
        if (repoUrl == null || repoUrl.isEmpty()) {
            return Registration.failed(errorResponse.apply("createWorkstreamIfMissing requires"
                + " repoUrl — a workstream is identified by repository and branch together, and"
                + " one created without a repository would have nothing to check out."));
        }

        Registration created = register(
                registrationRequest(targetBranch, repoUrl,
                        JsonFieldExtractor.extractString(body, "baseBranch")),
                targetBranch);
        if (created.failure() == null) {
            log.accept("Workstream " + (created.existing() ? "resolved" : "created")
                + " on submit for branch=" + targetBranch + "/repo=" + repoUrl
                + ": " + created.workstream().getWorkstreamId());
        }
        return created;
    }

    /**
     * Builds the registration request for a workstream created during a job
     * submission.
     *
     * <p>Only the fields that describe the workstream itself carry over. The
     * submission body is deliberately not forwarded: fields such as
     * {@code requiredLabels} and {@code phaseConfigs} mean "for this job"
     * there and "for every job on this workstream" in a registration, and a
     * one-off job constraint must not silently become a permanent default of
     * the workstream it created. Everything else — the Slack channel name,
     * the planning document, runner configuration — takes the same defaults a
     * bare {@code POST /api/workstreams} would produce, and can be set
     * afterwards through the update endpoint or {@code /flowtree config}.</p>
     *
     * @param targetBranch the branch the workstream tracks
     * @param repoUrl      the repository the workstream tracks
     * @param baseBranch   the base branch, or {@code null} for the default
     * @return the registration request JSON
     */
    private static String registrationRequest(String targetBranch, String repoUrl,
                                              String baseBranch) {
        StringBuilder json = new StringBuilder();
        json.append("{\"defaultBranch\":\"")
            .append(JsonFieldExtractor.escapeJson(targetBranch)).append("\"");
        json.append(",\"repoUrl\":\"")
            .append(JsonFieldExtractor.escapeJson(repoUrl)).append("\"");
        if (baseBranch != null && !baseBranch.isEmpty()) {
            json.append(",\"baseBranch\":\"")
                .append(JsonFieldExtractor.escapeJson(baseBranch)).append("\"");
        }
        json.append("}");
        return json.toString();
    }

    /**
     * Returns the workstream with the given identifier, or {@code null} when
     * no workspace knows it.
     *
     * @param workstreamId the workstream identifier
     * @return the workstream, or {@code null}
     */
    private Workstream workstreamById(String workstreamId) {
        SlackNotifier notifier = notifiers.notifierFor(workstreamId);
        return notifier != null ? notifier.getWorkstream(workstreamId) : null;
    }

    /**
     * Creates the Slack channel for a workstream that has a channel name but
     * no channel ID, and persists the result.
     *
     * <p>Channel creation at registration time can fail — most often for lack
     * of permission — leaving the workstream with a name and no channel. This
     * retries it, so a workstream registered before the permission was
     * granted starts reporting into Slack without being re-registered.</p>
     *
     * @param workstream the workstream to backfill; may be {@code null}
     */
    void backfillChannel(Workstream workstream) {
        if (workstream == null) return;
        if (workstream.getChannelId() != null && !workstream.getChannelId().isEmpty()) return;
        if (workstream.getChannelName() == null || workstream.getChannelName().isEmpty()) return;

        String workstreamId = workstream.getWorkstreamId();
        String name = workstream.getChannelName();
        if (name.startsWith("#")) {
            name = name.substring(1);
        }
        log.accept("Workstream " + workstreamId
            + " has no channel ID; retrying channel creation for " + name);
        String channelId = notifiers.notifierFor(workstreamId).createChannel(name);
        if (channelId == null) return;

        workstream.setChannelId(channelId);
        log.accept("Channel resolved for workstream " + workstreamId + ": " + channelId);
        // Re-register so channelToWorkstream picks up the new channelId,
        // then persist so the YAML reflects it too.
        if (listener != null) {
            listener.registerWorkstream(workstream);
            listener.persistConfig();
        }
    }

    /**
     * Registers a workstream for {@code defaultBranch}, or returns the one
     * already registered for that branch and repository.
     *
     * <p>This is the shared body of {@code POST /api/workstreams} and of the
     * {@code createWorkstreamIfMissing} path of {@code POST /api/submit}: a
     * job submitted for a branch nobody registered in advance creates the
     * workstream it needs instead of failing. Every optional field is read
     * from {@code body} under the names the registration endpoint uses
     * ({@code baseBranch}, {@code repoUrl}, {@code channelName}, …); the
     * branch is a separate parameter because a submission names it
     * {@code targetBranch}. The submission path supplies a request built by
     * {@link #registrationRequest(String, String, String)} rather than its own
     * body, so job-level fields never become workstream defaults.</p>
     *
     * @param body          the raw request body JSON
     * @param defaultBranch the branch the workstream tracks; must be non-empty
     * @return the resulting {@link Registration}
     */
    Registration register(String body, String defaultBranch) {
        String baseBranch = JsonFieldExtractor.extractString(body, "baseBranch");
        String repoUrl = JsonFieldExtractor.extractString(body, "repoUrl");
        String planningDocument = JsonFieldExtractor.extractString(body, "planningDocument");
        String channelName = JsonFieldExtractor.extractString(body, "channelName");
        if (channelName == null || channelName.isEmpty()) {
            if (defaultBranch.endsWith("/")) {
                return Registration.failed(errorResponse.apply("defaultBranch is malformed: ends with '/'"));
            }
            channelName = SlackNotifier.autoChannelName(defaultBranch, notifiers.allWorkstreams().values());
            if (channelName == null) {
                return Registration.failed(errorResponse.apply(
                    "Could not derive a valid channel name from defaultBranch: " + defaultBranch));
            }
        }
        String explicitWorkspaceId = JsonFieldExtractor.extractString(body, "workspaceId");
        if (explicitWorkspaceId == null || explicitWorkspaceId.isEmpty()) {
            explicitWorkspaceId = JsonFieldExtractor.extractString(body, "slackWorkspaceId"); // legacy alias
        }
        Map<String, String> requiredLabels = JsonFieldExtractor.extractStringObject(body, "requiredLabels");
        List<String> dependentRepos = JsonFieldExtractor.extractStringArray(body, "dependentRepos");
        List<String> completionListeners = extractCompletionListeners(body);
        boolean dispatchCapable = JsonFieldExtractor.extractBoolean(body, "dispatchCapable");
        boolean hasMaxWallClockHours = JsonFieldExtractor.hasField(body, "maxWallClockHours");
        int maxWallClockHours = hasMaxWallClockHours
                ? JsonFieldExtractor.extractInt(body, "maxWallClockHours") : 0;
        boolean defaultUseTmux = JsonFieldExtractor.extractBoolean(body, "defaultUseTmux");
        boolean dormantForCompletionListeners = JsonFieldExtractor.extractBoolean(body, "dormantForCompletionListeners");
        // Explicit kind wins over the heuristic. A missing or empty value means
        // "infer from branch name below"; an unknown value is rejected here so
        // the operator learns of the typo at registration time rather than at
        // the next archive scan. See Workstream.setKind for the vocabulary.
        String kind = JsonFieldExtractor.extractString(body, "kind");
        if (kind != null && !kind.isEmpty() && !Workstream.getKnownKinds().contains(kind)) {
            return Registration.failed(errorResponse.apply(
                "Unknown workstream kind '" + kind
                + "'. Expected one of: " + Workstream.getKnownKinds() + "."));
        }

        // Resolve the target workspace: an explicit workspaceId wins, then a
        // workspace derived from the repoUrl's GitHub org, then (in legacy
        // single-workspace mode) null / the primary notifier. In
        // multi-workspace mode failing to resolve a workspace is a 400 — the
        // alternative is silently placing the workstream in the wrong one.
        String targetWorkspaceId = explicitWorkspaceId;
        if ((targetWorkspaceId == null || targetWorkspaceId.isEmpty()) && repoUrl != null) {
            String org = GitHubProxyHandler.extractOrgFromRepoUrl(repoUrl);
            if (org != null) {
                targetWorkspaceId = orgToWorkspaceId.get(org);
            }
        }
        if ((targetWorkspaceId == null || targetWorkspaceId.isEmpty())
                && notifiers.isMultiWorkspace()) {
            return Registration.failed(errorResponse.apply("Could not determine target"
                    + " workspace. Supply workspaceId in the request body, or a repoUrl"
                    + " whose GitHub org matches a workspace in the controller config."));
        }
        if (targetWorkspaceId != null && !targetWorkspaceId.isEmpty()
                && notifiers.isMultiWorkspace()
                && !notifiers.notifiersByWorkspace().containsKey(targetWorkspaceId)) {
            return Registration.failed(errorResponse.apply("Unknown workspace: " + targetWorkspaceId));
        }

        SlackNotifier targetNotifier = notifiers.notifierForWorkspace(targetWorkspaceId);

        // Check for an existing workstream with the same branch and repo
        // across every workspace so callers don't race-create duplicates if
        // the workspace derivation ever differs between calls.
        Workstream existing = notifiers.findByBranchAndRepo(defaultBranch, repoUrl);
        if (existing != null) {
            log.accept("Workstream already exists for branch " + defaultBranch
                + ": " + existing.getWorkstreamId() + " — returning existing");
            return Registration.alreadyRegistered(existing);
        }

        // Auto-create Slack channel if a name is provided — must be created
        // on the target workspace's notifier so the channel lives in the
        // right Slack.
        String channelId = null;
        if (channelName != null && !channelName.isEmpty()) {
            channelId = targetNotifier.createChannel(channelName);
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
        String phaseConfigErr = PhaseConfigResolver.applyToWorkstream(workstream, body);
        if (phaseConfigErr != null) return Registration.failed(errorResponse.apply(phaseConfigErr));

        workstream.setPushToOrigin(true);

        // Stamp the workspace assignment so listener.registerWorkstream()
        // routes to the correct per-workspace notifier and so subsequent
        // API calls can identify the owning workspace.
        if (targetWorkspaceId != null && !targetWorkspaceId.isEmpty()) {
            workstream.setWorkspaceId(targetWorkspaceId);
        }

        // Cycle-check the proposed listener list against the live graph
        // BEFORE persisting. A self-edge or a 2-node / N-node cycle is
        // rejected with a 400; the error message names the offending
        // path so the operator can correct the registration.
        String cycleErr = checkListenerCycle(workstream, completionListeners);
        if (cycleErr != null) return Registration.failed(errorResponse.apply(cycleErr));
        workstream.setCompletionListeners(completionListeners);
        // Dispatch capability: the default is false; only opt-in workstreams
        // can register or update child workstreams. The flag is purely a
        // harness-CSV switch on the agent allowlist (see McpConfigBuilder);
        // the controller-side backstop is enforced on the calling workstream
        // when an ar-manager tool that requires dispatch is invoked.
        workstream.setDispatchCapable(dispatchCapable);
        // Workstream-level default for tmux-backed agent launches. The
        // default is false; opt in explicitly to make every job on this
        // workstream launch inside a tmux session by default. The per-job
        // use_tmux flag still wins on a per-job basis, so individual jobs
        // can opt in or out of tmux even when the workstream default is on.
        workstream.setUseTmux(defaultUseTmux);
        // Workstream-level wall-clock ceiling. Left null unless the caller
        // supplied one, so an unset workstream inherits the governor default
        // rather than pinning today's default into its persisted config.
        if (hasMaxWallClockHours) {
            workstream.setMaxWallClockHours(Integer.valueOf(maxWallClockHours));
        }
        // Listener-side dormancy flag for the completion-listener
        // cascade. Default false on register; mutable on update so
        // the orchestrator can flip its own state mid-run.
        workstream.setDormantForCompletionListeners(dormantForCompletionListeners);
        // Workstream classification. Explicit kind wins; otherwise infer
        // from the branch name. orchestrator when defaultBranch equals
        // baseBranch (the branch is the trunk); standing when defaultBranch
        // starts with the orchestration/ convention; feature otherwise.
        // The heuristic is intentionally narrow — it only fires when the
        // caller did not pass an explicit kind — so an operator who knows
        // better always wins.
        if (kind != null && !kind.isEmpty()) {
            workstream.setKind(kind);
        } else {
            String base = workstream.getBaseBranch();
            String def = workstream.getDefaultBranch();
            if (base != null && !base.isEmpty() && base.equals(def)) {
                workstream.setKind("orchestrator");
            } else if (def != null && def.startsWith("orchestration/")) {
                workstream.setKind("standing");
            } else {
                workstream.setKind("feature");
            }
        }

        boolean persisted;
        if (listener != null) {
            persisted = listener.registerAndPersistWorkstream(workstream);
        } else {
            targetNotifier.registerWorkstream(workstream);
            persisted = true;
        }
        // Never confirm a registration whose record did not reach disk.
        if (!persisted) {
            log.accept("Registration persist failed for " + workstream.getWorkstreamId()
                + " (branch=" + defaultBranch + ", channel=" + channelName + ")");
            return Registration.failed(FlowTreeApiEndpoint.persistFailureResponse("Registration"));
        }

        // Read the workstream back through the same view the list and context
        // endpoints use (notifiers.allWorkstreams()) before reporting success.
        // Registration mutates several in-memory maps and persists to YAML; if
        // any step silently failed — or a concurrent reload raced the persist —
        // this catches it so the caller gets an honest failure instead of a
        // green ok:true for a workstream that cannot actually be used.
        if (!notifiers.allWorkstreams().containsKey(workstream.getWorkstreamId())) {
            log.accept("Registration read-back failed for " + workstream.getWorkstreamId()
                + " (branch=" + defaultBranch + ", channel=" + channelName + ")"
                + " — workstream not resolvable after register");
            return Registration.failed(NanoHTTPD.newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, "application/json",
                "{\"ok\":false,\"error\":\"Workstream registered but not resolvable after"
                + " persistence; registration is not durable. Retry.\"}"));
        }

        log.accept("Registered workstream via API: " + workstream.getWorkstreamId()
            + " (branch=" + defaultBranch + ", channel=" + channelName + ")");

        return Registration.created(workstream, channelId, channelName);
    }

    /**
     * Handles {@code POST /api/workstreams/{id}/update} to update an existing workstream.
     *
     * <p>Supports updating any combination of: {@code channelId}, {@code channelName},
     * {@code defaultBranch}, {@code baseBranch}, {@code repoUrl},
     * {@code planningDocument}, {@code requiredLabels}, {@code dependentRepos},
     * {@code defaultPhaseConfig}, {@code phaseConfigs}.</p>
     *
     * <p>Runner / model / effort defaults are configured through
     * {@code defaultPhaseConfig} / {@code phaseConfigs}; the legacy
     * {@code model} / {@code effort} / {@code runners} / {@code defaultRunner}
     * fields are rejected with a 400.</p>
     *
     * @param session      the HTTP session
     * @param workstreamId the workstream identifier from the URL path
     * @return JSON response confirming the update
     */
    Response handleUpdate(IHTTPSession session, String workstreamId) {
        String body = readBody.apply(session);
        if (body == null) {
            return errorResponse.apply("Failed to read request body");
        }
        String legacyErr = PhaseConfigResolver.rejectLegacyRequestFields(body);
        if (legacyErr != null) return errorResponse.apply(legacyErr);

        SlackNotifier ownerNotifier = notifiers.notifierFor(workstreamId);
        Workstream workstream = ownerNotifier != null
                ? ownerNotifier.getWorkstream(workstreamId) : null;
        if (workstream == null) {
            return errorResponse.apply("Unknown workstream: " + workstreamId);
        }

        String channelId = JsonFieldExtractor.extractString(body, "channelId");
        String channelName = JsonFieldExtractor.extractString(body, "channelName");
        String defaultBranch = JsonFieldExtractor.extractString(body, "defaultBranch");
        String baseBranch = JsonFieldExtractor.extractString(body, "baseBranch");
        String repoUrl = JsonFieldExtractor.extractString(body, "repoUrl");
        String planningDocument = JsonFieldExtractor.extractString(body, "planningDocument");
        Map<String, String> requiredLabels = JsonFieldExtractor.extractStringObject(body, "requiredLabels");
        List<String> dependentRepos = JsonFieldExtractor.extractStringArray(body, "dependentRepos");
        boolean hasCompletionListeners = JsonFieldExtractor.hasField(body, "completionListeners");
        List<String> completionListeners = hasCompletionListeners
                ? extractCompletionListeners(body) : null;

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
        String phaseConfigErr = PhaseConfigResolver.applyToWorkstream(workstream, body);
        if (phaseConfigErr != null) return errorResponse.apply(phaseConfigErr);
        if (!requiredLabels.isEmpty()) {
            workstream.setRequiredLabels(requiredLabels);
        }
        if (dependentRepos != null && !dependentRepos.isEmpty()) {
            workstream.setDependentRepos(dependentRepos);
        }
        if (hasCompletionListeners) {
            // Cycle-check the proposed listener list against the live
            // graph BEFORE persisting. The check accepts the new state
            // of {@code workstream} (including the proposed listener list
            // when present) so the update is validated against the
            // post-update graph, not the pre-update one.
            String cycleErr = checkListenerCycle(workstream, completionListeners);
            if (cycleErr != null) return errorResponse.apply(cycleErr);
            workstream.setCompletionListeners(completionListeners);
        }
        // Dispatch capability: only mutated when the field is present in
        // the body (presence signal mirrors the completion_listeners
        // pattern). Omitting the field leaves the workstream's existing
        // value untouched so an unrelated update does not silently
        // revoke dispatch. A boolean false in the body explicitly
        // clears the flag.
        if (JsonFieldExtractor.hasField(body, "dispatchCapable")) {
            workstream.setDispatchCapable(
                    JsonFieldExtractor.extractBoolean(body, "dispatchCapable"));
        }
        // Workstream-level tmux default: same presence-signal pattern as
        // dispatch_capable so an unrelated update does not silently flip
        // the workstream's tmux opt-in. Omitting the field leaves the
        // workstream's existing useTmux value untouched; a boolean false
        // in the body explicitly clears the opt-in.
        if (JsonFieldExtractor.hasField(body, "defaultUseTmux")) {
            workstream.setUseTmux(
                    JsonFieldExtractor.extractBoolean(body, "defaultUseTmux"));
        }
        // Workstream-level wall-clock ceiling: same presence-signal pattern.
        // A negative value clears the override so the workstream returns to
        // inheriting the default; zero is retained as "no ceiling", which is
        // a different thing and must stay expressible.
        if (JsonFieldExtractor.hasField(body, "maxWallClockHours")) {
            int hours = JsonFieldExtractor.extractInt(body, "maxWallClockHours");
            workstream.setMaxWallClockHours(hours < 0 ? null : Integer.valueOf(hours));
        }
        // Listener-side dormancy flag for the completion-listener
        // cascade. Same presence-signal pattern as useTmux above:
        // omitting the field leaves the existing value untouched; a
        // boolean in the body explicitly sets it. See
        // Workstream#dormantForCompletionListeners for the semantics.
        if (JsonFieldExtractor.hasField(body, "dormantForCompletionListeners")) {
            workstream.setDormantForCompletionListeners(
                    JsonFieldExtractor.extractBoolean(body, "dormantForCompletionListeners"));
        }
        // kind: presence signal. An explicit value (one of
        // "feature"/"orchestrator"/"standing") sets it; an unknown value
        // is rejected so a typo does not silently change the lifecycle
        // verdict. Omitting the field leaves the workstream's existing
        // classification untouched so an unrelated update does not
        // downgrade a standing row back to feature.
        if (JsonFieldExtractor.hasField(body, "kind")) {
            String updateKind = JsonFieldExtractor.extractString(body, "kind");
            if (updateKind != null && !updateKind.isEmpty()) {
                if (!Workstream.getKnownKinds().contains(updateKind)) {
                    return errorResponse.apply(
                        "Unknown workstream kind '" + updateKind
                        + "'. Expected one of: " + Workstream.getKnownKinds() + ".");
                }
                workstream.setKind(updateKind);
            }
        }

        if (listener != null && !listener.registerAndPersistWorkstream(workstream)) {
            return FlowTreeApiEndpoint.persistFailureResponse("Update");
        }

        log.accept("Updated workstream via API: " + workstreamId);

        StringBuilder json = new StringBuilder();
        json.append("{\"ok\":true,\"workstreamId\":\"")
                .append(JsonFieldExtractor.escapeJson(workstreamId))
                .append("\"");
        PhaseConfigBundle updatedBundle = workstream.getPhaseConfigBundle();
        PhaseConfigResolver.appendBundleJson(json, updatedBundle);
        json.append("}");

        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK,
                "application/json", json.toString());
    }

    /**
     * Extracts the {@code completionListeners} array from a request
     * body. The field is always emitted as a JSON array; the MCP
     * layer translates a comma-separated string into one before
     * posting to the controller, so this method reads the array
     * shape directly. A missing or null field maps to an empty list
     * (the inert default — no fan-out, no error).
     *
     * @param body the request body JSON
     * @return the listener list; never {@code null}
     */
    private static List<String> extractCompletionListeners(String body) {
        if (body == null) return Collections.emptyList();
        List<String> parsed = JsonFieldExtractor.extractStringArray(body, "completionListeners");
        if (parsed == null) return Collections.emptyList();
        // Strip blanks and nulls so a stray "  ,  " entry from a
        // hand-rolled client does not become a phantom listener.
        List<String> cleaned = new ArrayList<>(parsed.size());
        for (String s : parsed) {
            if (s == null) continue;
            String trimmed = s.trim();
            if (trimmed.isEmpty()) continue;
            cleaned.add(trimmed);
        }
        return cleaned;
    }

    /**
     * Runs the {@link ListenerCycleChecker} on the proposed listener
     * list against the live listener graph and translates a non-empty
     * cycle path into a 400-style error string. The check accepts the
     * in-flight workstream's current listener list (which may have
     * been pre-populated for an update) so the validation reflects
     * the post-update graph, not the pre-update one.
     *
     * @param workstream        the workstream being configured
     * @param proposedListeners the proposed listener list (may be
     *                          empty or {@code null})
     * @return the error string to return in a 400 response, or
     *         {@code null} when the configuration is valid
     */
    private String checkListenerCycle(Workstream workstream,
                                      List<String> proposedListeners) {
        if (proposedListeners == null) return null;
        Map<String, Workstream> all = notifiers.allWorkstreams();
        // Build a snapshot that includes the in-flight workstream with
        // its POST-update listener list, so the DFS sees the graph as
        // it will exist after this registration completes.
        Map<String, Workstream> effective = new LinkedHashMap<>(all);
        effective.put(workstream.getWorkstreamId(), workstream);
        List<String> path = ListenerCycleChecker.check(
                workstream.getWorkstreamId(), proposedListeners, effective);
        if (path == null || path.isEmpty()) return null;
        if ("self-listing".equals(path.get(0))) {
            return "self-listing: workstream " + path.get(1)
                    + " cannot list itself as a completion listener";
        }
        StringBuilder sb = new StringBuilder("cycle: ");
        for (int i = 1; i < path.size(); i++) {
            if (i > 1) sb.append(" -> ");
            sb.append(path.get(i));
        }
        // Close the loop visually so the operator sees the cycle as
        // an actual cycle, not just a chain.
        if (path.size() > 2) {
            sb.append(" -> ").append(path.get(1));
        }
        return sb.toString();
    }
}
