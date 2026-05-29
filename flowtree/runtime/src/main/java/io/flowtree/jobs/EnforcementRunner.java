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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        Map<String, Integer> ruleEntryCount = new HashMap<>();
        do {
            anyRuleCorrectionRan = false;
            for (EnforcementRule rule : rules) {
                if (!rule.isViolated(job)) {
                    log("Enforcement rule '" + rule.getName() + "': no violation");
                    continue;
                }

                log("Enforcement rule '" + rule.getName() + "': violation detected");

                int ruleEntries = ruleEntryCount.getOrDefault(rule.getName(), 0);
                if (ruleEntries >= CodingAgentJob.DEFAULT_MAX_RULE_ENTRIES) {
                    warn("Enforcement rule '" + rule.getName()
                            + "': absolute entry ceiling (" + CodingAgentJob.DEFAULT_MAX_RULE_ENTRIES
                            + ") reached; skipping rule to prevent runaway cost");
                    job.harnessStatus().unusual("Enforcement rule '" + rule.getName()
                            + "' reached absolute entry ceiling and was skipped");
                    continue;
                }
                ruleEntryCount.put(rule.getName(), ruleEntries + 1);

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
                        if ("enforce-changes".equals(rule.getName())) {
                            job.setEnforcementAttempt(job.getEnforcementAttempt() + 1);
                            log("enforce_changes found no changes; restarting PRIMARY (retry "
                                    + job.getEnforcementAttempt() + ")");
                        }
                        Path rerunCommitFile = job.resolveWorkingPath("commit.txt");
                        String savedForRerun = null;
                        if (rerunCommitFile != null && Files.exists(rerunCommitFile)) {
                            try { savedForRerun = Files.readString(rerunCommitFile, StandardCharsets.UTF_8); }
                            catch (IOException e) { warn("Could not save commit.txt: " + e.getMessage()); }
                        }
                        String previousActivity = job.getCurrentActivity();
                        if (!"enforce-changes".equals(rule.getName())) {
                            job.setCurrentActivity(rule.getName());
                        }
                        try {
                            job.executeSingleRun();
                        } finally {
                            job.setCurrentActivity(previousActivity);
                        }
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
                        applyExhaustionFallback(rule, job);
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

    /**
     * Invokes the rule-specific fallback when a rule's per-pass cap is exhausted.
     * The fallback writes a usable commit message so the job can proceed.
     */
    private void applyExhaustionFallback(EnforcementRule rule, CodingAgentJob job) {
        if (!"commit-message".equals(rule.getName())) return;
        Path commitFile = job.resolveWorkingPath("commit.txt");
        if (commitFile == null) return;
        String fallback = buildFallbackCommitMessage(job);
        try {
            Files.writeString(commitFile, fallback, StandardCharsets.UTF_8);
            log("commit-message rule: wrote fallback commit message to commit.txt");
        } catch (IOException e) {
            warn("Could not write fallback commit.txt: " + e.getMessage());
        }
    }

    /**
     * Builds the fallback commit message used when the commit-message rule exhausts
     * its retries and needs to produce a usable message so the job can proceed.
     * Uses, in order:
     * <ol>
     *   <li>The job prompt's first line, if non-empty</li>
     *   <li>{@code "Job {jobId} commit"}</li>
     * </ol>
     */
    private String buildFallbackCommitMessage(CodingAgentJob job) {
        String prompt = job.getPrompt();
        if (prompt != null && !prompt.trim().isEmpty()) {
            String firstLine = prompt.trim().split("\n")[0].trim();
            if (!firstLine.isEmpty()) {
                return firstLine.length() > 72
                        ? firstLine.substring(0, 69) + "..."
                        : firstLine;
            }
        }
        return "Job " + job.getTaskId() + " commit";
    }
}
