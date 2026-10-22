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
 * Enforcement rule that verifies the agent produced at least one file change
 * that would actually survive staging. Used when
 * {@link CodingAgentJob#isEnforceChanges()} is {@code true}.
 *
 * <p>Evaluated against {@link GitManagedJob#previewStaging()} rather than raw
 * working-tree dirtiness: a file that {@link FileStager}'s guardrails would
 * reject is not a real change for this rule's purposes, even though it still
 * shows up as "uncommitted" in {@code git status}. Checking the raw working
 * tree let a run report satisfied when every changed file was actually
 * doomed to be dropped by a protection guardrail — the agent's fix never
 * reached the branch, but nothing here noticed.</p>
 *
 * <p>{@link #buildCorrectionPrompt} returns {@code null} so the framework
 * re-runs the agent with the existing prompt; the {@code enforceChanges}
 * flag already injects a "code changes are required" message via
 * {@link InstructionPromptBuilder}. That re-run is a restart of the
 * {@code PRIMARY} phase on the primary runner, never a phase of its own (which
 * is why {@code Phase.ENFORCE_CHANGES} is deprecated): a stronger model must not
 * quietly do the work a weaker primary declined to.</p>
 *
 * <p>The re-run cap ({@link CodingAgentJob#getMaxEnforceChangesRetries()}) is a
 * budget for the whole job, not for one enforcement pass. {@link EnforcementRunner}
 * re-enters a rule that exhausted its per-pass cap whenever another rule's
 * correction ran, so a per-pass cap would let the agent be re-run that many
 * times per pass. This rule counts its own re-runs and reports only the unspent
 * remainder from {@link #getMaxRetries()}. A job whose budget is spent with
 * nothing committed is reported DEGRADED by {@code CodingAgentJobEvent}.</p>
 */
class EnforceChangesRule implements EnforcementRule {
    /** Total re-runs this rule may launch over the job; 0 means never. */
    private final int maxRetries;

    /** Re-runs launched so far. */
    private int retriesRun;

    /** Creates the rule with the default budget, {@link CodingAgentJob#DEFAULT_MAX_RULE_RETRIES}. */
    EnforceChangesRule() {
        this(CodingAgentJob.DEFAULT_MAX_RULE_RETRIES);
    }

    /**
     * Creates the rule with a whole-job re-run budget.
     *
     * @param maxRetries how many times the agent may be re-run; 0 means never
     */
    EnforceChangesRule(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    @Override
    public String getName() { return "enforce-changes"; }

    /**
     * Returns the part of the whole-job re-run budget not yet spent.
     *
     * @return the remaining re-runs; 0 once the budget is spent
     */
    @Override
    public int getMaxRetries() { return Math.max(0, maxRetries - retriesRun); }

    @Override
    public void onCorrectionAttempted(CodingAgentJob job) { retriesRun++; }

    @Override
    public boolean isViolated(CodingAgentJob job) {
        if (job.hasAgentCommitted()) return false;
        return job.previewStaging().getStagedFiles().isEmpty();
    }

    @Override
    public String buildCorrectionPrompt(CodingAgentJob job) { return null; }
}
