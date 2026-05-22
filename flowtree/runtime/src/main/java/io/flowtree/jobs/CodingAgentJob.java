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

import io.flowtree.job.Job;
import io.flowtree.jobs.agent.AgentRunRequest;
import io.flowtree.jobs.agent.AgentRunResult;
import io.flowtree.jobs.agent.AgentRunner;
import io.flowtree.jobs.agent.AgentRunnerRegistry;
import io.flowtree.jobs.agent.ClaudeCodeRunner;
import io.flowtree.jobs.agent.Phase;
import org.almostrealism.util.KeyUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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
 * @author Michael Murray
 * @see GitManagedJob
 * @see CodingAgentJobFactory
 */
public class CodingAgentJob extends GitManagedJob {
    /** Sentinel string used to delimit multiple prompts in the serialized wire format. */
    public static final String PROMPT_SEPARATOR = ";;PROMPT;;";
    /** Default comma-separated list of tools permitted for Claude Code sessions. */
    public static final String DEFAULT_TOOLS = "Read,Edit,Write,Bash,Glob,Grep";

    /**
     * Valid values for the {@code --effort} flag exposed by the Claude Code runner.
     *
     * <p>Pluggable agent runners may accept different effort vocabularies; this
     * list is retained on the orchestrator for backwards compatibility with
     * callers (Workstream / WorkstreamConfig) that validate before submission.
     * The canonical home is now {@link ClaudeCodeRunner#VALID_EFFORT_LEVELS}.</p>
     */
    public static final List<String> VALID_EFFORT_LEVELS = ClaudeCodeRunner.VALID_EFFORT_LEVELS;

    /**
     * Accepted values for the {@code --model} flag exposed by the Claude Code runner.
     *
     * <p>The canonical home is {@link ClaudeCodeRunner#VALID_MODELS}; this alias
     * preserves the old API surface for callers that pre-validate inputs.</p>
     */
    public static final List<String> VALID_MODELS = ClaudeCodeRunner.VALID_MODELS;

    /**
     * Deduplication mode that runs an inline Claude Code session before the
     * commit is finalised.  The session receives an aggressive prompt listing
     * all new method names and is instructed to remove any duplicates it finds.
     * This mode is safe to test incrementally because it executes within the
     * existing job lifecycle and cannot spawn additional jobs.
     */
    public static final String DEDUP_LOCAL = "local";

    /**
     * Deduplication mode that submits a separate {@link CodingAgentJob} to the
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

    /** Default per-job deduplication pass cap; override via {@link #setMaxDeduplicationPasses(int)}. */
    public static final int DEFAULT_MAX_DEDUP_PASSES = 2;

    /** Default per-job review pass cap; override via {@link #setMaxReviewPasses(int)}. */
    public static final int DEFAULT_MAX_REVIEW_PASSES = ReviewRule.DEFAULT_MAX_PASSES;
    /** Default per-job post-completion pass cap; override via {@link #setMaxPostCompletionPasses(int)}. */
    public static final int DEFAULT_MAX_POST_COMPLETION_PASSES = PostCompletionCommandRule.DEFAULT_MAX_PASSES;

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
    /** Model alias or full name passed via {@code --model}; {@code null} uses the CLI default. */
    private String model;
    /** Effort/thinking level passed via {@code --effort}; {@code null} uses the CLI default. */
    private String effort;
    /** HTTP base URL of the ar-manager service, or {@code null} if not configured. */
    private String arManagerUrl;
    /** Bearer token for authenticating against the ar-manager service. */
    private String arManagerToken;
    /** Pushed-tools JSON; null when no controller is in the loop. */
    private String pushedToolsConfig;
    /** Optional planning document text injected into the Claude Code system prompt. */
    private String planningDocument;
    /** Whether the job must produce at least one staged file change to succeed. */
    private boolean enforceChanges;
    /** Number of times enforcement has been re-attempted after an empty commit. */
    private int enforcementAttempt;
    /** Enforcement rule name for the current correction session; {@code null} during primary runs. */
    private String currentActivity;
    /** Description of a git-tampering rule violation detected during this job. */
    private String gitTamperingViolation;
    /** Stdout silence duration after which the Claude subprocess is killed (default 20 min). */
    private long inactivityTimeoutMillis = TimeUnit.MINUTES.toMillis(20);
    /** Maximum relaunches of Claude after inactivity-triggered kills (default 3). */
    private int maxInactivityRestarts = 3;
    /** Number of inactivity-triggered relaunches in the current run; resets to 0 after the run. */
    private int inactivityRestartAttempt;
    /** Set by the monitor when it kills the Claude subprocess; consumed by the retry loop. */
    private volatile boolean wasKilledForInactivity;
    /**
     * Controls post-work deduplication behaviour.
     * {@code null} (the default) disables deduplication — the factory sets
     * this to {@link #DEDUP_LOCAL} when creating jobs.
     * {@link #DEDUP_SPAWN} submits a follow-up job to the same workstream.
     * {@link #DEDUP_NONE} also disables deduplication explicitly.
     */
    private String deduplicationMode;

    /** Per-job cap on deduplication passes; passed to {@link DeduplicationRule}. */
    private int maxDeduplicationPasses = DEFAULT_MAX_DEDUP_PASSES;

    /** When {@code true}, pom.xml {@code <dependency>} changes trigger a correction loop. */
    private boolean enforceMavenDependencies;

    /** When {@code true} (the default), new files are reviewed for correct module placement. */
    private boolean enforceOrganizationalPlacement = true;

    /** When {@code true} (the default), a second-pass review session runs after primary work. */
    private boolean reviewEnabled = true;
    /** Per-job cap on review passes; passed to {@link ReviewRule}. */
    private int maxReviewPasses = DEFAULT_MAX_REVIEW_PASSES;
    /** Active {@link ReviewRule} instance after {@link #buildActiveRules()} runs; owns review telemetry. */
    private ReviewRule activeReviewRule;

    /** Shell command run after the agent completes; non-empty activates {@link PostCompletionCommandRule}. */
    private String postCompletionCommand;

    /** Working directory for {@link #postCompletionCommand}; {@code null} uses the job's working directory. */
    private String postCompletionWorkingDir;

    /** Timeout in seconds for {@link #postCompletionCommand}; defaults to {@link PostCompletionCommandRule#DEFAULT_TIMEOUT_SECONDS}. */
    private int postCompletionTimeoutSeconds = PostCompletionCommandRule.DEFAULT_TIMEOUT_SECONDS;

    /** Per-job post-completion pass cap; passed to {@link PostCompletionCommandRule}. */
    private int maxPostCompletionPasses = DEFAULT_MAX_POST_COMPLETION_PASSES;
    /** {@code true} when the pass cap was exhausted without a zero exit. */
    private boolean postCompletionCapHit;

    /** Additional enforcement rules from {@link #addEnforcementRule}; built-ins are created in {@link #buildActiveRules()}. */
    private final List<EnforcementRule> customEnforcementRules = new ArrayList<>();

    /**
     * Legacy single-runner field retained for backwards source compatibility.
     *
     * <p>Mirrors {@link #defaultRunner}. {@link #setRunnerName(String)} updates
     * the default runner so that pre-Phase-2 callers continue to work without
     * any awareness of the per-phase map.</p>
     */
    private String runnerName = AgentRunnerRegistry.CLAUDE;

    /** Default {@link AgentRunner} name used when a phase has no explicit override. */
    private String defaultRunner = AgentRunnerRegistry.CLAUDE;

    /** Per-phase {@link AgentRunner} overrides; empty when only the default applies. */
    private final Map<Phase, String> runnerByPhase = new EnumMap<>(Phase.class);

    /** Builder used to assemble the MCP tool configuration JSON for Claude Code. */
    private final McpConfigBuilder mcpConfigBuilder = new McpConfigBuilder();
    /** Downloads pushed MCP tool source files before each agent launch. */
    private final ManagedToolsDownloader toolsDownloader = new ManagedToolsDownloader(mcpConfigBuilder);
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
    /** How the commit message was produced: {@code "agent"}, {@code "prompt_fallback"}, or {@code "commit_rule_recovered"}. */
    private String commitMessageSource;

    /** Wall-clock instant at which {@link #doWork()} began; used to filter abandoned-test-run scans. */
    private Instant sessionStartedAt;

    /**
     * Default constructor for deserialization.
     */
    public CodingAgentJob() {
        this.allowedTools = DEFAULT_TOOLS;
        this.maxTurns = 50;
        this.maxBudgetUsd = 10.0;
    }

    /**
     * Creates a new CodingAgentJob with the specified prompt.
     *
     * @param taskId  the task ID for tracking
     * @param prompt  the prompt to send to Claude Code
     */
    public CodingAgentJob(String taskId, String prompt) {
        super(taskId);
        this.allowedTools = DEFAULT_TOOLS;
        this.maxTurns = 50;
        this.maxBudgetUsd = 10.0;
        this.prompt = prompt;
    }

    /**
     * Creates a new CodingAgentJob with the specified prompt and tools.
     *
     * @param taskId       the task ID for tracking
     * @param prompt       the prompt to send to Claude Code
     * @param allowedTools comma-separated list of allowed tools (e.g., "Read,Edit,Bash")
     */
    public CodingAgentJob(String taskId, String prompt, String allowedTools) {
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

    /** Returns a short display summary for this job; description if set, else {@link #summarizePrompt(String)}. */
    public String getDisplaySummary() {
        if (description != null && !description.isEmpty()) {
            return description;
        }
        return summarizePrompt(prompt);
    }

    /** Short display string for {@code prompt}: returned as-is if ≤80 chars, otherwise formatted as character count. */
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

    /** Returns the Claude Code model, or {@code null} to use the CLI default. */
    public String getModel() { return model; }

    /**
     * Sets the Claude Code model.  Validated against {@link #VALID_MODELS} so
     * an unknown value fails at the caller instead of silently 404-ing the
     * dispatched subprocess.
     *
     * @param model a value from {@link #VALID_MODELS}, or {@code null}/empty
     * @throws IllegalArgumentException if not a recognised identifier
     */
    public void setModel(String model) {
        if (model == null || model.isEmpty()) { this.model = null; return; }
        if (!VALID_MODELS.contains(model)) {
            throw new IllegalArgumentException("Invalid model '" + model
                    + "'. Must be one of " + VALID_MODELS);
        }
        this.model = model;
    }

    /** Returns the effort/thinking level, or {@code null} to use the CLI default. */
    public String getEffort() { return effort; }

    /**
     * Sets the effort/thinking level. Fails loud for unknown values.
     *
     * @param effort one of {@link #VALID_EFFORT_LEVELS}, or {@code null}/empty
     * @throws IllegalArgumentException if not a valid level
     */
    public void setEffort(String effort) {
        if (effort == null || effort.isEmpty()) { this.effort = null; return; }
        if (!VALID_EFFORT_LEVELS.contains(effort)) {
            throw new IllegalArgumentException("Invalid effort level '" + effort
                    + "'. Must be one of " + VALID_EFFORT_LEVELS);
        }
        this.effort = effort;
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

    /** Returns the pushed-tools configuration JSON, or {@code null}. */
    public String getPushedToolsConfig() { return pushedToolsConfig; }

    /** Sets the pushed-tools configuration JSON (may be {@code null}). */
    public void setPushedToolsConfig(String pushedToolsConfig) {
        this.pushedToolsConfig = pushedToolsConfig;
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

    /** Returns the per-job deduplication pass cap; defaults to {@link #DEFAULT_MAX_DEDUP_PASSES}. */
    public int getMaxDeduplicationPasses() { return maxDeduplicationPasses; }
    /** Sets the per-job deduplication pass cap; must be positive (see {@link #DEFAULT_MAX_DEDUP_PASSES}). */
    public void setMaxDeduplicationPasses(int maxDeduplicationPasses) {
        if (maxDeduplicationPasses <= 0)
            throw new IllegalArgumentException("maxDeduplicationPasses must be positive, got: " + maxDeduplicationPasses);
        this.maxDeduplicationPasses = maxDeduplicationPasses;
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
     * Returns whether the organizational placement rule is active for this job.
     *
     * <p>When active, a correction session is run after the agent's primary work to
     * verify that new files are placed at the appropriate level of the module hierarchy.
     * If the agent moves no files after reviewing placement, the rule considers the
     * placement correct and exits.</p>
     *
     * @return {@code true} if organizational placement enforcement is enabled (the default)
     */
    public boolean isEnforceOrganizationalPlacement() {
        return enforceOrganizationalPlacement;
    }

    /**
     * Sets whether the organizational placement rule is active for this job.
     *
     * @param enforceOrganizationalPlacement {@code false} to disable placement enforcement
     */
    public void setEnforceOrganizationalPlacement(boolean enforceOrganizationalPlacement) {
        this.enforceOrganizationalPlacement = enforceOrganizationalPlacement;
    }

    /** Returns whether the {@link ReviewRule} (second-pass sanity check) is active; default {@code true}. */
    public boolean isReviewEnabled() { return reviewEnabled; }
    /** Sets whether the {@link ReviewRule} is active for this job; {@code false} to skip the review phase. */
    public void setReviewEnabled(boolean reviewEnabled) { this.reviewEnabled = reviewEnabled; }
    /** Returns the per-job review pass cap; defaults to {@link #DEFAULT_MAX_REVIEW_PASSES}. */
    public int getMaxReviewPasses() { return maxReviewPasses; }
    /** Sets the per-job review pass cap; must be positive. */
    public void setMaxReviewPasses(int maxReviewPasses) {
        if (maxReviewPasses <= 0)
            throw new IllegalArgumentException("maxReviewPasses must be positive, got: " + maxReviewPasses);
        this.maxReviewPasses = maxReviewPasses;
    }

    /** Returns the post-completion command; non-empty activates {@link PostCompletionCommandRule}. */
    public String getPostCompletionCommand() { return postCompletionCommand; }

    /** Sets the post-completion command; non-empty activates {@link PostCompletionCommandRule}. */
    public void setPostCompletionCommand(String postCompletionCommand) { this.postCompletionCommand = postCompletionCommand; }

    /** Returns the per-job post-completion pass cap; defaults to {@link #DEFAULT_MAX_POST_COMPLETION_PASSES}. */
    public int getMaxPostCompletionPasses() { return maxPostCompletionPasses; }
    /** Sets the per-job post-completion pass cap; must be positive (see {@link #DEFAULT_MAX_POST_COMPLETION_PASSES}). */
    public void setMaxPostCompletionPasses(int maxPostCompletionPasses) {
        if (maxPostCompletionPasses <= 0)
            throw new IllegalArgumentException("maxPostCompletionPasses must be positive, got: " + maxPostCompletionPasses);
        this.maxPostCompletionPasses = maxPostCompletionPasses;
    }

    /** Returns the working directory for the post-completion command; {@code null} uses the job's working directory. */
    public String getPostCompletionWorkingDir() { return postCompletionWorkingDir; }

    /** Sets the working directory for the post-completion command; {@code null} uses the job's working directory. */
    public void setPostCompletionWorkingDir(String postCompletionWorkingDir) { this.postCompletionWorkingDir = postCompletionWorkingDir; }

    /** Returns the post-completion command timeout in seconds; defaults to {@link PostCompletionCommandRule#DEFAULT_TIMEOUT_SECONDS}. */
    public int getPostCompletionTimeoutSeconds() { return postCompletionTimeoutSeconds; }

    /** Sets the post-completion command timeout in seconds. */
    public void setPostCompletionTimeoutSeconds(int postCompletionTimeoutSeconds) { this.postCompletionTimeoutSeconds = postCompletionTimeoutSeconds; }

    /** Sets the current correction-session rule name; null for primary work. Package-private for tests. */
    void setCurrentActivity(String currentActivity) { this.currentActivity = currentActivity; }

    /**
     * Returns the name of the {@link AgentRunner} dispatching this job's
     * sessions when no per-phase override is set. Defaults to
     * {@link AgentRunnerRegistry#CLAUDE}.
     *
     * <p>Equivalent to {@link #getDefaultRunner()}; retained as a legacy alias
     * so that pre-Phase-2 callers continue to compile.</p>
     *
     * @return the runner identifier
     */
    public String getRunnerName() { return runnerName; }

    /**
     * Sets the name of the {@link AgentRunner} that will dispatch this job's
     * sessions when no per-phase override is configured. The value is
     * validated against {@link AgentRunnerRegistry#available()} so
     * misconfiguration fails at the caller rather than during dispatch.
     *
     * <p>Equivalent to {@link #setDefaultRunner(String)}; retained as a legacy
     * alias. Updating one updates the other so {@link #getRunnerForPhase}
     * falls back consistently.</p>
     *
     * @param runnerName a registered runner identifier (e.g.
     *                   {@link AgentRunnerRegistry#CLAUDE}); {@code null} or empty
     *                   resets to the Claude runner
     * @throws IllegalArgumentException when the runner is not registered
     */
    public void setRunnerName(String runnerName) {
        if (runnerName == null || runnerName.isEmpty()) {
            this.runnerName = AgentRunnerRegistry.CLAUDE;
            this.defaultRunner = AgentRunnerRegistry.CLAUDE;
            return;
        }
        AgentRunnerRegistry.validateName(runnerName);
        this.runnerName = runnerName;
        this.defaultRunner = runnerName;
    }

    /**
     * Returns the default runner used when {@link #getRunnerForPhase(Phase)}
     * has no explicit override for a phase.
     *
     * @return the default runner identifier, never {@code null}
     */
    public String getDefaultRunner() { return defaultRunner; }

    /**
     * Sets the default runner used when {@link #getRunnerForPhase(Phase)} has
     * no explicit override for a phase. Validated against
     * {@link AgentRunnerRegistry#available()}.
     *
     * @param runnerName a registered runner identifier; {@code null}/empty
     *                   resets to {@link AgentRunnerRegistry#CLAUDE}
     * @throws IllegalArgumentException when the runner is not registered
     */
    public void setDefaultRunner(String runnerName) {
        setRunnerName(runnerName);
    }

    /**
     * Returns the {@link AgentRunner} name to use for {@code phase}. Falls
     * back to {@link #getDefaultRunner()} when no override is set.
     *
     * @param phase the lifecycle phase being dispatched
     * @return the runner identifier; never {@code null}
     */
    public String getRunnerForPhase(Phase phase) {
        if (phase == null) return defaultRunner;
        return runnerByPhase.getOrDefault(phase, defaultRunner);
    }

    /**
     * Sets the runner used for {@code phase}, overriding the default. Passing
     * a {@code null}/empty runner clears any existing override and lets the
     * default take effect.
     *
     * @param phase      the phase to configure
     * @param runnerName a registered runner identifier, or {@code null}/empty
     *                   to clear the override
     * @throws IllegalArgumentException when {@code phase} is {@code null} or
     *                                  {@code runnerName} is not registered
     */
    public void setRunnerForPhase(Phase phase, String runnerName) {
        if (phase == null) throw new IllegalArgumentException("phase must not be null");
        if (runnerName == null || runnerName.isEmpty()) {
            runnerByPhase.remove(phase);
            return;
        }
        AgentRunnerRegistry.validateName(runnerName);
        runnerByPhase.put(phase, runnerName);
    }

    /**
     * Returns an immutable snapshot of the per-phase runner overrides.
     *
     * @return the override map; empty when no overrides are set
     */
    public Map<Phase, String> getRunnerByPhase() {
        return new EnumMap<>(runnerByPhase);
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

    /** Number of files modified during the most recent review session. */
    public int getReviewFilesModified() { return activeReviewRule != null ? activeReviewRule.getFilesModified() : 0; }
    /** Number of {@code memory_store} calls observed during the most recent review session. */
    public int getReviewMemoriesStored() { return activeReviewRule != null ? activeReviewRule.getMemoriesStored() : 0; }
    /** Whether the most recent review session exited with code 0. */
    public boolean isReviewExitedCleanly() { return activeReviewRule != null && activeReviewRule.isExitedCleanly(); }
    /** Whether at least one review session ran during this job. */
    public boolean isReviewRan() { return activeReviewRule != null && activeReviewRule.hasRun(); }

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
     * with operational context for autonomous execution. When this job is
     * running an enforcement-rule correction session (non-null
     * {@link #currentActivity}), the outer {@code enforceChanges} preamble
     * and the enforcement-attempt retry preamble are suppressed so rule
     * correction prompts (which may accept "no changes" as resolution)
     * are not contradicted by the harness preamble.
     */
    String buildInstructionPrompt() {
        boolean inRuleCorrection = currentActivity != null && !currentActivity.isEmpty();
        return new InstructionPromptBuilder()
                .setPrompt(prompt)
                .setWorkstreamUrl(getWorkstreamUrl())
                .setProtectTestFiles(isProtectTestFiles())
                .setEnforceChanges(enforceChanges)
                .setEnforcementAttempt(enforcementAttempt)
                .setCorrectionSession(inRuleCorrection)
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
                .setInactivityRestartAttempt(inactivityRestartAttempt)
                .build();
    }

    /**
     * Configures the MCP config builder with current job state.
     */
    private void configureMcpBuilder() {
        mcpConfigBuilder.setArManagerUrl(arManagerUrl);
        mcpConfigBuilder.setArManagerToken(arManagerToken);
        mcpConfigBuilder.setPushedToolsConfig(pushedToolsConfig);
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

    /**
     * Hard cap on total correction attempts across all rules in
     * {@link #runEnforcementRules()}; bounds the otherwise-unbounded outer
     * loop so a chronically broken agent cannot spin forever.
     */
    public static final int DEFAULT_MAX_TOTAL_ENFORCEMENT_ATTEMPTS = 25;

    @Override
    protected void doWork() {
        if (sessionStartedAt == null) sessionStartedAt = Instant.now();
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
        activeReviewRule = reviewEnabled ? new ReviewRule(maxReviewPasses) : null;
        if (activeReviewRule != null) rules.add(activeReviewRule);
        if (DEDUP_LOCAL.equals(deduplicationMode)) {
            rules.add(new DeduplicationRule(maxDeduplicationPasses));
        }
        if (enforceOrganizationalPlacement) {
            rules.add(new OrganizationalPlacementRule());
        }
        if (postCompletionCommand != null && !postCompletionCommand.isEmpty()) {
            rules.add(new PostCompletionCommandRule(
                    postCompletionCommand,
                    postCompletionWorkingDir,
                    postCompletionTimeoutSeconds,
                    maxPostCompletionPasses));
        }
        if (enforceMavenDependencies) {
            rules.add(new MavenDependencyProtectionRule());
        }
        rules.addAll(customEnforcementRules);
        // Always last: verifies commit.txt is present and agent-authored.
        if (getTargetBranch() != null && !getTargetBranch().isEmpty()) {
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
     * tampering-detection path in {@link GitManagedJob} handles that case.</p>
     */
    void runEnforcementRules() {
        List<EnforcementRule> rules = buildActiveRules();
        int totalAttempts = 0;
        boolean anyRuleCorrectionRan;
        do {
            anyRuleCorrectionRan = false;
            for (EnforcementRule rule : rules) {
                if (!rule.isViolated(this)) {
                    log("Enforcement rule '" + rule.getName() + "': no violation");
                    continue;
                }

                log("Enforcement rule '" + rule.getName() + "': violation detected");
                int attempts = 0;
                while (attempts < rule.getMaxRetries()
                        && rule.isViolated(this)
                        && !hasAgentCommitted()
                        && totalAttempts < DEFAULT_MAX_TOTAL_ENFORCEMENT_ATTEMPTS) {
                    attempts++;
                    totalAttempts++;
                    anyRuleCorrectionRan = true;
                    log("Enforcement rule '" + rule.getName()
                            + "': correction attempt " + attempts);
                    String correctionPrompt = rule.buildCorrectionPrompt(this);
                    if (correctionPrompt != null) {
                        runCorrectionSession(correctionPrompt, rule.getName());
                    } else {
                        // enforce-changes is the only rule that re-runs with the existing
                        // prompt; bumping enforcementAttempt for other rules would inflate
                        // the user-facing escalation messaging.
                        if ("enforce-changes".equals(rule.getName())) {
                            enforcementAttempt++;
                            log("Enforcement attempt: " + (enforcementAttempt + 1));
                        }
                        // Preserve commit.txt: executeSingleRun() deletes it at startup.
                        Path rerunCommitFile = resolveWorkingPath("commit.txt");
                        String savedForRerun = null;
                        if (rerunCommitFile != null && Files.exists(rerunCommitFile)) {
                            try { savedForRerun = Files.readString(rerunCommitFile, StandardCharsets.UTF_8); }
                            catch (IOException e) { warn("Could not save commit.txt: " + e.getMessage()); }
                        }
                        // Tag the activity so per-phase runner routing applies even though
                        // there is no separate correction prompt for this rule.
                        String previousActivity = currentActivity;
                        currentActivity = rule.getName();
                        try {
                            executeSingleRun();
                        } finally {
                            currentActivity = previousActivity;
                        }
                        // Only restore old commit.txt if the rerun did not write a new one;
                        // when the rerun makes the actual code changes its message is authoritative.
                        boolean rerunWroteCommit = rerunCommitFile != null && Files.exists(rerunCommitFile);
                        if (!rerunWroteCommit && savedForRerun != null && rerunCommitFile != null) {
                            try { Files.writeString(rerunCommitFile, savedForRerun, StandardCharsets.UTF_8); }
                            catch (IOException e) { warn("Could not restore commit.txt: " + e.getMessage()); }
                        }
                    }
                    rule.onCorrectionAttempted(this);
                    if (hasAgentCommitted()) break;
                }

                if (!hasAgentCommitted() && rule.isViolated(this)) {
                    if (attempts >= rule.getMaxRetries()) {
                        warn("Enforcement rule '" + rule.getName() + "': exhausted "
                                + rule.getMaxRetries() + " retries without resolution");
                        if ("post-completion-command".equals(rule.getName())) postCompletionCapHit = true;
                    } else if (totalAttempts >= DEFAULT_MAX_TOTAL_ENFORCEMENT_ATTEMPTS) {
                        warn("Enforcement rule '" + rule.getName()
                                + "': stopped because the total enforcement attempt cap was reached");
                    }
                } else if ("post-completion-command".equals(rule.getName()) && ((PostCompletionCommandRule) rule).isCapHit()) {
                    postCompletionCapHit = true;
                }
                if (totalAttempts >= DEFAULT_MAX_TOTAL_ENFORCEMENT_ATTEMPTS) break;
            }
        } while (totalAttempts < DEFAULT_MAX_TOTAL_ENFORCEMENT_ATTEMPTS
                && anyRuleCorrectionRan && !hasAgentCommitted());

        if (totalAttempts >= DEFAULT_MAX_TOTAL_ENFORCEMENT_ATTEMPTS) {
            warn("Enforcement aborted after " + totalAttempts + " total attempts (cap: "
                    + DEFAULT_MAX_TOTAL_ENFORCEMENT_ATTEMPTS + ") — giving up to"
                    + " avoid an unbounded retry loop");
        }
    }

    /**
     * Runs a correction session tagged with {@code activity} (the rule name)
     * via {@code AR_AGENT_ACTIVITY} so {@code send_message} calls are labelled.
     *
     * @param correctionPrompt prompt for this session
     * @param activity         rule name used as the activity tag
     */
    protected void runCorrectionSession(String correctionPrompt, String activity) {
        String originalPrompt = this.prompt;
        String previousActivity = this.currentActivity;
        this.currentActivity = activity;
        // Snapshot commit.txt so executeSingleRun() (which deletes it at startup)
        // cannot discard the primary session's message.
        Path savedCommitFile = resolveWorkingPath("commit.txt");
        String savedCommitMessage = null;
        if (savedCommitFile != null && Files.exists(savedCommitFile)) {
            try { savedCommitMessage = Files.readString(savedCommitFile, StandardCharsets.UTF_8); }
            catch (IOException e) { warn("Could not read commit.txt: " + e.getMessage()); }
        }
        boolean reviewing = "review".equals(activity) && activeReviewRule != null;
        if (reviewing) activeReviewRule.captureBefore(this);
        try {
            this.prompt = correctionPrompt;
            executeSingleRun();
            if (reviewing) activeReviewRule.recordOutcome(this);
        } finally {
            this.prompt = originalPrompt;
            this.currentActivity = previousActivity;
            // Restore primary-session commit.txt only when the correction session did not
            // write its own; if it did, that message describes the actual changes made.
            boolean correctionWroteCommit = savedCommitFile != null && Files.exists(savedCommitFile);
            if (!correctionWroteCommit && savedCommitMessage != null && savedCommitFile != null) {
                try {
                    Files.writeString(savedCommitFile, savedCommitMessage, StandardCharsets.UTF_8);
                    log("Restored primary commit message from commit.txt");
                } catch (IOException e) {
                    warn("Could not restore commit.txt: " + e.getMessage());
                }
            }
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

        // Tag the restart with the GIT_TAMPERING_RESTART phase so the
        // per-phase runner map can route it to a different runner than the
        // primary work (e.g., a more conservative agent that won't repeat
        // the tampering).
        String previousActivity = currentActivity;
        currentActivity = Phase.GIT_TAMPERING_RESTART.wireName();
        try {
            // Re-run the session. The prompt will now include a stern warning
            // about the violation and the consequences of repeating it.
            executeSingleRun();
        } finally {
            currentActivity = previousActivity;
            // Clear the violation so it doesn't persist into further retries.
            gitTamperingViolation = null;
        }

        return true;
    }

    /**
     * Executes a single agent session via the configured {@link AgentRunner},
     * retrying up to {@link #maxInactivityRestarts} times on inactivity kills.
     * Package-private to allow test subclasses to override.
     */
    void executeSingleRun() {
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
        Path outputCapturePath = Path.of(outputFile);

        Phase currentPhase = resolveCurrentPhase();
        AgentRunner runner = resolveRunner(currentPhase);
        toolsDownloader.ensurePushedTools(pushedToolsConfig);
        configureMcpBuilder();
        String mcpConfigJson = mcpConfigBuilder.buildMcpConfig();
        String composedAllowedTools = mcpConfigBuilder.buildAllowedTools(allowedTools);

        if (getTargetBranch() != null) {
            log("Target branch: " + getTargetBranch());
        }
        if (enforcementAttempt > 0) {
            log("Enforcement attempt: " + (enforcementAttempt + 1));
        }

        output = "";
        AgentRunResult finalResult = null;
        for (int attempt = 0; attempt <= maxInactivityRestarts; attempt++) {
            inactivityRestartAttempt = attempt;
            wasKilledForInactivity = false;
            AgentRunRequest request = buildRunRequest(
                    composedAllowedTools, mcpConfigJson, outputCapturePath, attempt);
            AgentRunResult result = runner.run(request, this);
            output = result.rawOutput();
            wasKilledForInactivity = result.killedForInactivity();
            finalResult = result;
            if (!wasKilledForInactivity) break;
            if (attempt == maxInactivityRestarts) {
                warn("Inactivity-restart limit (" + maxInactivityRestarts + ") reached -- abandoning agent session");
            } else {
                log("Relaunching agent after inactivity timeout (attempt "
                        + (attempt + 2) + " of " + (maxInactivityRestarts + 1) + ")");
            }
        }
        inactivityRestartAttempt = 0;

        if (finalResult != null) {
            absorbResult(finalResult);
        }
        log("Output saved to: " + outputFile);

        if (getOutputConsumer() != null) {
            getOutputConsumer().accept(new CodingAgentJobOutput(
                getTaskId(), prompt, output, sessionId, exitCode));
        }
    }

    /**
     * Resolves the {@link AgentRunner} to use for {@code phase}.
     *
     * <p>Looks up the phase override via {@link #getRunnerForPhase(Phase)} and
     * resolves the resulting name through {@link AgentRunnerRegistry}.</p>
     *
     * @param phase the lifecycle phase being dispatched; may be {@code null}
     *              in which case the default runner is used
     * @return the runner, never {@code null}
     */
    AgentRunner resolveRunner(Phase phase) {
        String name = getRunnerForPhase(phase);
        return AgentRunnerRegistry.get(name != null ? name : AgentRunnerRegistry.CLAUDE);
    }

    /**
     * Identifies which {@link Phase} is currently being dispatched.
     *
     * <p>Reads {@link #currentActivity} (already populated by
     * {@link #runCorrectionSession(String, String)} or
     * {@link #onGitTampering()}). Returns {@link Phase#PRIMARY} for the
     * initial session and falls back to {@link Phase#PRIMARY} for any
     * activity tag that does not correspond to a known phase.</p>
     *
     * @return the resolved phase, never {@code null}
     */
    Phase resolveCurrentPhase() {
        if (currentActivity == null || currentActivity.isEmpty()) {
            return Phase.PRIMARY;
        }
        // currentActivity uses either the rule getName() value (e.g.
        // "no-maven-dependency-changes") or a phase wire name from the
        // git-tampering restart path. Try the rule mapping first, then the
        // wire-name lookup, then fall back to PRIMARY so an unrecognised tag
        // never breaks dispatch.
        Phase ruleMatch = Phase.fromRuleName(currentActivity);
        if (ruleMatch != null) {
            return ruleMatch;
        }
        try {
            return Phase.fromWireName(currentActivity);
        } catch (IllegalArgumentException e) {
            return Phase.PRIMARY;
        }
    }

    /**
     * Builds the {@link AgentRunRequest} for the current session, snapshotting
     * the instruction prompt and the orchestrator-owned MCP and tool policy.
     *
     * <p>Package-private for tests.</p>
     *
     * @param composedAllowedTools allowed-tools CSV including ar-manager and
     *                             pushed-tool entries from {@link McpConfigBuilder}
     * @param mcpConfigJson        MCP config JSON in the canonical
     *                             {@code {"mcpServers":{...}}} shape
     * @param outputCapturePath    file path where the runner should dump its
     *                             raw output
     * @param attempt              current inactivity-restart attempt index
     * @return the request handed to {@link AgentRunner#run}
     */
    AgentRunRequest buildRunRequest(String composedAllowedTools,
                                    String mcpConfigJson,
                                    Path outputCapturePath,
                                    int attempt) {
        Map<String, String> env = new LinkedHashMap<>();
        String wsUrl = resolveWorkstreamUrl();
        if (wsUrl != null && !wsUrl.isEmpty()) {
            log("AR_WORKSTREAM_URL: " + wsUrl);
        }
        mcpConfigBuilder.applyAgentEnvironment(env, wsUrl);

        Path workDir = getWorkingDirectory() != null
                ? Path.of(getWorkingDirectory()) : null;
        return AgentRunRequest.builder()
                .prompt(buildInstructionPrompt())
                .workingDirectory(workDir)
                .allowedTools(composedAllowedTools)
                .mcpConfigJson(mcpConfigJson)
                .environment(env)
                .model(model)
                .effort(effort)
                .maxTurns(maxTurns)
                .maxBudgetUsd(maxBudgetUsd)
                .inactivityTimeoutMillis(inactivityTimeoutMillis)
                .inactivityRestartAttempt(attempt)
                .maxInactivityRestarts(maxInactivityRestarts)
                .taskId(getTaskId())
                .activityTag(currentActivity)
                .outputCapturePath(outputCapturePath)
                .build();
    }

    /**
     * Absorbs {@code result} into the orchestrator's accumulated session
     * fields. Across primary and correction sessions, duration / cost / turn
     * metrics are summed; identification fields (session id, stop reason)
     * track only the latest session.
     *
     * @param result the result returned by {@link AgentRunner#run}
     */
    void absorbResult(AgentRunResult result) {
        exitCode = result.exitCode();
        sessionId = result.sessionId();
        subtype = result.stopReason();
        isError = result.sessionIsError();
        durationMs += result.durationMs();
        durationApiMs += result.durationApiMs();
        numTurns += result.numTurns();
        costUsd += result.costUsd();
        if (!result.deniedToolNames().isEmpty()) {
            if (deniedToolNames == null) {
                deniedToolNames = new ArrayList<>();
            }
            deniedToolNames.addAll(result.deniedToolNames());
            permissionDenials += result.deniedToolNames().size();
        }
    }

    /**
     * Checks whether the primary repository or any dependent repository has
     * uncommitted changes (excluding files in the standard exclusion patterns).
     *
     * <p>Used by the enforcement loop to determine whether the agent produced
     * any meaningful code changes during its session. Dependent repos are
     * checked so that agents whose only changes land in a dependent repo are
     * not falsely flagged as having produced no output.</p>
     *
     * @return true if there are uncommitted changes to non-excluded files
     *         in the primary repo or any dependent repo
     */
    boolean hasUncommittedChanges() {
        if (GitOperations.hasUncommittedChanges(getWorkingDirectory())) {
            return true;
        }
        for (String depPath : getDependentRepoPaths()) {
            if (GitOperations.hasUncommittedChanges(depPath)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected JobCompletionEvent createEvent(Exception error) {
        if (error != null) return CodingAgentJobEvent.failed(getTaskId(), getTaskString(), error.getMessage(), error);
        if (exitCode != 0) return CodingAgentJobEvent.failed(getTaskId(), getTaskString(), "Claude Code exited with code " + exitCode, null);
        if (postCompletionCapHit)
            return CodingAgentJobEvent.degraded(getTaskId(), getTaskString(),
                "Post-completion command did not exit zero within " + maxPostCompletionPasses + " pass(es) — gate abandoned, work may be incomplete");
        List<String> abandoned = AbandonedTestRunDetector.findAbandonedRunsForJob(getWorkingDirectory(), sessionStartedAt);
        if (abandoned.isEmpty()) return CodingAgentJobEvent.success(getTaskId(), getTaskString());
        return CodingAgentJobEvent.degraded(getTaskId(), getTaskString(),
            "Agent abandoned " + abandoned.size() + " test-runner run(s): " + String.join(", ", abandoned));
    }

    @Override
    protected void populateEventDetails(JobCompletionEvent event) {
        if (event instanceof CodingAgentJobEvent) {
            CodingAgentJobEvent ccEvent = (CodingAgentJobEvent) event;
            ccEvent.withClaudeCodeInfo(prompt, sessionId, exitCode);
            ccEvent.withTimingInfo(durationMs, durationApiMs, costUsd, numTurns);
            ccEvent.withSessionDetails(subtype, isError, permissionDenials, deniedToolNames);
            if (commitMessageSource != null) {
                ccEvent.withCommitMessageSource(commitMessageSource);
            }
            ccEvent.withRunnerName(runnerName);
            if (postCompletionCapHit) ccEvent.withPostCompletionCapHit(true);
            if (activeReviewRule != null && activeReviewRule.hasRun()) {
                ccEvent.withReviewInfo(true, activeReviewRule.getFilesModified(),
                        activeReviewRule.getMemoriesStored(), activeReviewRule.isExitedCleanly());
            }
        }
    }

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
            DeduplicationSpawner.submitSpawnJob(extractNewMethodNames(),
                    resolveWorkstreamUrl(), this::postJson, this::log, this::warn);
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

    /** Returns new Java method names introduced since the base branch. */
    List<String> extractNewMethodNames() {
        String base = getBaseBranch() != null ? getBaseBranch() : "master";
        return GitOperations.extractNewMethodNames(getWorkingDirectory(), base, this::warn);
    }

    /** Returns paths of files that are new on the branch since the base branch. */
    List<String> extractNewFilePaths() {
        String base = getBaseBranch() != null ? getBaseBranch() : "master";
        return GitOperations.extractNewFilePaths(getWorkingDirectory(), base, this::warn);
    }

    /**
     * Returns how the commit message was produced ({@code "agent"}, {@code "prompt_fallback"},
     * or {@code "commit_rule_recovered"}). Populated on the first call to {@link #getCommitMessage()}.
     *
     * @return the source tag, or {@code null} if no commit has been made yet
     */
    public String getCommitMessageSource() {
        return commitMessageSource;
    }

    /** Sets the commit message source tag; called by {@link CommitMessageRule} on recovery. */
    void setCommitMessageSource(String source) {
        this.commitMessageSource = source;
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
                    if (commitMessageSource == null) {
                        commitMessageSource = "agent";
                    }
                    return agentMessage;
                }
            } catch (IOException e) {
                warn("Failed to read commit.txt: " + e.getMessage());
            }
        }

        // Fallback: generate commit message from prompt
        warn("No commit.txt found or it was empty — falling back to task prompt as commit message");
        commitMessageSource = "prompt_fallback";
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


    @Override
    public String encode() {
        StringBuilder sb = new StringBuilder(super.encode());
        CodingAgentJobCodec.appendEncoded(sb, this);
        return sb.toString();
    }


    @Override
    public void set(String key, String value) {
        if (!CodingAgentJobCodec.applySetting(this, key, value)) {
            super.set(key, value);
        }
    }

    /**
     * Replaces the per-phase runner overrides with the decoded contents of
     * {@code wireValue}. Called by {@link CodingAgentJobCodec} when handling
     * the {@code runners} wire key.
     *
     * @param wireValue the encoded runner map as produced by
     *                  {@link Phase#encodeRunnerMap(Map)}
     */
    void applyRunnerMap(String wireValue) {
        runnerByPhase.clear();
        runnerByPhase.putAll(Phase.decodeRunnerMap(wireValue, this::warn));
    }

    /**
     * Backward-compatible alias for {@link CodingAgentJobFactory}; new code should use that class directly.
     * Exists so that {@code new CodingAgentJob.Factory(...)} call sites and
     * {@code CodingAgentJob$Factory} wire-format strings continue to work.
     */
    public static class Factory extends CodingAgentJobFactory {
        /** Default constructor for deserialization. */
        public Factory() { super(); }
        /** Creates a factory with the specified prompts. */
        public Factory(String... prompts) { super(prompts); }
        /** Creates a factory with the specified prompts list. */
        public Factory(List<String> prompts) { super(prompts); }
    }
}
