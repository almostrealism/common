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

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import io.flowtree.workstream.Workstream;

/**
 * Classifies a workstream's lifecycle state for archival triage.
 *
 * <p>The classifier answers "is this workstream done?" from the workstream's
 * persisted metadata ({@code kind}, {@code defaultBranch}, {@code baseBranch}),
 * the GitHub pull-request data fetched by
 * {@link WorkstreamListing#warmPrCache}, and the persisted timestamp of the
 * workstream's most recent job. It deliberately does not query
 * {@link io.flowtree.controller.JobStatsStore} itself — the caller reads
 * {@code lastJobAt} and passes it in, so the classifier and
 * {@code WorkstreamListing.statusFields}'s own {@code lastJobAt} field always
 * agree on the same value.</p>
 *
 * <p>Order of precedence:</p>
 * <ol>
 *   <li>{@code kind == "standing"} or {@code kind == "orchestrator"} — return
 *       {@code "standing"} / {@code "orchestrator"} verbatim. A standing
 *       workstream is never eligible for archival, so the classifier
 *       short-circuits regardless of PR or job data.</li>
 *   <li>An open pull request for {@code defaultBranch} → {@code "active"}.</li>
 *   <li>{@code lastJobAt} falls within the idle window → {@code "active"},
 *       even with no open PR: recent work is active regardless of PR
 *       history.</li>
 *   <li>The most recent PR is merged → {@code "merged"}.</li>
 *   <li>The most recent PR is closed but unmerged → {@code "abandoned"}.</li>
 *   <li>No PR for the branch → {@code "idle"}.</li>
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
     * Classifies a workstream using the supplied inputs. {@code branchPrs} is
     * the list of GitHub pull requests the listing's PR cache resolved for
     * {@code defaultBranch}; {@code null} when the cache had no entry for the
     * workstream's repository, as distinct from an empty list, which means
     * the repository resolved but has no PRs for this branch.
     * {@code lastJobAt} is the persisted timestamp of the workstream's most
     * recent job (from {@code job_timing.completed_at}, or
     * {@code started_at} for a still-running job); {@code null} when no job
     * has ever run or no job store is configured.
     *
     * @param ws         the workstream to classify
     * @param branchPrs  the PRs for {@code defaultBranch}, or {@code null}
     * @param idleDays   the idle window in days
     * @param lastJobAt  the workstream's most recent job timestamp, or
     *                   {@code null}
     * @return the classification result
     */
    static Classification classify(Workstream ws, List<JsonNode> branchPrs, int idleDays,
                                    Instant lastJobAt) {
        String kind = ws.getKind();
        if ("standing".equals(kind) || "orchestrator".equals(kind)) {
            return new Classification(kind,
                    "kind=" + kind + " — classifier defers to explicit workstream classification");
        }

        boolean hasOpenPr = false;
        boolean hasMergedPr = false;
        boolean hasClosedUnmergedPr = false;
        Integer openPrNumber = null;
        Integer mergedPrNumber = null;
        Integer closedPrNumber = null;
        if (branchPrs != null) {
            for (JsonNode pr : branchPrs) {
                String state = pr.path("state").asText("");
                boolean merged = !pr.path("merged_at").isMissingNode()
                        && !pr.path("merged_at").isNull()
                        && !pr.path("merged_at").asText().isEmpty();
                if (merged) {
                    hasMergedPr = true;
                    if (mergedPrNumber == null) mergedPrNumber = pr.path("number").asInt();
                } else if ("open".equals(state)) {
                    hasOpenPr = true;
                    if (openPrNumber == null) openPrNumber = pr.path("number").asInt();
                } else if ("closed".equals(state)) {
                    hasClosedUnmergedPr = true;
                    if (closedPrNumber == null) closedPrNumber = pr.path("number").asInt();
                }
            }
        }

        // Open PR is the strongest "still active" signal — archive triage
        // must never propose archiving a branch with a PR outstanding.
        if (hasOpenPr) {
            return new Classification("active",
                    "PR #" + openPrNumber + " is open on " + ws.getDefaultBranch());
        }

        boolean jobRecentlyActive = lastJobAt != null
                && lastJobAt.isAfter(Instant.now().minus(Duration.ofDays(idleDays)));
        if (jobRecentlyActive) {
            return new Classification("active",
                    "a job completed on " + lastJobAt + ", within the last " + idleDays + " days");
        }

        if (hasMergedPr) {
            return new Classification("merged",
                    "PR #" + mergedPrNumber + " merged on " + ws.getDefaultBranch());
        }
        if (hasClosedUnmergedPr) {
            return new Classification("abandoned",
                    "PR #" + closedPrNumber + " closed without merging on "
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