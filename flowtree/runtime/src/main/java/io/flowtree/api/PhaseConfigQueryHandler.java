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
import fi.iki.elonen.NanoHTTPD.Response;
import io.flowtree.JsonFieldExtractor;
import io.flowtree.jobs.agent.PhaseConfigBundle;
import io.flowtree.submission.PhaseConfigResolver;
import io.flowtree.submission.SubmissionConfigResolver;
import io.flowtree.workstream.WorkspaceEntry;
import io.flowtree.workstream.Workstream;

import java.util.List;
import java.util.function.Function;

/**
 * Handles {@code POST /api/workstreams/{id}/phase-config} for
 * {@link FlowTreeApiEndpoint}: a read-only diagnostic that explains how each
 * phase's {@code (runner, model, effort, provider)} resolves for a workstream,
 * and which precedence level supplied each value.
 *
 * <p>The request body is optional and mirrors a job submission's per-phase
 * configuration fields ({@code defaultPhaseConfig} / {@code phaseConfigs} and
 * their {@code fallback*} counterparts), so a caller can preview how a
 * <em>hypothetical</em> submission would resolve without submitting a job. With
 * an empty body the diagnostic reports how the workstream resolves today across
 * the workstream, workspace and controller-default levels.</p>
 *
 * <p>Resolution runs through the same {@link SubmissionConfigResolver} the
 * submit path uses, so this endpoint doubles as a dry-run validator: a
 * configuration that would be rejected at submission (an unsupported
 * model-for-runner, an incompatible provider) is reported here as an
 * {@code error} instead of a resolution map, before a job is ever created.
 * Unlike the submit path, the body is also checked strictly: a malformed body,
 * an unknown phase or key, or a non-string value is reported rather than
 * silently ignored, since a dry run that ignored them would describe a
 * configuration the caller did not ask for.</p>
 *
 * @author Michael Murray
 * @see FlowTreeApiEndpoint
 * @see PhaseConfigResolver#resolutionOf(io.flowtree.jobs.agent.Phase)
 */
class PhaseConfigQueryHandler {

    /** Resolves a workstream id to its {@link Workstream}, or {@code null}. */
    private final Function<String, Workstream> workstreamLookup;

    /** Resolves a workspace id to its {@link WorkspaceEntry}, or {@code null}. */
    private final Function<String, WorkspaceEntry> workspaceLookup;

    /**
     * @param workstreamLookup resolves a workstream id to its {@link Workstream};
     *                         returns {@code null} when the id is unknown
     * @param workspaceLookup  resolves a workspace id to its {@link WorkspaceEntry};
     *                         returns {@code null} when the workstream has no
     *                         workspace or it does not resolve
     */
    PhaseConfigQueryHandler(Function<String, Workstream> workstreamLookup,
                            Function<String, WorkspaceEntry> workspaceLookup) {
        this.workstreamLookup = workstreamLookup;
        this.workspaceLookup = workspaceLookup;
    }

    /**
     * Handles a {@code POST /api/workstreams/{id}/phase-config} request.
     *
     * @param workstreamId the workstream to explain; must resolve through the
     *                     lookup supplied to the constructor
     * @param body         the optional request body carrying hypothetical
     *                     per-job {@code defaultPhaseConfig} / {@code phaseConfigs}
     *                     / {@code fallback*} overrides; {@code null} or empty
     *                     explains the workstream as configured today
     * @return a {@code 200} response with {@code ok=true} plus the resolved
     *         bundle, the per-phase {@code phaseConfigResolution} map and any
     *         {@code warnings}; a {@code 404} with {@code ok=false} and an
     *         {@code error} when the workstream is unknown; or a {@code 400}
     *         with {@code ok=false} and an {@code error} when the body is
     *         malformed (see {@link PhaseConfigResolver#requestShapeError(String)}),
     *         carries a removed legacy field, or resolves to a configuration
     *         the submit path would reject
     */
    Response handle(String workstreamId, String body) {
        Workstream workstream = workstreamLookup.apply(workstreamId);
        if (workstream == null) {
            return error(Response.Status.NOT_FOUND, workstreamId,
                    "Unknown workstream: " + workstreamId);
        }
        String requestErr = PhaseConfigResolver.rejectLegacyRequestFields(body);
        if (requestErr == null) requestErr = PhaseConfigResolver.requestShapeError(body);
        if (requestErr != null) {
            return error(Response.Status.BAD_REQUEST, workstreamId, requestErr);
        }
        String wsId = workstream.getWorkspaceId();
        WorkspaceEntry wsEntry = (wsId != null && !wsId.isEmpty())
                ? workspaceLookup.apply(wsId) : null;

        PhaseConfigBundle requestBundle = PhaseConfigResolver.bundleFromRequest(body);
        PhaseConfigBundle fallbackBundle = PhaseConfigResolver.fallbackBundleFromRequest(body);
        SubmissionConfigResolver resolver = SubmissionConfigResolver.resolve(
                requestBundle, fallbackBundle, workstream, wsEntry);
        if (resolver.error() != null) {
            return error(Response.Status.BAD_REQUEST, workstreamId, resolver.error());
        }

        PhaseConfigResolver phaseConfig = resolver.phaseConfigResolver();
        StringBuilder json = new StringBuilder();
        json.append("{\"ok\":true,\"workstreamId\":")
                .append(FlowTreeApiEndpoint.escapeJsonValue(workstreamId));
        PhaseConfigResolver.appendBundleJson(json, phaseConfig.resolvedBundle());
        phaseConfig.appendPhaseConfigResolutionJson(json);
        phaseConfig.appendFallbackResolutionJson(json);
        appendWarnings(json, phaseConfig.capabilityWarnings());
        json.append("}");
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK,
                "application/json", json.toString());
    }

    /** Appends a {@code ,"warnings":[...]} array when non-empty; nothing otherwise. */
    private static void appendWarnings(StringBuilder json, List<String> warnings) {
        if (warnings.isEmpty()) return;
        json.append(",\"warnings\":[");
        boolean first = true;
        for (String warning : warnings) {
            if (!first) json.append(",");
            first = false;
            json.append(FlowTreeApiEndpoint.escapeJsonValue(warning));
        }
        json.append("]");
    }

    /** Builds an {@code {"ok":false,"workstreamId":...,"error":...}} response with {@code status}. */
    private static Response error(Response.Status status, String workstreamId, String message) {
        String json = "{\"ok\":false,\"workstreamId\":"
                + FlowTreeApiEndpoint.escapeJsonValue(workstreamId)
                + ",\"error\":" + FlowTreeApiEndpoint.escapeJsonValue(message) + "}";
        return NanoHTTPD.newFixedLengthResponse(status, "application/json", json);
    }
}
