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
 * {@link InstructionPromptBuilder}.</p>
 */
class EnforceChangesRule implements EnforcementRule {
    @Override
    public String getName() { return "enforce-changes"; }

    @Override
    public boolean isViolated(CodingAgentJob job) {
        if (job.hasAgentCommitted()) return false;
        return job.previewStaging().getStagedFiles().isEmpty();
    }

    @Override
    public String buildCorrectionPrompt(CodingAgentJob job) { return null; }
}
