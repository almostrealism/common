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
import io.flowtree.job.AbstractJobFactory;
import io.flowtree.job.Job;
import io.flowtree.jobs.agent.AgentRunnerRegistry;
import io.flowtree.jobs.agent.Phase;
import io.flowtree.jobs.agent.PhaseConfig;
import io.flowtree.jobs.agent.PhaseConfigBundle;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.util.KeyUtils;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Factory for producing {@link CodingAgentJob} instances from a list of prompts.
 *
 * <p>Each prompt becomes a separate job, allowing the Flowtree system to
 * distribute prompts across multiple nodes. When a node finishes a prompt,
 * it becomes idle and can pick up the next job.</p>
 *
 * <p>All configuration (target branch, git credentials, tool list, etc.) is
 * stored in the inherited {@link AbstractJobFactory} properties map so that
 * the factory survives wire serialization and deserialization unchanged.</p>
 *
 * <p>For backward source compatibility, {@link CodingAgentJob} publishes
 * {@code CodingAgentJob.Factory} as a thin subclass of this class.</p>
 *
 * @author Michael Murray
 * @see CodingAgentJob
 */
public class CodingAgentJobFactory extends AbstractJobFactory implements ConsoleFeatures {

    /** Cached decoded list of prompts; populated lazily from the serialized properties. */
    private List<String> prompts;

    /** Short human-readable description for jobs created by this factory. */
    private String description;

    /** Index of the next prompt to be dispatched as a job. */
    private int index;

    /** Comma-separated list of tools Claude Code is permitted to invoke. */
    private String allowedTools = CodingAgentJob.DEFAULT_TOOLS;

    /** Maximum number of agentic turns Claude Code may take per job. */
    private int maxTurns = 50;

    /** Maximum spend budget per job in US dollars. */
    private double maxBudgetUsd = 10.0;

    /**
     * Model alias or full model name propagated to jobs created by this
     * factory.  {@code null} leaves the Claude Code {@code --model} flag off
     * so the CLI uses its own default.
     */
    private String model;

    /**
     * Effort/thinking level propagated to jobs created by this factory.
     * {@code null} leaves the Claude Code {@code --effort} flag off so the
     * CLI uses its own default.  Must be one of
     * {@link CodingAgentJob#VALID_EFFORT_LEVELS} when set.
     */
    private String effort;

    /** HTTP base URL of the ar-manager service, or {@code null} if not configured. */
    private String arManagerUrl;

    /** Bearer token for authenticating against the ar-manager service. */
    private String arManagerToken;

    /**
     * JSON describing pushed MCP tools the controller is serving for this
     * job (always includes ar-secrets; may include additional operator
     * declarations). Format matches {@code FlowTreeController.getPushedToolsConfig()}.
     */
    private String pushedToolsConfig;

    /** JSON object of per-workstream env vars set on the agent subprocess; may be {@code null}. */
    private String agentEnvJson;

    /** Optional planning document text to inject into the Claude Code system prompt. */
    private String planningDocument;

    /**
     * Deduplication mode applied to jobs created by this factory.
     * Defaults to {@link CodingAgentJob#DEDUP_NONE} (disabled).
     * Opt in by passing {@link CodingAgentJob#DEDUP_LOCAL} or
     * {@link CodingAgentJob#DEDUP_SPAWN} when submitting a job.
     */
    private String deduplicationMode = CodingAgentJob.DEDUP_NONE;

    /**
     * Per-job cap on deduplication passes propagated to jobs created by this factory.
     * Defaults to {@link CodingAgentJob#DEFAULT_MAX_DEDUP_PASSES}.
     */
    private int maxDeduplicationPasses = CodingAgentJob.DEFAULT_MAX_DEDUP_PASSES;

    /**
     * When {@code true}, jobs created by this factory activate the Maven
     * dependency protection rule, blocking {@code <dependency>} changes in
     * {@code pom.xml} files.
     */
    private boolean enforceMavenDependencies;

    /**
     * When {@code true}, jobs created by this factory activate the organizational
     * placement rule, prompting the agent to verify that new files are placed at the
     * appropriate level of the module hierarchy.
     * Defaults to {@code false}; opt in via {@code enforceOrganizationalPlacement=true}
     * on the submit request, or by calling
     * {@link #setEnforceOrganizationalPlacement(boolean)} directly.
     */
    private boolean enforceOrganizationalPlacement = false;

    /**
     * When {@code true} (the default), jobs created by this factory activate the
     * {@link ReviewRule} (second-pass sanity check by a separate runner).
     */
    private boolean reviewEnabled = true;

    /**
     * Per-job cap on review passes propagated to jobs created by this factory.
     * Defaults to {@link CodingAgentJob#DEFAULT_MAX_REVIEW_PASSES}.
     */
    private int maxReviewPasses = CodingAgentJob.DEFAULT_MAX_REVIEW_PASSES;

    /**
     * Shell command run after the agent's primary work to verify the result.
     * Propagated to jobs via {@link CodingAgentJob#setPostCompletionCommand(String)}.
     * Empty or {@code null} disables the post-completion check.
     */
    private String postCompletionCommand;

    /**
     * Working directory for the post-completion command.
     * {@code null} means the job's working directory is used.
     */
    private String postCompletionWorkingDir;

    /**
     * Timeout in seconds for the post-completion command.
     * Defaults to {@link PostCompletionCommandRule#DEFAULT_TIMEOUT_SECONDS}.
     */
    private int postCompletionTimeoutSeconds = PostCompletionCommandRule.DEFAULT_TIMEOUT_SECONDS;

    /**
     * Per-job cap on post-completion correction sessions propagated to jobs created by this
     * factory. Defaults to {@link CodingAgentJob#DEFAULT_MAX_POST_COMPLETION_PASSES}.
     */
    private int maxPostCompletionPasses = CodingAgentJob.DEFAULT_MAX_POST_COMPLETION_PASSES;

    /**
     * Legacy single-runner name for jobs created by this factory. Mirrored to
     * {@link #defaultRunner}; retained for source compatibility with pre-Phase-2
     * callers that did not understand the per-phase map.
     */
    private String runnerName = AgentRunnerRegistry.CLAUDE;

    /**
     * Default {@link io.flowtree.jobs.agent.AgentRunner} for jobs created by
     * this factory; takes effect when {@link #runnerByPhase} has no entry for
     * the dispatched phase. Defaults to {@link AgentRunnerRegistry#CLAUDE}.
     */
    private String defaultRunner = AgentRunnerRegistry.CLAUDE;

    /** Per-phase runner overrides propagated to jobs created by this factory. */
    private final Map<Phase, String> runnerByPhase = new EnumMap<>(Phase.class);

    /**
     * Unified per-phase configuration bundle propagated to jobs created by
     * this factory. Kept in sync with the legacy {@link #defaultRunner},
     * {@link #runnerByPhase}, {@link #model}, and {@link #effort} fields.
     */
    private PhaseConfigBundle phaseConfigBundle = PhaseConfigBundle.EMPTY;

    /**
     * Default constructor for deserialization.
     */
    public CodingAgentJobFactory() {
        super(KeyUtils.generateKey());
        // Persist taskId in properties so it survives wire serialization.
        // AbstractJobFactory.encode() does NOT serialize the taskId field,
        // so without this the deserialized factory would get a new random ID.
        set("factoryTaskId", super.getTaskId());

        // Store default for pushToOrigin so isPushToOrigin() returns true
        // even before setPushToOrigin() is explicitly called.
        set("push", String.valueOf(true));
    }

    /**
     * Returns the stable task identifier for this factory. The ID is
     * persisted in the serialized properties map so that it survives
     * wire serialisation and deserialisation.
     *
     * @return the factory's task ID
     */
    @Override
    public String getTaskId() {
        String stored = get("factoryTaskId");
        return stored != null ? stored : super.getTaskId();
    }

    /**
     * Creates a factory with the specified prompts.
     *
     * @param prompts the prompts to process
     */
    public CodingAgentJobFactory(String... prompts) {
        this();
        if (prompts.length > 0) {
            setPrompts(prompts);
        }
    }

    /**
     * Creates a factory with the specified prompts list.
     * Use this when you have an existing list of prompts.
     *
     * @param prompts the list of prompts to process
     */
    public CodingAgentJobFactory(List<String> prompts) {
        this();
        setPrompts(prompts.toArray(new String[0]));
    }

    /**
     * Sets the list of prompts for this factory by encoding and persisting
     * them to the serialized properties map.
     *
     * @param prompts  the prompts to encode and store
     */
    public void setPrompts(String... prompts) {
        String code = String.join(CodingAgentJob.PROMPT_SEPARATOR, prompts);
        set("prompts", GitManagedJob.base64Encode(code));
    }

    /**
     * Returns the list of prompts configured for this factory, decoding
     * them from the serialized properties map on the first access.
     *
     * @return the list of prompts; never {@code null}
     */
    public List<String> getPrompts() {
        if (prompts == null) {
            String code = GitManagedJob.base64Decode(get("prompts"));
            prompts = code == null ? new ArrayList<>()
                    : new ArrayList<>(List.of(code.split(CodingAgentJob.PROMPT_SEPARATOR)));
        }
        return prompts;
    }

    /**
     * Returns the short description for jobs created by this factory.
     */
    public String getDescription() {
        if (description == null) {
            description = GitManagedJob.base64Decode(get("desc"));
        }
        return description;
    }

    /**
     * Sets a short human-readable description for jobs created by this factory.
     *
     * @param description a concise label (e.g., "Resolve test failures")
     */
    public void setDescription(String description) {
        this.description = description;
        set("desc", GitManagedJob.base64Encode(description));
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
        set("tools", allowedTools);
    }

    /**
     * Returns the working directory for jobs created by this factory.
     * Reads from the serialized properties map, using the same key
     * and encoding as {@link GitManagedJob}.
     */
    public String getWorkingDirectory() {
        return GitManagedJob.base64Decode(get("workDir"));
    }

    /**
     * Sets the working directory for jobs created by this factory.
     *
     * @param workingDirectory  absolute path to the working directory
     */
    public void setWorkingDirectory(String workingDirectory) {
        set("workDir", GitManagedJob.base64Encode(workingDirectory));
    }

    /**
     * Returns the git repository URL for automatic checkout.
     */
    public String getRepoUrl() {
        return GitManagedJob.base64Decode(get("repoUrl"));
    }

    /**
     * Sets the git repository URL. When set, the agent will clone
     * this repo if no working directory is specified.
     *
     * @param repoUrl the git clone URL
     */
    public void setRepoUrl(String repoUrl) {
        set("repoUrl", GitManagedJob.base64Encode(repoUrl));
    }

    /**
     * Returns the default workspace path for repo checkouts.
     */
    public String getDefaultWorkspacePath() {
        return GitManagedJob.base64Decode(get("defaultWsPath"));
    }

    /**
     * Sets the default workspace path for repo checkouts.
     *
     * @param defaultWorkspacePath the absolute path for repo checkouts
     */
    public void setDefaultWorkspacePath(String defaultWorkspacePath) {
        set("defaultWsPath", GitManagedJob.base64Encode(defaultWorkspacePath));
    }

    /**
     * Returns the maximum number of agentic turns Claude Code may take per job.
     *
     * @return the turn limit
     */
    public int getMaxTurns() {
        return maxTurns;
    }

    /**
     * Sets the maximum number of agentic turns Claude Code may take per job.
     *
     * @param maxTurns  the turn limit
     */
    public void setMaxTurns(int maxTurns) {
        this.maxTurns = maxTurns;
        set("maxTurns", String.valueOf(maxTurns));
    }

    /**
     * Returns the maximum spend budget per job in US dollars.
     *
     * @return the budget cap in USD
     */
    public double getMaxBudgetUsd() {
        return maxBudgetUsd;
    }

    /**
     * Sets the maximum spend budget per job in US dollars.
     *
     * @param maxBudgetUsd  the budget cap in USD; negative values disable the limit
     */
    public void setMaxBudgetUsd(double maxBudgetUsd) {
        this.maxBudgetUsd = maxBudgetUsd;
        set("maxBudget", String.valueOf(maxBudgetUsd));
    }

    /**
     * Returns the Claude Code model for jobs created by this factory.
     *
     * @return the model alias or full name, or {@code null} to use the CLI default
     */
    public String getModel() {
        return model;
    }

    /**
     * Sets the model for jobs created by this factory.  Passed to each created
     * job via {@link CodingAgentJob#setModel(String)}.  The value is validated
     * immediately against the configured runner's advertised models via
     * {@link AgentRunnerRegistry#validateModel(String, String)} so
     * misconfiguration fails at the caller rather than silently at dispatch.
     * Validation is runner-aware, so non-Claude runners (e.g. opencode) are not
     * rejected against the Claude allowlist.
     *
     * @param model a model identifier accepted by the configured default
     *              runner, or {@code null}/empty to use the CLI default
     * @throws IllegalArgumentException if the runner restricts its models and
     *                                  {@code model} is not among them
     */
    public void setModel(String model) {
        if (model == null || model.isEmpty()) {
            this.model = null;
            set("model", null);
            this.phaseConfigBundle = phaseConfigBundle.withDefaultModel(null);
            return;
        }
        AgentRunnerRegistry.validateModel(defaultRunner, model);
        this.model = model;
        set("model", model);
        this.phaseConfigBundle = phaseConfigBundle.withDefaultModel(model);
    }

    /**
     * Returns the effort/thinking level for jobs created by this factory.
     *
     * @return one of {@link CodingAgentJob#VALID_EFFORT_LEVELS}, or
     *         {@code null} to use the CLI default
     */
    public String getEffort() {
        return effort;
    }

    /**
     * Sets the effort/thinking level for jobs created by this factory.  The
     * value is validated immediately against
     * {@link CodingAgentJob#VALID_EFFORT_LEVELS} so misconfiguration fails at
     * the caller rather than silently at dispatch.
     *
     * @param effort one of {@link CodingAgentJob#VALID_EFFORT_LEVELS}, or
     *               {@code null}/empty to use the CLI default
     * @throws IllegalArgumentException if {@code effort} is not valid
     */
    public void setEffort(String effort) {
        if (effort == null || effort.isEmpty()) {
            this.effort = null;
            set("effort", null);
            this.phaseConfigBundle = phaseConfigBundle.withDefaultEffort(null);
            return;
        }
        if (!CodingAgentJob.VALID_EFFORT_LEVELS.contains(effort)) {
            throw new IllegalArgumentException(
                    "Invalid effort level '" + effort + "'. Must be one of "
                    + CodingAgentJob.VALID_EFFORT_LEVELS);
        }
        this.effort = effort;
        set("effort", effort);
        this.phaseConfigBundle = phaseConfigBundle.withDefaultEffort(effort);
    }

    /**
     * Returns the target branch for git operations.
     */
    public String getTargetBranch() {
        return GitManagedJob.base64Decode(get("branch"));
    }

    /**
     * Sets the target branch for git operations.
     * When set, each job will commit and push its changes to this branch.
     *
     * @param targetBranch the branch name (e.g., "feature/my-work")
     */
    public void setTargetBranch(String targetBranch) {
        set("branch", GitManagedJob.base64Encode(targetBranch));
    }

    /**
     * Returns the base branch for new branch creation.
     */
    public String getBaseBranch() {
        return GitManagedJob.base64Decode(get("baseBranch"));
    }

    /**
     * Sets the base branch used as the starting point when the target
     * branch does not yet exist. Defaults to {@code "master"}.
     *
     * @param baseBranch the branch name to base new branches on
     */
    public void setBaseBranch(String baseBranch) {
        set("baseBranch", GitManagedJob.base64Encode(baseBranch));
    }

    /**
     * Returns whether to push commits to origin. Defaults to {@code true}.
     */
    public boolean isPushToOrigin() {
        return Boolean.parseBoolean(get("push"));
    }

    /**
     * Sets whether jobs produced by this factory should push their commits
     * to the remote origin.
     *
     * @param pushToOrigin  {@code true} to push, {@code false} to commit locally only
     */
    public void setPushToOrigin(boolean pushToOrigin) {
        set("push", String.valueOf(pushToOrigin));
    }

    /**
     * Returns the git user name for commits.
     */
    public String getGitUserName() {
        return GitManagedJob.base64Decode(get("gitUserName"));
    }

    /**
     * Sets the git user name for commits made by jobs from this factory.
     *
     * @param gitUserName the name to use in git commits
     */
    public void setGitUserName(String gitUserName) {
        set("gitUserName", GitManagedJob.base64Encode(gitUserName));
    }

    /**
     * Returns the git user email for commits.
     */
    public String getGitUserEmail() {
        return GitManagedJob.base64Decode(get("gitUserEmail"));
    }

    /**
     * Sets the git user email for commits made by jobs from this factory.
     *
     * @param gitUserEmail the email to use in git commits
     */
    public void setGitUserEmail(String gitUserEmail) {
        set("gitUserEmail", GitManagedJob.base64Encode(gitUserEmail));
    }

    /**
     * Returns the workstream URL for jobs created by this factory.
     */
    public String getWorkstreamUrl() {
        return GitManagedJob.base64Decode(get("workstreamUrl"));
    }

    /**
     * Sets the workstream URL for jobs created by this factory.
     * This single URL is used for both status reporting and
     * messaging (by appending {@code /messages}).
     *
     * @param workstreamUrl the controller URL for the workstream
     */
    public void setWorkstreamUrl(String workstreamUrl) {
        set("workstreamUrl", GitManagedJob.base64Encode(workstreamUrl));
    }

    /**
     * Returns the ar-manager HTTP URL.
     */
    public String getArManagerUrl() {
        return arManagerUrl;
    }

    /**
     * Sets the ar-manager HTTP URL for agent jobs.
     *
     * @param arManagerUrl the ar-manager service URL
     */
    public void setArManagerUrl(String arManagerUrl) {
        this.arManagerUrl = arManagerUrl;
        set("arManagerUrl", GitManagedJob.base64Encode(arManagerUrl));
    }

    /**
     * Returns the ar-manager auth token.
     */
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
        set("arManagerToken", GitManagedJob.base64Encode(arManagerToken));
    }

    /**
     * Returns the pushed-tools configuration JSON. May be {@code null}.
     */
    public String getPushedToolsConfig() {
        return pushedToolsConfig;
    }

    /**
     * Sets the pushed-tools configuration JSON. The same string is later
     * forwarded to each created job so it can drive both
     * {@link ManagedToolsDownloader#ensurePushedTools(String)} and
     * {@link McpConfigBuilder#setPushedToolsConfig(String)}.
     *
     * @param pushedToolsConfig the JSON configuration; may be {@code null}
     */
    public void setPushedToolsConfig(String pushedToolsConfig) {
        this.pushedToolsConfig = pushedToolsConfig;
        set("pushedToolsConfig", GitManagedJob.base64Encode(pushedToolsConfig));
    }

    /**
     * Sets per-workstream environment variables to apply to the agent
     * subprocess of each created job. Serialized as a JSON object so it
     * survives wire encode/decode; propagated to the job at build time.
     *
     * @param agentEnv environment variable map; {@code null} or empty clears it
     */
    public void setAgentEnv(Map<String, String> agentEnv) {
        if (agentEnv == null || agentEnv.isEmpty()) {
            this.agentEnvJson = null;
            set("agentEnv", null);
            return;
        }
        this.agentEnvJson = JsonFieldExtractor.toJsonObject(agentEnv);
        set("agentEnv", GitManagedJob.base64Encode(this.agentEnvJson));
    }

    /**
     * Returns the planning document path for jobs.
     */
    public String getPlanningDocument() {
        return planningDocument;
    }

    /**
     * Sets the planning document path for jobs created by this factory.
     *
     * @param planningDocument path relative to the working directory
     */
    public void setPlanningDocument(String planningDocument) {
        this.planningDocument = planningDocument;
        set("planDoc", GitManagedJob.base64Encode(planningDocument));
    }

    /**
     * Returns whether test file protection is enabled for jobs.
     */
    public boolean isProtectTestFiles() {
        return "true".equals(get("protectTests"));
    }

    /**
     * Sets whether to protect test files that exist on the base branch.
     *
     * @param protectTestFiles true to block staging of existing test/CI files
     */
    public void setProtectTestFiles(boolean protectTestFiles) {
        set("protectTests", String.valueOf(protectTestFiles));
    }

    /**
     * Returns whether enforcement mode is enabled for jobs.
     * When enabled, jobs will loop until code changes are produced.
     */
    public boolean isEnforceChanges() {
        return "true".equals(get("enforceChanges"));
    }

    /**
     * Sets whether enforcement mode is enabled for jobs.
     * When enabled, the agent session restarts with escalating warnings
     * if it finishes without producing any code changes.
     *
     * @param enforceChanges true to require code changes for completion
     */
    public void setEnforceChanges(boolean enforceChanges) {
        set("enforceChanges", String.valueOf(enforceChanges));
    }

    /**
     * Returns the deduplication mode applied to jobs created by this factory.
     * Defaults to {@link CodingAgentJob#DEDUP_NONE} (disabled).
     *
     * @return {@link CodingAgentJob#DEDUP_NONE} (default), {@link CodingAgentJob#DEDUP_LOCAL},
     *         or {@link CodingAgentJob#DEDUP_SPAWN}
     */
    public String getDeduplicationMode() {
        return deduplicationMode;
    }

    /**
     * Sets the deduplication mode for jobs created by this factory.
     *
     * @param deduplicationMode {@link CodingAgentJob#DEDUP_LOCAL},
     *                          {@link CodingAgentJob#DEDUP_SPAWN},
     *                          or {@link CodingAgentJob#DEDUP_NONE}
     */
    public void setDeduplicationMode(String deduplicationMode) {
        this.deduplicationMode = deduplicationMode;
        set("dedupMode", deduplicationMode);
    }

    /**
     * Returns the maximum number of deduplication passes for jobs created by this factory.
     *
     * @return the pass cap; defaults to {@link CodingAgentJob#DEFAULT_MAX_DEDUP_PASSES}
     */
    public int getMaxDeduplicationPasses() {
        return maxDeduplicationPasses;
    }

    /**
     * Sets the maximum number of deduplication passes for jobs created by this factory.
     *
     * @param maxDeduplicationPasses cap on correction sessions per job; must be positive
     * @throws IllegalArgumentException if the value is not positive
     */
    public void setMaxDeduplicationPasses(int maxDeduplicationPasses) {
        if (maxDeduplicationPasses <= 0) {
            throw new IllegalArgumentException(
                    "maxDeduplicationPasses must be positive, got: " + maxDeduplicationPasses);
        }
        this.maxDeduplicationPasses = maxDeduplicationPasses;
        set("maxDedupPasses", String.valueOf(maxDeduplicationPasses));
    }

    /**
     * Returns whether jobs created by this factory activate the Maven dependency
     * protection rule.
     *
     * <p>When active, any {@code <dependency>} additions, removals, or modifications
     * in {@code pom.xml} files trigger a correction loop that instructs the agent
     * to revert those changes.</p>
     *
     * @return {@code true} if Maven dependency changes are blocked
     */
    public boolean isEnforceMavenDependencies() {
        return enforceMavenDependencies;
    }

    /**
     * Sets whether jobs created by this factory activate the Maven dependency
     * protection rule.
     *
     * @param enforceMavenDependencies {@code true} to block {@code <dependency>} changes
     */
    public void setEnforceMavenDependencies(boolean enforceMavenDependencies) {
        this.enforceMavenDependencies = enforceMavenDependencies;
        set("enforceMavenDeps", String.valueOf(enforceMavenDependencies));
    }

    /**
     * Returns whether jobs created by this factory activate the organizational
     * placement rule.
     *
     * @return {@code true} if placement enforcement is enabled; {@code false} by default
     */
    public boolean isEnforceOrganizationalPlacement() {
        return enforceOrganizationalPlacement;
    }

    /**
     * Sets whether jobs created by this factory activate the organizational
     * placement rule.
     *
     * @param enforceOrganizationalPlacement {@code true} to enable placement enforcement
     */
    public void setEnforceOrganizationalPlacement(boolean enforceOrganizationalPlacement) {
        this.enforceOrganizationalPlacement = enforceOrganizationalPlacement;
        set("enforceOrgPlacement", String.valueOf(enforceOrganizationalPlacement));
    }

    /**
     * Returns whether the {@link ReviewRule} (second-pass sanity check) is active
     * for jobs created by this factory.
     *
     * @return {@code true} when review is enabled (the default)
     */
    public boolean isReviewEnabled() {
        return reviewEnabled;
    }

    /**
     * Sets whether the {@link ReviewRule} is active for jobs created by this factory.
     *
     * @param reviewEnabled {@code false} to disable the review phase
     */
    public void setReviewEnabled(boolean reviewEnabled) {
        this.reviewEnabled = reviewEnabled;
        set("reviewEnabled", String.valueOf(reviewEnabled));
    }

    /**
     * Returns the maximum number of review passes for jobs created by this factory.
     *
     * @return the pass cap; defaults to {@link CodingAgentJob#DEFAULT_MAX_REVIEW_PASSES}
     */
    public int getMaxReviewPasses() {
        return maxReviewPasses;
    }

    /**
     * Sets the maximum number of review passes for jobs created by this factory.
     *
     * @param maxReviewPasses cap on review sessions per job; must be positive
     * @throws IllegalArgumentException if the value is not positive
     */
    public void setMaxReviewPasses(int maxReviewPasses) {
        if (maxReviewPasses <= 0) {
            throw new IllegalArgumentException(
                    "maxReviewPasses must be positive, got: " + maxReviewPasses);
        }
        this.maxReviewPasses = maxReviewPasses;
        set("maxReviewPasses", String.valueOf(maxReviewPasses));
    }

    /**
     * Returns the post-completion command for jobs created by this factory.
     *
     * <p>When non-empty, the command is run after the agent's primary work.
     * A non-zero exit triggers a correction session. See
     * {@link CodingAgentJob#getPostCompletionCommand()} for examples.</p>
     *
     * @return the command string, or {@code null}/empty if disabled
     */
    public String getPostCompletionCommand() {
        return postCompletionCommand;
    }

    /**
     * Sets the post-completion command for jobs created by this factory.
     *
     * <p>When the command is cleared (set to {@code null} or empty), the
     * dependent {@code postCmdDir} and {@code postCmdTimeout} property keys
     * are also cleared so that a serialize/deserialize round-trip cannot
     * silently re-enable the feature with stale dependent values.</p>
     *
     * @param postCompletionCommand the shell command, or {@code null}/empty to disable
     */
    public void setPostCompletionCommand(String postCompletionCommand) {
        this.postCompletionCommand = postCompletionCommand;
        if (postCompletionCommand != null && !postCompletionCommand.isEmpty()) {
            set("postCmd", GitManagedJob.base64Encode(postCompletionCommand));
        } else {
            set("postCmd", null);
            set("postCmdDir", null);
            set("postCmdTimeout", null);
            this.postCompletionWorkingDir = null;
            this.postCompletionTimeoutSeconds = PostCompletionCommandRule.DEFAULT_TIMEOUT_SECONDS;
        }
    }

    /**
     * Returns the working directory for the post-completion command.
     *
     * @return the directory path, or {@code null} to use the job's working directory
     */
    public String getPostCompletionWorkingDir() {
        return postCompletionWorkingDir;
    }

    /**
     * Sets the working directory for the post-completion command.
     *
     * <p>Passing {@code null} clears the corresponding {@code postCmdDir}
     * property key so that a serialize/deserialize round-trip does not
     * silently re-apply a previously-set working directory.</p>
     *
     * @param postCompletionWorkingDir the path, or {@code null} for the job's working dir
     */
    public void setPostCompletionWorkingDir(String postCompletionWorkingDir) {
        this.postCompletionWorkingDir = postCompletionWorkingDir;
        if (postCompletionWorkingDir != null) {
            set("postCmdDir", GitManagedJob.base64Encode(postCompletionWorkingDir));
        } else {
            set("postCmdDir", null);
        }
    }

    /**
     * Returns the timeout in seconds for the post-completion command.
     *
     * @return timeout seconds; defaults to {@link PostCompletionCommandRule#DEFAULT_TIMEOUT_SECONDS}
     */
    public int getPostCompletionTimeoutSeconds() {
        return postCompletionTimeoutSeconds;
    }

    /**
     * Sets the timeout in seconds for the post-completion command.
     *
     * @param postCompletionTimeoutSeconds timeout in seconds (must be positive)
     */
    public void setPostCompletionTimeoutSeconds(int postCompletionTimeoutSeconds) {
        this.postCompletionTimeoutSeconds = postCompletionTimeoutSeconds;
        set("postCmdTimeout", String.valueOf(postCompletionTimeoutSeconds));
    }

    /**
     * Returns the maximum number of post-completion correction sessions for jobs
     * created by this factory.
     *
     * @return the pass cap; defaults to {@link CodingAgentJob#DEFAULT_MAX_POST_COMPLETION_PASSES}
     */
    public int getMaxPostCompletionPasses() {
        return maxPostCompletionPasses;
    }

    /**
     * Sets the maximum number of post-completion correction sessions for jobs created
     * by this factory. Protects against a flaky gate command burning the entire context
     * budget on repeated retries.
     *
     * @param maxPostCompletionPasses cap on correction sessions per job; must be positive
     * @throws IllegalArgumentException if the value is not positive
     */
    public void setMaxPostCompletionPasses(int maxPostCompletionPasses) {
        if (maxPostCompletionPasses <= 0) {
            throw new IllegalArgumentException(
                    "maxPostCompletionPasses must be positive, got: " + maxPostCompletionPasses);
        }
        this.maxPostCompletionPasses = maxPostCompletionPasses;
        set("maxPostCmdPasses", String.valueOf(maxPostCompletionPasses));
    }

    /**
     * Returns the name of the agent runner used to dispatch sessions for jobs
     * created by this factory when no per-phase override applies.
     *
     * <p>Equivalent to {@link #getDefaultRunner()}; retained as a legacy alias.</p>
     *
     * @return the runner identifier; defaults to
     *         {@link AgentRunnerRegistry#CLAUDE}
     */
    public String getRunnerName() { return runnerName; }

    /**
     * Sets the agent runner name applied to jobs created by this factory when
     * no per-phase override applies.
     *
     * <p>Equivalent to {@link #setDefaultRunner(String)}; retained as a legacy
     * alias so pre-Phase-2 callers continue to work unchanged.</p>
     *
     * @param runnerName a registered runner identifier; {@code null}/empty
     *                   resets to the Claude runner
     * @throws IllegalArgumentException when the runner is not registered
     */
    public void setRunnerName(String runnerName) {
        if (runnerName == null || runnerName.isEmpty()) {
            this.runnerName = AgentRunnerRegistry.CLAUDE;
            this.defaultRunner = AgentRunnerRegistry.CLAUDE;
            set("defaultRunner", null);
            this.phaseConfigBundle = phaseConfigBundle.withDefaultRunner(null);
            return;
        }
        AgentRunnerRegistry.validateName(runnerName);
        this.runnerName = runnerName;
        this.defaultRunner = runnerName;
        if (AgentRunnerRegistry.CLAUDE.equals(runnerName)) {
            set("defaultRunner", null);
        } else {
            set("defaultRunner", runnerName);
        }
        this.phaseConfigBundle = phaseConfigBundle.withDefaultRunner(runnerName);
    }

    /**
     * Returns the default runner applied to jobs created by this factory.
     *
     * @return the runner identifier; defaults to {@link AgentRunnerRegistry#CLAUDE}
     */
    public String getDefaultRunner() { return defaultRunner; }

    /**
     * Sets the default runner applied to jobs created by this factory.
     * Validated against {@link AgentRunnerRegistry#available()}.
     *
     * @param runnerName a registered runner identifier; {@code null}/empty
     *                   resets to the Claude runner
     * @throws IllegalArgumentException when the runner is not registered
     */
    public void setDefaultRunner(String runnerName) {
        setRunnerName(runnerName);
    }

    /**
     * Returns the runner used for {@code phase} on jobs created by this
     * factory. Falls back to {@link #getDefaultRunner()} when no override is
     * set.
     */
    public String getRunnerForPhase(Phase phase) {
        if (phase == null) return defaultRunner;
        return runnerByPhase.getOrDefault(phase, defaultRunner);
    }

    /**
     * Sets the per-phase runner override for jobs created by this factory.
     * Passing {@code null}/empty clears the override.
     *
     * @param phase      the phase to configure; must not be {@code null}
     * @param runnerName a registered runner identifier or {@code null}/empty
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
        } else {
            AgentRunnerRegistry.validateName(runnerName);
            runnerByPhase.put(phase, runnerName);
            PhaseConfig existing = phaseConfigBundle.phaseConfigs().get(phase);
            PhaseConfig updated = (existing != null ? existing : PhaseConfig.EMPTY)
                    .withRunner(runnerName);
            phaseConfigBundle = phaseConfigBundle.withPhase(phase, updated);
        }
        if (runnerByPhase.isEmpty()) {
            set("runners", null);
        } else {
            set("runners", Phase.encodeRunnerMap(runnerByPhase));
        }
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
     * Returns the unified per-phase configuration bundle propagated to jobs
     * created by this factory.
     *
     * @return the bundle, never {@code null}
     */
    public PhaseConfigBundle getPhaseConfigBundle() {
        return phaseConfigBundle;
    }

    /**
     * Replaces the per-phase configuration bundle. Updates the legacy
     * {@code defaultRunner}, {@code runnerByPhase}, {@code model}, and
     * {@code effort} fields to match so legacy callers continue to see
     * consistent state.
     *
     * @param bundle the new bundle; {@code null} resets to
     *               {@link PhaseConfigBundle#EMPTY}
     */
    public void setPhaseConfigBundle(PhaseConfigBundle bundle) {
        this.phaseConfigBundle = bundle != null ? bundle : PhaseConfigBundle.EMPTY;
        PhaseConfig def = phaseConfigBundle.defaultPhaseConfig();
        String r = def.runner();
        this.defaultRunner = (r != null && !r.isEmpty()) ? r : AgentRunnerRegistry.CLAUDE;
        this.runnerName = this.defaultRunner;
        this.model = def.model();
        this.effort = def.effort();
        // Update serialised property keys so the wire format stays current.
        if (AgentRunnerRegistry.CLAUDE.equals(this.defaultRunner)) {
            set("defaultRunner", null);
        } else {
            set("defaultRunner", this.defaultRunner);
        }
        if (this.model != null) set("model", this.model); else set("model", null);
        if (this.effort != null) set("effort", this.effort); else set("effort", null);
        runnerByPhase.clear();
        for (Map.Entry<Phase, PhaseConfig> e : phaseConfigBundle.phaseConfigs().entrySet()) {
            String phaseRunner = e.getValue().runner();
            if (phaseRunner != null && !phaseRunner.isEmpty()) {
                runnerByPhase.put(e.getKey(), phaseRunner);
            }
        }
        if (runnerByPhase.isEmpty()) {
            set("runners", null);
        } else {
            set("runners", Phase.encodeRunnerMap(runnerByPhase));
        }
        // Persist the full bundle separately. The legacy keys above only
        // preserve runner identity per phase; per-phase model / effort /
        // provider — and the default provider — are otherwise dropped on
        // the wire and re-defaulted on the receiving side.
        set("phaseConfigBundle", CodingAgentJobCodec.encodePhaseConfigBundle(
                this.phaseConfigBundle));
    }

    /**
     * Returns whether a pull request should be automatically created
     * upon successful job completion.
     */
    public boolean isAutoCreatePr() {
        return "true".equals(get("autoCreatePr"));
    }

    /**
     * Sets whether to automatically create a GitHub pull request when
     * the job completes successfully. The controller will create the PR
     * using the GitHub token associated with the workstream's organization.
     *
     * @param autoCreatePr true to auto-create a PR on success
     */
    public void setAutoCreatePr(boolean autoCreatePr) {
        set("autoCreatePr", String.valueOf(autoCreatePr));
    }

    /**
     * Returns the Python requirements for the managed venv.
     *
     * @return pip requirements.txt content, or null
     */
    public String getPythonRequirements() {
        return GitManagedJob.base64Decode(get("pyReqs"));
    }

    /**
     * Sets the Python package requirements (pip requirements.txt content)
     * that will be installed in a managed venv on the receiving node
     * before the job executes.
     *
     * @param requirements the requirements.txt content
     */
    public void setPythonRequirements(String requirements) {
        set("pyReqs", GitManagedJob.base64Encode(requirements));
    }

    /**
     * Returns the dependent repository URLs for jobs created by this factory.
     *
     * @return list of git clone URLs, or null if none configured
     */
    public List<String> getDependentRepos() {
        String encoded = get("dependentRepos");
        if (encoded == null || encoded.isEmpty()) {
            return null;
        }
        String decoded = GitManagedJob.base64Decode(encoded);
        if (decoded == null || decoded.isEmpty()) {
            return null;
        }
        List<String> repos = new ArrayList<>();
        for (String repo : decoded.split(",")) {
            String normalized = repo.trim();
            if (!normalized.isEmpty()) {
                repos.add(normalized);
            }
        }
        return repos.isEmpty() ? null : repos;
    }

    /**
     * Sets the dependent repository URLs for jobs created by this factory.
     * Each URL is cloned as a sibling of the primary working directory and
     * checked out to the same target branch as the primary repo.
     *
     * @param dependentRepos list of git clone URLs
     */
    public void setDependentRepos(List<String> dependentRepos) {
        if (dependentRepos == null || dependentRepos.isEmpty()) {
            set("dependentRepos", null);
            return;
        }
        set("dependentRepos", GitManagedJob.base64Encode(String.join(",", dependentRepos)));
    }

    /**
     * Creates the next {@link CodingAgentJob} from the prompt list, applying
     * all configuration properties. Returns {@code null} when all prompts
     * have been dispatched.
     *
     * @return the next job, or {@code null} if the prompt list is exhausted
     */
    @Override
    public Job nextJob() {
        List<String> p = getPrompts();
        if (index >= p.size()) return null;

        String workingDirectory = getWorkingDirectory();
        String repoUrl = getRepoUrl();
        String defaultWorkspacePath = getDefaultWorkspacePath();
        String targetBranch = getTargetBranch();
        String baseBranch = getBaseBranch();
        String gitUserName = getGitUserName();
        String gitUserEmail = getGitUserEmail();
        String workstreamUrl = getWorkstreamUrl();

        CodingAgentJob job = new CodingAgentJob(getTaskId(), p.get(index++));
        job.setAllowedTools(allowedTools);
        job.setWorkingDirectory(workingDirectory);
        job.setMaxTurns(maxTurns);
        job.setMaxBudgetUsd(maxBudgetUsd);

        // Establish the runner before the model so setModel() validates against
        // the correct runner's allowlist. Setting the model first would always
        // validate against the default Claude runner and reject legitimate
        // non-Claude models (e.g. opencode/openrouter), dropping the job.
        if (defaultRunner != null && !AgentRunnerRegistry.CLAUDE.equals(defaultRunner)) {
            job.setDefaultRunner(defaultRunner);
        }
        for (Map.Entry<Phase, String> e : runnerByPhase.entrySet()) {
            job.setRunnerForPhase(e.getKey(), e.getValue());
        }

        if (model != null) {
            job.setModel(model);
        }
        if (effort != null) {
            job.setEffort(effort);
        }

        String desc = getDescription();
        if (desc != null) {
            job.setDescription(desc);
        }

        if (repoUrl != null) {
            job.setRepoUrl(repoUrl);
        }
        if (defaultWorkspacePath != null) {
            job.setDefaultWorkspacePath(defaultWorkspacePath);
        }

        if (targetBranch != null) {
            job.setTargetBranch(targetBranch);
            job.setPushToOrigin(isPushToOrigin());
        }
        if (baseBranch != null) {
            job.setBaseBranch(baseBranch);
        }
        if (gitUserName != null) {
            job.setGitUserName(gitUserName);
        }
        if (gitUserEmail != null) {
            job.setGitUserEmail(gitUserEmail);
        }

        if (workstreamUrl != null) {
            job.setWorkstreamUrl(workstreamUrl);
        }

        if (arManagerUrl != null) {
            job.setArManagerUrl(arManagerUrl);
        }
        if (arManagerToken != null) {
            job.setArManagerToken(arManagerToken);
        }
        if (pushedToolsConfig != null) {
            job.setPushedToolsConfig(pushedToolsConfig);
        } else {
            warn("no pushedToolsConfig to propagate to " + job.getTaskId());
        }

        if (agentEnvJson != null) {
            job.setAgentEnv(JsonFieldExtractor.parseStringObject(agentEnvJson));
        }

        if (planningDocument != null) {
            job.setPlanningDocument(planningDocument);
        }

        job.setProtectTestFiles(isProtectTestFiles());
        job.setEnforceChanges(isEnforceChanges());
        job.setDeduplicationMode(deduplicationMode);
        job.setMaxDeduplicationPasses(maxDeduplicationPasses);
        job.setEnforceMavenDependencies(enforceMavenDependencies);
        job.setEnforceOrganizationalPlacement(enforceOrganizationalPlacement);
        job.setReviewEnabled(reviewEnabled);
        job.setMaxReviewPasses(maxReviewPasses);
        if (postCompletionCommand != null && !postCompletionCommand.isEmpty()) {
            job.setPostCompletionCommand(postCompletionCommand);
            if (postCompletionWorkingDir != null) {
                job.setPostCompletionWorkingDir(postCompletionWorkingDir);
            }
            if (postCompletionTimeoutSeconds != PostCompletionCommandRule.DEFAULT_TIMEOUT_SECONDS) {
                job.setPostCompletionTimeoutSeconds(postCompletionTimeoutSeconds);
            }
            if (maxPostCompletionPasses != CodingAgentJob.DEFAULT_MAX_POST_COMPLETION_PASSES) {
                job.setMaxPostCompletionPasses(maxPostCompletionPasses);
            }
        }

        String pyReqs = getPythonRequirements();
        if (pyReqs != null) {
            job.setPythonRequirements(pyReqs);
        }

        List<String> depRepos = getDependentRepos();
        if (depRepos != null && !depRepos.isEmpty()) {
            job.setDependentRepos(depRepos);
        }

        for (Map.Entry<String, String> entry : getRequiredLabels().entrySet()) {
            job.setRequiredLabel(entry.getKey(), entry.getValue());
        }

        // Propagate the full per-phase config bundle so per-phase model and
        // effort overrides reach the job. The default runner and per-phase
        // runners were already applied above (before the model) so setModel()
        // validated against the correct runner; setPhaseConfigBundle()
        // re-derives the legacy fields from the bundle, restating those same
        // values plus the per-phase model/effort overrides.
        if (!phaseConfigBundle.isEmpty()) {
            job.setPhaseConfigBundle(phaseConfigBundle);
        }

        return job;
    }

    /**
     * Not used — jobs are created via {@link #nextJob()}.
     *
     * @param data ignored
     * @return always {@code null}
     */
    @Override
    public Job createJob(String data) {
        return null;
    }

    /**
     * Returns the fraction of prompts that have been dispatched as jobs,
     * in the range {@code [0.0, 1.0]}.
     *
     * @return completeness ratio; {@code 1.0} when the prompt list is empty
     */
    @Override
    public double getCompleteness() {
        List<String> p = getPrompts();
        return p.isEmpty() ? 1.0 : index / (double) p.size();
    }

    /**
     * Deserializes a property from the wire format.
     *
     * <p>Git-related properties (workDir, repoUrl, branch, etc.) are
     * stored directly in the {@link AbstractJobFactory} properties map
     * and decoded on read by the corresponding getter methods.  This
     * avoids duplicating the decode logic that also exists in
     * {@link GitManagedJob#set(String, String)}.</p>
     *
     * @param key   the property key
     * @param value the property value
     */
    @Override
    public void set(String key, String value) {
        super.set(key, value);

        switch (key) {
            case "tools":
                this.allowedTools = value;
                break;
            case "maxTurns":
                this.maxTurns = Integer.parseInt(value);
                break;
            case "maxBudget":
                this.maxBudgetUsd = Double.parseDouble(value);
                break;
            case "model":
                this.model = (value == null || value.isEmpty()) ? null : value;
                break;
            case "effort":
                this.effort = (value == null || value.isEmpty()) ? null : value;
                break;
            case "arManagerUrl":
                this.arManagerUrl = GitManagedJob.base64Decode(value);
                break;
            case "arManagerToken":
                this.arManagerToken = GitManagedJob.base64Decode(value);
                break;
            case "pushedToolsConfig":
                this.pushedToolsConfig = GitManagedJob.base64Decode(value);
                break;
            case "agentEnv":
                this.agentEnvJson = GitManagedJob.base64Decode(value);
                break;
            case "planDoc":
                this.planningDocument = GitManagedJob.base64Decode(value);
                break;
            case "enforceChanges":
                break;
            case "runner":
                // Legacy single-runner key; honored only when no explicit
                // defaultRunner has been decoded yet.
                if (AgentRunnerRegistry.CLAUDE.equals(defaultRunner)) {
                    String legacy = (value == null || value.isEmpty())
                            ? AgentRunnerRegistry.CLAUDE : value;
                    this.runnerName = legacy;
                    this.defaultRunner = legacy;
                }
                break;
            case "defaultRunner":
                String resolvedDefault = (value == null || value.isEmpty())
                        ? AgentRunnerRegistry.CLAUDE : value;
                this.runnerName = resolvedDefault;
                this.defaultRunner = resolvedDefault;
                break;
            case "runners":
                runnerByPhase.clear();
                runnerByPhase.putAll(Phase.decodeRunnerMap(value, this::warn));
                break;
            case "phaseConfigBundle":
                // Assign the field directly: setPhaseConfigBundle would
                // re-emit set("phaseConfigBundle", ...) and recurse. The
                // legacy keys above already carry runner/model/effort and
                // arrive in their own set() calls; the bundle here just
                // restores per-phase model/effort/provider.
                this.phaseConfigBundle =
                        CodingAgentJobCodec.decodePhaseConfigBundle(value);
                break;
            default:
                setEnforcementFlag(key, value);
        }
    }

    /**
     * Handles enforcement-flag key/value pairs, updating the corresponding local fields
     * so that {@link #nextJob()} can propagate them to created jobs.
     *
     * <p>Called from {@link #set(String, String)} for keys not handled by the main switch.
     * Uses early return rather than break so the switch bodies here are textually distinct
     * from the equivalent cases in {@link CodingAgentJob#set(String, String)}.</p>
     *
     * @param key   the property key
     * @param value the property value
     */
    private void setEnforcementFlag(String key, String value) {
        switch (key) {
            case "dedupMode":
                this.deduplicationMode = value;
                return;
            case "maxDedupPasses":
                this.maxDeduplicationPasses = CodingAgentJobCodec.parsePositiveOrDefault(
                        value, CodingAgentJob.DEFAULT_MAX_DEDUP_PASSES);
                return;
            case "enforceMavenDeps":
                this.enforceMavenDependencies = Boolean.parseBoolean(value);
                return;
            case "enforceOrgPlacement":
                this.enforceOrganizationalPlacement = Boolean.parseBoolean(value);
                return;
            case "reviewEnabled":
                this.reviewEnabled = Boolean.parseBoolean(value);
                return;
            case "maxReviewPasses":
                this.maxReviewPasses = CodingAgentJobCodec.parsePositiveOrDefault(
                        value, CodingAgentJob.DEFAULT_MAX_REVIEW_PASSES);
                return;
            case "postCmd":
                this.postCompletionCommand = GitManagedJob.base64Decode(value);
                return;
            case "postCmdDir":
                this.postCompletionWorkingDir = GitManagedJob.base64Decode(value);
                return;
            case "postCmdTimeout":
                this.postCompletionTimeoutSeconds = (value == null)
                        ? PostCompletionCommandRule.DEFAULT_TIMEOUT_SECONDS
                        : Integer.parseInt(value);
                return;
            case "maxPostCmdPasses":
                this.maxPostCompletionPasses = CodingAgentJobCodec.parsePositiveOrDefault(
                        value, CodingAgentJob.DEFAULT_MAX_POST_COMPLETION_PASSES);
                return;
            default:
                // Unknown key; already stored in properties map by AbstractJobFactory.set().
        }
    }

    /**
     * Returns a concise description of this factory for logging.
     *
     * @return a string including the prompt count, tools, branch, and completeness
     */
    @Override
    public String toString() {
        return "CodingAgentJobFactory[prompts=" + getPrompts().size() +
               ", tools=" + allowedTools +
               ", branch=" + getTargetBranch() +
               ", completeness=" + getCompleteness() + "]";
    }
}
