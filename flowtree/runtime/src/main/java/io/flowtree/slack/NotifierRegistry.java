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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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
        return Collections.unmodifiableMap(byWorkspace);
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
     * Returns the job IDs of any active ({@link JobCompletionEvent.Status#STARTED})
     * jobs on the given workstream, searching across every notifier.
     *
     * @param workstreamId the workstream identifier
     * @return a (possibly empty) list of active job IDs
     */
    List<String> getActiveJobIds(String workstreamId) {
        SlackNotifier owner = notifierFor(workstreamId);
        return owner != null ? owner.getActiveJobIds(workstreamId) : new ArrayList<>();
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
     *
     * <p>This lookup is ambiguous when two workstreams (typically on
     * different repositories) share a {@code defaultBranch}. Callers that
     * cannot tolerate cross-routing should use
     * {@link #findAllByBranch(String)} instead and disambiguate explicitly,
     * either by passing a {@code repoUrl} to
     * {@link #findByBranchAndRepo(String, String)} or by rejecting the
     * request when more than one match is returned.</p>
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
     * Outcome of {@link #resolveBranch(String, String)}: either a single
     * matched workstream, an error message describing why no unambiguous
     * match could be made, or both fields {@code null} when the branch
     * matches no workstream at all.
     */
    static final class BranchResolution {
        /** The matched workstream, or {@code null} when ambiguous or missing. */
        private final Workstream match;
        /** Human-readable error description when the lookup is ambiguous. */
        private final String error;

        /**
         * Creates a resolution result. Exactly one of {@code match} and
         * {@code error} should be non-null; both null means "no match".
         *
         * @param match the resolved workstream, or {@code null}
         * @param error the ambiguity error message, or {@code null}
         */
        private BranchResolution(Workstream match, String error) {
            this.match = match;
            this.error = error;
        }

        /** Returns the matched workstream, or {@code null} when none. */
        Workstream match() { return match; }
        /** Returns the ambiguity error message, or {@code null} when none. */
        String error() { return error; }

        /** Returns a "no match" resolution. */
        static BranchResolution none() { return new BranchResolution(null, null); }
        /** Returns a successful resolution carrying {@code w}. */
        static BranchResolution of(Workstream w) { return new BranchResolution(w, null); }
        /** Returns an ambiguous resolution describing why in {@code msg}. */
        static BranchResolution failed(String msg) { return new BranchResolution(null, msg); }
    }

    /**
     * Resolves a workstream by branch (and optional repoUrl), returning a
     * {@link BranchResolution} that the caller can convert into either a
     * routing decision or an error response.
     *
     * <p>When {@code repoUrl} is supplied the lookup is unambiguous: the
     * unique workstream registered for {@code (branch, repoUrl)} is
     * returned, or {@code none()} when nothing matches. When {@code repoUrl}
     * is absent the lookup falls back to branch-only matching but rejects
     * ambiguous cases — two workstreams sharing a branch across different
     * repositories must not be cross-routed silently.</p>
     *
     * @param branch  the requested target branch
     * @param repoUrl the repository URL used to disambiguate (may be {@code null})
     * @return a {@link BranchResolution} describing the outcome
     */
    BranchResolution resolveBranch(String branch, String repoUrl) {
        if (branch == null || branch.isEmpty()) {
            return BranchResolution.none();
        }
        if (repoUrl != null && !repoUrl.isEmpty()) {
            Workstream m = findByBranchAndRepo(branch, repoUrl);
            return m != null ? BranchResolution.of(m) : BranchResolution.none();
        }
        List<Workstream> matches = findAllByBranch(branch);
        if (matches.isEmpty()) return BranchResolution.none();
        if (matches.size() == 1) return BranchResolution.of(matches.get(0));
        StringBuilder repos = new StringBuilder();
        for (Workstream m : matches) {
            if (repos.length() > 0) repos.append(", ");
            String r = m.getRepoUrl();
            repos.append(r != null && !r.isEmpty() ? r : "(no repoUrl)");
        }
        return BranchResolution.failed("Ambiguous workstream resolution: " + matches.size()
            + " workstreams share defaultBranch '" + branch
            + "' on different repositories (" + repos + "). Supply repoUrl in the"
            + " request body to disambiguate, or pass workstreamId explicitly.");
    }

    /**
     * Returns every workstream whose default branch matches the given branch,
     * across every workspace. Useful for detecting branch-name collisions
     * between workstreams on different repositories before routing a job
     * submission.
     *
     * @param branch the branch name to match
     * @return all matching workstreams (never {@code null})
     */
    List<Workstream> findAllByBranch(String branch) {
        List<Workstream> matches = new ArrayList<>();
        if (branch == null || branch.isEmpty()) {
            return matches;
        }
        if (!byWorkspace.isEmpty()) {
            for (SlackNotifier n : byWorkspace.values()) {
                matches.addAll(n.findWorkstreamsByBranch(branch));
            }
        } else if (primary != null) {
            matches.addAll(primary.findWorkstreamsByBranch(branch));
        }
        return matches;
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
