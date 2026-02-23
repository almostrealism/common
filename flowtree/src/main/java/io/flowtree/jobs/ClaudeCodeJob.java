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
 */
public class ClaudeCodeJob extends GitManagedJob {
    public static final String PROMPT_SEPARATOR = ";;PROMPT;;";
    public static final String DEFAULT_TOOLS = "Read,Edit,Write,Bash,Glob,Grep";

    private String prompt;
    private String allowedTools;
    private int maxTurns;
    private double maxBudgetUsd;
    private String centralizedMcpConfig;
    private String pushedToolsConfig;
    private Map<String, String> workstreamEnv;
    private String planningDocument;

    private final McpConfigBuilder mcpConfigBuilder = new McpConfigBuilder();
    private final ManagedToolsDownloader toolsDownloader = new ManagedToolsDownloader(mcpConfigBuilder);
    private static final ObjectMapper outputMapper = new ObjectMapper();

    private String sessionId;
    private String output;
    private int exitCode;
    private long durationMs;
    private long durationApiMs;
    private double costUsd;
    private int numTurns;
    private String subtype;
    private boolean isError;
    private int permissionDenials;
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
        return prompt != null && prompt.length() > 50
            ? prompt.substring(0, 47) + "..."
            : prompt;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public String getAllowedTools() {
        return allowedTools;
    }

    public void setAllowedTools(String allowedTools) {
        this.allowedTools = allowedTools;
    }

    public int getMaxTurns() {
        return maxTurns;
    }

    public void setMaxTurns(int maxTurns) {
        this.maxTurns = maxTurns;
    }

    public double getMaxBudgetUsd() {
        return maxBudgetUsd;
    }

    public void setMaxBudgetUsd(double maxBudgetUsd) {
        this.maxBudgetUsd = maxBudgetUsd;
    }

    /**
     * Returns the centralized MCP configuration JSON string.
     *
     * <p>When set, this JSON maps server names to their HTTP URLs and
     * tool names. Servers in this config are connected via HTTP instead
     * of stdio, and their tools are included in the allowed tools list.</p>
     */
    public String getCentralizedMcpConfig() {
        return centralizedMcpConfig;
    }

    /**
     * Sets the centralized MCP configuration JSON string.
     *
     * @param centralizedMcpConfig JSON mapping server names to URLs and tool names
     */
    public void setCentralizedMcpConfig(String centralizedMcpConfig) {
        this.centralizedMcpConfig = centralizedMcpConfig;
    }

    /**
     * Returns the pushed MCP tools configuration JSON string.
     *
     * <p>When set, this JSON maps tool server names to their download URLs
     * and tool names. Tools are downloaded from the controller and run
     * locally via stdio in the agent's container.</p>
     */
    public String getPushedToolsConfig() {
        return pushedToolsConfig;
    }

    /**
     * Sets the pushed MCP tools configuration JSON string.
     *
     * @param pushedToolsConfig JSON mapping server names to download URLs and tool names
     */
    public void setPushedToolsConfig(String pushedToolsConfig) {
        this.pushedToolsConfig = pushedToolsConfig;
    }

    /**
     * Returns per-workstream environment variables that override the global
     * pushed tool env in the final MCP stdio config.
     */
    public Map<String, String> getWorkstreamEnv() {
        return workstreamEnv;
    }

    /**
     * Sets per-workstream environment variables for pushed tools.
     *
     * @param workstreamEnv map of environment variable names to values
     */
    public void setWorkstreamEnv(Map<String, String> workstreamEnv) {
        this.workstreamEnv = workstreamEnv;
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
     * Slack instructions appear only when a workstream URL is configured,
     * GitHub instructions only when the MCP config includes ar-github,
     * commit.txt instructions only when git management is active, and
     * budget/turn/task/workstream context is included when available.</p>
     */
    private String buildInstructionPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("You are working autonomously as a coding agent. ");
        sb.append("There is no TTY and no interactive session --do not attempt to wait ");
        sb.append("for user input or interactive chat responses.\n\n");

        // Slack instructions -only when a workstream URL is configured
        if (getWorkstreamUrl() != null && !getWorkstreamUrl().isEmpty()) {
            sb.append("## Slack Communication\n");
            sb.append("You MUST use the Slack MCP tool (slack_send_message) to provide status ");
            sb.append("updates to the user throughout your work. Specifically:\n");
            sb.append("- Send an update when you begin working on the task\n");
            sb.append("- Send updates when you reach significant milestones or make key decisions\n");
            sb.append("- Send an update with your findings or results before you finish\n");
            sb.append("- If you encounter blockers or need clarification, send a message describing the issue\n");
            sb.append("Do not wait for a reply --continue working after sending a message.\n\n");

            sb.append("## Permission Denials\n");
            sb.append("If any tool call is denied due to a permission issue, you MUST immediately ");
            sb.append("send a Slack message describing:\n");
            sb.append("- Which tool was denied (exact tool name)\n");
            sb.append("- What you were trying to do with it\n");
            sb.append("- The error message, if any\n");
            sb.append("Permission denials should never happen in this environment. ");
            sb.append("Reporting them is critical for diagnosing configuration issues.\n\n");

            sb.append("## Non-Code Requests\n");
            sb.append("If the user's request does not require code changes (e.g., a question about ");
            sb.append("the codebase, a request to run a command, check status, or perform an action) ");
            sb.append("it is perfectly fine to answer via Slack and exit without modifying any files. ");
            sb.append("Not every task requires code changes.\n\n");

            sb.append("## Justifying No Code Changes\n");
            sb.append("If you finish your work without making any changes to files in the git repository, ");
            sb.append("you MUST send a Slack message explaining why no code changes were needed. ");
            sb.append("This justification should clearly explain either:\n");
            sb.append("- Why the user's request was fulfilled without code changes ");
            sb.append("(e.g., it was an informational question, a status check, or a run command)\n");
            sb.append("- Why you were unable to make the requested changes ");
            sb.append("(e.g., a blocker, missing context, or ambiguity that needs clarification)\n");
            sb.append("This requirement does NOT apply if you have already fully addressed the user's ");
            sb.append("request through earlier Slack messages (e.g., answering a question, reporting ");
            sb.append("results). In that case, the earlier messages serve as sufficient justification.\n\n");
        }

        // GitHub instructions -- ar-github is always enabled
        sb.append("You can read and respond to GitHub PR review comments using the GitHub MCP tools ");
        sb.append("(github_pr_find, github_pr_review_comments, github_pr_conversation, github_pr_reply). ");
        sb.append("Use these to check for code review feedback and address it.\n\n");

        // Test integrity policy -only when protectTestFiles is enabled
        if (isProtectTestFiles()) {
            sb.append("## Test Integrity Policy\n");
            sb.append("You MUST NOT modify test files that exist on the base branch (");
            sb.append(getBaseBranch() != null ? getBaseBranch() : "master");
            sb.append("). Fix the production code instead. ");
            sb.append("Tests you introduced on this branch may be modified. ");
            sb.append("The commit harness will reject changes to protected test files.\n\n");
        }

        // Git commit instructions -conditional on git management being active
        if (getTargetBranch() != null && !getTargetBranch().isEmpty()) {
            sb.append("Do NOT make git commits. Your work will be committed by the harness ");
            sb.append("after you finish. If you want to control the commit message, write it ");
            sb.append("to a file called `commit.txt` in the working directory root.\n\n");
        } else {
            sb.append("Do NOT make git commits. Your work will be committed by the harness ");
            sb.append("after you finish.\n\n");
        }

        // Merge conflict instructions -- when the base branch has diverged
        if (hasMergeConflicts()) {
            sb.append("## Merge Conflicts\n");
            sb.append("IMPORTANT: The base branch (origin/").append(getBaseBranch());
            sb.append(") has diverged from your working branch (").append(getTargetBranch());
            sb.append(") and a merge attempt produced conflicts. ");
            sb.append("The merge was aborted so your working directory is clean, ");
            sb.append("but you MUST resolve these conflicts as part of your work.\n\n");
            sb.append("To resolve:\n");
            sb.append("1. Run `git merge origin/").append(getBaseBranch()).append("`\n");
            sb.append("2. Resolve the conflicts in the following files:\n");
            for (String file : getMergeConflictFiles()) {
                sb.append("   - `").append(file).append("`\n");
            }
            sb.append("3. After resolving all conflicts, stage the resolved files with `git add`\n");
            sb.append("4. Complete the merge with `git commit --no-edit`\n");
            sb.append("5. Then proceed with the user's requested work\n\n");
            sb.append("Do NOT skip conflict resolution. The merge must be completed before ");
            sb.append("any other changes are made.\n\n");
        }

        // Remote branch context -- user instructions always refer to remote branches
        sb.append("## Branch Context\n");
        sb.append("This work is being done in a sandboxed environment. ");
        sb.append("When the user's instructions mention branch names (e.g., \"this works ");
        sb.append("on master\", \"compare with develop\", \"based on main\"), they are ALWAYS ");
        sb.append("referring to the remote branch (origin/<branch>). Local branches in this ");
        sb.append("sandbox may be stale or absent. Always use `origin/<branch>` when ");
        sb.append("comparing, cherry-picking, or referencing other branches. For example:\n");
        sb.append("- \"works on master\" means `origin/master`\n");
        sb.append("- \"merge from develop\" means `origin/develop`\n");
        sb.append("- \"diff against main\" means `git diff origin/main`\n\n");

        // Working directory and branch context
        String workDir = getWorkingDirectory();
        sb.append("Your working directory is: ");
        sb.append(workDir != null ? workDir : System.getProperty("user.dir"));
        sb.append("\n");
        if (getTargetBranch() != null && !getTargetBranch().isEmpty()) {
            sb.append("Target branch: ").append(getTargetBranch()).append("\n");
        }
        sb.append("\n");

        // Branch awareness and anti-loop guidance
        if (getTargetBranch() != null && !getTargetBranch().isEmpty()) {
            sb.append("## Branch Awareness and Continuity\n");
            sb.append("IMPORTANT: You are not the first agent to work on this branch. ");
            sb.append("Previous coding agent sessions have already made changes that are ");
            sb.append("reflected in the git history. Those changes are YOUR team's work -- ");
            sb.append("treat them as intentional progress, not as problems to undo.\n\n");

            sb.append("### Catching Up on Prior Work\n");
            sb.append("Before making any changes, you MUST use the `branch_catchup` tool ");
            sb.append("to understand what has already been done on this branch:\n");
            sb.append("```\n");
            sb.append("mcp__ar-consultant__branch_catchup repo_url:\"<from git remote ");
            sb.append("get-url origin>\" branch:\"").append(getTargetBranch()).append("\"\n");
            sb.append("```\n");
            sb.append("This will show you memories from prior agent sessions and the ");
            sb.append("commit timeline, synthesized into a briefing.\n\n");

            sb.append("### Recording Your Work\n");
            sb.append("When you make decisions, discover issues, or complete tasks, ");
            sb.append("store memories with the branch context so future sessions can ");
            sb.append("pick up where you left off:\n");
            sb.append("```\n");
            sb.append("mcp__ar-consultant__remember content:\"<what you learned>\" ");
            sb.append("repo_url:\"<repo url>\" branch:\"").append(getTargetBranch());
            sb.append("\" tags:[\"progress\"]\n");
            sb.append("```\n\n");

            sb.append("### CRITICAL: Avoid Add/Revert Loops\n");
            sb.append("A common failure mode for coding agents is getting stuck in a loop:\n");
            sb.append("1. Agent adds a feature or makes changes\n");
            sb.append("2. CI pipeline fails\n");
            sb.append("3. Agent reverts the changes to \"fix\" the failure\n");
            sb.append("4. Next session re-adds the same changes\n");
            sb.append("5. CI fails again, agent reverts again\n");
            sb.append("6. Repeat indefinitely\n\n");
            sb.append("This is NEVER the right approach. If CI fails after your changes:\n");
            sb.append("- **DO NOT** simply revert the changes. That undoes prior agent work.\n");
            sb.append("- **DO** investigate the actual failure and fix it properly.\n");
            sb.append("- **DO** check `branch_catchup` to see if this same pattern ");
            sb.append("has already occurred in prior sessions.\n");
            sb.append("- **DO** store a memory describing the CI failure and your ");
            sb.append("analysis so the next session doesn't repeat the same mistake.\n");
            sb.append("- If the failure is in code YOU did not write (i.e., pre-existing ");
            sb.append("on the branch from prior sessions), investigate whether it's a ");
            sb.append("real bug that needs fixing vs. an environment/configuration issue.\n\n");
        }

        // Budget and turn limits
        if (maxBudgetUsd > 0 || maxTurns > 0) {
            sb.append("You have");
            if (maxBudgetUsd > 0) {
                sb.append(String.format(" a budget of $%.2f", maxBudgetUsd));
            }
            if (maxTurns > 0) {
                sb.append(maxBudgetUsd > 0 ? " and" : "");
                sb.append(" a maximum of ").append(maxTurns).append(" turns");
            }
            sb.append(". Pace yourself accordingly.\n\n");
        }

        // Task ID and workstream context
        if (getTaskId() != null && !getTaskId().isEmpty()) {
            sb.append("Task ID: ").append(getTaskId()).append("\n");
        }
        if (getWorkstreamUrl() != null && !getWorkstreamUrl().isEmpty()) {
            sb.append("Workstream URL: ").append(getWorkstreamUrl()).append("\n");
        }
        if (getTaskId() != null || getWorkstreamUrl() != null) {
            sb.append("\n");
        }

        // Planning document context -only when the workstream has one configured
        if (planningDocument != null && !planningDocument.isEmpty()) {
            sb.append("## Planning Document\n");
            sb.append("This branch has a planning document that describes the broader goal of ");
            sb.append("the work being done. You MUST read this document before starting work:\n");
            sb.append("  `").append(planningDocument).append("`\n\n");
            sb.append("The user's request below is a sub-task of this broader goal. ");
            sb.append("Do NOT revert or undo work from prior sessions that supports the planning ");
            sb.append("document's goals, even if the current sub-task doesn't directly relate to it. ");
            sb.append("If the sub-task conflicts with the planning document, note the conflict in ");
            sb.append("a Slack message and proceed with the sub-task unless the conflict is severe.\n\n");
        }

        sb.append("--- BEGIN USER REQUEST ---\n");
        sb.append(prompt);
        sb.append("\n--- END USER REQUEST ---");

        return sb.toString();
    }

    /**
     * Configures the MCP config builder with current job state.
     */
    private void configureMcpBuilder() {
        mcpConfigBuilder.setCentralizedMcpConfig(centralizedMcpConfig);
        mcpConfigBuilder.setPushedToolsConfig(pushedToolsConfig);
        mcpConfigBuilder.setWorkstreamEnv(workstreamEnv);
        mcpConfigBuilder.setWorkstreamUrl(getWorkstreamUrl());
        Path workDir = getWorkingDirectory() != null
            ? Path.of(getWorkingDirectory()) : Path.of(System.getProperty("user.dir"));
        mcpConfigBuilder.setWorkingDirectory(workDir);
    }

    @Override
    protected void doWork() {
        // Download pushed tools from the controller if needed
        toolsDownloader.ensurePushedTools(pushedToolsConfig);

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

        // Verify MCP tool server files exist before launching Claude Code
        Path mcpWorkDir = getWorkingDirectory() != null
            ? Path.of(getWorkingDirectory()) : Path.of(System.getProperty("user.dir"));
        toolsDownloader.verifyMcpToolFiles(mcpWorkDir);

        // MCP config (ar-github always; ar-slack when workstream URL is set)
        command.add("--mcp-config");
        command.add(mcpConfigBuilder.buildMcpConfig());

        try {
            ProcessBuilder pb = new ProcessBuilder(command);

            String workDir = getWorkingDirectory();
            if (workDir != null) {
                pb.directory(new File(workDir));
            }

            // Set resolved workstream URL for MCP servers (ar-slack, ar-github).
            // resolveWorkstreamUrl() replaces the 0.0.0.0 placeholder with the
            // actual controller host from FLOWTREE_ROOT_HOST, which is required
            // when the agent runs in a Docker container.
            String wsUrl = resolveWorkstreamUrl();
            if (wsUrl != null && !wsUrl.isEmpty()) {
                pb.environment().put("AR_WORKSTREAM_URL", wsUrl);
                log("AR_WORKSTREAM_URL: " + wsUrl);
            }

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

    @Override
    protected JobCompletionEvent createEvent(Exception error) {
        if (error != null) {
            return ClaudeCodeJobEvent.failed(
                getTaskId(), getTaskString(),
                error.getMessage(), error
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
        Path auditScript = resolveWorkingPath("tools/ci/detect-test-hiding.sh");
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
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();

        if (code == 2) {
            // Exit code 2 = violations found
            warn("Test-hiding violations detected - aborting commit:\n" + output);
            return false;
        } else if (code != 0) {
            warn("detect-test-hiding.sh exited with code " + code + ": " + output);
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
        String resultJson = io.flowtree.JsonFieldExtractor.extractLastJsonObject(jsonOutput, "result");
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

    private static String getTextOrNull(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return (child != null && child.isTextual()) ? child.asText() : null;
    }

    /**
     * Serializes a {@link Map} of string entries to a JSON object string
     * using Jackson.
     *
     * @param map the map to serialize
     * @return JSON object string
     */
    private static String mapToJsonObject(Map<String, String> map) {
        try {
            return outputMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * Parses a JSON object string into a {@link Map} of string entries
     * using Jackson.
     *
     * @param json the JSON object string
     * @return parsed map, empty if input is null or unparseable
     */
    private static Map<String, String> parseJsonObjectToMap(String json) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        if (json == null) return result;
        try {
            JsonNode root = outputMapper.readTree(json);
            java.util.Iterator<String> fieldNames = root.fieldNames();
            while (fieldNames.hasNext()) {
                String key = fieldNames.next();
                JsonNode valueNode = root.get(key);
                if (valueNode.isTextual()) {
                    result.put(key, valueNode.asText());
                }
            }
        } catch (Exception e) {
            // Return empty map on parse failure
        }
        return result;
    }

    @Override
    public String encode() {
        StringBuilder sb = new StringBuilder();
        sb.append(super.encode());
        sb.append("::prompt:=").append(base64Encode(prompt));
        sb.append("::tools:=").append(base64Encode(allowedTools));
        sb.append("::maxTurns:=").append(maxTurns);
        sb.append("::maxBudget:=").append(maxBudgetUsd);
        if (centralizedMcpConfig != null) {
            sb.append("::centralMcp:=").append(base64Encode(centralizedMcpConfig));
        }
        if (pushedToolsConfig != null) {
            sb.append("::pushedTools:=").append(base64Encode(pushedToolsConfig));
        }
        if (workstreamEnv != null && !workstreamEnv.isEmpty()) {
            sb.append("::wsEnv:=").append(base64Encode(mapToJsonObject(workstreamEnv)));
        }
        if (planningDocument != null) {
            sb.append("::planDoc:=").append(base64Encode(planningDocument));
        }
        sb.append("::protectTests:=").append(isProtectTestFiles());
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
            case "centralMcp":
                this.centralizedMcpConfig = base64Decode(value);
                break;
            case "pushedTools":
                this.pushedToolsConfig = base64Decode(value);
                break;
            case "wsEnv":
                this.workstreamEnv = parseJsonObjectToMap(base64Decode(value));
                break;
            case "planDoc":
                this.planningDocument = base64Decode(value);
                break;
            case "protectTests":
                setProtectTestFiles(Boolean.parseBoolean(value));
                break;
            default:
                // Delegate to parent for git-related properties
                super.set(key, value);
        }
    }

    /**
     * Output record for ClaudeCodeJob results.
     */
    public static class ClaudeCodeJobOutput extends JobOutput {
        private final String prompt;
        private final String sessionId;
        private final int exitCode;

        public ClaudeCodeJobOutput(String taskId, String prompt, String output, String sessionId, int exitCode) {
            super(taskId, "", "", output);
            this.prompt = prompt;
            this.sessionId = sessionId;
            this.exitCode = exitCode;
        }

        public String getPrompt() { return prompt; }
        public String getSessionId() { return sessionId; }
        public int getExitCode() { return exitCode; }

        @Override
        public String toString() {
            return "ClaudeCodeJobOutput{taskId=" + getTaskId() + ", exitCode=" + exitCode +
                   ", sessionId=" + sessionId + "}";
        }
    }

    /**
     * Factory for producing {@link ClaudeCodeJob} instances from a list of prompts.
     *
     * <p>Each prompt becomes a separate job, allowing the Flowtree system to
     * distribute prompts across multiple nodes. When a node finishes a prompt,
     * it becomes idle and can pick up the next job.</p>
     */
    public static class Factory extends AbstractJobFactory {
        private List<String> prompts;
        private int index;
        private String allowedTools = DEFAULT_TOOLS;
        private String workingDirectory;
        private String repoUrl;
        private String defaultWorkspacePath;
        private int maxTurns = 50;
        private double maxBudgetUsd = 10.0;
        private String targetBranch;
        private String baseBranch;
        private boolean pushToOrigin = true;
        private String workstreamUrl;
        private String gitUserName;
        private String gitUserEmail;
        private String centralizedMcpConfig;
        private String pushedToolsConfig;
        private Map<String, String> workstreamEnv;
        private String planningDocument;
        private boolean protectTestFiles = false;

        /**
         * Default constructor for deserialization.
         */
        public Factory() {
            super(KeyUtils.generateKey());
            // Persist taskId in properties so it survives wire serialization.
            // AbstractJobFactory.encode() does NOT serialize the taskId field,
            // so without this the deserialized factory would get a new random ID.
            set("factoryTaskId", super.getTaskId());
        }

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
        public Factory(String... prompts) {
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
        public Factory(List<String> prompts) {
            this();
            setPrompts(prompts.toArray(new String[0]));
        }

        public void setPrompts(String... prompts) {
            String code = String.join(PROMPT_SEPARATOR, prompts);
            set("prompts", base64Encode(code));
        }

        public List<String> getPrompts() {
            if (prompts == null) {
                String code = base64Decode(get("prompts"));
                prompts = code == null ? new ArrayList<>() : new ArrayList<>(List.of(code.split(PROMPT_SEPARATOR)));
            }
            return prompts;
        }

        public String getAllowedTools() {
            return allowedTools;
        }

        public void setAllowedTools(String allowedTools) {
            this.allowedTools = allowedTools;
            set("tools", allowedTools);
        }

        public String getWorkingDirectory() {
            return workingDirectory;
        }

        public void setWorkingDirectory(String workingDirectory) {
            this.workingDirectory = workingDirectory;
            set("workDir", base64Encode(workingDirectory));
        }

        /**
         * Returns the git repository URL for automatic checkout.
         */
        public String getRepoUrl() {
            return repoUrl;
        }

        /**
         * Sets the git repository URL. When set, the agent will clone
         * this repo if no working directory is specified.
         *
         * @param repoUrl the git clone URL
         */
        public void setRepoUrl(String repoUrl) {
            this.repoUrl = repoUrl;
            set("repoUrl", base64Encode(repoUrl));
        }

        /**
         * Returns the default workspace path for repo checkouts.
         */
        public String getDefaultWorkspacePath() {
            return defaultWorkspacePath;
        }

        /**
         * Sets the default workspace path for repo checkouts.
         *
         * @param defaultWorkspacePath the absolute path for repo checkouts
         */
        public void setDefaultWorkspacePath(String defaultWorkspacePath) {
            this.defaultWorkspacePath = defaultWorkspacePath;
            set("defaultWsPath", base64Encode(defaultWorkspacePath));
        }

        public int getMaxTurns() {
            return maxTurns;
        }

        public void setMaxTurns(int maxTurns) {
            this.maxTurns = maxTurns;
            set("maxTurns", String.valueOf(maxTurns));
        }

        public double getMaxBudgetUsd() {
            return maxBudgetUsd;
        }

        public void setMaxBudgetUsd(double maxBudgetUsd) {
            this.maxBudgetUsd = maxBudgetUsd;
            set("maxBudget", String.valueOf(maxBudgetUsd));
        }

        public String getTargetBranch() {
            return targetBranch;
        }

        /**
         * Sets the target branch for git operations.
         * When set, each job will commit and push its changes to this branch.
         *
         * @param targetBranch the branch name (e.g., "feature/my-work")
         */
        public void setTargetBranch(String targetBranch) {
            this.targetBranch = targetBranch;
            set("branch", base64Encode(targetBranch));
        }

        /**
         * Returns the base branch for new branch creation.
         */
        public String getBaseBranch() {
            return baseBranch;
        }

        /**
         * Sets the base branch used as the starting point when the target
         * branch does not yet exist. Defaults to {@code "master"}.
         *
         * @param baseBranch the branch name to base new branches on
         */
        public void setBaseBranch(String baseBranch) {
            this.baseBranch = baseBranch;
            set("baseBranch", base64Encode(baseBranch));
        }

        public boolean isPushToOrigin() {
            return pushToOrigin;
        }

        public void setPushToOrigin(boolean pushToOrigin) {
            this.pushToOrigin = pushToOrigin;
            set("push", String.valueOf(pushToOrigin));
        }

        /**
         * Returns the git user name for commits.
         */
        public String getGitUserName() {
            return gitUserName;
        }

        /**
         * Sets the git user name for commits made by jobs from this factory.
         *
         * @param gitUserName the name to use in git commits
         */
        public void setGitUserName(String gitUserName) {
            this.gitUserName = gitUserName;
            set("gitUserName", base64Encode(gitUserName));
        }

        /**
         * Returns the git user email for commits.
         */
        public String getGitUserEmail() {
            return gitUserEmail;
        }

        /**
         * Sets the git user email for commits made by jobs from this factory.
         *
         * @param gitUserEmail the email to use in git commits
         */
        public void setGitUserEmail(String gitUserEmail) {
            this.gitUserEmail = gitUserEmail;
            set("gitUserEmail", base64Encode(gitUserEmail));
        }

        /**
         * Returns the workstream URL for jobs created by this factory.
         */
        public String getWorkstreamUrl() {
            return workstreamUrl;
        }

        /**
         * Sets the workstream URL for jobs created by this factory.
         * This single URL is used for both status reporting and Slack
         * messaging (by appending {@code /messages}).
         *
         * @param workstreamUrl the controller URL for the workstream
         */
        public void setWorkstreamUrl(String workstreamUrl) {
            this.workstreamUrl = workstreamUrl;
            set("workstreamUrl", base64Encode(workstreamUrl));
        }

        /**
         * Returns the centralized MCP configuration JSON for jobs.
         */
        public String getCentralizedMcpConfig() {
            return centralizedMcpConfig;
        }

        /**
         * Sets the centralized MCP configuration JSON for jobs created by this factory.
         *
         * @param centralizedMcpConfig JSON mapping server names to HTTP URLs and tool names
         */
        public void setCentralizedMcpConfig(String centralizedMcpConfig) {
            this.centralizedMcpConfig = centralizedMcpConfig;
            set("centralMcp", base64Encode(centralizedMcpConfig));
        }

        /**
         * Returns the pushed MCP tools configuration JSON for jobs.
         */
        public String getPushedToolsConfig() {
            return pushedToolsConfig;
        }

        /**
         * Sets the pushed MCP tools configuration JSON for jobs created by this factory.
         *
         * @param pushedToolsConfig JSON mapping server names to download URLs and tool names
         */
        public void setPushedToolsConfig(String pushedToolsConfig) {
            this.pushedToolsConfig = pushedToolsConfig;
            set("pushedTools", base64Encode(pushedToolsConfig));
        }

        /**
         * Returns per-workstream environment variables for pushed tools.
         */
        public Map<String, String> getWorkstreamEnv() {
            return workstreamEnv;
        }

        /**
         * Sets per-workstream environment variables for pushed tools.
         * These override global env vars defined on the pushed tool entry.
         *
         * @param workstreamEnv map of environment variable names to values
         */
        public void setWorkstreamEnv(Map<String, String> workstreamEnv) {
            this.workstreamEnv = workstreamEnv;
            set("wsEnv", base64Encode(mapToJsonObject(workstreamEnv)));
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
            set("planDoc", base64Encode(planningDocument));
        }

        /**
         * Returns whether test file protection is enabled for jobs.
         */
        public boolean isProtectTestFiles() {
            return protectTestFiles;
        }

        /**
         * Sets whether to protect test files that exist on the base branch.
         *
         * @param protectTestFiles true to block staging of existing test/CI files
         */
        public void setProtectTestFiles(boolean protectTestFiles) {
            this.protectTestFiles = protectTestFiles;
            set("protectTests", String.valueOf(protectTestFiles));
        }

        @Override
        public Job nextJob() {
            List<String> p = getPrompts();
            if (index >= p.size()) return null;

            ClaudeCodeJob job = new ClaudeCodeJob(getTaskId(), p.get(index++));
            job.setAllowedTools(allowedTools);
            job.setWorkingDirectory(workingDirectory);
            job.setMaxTurns(maxTurns);
            job.setMaxBudgetUsd(maxBudgetUsd);

            // Repository URL for automatic checkout
            if (repoUrl != null) {
                job.setRepoUrl(repoUrl);
            }
            if (defaultWorkspacePath != null) {
                job.setDefaultWorkspacePath(defaultWorkspacePath);
            }

            // Git management settings
            if (targetBranch != null) {
                job.setTargetBranch(targetBranch);
                job.setPushToOrigin(pushToOrigin);
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

            // Workstream URL (status reporting + Slack messaging)
            if (workstreamUrl != null) {
                job.setWorkstreamUrl(workstreamUrl);
            }

            // Centralized MCP server config
            if (centralizedMcpConfig != null) {
                job.setCentralizedMcpConfig(centralizedMcpConfig);
            }

            // Pushed MCP tools config
            if (pushedToolsConfig != null) {
                job.setPushedToolsConfig(pushedToolsConfig);
            }

            // Per-workstream env vars for pushed tools
            if (workstreamEnv != null && !workstreamEnv.isEmpty()) {
                job.setWorkstreamEnv(workstreamEnv);
            }

            // Planning document
            if (planningDocument != null) {
                job.setPlanningDocument(planningDocument);
            }

            // Test file protection
            job.setProtectTestFiles(protectTestFiles);

            return job;
        }

        @Override
        public Job createJob(String data) {
            return null; // Not used - jobs created via nextJob()
        }

        @Override
        public double getCompleteness() {
            List<String> p = getPrompts();
            return p.isEmpty() ? 1.0 : index / (double) p.size();
        }

        @Override
        public void set(String key, String value) {
            super.set(key, value);

            switch (key) {
                // Git properties (shared key names with GitManagedJob)
                case "workDir":
                    this.workingDirectory = base64Decode(value);
                    break;
                case "repoUrl":
                    this.repoUrl = base64Decode(value);
                    break;
                case "defaultWsPath":
                    this.defaultWorkspacePath = base64Decode(value);
                    break;
                case "branch":
                    this.targetBranch = base64Decode(value);
                    break;
                case "baseBranch":
                    this.baseBranch = base64Decode(value);
                    break;
                case "push":
                    this.pushToOrigin = Boolean.parseBoolean(value);
                    break;
                case "workstreamUrl":
                    this.workstreamUrl = base64Decode(value);
                    break;
                case "gitUserName":
                    this.gitUserName = base64Decode(value);
                    break;
                case "gitUserEmail":
                    this.gitUserEmail = base64Decode(value);
                    break;
                case "protectTests":
                    this.protectTestFiles = Boolean.parseBoolean(value);
                    break;

                // Factory-specific properties
                case "tools":
                    this.allowedTools = value;
                    break;
                case "maxTurns":
                    this.maxTurns = Integer.parseInt(value);
                    break;
                case "maxBudget":
                    this.maxBudgetUsd = Double.parseDouble(value);
                    break;
                case "centralMcp":
                    this.centralizedMcpConfig = base64Decode(value);
                    break;
                case "pushedTools":
                    this.pushedToolsConfig = base64Decode(value);
                    break;
                case "wsEnv":
                    this.workstreamEnv = parseJsonObjectToMap(base64Decode(value));
                    break;
                case "planDoc":
                    this.planningDocument = base64Decode(value);
                    break;
            }
        }

        @Override
        public String toString() {
            return "ClaudeCodeJob.Factory[prompts=" + getPrompts().size() +
                   ", tools=" + allowedTools +
                   ", branch=" + targetBranch +
                   ", completeness=" + getCompleteness() + "]";
        }
    }
}
