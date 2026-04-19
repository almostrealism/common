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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Enforcement rule that detects new Java methods introduced since the base
 * branch and runs a deduplication session to remove any that duplicate
 * existing functionality. Active when {@link ClaudeCodeJob#getDeduplicationMode()}
 * is {@link ClaudeCodeJob#DEDUP_LOCAL}.
 *
 * <p>This rule uses method-set comparison to determine when to stop looping.
 * Before each correction session, {@link #isViolated(ClaudeCodeJob)} records
 * the current set of new method names. After the session completes,
 * {@link #onCorrectionAttempted(ClaudeCodeJob)} compares the post-session
 * set with the recorded pre-session set. If the sets are identical, the agent
 * had one opportunity and removed nothing — the rule marks itself as resolved
 * so the loop exits on the next {@link #isViolated} check. If the set changed
 * (methods were removed), another pass is made to check for remaining
 * duplicates. This approach is deterministic and does not rely on heuristics
 * about file changes or agent output.</p>
 *
 * @author Michael Murray
 * @see ClaudeCodeJob#extractNewMethodNames()
 * @see ClaudeCodeJob#buildDeduplicationPrompt(List, boolean, int)
 * @see EnforcementRule
 */
class DeduplicationRule implements EnforcementRule {

    /**
     * The set of new method names recorded by the most recent {@link #isViolated}
     * call. Used by {@link #onCorrectionAttempted} to compare against the
     * post-session set and determine whether the agent removed any methods.
     */
    private Set<String> methodSetBeforeSession = null;

    /**
     * Set to {@code true} by {@link #onCorrectionAttempted} when a correction
     * session completes without changing the method set, indicating the agent
     * found no duplicates to remove. Once resolved, {@link #isViolated} returns
     * {@code false} immediately so the loop exits.
     */
    private boolean resolved = false;

    @Override
    public String getName() { return "deduplication"; }

    @Override
    public boolean isViolated(ClaudeCodeJob job) {
        if (resolved) return false;
        List<String> newMethods = job.extractNewMethodNames();
        methodSetBeforeSession = new LinkedHashSet<>(newMethods);
        if (!newMethods.isEmpty()) {
            job.log("Deduplication scan: found " + newMethods.size() + " new method(s)");
        }
        return !newMethods.isEmpty();
    }

    /**
     * Compares the post-session method set against the pre-session snapshot
     * recorded by {@link #isViolated}. If the sets are equal, the agent
     * removed nothing during the correction session and the rule marks itself
     * resolved so the loop exits on the next {@link #isViolated} check.
     *
     * <p><b>Performance note:</b> this method calls
     * {@link ClaudeCodeJob#extractNewMethodNames()}, which spawns
     * {@code git diff} and {@code git ls-files} processes. Combined with the
     * calls already made in {@link #isViolated} and
     * {@link #buildCorrectionPrompt}, each correction attempt currently
     * performs three separate scans. A future improvement would be to cache
     * the pre-session result from {@code isViolated} in a {@code List<String>}
     * field and reuse it in {@code buildCorrectionPrompt}, reducing each
     * attempt to one pre-session scan and one post-session scan.</p>
     */
    @Override
    public void onCorrectionAttempted(ClaudeCodeJob job) {
        if (methodSetBeforeSession == null) return;
        Set<String> currentMethodSet = new LinkedHashSet<>(job.extractNewMethodNames());
        if (currentMethodSet.equals(methodSetBeforeSession)) {
            job.log("Deduplication: method set unchanged after correction session; stopping deduplication loop");
            resolved = true;
        }
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
