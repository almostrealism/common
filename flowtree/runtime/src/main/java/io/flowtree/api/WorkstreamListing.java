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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import io.flowtree.JsonFieldExtractor;
import io.flowtree.controller.JobStatsStore;
import io.flowtree.github.GitHubProxyHandler;
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

    /** How long a GitHub PR lookup is cached, per (owner, repo). Short TTL keeps repeat
     * scans cheap without committing the controller to stale state across an archival
     * triage session. */
    private static final long PR_CACHE_TTL_MS = 60_000L;

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
     *   <li>{@code lifecycle} — exact-match filter on the per-row lifecycle
     *       classification. Applied after enrichment so the same listing
     *       call can ask "show me the merged workstreams" without an extra
     *       round trip per row.</li>
     * </ul>
     *
     * <p>Two further parameters enrich each surviving entry rather than
     * selecting between them:</p>
     *
     * <ul>
     *   <li>{@code includeStatus} — adds {@code lastJobId},
     *       {@code lastJobStatus}, {@code lastJobAt},
     *       {@code lastJobStartedAt} and {@code lastJobFinishedAt}.</li>
     *   <li>{@code includePullRequest} — adds {@code pullRequest}, read from
     *       the most recent job that recorded one.</li>
     *   <li>{@code includePullRequestState} — adds {@code pullRequestState},
     *       {@code prCount} derived from a GitHub lookup keyed by
     *       {@code defaultBranch}. Coalesced across workstreams so a
     *       multi-row listing that lives on two repositories issues two
     *       GitHub calls, not N.</li>
     *   <li>{@code includeLifecycle} — adds {@code lifecycle} and
     *       {@code lifecycleReason}. The classifier uses GitHub PR state
     *       (when available) plus the persisted last-job timestamp.</li>
     * </ul>
     *
     * <p>All default to off, so the cost of the listing is unchanged for a
     * caller that does not ask for them. The PR and lifecycle enrichments
     * cost one GitHub call per distinct repository in the surviving set; the
     * results are cached in-memory for {@link #PR_CACHE_TTL_MS} ms.</p>
     *
     * @param session     the request, whose query parameters carry the filters
     * @param workstreams every registered workstream, keyed by id
     * @param statsStore  supplies the job history; {@code null} leaves the
     *                    status and pull-request fields absent
     * @return a JSON array of the workstreams that pass every filter
     */
    static String toJson(IHTTPSession session, Map<String, Workstream> workstreams,
            JobStatsStore statsStore) {
        return toJson(session, workstreams, statsStore, null);
    }

    /**
     * Overload that accepts an injected {@link GitHubProxyHandler} for
     * PR-state enrichment. Production code passes the controller's handler;
     * tests can pass {@code null} and skip the enrichment.
     */
    static String toJson(IHTTPSession session, Map<String, Workstream> workstreams,
            JobStatsStore statsStore, GitHubProxyHandler githubHandler) {
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
        boolean includePullRequestState = "true".equalsIgnoreCase(
                RequestParameters.first(session, "includePullRequestState", "false"));
        boolean includeLifecycle = "true".equalsIgnoreCase(
                RequestParameters.first(session, "includeLifecycle", "false"));
        int idleDays = parseIdleDays(session);
        String lifecycleFilter = RequestParameters.first(session, "lifecycle", "");
        // A lifecycle filter is meaningless without the classification that
        // feeds it — enrich implicitly rather than silently dropping every
        // row when a caller passes `lifecycle` without `includeLifecycle`.
        if (!lifecycleFilter.isEmpty()) {
            includeLifecycle = true;
        }

        // `archived` is the explicit selector; `includeArchived` is the older
        // parameter it generalises. Both are honoured: archived=true means
        // "only archived", archived=false means "only live", and neither means
        // "live unless includeArchived says otherwise".
        Boolean archivedFilter = archived == null ? null : Boolean.valueOf(
                "true".equalsIgnoreCase(archived));

        // First pass: apply the cheap filters so we only pay the per-row
        // GitHub enrichment for surviving workstreams.
        List<Workstream> survivors = new ArrayList<>();
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
            survivors.add(ws);
        }

        // Coalesced by repository; see warmPrCache and PR_CACHE javadoc.
        if ((includePullRequestState || includeLifecycle) && githubHandler != null) {
            warmPrCache(githubHandler, survivors);
        }

        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        for (Workstream ws : survivors) {
            boolean enriched = false;
            StringBuilder extras = new StringBuilder();
            if ((includeStatus || includePullRequest) && statsStore != null) {
                String statusExtras = statusFields(ws, statsStore, includeStatus, includePullRequest);
                if (!statusExtras.isEmpty()) {
                    extras.append(statusExtras);
                    enriched = true;
                }
            }
            String lifecycleLabel = null;
            if (includePullRequestState || includeLifecycle) {
                String prFields = prAndLifecycleFields(ws, statsStore,
                        includePullRequestState, includeLifecycle, idleDays);
                if (!prFields.isEmpty()) {
                    extras.append(prFields);
                    enriched = true;
                    lifecycleLabel = LifecycleClassifier.extractLifecycle(prFields);
                }
            }
            // Apply the lifecycle filter now that the classification has run;
            // a `merged` filter must drop rows whose classification is anything
            // else, including rows the enrichment would have added.
            if (!lifecycleFilter.isEmpty()
                    && !lifecycleFilter.equals(lifecycleLabel == null ? "" : lifecycleLabel)) {
                continue;
            }
            if (!first) json.append(",");
            first = false;
            String summary = ws.toSummaryJson();
            if (enriched) {
                json.append(summary, 0, summary.length() - 1).append(extras).append("}");
            } else {
                json.append(summary);
            }
        }
        json.append("]");
        return json.toString();
    }

    /**
     * Parses the {@code idleDays} query parameter, falling back to 14 when
     * absent, blank, non-numeric, or non-positive.
     *
     * @param session the request whose query parameters carry the filter
     * @return the idle window in days
     */
    private static int parseIdleDays(IHTTPSession session) {
        String raw = RequestParameters.first(session, "idleDays", "14");
        try {
            int n = Integer.parseInt(raw);
            return n > 0 ? n : 14;
        } catch (NumberFormatException e) {
            return 14;
        }
    }

    /**
     * Holds one GitHub PR lookup's results. Both the "latest PR" and the
     * "count of PRs" come from the same listing response, so we cache the
     * parsed array once and read both values out of it.
     */
    static final class PrLookup {
        /** Full array response grouped by branch; may be empty. */
        final Map<String, List<JsonNode>> byBranch;
        /** Wall-clock instant at which this lookup was performed, in milliseconds. */
        final long resolvedAtMs;

        /**
         * Creates a new cache entry holding the PRs grouped by branch and
         * the timestamp at which the lookup ran.
         *
         * @param byBranch the PRs grouped by their head branch
         */
        PrLookup(Map<String, List<JsonNode>> byBranch) {
            this.byBranch = byBranch;
            this.resolvedAtMs = System.currentTimeMillis();
        }

        /** Returns {@code true} when the entry has not yet exceeded {@link #PR_CACHE_TTL_MS}. */
        boolean fresh() {
            return System.currentTimeMillis() - resolvedAtMs <= PR_CACHE_TTL_MS;
        }
    }

    /**
     * Per-{@code (owner, repo)} GitHub PR lookup cache, shared across every
     * request this process serves rather than scoped to one {@link #toJson}
     * call. Sharing it is what makes {@link #PR_CACHE_TTL_MS} meaningful: a
     * request-scoped cache is warmed and discarded within the same call, so a
     * second listing issued a second later would still pay for a fresh
     * GitHub round trip. {@link PrLookup#fresh()} is what actually bounds
     * how stale a shared entry can get.
     */
    private static final Map<String, PrLookup> PR_CACHE = new ConcurrentHashMap<>();

    /**
     * One GitHub call per distinct repository across the surviving workstreams
     * whose {@link #PR_CACHE} entry is missing or stale. The branches are
     * split client-side. PRs whose {@code head.repo.full_name} does not match
     * {@code ownerRepo} are excluded so a fork PR sharing a branch name with a
     * base-repo branch cannot be mistaken for that branch's own PR.
     */
    private static void warmPrCache(GitHubProxyHandler handler, List<Workstream> survivors) {
        Set<String> repoSet = new LinkedHashSet<>();
        for (Workstream ws : survivors) {
            String ownerRepo = GitHubProxyHandler.extractOwnerRepo(ws.getRepoUrl());
            if (ownerRepo != null) repoSet.add(ownerRepo);
        }
        for (String ownerRepo : repoSet) {
            PrLookup existing = PR_CACHE.get(ownerRepo);
            if (existing != null && existing.fresh()) continue;
            JsonNode listing = handler.listPullRequestsByRepo(ownerRepo);
            Map<String, List<JsonNode>> byBranch = new LinkedHashMap<>();
            if (listing != null && listing.isArray()) {
                for (JsonNode pr : listing) {
                    JsonNode head = pr.path("head");
                    String headRepo = head.path("repo").path("full_name").asText(null);
                    if (headRepo != null && !headRepo.equalsIgnoreCase(ownerRepo)) continue;
                    String branchName = head.path("ref").asText(null);
                    if (branchName == null || branchName.isEmpty()) continue;
                    byBranch.computeIfAbsent(branchName, k -> new ArrayList<>()).add(pr);
                }
            }
            PR_CACHE.put(ownerRepo, new PrLookup(byBranch));
        }
    }

    /**
     * Builds the {@code pullRequestState} / {@code prCount} / {@code lifecycle} /
     * {@code lifecycleReason} members for a single workstream. The workstream's
     * PR data is sourced from {@link #PR_CACHE}, populated by
     * {@link #warmPrCache}. The classifier consults PR state, the persisted last
     * job time, and the workstream's {@code kind} so a standing workstream
     * never reports {@code merged} regardless of PR state.
     *
     * <p>{@code branchPrs} is {@code null} only when the repository could not
     * be resolved to a cache entry at all (unknown repo, or the cache entry
     * expired between {@link #warmPrCache} and this call); a repository that
     * resolved but has no PRs for {@code branch} yields an empty list. That
     * distinction matters to {@link LifecycleClassifier#classify}, which
     * treats {@code null} as "unknown" and an empty list as "idle".</p>
     *
     * @param statsStore supplies the workstream's most recent job timestamp
     *                   for the classifier's idle-window comparison;
     *                   {@code null} leaves that comparison unavailable
     */
    private static String prAndLifecycleFields(Workstream ws, JobStatsStore statsStore,
                                                boolean includePullRequestState,
                                                boolean includeLifecycle,
                                                int idleDays) {
        StringBuilder extras = new StringBuilder();
        String ownerRepo = GitHubProxyHandler.extractOwnerRepo(ws.getRepoUrl());
        String branch = ws.getDefaultBranch();
        List<JsonNode> branchPrs = null;
        PrLookup cached = ownerRepo != null ? PR_CACHE.get(ownerRepo) : null;
        if (cached != null && cached.fresh()) {
            branchPrs = cached.byBranch.getOrDefault(branch, List.of());
        }

        if (includePullRequestState) {
            int branchCount = branchPrs == null ? 0 : branchPrs.size();
            extras.append(",\"prCount\":").append(branchCount);
            if (branchPrs != null && !branchPrs.isEmpty()) {
                JsonNode pr = branchPrs.get(0);
                extras.append(",\"pullRequestState\":").append(prStateJson(pr));
            }
        }

        if (includeLifecycle) {
            Instant lastJobAt = null;
            if (statsStore != null) {
                List<JobCompletionEvent> recent = statsStore.getRecentJobs(ws.getWorkstreamId(), 1);
                if (!recent.isEmpty()) lastJobAt = recent.get(0).getEventTime();
            }
            LifecycleClassifier.Classification cls =
                    LifecycleClassifier.classify(ws, branchPrs, idleDays, lastJobAt);
            extras.append(",\"lifecycle\":\"").append(cls.label).append("\"");
            if (cls.reason != null) {
                extras.append(",\"lifecycleReason\":\"")
                        .append(JsonFieldExtractor.escapeJson(cls.reason)).append("\"");
            }
        }
        return extras.toString();
    }

    /** Renders a single GitHub PR as a JSON object suitable for the {@code pullRequestState} field. */
    private static String prStateJson(JsonNode pr) {
        StringBuilder sb = new StringBuilder("{");
        boolean merged = !pr.path("merged_at").isMissingNode()
                && !pr.path("merged_at").isNull()
                && !pr.path("merged_at").asText().isEmpty();
        String state = pr.path("state").asText("");
        String effective = merged ? "merged" : state;
        sb.append("\"state\":\"").append(effective).append("\"");
        sb.append(",\"merged\":").append(merged);
        if (!pr.path("merged_at").isMissingNode() && !pr.path("merged_at").isNull()) {
            sb.append(",\"mergedAt\":\"").append(pr.path("merged_at").asText()).append("\"");
        }
        if (!pr.path("closed_at").isMissingNode() && !pr.path("closed_at").isNull()) {
            sb.append(",\"closedAt\":\"").append(pr.path("closed_at").asText()).append("\"");
        }
        sb.append(",\"number\":").append(pr.path("number").asInt());
        sb.append(",\"url\":\"").append(JsonFieldExtractor.escapeJson(pr.path("html_url").asText())).append("\"");
        sb.append("}");
        return sb.toString();
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
     * <p>Three timestamp fields are emitted when the row's {@code completed_at}
     * and {@code started_at} differ: {@code lastJobStartedAt} (always) and
     * {@code lastJobFinishedAt} (only when {@code completed_at} is non-null).
     * {@code lastJobAt} is retained as a backwards-compatible alias for the
     * finished-at value when present, so existing callers keep working. Both
     * come from the persisted row, not the constructor stamp, so two listings
     * issued seconds apart return byte-identical values for the same workstream.</p>
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
            // getEventTime() returns the persisted row time (or the constructor
            // stamp as a fallback). Use this instead of getTimestamp() to keep
            // two listings issued seconds apart byte-identical.
            Instant eventTime = latest.getEventTime();
            Instant started = latest.getStartedAt();
            Instant finished = latest.getFinishedAt();
            if (eventTime != null) {
                json.append(",\"lastJobAt\":\"").append(eventTime).append("\"");
            }
            if (started != null) {
                json.append(",\"lastJobStartedAt\":\"").append(started).append("\"");
            }
            if (finished != null) {
                json.append(",\"lastJobFinishedAt\":\"").append(finished).append("\"");
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