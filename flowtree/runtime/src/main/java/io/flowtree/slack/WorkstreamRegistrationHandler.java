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
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;
import io.flowtree.JsonFieldExtractor;
import io.flowtree.jobs.agent.PhaseConfigBundle;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Handles workstream registration and update endpoints
 * ({@code POST /api/workstreams} and
 * {@code POST /api/workstreams/{id}/update}) for {@link FlowTreeApiEndpoint}.
 *
 * <p>Both endpoints share the same field surface ({@code defaultBranch},
 * {@code baseBranch}, {@code repoUrl}, {@code planningDocument},
 * {@code channelName}, {@code requiredLabels}, {@code dependentRepos},
 * {@code defaultPhaseConfig}, {@code phaseConfigs}).
 * Registration additionally derives the target Slack workspace from
 * {@code slackWorkspaceId}, the GitHub org of {@code repoUrl}, or the
 * primary notifier in single-workspace mode, then auto-creates a Slack
 * channel on that workspace.</p>
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

        String baseBranch = JsonFieldExtractor.extractString(body, "baseBranch");
        String repoUrl = JsonFieldExtractor.extractString(body, "repoUrl");
        String planningDocument = JsonFieldExtractor.extractString(body, "planningDocument");
        String channelName = JsonFieldExtractor.extractString(body, "channelName");
        if (channelName == null || channelName.isEmpty()) {
            if (defaultBranch.endsWith("/")) {
                return errorResponse.apply("defaultBranch is malformed: ends with '/'");
            }
            channelName = SlackNotifier.autoChannelName(defaultBranch, notifiers.allWorkstreams().values());
            if (channelName == null) {
                return errorResponse.apply("Could not derive a valid channel name from defaultBranch: " + defaultBranch);
            }
        }
        String explicitWorkspaceId = JsonFieldExtractor.extractString(body, "workspaceId");
        if (explicitWorkspaceId == null || explicitWorkspaceId.isEmpty()) {
            explicitWorkspaceId = JsonFieldExtractor.extractString(body, "slackWorkspaceId"); // legacy alias
        }
        Map<String, String> requiredLabels = JsonFieldExtractor.extractStringObject(body, "requiredLabels");
        List<String> dependentRepos = JsonFieldExtractor.extractStringArray(body, "dependentRepos");

        // Resolve the target Slack workspace: explicit slackWorkspaceId wins,
        // then a workspace derived from the repoUrl's GitHub org, then (in
        // legacy single-workspace mode) null / the primary notifier. In
        // multi-workspace mode failing to resolve a workspace is a 400 — the
        // alternative is silently placing the workstream in the wrong Slack.
        String targetWorkspaceId = explicitWorkspaceId;
        if ((targetWorkspaceId == null || targetWorkspaceId.isEmpty()) && repoUrl != null) {
            String org = GitHubProxyHandler.extractOrgFromRepoUrl(repoUrl);
            if (org != null) {
                targetWorkspaceId = orgToWorkspaceId.get(org);
            }
        }
        if ((targetWorkspaceId == null || targetWorkspaceId.isEmpty())
                && notifiers.isMultiWorkspace()) {
            return errorResponse.apply("Could not determine target Slack workspace. Supply"
                    + " slackWorkspaceId in the request body, or a repoUrl whose"
                    + " GitHub org matches a workspace in the controller config.");
        }
        if (targetWorkspaceId != null && !targetWorkspaceId.isEmpty()
                && notifiers.isMultiWorkspace()
                && !notifiers.notifiersByWorkspace().containsKey(targetWorkspaceId)) {
            return errorResponse.apply("Unknown Slack workspace: " + targetWorkspaceId);
        }

        SlackNotifier targetNotifier = notifiers.notifierForWorkspace(targetWorkspaceId);

        // Check for an existing workstream with the same branch and repo
        // across every workspace so callers don't race-create duplicates if
        // the workspace derivation ever differs between calls.
        Workstream existing = notifiers.findByBranchAndRepo(defaultBranch, repoUrl);
        if (existing != null) {
            log.accept("Workstream already exists for branch " + defaultBranch
                + ": " + existing.getWorkstreamId() + " — returning existing");

            StringBuilder json = new StringBuilder();
            json.append("{\"ok\":true,\"existing\":true");
            json.append(",\"workstreamId\":\"").append(JsonFieldExtractor.escapeJson(existing.getWorkstreamId())).append("\"");
            json.append("}");

            return NanoHTTPD.newFixedLengthResponse(Response.Status.OK,
                    "application/json", json.toString());
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
        if (phaseConfigErr != null) return errorResponse.apply(phaseConfigErr);

        workstream.setPushToOrigin(true);

        // Stamp the workspace assignment so listener.registerWorkstream()
        // routes to the correct per-workspace notifier and so subsequent
        // API calls can identify the owning workspace.
        if (targetWorkspaceId != null && !targetWorkspaceId.isEmpty()) {
            workstream.setWorkspaceId(targetWorkspaceId);
        }

        if (listener != null) {
            listener.registerAndPersistWorkstream(workstream);
        } else {
            targetNotifier.registerWorkstream(workstream);
        }

        log.accept("Registered workstream via API: " + workstream.getWorkstreamId()
            + " (branch=" + defaultBranch + ", channel=" + channelName + ")");

        StringBuilder json = new StringBuilder();
        json.append("{\"ok\":true");
        json.append(",\"workstreamId\":\"").append(JsonFieldExtractor.escapeJson(workstream.getWorkstreamId())).append("\"");
        if (channelId != null) {
            json.append(",\"channelId\":\"").append(JsonFieldExtractor.escapeJson(channelId)).append("\"");
        }
        if (channelName != null) {
            json.append(",\"channelName\":\"").append(JsonFieldExtractor.escapeJson(channelName)).append("\"");
        }
        PhaseConfigBundle registeredBundle = workstream.getPhaseConfigBundle();
        PhaseConfigResolver.appendBundleJson(json, registeredBundle);
        json.append("}");

        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK,
                "application/json", json.toString());
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

        if (listener != null) {
            listener.registerAndPersistWorkstream(workstream);
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
}
