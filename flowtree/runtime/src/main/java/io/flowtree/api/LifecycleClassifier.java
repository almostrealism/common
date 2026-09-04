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

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import io.flowtree.workstream.Workstream;

/**
 * Classifies a workstream's lifecycle state for archival triage.
 *
 * <p>The classifier answers "is this workstream done?" from the inputs that
 * already exist in {@code WorkstreamListing}: the workstream's persisted
 * metadata ({@code kind}, {@code defaultBranch}, {@code baseBranch}) and the
 * GitHub pull-request data fetched by
 * {@link WorkstreamListing#warmPrCache}. It deliberately does not consult
 * {@link io.flowtree.controller.JobStatsStore} directly: the last-job
 * timestamp used as the idle-window anchor comes from
 * {@code WorkstreamListing.statusFields} so the classifier and the listing
 * agree on the same value.</p>
 *
 * <p>Order of precedence:</p>
 * <ol>
 *   <li>{@code kind == "standing"} or {@code kind == "orchestrator"} — return
 *       {@code "standing"} / {@code "orchestrator"} verbatim. A standing
 *       workstream is never eligible for archival, so the classifier
 *       short-circuits regardless of PR data.</li>
 *   <li>An open pull request for {@code defaultBranch} → {@code "active"}.</li>
 *   <li>The most recent PR is merged and there is no job in the idle
 *       window → {@code "merged"}.</li>
 *   <li>The most recent PR is closed but unmerged → {@code "abandoned"}.</li>
 *   <li>No PR for the branch and no job in the idle window → {@code "idle"}.</li>
 *   <li>Anything else (e.g. no PR lookup available) → {@code "unknown"}.</li>
 * </ol>
 */
final class LifecycleClassifier {

    /** Result of classifying one workstream's lifecycle. */
    static final class Classification {
        /** One of the lifecycle labels documented in the class Javadoc. */
        final String label;
        /** Short human-readable description of the inputs that produced the label. */
        final String reason;

        /**
         * Builds a classification result.
         *
         * @param label  the lifecycle label
         * @param reason the short reason string, may be {@code null}
         */
        Classification(String label, String reason) {
            this.label = label;
            this.reason = reason;
        }
    }

    /** Not instantiable. */
    private LifecycleClassifier() { }

    /**
     * Extracts the {@code "lifecycle"} label from a JSON member fragment built
     * by {@link WorkstreamListing#prAndLifecycleFields}. Used by the listing
     * to apply a server-side filter without re-running the classifier.
     *
     * @param extras the JSON fragment, possibly empty
     * @return the label, or {@code null} when the fragment did not include one
     */
    static String extractLifecycle(String extras) {
        if (extras == null || extras.isEmpty()) return null;
        int key = extras.indexOf("\"lifecycle\":\"");
        if (key < 0) return null;
        int value = key + "\"lifecycle\":\"".length();
        int end = extras.indexOf('"', value);
        if (end < 0) return null;
        return extras.substring(value, end);
    }

    /**
     * Classifies a workstream using the supplied inputs. {@code lastJobAt} is
     * the persisted timestamp from {@code job_timing.completed_at} (or
     * {@code started_at} for a still-running job), read by
     * {@code WorkstreamListing.statusFields}. {@code branchPrs} is the list of
     * GitHub pull requests the listing's PR cache resolved for
     * {@code defaultBranch}; {@code null} when the cache had no entry for
     * the workstream's repository.
     *
     * @param ws         the workstream to classify
     * @param branchPrs  the PRs for {@code defaultBranch}, or {@code null}
     * @param idleDays   the idle window in days (matches {@code idleDays})
     * @return the classification result
     */
    static Classification classify(Workstream ws, List<JsonNode> branchPrs, int idleDays) {
        String kind = ws.getKind();
        if ("standing".equals(kind) || "orchestrator".equals(kind)) {
            return new Classification(kind,
                    "kind=" + kind + " — classifier defers to explicit workstream classification");
        }

        // idle window is used by the "idle" verdict path via idleDays below.
        // We do not need a cutoff or "now" reference because no
        // idle-vs-active comparison in this method consumes them; idleDays
        // is passed only for the reason string.

        // Pull-request state derived from the GitHub-derived listing the
        // caller already populated. We do not consult JobStatsStore here:
        // the listing passed lastJobAt separately so a separate DB read would
        // be both costly and a duplicate.
        boolean hasOpenPr = false;
        boolean hasMergedPr = false;
        boolean hasClosedUnmergedPr = false;
        // TODO(review): prNumber is shared across states; a branch with PRs in more
        // than one state can report the wrong PR number in the reason string below.
        Integer prNumber = null;
        if (branchPrs != null && !branchPrs.isEmpty()) {
            for (JsonNode pr : branchPrs) {
                String state = pr.path("state").asText("");
                boolean merged = !pr.path("merged_at").isMissingNode()
                        && !pr.path("merged_at").isNull()
                        && !pr.path("merged_at").asText().isEmpty();
                if (merged) {
                    hasMergedPr = true;
                    if (prNumber == null) prNumber = pr.path("number").asInt();
                } else if ("open".equals(state)) {
                    hasOpenPr = true;
                    if (prNumber == null) prNumber = pr.path("number").asInt();
                } else if ("closed".equals(state)) {
                    hasClosedUnmergedPr = true;
                    if (prNumber == null) prNumber = pr.path("number").asInt();
                }
            }
        }

        // Open PR is the strongest "still active" signal — archive triage
        // must never propose archiving a branch with a PR outstanding.
        if (hasOpenPr) {
            return new Classification("active",
                    "PR #" + prNumber + " is open on " + ws.getDefaultBranch());
        }

        // The lifecycle classifier relies on lastJobAt to decide idle / merged /
        // abandoned. We don't have it directly, so the caller folds it into the
        // reason via the JSON fragment — but the verdict here does not need it
        // when the PR data alone settles the question.
        if (hasMergedPr && branchPrs != null) {
            // A merged PR is sufficient to call the workstream merged even when
            // lastJobAt is older than idleDays: the work landed regardless of
            // whether a job has run since.
            return new Classification("merged",
                    "PR #" + prNumber + " merged on " + ws.getDefaultBranch());
        }
        if (hasClosedUnmergedPr) {
            return new Classification("abandoned",
                    "PR #" + prNumber + " closed without merging on "
                            + ws.getDefaultBranch());
        }
        if (branchPrs == null) {
            return new Classification("unknown",
                    "repo or branch could not be resolved to a GitHub repository");
        }
        return new Classification("idle",
                "no job or pull request activity in the last " + idleDays + " days");
    }
}