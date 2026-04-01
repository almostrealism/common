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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;


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

    /** Maximum number of enforcement retries before giving up. */
    private static final int MAX_ENFORCEMENT_RETRIES = 5;

    @Override
    protected void doWork() {
        executeSingleRun();

        // Enforcement loop: when enforceChanges is enabled and the agent produced no
        // file changes, restart the session.  Git integrity violations are a completely
        // separate concern -- if the agent committed (HEAD moved), exit immediately and
        // let the tampering-detection path in GitManagedJob.run() handle it.
        if (enforceChanges && !hasAgentCommitted()) {
            while (enforcementAttempt < MAX_ENFORCEMENT_RETRIES
                    && !hasUncommittedChanges()
                    && !hasAgentCommitted()) {
                enforcementAttempt++;
                log("Enforcement loop: attempt " + enforcementAttempt
                    + " produced no changes -- restarting (attempt "
                    + (enforcementAttempt + 1) + ")");
                executeSingleRun();
            }

            if (!hasUncommittedChanges() && !hasAgentCommitted()) {
                warn("Enforcement loop: exhausted " + MAX_ENFORCEMENT_RETRIES
                    + " retries without producing changes");
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

    @Override
    protected boolean validateChanges() throws Exception {
        if (!isProtectTestFiles()) {
            return true;
        }

        // Use the existing detect-test-hiding.sh script for diff auditing
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
            // Exit code 2 = violations found
            warn("Test-hiding violations detected - aborting commit:\n" + auditOutput);
            return false;
        } else if (code != 0) {
            warn("detect-test-hiding.sh exited with code " + code + ": " + auditOutput);
            // Non-violation error (code 1 = bad args); don't block on script bugs
        }

        log("Test integrity check passed");
        return true;
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
