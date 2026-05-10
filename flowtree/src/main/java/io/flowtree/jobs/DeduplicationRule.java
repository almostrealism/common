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
 * @see ClaudeCodeJob#buildDeduplicationPrompt(List, boolean, int)
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
        return ClaudeCodeJob.buildDeduplicationPrompt(capped,
                newMethods.size() > ClaudeCodeJob.MAX_DEDUP_METHODS, newMethods.size());
    }
}
