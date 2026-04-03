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

import io.flowtree.job.AbstractJobFactory;
import io.flowtree.job.Job;
import org.almostrealism.util.KeyUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Factory for producing {@link ClaudeCodeJob} instances from a list of prompts.
 *
 * <p>Each prompt becomes a separate job, allowing the Flowtree system to
 * distribute prompts across multiple nodes. When a node finishes a prompt,
 * it becomes idle and can pick up the next job.</p>
 *
 * <p>All configuration (target branch, git credentials, tool list, etc.) is
 * stored in the inherited {@link AbstractJobFactory} properties map so that
 * the factory survives wire serialization and deserialization unchanged.</p>
 *
 * <p>For backward source compatibility, {@link ClaudeCodeJob} publishes
 * {@code ClaudeCodeJob.Factory} as a thin subclass of this class.</p>
 *
 * @author Michael Murray
 * @see ClaudeCodeJob
 */
public class ClaudeCodeJobFactory extends AbstractJobFactory {

    /** Cached decoded list of prompts; populated lazily from the serialized properties. */
    private List<String> prompts;

    /** Short human-readable description for jobs created by this factory. */
    private String description;

    /** Index of the next prompt to be dispatched as a job. */
    private int index;

    /** Comma-separated list of tools Claude Code is permitted to invoke. */
    private String allowedTools = ClaudeCodeJob.DEFAULT_TOOLS;

    /** Maximum number of agentic turns Claude Code may take per job. */
    private int maxTurns = 50;

    /** Maximum spend budget per job in US dollars. */
    private double maxBudgetUsd = 10.0;

    /** HTTP base URL of the ar-manager service, or {@code null} if not configured. */
    private String arManagerUrl;

    /** Bearer token for authenticating against the ar-manager service. */
    private String arManagerToken;

    /** Optional planning document text to inject into the Claude Code system prompt. */
    private String planningDocument;

    /** Whether jobs created by this factory must produce at least one staged file change. */
    private boolean enforceChanges;

    /**
     * Deduplication mode applied to jobs created by this factory.
     * {@code null} disables deduplication.
     * See {@link ClaudeCodeJob#DEDUP_LOCAL} and {@link ClaudeCodeJob#DEDUP_SPAWN}.
     */
    private String deduplicationMode;

    /**
     * Default constructor for deserialization.
     */
    public ClaudeCodeJobFactory() {
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
    public ClaudeCodeJobFactory(String... prompts) {
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
    public ClaudeCodeJobFactory(List<String> prompts) {
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
        String code = String.join(ClaudeCodeJob.PROMPT_SEPARATOR, prompts);
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
                    : new ArrayList<>(List.of(code.split(ClaudeCodeJob.PROMPT_SEPARATOR)));
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
        this.enforceChanges = enforceChanges;
        set("enforceChanges", String.valueOf(enforceChanges));
    }

    /**
     * Returns the deduplication mode applied to jobs created by this factory,
     * or {@code null} if deduplication is disabled.
     *
     * @return {@link ClaudeCodeJob#DEDUP_LOCAL}, {@link ClaudeCodeJob#DEDUP_SPAWN},
     *         or {@code null}
     */
    public String getDeduplicationMode() {
        return deduplicationMode;
    }

    /**
     * Sets the deduplication mode for jobs created by this factory.
     *
     * @param deduplicationMode {@link ClaudeCodeJob#DEDUP_LOCAL},
     *                          {@link ClaudeCodeJob#DEDUP_SPAWN}, or {@code null}
     */
    public void setDeduplicationMode(String deduplicationMode) {
        this.deduplicationMode = deduplicationMode;
        if (deduplicationMode != null) {
            set("dedupMode", deduplicationMode);
        }
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
     * Creates the next {@link ClaudeCodeJob} from the prompt list, applying
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

        ClaudeCodeJob job = new ClaudeCodeJob(getTaskId(), p.get(index++));
        job.setAllowedTools(allowedTools);
        job.setWorkingDirectory(workingDirectory);
        job.setMaxTurns(maxTurns);
        job.setMaxBudgetUsd(maxBudgetUsd);

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

        if (planningDocument != null) {
            job.setPlanningDocument(planningDocument);
        }

        job.setProtectTestFiles(isProtectTestFiles());
        job.setEnforceChanges(isEnforceChanges());
        if (deduplicationMode != null) {
            job.setDeduplicationMode(deduplicationMode);
        }

        String pyReqs = getPythonRequirements();
        if (pyReqs != null) {
            job.setPythonRequirements(pyReqs);
        }

        for (Map.Entry<String, String> entry : getRequiredLabels().entrySet()) {
            job.setRequiredLabel(entry.getKey(), entry.getValue());
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
            case "arManagerUrl":
                this.arManagerUrl = GitManagedJob.base64Decode(value);
                break;
            case "arManagerToken":
                this.arManagerToken = GitManagedJob.base64Decode(value);
                break;
            case "planDoc":
                this.planningDocument = GitManagedJob.base64Decode(value);
                break;
            case "enforceChanges":
                this.enforceChanges = Boolean.parseBoolean(value);
                break;
            case "dedupMode":
                this.deduplicationMode = value;
                break;
            default:
                break;
        }
    }

    /**
     * Returns a concise description of this factory for logging.
     *
     * @return a string including the prompt count, tools, branch, and completeness
     */
    @Override
    public String toString() {
        return "ClaudeCodeJobFactory[prompts=" + getPrompts().size() +
               ", tools=" + allowedTools +
               ", branch=" + getTargetBranch() +
               ", completeness=" + getCompleteness() + "]";
    }
}
