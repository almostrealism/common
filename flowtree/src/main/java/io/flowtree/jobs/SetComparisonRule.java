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
 * Abstract base for enforcement rules that use set-snapshot comparison to
 * determine when to stop looping.
 *
 * <p>Before each correction session, {@link #isViolated} records the current
 * set of items returned by {@link #extractItems}. After the session completes,
 * {@link #onCorrectionAttempted} compares the post-session set with the
 * pre-session snapshot. If the sets are identical, the agent made no changes
 * during the correction session and the rule marks itself resolved so the loop
 * exits on the next {@link #isViolated} check. If the set changed, another pass
 * is made to verify the remaining items.</p>
 *
 * @author Michael Murray
 * @see DeduplicationRule
 * @see OrganizationalPlacementRule
 * @see EnforcementRule
 */
abstract class SetComparisonRule implements EnforcementRule {

    /**
     * The set of items recorded by the most recent {@link #isViolated} call.
     * Used by {@link #onCorrectionAttempted} to detect whether the agent made
     * any changes during the correction session.
     */
    private Set<String> snapshot = null;

    /**
     * Set to {@code true} by {@link #onCorrectionAttempted} when a correction
     * session completes without changing the item set, indicating no further
     * action is required. Once resolved, {@link #isViolated} returns
     * {@code false} immediately so the loop exits.
     */
    private boolean resolved = false;

    /**
     * Returns the current list of items to track for this rule.
     *
     * <p>Called by {@link #isViolated} and {@link #onCorrectionAttempted}.
     * The returned list must reflect the current on-disk state of the working
     * directory, not a cached value.</p>
     *
     * @param job the job whose working-tree state is being inspected
     * @return the current list of relevant items; must not be {@code null}
     */
    protected abstract List<String> extractItems(ClaudeCodeJob job);

    @Override
    public boolean isViolated(ClaudeCodeJob job) {
        if (resolved) return false;
        List<String> items = extractItems(job);
        snapshot = new LinkedHashSet<>(items);
        return !items.isEmpty();
    }

    /**
     * Compares the post-session item set against the pre-session snapshot. If
     * the sets are equal the agent changed nothing during the correction session
     * and the rule marks itself resolved so the loop exits on the next
     * {@link #isViolated} check.
     */
    @Override
    public void onCorrectionAttempted(ClaudeCodeJob job) {
        if (snapshot == null) return;
        Set<String> current = new LinkedHashSet<>(extractItems(job));
        if (current.equals(snapshot)) {
            resolved = true;
        }
    }
}
