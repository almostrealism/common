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

import io.flowtree.JsonFieldExtractor;
import io.flowtree.job.Job;
import org.almostrealism.io.JobOutput;
import org.almostrealism.util.KeyUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * A {@link Job} implementation that executes a Claude Code prompt.
 *
 * <p>This job invokes Claude Code via the command line using the {@code -p} flag
 * for non-interactive (headless) execution. The job completes when Claude Code
 * finishes processing the prompt, making it suitable for distributed task queues
 * where idle detection is important.</p>
 *
 * <p>Extends {@link GitManagedJob} to optionally commit and push changes after
 * Claude Code completes its work. Set a target branch via {@link #setTargetBranch(String)}
 * to enable automatic git management.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Simple job without git management
 * ClaudeCodeJob job = new ClaudeCodeJob(taskId, "Fix the bug in auth.py");
 * job.run();
 *
 * // Job with git management
 * ClaudeCodeJob job = new ClaudeCodeJob(taskId, "Fix the bug in auth.py");
 * job.setTargetBranch("feature/fix-auth-bug");
 * job.run();  // Changes will be committed and pushed
 *
 * // Via factory (for Flowtree integration)
 * ClaudeCodeJob.Factory factory = new ClaudeCodeJob.Factory(
 *     "Review and improve error handling",
 *     "Add unit tests for the new feature"
 * );
 * factory.setAllowedTools("Read,Edit,Bash,Glob,Grep");
 * factory.setTargetBranch("feature/improvements");
 * server.sendTask(factory);
 * }</pre>
 *
 * @author Michael Murray
 * @see GitManagedJob
 * @see ClaudeCodeJobFactory
 */
public class ClaudeCodeJob extends GitManagedJob {
    /** Sentinel string used to delimit multiple prompts in the serialized wire format. */
    public static final String PROMPT_SEPARATOR = ";;PROMPT;;";
    /** Default comma-separated list of tools permitted for Claude Code sessions. */
    public static final String DEFAULT_TOOLS = "Read,Edit,Write,Bash,Glob,Grep";

    /**
     * Deduplication mode that runs an inline Claude Code session before the
     * commit is finalised.  The session receives an aggressive prompt listing
     * all new method names and is instructed to remove any duplicates it finds.
     * This mode is safe to test incrementally because it executes within the
     * existing job lifecycle and cannot spawn additional jobs.
     */
    public static final String DEDUP_LOCAL = "local";

    /**
     * Deduplication mode that submits a separate {@link ClaudeCodeJob} to the
     * same workstream after the current job's commit has been pushed.
     * Requires a workstream URL to be configured on this job.
     */
    public static final String DEDUP_SPAWN = "spawn";

    /**
     * Deduplication mode that disables the deduplication scan entirely.
     * Use this to explicitly opt out when the default {@link #DEDUP_LOCAL}
     * behaviour is not desired.
     */
    public static final String DEDUP_NONE = "none";

    /** The prompt submitted to Claude Code for this job. */
    private String prompt;
    /** Short human-readable description of this job, used in status messages. */
    private String description;
    /** Comma-separated list of tool names that Claude Code is permitted to invoke. */
    private String allowedTools;
    /** Maximum number of agentic turns Claude Code may take before being stopped. */
    private int maxTurns;
    /** Maximum spend budget for this job in US dollars; negative disables the limit. */
    private double maxBudgetUsd;
    /** HTTP base URL of the ar-manager service, or {@code null} if not configured. */
    private String arManagerUrl;
    /** Bearer token for authenticating against the ar-manager service. */
    private String arManagerToken;
    /** Optional planning document text injected into the Claude Code system prompt. */
    private String planningDocument;
    /** GitHub organisation name used to look up API tokens for PR creation. */
    private String githubOrg;
    /** Whether the job must produce at least one staged file change to succeed. */
    private boolean enforceChanges;
    /** Number of times enforcement has been re-attempted after an empty commit. */
    private int enforcementAttempt;
    /** Description of a git-tampering rule violation detected during this job. */
    private String gitTamperingViolation;
    /**
     * Controls post-work deduplication behaviour.
     * {@code null} (the default) disables deduplication — the factory sets
     * this to {@link #DEDUP_LOCAL} when creating jobs.
     * {@link #DEDUP_SPAWN} submits a follow-up job to the same workstream.
     * {@link #DEDUP_NONE} also disables deduplication explicitly.
     */
    private String deduplicationMode;

    /**
     * When {@code true}, the Maven dependency protection rule is active.
     * Any {@code <dependency>} additions, removals, or changes in {@code pom.xml}
     * files detected against the base branch trigger a correction loop that
     * instructs the agent to revert those changes.
     */
    private boolean enforceMavenDependencies;

    /**
     * Additional enforcement rules registered via {@link #addEnforcementRule(EnforcementRule)}.
     * Built-in rules (enforce-changes, deduplication, maven-dependency-protection) are
     * instantiated from job flags in {@link #buildActiveRules()} and are not stored here.
     */
    private final List<EnforcementRule> customEnforcementRules = new ArrayList<>();

    /** Builder used to assemble the MCP tool configuration JSON for Claude Code. */
    private final McpConfigBuilder mcpConfigBuilder = new McpConfigBuilder();
    /** Helper that downloads managed tool definitions referenced by the MCP config. */
    private final ManagedToolsDownloader toolsDownloader = new ManagedToolsDownloader(mcpConfigBuilder);
    /** JSON mapper used to parse structured output from Claude Code. */
    private static final ObjectMapper outputMapper = new ObjectMapper();

    /** The Claude Code session identifier assigned during execution. */
    private String sessionId;
    /** Raw text output produced by the Claude Code process. */
    private String output;
    /** Exit code returned by the Claude Code process. */
    private int exitCode;
    /** Total wall-clock duration of the Claude Code session in milliseconds. */
    private long durationMs;
    /** Time spent in API calls during the Claude Code session, in milliseconds. */
    private long durationApiMs;
    /** Total cost of the Claude Code session in US dollars. */
    private double costUsd;
    /** Number of agentic turns taken during the Claude Code session. */
    private int numTurns;
    /** Session subtype / stop reason reported by Claude Code (e.g. "success"). */
    private String subtype;
    /** Whether Claude Code flagged the session result as an error. */
    private boolean isError;
    /** Number of tool-use permission denials recorded during the session. */
    private int permissionDenials;
    /** Names of the tools that were denied during the session. */
    private List<String> deniedToolNames;

    /**
     * Default constructor for deserialization.
     */
    public ClaudeCodeJob() {
        this.allowedTools = DEFAULT_TOOLS;
        this.maxTurns = 50;
        this.maxBudgetUsd = 10.0;
    }

    /**
     * Creates a new ClaudeCodeJob with the specified prompt.
     *
     * @param taskId  the task ID for tracking
     * @param prompt  the prompt to send to Claude Code
     */
    public ClaudeCodeJob(String taskId, String prompt) {
        super(taskId);
        this.allowedTools = DEFAULT_TOOLS;
        this.maxTurns = 50;
        this.maxBudgetUsd = 10.0;
        this.prompt = prompt;
    }

    /**
     * Creates a new ClaudeCodeJob with the specified prompt and tools.
     *
     * @param taskId       the task ID for tracking
     * @param prompt       the prompt to send to Claude Code
     * @param allowedTools comma-separated list of allowed tools (e.g., "Read,Edit,Bash")
     */
    public ClaudeCodeJob(String taskId, String prompt, String allowedTools) {
        this(taskId, prompt);
        this.allowedTools = allowedTools;
    }

    @Override
    public String getTaskString() {
        if (description != null && !description.isEmpty()) {
            return description;
        }
        return prompt != null && prompt.length() > 50
            ? prompt.substring(0, 47) + "..."
            : prompt;
    }

    /**
     * Returns a short display summary for this job suitable for notifications.
     *
     * <p>If a description is set, it is returned directly. Otherwise, short prompts
     * (80 characters or fewer) are returned as-is, and longer prompts are summarized
     * as their character count.</p>
     *
     * @return the display summary
     */
    public String getDisplaySummary() {
        if (description != null && !description.isEmpty()) {
            return description;
        }
        return summarizePrompt(prompt);
    }

    /**
     * Produces a short display string for a prompt. Short prompts (80 characters
     * or fewer) are returned as-is; longer prompts are summarized as their
     * character count.
     *
     * @param prompt the raw prompt text
     * @return a concise summary suitable for notifications
     */
    public static String summarizePrompt(String prompt) {
        if (prompt == null) {
            return "(no prompt)";
        }
        if (prompt.length() <= 80) {
            return prompt;
        }
        return String.format("%,d character prompt", prompt.length());
    }

    /**
     * Returns the prompt submitted to Claude Code for this job.
     *
     * @return the prompt string
     */
    public String getPrompt() {
        return prompt;
    }

    /**
     * Sets the prompt to submit to Claude Code for this job.
     *
     * @param prompt  the prompt text
     */
    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    /**
     * Returns the short human-readable description for this job,
     * or {@code null} if none was set.
     */
    public String getDescription() {
        return description;
    }

    /**
     * Sets a short human-readable description for this job. When set, this
     * description is used in notifications instead of the prompt text.
     *
     * @param description a concise label (e.g., "Resolve test failures")
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * Returns the comma-separated list of tools Claude Code is permitted to invoke.
     *
     * @return the allowed tools string
     */
    public String getAllowedTools() {
        return allowedTools;
    }

    /**
     * Sets the comma-separated list of tools Claude Code is permitted to invoke.
     *
     * @param allowedTools  the tool names, comma-separated
     */
    public void setAllowedTools(String allowedTools) {
        this.allowedTools = allowedTools;
    }

    /**
     * Returns the maximum number of agentic turns Claude Code may take.
     *
     * @return the turn limit
     */
    public int getMaxTurns() {
        return maxTurns;
    }

    /**
     * Sets the maximum number of agentic turns Claude Code may take before being stopped.
     *
     * @param maxTurns  the turn limit
     */
    public void setMaxTurns(int maxTurns) {
        this.maxTurns = maxTurns;
    }

    /**
     * Returns the maximum spend budget for this job in US dollars.
     *
     * @return the budget cap in USD
     */
    public double getMaxBudgetUsd() {
        return maxBudgetUsd;
    }

    /**
     * Sets the maximum spend budget for this job in US dollars.
     *
     * @param maxBudgetUsd  the budget cap in USD; negative values disable the limit
     */
    public void setMaxBudgetUsd(double maxBudgetUsd) {
        this.maxBudgetUsd = maxBudgetUsd;
    }

    /** Returns the ar-manager HTTP URL. */
    public String getArManagerUrl() {
        return arManagerUrl;
    }

    /**
     * Sets the ar-manager HTTP URL for centralized tool access.
     *
     * @param arManagerUrl the ar-manager service URL
     */
    public void setArManagerUrl(String arManagerUrl) {
        this.arManagerUrl = arManagerUrl;
    }

    /** Returns the ar-manager auth token. */
    public String getArManagerToken() {
        return arManagerToken;
    }

    /**
     * Sets the HMAC temporary auth token for ar-manager.
     *
     * @param arManagerToken the bearer token
     */
    public void setArManagerToken(String arManagerToken) {
        this.arManagerToken = arManagerToken;
    }

    /**
     * Returns the planning document path for this job.
     * When set, the agent is instructed to read this file for the
     * broader goal of the current branch.
     */
    public String getPlanningDocument() {
        return planningDocument;
    }

    /**
     * Sets the planning document path for this job.
     *
     * @param planningDocument path relative to the working directory
     */
    public void setPlanningDocument(String planningDocument) {
        this.planningDocument = planningDocument;
    }

    /**
     * Returns the GitHub organization name for this job.
     * When set, the {@code AR_GITHUB_ORG} env var is injected into
     * the ar-github MCP server entry.
     */
    public String getGithubOrg() {
        return githubOrg;
    }

    /**
     * Sets the GitHub organization name for org-based token selection.
     *
     * @param githubOrg the GitHub organization name
     */
    public void setGithubOrg(String githubOrg) {
        this.githubOrg = githubOrg;
    }

    /**
     * Returns whether this job enforces that code changes must be produced.
     *
     * <p>When enabled, the instruction prompt replaces the permissive
     * "non-code requests" and "justifying no code changes" sections with
     * a strict message requiring code changes. Used by the enforcement
     * loop in {@link #doWork()} to prevent agents from dismissing test
     * failures without investigation.</p>
     */
    public boolean isEnforceChanges() {
        return enforceChanges;
    }

    /**
     * Sets whether this job enforces code changes.
     *
     * @param enforceChanges true to require code changes for completion
     */
    public void setEnforceChanges(boolean enforceChanges) {
        this.enforceChanges = enforceChanges;
    }

    /**
     * Returns the current enforcement attempt counter.
     * Zero means this is the first attempt (no retries yet).
     */
    public int getEnforcementAttempt() {
        return enforcementAttempt;
    }

    /**
     * Sets the enforcement attempt counter.
     *
     * @param enforcementAttempt the number of prior failed attempts
     */
    public void setEnforcementAttempt(int enforcementAttempt) {
        this.enforcementAttempt = enforcementAttempt;
    }

    /**
     * Returns the deduplication mode for this job.
     *
     * @return {@link #DEDUP_LOCAL}, {@link #DEDUP_SPAWN}, or {@link #DEDUP_NONE}
     */
    public String getDeduplicationMode() {
        return deduplicationMode;
    }

    /**
     * Sets the deduplication mode for this job.
     *
     * <p>The default is {@link #DEDUP_LOCAL} (inline session before commit).
     * Use {@link #DEDUP_SPAWN} to submit a separate agent job after committing
     * (requires a workstream URL). Use {@link #DEDUP_NONE} to disable
     * deduplication entirely.</p>
     *
     * @param deduplicationMode {@link #DEDUP_LOCAL}, {@link #DEDUP_SPAWN},
     *                          or {@link #DEDUP_NONE}
     */
    public void setDeduplicationMode(String deduplicationMode) {
        this.deduplicationMode = deduplicationMode;
    }

    /**
     * Returns whether the Maven dependency protection rule is active for this job.
     *
     * <p>When active, any {@code <dependency>} additions, removals, or modifications
     * in {@code pom.xml} files detected against the base branch trigger a correction
     * loop that instructs the agent to revert those changes. Changes to other parts
     * of {@code pom.xml} (plugin configuration, properties, etc.) are not affected.</p>
     *
     * @return {@code true} if Maven dependency changes are blocked
     */
    public boolean isEnforceMavenDependencies() {
        return enforceMavenDependencies;
    }

    /**
     * Sets whether the Maven dependency protection rule is active for this job.
     *
     * @param enforceMavenDependencies {@code true} to block {@code <dependency>} changes
     */
    public void setEnforceMavenDependencies(boolean enforceMavenDependencies) {
        this.enforceMavenDependencies = enforceMavenDependencies;
    }

    /**
     * Registers an additional enforcement rule to run after the agent completes
     * its primary work.
     *
     * <p>Custom rules are evaluated after all built-in rules (enforce-changes,
     * deduplication, maven-dependency-protection). Each rule is checked and
     * retried independently up to its configured {@link EnforcementRule#getMaxRetries()}
     * limit.</p>
     *
     * @param rule the rule to add; must not be {@code null}
     */
    public void addEnforcementRule(EnforcementRule rule) {
        if (rule == null) throw new IllegalArgumentException("rule must not be null");
        customEnforcementRules.add(rule);
    }

    /**
     * Returns the Claude Code session ID from the last execution.
     * Can be used to resume the session later.
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * Returns the full output from the last execution.
     */
    public String getOutput() {
        return output;
    }

    /**
     * Returns the exit code from the last execution.
     */
    public int getExitCode() {
        return exitCode;
    }

    /**
     * Builds the full instruction prompt that wraps the user's request
     * with operational context for autonomous execution.
     *
     * <p>Sections are conditionally included based on the job's configuration:
     * Messaging instructions appear only when a workstream URL is configured,
     * GitHub instructions only when the MCP config includes ar-github,
     * commit.txt instructions only when git management is active, and
     * budget/turn/task/workstream context is included when available.</p>
     */
    private String buildInstructionPrompt() {
        return new InstructionPromptBuilder()
                .setPrompt(prompt)
                .setWorkstreamUrl(getWorkstreamUrl())
                .setProtectTestFiles(isProtectTestFiles())
                .setEnforceChanges(enforceChanges)
                .setEnforcementAttempt(enforcementAttempt)
                .setBaseBranch(getBaseBranch())
                .setTargetBranch(getTargetBranch())
                .setWorkingDirectory(getWorkingDirectory())
                .setHasMergeConflicts(hasMergeConflicts())
                .setMergeConflictFiles(getMergeConflictFiles())
                .setMaxBudgetUsd(maxBudgetUsd)
                .setMaxTurns(maxTurns)
                .setTaskId(getTaskId())
                .setPlanningDocument(planningDocument)
                .setDependentRepoPaths(getDependentRepoPaths())
                .setGitHubMcpEnabled(true)
                .setGitTamperingViolation(gitTamperingViolation)
                .build();
    }

    /**
     * Configures the MCP config builder with current job state.
     */
    private void configureMcpBuilder() {
        mcpConfigBuilder.setArManagerUrl(arManagerUrl);
        mcpConfigBuilder.setArManagerToken(arManagerToken);
        mcpConfigBuilder.setPythonCommand(getPythonCommand());
        Path workDir = getWorkingDirectory() != null
            ? Path.of(getWorkingDirectory()) : Path.of(System.getProperty("user.dir"));
        mcpConfigBuilder.setWorkingDirectory(workDir);
    }

    /**
     * Default maximum number of correction attempts per enforcement rule.
     * Used by all built-in rules and as the default for custom rules added via
     * {@link #addEnforcementRule(EnforcementRule)}.
     */
    public static final int DEFAULT_MAX_RULE_RETRIES = 5;

    @Override
    protected void doWork() {
        executeSingleRun();

        // Git integrity violations are handled by onGitTampering() in GitManagedJob;
        // bail out immediately to let that path take over.
        if (!hasAgentCommitted()) {
            runEnforcementRules();
        }
    }

    /**
     * Builds the ordered list of enforcement rules that are currently active for
     * this job, based on the job's configuration flags.
     *
     * <p>Rules are evaluated in declaration order: built-in rules first, then any
     * custom rules registered via {@link #addEnforcementRule(EnforcementRule)}.</p>
     *
     * @return ordered list of active enforcement rules; never {@code null}
     */
    private List<EnforcementRule> buildActiveRules() {
        List<EnforcementRule> rules = new ArrayList<>();
        if (enforceChanges) {
            rules.add(new EnforceChangesRule());
        }
        if (DEDUP_LOCAL.equals(deduplicationMode)) {
            rules.add(new DeduplicationRule());
        }
        if (enforceMavenDependencies) {
            rules.add(new MavenDependencyProtectionRule());
        }
        rules.addAll(customEnforcementRules);
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
     * tampering-detection path in {@link GitManagedJob} handles that case.</p>
     */
    private void runEnforcementRules() {
        for (EnforcementRule rule : buildActiveRules()) {
            if (!rule.isViolated(this)) {
                log("Enforcement rule '" + rule.getName() + "': no violation");
                continue;
            }

            log("Enforcement rule '" + rule.getName() + "': violation detected");
            int attempts = 0;
            while (attempts < rule.getMaxRetries()
                    && rule.isViolated(this)
                    && !hasAgentCommitted()) {
                attempts++;
                log("Enforcement rule '" + rule.getName()
                        + "': correction attempt " + attempts);
                String correctionPrompt = rule.buildCorrectionPrompt(this);
                if (correctionPrompt != null) {
                    runCorrectionSession(correctionPrompt);
                } else {
                    // Re-run with the existing prompt; the job's built-in instruction
                    // context (e.g., enforceChanges) already provides correction guidance.
                    // Only escalate enforcementAttempt for the enforce-changes rule so
                    // that unrelated custom rules returning null do not inflate the counter
                    // and trigger spurious enforcement escalation messaging.
                    if ("enforce-changes".equals(rule.getName())) {
                        enforcementAttempt++;
                        log("Enforcement attempt: " + (enforcementAttempt + 1));
                    }
                    executeSingleRun();
                }
                rule.onCorrectionAttempted(this);
                if (hasAgentCommitted()) {
                    break;
                }
            }

            if (!hasAgentCommitted() && rule.isViolated(this)) {
                warn("Enforcement rule '" + rule.getName() + "': exhausted "
                        + rule.getMaxRetries() + " retries without resolution");
            }
        }
    }

    /**
     * Runs a correction session inline with the specified prompt replacing the
     * primary job's prompt for the duration of the session.
     *
     * <p>The original prompt is always restored in a {@code finally} block.
     * Any commit message written by the primary agent is also saved and
     * restored, preventing correction sessions from overwriting it.</p>
     *
     * @param correctionPrompt the prompt to use for the correction session
     */
    private void runCorrectionSession(String correctionPrompt) {
        String originalPrompt = this.prompt;

        // Preserve the primary agent's commit.txt so the correction session cannot
        // overwrite it.  executeSingleRun() deletes any stale commit.txt at startup,
        // so we read the content now and write it back in finally.
        Path commitFile = resolveWorkingPath("commit.txt");
        String savedCommitMessage = null;
        if (commitFile != null && Files.exists(commitFile)) {
            try {
                savedCommitMessage = Files.readString(commitFile, StandardCharsets.UTF_8);
            } catch (IOException e) {
                warn("Could not read commit.txt before correction session: " + e.getMessage());
            }
        }

        try {
            this.prompt = correctionPrompt;
            executeSingleRun();
        } finally {
            this.prompt = originalPrompt;

            if (commitFile != null) {
                try {
                    if (savedCommitMessage != null) {
                        Files.writeString(commitFile, savedCommitMessage, StandardCharsets.UTF_8);
                        log("Restored primary commit message from commit.txt");
                    } else {
                        Files.deleteIfExists(commitFile);
                    }
                } catch (IOException e) {
                    warn("Could not restore commit.txt after correction session: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Enforcement rule that verifies the agent produced at least one uncommitted
     * file change. Used when {@link #isEnforceChanges()} is {@code true}.
     *
     * <p>Returns {@code null} from {@link #buildCorrectionPrompt} so the framework
     * re-runs the agent with the existing prompt unchanged; the {@code enforceChanges}
     * flag already injects a "code changes are required" message via
     * {@link InstructionPromptBuilder}.</p>
     */
    private static class EnforceChangesRule implements EnforcementRule {
        @Override
        public String getName() { return "enforce-changes"; }

        @Override
        public boolean isViolated(ClaudeCodeJob job) {
            return !job.hasUncommittedChanges() && !job.hasAgentCommitted();
        }

        @Override
        public String buildCorrectionPrompt(ClaudeCodeJob job) {
            // Return null: re-run with the existing prompt.  The enforceChanges
            // flag causes InstructionPromptBuilder to emit the escalating warning.
            return null;
        }
    }

    /**
     * Enforcement rule that detects new Java methods introduced since the base
     * branch and runs a deduplication session to remove any that duplicate
     * existing functionality. Active when {@link #getDeduplicationMode()} is
     * {@link #DEDUP_LOCAL}.
     *
     * <p>This rule uses method-set comparison to determine when to stop looping.
     * Before each correction session, the set of new method names is recorded.
     * After the session, the set is re-extracted. If the sets are identical, the
     * agent had one opportunity and removed nothing — the loop exits immediately.
     * If the set changed (methods were removed), another pass is made to check for
     * remaining duplicates. This approach is deterministic and does not rely on
     * heuristics about file changes or agent output.</p>
     */
    private class DeduplicationRule implements EnforcementRule {

        /**
         * The set of new method names observed at the start of the most recent
         * {@link #isViolated} check. Used to detect whether a correction session
         * successfully removed any methods: if the set is unchanged after a session,
         * the agent produced no removals and the loop exits.
         */
        private Set<String> methodSetBeforeSession = null;

        @Override
        public String getName() { return "deduplication"; }

        @Override
        public boolean isViolated(ClaudeCodeJob job) {
            List<String> newMethods = job.extractNewMethodNames();
            Set<String> currentMethodSet = new LinkedHashSet<>(newMethods);

            if (methodSetBeforeSession != null && currentMethodSet.equals(methodSetBeforeSession)) {
                // The correction session ran but the method set is unchanged.
                // The agent had one shot and removed nothing — exit the loop.
                log("Deduplication: method set unchanged after correction session — no duplicates to remove");
                return false;
            }

            methodSetBeforeSession = currentMethodSet;

            if (!newMethods.isEmpty()) {
                log("Deduplication scan: found " + newMethods.size() + " new method(s)");
            }
            return !newMethods.isEmpty();
        }

        @Override
        public String buildCorrectionPrompt(ClaudeCodeJob job) {
            List<String> newMethods = job.extractNewMethodNames();
            List<String> capped = newMethods.size() > MAX_DEDUP_METHODS
                    ? newMethods.subList(0, MAX_DEDUP_METHODS) : newMethods;
            return buildDeduplicationPrompt(capped,
                    newMethods.size() > MAX_DEDUP_METHODS, newMethods.size());
        }
    }

    /**
     * Enforcement rule that prevents {@code <dependency>} changes in Maven
     * {@code pom.xml} files. Active when {@link #isEnforceMavenDependencies()}
     * is {@code true}.
     *
     * <p>Only {@code <dependency>} element additions, removals, or modifications
     * are flagged. Changes to other {@code pom.xml} content (plugin configuration,
     * properties, etc.) are not affected.</p>
     */
    private static class MavenDependencyProtectionRule implements EnforcementRule {
        @Override
        public String getName() { return "no-maven-dependency-changes"; }

        @Override
        public boolean isViolated(ClaudeCodeJob job) {
            return job.hasMavenDependencyChanges();
        }

        @Override
        public String buildCorrectionPrompt(ClaudeCodeJob job) {
            String baseBranch = job.getBaseBranch() != null ? job.getBaseBranch() : "master";
            StringBuilder sb = new StringBuilder();
            sb.append("MAVEN DEPENDENCY PROTECTION RULE VIOLATION\n\n");
            sb.append("Your changes to one or more pom.xml files add, remove, or modify ");
            sb.append("<dependency> entries. Maven module dependencies are externally ");
            sb.append("controlled and MUST NOT be modified by agents.\n\n");
            sb.append("MANDATORY ACTION: Revert all <dependency> changes in any pom.xml files.\n\n");
            sb.append("Steps to identify and fix the violation:\n");
            sb.append("1. Run: git diff origin/").append(baseBranch)
              .append(" -- '**/pom.xml' 'pom.xml'\n");
            sb.append("   to see exactly what <dependency> lines you added or removed.\n");
            sb.append("2. Use the Edit tool to surgically remove any added <dependency> ");
            sb.append("blocks and restore any removed ones.\n\n");
            sb.append("IMPORTANT — do NOT use git restore, git checkout --, or git reset ");
            sb.append("to revert pom.xml files. Those commands discard ALL your changes ");
            sb.append("to the file, not just the dependency modifications. Use the Edit ");
            sb.append("tool to remove only the <dependency> changes.\n\n");
            sb.append("You MAY keep all non-dependency changes to pom.xml files ");
            sb.append("(plugin configuration, properties, build settings, etc.). ");
            sb.append("Only <dependency> additions, removals, and modifications must be undone.");
            return sb.toString();
        }
    }

    @Override
    protected boolean onGitTampering() {
        String violation = getTamperingDescription();
        warn("Agent tampered with git: " + violation
            + " -- destroying all changes and restarting session");

        // Set the violation message so buildInstructionPrompt() includes
        // the warning in the restarted session's prompt.
        gitTamperingViolation = violation;

        // Re-run the session.  The prompt will now include a stern warning
        // about the violation and the consequences of repeating it.
        executeSingleRun();

        // Clear the violation so it doesn't persist into further retries.
        gitTamperingViolation = null;

        return true;
    }

    /**
     * Executes a single Claude Code session.
     *
     * <p>This method encapsulates the full lifecycle of one agent invocation:
     * tool download, command construction, process execution, and output
     * parsing. It is called once in normal mode and potentially multiple
     * times when enforcement mode is active.</p>
     */
    private void executeSingleRun() {
        // Remove stale commit.txt from any previous run
        Path staleCommitFile = resolveWorkingPath("commit.txt");
        if (staleCommitFile != null && Files.exists(staleCommitFile)) {
            try {
                Files.delete(staleCommitFile);
                log("Removed stale commit.txt");
            } catch (IOException e) {
                warn("Failed to remove stale commit.txt: " + e.getMessage());
            }
        }

        File outputDir = new File("claude-output");
        if (!outputDir.exists()) outputDir.mkdir();

        String outputFile = "claude-output/" + KeyUtils.generateKey() + ".json";

        List<String> command = new ArrayList<>();
        command.add("claude");
        command.add("-p");
        command.add(buildInstructionPrompt());
        command.add("--output-format");
        command.add("json");
        command.add("--allowedTools");
        configureMcpBuilder();
        command.add(mcpConfigBuilder.buildAllowedTools(allowedTools));
        command.add("--max-turns");
        command.add(String.valueOf(maxTurns));

        if (maxBudgetUsd > 0) {
            command.add("--max-budget-usd");
            command.add(String.format("%.2f", maxBudgetUsd));
        }

        log("Starting: " + getTaskString());
        log("Tools: " + allowedTools);
        if (getTargetBranch() != null) {
            log("Target branch: " + getTargetBranch());
        }
        if (enforcementAttempt > 0) {
            log("Enforcement attempt: " + (enforcementAttempt + 1));
        }

        // Verify MCP tool server files exist before launching Claude Code
        Path mcpWorkDir = getWorkingDirectory() != null
            ? Path.of(getWorkingDirectory()) : Path.of(System.getProperty("user.dir"));
        toolsDownloader.verifyMcpToolFiles(mcpWorkDir);

        // MCP config (ar-github always; ar-messages when workstream URL is set)
        command.add("--mcp-config");
        command.add(mcpConfigBuilder.buildMcpConfig());

        try {
            ProcessBuilder pb = new ProcessBuilder(command);

            String workDir = getWorkingDirectory();
            if (workDir != null) {
                pb.directory(new File(workDir));
            }

            // Set resolved workstream URL for MCP servers (ar-messages, ar-github).
            String wsUrl = resolveWorkstreamUrl();
            if (wsUrl != null && !wsUrl.isEmpty()) {
                pb.environment().put("AR_WORKSTREAM_URL", wsUrl);
                log("AR_WORKSTREAM_URL: " + wsUrl);
            }

            GitOperations.augmentPath(pb);

            log("Command: " + String.join(" ", command));
            log("Working directory: " + (workDir != null ? workDir : System.getProperty("user.dir")));

            pb.redirectErrorStream(true);
            pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
            Process process = pb.start();

            long pid = process.pid();
            log("Process started (PID: " + pid + ")");

            StringBuilder outputBuilder = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    outputBuilder.append(line).append("\n");
                    log("[ClaudeCode] " + line);
                }
            }

            log("Process output stream closed, waiting for exit...");
            exitCode = process.waitFor();
            output = outputBuilder.toString();

            // Save output to file
            try (FileWriter writer = new FileWriter(outputFile)) {
                writer.write(output);
            }

            // Extract session ID and timing metrics from JSON output
            extractOutputMetrics(output);

            log("Completed with exit code: " + exitCode);
            log("Output saved to: " + outputFile);

            if (getOutputConsumer() != null) {
                getOutputConsumer().accept(new ClaudeCodeJobOutput(
                    getTaskId(), prompt, output, sessionId, exitCode
                ));
            }

        } catch (IOException | InterruptedException e) {
            warn("Error: " + e.getMessage(), e);
            exitCode = -1;
        }
    }

    /**
     * Checks whether the working directory has uncommitted changes
     * (excluding files in the standard exclusion patterns).
     *
     * <p>Used by the enforcement loop to determine whether the agent
     * produced any meaningful code changes during its session.</p>
     *
     * @return true if there are uncommitted changes to non-excluded files
     */
    private boolean hasUncommittedChanges() {
        try {
            ProcessBuilder pb = new ProcessBuilder(GitOperations.resolveGitCommand(), "status", "--porcelain");
            String workDir = getWorkingDirectory();
            if (workDir != null) {
                pb.directory(new File(workDir));
            }
            pb.redirectErrorStream(true);
            GitOperations.augmentPath(pb);
            Process process = pb.start();
            String statusOutput = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor();

            // Filter out excluded patterns (claude-output, .claude, target, etc.)
            for (String line : statusOutput.split("\n")) {
                if (line.length() > 3) {
                    String file = line.substring(3).trim();
                    if (file.contains(" -> ")) {
                        file = file.split(" -> ")[1];
                    }
                    if (!file.isEmpty()
                            && !file.startsWith("claude-output/")
                            && !file.startsWith(".claude/")
                            && !file.startsWith("target/")
                            && !file.equals("commit.txt")) {
                        return true;
                    }
                }
            }
            return false;
        } catch (IOException | InterruptedException e) {
            warn("Failed to check for uncommitted changes: " + e.getMessage());
            return false;
        }
    }

    /**
     * Checks whether the agent's changes modify {@code <dependency>} entries
     * in any {@code pom.xml} files when compared against the base branch.
     *
     * <p>Detects added or removed lines containing {@code <dependency>} in
     * the diff of {@code pom.xml} files against {@code origin/<baseBranch>}.
     * Changes to other {@code pom.xml} content (plugin configuration, properties,
     * etc.) are not flagged.</p>
     *
     * @return {@code true} if any {@code <dependency>} additions or removals
     *         were detected
     */
    private boolean hasMavenDependencyChanges() {
        String workDir = getWorkingDirectory();
        String baseBranch = getBaseBranch() != null ? getBaseBranch() : "master";

        try {
            // Use a large unified context so that opening <dependency> tags always
            // appear in the hunk even when the changed line is deep inside a long block.
            ProcessBuilder pb = new ProcessBuilder(
                    GitOperations.resolveGitCommand(),
                    "diff", "--unified=50", "origin/" + baseBranch, "--", "**/pom.xml", "pom.xml");
            if (workDir != null) pb.directory(new File(workDir));
            pb.redirectErrorStream(true);
            GitOperations.augmentPath(pb);
            Process p = pb.start();
            String diff = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = p.waitFor();

            if (exitCode != 0) {
                warn("Maven dependency check: git diff exited with code " + exitCode
                    + " — treating as violation (fail closed)");
                return true;
            }

            // Track whether the current context is inside a <dependency> block so that
            // modifications to child elements (e.g. <version>, <groupId>) are detected
            // even when the <dependency> opening tag itself is not on a changed line.
            boolean inDependencyBlock = false;
            for (String line : diff.split("\n")) {
                if (line.startsWith("+++") || line.startsWith("---") || line.startsWith("@@")) {
                    continue;
                }

                String content = line.length() > 0 ? line.substring(1).trim() : "";
                boolean opensBlock = content.contains("<dependency>") || content.contains("<dependency ");
                boolean closesBlock = content.contains("</dependency>");

                if (opensBlock) {
                    inDependencyBlock = true;
                }

                if ((line.startsWith("+") || line.startsWith("-")) && (inDependencyBlock || opensBlock)) {
                    return true;
                }

                if (closesBlock) {
                    inDependencyBlock = false;
                }
            }
        } catch (IOException e) {
            warn("Maven dependency check: failed to diff pom.xml files: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            warn("Maven dependency check: failed to diff pom.xml files: " + e.getMessage());
        }
        return false;
    }

    @Override
    protected JobCompletionEvent createEvent(Exception error) {
        if (error != null) {
            return ClaudeCodeJobEvent.failed(
                getTaskId(), getTaskString(),
                error.getMessage(), error
            );
        } else if (exitCode != 0) {
            return ClaudeCodeJobEvent.failed(
                getTaskId(), getTaskString(),
                "Claude Code exited with code " + exitCode, null
            );
        } else {
            return ClaudeCodeJobEvent.success(getTaskId(), getTaskString());
        }
    }

    @Override
    protected void populateEventDetails(JobCompletionEvent event) {
        if (event instanceof ClaudeCodeJobEvent) {
            ClaudeCodeJobEvent ccEvent = (ClaudeCodeJobEvent) event;
            ccEvent.withClaudeCodeInfo(prompt, sessionId, exitCode);
            ccEvent.withTimingInfo(durationMs, durationApiMs, costUsd, numTurns);
            ccEvent.withSessionDetails(subtype, isError, permissionDenials, deniedToolNames);
        }
    }

    /**
     * Pattern that matches new Java method declarations in a unified diff.
     *
     * <p>Matches lines beginning with {@code +} (new content) followed by
     * optional whitespace, an access modifier, optional other modifiers and
     * generic type parameters, a return type, and then the method name
     * immediately before an opening parenthesis.  Constructors and interface
     * default methods are included intentionally — the deduplication agent
     * decides whether they constitute genuine duplicates.</p>
     */
    private static final Pattern NEW_METHOD_PATTERN = Pattern.compile(
            "^\\+[ \\t]+(?:public|private|protected)\\b.*\\b(\\w+)[ \\t]*\\(");

    /** Maximum number of method names included in a single deduplication prompt. */
    private static final int MAX_DEDUP_METHODS = 50;

    @Override
    protected boolean validateChanges() throws Exception {
        // Test-hiding audit (only when protect-test-files is enabled)
        if (isProtectTestFiles() && !runTestHidingAudit()) {
            return false;
        }

        // Deduplication SPAWN mode: fire-and-forget follow-up job after primary work.
        // Note: DEDUP_LOCAL is handled inline by DeduplicationRule in the enforcement
        // framework (runEnforcementRules), which runs in doWork() before the commit.
        if (DEDUP_SPAWN.equals(deduplicationMode)) {
            submitDeduplicationSpawnJob();
        }

        return true;
    }

    /**
     * Runs the detect-test-hiding.sh audit script against the base branch.
     *
     * @return {@code false} if test-hiding violations were found (exit code 2),
     *         {@code true} otherwise (including script-not-found and other errors)
     * @throws Exception if the process cannot be started
     */
    private boolean runTestHidingAudit() throws Exception {
        Path auditScript = resolveWorkingPath("tools/ci/agent-protection/detect-test-hiding.sh");
        if (auditScript == null || !Files.exists(auditScript)) {
            log("detect-test-hiding.sh not found, skipping validation");
            return true;
        }

        String baseBranch = getBaseBranch() != null ? getBaseBranch() : "master";
        ProcessBuilder pb = new ProcessBuilder("bash", auditScript.toString(),
                "origin/" + baseBranch);
        String workDir = getWorkingDirectory();
        if (workDir != null) {
            pb.directory(new File(workDir));
        }
        pb.redirectErrorStream(true);
        GitOperations.augmentPath(pb);
        Process p = pb.start();
        String auditOutput = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();

        if (code == 2) {
            warn("Test-hiding violations detected - aborting commit:\n" + auditOutput);
            return false;
        } else if (code != 0) {
            warn("detect-test-hiding.sh exited with code " + code + ": " + auditOutput);
        }

        log("Test integrity check passed");
        return true;
    }

    /**
     * Posts a deduplication job to the same workstream via the controller API.
     * Only called when {@link #deduplicationMode} is {@link #DEDUP_SPAWN}.
     *
     * <p>The {@link #DEDUP_LOCAL} mode is handled by {@link DeduplicationRule}
     * inside the enforcement framework ({@link #runEnforcementRules()}), which
     * runs inline before the commit is finalised.</p>
     */
    private void submitDeduplicationSpawnJob() {
        List<String> newMethods = extractNewMethodNames();
        if (newMethods.isEmpty()) {
            log("Deduplication scan: no new Java methods detected");
            return;
        }

        log("Deduplication scan: found " + newMethods.size() + " new method(s) -- spawning follow-up job");
        List<String> capped = newMethods.size() > MAX_DEDUP_METHODS
                ? newMethods.subList(0, MAX_DEDUP_METHODS) : newMethods;
        boolean truncated = newMethods.size() > MAX_DEDUP_METHODS;
        spawnDeduplicationJob(
                buildDeduplicationPrompt(capped, truncated, newMethods.size()),
                newMethods.size());
    }

    /**
     * Posts a deduplication job to the same workstream via the controller API.
     *
     * <p>This is fire-and-forget: errors are logged but do not affect the
     * outcome of the current job.  Requires a workstream URL to be configured.</p>
     *
     * @param dedupPrompt the deduplication prompt
     * @param methodCount the total number of new methods detected
     */
    private void spawnDeduplicationJob(String dedupPrompt, int methodCount) {
        String wsUrl = resolveWorkstreamUrl();
        if (wsUrl == null || wsUrl.isEmpty()) {
            warn("Deduplication mode is 'spawn' but no workstream URL is configured -- skipping");
            return;
        }

        String controllerBase = extractControllerBaseUrl(wsUrl);
        String workstreamId = extractWorkstreamId(wsUrl);
        if (controllerBase == null || workstreamId == null) {
            warn("Cannot parse workstream URL for deduplication job: " + wsUrl);
            return;
        }

        try {
            ObjectNode payload = outputMapper.createObjectNode();
            payload.put("prompt", dedupPrompt);
            payload.put("workstreamId", workstreamId);
            payload.put("description", "Deduplication audit: " + methodCount + " new method(s)");
            payload.put("automated", true);
            String json = outputMapper.writeValueAsString(payload);

            log("Spawning deduplication job on workstream " + workstreamId);
            postJson(controllerBase + "/api/submit", json);
        } catch (Exception e) {
            warn("Failed to spawn deduplication job: " + e.getMessage());
        }
    }

    /**
     * Scans the working tree for new Java method declarations introduced since
     * the base branch.
     *
     * <p>Two sources are checked:
     * <ol>
     *   <li>The unified diff of tracked {@code .java} files against
     *       {@code origin/<baseBranch>} — new lines ({@code +}) that contain
     *       a method declaration are parsed via {@link #NEW_METHOD_PATTERN}.</li>
     *   <li>Untracked {@code .java} files reported by {@code git ls-files --others}
     *       — every method declaration in these entirely new files is included.</li>
     * </ol>
     *
     * @return deduplicated list of new method names, order-preserving
     */
    private List<String> extractNewMethodNames() {
        Set<String> seen = new LinkedHashSet<>();
        String workDir = getWorkingDirectory();
        String baseBranch = getBaseBranch() != null ? getBaseBranch() : "master";

        // Tracked Java files: diff working tree against remote base branch
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    GitOperations.resolveGitCommand(),
                    "diff", "origin/" + baseBranch, "--", "*.java");
            if (workDir != null) pb.directory(new File(workDir));
            pb.redirectErrorStream(true);
            GitOperations.augmentPath(pb);
            Process p = pb.start();
            String diff = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor();
            extractMethodNamesFromDiff(diff, seen);
        } catch (IOException | InterruptedException e) {
            warn("Deduplication scan: failed to diff tracked Java files: " + e.getMessage());
        }

        // Untracked Java files: every method in these files is new
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    GitOperations.resolveGitCommand(),
                    "ls-files", "--others", "--exclude-standard", "--", "*.java");
            if (workDir != null) pb.directory(new File(workDir));
            pb.redirectErrorStream(true);
            GitOperations.augmentPath(pb);
            Process p = pb.start();
            String listing = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor();
            for (String rel : listing.split("\n")) {
                rel = rel.trim();
                if (!rel.isEmpty()) {
                    extractMethodNamesFromFile(rel, workDir, seen);
                }
            }
        } catch (IOException | InterruptedException e) {
            warn("Deduplication scan: failed to list untracked Java files: " + e.getMessage());
        }

        return new ArrayList<>(seen);
    }

    /**
     * Parses new-line entries ({@code +} prefix) from a unified diff and adds
     * any Java method names found to {@code sink}.
     *
     * @param diff  the unified diff output
     * @param sink  the set to add discovered method names to
     */
    private static void extractMethodNamesFromDiff(String diff, Set<String> sink) {
        for (String line : diff.split("\n")) {
            if (line.startsWith("+++") || line.startsWith("---")) {
                continue;
            }
            Matcher m = NEW_METHOD_PATTERN.matcher(line);
            if (m.find()) {
                sink.add(m.group(1));
            }
        }
    }

    /**
     * Reads a Java source file and adds all method names declared with a
     * public/private/protected access modifier to {@code sink}.
     *
     * @param relativePath  path relative to the working directory (or absolute)
     * @param workDir       working directory, or {@code null}
     * @param sink          the set to add discovered method names to
     */
    private void extractMethodNamesFromFile(String relativePath,
                                             String workDir,
                                             Set<String> sink) {
        File file = (workDir != null)
                ? new File(workDir, relativePath) : new File(relativePath);
        if (!file.exists()) return;

        try {
            String source = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            // Synthesise a diff-like representation so the same pattern applies
            for (String line : source.split("\n")) {
                Matcher m = NEW_METHOD_PATTERN.matcher("+ " + line);
                if (m.find()) {
                    sink.add(m.group(1));
                }
            }
        } catch (IOException e) {
            warn("Deduplication scan: cannot read " + relativePath + ": " + e.getMessage());
        }
    }

    /**
     * Builds the aggressive deduplication prompt that is sent to the follow-up
     * agent session.
     *
     * @param methodNames  the (possibly capped) list of new method names
     * @param truncated    {@code true} if the list was capped due to size
     * @param totalCount   the total number of methods found (before capping)
     * @return the full prompt string
     */
    private static String buildDeduplicationPrompt(List<String> methodNames,
                                                    boolean truncated,
                                                    int totalCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("DEDUPLICATION AUDIT — MANDATORY PRE-COMMIT REVIEW\n\n");
        sb.append("A prior agent session has committed changes that introduce the ");
        sb.append("following new methods");
        if (truncated) {
            sb.append(" (showing ").append(methodNames.size())
              .append(" of ").append(totalCount).append(" total)");
        }
        sb.append(":\n\n");
        for (String name : methodNames) {
            sb.append("  - ").append(name).append("\n");
        }
        sb.append("\n");
        sb.append("Your job is to determine whether any of these methods duplicate ");
        sb.append("functionality that already exists elsewhere in the codebase. ");
        sb.append("This is a mandatory review step — do not skip it and do not ");
        sb.append("conclude quickly that a method is unique without actually searching.\n\n");
        sb.append("CRITICAL ASSUMPTION: For every method in the list above, you MUST ");
        sb.append("assume it is a clone of an existing method until you have proven ");
        sb.append("otherwise. This is not a pessimistic assumption — it is statistically ");
        sb.append("accurate. The majority of methods introduced by agent sessions are ");
        sb.append("duplicates of functionality that already exists elsewhere. The agent ");
        sb.append("re-implemented things it could not find by search. The clone may not ");
        sb.append("be an exact copy: it may be renamed, slightly generalised, or placed ");
        sb.append("in a different class — but it performs the same operation on the same ");
        sb.append("data.\n\n");
        sb.append("For each method:\n");
        sb.append("1. Search the codebase for methods that perform the same logical ");
        sb.append("operation. Use Grep to search by keyword, not just by name.\n");
        sb.append("2. If a duplicate exists: remove the new method entirely and replace ");
        sb.append("all call sites with the existing method.\n");
        sb.append("3. Only after a thorough search may you conclude a method is ");
        sb.append("genuinely new.\n\n");
        sb.append("IMPORTANT — editing rules:\n");
        sb.append("- Use the Edit tool to remove duplicate methods surgically. ");
        sb.append("Remove only the duplicate method body and its declaration; ");
        sb.append("preserve all other changes in the file.\n");
        sb.append("- NEVER use git restore, git checkout --, git reset, or any ");
        sb.append("other git command to revert a file. Those commands discard ALL ");
        sb.append("changes in that file, not just the duplicate method, and will ");
        sb.append("destroy work that must be preserved.\n\n");
        sb.append("Do not rationalise keeping a duplicate because it is 'slightly ");
        sb.append("different'. Slight differences are how duplicates hide. If the ");
        sb.append("logical purpose is the same, merge them. The codebase already has ");
        sb.append("too many near-identical copies of the same logic; every one you ");
        sb.append("remove improves maintainability for every future session.");
        return sb.toString();
    }

    /**
     * Extracts the controller base URL (scheme + host + port) from a workstream URL.
     *
     * <p>Workstream URLs follow the pattern
     * {@code http://host:port/api/workstreams/{id}/jobs/{jobId}}.
     * This method returns everything before {@code /api/workstreams/}.</p>
     *
     * @param workstreamUrl the full workstream URL
     * @return the controller base URL, or {@code null} if the URL cannot be parsed
     */
    static String extractControllerBaseUrl(String workstreamUrl) {
        int idx = workstreamUrl.indexOf("/api/workstreams/");
        if (idx < 0) return null;
        return workstreamUrl.substring(0, idx);
    }

    /**
     * Extracts the workstream identifier from a workstream URL.
     *
     * <p>Workstream URLs follow the pattern
     * {@code http://host:port/api/workstreams/{id}/jobs/{jobId}}.
     * This method returns the {@code {id}} segment.</p>
     *
     * @param workstreamUrl the full workstream URL
     * @return the workstream ID, or {@code null} if the URL cannot be parsed
     */
    static String extractWorkstreamId(String workstreamUrl) {
        int start = workstreamUrl.indexOf("/api/workstreams/");
        if (start < 0) return null;
        start += "/api/workstreams/".length();
        int end = workstreamUrl.indexOf("/", start);
        return end < 0 ? workstreamUrl.substring(start) : workstreamUrl.substring(start, end);
    }

    @Override
    protected String getCommitMessage() {
        // Check if the agent wrote a commit.txt
        Path commitFile = resolveWorkingPath("commit.txt");
        if (commitFile != null && Files.exists(commitFile)) {
            try {
                String agentMessage = Files.readString(commitFile, StandardCharsets.UTF_8).trim();
                if (!agentMessage.isEmpty()) {
                    log("Using commit message from commit.txt");
                    return agentMessage;
                }
            } catch (IOException e) {
                warn("Failed to read commit.txt: " + e.getMessage());
            }
        }

        // Fallback: generate commit message from prompt
        StringBuilder msg = new StringBuilder();
        msg.append("Claude Code: ");

        String summary = prompt;
        if (summary.length() > 72) {
            summary = summary.substring(0, 69) + "...";
        }
        msg.append(summary);

        msg.append("\n\nPrompt: ").append(prompt);

        if (sessionId != null) {
            msg.append("\nSession: ").append(sessionId);
        }

        msg.append("\nExit code: ").append(exitCode);

        return msg.toString();
    }

    /**
     * Resolves a path relative to the working directory.
     *
     * @param filename the path relative to the working directory
     * @return the resolved {@link Path}
     */
    private Path resolveWorkingPath(String filename) {
        String workDir = getWorkingDirectory();
        if (workDir != null) {
            return Path.of(workDir, filename);
        }
        return Path.of(filename);
    }

    /**
     * Extracts session ID, timing metrics, stop reason, and permission denials
     * from the Claude Code JSON output using Jackson.
     *
     * @param jsonOutput the raw JSON output from Claude Code
     */
    private void extractOutputMetrics(String jsonOutput) {
        if (jsonOutput == null || jsonOutput.isEmpty()) return;

        // Claude Code with --output-format json emits NDJSON: one JSON object
        // per line. Locate the result object (type=result) and extract from that.
        String resultJson = JsonFieldExtractor.extractLastJsonObject(jsonOutput, "result");
        if (resultJson == null) {
            resultJson = jsonOutput;
        }

        try {
            JsonNode root = outputMapper.readTree(resultJson);

            sessionId = getTextOrNull(root, "session_id");

            durationMs = root.path("duration_ms").asLong(0);
            durationApiMs = root.path("duration_api_ms").asLong(0);
            numTurns = root.path("num_turns").asInt(0);

            costUsd = root.path("total_cost_usd").asDouble(0.0);
            if (costUsd == 0.0) {
                costUsd = root.path("cost_usd").asDouble(0.0);
            }

            subtype = getTextOrNull(root, "subtype");
            isError = root.path("is_error").asBoolean(false);

            // Count permission_denials array entries and extract denied tool names
            JsonNode denials = root.get("permission_denials");
            if (denials != null && denials.isArray()) {
                permissionDenials = denials.size();
                deniedToolNames = new ArrayList<>();
                for (JsonNode denial : denials) {
                    JsonNode toolNode = denial.get("tool");
                    if (toolNode != null && toolNode.isTextual()) {
                        deniedToolNames.add(toolNode.asText());
                    }
                }
            } else {
                permissionDenials = 0;
            }
        } catch (Exception e) {
            warn("Failed to parse output metrics: " + e.getMessage());
        }
    }

    /**
     * Returns the text value of a JSON field, or {@code null} if the field is
     * absent or not a text node.
     *
     * @param node   the parent JSON object node
     * @param field  the field name to look up
     * @return       the string value, or {@code null}
     */
    private static String getTextOrNull(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return (child != null && child.isTextual()) ? child.asText() : null;
    }

    @Override
    public String encode() {
        StringBuilder sb = new StringBuilder();
        sb.append(super.encode());
        sb.append("::prompt:=").append(base64Encode(prompt));
        sb.append("::tools:=").append(base64Encode(allowedTools));
        sb.append("::maxTurns:=").append(maxTurns);
        sb.append("::maxBudget:=").append(maxBudgetUsd);
        if (arManagerUrl != null) {
            sb.append("::arManagerUrl:=").append(base64Encode(arManagerUrl));
        }
        if (arManagerToken != null) {
            sb.append("::arManagerToken:=").append(base64Encode(arManagerToken));
        }
        if (planningDocument != null) {
            sb.append("::planDoc:=").append(base64Encode(planningDocument));
        }
        sb.append("::protectTests:=").append(isProtectTestFiles());
        sb.append("::enforceChanges:=").append(enforceChanges);
        if (deduplicationMode != null) {
            sb.append("::dedupMode:=").append(deduplicationMode);
        }
        if (enforceMavenDependencies) {
            sb.append("::enforceMavenDeps:=true");
        }
        return sb.toString();
    }

    @Override
    public void set(String key, String value) {
        switch (key) {
            case "prompt":
                this.prompt = base64Decode(value);
                break;
            case "tools":
                this.allowedTools = base64Decode(value);
                break;
            case "maxTurns":
                this.maxTurns = Integer.parseInt(value);
                break;
            case "maxBudget":
                this.maxBudgetUsd = Double.parseDouble(value);
                break;
            case "arManagerUrl":
                this.arManagerUrl = base64Decode(value);
                break;
            case "arManagerToken":
                this.arManagerToken = base64Decode(value);
                break;
            case "planDoc":
                this.planningDocument = base64Decode(value);
                break;
            case "protectTests":
                setProtectTestFiles(Boolean.parseBoolean(value));
                break;
            case "enforceChanges":
                this.enforceChanges = Boolean.parseBoolean(value);
                break;
            case "dedupMode":
                this.deduplicationMode = value;
                break;
            case "enforceMavenDeps":
                this.enforceMavenDependencies = Boolean.parseBoolean(value);
                break;
            default:
                // Delegate to parent for git-related properties
                super.set(key, value);
        }
    }

    /**
     * Output record produced by a completed {@link ClaudeCodeJob}.
     */
    public static class ClaudeCodeJobOutput extends JobOutput {
        /** The prompt that was submitted to Claude Code for this job. */
        private final String prompt;
        /** The session identifier assigned by Claude Code. */
        private final String sessionId;
        /** The process exit code returned by the Claude Code process. */
        private final int exitCode;

        /**
         * Constructs a new {@link ClaudeCodeJobOutput}.
         *
         * @param taskId     the task identifier
         * @param prompt     the prompt submitted to Claude Code
         * @param output     the raw text output produced by Claude Code
         * @param sessionId  the Claude Code session identifier
         * @param exitCode   the process exit code
         */
        public ClaudeCodeJobOutput(String taskId, String prompt, String output,
                                   String sessionId, int exitCode) {
            super(taskId, "", "", output);
            this.prompt = prompt;
            this.sessionId = sessionId;
            this.exitCode = exitCode;
        }

        /**
         * Returns the prompt that was submitted to Claude Code.
         *
         * @return the prompt string
         */
        public String getPrompt() { return prompt; }

        /**
         * Returns the Claude Code session identifier.
         *
         * @return the session ID
         */
        public String getSessionId() { return sessionId; }

        /**
         * Returns the process exit code returned by Claude Code.
         *
         * @return the exit code (0 typically indicates success)
         */
        public int getExitCode() { return exitCode; }

        /**
         * Returns a human-readable summary of this output record.
         *
         * @return a string including the task ID, exit code, and session ID
         */
        @Override
        public String toString() {
            return "ClaudeCodeJobOutput{taskId=" + getTaskId() + ", exitCode=" + exitCode +
                   ", sessionId=" + sessionId + "}";
        }
    }

    /**
     * Backward-compatible alias for {@link ClaudeCodeJobFactory}.
     *
     * <p>New code should reference {@link ClaudeCodeJobFactory} directly.
     * This subclass exists so that existing call sites using
     * {@code new ClaudeCodeJob.Factory(...)} and serialized wire-format
     * strings containing {@code ClaudeCodeJob$Factory} continue to work.</p>
     */
    public static class Factory extends ClaudeCodeJobFactory {
        /**
         * Default constructor for deserialization.
         */
        public Factory() { super(); }

        /**
         * Creates a factory with the specified prompts.
         *
         * @param prompts the prompts to process
         */
        public Factory(String... prompts) { super(prompts); }

        /**
         * Creates a factory with the specified prompts list.
         *
         * @param prompts the list of prompts to process
         */
        public Factory(List<String> prompts) { super(prompts); }
    }
}
