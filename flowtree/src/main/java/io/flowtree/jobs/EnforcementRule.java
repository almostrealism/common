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

/**
 * Defines a rule that is evaluated after an agent completes its primary work.
 * When a violation is detected, a correction session is run using the rule's
 * prompt, and the check is repeated until the violation is resolved or the
 * maximum retry count is exhausted.
 *
 * <p>Rules are evaluated in order by the enforcement framework in
 * {@link ClaudeCodeJob}. Each rule is independent: a correction session
 * for one rule does not prevent other rules from being checked or retried.</p>
 *
 * <p>Implementations should be stateless — all inspection is performed
 * through the {@link ClaudeCodeJob} argument passed to each method.</p>
 *
 * <p>To add a new rule, implement this interface and add it via
 * {@link ClaudeCodeJob#addEnforcementRule(EnforcementRule)}. Built-in rules
 * (enforce-changes, deduplication, maven-dependency-protection) are activated
 * by flags on the job or factory and do not need to be added manually.</p>
 *
 * @author Michael Murray
 * @see ClaudeCodeJob
 * @see ClaudeCodeJob#DEFAULT_MAX_RULE_RETRIES
 */
public interface EnforcementRule {

    /**
     * Returns a short name for this rule, used in log messages.
     *
     * @return the rule name (e.g., {@code "enforce-changes"},
     *         {@code "no-maven-dependency-changes"})
     */
    String getName();

    /**
     * Returns {@code true} if this rule is currently violated by the agent's work.
     *
     * <p>This method is called before the first correction attempt and after each
     * subsequent attempt. Return {@code false} to signal that the violation has
     * been resolved and no further correction is needed.</p>
     *
     * @param job the job whose current working-tree state is being inspected
     * @return {@code true} if a violation was detected
     */
    boolean isViolated(ClaudeCodeJob job);

    /**
     * Builds the correction prompt to send to the agent when a violation is detected.
     *
     * <p>The prompt should clearly describe the violation and provide specific
     * instructions for resolving it. The enforcement framework runs the agent with
     * this prompt as a temporary replacement for the job's primary prompt.</p>
     *
     * <p>Return {@code null} to re-run the agent with the existing job prompt
     * unchanged. Use this when the job's built-in instruction context already
     * contains the correction guidance (e.g., when {@code enforceChanges} is
     * enabled, {@link ClaudeCodeJob}'s instruction builder already injects the
     * "code changes are required" message).</p>
     *
     * @param job the job whose current state is being inspected
     * @return the correction prompt, or {@code null} to reuse the existing job prompt
     */
    String buildCorrectionPrompt(ClaudeCodeJob job);

    /**
     * Returns the maximum number of correction attempts before the rule gives up.
     *
     * <p>When the attempt limit is reached and the violation is still present,
     * the framework logs a warning and moves on to the next rule.</p>
     *
     * <p>Defaults to {@link ClaudeCodeJob#DEFAULT_MAX_RULE_RETRIES}.</p>
     *
     * @return the maximum retry count (must be positive)
     */
    default int getMaxRetries() {
        return ClaudeCodeJob.DEFAULT_MAX_RULE_RETRIES;
    }
}
