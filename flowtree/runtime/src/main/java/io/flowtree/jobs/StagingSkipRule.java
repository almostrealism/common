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

import java.util.ArrayList;
import java.util.List;

/**
 * Enforcement rule that previews the file-staging guardrails against the
 * agent's current uncommitted changes and, when any file would be skipped,
 * gives the agent a correction turn naming the skipped files and why —
 * while the session can still react to it.
 *
 * <p>Without this rule, a guardrail skip is discovered only after
 * {@code doWork()} returns, when {@link GitCommitHandler} actually stages
 * files: by then the agent's session has already ended, so a discarded fix
 * is invisible until the job's completion status is inspected (see
 * {@link JobWorkOutcome#allChangesDropped()}). This rule surfaces the same
 * information earlier, during the enforcement retry loop, so the agent can
 * find another approach — for a protected test file, editing only a method
 * it introduced on this branch rather than a pre-existing one — or say
 * explicitly that a human needs to intervene.</p>
 *
 * <p>Always active for git-enabled jobs, not just when
 * {@link CodingAgentJob#isProtectTestFiles()} is set: any guardrail (pattern
 * exclusion, size limit, binary detection) can silently discard a real fix,
 * and the agent should hear about all of them, not just test-protection
 * skips.</p>
 *
 * <p>{@link EnforcementRunner} places this rule after the content rules
 * (review, deduplication, organizational placement, custom rules) and
 * immediately before the final commit-message check, so a skip is reported
 * only once those rules have had their say about the tree's content.</p>
 *
 * <p>{@code commit.txt} is excluded from what this rule reports even though
 * {@link FileStager} always skips it: every job is instructed to write it,
 * it is harness-owned metadata read by {@link CommitMessageBuilder} and
 * never itself committed, and flagging its expected exclusion on every job
 * would just be noise crowding out real drops.</p>
 */
class StagingSkipRule implements EnforcementRule {

    /** The one harness-owned file every job writes that is never itself committed. */
    private static final String COMMIT_MESSAGE_FILE = "commit.txt";

    @Override
    public String getName() {
        return "staging-skip";
    }

    @Override
    public boolean isViolated(CodingAgentJob job) {
        if (job.hasAgentCommitted()) return false;
        return !reportableSkips(job).isEmpty();
    }

    @Override
    public String buildCorrectionPrompt(CodingAgentJob job) {
        List<String> skipped = reportableSkips(job);
        if (skipped.isEmpty()) return null;

        StringBuilder prompt = new StringBuilder();
        prompt.append("Some of your changes will NOT be committed because a staging guardrail")
                .append(" rejected them:\n\n");
        for (String entry : skipped) {
            prompt.append("  - ").append(entry).append('\n');
        }
        prompt.append("\nFind another way to make this change. For a protected test file, add a")
                .append(" new test method or edit only a method you introduced on this branch —")
                .append(" editing a pre-existing test method is never staged, even when the edit")
                .append(" only adds lines. If the file is rejected by a pattern, size, or binary")
                .append(" guardrail, its content cannot be committed by this job at all. If you")
                .append(" cannot find another approach, say explicitly in your final message that")
                .append(" a human needs to intervene, and why.");
        return prompt.toString();
    }

    @Override
    public int getMaxRetries() {
        return 2;
    }

    /**
     * Classifies the skip as environment-determined when every reportable skip
     * was rejected by a staging guardrail this job cannot bypass from where it
     * runs — an excluded pattern, CI/workflow protection, a binary file, or a
     * file over the size limit. In those cases the rule's own prompt already
     * tells the agent the content "cannot be committed by this job at all", so
     * re-running it only spends sessions re-discovering the same wall.
     *
     * <p>Any other skip is left {@linkplain ViolationClassification#agentFixable
     * agent-fixable}: a protected test file has a documented workaround (edit a
     * method the branch introduced, or add a new one), and an unrecognised
     * reason is retried rather than silently abandoned. The violation is
     * therefore environment-determined only when there is no reportable skip the
     * agent could act on.</p>
     *
     * @param job the job whose staging preview produced the skips
     * @return an environment-determined classification when no skip is
     *         actionable, otherwise agent-fixable
     */
    @Override
    public ViolationClassification classifyViolation(CodingAgentJob job) {
        List<String> skipped = reportableSkips(job);
        if (skipped.isEmpty()) return ViolationClassification.agentFixable();
        for (String entry : skipped) {
            if (!isUnbypassableGuardrail(entry)) {
                return ViolationClassification.agentFixable();
            }
        }
        return ViolationClassification.environmentDetermined(
                "every changed file was rejected by a staging guardrail this job cannot"
                        + " bypass: " + String.join("; ", skipped));
    }

    /**
     * Returns whether a {@code "path (reason)"} skip entry names a guardrail
     * the agent cannot work around within this job. The markers mirror the
     * reasons {@link FileStager} records when it rejects a file outright.
     *
     * @param skipEntry a reportable skip entry in {@code "path (reason)"} form
     * @return {@code true} when the agent has no way to get this file committed
     */
    private boolean isUnbypassableGuardrail(String skipEntry) {
        return skipEntry.contains("(excluded pattern)")
                || skipEntry.contains("(protected - CI/workflow file)")
                || skipEntry.contains("(binary file)")
                || skipEntry.contains("(exceeds ");
    }

    /**
     * Returns the staging skips worth surfacing to the agent: every skip
     * from {@link GitManagedJob#previewStaging()} except the expected
     * exclusion of {@link #COMMIT_MESSAGE_FILE}.
     *
     * @param job the job whose current uncommitted changes to preview
     * @return the skipped-file entries the agent should hear about
     */
    private List<String> reportableSkips(CodingAgentJob job) {
        List<String> reportable = new ArrayList<>();
        for (String entry : job.previewStaging().getSkippedFiles()) {
            if (!entry.startsWith(COMMIT_MESSAGE_FILE + " (")) {
                reportable.add(entry);
            }
        }
        return reportable;
    }
}
