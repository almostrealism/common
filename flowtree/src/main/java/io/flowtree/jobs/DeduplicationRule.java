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

package io.flowtree.jobs;

import java.util.List;

/**
 * Enforcement rule that detects new Java methods introduced since the base
 * branch and runs a deduplication session to remove any that duplicate
 * existing functionality. Active when {@link ClaudeCodeJob#getDeduplicationMode()}
 * is {@link ClaudeCodeJob#DEDUP_LOCAL}.
 *
 * <p>Uses set-snapshot comparison (inherited from {@link SetComparisonRule}) to
 * determine when to stop looping: after a correction session that produces no
 * change in the new-method set, the rule marks itself resolved and exits.</p>
 *
 * <p>A per-job pass cap ({@link #maxPasses}) provides a cost ceiling independent
 * of the set-comparison exit condition. The cap is enforced via
 * {@link #getMaxRetries()}, which the enforcement framework uses to bound the
 * correction loop. When the cap is reached and duplicates still exist, the
 * framework logs a warning (via {@link ClaudeCodeJob#warn(String)}) and moves on.
 * The default cap is {@link ClaudeCodeJob#DEFAULT_MAX_DEDUP_PASSES}.</p>
 *
 * @author Michael Murray
 * @see ClaudeCodeJob#extractNewMethodNames()
 * @see DeduplicationRule#buildDeduplicationPrompt(List, boolean, int)
 * @see SetComparisonRule
 * @see EnforcementRule
 */
class DeduplicationRule extends SetComparisonRule {

    /** Maximum number of correction sessions allowed per job. */
    private final int maxPasses;

    /** Creates a rule with the default pass cap ({@link ClaudeCodeJob#DEFAULT_MAX_DEDUP_PASSES}). */
    DeduplicationRule() {
        this(ClaudeCodeJob.DEFAULT_MAX_DEDUP_PASSES);
    }

    /**
     * Creates a rule with an explicit pass cap.
     *
     * @param maxPasses maximum deduplication sessions before the enforcement
     *                  framework moves on; must be positive
     * @throws IllegalArgumentException if {@code maxPasses} is not positive
     */
    DeduplicationRule(int maxPasses) {
        if (maxPasses <= 0) {
            throw new IllegalArgumentException(
                    "maxPasses must be positive, got: " + maxPasses);
        }
        this.maxPasses = maxPasses;
    }

    @Override
    public String getName() { return "deduplication"; }

    /**
     * Returns the pass cap so the enforcement framework bounds the correction loop.
     * When exhausted while duplicates remain, the framework logs a warning and continues.
     */
    @Override
    public int getMaxRetries() { return maxPasses; }

    @Override
    protected List<String> extractItems(ClaudeCodeJob job) {
        return job.extractNewMethodNames();
    }

    @Override
    public String buildCorrectionPrompt(ClaudeCodeJob job) {
        List<String> newMethods = job.extractNewMethodNames();
        List<String> capped = newMethods.size() > ClaudeCodeJob.MAX_DEDUP_METHODS
                ? newMethods.subList(0, ClaudeCodeJob.MAX_DEDUP_METHODS) : newMethods;
        return buildDeduplicationPrompt(capped,
                newMethods.size() > ClaudeCodeJob.MAX_DEDUP_METHODS, newMethods.size());
    }

    /**
     * Builds the aggressive deduplication prompt sent to the follow-up agent session.
     *
     * @param methodNames the (possibly capped) list of new method names
     * @param truncated   {@code true} if the list was capped due to size
     * @param totalCount  the total number of methods found (before capping)
     * @return the full prompt string
     */
    static String buildDeduplicationPrompt(List<String> methodNames,
                                           boolean truncated,
                                           int totalCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("DEDUPLICATION AUDIT — MANDATORY PRE-COMMIT REVIEW\n\n");
        sb.append("A prior agent session has committed changes that introduce the ");
        sb.append("following new methods");
        if (truncated) {
            sb.append(" (showing ").append(methodNames.size())
              .append(" of ").append(totalCount).append(" total)");
        }
        sb.append(":\n\n");
        for (String name : methodNames) {
            sb.append("  - ").append(name).append("\n");
        }
        sb.append("\n");
        sb.append("Your job is to determine whether any of these methods duplicate ");
        sb.append("functionality that already exists elsewhere in the codebase. ");
        sb.append("This is a mandatory review step — do not skip it and do not ");
        sb.append("conclude quickly that a method is unique without actually searching.\n\n");
        sb.append("CRITICAL ASSUMPTION: For every method in the list above, you MUST ");
        sb.append("assume it is a clone of an existing method until you have proven ");
        sb.append("otherwise. This is not a pessimistic assumption — it is statistically ");
        sb.append("accurate. The majority of methods introduced by agent sessions are ");
        sb.append("duplicates of functionality that already exists elsewhere. The agent ");
        sb.append("re-implemented things it could not find by search. The clone may not ");
        sb.append("be an exact copy: it may be renamed, slightly generalised, or placed ");
        sb.append("in a different class — but it performs the same operation on the same ");
        sb.append("data.\n\n");
        sb.append("For each method:\n");
        sb.append("1. Search the codebase for methods that perform the same logical ");
        sb.append("operation. Use Grep to search by keyword, not just by name.\n");
        sb.append("2. If a duplicate exists: remove the new method entirely and replace ");
        sb.append("all call sites with the existing method.\n");
        sb.append("3. Only after a thorough search may you conclude a method is ");
        sb.append("genuinely new.\n\n");
        sb.append("IMPORTANT — editing rules:\n");
        sb.append("- Use the Edit tool to remove duplicate methods surgically. ");
        sb.append("Remove only the duplicate method body and its declaration; ");
        sb.append("preserve all other changes in the file.\n");
        sb.append("- NEVER use git restore, git checkout --, git reset, or any ");
        sb.append("other git command to revert a file. Those commands discard ALL ");
        sb.append("changes in that file, not just the duplicate method, and will ");
        sb.append("destroy work that must be preserved.\n\n");
        sb.append("Do not rationalise keeping a duplicate because it is 'slightly ");
        sb.append("different'. Slight differences are how duplicates hide. If the ");
        sb.append("logical purpose is the same, merge them. The codebase already has ");
        sb.append("too many near-identical copies of the same logic; every one you ");
        sb.append("remove improves maintainability for every future session.");
        return sb.toString();
    }
}
