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

package io.flowtree.github;

import java.util.List;

/**
 * The outcome of choosing which runner a job should use: the {@code runs-on}
 * label list to emit, and why.
 *
 * <p>{@link #choose(String, List, RunnerListing)} walks a preference order of
 * {@link RunnerFleet fleets} and returns the first that has an online, idle
 * runner carrying both the fleet's label and the job's required label. When no
 * preferred fleet has such a runner — or when the runner list could not be read
 * at all (a missing permission, or GitHub unreachable) — it falls back to the
 * GitHub-hosted {@code ubuntu-latest}. The fallback always carries a
 * {@link #reason}, and {@link #listed} records whether the decision was made
 * against a real runner list or against none, so a permission failure is never
 * silently indistinguishable from "every fleet is busy".</p>
 *
 * @param runsOn the {@code runs-on} label list the job should use
 * @param fleet  the capability label of the chosen fleet, or {@code null} when hosted
 * @param hosted whether the job falls back to the GitHub-hosted runner
 * @param listed whether the decision was made against a successfully-read runner list
 * @param reason a human-readable explanation of the choice
 *
 * @author  Michael Murray
 */
public record RunnerSelection(List<String> runsOn, String fleet, boolean hosted,
                              boolean listed, String reason) {

    /** The hosted fallback's {@code runs-on} list. */
    private static final List<String> HOSTED = List.of("ubuntu-latest");

    /**
     * Chooses a runner for a job that requires {@code requiredLabel}, preferring
     * the fleets in {@code preference} order.
     *
     * @param requiredLabel the label every candidate runner must carry (e.g. {@code ar-build})
     * @param preference    the fleets to consider, in order of preference
     * @param listing       the organization's runner list
     * @return the selection
     */
    public static RunnerSelection choose(String requiredLabel, List<RunnerFleet> preference,
                                         RunnerListing listing) {
        if (!listing.ok()) {
            return hosted(false, listing.error());
        }
        for (RunnerFleet candidate : preference) {
            for (GitHubRunner runner : listing.runners()) {
                // Match the fleet's whole runs-on, not just its capability label,
                // so a macOS runner carrying ar-ci does not match the Linux ar-ci
                // fleet (it has no linux label).
                if (runner.idle() && runner.hasLabels(candidate.runsOn())
                        && runner.hasLabel(requiredLabel)) {
                    return new RunnerSelection(candidate.runsOnWith(requiredLabel),
                            candidate.label(), false, true,
                            "idle runner " + runner.name() + " in fleet " + candidate.label());
                }
            }
        }
        return hosted(true, "no online, idle runner carries " + requiredLabel
                + " in any preferred fleet");
    }

    /**
     * Builds a hosted-fallback selection.
     *
     * @param listed whether a runner list was successfully read
     * @param reason why the job falls back to the hosted runner
     * @return the hosted selection
     */
    private static RunnerSelection hosted(boolean listed, String reason) {
        return new RunnerSelection(HOSTED, null, true, listed, reason);
    }
}
