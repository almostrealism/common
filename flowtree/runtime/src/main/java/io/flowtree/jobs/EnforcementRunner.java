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

import org.almostrealism.io.ConsoleFeatures;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates the post-run enforcement rules for a {@link CodingAgentJob}.
 *
 * <p>This collaborator owns the rule-set assembly and the retry loop that was
 * formerly inlined in {@code CodingAgentJob}. It drives the rules but defers
 * the actual agent execution back to the job: correction sessions go through
 * {@link CodingAgentJob#runCorrectionSession(String, String)} (which test spies
 * override) and plain re-runs through {@link CodingAgentJob#executeSingleRun()},
 * so behavior — and the job's testable surface — is unchanged.</p>
 *
 * <p>Lives in {@code io.flowtree.jobs} so it can reach the job's package-private
 * run primitives without widening the public API.</p>
 */
class EnforcementRunner implements ConsoleFeatures {

    /** The job whose configuration and run primitives this runner drives. */
    private final CodingAgentJob job;

    /**
     * Creates a runner bound to the given job.
     *
     * @param job the job to run enforcement rules for
     */
    EnforcementRunner(CodingAgentJob job) {
        this.job = job;
    }

    /**
     * Builds the ordered list of enforcement rules active for the job, based on
     * its configuration flags.
     *
     * <p>Rules are evaluated in declaration order: built-in rules first, then any
     * custom rules registered via {@link CodingAgentJob#addEnforcementRule}.</p>
     *
     * @return ordered list of active enforcement rules; never {@code null}
     */
    private List<EnforcementRule> buildActiveRules() {
        List<EnforcementRule> rules = new ArrayList<>();
        if (job.isEnforceChanges()) {
            rules.add(new EnforceChangesRule());
        }
        ReviewRule reviewRule = job.isReviewEnabled() ? new ReviewRule(job.getMaxReviewPasses()) : null;
        job.setActiveReviewRule(reviewRule);
        if (reviewRule != null) rules.add(reviewRule);
        if (CodingAgentJob.DEDUP_LOCAL.equals(job.getDeduplicationMode())) {
            rules.add(new DeduplicationRule(job.getMaxDeduplicationPasses()));
        }
        if (job.isEnforceOrganizationalPlacement()) {
            rules.add(new OrganizationalPlacementRule());
        }
        if (job.getPostCompletionCommand() != null && !job.getPostCompletionCommand().isEmpty()) {
            rules.add(new PostCompletionCommandRule(
                    job.getPostCompletionCommand(),
                    job.getPostCompletionWorkingDir(),
                    job.getPostCompletionTimeoutSeconds(),
                    job.getMaxPostCompletionPasses()));
        }
        if (job.isEnforceMavenDependencies()) {
            rules.add(new MavenDependencyProtectionRule());
        }
        rules.addAll(job.getCustomEnforcementRules());
        // Always last: verifies commit.txt is present and agent-authored.
        if (job.getTargetBranch() != null && !job.getTargetBranch().isEmpty()) {
            rules.add(new CommitMessageRule());
        }
        return rules;
    }

    /**
     * Runs all active enforcement rules in sequence.
     *
     * <p>For each rule that detects a violation, a correction session is started
     * and the check is repeated until the violation is resolved or the rule's
     * maximum retry count is exhausted. Rules are independent: a failure in one
     * rule does not prevent subsequent rules from running.</p>
     *
     * <p>Exits early if the agent commits during a correction session — the
     * tampering-detection path in {@code GitManagedJob} handles that case.</p>
     */
    void run() {
        List<EnforcementRule> rules = buildActiveRules();
        int totalAttempts = 0;
        boolean anyRuleCorrectionRan;
        do {
            anyRuleCorrectionRan = false;
            for (EnforcementRule rule : rules) {
                if (!rule.isViolated(job)) {
                    log("Enforcement rule '" + rule.getName() + "': no violation");
                    continue;
                }

                log("Enforcement rule '" + rule.getName() + "': violation detected");
                int attempts = 0;
                while (attempts < rule.getMaxRetries()
                        && rule.isViolated(job)
                        && !job.hasAgentCommitted()
                        && totalAttempts < CodingAgentJob.DEFAULT_MAX_TOTAL_ENFORCEMENT_ATTEMPTS) {
                    attempts++;
                    totalAttempts++;
                    anyRuleCorrectionRan = true;
                    log("Enforcement rule '" + rule.getName()
                            + "': correction attempt " + attempts);
                    String correctionPrompt = rule.buildCorrectionPrompt(job);
                    if (correctionPrompt != null) {
                        job.runCorrectionSession(correctionPrompt, rule.getName());
                    } else {
                        // TODO(review): merge dropped origin/master's enforce-changes routing fix here;
                        // master no longer sets currentActivity for enforce-changes (keeps PRIMARY routing,
                        // since Phase.ENFORCE_CHANGES is now DEPRECATED) and updated the log message below.
                        // enforce-changes is the only rule that re-runs with the existing
                        // prompt; bumping enforcementAttempt for other rules would inflate
                        // the user-facing escalation messaging.
                        if ("enforce-changes".equals(rule.getName())) {
                            job.setEnforcementAttempt(job.getEnforcementAttempt() + 1);
                            log("Enforcement attempt: " + (job.getEnforcementAttempt() + 1));
                        }
                        // Preserve commit.txt: executeSingleRun() deletes it at startup.
                        Path rerunCommitFile = job.resolveWorkingPath("commit.txt");
                        String savedForRerun = null;
                        if (rerunCommitFile != null && Files.exists(rerunCommitFile)) {
                            try { savedForRerun = Files.readString(rerunCommitFile, StandardCharsets.UTF_8); }
                            catch (IOException e) { warn("Could not save commit.txt: " + e.getMessage()); }
                        }
                        // Tag the activity so per-phase runner routing applies even though
                        // there is no separate correction prompt for this rule.
                        String previousActivity = job.getCurrentActivity();
                        job.setCurrentActivity(rule.getName());
                        try {
                            job.executeSingleRun();
                        } finally {
                            job.setCurrentActivity(previousActivity);
                        }
                        // Only restore old commit.txt if the rerun did not write a new one;
                        // when the rerun makes the actual code changes its message is authoritative.
                        boolean rerunWroteCommit = rerunCommitFile != null && Files.exists(rerunCommitFile);
                        if (!rerunWroteCommit && savedForRerun != null && rerunCommitFile != null) {
                            try { Files.writeString(rerunCommitFile, savedForRerun, StandardCharsets.UTF_8); }
                            catch (IOException e) { warn("Could not restore commit.txt: " + e.getMessage()); }
                        }
                    }
                    rule.onCorrectionAttempted(job);
                    if (job.hasAgentCommitted()) break;
                }

                if (!job.hasAgentCommitted() && rule.isViolated(job)) {
                    if (attempts >= rule.getMaxRetries()) {
                        warn("Enforcement rule '" + rule.getName() + "': exhausted "
                                + rule.getMaxRetries() + " retries without resolution");
                        job.harnessStatus().unusual("Enforcement rule '" + rule.getName()
                                + "' exhausted " + rule.getMaxRetries() + " retries without resolution");
                        if ("post-completion-command".equals(rule.getName())) job.setPostCompletionCapHit(true);
                    } else if (totalAttempts >= CodingAgentJob.DEFAULT_MAX_TOTAL_ENFORCEMENT_ATTEMPTS) {
                        warn("Enforcement rule '" + rule.getName()
                                + "': stopped because the total enforcement attempt cap was reached");
                    }
                } else if ("post-completion-command".equals(rule.getName()) && ((PostCompletionCommandRule) rule).isCapHit()) {
                    job.setPostCompletionCapHit(true);
                }
                if (totalAttempts >= CodingAgentJob.DEFAULT_MAX_TOTAL_ENFORCEMENT_ATTEMPTS) break;
            }
        } while (totalAttempts < CodingAgentJob.DEFAULT_MAX_TOTAL_ENFORCEMENT_ATTEMPTS
                && anyRuleCorrectionRan && !job.hasAgentCommitted());

        if (totalAttempts >= CodingAgentJob.DEFAULT_MAX_TOTAL_ENFORCEMENT_ATTEMPTS) {
            warn("Enforcement aborted after " + totalAttempts + " total attempts (cap: "
                    + CodingAgentJob.DEFAULT_MAX_TOTAL_ENFORCEMENT_ATTEMPTS + ") — giving up to"
                    + " avoid an unbounded retry loop");
        }
    }
}
