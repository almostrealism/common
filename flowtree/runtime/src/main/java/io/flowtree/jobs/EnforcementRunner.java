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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

    /**
     * How many consecutive correction attempts may leave the working tree
     * exactly as they found it, with the rule still violated, before the rule
     * is retired for as long as the tree stays that way.
     *
     * <p>An attempt that changes nothing and leaves the violation in place
     * shows the agent cannot (or will not) satisfy the rule from where it is;
     * another identical attempt is the same question asked again. One repeat
     * is allowed, since a session can end early for reasons of its own. Without
     * this bound a rule the agent cannot satisfy, such as a guardrail rejecting
     * content the agent did not write, runs until the job-wide caps stop it:
     * more than a dozen sessions on one branch in eleven minutes, each
     * re-verifying the same impossibility.</p>
     */
    static final int MAX_NO_PROGRESS_ATTEMPTS = 2;

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
        // Review is deliberately NOT gated on the primary phase's outcome. A
        // primary phase that reports failure has still been observed to leave
        // work review can recover, so skipping review on the strength of that
        // report discards changes that would have landed. What makes review
        // pointless is a tree that cannot be pushed — a merge conflict, a git
        // failure, or a run that never intended to push — not a bad primary
        // result. Falsification is gated (see CodingAgentJob#doWork) because it
        // reasons about work the agent claims to have completed; review reasons
        // about the tree, which exists either way.
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
        if (reviewRule != null) {
            // One session per changed class: after the content-protection rules, so that a
            // branch touching many classes cannot spend the total attempt cap before they run.
            rules.add(new ObjectOrientedDesignRule());
        }
        if (job.getTargetBranch() != null && !job.getTargetBranch().isEmpty()) {
            // Checked after the content rules above, right before the final
            // commit-message check -- see StagingSkipRule's class javadoc.
            rules.add(new StagingSkipRule());
            // Always last: verifies commit.txt is present and agent-authored.
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
     *
     * <p>A rule that exhausts its retries is retired for good only when its
     * per-rule cap exceeded the absolute ceiling (re-running would hit the
     * ceiling again) or an exhaustion fallback resolved it. Otherwise it
     * re-enters across passes, bounded by the global total-attempt cap. A rule
     * retired after {@link #MAX_NO_PROGRESS_ATTEMPTS} attempts that changed
     * nothing is remembered with the working-tree fingerprint it stalled on:
     * it is skipped while the tree still matches, and re-enters once something
     * else, such as another rule's correction, changes the tree.</p>
     */
    void run() {
        List<EnforcementRule> rules = buildActiveRules();
        int totalAttempts = 0;
        boolean anyRuleCorrectionRan;
        Set<String> exhaustedRules = new HashSet<>();
        Map<String, String> stalledRules = new HashMap<>();
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
                if (stalledRules.containsKey(ruleName)
                        && stalledRules.get(ruleName).equals(workingTreeFingerprint(rule))) {
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
                int noProgressAttempts = 0;
                while (attempts < ruleCap
                        && noProgressAttempts < MAX_NO_PROGRESS_ATTEMPTS
                        && rule.isViolated(job)
                        && !job.hasAgentCommitted()
                        && totalAttempts < CodingAgentJob.DEFAULT_MAX_TOTAL_ENFORCEMENT_ATTEMPTS
                        && job.restartGovernor().canLaunchSession()) {
                    attempts++;
                    totalAttempts++;
                    anyRuleCorrectionRan = true;
                    log("Enforcement rule '" + ruleName
                            + "': correction attempt " + attempts);
                    String treeBefore = workingTreeFingerprint(rule);
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
                    noProgressAttempts = treeBefore != null && treeBefore.equals(workingTreeFingerprint(rule))
                            ? noProgressAttempts + 1 : 0;
                }

                if (!job.hasAgentCommitted() && rule.isViolated(job)) {
                    boolean stalled = noProgressAttempts >= MAX_NO_PROGRESS_ATTEMPTS;
                    if (stalled || attempts >= ruleCap) {
                        if (stalled) {
                            warn("Enforcement rule '" + ruleName + "': " + noProgressAttempts
                                    + " consecutive correction attempts left the working tree unchanged"
                                    + " and the violation in place; retiring the rule until the tree changes");
                            job.harnessStatus().unusual("Enforcement rule '" + ruleName + "' retired after "
                                    + noProgressAttempts + " correction attempts that made no progress");
                        } else if (ceilingLimited) {
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
                        if (ceilingLimited || fallbackApplied) {
                            exhaustedRules.add(ruleName);
                        } else if (stalled) {
                            String stalledOn = workingTreeFingerprint(rule);
                            if (stalledOn != null) stalledRules.put(ruleName, stalledOn);
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
     * Returns a fingerprint of the job's uncommitted working-tree state as it
     * bears on {@code rule}: every changed file outside scratch space, plus the
     * rule's own {@linkplain EnforcementRule#getProgressPaths() progress paths};
     * see {@link GitOperations#fingerprintUncommittedState}.
     *
     * <p>A job without a working directory has no tree to judge; git would
     * otherwise inspect the JVM's own working directory.</p>
     *
     * @param rule the rule whose progress is being measured
     * @return the fingerprint, or {@code null} when it cannot be computed (no
     *         working directory, not a git working tree, a failed status
     *         query), in which case no attempt is ever judged to have made no
     *         progress
     */
    private String workingTreeFingerprint(EnforcementRule rule) {
        if (job.getWorkingDirectory() == null) {
            return null;
        }
        try {
            return GitOperations.fingerprintUncommittedState(job.getWorkingDirectory(),
                    rule.getProgressPaths().toArray(new String[0]));
        } catch (IOException e) {
            return null;
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

        // The fallback synthesises a message from the job prompt — a description
        // of what was ASKED for, not of what was done. That is a reasonable
        // stand-in when the agent worked and merely failed to describe itself,
        // and a fabrication when the primary phase hard-failed: it would label a
        // tree the agent never successfully worked on with a message implying it
        // had. Downstream, that message is what a reviewer reads and what the
        // status rollup treats as a completed unit of work.
        //
        // Refusing here leaves the rule unresolved, which is the honest outcome:
        // the job reports a failure it actually had rather than a success it
        // manufactured.
        if (job.isPrimaryPhaseHardFailed()) {
            warn("commit-message rule: not writing a fallback commit message because"
                    + " the primary phase hard-failed; a prompt-derived message would"
                    + " describe work that was not done");
            job.harnessStatus().unusual("commit-message fallback suppressed after a"
                    + " hard primary failure");
            return false;
        }

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
     *   <li>The job prompt's first line, if non-empty and free of author
     *       attribution (a prompt line that names an author would produce the
     *       very message {@link CommitMessageBuilder} refuses to commit)</li>
     *   <li>{@code "Job {jobId} commit"}</li>
     * </ol>
     */
    private String buildFallbackCommitMessage(CodingAgentJob job) {
        String prompt = job.getPrompt();
        if (prompt != null && !prompt.trim().isEmpty()) {
            String firstLine = prompt.trim().split("\n")[0].trim();
            if (!firstLine.isEmpty() && !AuthorAttribution.containsAttribution(firstLine)) {
                return firstLine.length() > 72
                        ? firstLine.substring(0, 69) + "..."
                        : firstLine;
            }
        }
        return "Job " + job.getTaskId() + " commit";
    }
}
