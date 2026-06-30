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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        // Rules that must never be re-entered: either their per-rule cap exceeded the
        // absolute ceiling (so re-running would only hit the ceiling again) or an
        // exhaustion fallback has already resolved them. A chronically-violated rule
        // with a modest per-rule cap is NOT placed here — it re-enters across passes
        // and is bounded by the global total-attempt cap instead.
        Set<String> exhaustedRules = new HashSet<>();
        do {
            // The RestartGovernor is the universal stop: once the global session
            // cap or the job-wide dollar/turn budget is exhausted, no further
            // correction sessions may launch regardless of per-rule caps.
            if (!job.restartGovernor().canLaunchSession()) {
                warn("Enforcement halted before completion -- " + job.restartGovernor().blockReason());
                break;
            }
            anyRuleCorrectionRan = false;
            for (EnforcementRule rule : rules) {
                String ruleName = rule.getName();
                if (exhaustedRules.contains(ruleName)) {
                    continue;
                }
                if (!rule.isViolated(job)) {
                    log("Enforcement rule '" + ruleName + "': no violation");
                    continue;
                }

                log("Enforcement rule '" + ruleName + "': violation detected");

                // Correction attempts in a single pass are bounded by the rule's own cap
                // and the absolute safety ceiling, whichever is smaller. When the rule's
                // cap exceeds the ceiling, the ceiling is what stops the pass — and in that
                // case the rule is retired afterwards so it cannot re-enter and run away.
                int ruleCap = Math.min(rule.getMaxRetries(), CodingAgentJob.DEFAULT_MAX_RULE_ENTRIES);
                boolean ceilingLimited = rule.getMaxRetries() > CodingAgentJob.DEFAULT_MAX_RULE_ENTRIES;
                int attempts = 0;
                while (attempts < ruleCap
                        && rule.isViolated(job)
                        && !job.hasAgentCommitted()
                        && totalAttempts < CodingAgentJob.DEFAULT_MAX_TOTAL_ENFORCEMENT_ATTEMPTS
                        && job.restartGovernor().canLaunchSession()) {
                    attempts++;
                    totalAttempts++;
                    anyRuleCorrectionRan = true;
                    log("Enforcement rule '" + ruleName
                            + "': correction attempt " + attempts);
                    String correctionPrompt = rule.buildCorrectionPrompt(job);
                    if (correctionPrompt != null) {
                        job.runCorrectionSession(correctionPrompt, ruleName);
                    } else {
                        if ("enforce-changes".equals(ruleName)) {
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
                        if (!"enforce-changes".equals(ruleName)) {
                            job.setCurrentActivity(ruleName);
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
                    if (attempts >= ruleCap) {
                        if (ceilingLimited) {
                            warn("Enforcement rule '" + ruleName
                                    + "': absolute entry ceiling (" + CodingAgentJob.DEFAULT_MAX_RULE_ENTRIES
                                    + ") reached; skipping rule to prevent runaway cost");
                            job.harnessStatus().unusual("Enforcement rule '" + ruleName
                                    + "' reached absolute entry ceiling and was skipped");
                        } else {
                            warn("Enforcement rule '" + ruleName + "': exhausted "
                                    + rule.getMaxRetries() + " retries without resolution");
                            job.harnessStatus().unusual("Enforcement rule '" + ruleName
                                    + "' exhausted " + rule.getMaxRetries() + " retries without resolution");
                        }
                        if ("post-completion-command".equals(ruleName)) job.setPostCompletionCapHit(true);
                        boolean fallbackApplied = applyExhaustionFallback(rule, job);
                        // Retire the rule only when the ceiling bound it (re-running would
                        // just hit the ceiling again) or a fallback already resolved it.
                        // Otherwise the rule is free to re-enter on the next pass, with the
                        // global total-attempt cap as the ultimate backstop.
                        if (ceilingLimited || fallbackApplied) {
                            exhaustedRules.add(ruleName);
                        }
                    } else if (totalAttempts >= CodingAgentJob.DEFAULT_MAX_TOTAL_ENFORCEMENT_ATTEMPTS) {
                        warn("Enforcement rule '" + ruleName
                                + "': stopped because the total enforcement attempt cap was reached");
                    }
                } else if ("post-completion-command".equals(ruleName) && ((PostCompletionCommandRule) rule).isCapHit()) {
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
     *
     * @return {@code true} if a fallback was applied (so the rule is now resolved
     *         and should not be re-entered); {@code false} if the rule has no
     *         fallback
     */
    private boolean applyExhaustionFallback(EnforcementRule rule, CodingAgentJob job) {
        if (!"commit-message".equals(rule.getName())) return false;
        Path commitFile = job.resolveWorkingPath("commit.txt");
        if (commitFile == null) return false;
        String fallback = buildFallbackCommitMessage(job);
        try {
            Files.writeString(commitFile, fallback, StandardCharsets.UTF_8);
            log("commit-message rule: wrote fallback commit message to commit.txt");
            return true;
        } catch (IOException e) {
            warn("Could not write fallback commit.txt: " + e.getMessage());
            return false;
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
