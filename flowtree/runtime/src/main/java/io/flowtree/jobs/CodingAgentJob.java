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
import io.flowtree.jobs.agent.PhaseConfig;
import io.flowtree.jobs.agent.PhaseConfigBundle;
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
// TODO(review): CodingAgentJob is 1558 lines (soft limit 1500). Next split: extract enforcement-rule
// orchestration (EnforcementRunner loop) and MCP/harness config (configureMcpBuilder, toolsDownloader)
// into separate classes; consider whether CodingAgentJobCodec can absorb more of encode/set.
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
     * This is the default; opt in to deduplication by passing
     * {@link #DEDUP_LOCAL} or {@link #DEDUP_SPAWN} explicitly.
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
    /** HTTP base URL of the ar-manager service, or {@code null} if not configured. */
    private String arManagerUrl;
    /** Bearer token for authenticating against the ar-manager service. */
    private String arManagerToken;
    /** Pushed-tools JSON; null when no controller is in the loop. */
    private String pushedToolsConfig;
    /** Whether the workstream is dispatch-capable; sourced from {@code Workstream.isDispatchCapable()}. */
    private boolean dispatchCapable;
    /** Per-workstream env vars set on the agent subprocess; null when none configured. */
    private Map<String, String> agentEnv;
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
    /** Maximum relaunches of Claude after inactivity-triggered kills (default 3). */
    private int maxInactivityRestarts = 3;
    /** Number of inactivity-triggered relaunches in the current run; resets to 0 after the run. */
    private int inactivityRestartAttempt;
    /** Set by the monitor when it kills the Claude subprocess; consumed by the retry loop. */
    private volatile boolean wasKilledForInactivity;
    /** When {@code true}, launch the agent subprocess inside a tmux session (real tty); runner also honours {@code AR_AGENT_USE_TMUX}. */
    private boolean useTmux;
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

    /**
     * When {@code true}, new files are reviewed for correct module placement.
     * Defaults to {@code false}; opt in via
     * {@link #setEnforceOrganizationalPlacement(boolean)}.
     */
    private boolean enforceOrganizationalPlacement = false;

    /** When {@code true} (the default), a second-pass review session runs after primary work. */
    private boolean reviewEnabled = true;
    /** Per-job cap on review passes; passed to {@link ReviewRule}. */
    private int maxReviewPasses = DEFAULT_MAX_REVIEW_PASSES;
    /** Active {@link ReviewRule} instance set by {@link EnforcementRunner} while assembling rules; owns review telemetry. */
    private ReviewRule activeReviewRule;

    /**
     * When {@code true}, a retrospective session runs after all other phases,
     * analyzing the primary phase transcript for tool-use and context-efficiency
     * improvement opportunities. Defaults to {@code false}; opt in per-job.
     */
    private boolean retrospectiveEnabled = false;
    /** Owns retrospective-phase state and execution. Reset at the top of each {@link #doWork()} call. */
    private final RetrospectivePhase retrospective = new RetrospectivePhase();

    /**
     * Sensitive-file protection flag (default {@code true}): the harness-side
     * FileStager, validateChanges TestHidingAudit, and commit-trailer signing
     * are all gated on this flag. Operator-controlled at submission time;
     * never settable by the agent itself.
     */
    private boolean sensitiveFileProtectionEnabled = true;

    /**
     * Controller-signed HMAC-SHA256 bypass signature, populated by the
     * controller at submission time when the flag is false. Verified by CI
     * via {@code tools/ci/agent-protection/verify-sensitive-bypass.sh} using
     * the same shared secret ({@code AR_AGENT_BYPASS_SECRET}). {@code null}
     * when the flag is true or when the controller has no signing key.
     */
    private String sensitiveFileBypassSignature;

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

    /**
     * Set to {@code true} when the primary phase ended in a hard failure:
     * non-zero exit, 0s wall-clock duration, not killed for inactivity.
     * Captured in {@link #doWork()} after the first {@link #executeSingleRun()}
     * and never reset, so it survives any subsequent retry that absorbs a new,
     * successful result into the accumulator. The rollup in
     * {@link #createEvent(Exception)} treats this as terminal.
     */
    private boolean primaryPhaseHardFailed;

    /** Additional enforcement rules from {@link #addEnforcementRule}; built-ins are created by {@link EnforcementRunner}. */
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

    /**
     * Unified per-phase configuration bundle holding the default
     * {@link PhaseConfig} plus per-phase overrides for runner / model /
     * effort / provider. This is the sole source of model, effort, and
     * provider; the runner-resolution fields {@link #defaultRunner} and
     * {@link #runnerByPhase} are kept in lockstep with it by
     * {@link #setPhaseConfigBundle(PhaseConfigBundle)}.
     */
    private PhaseConfigBundle phaseConfigBundle = PhaseConfigBundle.EMPTY;

    /** Builder used to assemble the MCP tool configuration JSON for Claude Code. Package private so the same-package pushed-tools test can assert propagated state without reflection. */
    final McpConfigBuilder mcpConfigBuilder = new McpConfigBuilder();
    /** Downloads pushed MCP tool source files before each agent launch. */
    private final ManagedToolsDownloader toolsDownloader = new ManagedToolsDownloader(mcpConfigBuilder);
    /** Per-runner and per-model USD cost accumulation across every phase invocation. */
    private final JobCostTracker costTracker = new JobCostTracker();
    /**
     * Accumulates session-level result state (exit code, session ID, timing, cost,
     * turn count, permission denials) across all phase runs within this job.
     * @see JobSessionAccumulator
     */
    private final JobSessionAccumulator accumulator = new JobSessionAccumulator();
    /** How the commit message was produced: {@code "agent"}, {@code "prompt_fallback"}, or {@code "commit_rule_recovered"}. */
    private String commitMessageSource;

    /** Wall-clock instant at which {@link #doWork()} began; used to filter abandoned-test-run scans. */
    private Instant sessionStartedAt;

    /** Publishes harness-level status messages; created lazily once the workstream URL is known. */
    private HarnessStatusReporter harnessStatus;

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
     * Sets whether agents on this workstream may use dispatch / orchestration tools.
     *
     * @param dispatchCapable {@code true} to grant access to {@code workstream_register}
     *                        and {@code workstream_update_config}; {@code false} (default)
     *                        to restrict the agent to its own workstream's tools
     * @see McpConfigBuilder#setDispatchCapable(boolean)
     */
    public void setDispatchCapable(boolean dispatchCapable) {
        this.dispatchCapable = dispatchCapable;
    }
    /**
     * Returns whether this job's workstream is dispatch-capable. When
     * {@code true}, {@link #configureMcpBuilder()} grants the agent the
     * {@code workstream_register} and {@code workstream_update_config}
     * tools. The value is carried across the wire by
     * {@link CodingAgentJobCodec} so a job dispatched to a remote agent
     * node retains the grant.
     *
     * @return {@code true} when the dispatch tools are granted to this agent
     * @see McpConfigBuilder#buildAllowedTools(String)
     */
    public boolean isDispatchCapable() {
        return dispatchCapable;
    }
    /** Returns the pushed-tools configuration JSON, or {@code null}. */
    public String getPushedToolsConfig() { return pushedToolsConfig; }

    /** Sets the pushed-tools configuration JSON (may be {@code null}). */
    public void setPushedToolsConfig(String pushedToolsConfig) {
        this.pushedToolsConfig = pushedToolsConfig;
    }

    /** Returns the per-workstream agent-subprocess env vars, or {@code null}. */
    public Map<String, String> getAgentEnv() { return agentEnv; }

    /** Sets per-workstream env vars applied to the agent subprocess (may be {@code null}). */
    public void setAgentEnv(Map<String, String> agentEnv) {
        this.agentEnv = agentEnv;
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
     * <p>Deduplication is disabled by default ({@link #DEDUP_NONE}).
     * Pass {@link #DEDUP_LOCAL} to run an inline session before committing.
     * Use {@link #DEDUP_SPAWN} to submit a separate agent job after committing
     * (requires a workstream URL).</p>
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

    /** Returns whether the agent subprocess is launched inside a tmux session for this job. */
    public boolean isUseTmux() { return useTmux; }

    /** Sets whether the agent subprocess launches inside a tmux session; the runner also honours {@code AR_AGENT_USE_TMUX}. */
    public void setUseTmux(boolean useTmux) { this.useTmux = useTmux; }

    /**
     * Returns whether the organizational placement rule is active for this job.
     *
     * <p>When active, a correction session is run after the agent's primary work to
     * verify that new files are placed at the appropriate level of the module hierarchy.
     * If the agent moves no files after reviewing placement, the rule considers the
     * placement correct and exits.</p>
     *
     * @return {@code true} if organizational placement enforcement is enabled
     */
    public boolean isEnforceOrganizationalPlacement() {
        return enforceOrganizationalPlacement;
    }

    /**
     * Sets whether the organizational placement rule is active for this job.
     *
     * @param enforceOrganizationalPlacement {@code true} to enable placement enforcement
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

    /** Returns whether the retrospective phase is active for this job; default {@code false}. */
    public boolean isRetrospectiveEnabled() { return retrospectiveEnabled; }
    /** Sets whether the retrospective phase is active for this job; {@code true} to enable retrospective analysis. */
    public void setRetrospectiveEnabled(boolean retrospectiveEnabled) { this.retrospectiveEnabled = retrospectiveEnabled; }

    /** Returns whether the per-job sensitive-file protections are active; default {@code true}. */
    public boolean isSensitiveFileProtectionEnabled() { return sensitiveFileProtectionEnabled; }

    /**
     * Sets whether the per-job sensitive-file protections are active.
     * Operator-controlled; the agent must not be able to set this. CI
     * honours the controller's HMAC-signed bypass signature (see
     * {@link #setSensitiveFileBypassSignature(String)}); the agent has no
     * access to the signing secret.
     */
    public void setSensitiveFileProtectionEnabled(boolean v) {
        this.sensitiveFileProtectionEnabled = v;
    }

    /** Returns the controller-signed HMAC bypass signature, or {@code null} if absent. */
    public String getSensitiveFileBypassSignature() { return sensitiveFileBypassSignature; }

    /**
     * Sets the controller-signed HMAC bypass signature. Only the controller
     * should call this; the agent never has access to the signing secret.
     */
    public void setSensitiveFileBypassSignature(String sensitiveFileBypassSignature) {
        this.sensitiveFileBypassSignature = sensitiveFileBypassSignature;
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

    /** Returns the current correction-session rule name, or null for primary work. */
    String getCurrentActivity() { return currentActivity; }

    /** Sets the active review rule (or null); used by {@link EnforcementRunner} when assembling rules. */
    void setActiveReviewRule(ReviewRule activeReviewRule) { this.activeReviewRule = activeReviewRule; }

    /** Marks that the post-completion command rule hit its retry cap. */
    void setPostCompletionCapHit(boolean postCompletionCapHit) { this.postCompletionCapHit = postCompletionCapHit; }

    /**
     * Records whether the primary phase ended in a hard failure (non-zero
     * exit, 0s duration, not killed for inactivity). Package-private; set by
     * {@link #doWork()} and by tests that drive {@link #createEvent(Exception)}
     * directly.
     */
    void setPrimaryPhaseHardFailed(boolean primaryPhaseHardFailed) {
        this.primaryPhaseHardFailed = primaryPhaseHardFailed;
    }

    /** Returns whether the primary phase ended in a hard failure. */
    public boolean isPrimaryPhaseHardFailed() { return primaryPhaseHardFailed; }

    /** Returns whether the post-completion command hit its retry cap. Package-private for event population. */
    boolean isPostCompletionCapHit() { return postCompletionCapHit; }
    /** Returns the instant at which {@link #doWork()} began. Package-private for event population. */
    Instant getSessionStartedAt() { return sessionStartedAt; }
    /** Returns whether the retrospective phase ran. Package-private for event population. */
    boolean isRetrospectiveRan() { return retrospective.ran(); }
    /** Returns the retrospective phase's USD cost. Package-private for event population. */
    double getRetrospectiveCostUsd() { return retrospective.costUsd(); }
    /** Returns whether the retrospective found a primary-phase transcript. Package-private for event population. */
    boolean isRetrospectiveTranscriptFound() { return retrospective.transcriptFound(); }
    /** Returns the number of improvement findings from the retrospective. Package-private for event population. */
    int getRetrospectiveFindingsCount() { return retrospective.findingsCount(); }
    /** Returns the retrospective's upfront token estimate. Package-private for event population. */
    int getRetrospectiveContextUpfrontTokenEstimate() { return retrospective.contextUpfrontTokenEstimate(); }
    /** Returns the retrospective's context-pressure event count. Package-private for event population. */
    int getRetrospectiveContextPressureEvents() { return retrospective.contextPressureEvents(); }

    /**
     * Records the inactivity-kill flag for the last session. Used by
     * {@link #isHardPrimaryFailure()} and normally set by
     * {@link #executeSingleRun()}; package-private so test spies that drive
     * {@link #executeSingleRun()} directly can mirror the production wiring.
     */
    void setWasKilledForInactivity(boolean killed) { this.wasKilledForInactivity = killed; }

    /** Returns the custom enforcement rules registered via {@link #addEnforcementRule}. */
    List<EnforcementRule> getCustomEnforcementRules() { return customEnforcementRules; }

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
        String resolved = (runnerName == null || runnerName.isEmpty())
                ? AgentRunnerRegistry.CLAUDE : runnerName;
        if (runnerName != null && !runnerName.isEmpty()) {
            AgentRunnerRegistry.validateName(runnerName);
        }
        this.runnerName = resolved;
        this.defaultRunner = resolved;
        this.phaseConfigBundle = phaseConfigBundle.withDefaultRunner(
                (runnerName == null || runnerName.isEmpty()) ? null : runnerName);
    }

    /**
     * Returns the default runner used when {@link #getRunnerForPhase(Phase)}
     * has no explicit override for a phase.
     *
     * @return the default runner identifier, never {@code null}
     */
    public String getDefaultRunner() { return defaultRunner; }

    /**
     * Alias for {@link #setRunnerName(String)} that emphasises this is the
     * default runner applied when {@link #getRunnerForPhase(Phase)} has no
     * explicit override.
     *
     * @param runnerName a registered runner identifier; {@code null}/empty
     *                   resets to {@link AgentRunnerRegistry#CLAUDE}
     * @throws IllegalArgumentException when the runner is not registered
     */
    public void setDefaultRunner(String runnerName) {
        String name = (runnerName == null) ? null : runnerName.trim();
        setRunnerName(name);
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
            PhaseConfig existing = phaseConfigBundle.phaseConfigs().get(phase);
            if (existing != null) {
                phaseConfigBundle = phaseConfigBundle.withPhase(phase, existing.withRunner(null));
            }
            return;
        }
        AgentRunnerRegistry.validateName(runnerName);
        runnerByPhase.put(phase, runnerName);
        PhaseConfig existing = phaseConfigBundle.phaseConfigs().get(phase);
        PhaseConfig updated = (existing != null ? existing : PhaseConfig.EMPTY)
                .withRunner(runnerName);
        phaseConfigBundle = phaseConfigBundle.withPhase(phase, updated);
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
     * Returns the unified per-phase configuration bundle for this job.
     *
     * <p>Reflects the latest state of {@code defaultRunner},
     * {@code runnerByPhase}, {@code model}, and {@code effort}; legacy
     * setters keep it in sync. Phase 2 callers (the orchestrator's
     * per-phase {@link AgentRunRequest} builder) read directly from this
     * bundle.</p>
     *
     * @return the bundle, never {@code null}
     */
    public PhaseConfigBundle getPhaseConfigBundle() {
        return phaseConfigBundle;
    }

    /**
     * Replaces the per-phase configuration bundle. Updates the legacy runner
     * fields ({@link #defaultRunner}, {@link #runnerByPhase}) to reflect the
     * new bundle so that runner-resolution callers see consistent state.
     *
     * @param bundle the new bundle; {@code null} resets to
     *               {@link PhaseConfigBundle#EMPTY}
     */
    public void setPhaseConfigBundle(PhaseConfigBundle bundle) {
        this.phaseConfigBundle = bundle != null ? bundle : PhaseConfigBundle.EMPTY;
        PhaseConfig def = phaseConfigBundle.defaultPhaseConfig();
        // Resync legacy runner fields, bypassing the bundle update path that the
        // public setters would otherwise trigger.
        String r = def.runner();
        this.defaultRunner = (r != null && !r.isEmpty()) ? r : AgentRunnerRegistry.CLAUDE;
        this.runnerName = this.defaultRunner;
        this.runnerByPhase.clear();
        for (Map.Entry<Phase, PhaseConfig> e : phaseConfigBundle.phaseConfigs().entrySet()) {
            String phaseRunner = e.getValue().runner();
            if (phaseRunner != null && !phaseRunner.isEmpty()) {
                this.runnerByPhase.put(e.getKey(), phaseRunner);
            }
        }
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
        return accumulator.getSessionId();
    }

    /**
     * Returns the full output from the last execution.
     */
    public String getOutput() {
        return accumulator.getOutput();
    }

    /**
     * Returns the exit code from the last execution.
     */
    public int getExitCode() {
        return accumulator.getExitCode();
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
     * Configures the MCP config builder with current job state. Package
     * private so the dispatch-capable plumbing (job flag to builder to
     * allowed-tools CSV) can be exercised end to end in a unit test.
     */
    void configureMcpBuilder() {
        mcpConfigBuilder.setArManagerUrl(arManagerUrl);
        mcpConfigBuilder.setArManagerToken(arManagerToken);
        mcpConfigBuilder.setPushedToolsConfig(pushedToolsConfig);
        mcpConfigBuilder.setPythonCommand(getPythonCommand());
        mcpConfigBuilder.setDispatchCapable(dispatchCapable);
        Path workDir = getWorkingDirectory() != null ? Path.of(getWorkingDirectory()) : Path.of(System.getProperty("user.dir"));
        mcpConfigBuilder.setWorkingDirectory(workDir);
    }

    /**
     * Composes the allowed-tools CSV handed to the launched agent, layering
     * ar-manager, dispatch (only when {@link #isDispatchCapable()}), pushed,
     * and project-server entries onto the base tools. {@link #configureMcpBuilder()}
     * must run first so the builder reflects current job state; this is the
     * real artifact that grants the dispatch tools to an orchestrator.
     *
     * @return the composed comma-separated allowed-tools list
     */
    String buildComposedAllowedTools() {
        return mcpConfigBuilder.buildAllowedTools(allowedTools);
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

    /**
     * Absolute ceiling on the number of times the outer enforcement loop will
     * re-enter any single rule within one job.  This is a safety net: per-rule
     * caps ({@link EnforcementRule#getMaxRetries()}) are the primary mechanism,
     * but a bug in the exhaustion-tracking logic could allow a rule to re-enter
     * indefinitely.  A value of 10 is far beyond any normal rule's per-pass
     * budget (e.g. commit-message has cap=2, deduplication has cap=3) so it will
     * not trip in ordinary operation but prevents runaway cost from any future
     * bug like the infinite loop described in the task.
     */
    public static final int DEFAULT_MAX_RULE_ENTRIES = 10;

    @Override
    protected void doWork() {
        if (sessionStartedAt == null) sessionStartedAt = Instant.now();
        executeSingleRun();

        // Capture the primary phase outcome before any enforce_changes retry
        // overwrites exitCode with a successful result.
        primaryPhaseHardFailed = isHardPrimaryFailure();

        // Git integrity violations handled by onGitTampering in GitManagedJob.
        if (!hasAgentCommitted()) {
            runEnforcementRules();
        }
        // Retrospective phase runs after enforcement rules; produces memories, not code.
        retrospective.reset();
        if (retrospectiveEnabled) {
            runReflectionPhase();
        }
    }

    /**
     * Runs all active enforcement rules in sequence, delegating to
     * {@link EnforcementRunner}.
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
        new EnforcementRunner(this).run();
    }

    /**
     * Runs the retrospective phase — a single agent session that analyzes the
     * primary phase transcript for tool-use and context-efficiency improvement
     * opportunities.
     *
     * <p>The session runs after all enforcement rules have completed, regardless
     * of whether the agent produced a commit. It produces memories, not code
     * changes, so it cannot trigger re-entry into the enforcement loop even
     * when {@link #hasAgentCommitted()} returns {@code false}.</p>
     *
     * <p>Delegates to {@link RetrospectivePhase#run(CodingAgentJob)}; package-private
     * so test spies can override it to suppress real session dispatch.</p>
     */
    void runReflectionPhase() {
        retrospective.run(this);
    }

    /** Returns the cumulative cost for {@code modelKey}; used by {@link RetrospectivePhase} to isolate session cost. */
    double getCostForModel(String modelKey) {
        return costTracker.costForModel(modelKey);
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

    /**
     * Resolves a completion-time push reconciliation conflict by running a
     * focused conflict-resolution agent session.
     *
     * <p>Unlike {@link #onGitTampering()}, which restarts the full primary
     * session, this runs a narrow {@code "resolve these markers, nothing else"}
     * correction session via {@link #runCorrectionSession(String, String)} tagged
     * with {@link Phase#PUSH_CONFLICT_RESOLUTION}. After this returns,
     * {@link GitPushReconciler} verifies the markers are gone, stages the
     * resolved files, and makes the merge commit.</p>
     *
     * @param repoPath        repository containing the conflict (primary working
     *                        directory or a dependent repo sibling)
     * @param conflictedFiles paths, relative to {@code repoPath}, left unmerged
     * @return always {@code true}; the reconciler verifies the actual outcome
     */
    @Override
    protected boolean onPushConflict(String repoPath, List<String> conflictedFiles) {
        warn("Resolving push reconciliation conflict in " + repoPath
                + " (" + conflictedFiles.size() + " file(s))");
        harnessStatus().unusual("Push reconciliation conflict in " + repoPath
                + " — running focused conflict-resolution session");
        String correctionPrompt = PushConflictPromptBuilder.build(this, repoPath, conflictedFiles);
        runCorrectionSession(correctionPrompt, Phase.PUSH_CONFLICT_RESOLUTION.wireName());
        return true;
    }

    @Override
    protected boolean onGitTampering() {
        String violation = getTamperingDescription();
        warn("Agent tampered with git: " + violation
            + " -- destroying all changes and restarting session");
        harnessStatus().unusual("Git tampering detected (" + violation
            + ") — destroying changes and restarting session");

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
        harnessStatus().phaseEntry(currentPhase, runner.getName(),
                resolveEffectivePhaseConfig(currentPhase));
        toolsDownloader.ensurePushedTools(pushedToolsConfig);
        configureMcpBuilder();
        String mcpConfigJson = mcpConfigBuilder.buildMcpConfig();
        String composedAllowedTools = buildComposedAllowedTools();

        if (getTargetBranch() != null) {
            log("Target branch: " + getTargetBranch());
        }
        if (enforcementAttempt > 0) {
            log("enforce_changes retry: restarting from PRIMARY (retry " + enforcementAttempt + ")");
        }

        PhaseConfig effective = resolveEffectivePhaseConfig(currentPhase);
        String modelKey = effective.toModelKey();

        accumulator.setOutput("");
        AgentRunResult finalResult = null;
        for (int attempt = 0; attempt <= maxInactivityRestarts; attempt++) {
            inactivityRestartAttempt = attempt;
            wasKilledForInactivity = false;
            AgentRunRequest request = buildRunRequest(
                    composedAllowedTools, mcpConfigJson, outputCapturePath, attempt);
            AgentRunResult result = runner.run(request, this);
            accumulator.setOutput(result.rawOutput());
            wasKilledForInactivity = result.killedForInactivity();
            finalResult = result;
            if (!wasKilledForInactivity) break;
            harnessStatus().inactivitySuspended(runner.getName(), attempt, maxInactivityRestarts);
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
            costTracker.record(runner.getName(), modelKey, finalResult.costUsd());
        }
        harnessStatus().phaseExit(currentPhase, finalResult);
        log("Output saved to: " + outputFile);

        if (getOutputConsumer() != null) {
            getOutputConsumer().accept(new CodingAgentJobOutput(
                getTaskId(), prompt, accumulator.getOutput(), accumulator.getSessionId(), accumulator.getExitCode()));
        }
    }

    /**
     * Resolves the {@link AgentRunner} to use for {@code phase}.
     *
     * <p>Consults {@link #resolveEffectivePhaseConfig(Phase)} first (per-phase
     * bundle override overlaid on the bundle default, which itself layers the
     * legacy {@code defaultRunner} field). When the resolved {@link PhaseConfig}
     * carries no runner, falls back to {@link #getRunnerForPhase(Phase)}. The
     * name is then resolved through {@link AgentRunnerRegistry}, defaulting
     * to {@link AgentRunnerRegistry#CLAUDE}.</p>
     *
     * @param phase the lifecycle phase being dispatched; may be {@code null}
     * @return the runner, never {@code null}
     */
    AgentRunner resolveRunner(Phase phase) {
        String name = resolveEffectivePhaseConfig(phase).runner();
        if (name == null || name.isEmpty()) name = getRunnerForPhase(phase);
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
     * <p>Reads per-phase model and effort from {@link #phaseConfigBundle} via
     * {@link #resolveEffectivePhaseConfig(Phase)} so that mixed-phase jobs
     * dispatch each phase with its own resolved {@code (model, effort)}
     * pair. Runner selection still goes through {@link #resolveRunner(Phase)}
     * in {@link #executeSingleRun()}.</p>
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
        // Per-workstream agentEnv first, so framework-critical vars set by
        // applyAgentEnvironment (ar-manager URL/token, workstream URL) win on
        // any key collision.
        if (agentEnv != null && !agentEnv.isEmpty()) {
            env.putAll(agentEnv);
        }
        String wsUrl = resolveWorkstreamUrl();
        if (wsUrl != null && !wsUrl.isEmpty()) {
            log("AR_WORKSTREAM_URL: " + wsUrl);
        }
        mcpConfigBuilder.applyAgentEnvironment(env, wsUrl);

        Path workDir = getWorkingDirectory() != null
                ? Path.of(getWorkingDirectory()) : null;
        // Resolve per-phase model/effort/provider from the bundle. The runner
        // falls back to the default runner; model/effort/provider come solely
        // from the phase config (null means "use the runner's CLI default").
        Phase phase = resolveCurrentPhase();
        PhaseConfig effective = phaseConfigBundle.forPhase(phase)
                .overlayOn(new PhaseConfig(defaultRunner, null, null));
        return AgentRunRequest.builder()
                .prompt(buildInstructionPrompt())
                .workingDirectory(workDir)
                .allowedTools(composedAllowedTools)
                .mcpConfigJson(mcpConfigJson)
                .environment(env)
                .model(effective.model())
                .effort(effective.effort())
                .provider(effective.provider())
                .maxTurns(maxTurns)
                .maxBudgetUsd(maxBudgetUsd)
                .inactivityTimeoutMillis(resolveRunner(phase).defaultInactivityTimeoutMillis())
                .inactivityRestartAttempt(attempt)
                .maxInactivityRestarts(maxInactivityRestarts)
                .taskId(getTaskId())
                .activityTag(currentActivity)
                .outputCapturePath(outputCapturePath)
                .useTmux(useTmux)
                .build();
    }

    /** Effective {@link PhaseConfig} for {@code phase}: bundle overlay over the default runner. */
    PhaseConfig resolveEffectivePhaseConfig(Phase phase) {
        return phaseConfigBundle.forPhase(phase)
                .overlayOn(new PhaseConfig(defaultRunner, null, null));
    }

    /** Returns the lazily-created harness status reporter (no-op when no workstream URL is set). */
    HarnessStatusReporter harnessStatus() {
        if (harnessStatus == null) {
            harnessStatus = new HarnessStatusReporter(resolveWorkstreamUrl(), this::postJson);
        }
        return harnessStatus;
    }

    /** Returns an immutable snapshot of the cumulative per-runner USD cost for this job. */
    Map<String, Double> getCostByRunner() {
        return costTracker.snapshotByRunner();
    }

    /** Returns an immutable snapshot of the cumulative per-model USD cost for this job. */
    Map<String, Double> getCostByModel() {
        return costTracker.snapshotByModel();
    }

    /**
     * Returns whether the most recent {@link #executeSingleRun()} call ended
     * in a hard failure: non-zero exit, 0s wall-clock duration, not killed by
     * the inactivity watchdog. This is the "failed (exit N) in 0s" signature
     * the per-phase {@code harness_status} messages report.
     *
     * <p>Called by {@link #doWork()} to capture the primary phase outcome
     * before any enforcement retry can absorb a new, successful result.</p>
     */
    boolean isHardPrimaryFailure() {
        return accumulator.getExitCode() != 0 && accumulator.getDurationMs() == 0L && !wasKilledForInactivity;
    }

    /**
     * Absorbs {@code result} into the {@link JobSessionAccumulator}. Across
     * primary and correction sessions, duration / cost / turn metrics are
     * summed; identification fields (session id, stop reason) track only the
     * latest session.
     *
     * @param result the result returned by {@link AgentRunner#run}
     */
    void absorbResult(AgentRunResult result) {
        accumulator.absorb(result);
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
        return CodingAgentJobEvent.forJob(this, accumulator, error);
    }

    @Override
    protected void populateEventDetails(JobCompletionEvent event) {
        if (event instanceof CodingAgentJobEvent) {
            ((CodingAgentJobEvent) event).populateFrom(this, accumulator);
        }
    }

    @Override
    protected boolean validateChanges() throws Exception {
        // Test-hiding audit gated on BOTH protectTestFiles AND the new
        // sensitiveFileProtectionEnabled flag.
        if (isProtectTestFiles() && sensitiveFileProtectionEnabled
                && !TestHidingAudit.passes(getWorkingDirectory(), getBaseBranch(), this)) {
            return false;
        }

        // Deduplication SPAWN mode: fire-and-forget follow-up job after primary work.
        // DEDUP_LOCAL is handled inline by DeduplicationRule in runEnforcementRules().
        if (DEDUP_SPAWN.equals(deduplicationMode)) {
            DeduplicationSpawner.submitSpawnJob(extractNewMethodNames(),
                    getBaseBranch(),
                    resolveWorkstreamUrl(), this::postJson, this::log, this::warn);
        }

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
        return CommitMessageBuilder.resolve(this);
    }

    /**
     * Resolves a path relative to the working directory.
     *
     * @param filename the path relative to the working directory
     * @return the resolved {@link Path}
     */
    Path resolveWorkingPath(String filename) {
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
     * {@code wireValue}. Called by {@link CodingAgentJobCodec} for the
     * {@code runners} wire key. Syncs both {@link #runnerByPhase} and
     * {@link #phaseConfigBundle} so {@link #resolveRunner(Phase)} sees them.
     */
    void applyRunnerMap(String wireValue) {
        runnerByPhase.clear();
        Map<Phase, String> decoded = Phase.decodeRunnerMap(wireValue, this::warn);
        runnerByPhase.putAll(decoded);
        for (Map.Entry<Phase, String> entry : decoded.entrySet()) {
            PhaseConfig existing = phaseConfigBundle.phaseConfigs().get(entry.getKey());
            PhaseConfig updated = (existing != null ? existing : PhaseConfig.EMPTY)
                    .withRunner(entry.getValue());
            phaseConfigBundle = phaseConfigBundle.withPhase(entry.getKey(), updated);
        }
    }

    /** Backward-compatible alias for {@link CodingAgentJobFactory}; new code should use that class directly. */
    public static class Factory extends CodingAgentJobFactory {
        /** Default constructor for deserialization. */
        public Factory() { super(); }
        /** Creates a factory with the specified prompts. */
        public Factory(String... prompts) { super(prompts); }
        /** Creates a factory with the specified prompts list. */
        public Factory(List<String> prompts) { super(prompts); }
    }
}
