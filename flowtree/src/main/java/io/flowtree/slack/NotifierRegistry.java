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

import io.flowtree.jobs.JobCompletionEvent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Aggregation helper used by {@link FlowTreeApiEndpoint} to resolve workstream
 * and job lookups across multiple per-workspace {@link SlackNotifier}
 * instances.
 *
 * <p>In single-workspace (legacy) mode only {@link #primary} is populated; in
 * multi-workspace mode the map {@link #byWorkspace} carries one notifier per
 * Slack team ID and {@link #primary} points at the first entry for operations
 * that need a sensible default. All resolution methods walk the map and fall
 * back to the primary when the lookup yields nothing.</p>
 */
final class NotifierRegistry {

    /**
     * The notifier used when no workspace claims a workstream (single-workspace
     * mode, or operations that are scheduled before a workspace is chosen —
     * e.g. brand-new channel creation).
     */
    private final SlackNotifier primary;

    /**
     * All notifiers keyed by Slack team ID (T...). Empty in single-workspace
     * mode; populated in multi-workspace mode by
     * {@link FlowTreeController#startApiEndpoint()}.
     */
    private final Map<String, SlackNotifier> byWorkspace;

    /**
     * Creates a registry backed by the given primary notifier and optional
     * per-workspace map. The map is defensively copied.
     *
     * @param primary     the fallback notifier (may be {@code null})
     * @param byWorkspace workspace-ID → notifier map, or {@code null} for empty
     */
    NotifierRegistry(SlackNotifier primary, Map<String, SlackNotifier> byWorkspace) {
        this.primary = primary;
        this.byWorkspace = byWorkspace != null
                ? new LinkedHashMap<>(byWorkspace)
                : new LinkedHashMap<>();
    }

    /** Returns the primary (fallback) notifier; may be {@code null}. */
    SlackNotifier primary() {
        return primary;
    }

    /** Returns {@code true} when this registry was built in multi-workspace mode. */
    boolean isMultiWorkspace() {
        return !byWorkspace.isEmpty();
    }

    /** Returns the live notifiers keyed by workspace ID (unmodifiable view). */
    Map<String, SlackNotifier> notifiersByWorkspace() {
        return java.util.Collections.unmodifiableMap(byWorkspace);
    }

    /**
     * Returns the notifier owning the given workstream, or {@link #primary}
     * when no workspace claims it.
     */
    SlackNotifier notifierFor(String workstreamId) {
        if (workstreamId != null && !byWorkspace.isEmpty()) {
            for (SlackNotifier n : byWorkspace.values()) {
                if (n.getWorkstream(workstreamId) != null) {
                    return n;
                }
            }
        }
        return primary;
    }

    /**
     * Returns the workspace ID owning the given workstream, or {@code null}
     * if no workspace claims it.
     */
    String workspaceIdFor(String workstreamId) {
        if (workstreamId == null) return null;
        for (Map.Entry<String, SlackNotifier> e : byWorkspace.entrySet()) {
            if (e.getValue().getWorkstream(workstreamId) != null) {
                return e.getKey();
            }
        }
        return null;
    }

    /**
     * Returns the notifier for the given workspace ID, or {@link #primary}
     * when the ID is {@code null} or unknown.
     */
    SlackNotifier notifierForWorkspace(String workspaceId) {
        if (workspaceId != null && byWorkspace.containsKey(workspaceId)) {
            return byWorkspace.get(workspaceId);
        }
        return primary;
    }

    /**
     * Returns every workstream across every workspace, merged into a single
     * map keyed by workstream ID.
     */
    Map<String, Workstream> allWorkstreams() {
        if (byWorkspace.isEmpty()) {
            return primary != null ? primary.getWorkstreams() : new LinkedHashMap<>();
        }
        Map<String, Workstream> merged = new LinkedHashMap<>();
        for (SlackNotifier n : byWorkspace.values()) {
            merged.putAll(n.getWorkstreams());
        }
        return merged;
    }

    /**
     * Returns the workstream ID that owns the given job, searching all
     * notifiers. Returns {@code null} if no workstream claims this job.
     */
    String findWorkstreamIdForJob(String jobId) {
        if (jobId == null) return null;
        if (!byWorkspace.isEmpty()) {
            for (SlackNotifier n : byWorkspace.values()) {
                String wsId = n.getWorkstreamIdForJob(jobId);
                if (wsId != null) return wsId;
            }
            return null;
        }
        return primary != null ? primary.getWorkstreamIdForJob(jobId) : null;
    }

    /**
     * Searches every workspace for the given job ID; returns the first match
     * or {@code null}.
     */
    JobCompletionEvent findJob(String jobId) {
        if (!byWorkspace.isEmpty()) {
            for (SlackNotifier n : byWorkspace.values()) {
                JobCompletionEvent ev = n.getJob(jobId);
                if (ev != null) return ev;
            }
            return null;
        }
        return primary != null ? primary.getJob(jobId) : null;
    }

    /**
     * Searches every workspace for a workstream whose default branch matches
     * the given branch; returns the first match or {@code null}.
     */
    Workstream findByBranch(String branch) {
        if (!byWorkspace.isEmpty()) {
            for (SlackNotifier n : byWorkspace.values()) {
                Workstream w = n.findWorkstreamByBranch(branch);
                if (w != null) return w;
            }
            return null;
        }
        return primary != null ? primary.findWorkstreamByBranch(branch) : null;
    }

    /**
     * Searches every workspace for a workstream matching both branch and
     * repoUrl; returns the first match or {@code null}.
     */
    Workstream findByBranchAndRepo(String branch, String repoUrl) {
        if (!byWorkspace.isEmpty()) {
            for (SlackNotifier n : byWorkspace.values()) {
                Workstream w = n.findWorkstreamByBranchAndRepo(branch, repoUrl);
                if (w != null) return w;
            }
            return null;
        }
        return primary != null ? primary.findWorkstreamByBranchAndRepo(branch, repoUrl) : null;
    }
}
