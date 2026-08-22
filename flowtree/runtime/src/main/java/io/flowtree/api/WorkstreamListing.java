/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.flowtree.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import io.flowtree.controller.JobStatsStore;
import io.flowtree.jobs.GitOperations;
import io.flowtree.jobs.JobCompletionEvent;
import io.flowtree.workstream.Workstream;

/**
 * Renders the workstream listing, applying the caller's filters.
 *
 * <p>Separate from {@link FlowTreeApiEndpoint} because the filtering is the
 * substance of the endpoint rather than part of its request routing, and
 * because the endpoint sits at the file-length limit.</p>
 */
final class WorkstreamListing {

    /** Not instantiable: this type is a namespace for the renderer below. */
    private WorkstreamListing() { }

    /**
     * Renders {@code GET /api/workstreams} as a JSON array of registered
     * workstreams with their configuration and capabilities.
     *
     * <p>Accepts optional filters, applied here rather than by the caller so
     * that "which workstreams match this predicate?" costs one request instead
     * of a full listing plus a scan:</p>
     *
     * <ul>
     *   <li>{@code workspaceId} — exact match on the owning workspace.</li>
     *   <li>{@code repoUrl} — matched on repository identity, so the SSH and
     *       HTTPS spellings of one repository are equivalent.</li>
     *   <li>{@code dispatchCapable} — {@code true} or {@code false}.</li>
     *   <li>{@code archived} — {@code true} for archived only, {@code false}
     *       for live only. Supersedes {@code includeArchived}, the older and
     *       coarser parameter, which is still honoured when {@code archived}
     *       is absent.</li>
     * </ul>
     *
     * <p>An empty parameter value counts as absent, so a caller assembling a
     * query from optional values need not omit the keys it has no value for.</p>
     *
     * <p>Two further parameters enrich each surviving entry rather than
     * selecting between them:</p>
     *
     * <ul>
     *   <li>{@code includeStatus} — adds {@code lastJobId},
     *       {@code lastJobStatus} and {@code lastJobAt}.</li>
     *   <li>{@code includePullRequest} — adds {@code pullRequest}, read from
     *       the most recent job that recorded one.</li>
     * </ul>
     *
     * <p>Both default to off, so the cost of the listing is unchanged for a
     * caller that does not ask for them. Each costs one job-store read per
     * surviving workstream, which is why they are opt-in rather than always
     * present: the filters above are what keep that count small.</p>
     *
     * @param session     the request, whose query parameters carry the filters
     * @param workstreams every registered workstream, keyed by id
     * @param statsStore  supplies the job history; {@code null} leaves the
     *                    status and pull-request fields absent
     * @return a JSON array of the workstreams that pass every filter
     */
    static String toJson(IHTTPSession session, Map<String, Workstream> workstreams,
            JobStatsStore statsStore) {
        String workspaceId = RequestParameters.first(session, "workspaceId", null);
        String repoUrl = RequestParameters.first(session, "repoUrl", null);
        String dispatchCapable = RequestParameters.first(session, "dispatchCapable", null);
        String archived = RequestParameters.first(session, "archived", null);
        boolean includeArchived = "true".equalsIgnoreCase(
                RequestParameters.first(session, "includeArchived", "false"));
        boolean includeStatus = "true".equalsIgnoreCase(
                RequestParameters.first(session, "includeStatus", "false"));
        boolean includePullRequest = "true".equalsIgnoreCase(
                RequestParameters.first(session, "includePullRequest", "false"));

        // `archived` is the explicit selector; `includeArchived` is the older
        // parameter it generalises. Both are honoured: archived=true means
        // "only archived", archived=false means "only live", and neither means
        // "live unless includeArchived says otherwise".
        Boolean archivedFilter = archived == null ? null : Boolean.valueOf(
                "true".equalsIgnoreCase(archived));

        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        for (Workstream ws : workstreams.values()) {
            if (archivedFilter != null) {
                if (ws.isArchived() != archivedFilter) continue;
            } else if (!includeArchived && ws.isArchived()) {
                continue;
            }
            if (workspaceId != null && !workspaceId.equals(ws.getWorkspaceId())) continue;
            if (repoUrl != null && !GitOperations.isSameRepository(repoUrl, ws.getRepoUrl())) {
                continue;
            }
            if (dispatchCapable != null
                    && ws.isDispatchCapable() != "true".equalsIgnoreCase(dispatchCapable)) {
                continue;
            }
            if (!first) json.append(",");
            first = false;
            String summary = ws.toSummaryJson();
            String enrichment = (includeStatus || includePullRequest) && statsStore != null
                    ? statusFields(ws, statsStore, includeStatus, includePullRequest) : "";
            if (enrichment.isEmpty()) {
                json.append(summary);
            } else {
                json.append(summary, 0, summary.length() - 1).append(enrichment).append("}");
            }
        }
        json.append("]");
        return json.toString();
    }

    /**
     * Renders the requested status and pull-request fields for one workstream
     * as JSON object members, each with a leading comma so the caller can
     * splice them into the summary object.
     *
     * <p>Both requests are served from a single job-history read. The
     * pull-request URL is taken from the most recent job that recorded one
     * rather than from the newest job alone, because a workstream's PR does
     * not stop existing when a later job runs without touching it.</p>
     *
     * <p>Timestamps are ISO-8601, matching how the rest of the tool surface
     * renders them; {@code toSummaryJson} itself carries no timestamps to
     * follow.</p>
     *
     * @param ws                 the workstream to describe
     * @param statsStore         the job history to read
     * @param includeStatus      whether to emit the last-job fields
     * @param includePullRequest whether to emit the pull-request field
     * @return the members to splice in, or an empty string when there is
     *         nothing to add
     */
    private static String statusFields(Workstream ws, JobStatsStore statsStore,
            boolean includeStatus, boolean includePullRequest) {
        List<JobCompletionEvent> recent =
                statsStore.getRecentJobs(ws.getWorkstreamId(), PR_SEARCH_DEPTH);
        if (recent.isEmpty()) return "";

        StringBuilder json = new StringBuilder();
        if (includeStatus) {
            JobCompletionEvent latest = recent.get(0);
            if (latest.getJobId() != null) {
                json.append(",\"lastJobId\":\"").append(latest.getJobId()).append("\"");
            }
            if (latest.getStatus() != null) {
                json.append(",\"lastJobStatus\":\"").append(latest.getStatus()).append("\"");
            }
            Instant at = latest.getTimestamp();
            if (at != null) {
                json.append(",\"lastJobAt\":\"").append(at).append("\"");
            }
        }
        if (includePullRequest) {
            for (JobCompletionEvent event : recent) {
                String url = event.getPullRequestUrl();
                if (url == null || url.isEmpty()) continue;
                json.append(",\"pullRequest\":{\"url\":\"").append(url).append("\"");
                int number = pullRequestNumber(url);
                if (number > 0) json.append(",\"number\":").append(number);
                json.append("}");
                break;
            }
        }
        return json.toString();
    }

    /**
     * How far back to look for a recorded pull request. Deep enough that a run
     * of follow-up jobs does not hide the PR they belong to, shallow enough
     * that the read stays a single indexed query per workstream.
     */
    private static final int PR_SEARCH_DEPTH = 20;

    /**
     * Extracts the pull-request number from a GitHub pull-request URL.
     *
     * @param url the URL, expected to end in {@code /pull/{number}}
     * @return the number, or {@code -1} when the URL does not carry one
     */
    private static int pullRequestNumber(String url) {
        int slash = url.lastIndexOf('/');
        if (slash < 0 || slash == url.length() - 1) return -1;
        try {
            return Integer.parseInt(url.substring(slash + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
