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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 */
public class ClaudeCodeJob extends GitManagedJob {
    public static final String PROMPT_SEPARATOR = ";;PROMPT;;";
    public static final String DEFAULT_TOOLS = "Read,Edit,Write,Bash,Glob,Grep";

    /** GitHub MCP tools, always included when ar-github is enabled. */
    private static final String GITHUB_MCP_TOOLS =
        "mcp__ar-github__github_pr_find," +
        "mcp__ar-github__github_pr_review_comments," +
        "mcp__ar-github__github_pr_conversation," +
        "mcp__ar-github__github_pr_reply";

    /** Slack MCP tool, included only when a workstream URL is configured. */
    private static final String SLACK_MCP_TOOL = "mcp__ar-slack__slack_send_message";

    private String prompt;
    private String allowedTools;
    private int maxTurns;
    private double maxBudgetUsd;
    private String centralizedMcpConfig;
    private String pushedToolsConfig;
    private Map<String, String> workstreamEnv;

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

        // GitHub instructions -only when ar-github is in the MCP config
        if (isGitHubMcpEnabled()) {
            sb.append("You can read and respond to GitHub PR review comments using the GitHub MCP tools ");
            sb.append("(github_pr_find, github_pr_review_comments, github_pr_conversation, github_pr_reply). ");
            sb.append("Use these to check for code review feedback and address it.\n\n");
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

        sb.append("--- BEGIN USER REQUEST ---\n");
        sb.append(prompt);
        sb.append("\n--- END USER REQUEST ---");

        return sb.toString();
    }

    /**
     * Returns whether the GitHub MCP server is enabled in the MCP configuration.
     * This is true when ar-github is either centralized or included locally.
     */
    private boolean isGitHubMcpEnabled() {
        return true;
    }

    /**
     * Downloads pushed tool source files from the controller to
     * {@code ~/.flowtree/tools/mcp/{name}/server.py} if not already present.
     *
     * <p>This method is called at the start of {@link #doWork()} before
     * building the Claude command. Each tool's download URL is resolved
     * from the {@link #pushedToolsConfig} JSON, with the {@code 0.0.0.0}
     * placeholder replaced by {@code FLOWTREE_ROOT_HOST}.</p>
     */
    private void ensurePushedTools() {
        Map<String, List<String>> pushedTools = parsePushedConfig();
        if (pushedTools.isEmpty()) return;

        String rootHost = System.getenv("FLOWTREE_ROOT_HOST");
        String home = System.getProperty("user.home");

        for (String serverName : pushedTools.keySet()) {
            Path targetDir = Path.of(home, ".flowtree", "tools", "mcp", serverName);
            Path targetFile = targetDir.resolve("server.py");

            if (Files.exists(targetFile)) {
                log("Pushed tool already present: " + serverName);
                continue;
            }

            // Extract download URL from config
            String url = extractJsonStringField(pushedToolsConfig, serverName, "url");
            if (url == null) continue;
            if (rootHost != null && !rootHost.isEmpty()) {
                url = url.replace("0.0.0.0", rootHost);
            }

            try {
                Files.createDirectories(targetDir);
                String content = httpGet(url);
                Files.writeString(targetFile, content, StandardCharsets.UTF_8);
                log("Downloaded pushed tool: " + serverName + " -> " + targetFile);
            } catch (IOException e) {
                warn("Failed to download pushed tool " + serverName + ": " + e.getMessage());
            }
        }
    }

    /**
     * Performs an HTTP GET request and returns the response body as a string.
     *
     * @param url the URL to fetch
     * @return the response body
     * @throws IOException if the request fails or returns a non-2xx status
     */
    private String httpGet(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);

        int responseCode = conn.getResponseCode();
        if (responseCode < 200 || responseCode >= 300) {
            throw new IOException("HTTP " + responseCode + " from " + url);
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        }
    }

    /**
     * Parses the {@link #pushedToolsConfig} JSON to extract server names
     * and their tool lists. Uses the same format as
     * {@link #parseCentralizedConfig()}.
     *
     * @return map of server name to list of tool names, empty if no config
     */
    private Map<String, List<String>> parsePushedConfig() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (pushedToolsConfig == null || pushedToolsConfig.isEmpty()) return result;

        int pos = 0;
        while (pos < pushedToolsConfig.length()) {
            int keyStart = pushedToolsConfig.indexOf("\"", pos);
            if (keyStart < 0) break;
            int keyEnd = pushedToolsConfig.indexOf("\"", keyStart + 1);
            if (keyEnd < 0) break;

            String key = pushedToolsConfig.substring(keyStart + 1, keyEnd);

            int objStart = pushedToolsConfig.indexOf("{", keyEnd);
            if (objStart < 0) break;

            int depth = 1;
            int objEnd = objStart + 1;
            while (objEnd < pushedToolsConfig.length() && depth > 0) {
                char c = pushedToolsConfig.charAt(objEnd);
                if (c == '{') depth++;
                else if (c == '}') depth--;
                objEnd++;
            }

            String objBody = pushedToolsConfig.substring(objStart, objEnd);

            List<String> tools = new ArrayList<>();
            int toolsIdx = objBody.indexOf("\"tools\"");
            if (toolsIdx >= 0) {
                int arrStart = objBody.indexOf("[", toolsIdx);
                int arrEnd = objBody.indexOf("]", arrStart);
                if (arrStart >= 0 && arrEnd >= 0) {
                    String arr = objBody.substring(arrStart + 1, arrEnd);
                    Pattern namePattern = Pattern.compile("\"([^\"]+)\"");
                    Matcher m = namePattern.matcher(arr);
                    while (m.find()) {
                        tools.add(m.group(1));
                    }
                }
            }

            if (!tools.isEmpty()) {
                result.put(key, tools);
            }

            pos = objEnd;
        }

        return result;
    }

    @Override
    protected void doWork() {
        // Download pushed tools from the controller if needed
        ensurePushedTools();

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
        command.add(buildAllowedTools());
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
        verifyMcpToolFiles();

        // MCP config (ar-github always; ar-slack when workstream URL is set)
        command.add("--mcp-config");
        command.add(buildMcpConfig());

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
    protected void populateEventDetails(JobCompletionEvent event) {
        event.withClaudeCodeInfo(prompt, sessionId, exitCode);
        event.withTimingInfo(durationMs, durationApiMs, costUsd, numTurns);
        event.withSessionDetails(subtype, isError, permissionDenials, deniedToolNames);
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
     * Builds the complete allowed-tools string by appending MCP tool
     * names to the base {@link #allowedTools} list. Includes tools from
     * centralized servers (if configured), project MCP servers (discovered
     * via {@code .mcp.json}), GitHub tools, and Slack tools.
     */
    private String buildAllowedTools() {
        // Parse centralized and pushed server names for skip logic
        Map<String, List<String>> centralizedServers = parseCentralizedConfig();
        Map<String, List<String>> pushedTools = parsePushedConfig();

        StringBuilder sb = new StringBuilder(allowedTools);

        // Add tools from centralized servers
        for (Map.Entry<String, List<String>> entry : centralizedServers.entrySet()) {
            String serverName = entry.getKey();
            for (String tool : entry.getValue()) {
                sb.append(",mcp__").append(serverName).append("__").append(tool);
            }
            log("Centralized " + entry.getValue().size() + " tools from " + serverName);
        }

        // Add tools from pushed tools
        for (Map.Entry<String, List<String>> entry : pushedTools.entrySet()) {
            String serverName = entry.getKey();
            for (String tool : entry.getValue()) {
                sb.append(",mcp__").append(serverName).append("__").append(tool);
            }
            log("Pushed " + entry.getValue().size() + " tools from " + serverName);
        }

        // Add GitHub tools unless centralized or pushed
        if (!centralizedServers.containsKey("ar-github") && !pushedTools.containsKey("ar-github")) {
            sb.append(",").append(GITHUB_MCP_TOOLS);
        }

        // Add Slack tool unless centralized or pushed
        if (!centralizedServers.containsKey("ar-slack") && !pushedTools.containsKey("ar-slack")) {
            if (getWorkstreamUrl() != null && !getWorkstreamUrl().isEmpty()) {
                sb.append(",").append(SLACK_MCP_TOOL);
            }
        }

        // Discover and include tools from project MCP servers
        Path workDir = getWorkingDirectory() != null
            ? Path.of(getWorkingDirectory()) : Path.of(System.getProperty("user.dir"));
        Map<String, String> projectServers = discoverProjectMcpServers();
        for (Map.Entry<String, String> entry : projectServers.entrySet()) {
            String serverName = entry.getKey();
            // Skip if this server is centralized or pushed
            if (centralizedServers.containsKey(serverName)) continue;
            if (pushedTools.containsKey(serverName)) continue;

            Path serverFile = workDir.resolve(entry.getValue());
            List<String> tools = discoverToolNames(serverFile);
            for (String tool : tools) {
                sb.append(",mcp__").append(serverName).append("__").append(tool);
            }
            if (!tools.isEmpty()) {
                log("Discovered " + tools.size() + " tools from " + serverName);
            }
        }

        return sb.toString();
    }

    /**
     * Builds a JSON MCP configuration string for agent MCP servers.
     *
     * <p>Centralized servers (from {@link #centralizedMcpConfig}) are emitted
     * as HTTP entries with resolved URLs. Local servers from {@code .mcp.json}
     * are emitted as stdio entries. ar-github and ar-slack fall back to stdio
     * only when they are not in the centralized config.</p>
     */
    private String buildMcpConfig() {
        Map<String, List<String>> centralizedServers = parseCentralizedConfig();
        Map<String, List<String>> pushedTools = parsePushedConfig();
        String rootHost = System.getenv("FLOWTREE_ROOT_HOST");

        StringBuilder sb = new StringBuilder();
        sb.append("{\"mcpServers\":{");

        boolean first = true;

        // Emit centralized servers as HTTP entries
        if (centralizedMcpConfig != null && !centralizedMcpConfig.isEmpty()) {
            // Parse each server's URL from the config JSON
            for (String serverName : centralizedServers.keySet()) {
                String url = extractJsonStringField(centralizedMcpConfig, serverName, "url");
                if (url == null) continue;

                // Resolve 0.0.0.0 placeholder with actual controller host
                if (rootHost != null && !rootHost.isEmpty()) {
                    url = url.replace("0.0.0.0", rootHost);
                }

                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(serverName).append("\":{");
                sb.append("\"type\":\"http\",");
                sb.append("\"url\":\"").append(url).append("\"");
                sb.append("}");
            }
        }

        // Emit pushed tools as stdio entries pointing to ~/.flowtree/tools/mcp/{name}/server.py
        String home = System.getProperty("user.home");
        for (String serverName : pushedTools.keySet()) {
            if (!first) sb.append(",");
            first = false;
            String path = home + "/.flowtree/tools/mcp/" + serverName + "/server.py";
            sb.append("\"").append(serverName).append("\":{");
            sb.append("\"command\":\"python3\",");
            sb.append("\"args\":[\"").append(path).append("\"]");

            // Merge global pushed-tool env with per-workstream env (workstream wins)
            Map<String, String> mergedEnv = new LinkedHashMap<>();
            String globalEnvJson = extractJsonObjectField(pushedToolsConfig, serverName, "env");
            if (globalEnvJson != null) {
                mergedEnv.putAll(parseJsonObjectToMap(globalEnvJson));
            }
            if (workstreamEnv != null) {
                mergedEnv.putAll(workstreamEnv);
            }
            if (!mergedEnv.isEmpty()) {
                sb.append(",\"env\":").append(mapToJsonObject(mergedEnv));
            }

            sb.append("}");
        }

        // Include project MCP servers discovered from .mcp.json (skip centralized and pushed)
        Map<String, String> projectServers = discoverProjectMcpServers();
        for (Map.Entry<String, String> entry : projectServers.entrySet()) {
            if (centralizedServers.containsKey(entry.getKey())) continue;
            if (pushedTools.containsKey(entry.getKey())) continue;

            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(entry.getKey()).append("\":{");
            sb.append("\"command\":\"python3\",");
            sb.append("\"args\":[\"").append(entry.getValue()).append("\"]");
            sb.append("}");
        }

        // ar-github: stdio fallback only when not centralized and not pushed
        if (!centralizedServers.containsKey("ar-github") && !pushedTools.containsKey("ar-github")) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"ar-github\":{");
            sb.append("\"command\":\"python3\",");
            sb.append("\"args\":[\"tools/mcp/github/server.py\"]");
            sb.append("}");
        }

        // ar-slack: stdio fallback only when not centralized, not pushed, and workstream URL is set
        if (!centralizedServers.containsKey("ar-slack") && !pushedTools.containsKey("ar-slack")) {
            if (getWorkstreamUrl() != null && !getWorkstreamUrl().isEmpty()) {
                if (!first) sb.append(",");
                sb.append("\"ar-slack\":{");
                sb.append("\"command\":\"python3\",");
                sb.append("\"args\":[\"tools/mcp/slack/server.py\"]");
                sb.append("}");
            }
        }

        sb.append("}}");
        return sb.toString();
    }

    /**
     * Parses the {@link #centralizedMcpConfig} JSON to extract server names
     * and their tool lists.
     *
     * @return map of server name to list of tool names, empty if no config
     */
    private Map<String, List<String>> parseCentralizedConfig() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (centralizedMcpConfig == null || centralizedMcpConfig.isEmpty()) return result;

        // Simple JSON parsing: find top-level keys and their "tools" arrays
        // Format: {"ar-slack":{"url":"...","tools":["tool1","tool2"]}, ...}
        int pos = 0;
        while (pos < centralizedMcpConfig.length()) {
            // Find next key at the top level (skip nested braces)
            int keyStart = centralizedMcpConfig.indexOf("\"", pos);
            if (keyStart < 0) break;
            int keyEnd = centralizedMcpConfig.indexOf("\"", keyStart + 1);
            if (keyEnd < 0) break;

            String key = centralizedMcpConfig.substring(keyStart + 1, keyEnd);

            // Find the opening brace for this server's object
            int objStart = centralizedMcpConfig.indexOf("{", keyEnd);
            if (objStart < 0) break;

            // Find the matching closing brace
            int depth = 1;
            int objEnd = objStart + 1;
            while (objEnd < centralizedMcpConfig.length() && depth > 0) {
                char c = centralizedMcpConfig.charAt(objEnd);
                if (c == '{') depth++;
                else if (c == '}') depth--;
                objEnd++;
            }

            String objBody = centralizedMcpConfig.substring(objStart, objEnd);

            // Extract tools array from the object
            List<String> tools = new ArrayList<>();
            int toolsIdx = objBody.indexOf("\"tools\"");
            if (toolsIdx >= 0) {
                int arrStart = objBody.indexOf("[", toolsIdx);
                int arrEnd = objBody.indexOf("]", arrStart);
                if (arrStart >= 0 && arrEnd >= 0) {
                    String arr = objBody.substring(arrStart + 1, arrEnd);
                    Pattern namePattern = Pattern.compile("\"([^\"]+)\"");
                    Matcher m = namePattern.matcher(arr);
                    while (m.find()) {
                        tools.add(m.group(1));
                    }
                }
            }

            if (!tools.isEmpty()) {
                result.put(key, tools);
            }

            pos = objEnd;
        }

        return result;
    }

    /**
     * Extracts a nested string field from a JSON config.
     * Looks for {@code "parentKey": { ... "fieldName": "value" ... }}.
     *
     * @param json      the JSON string
     * @param parentKey the parent object key
     * @param fieldName the field to extract
     * @return the field value, or null if not found
     */
    private String extractJsonStringField(String json, String parentKey, String fieldName) {
        // Find the parent key
        int parentIdx = json.indexOf("\"" + parentKey + "\"");
        if (parentIdx < 0) return null;

        // Find the opening brace of the parent object
        int objStart = json.indexOf("{", parentIdx);
        if (objStart < 0) return null;

        // Find the matching closing brace
        int depth = 1;
        int objEnd = objStart + 1;
        while (objEnd < json.length() && depth > 0) {
            char c = json.charAt(objEnd);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            objEnd++;
        }

        String obj = json.substring(objStart, objEnd);

        // Find the field within the object
        int fieldIdx = obj.indexOf("\"" + fieldName + "\"");
        if (fieldIdx < 0) return null;

        int colonIdx = obj.indexOf(":", fieldIdx);
        if (colonIdx < 0) return null;

        int valueStart = obj.indexOf("\"", colonIdx) + 1;
        if (valueStart <= 0) return null;

        int valueEnd = obj.indexOf("\"", valueStart);
        if (valueEnd < 0) return null;

        return obj.substring(valueStart, valueEnd);
    }

    /**
     * Extracts a nested JSON object field from a JSON config.
     * Looks for {@code "parentKey": { ... "fieldName": {...} ... }} and
     * returns the raw JSON object string (including braces).
     *
     * @param json      the JSON string
     * @param parentKey the parent object key
     * @param fieldName the field to extract (must be an object value)
     * @return the raw JSON object string, or null if not found
     */
    private String extractJsonObjectField(String json, String parentKey, String fieldName) {
        int parentIdx = json.indexOf("\"" + parentKey + "\"");
        if (parentIdx < 0) return null;

        int objStart = json.indexOf("{", parentIdx);
        if (objStart < 0) return null;

        int depth = 1;
        int objEnd = objStart + 1;
        while (objEnd < json.length() && depth > 0) {
            char c = json.charAt(objEnd);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            objEnd++;
        }

        String obj = json.substring(objStart, objEnd);

        int fieldIdx = obj.indexOf("\"" + fieldName + "\"");
        if (fieldIdx < 0) return null;

        int colonIdx = obj.indexOf(":", fieldIdx);
        if (colonIdx < 0) return null;

        // Skip whitespace after the colon to find the opening brace
        int braceStart = -1;
        for (int i = colonIdx + 1; i < obj.length(); i++) {
            char c = obj.charAt(i);
            if (c == '{') {
                braceStart = i;
                break;
            }
            if (!Character.isWhitespace(c)) return null;
        }
        if (braceStart < 0) return null;

        int innerDepth = 1;
        int braceEnd = braceStart + 1;
        while (braceEnd < obj.length() && innerDepth > 0) {
            char c = obj.charAt(braceEnd);
            if (c == '{') innerDepth++;
            else if (c == '}') innerDepth--;
            braceEnd++;
        }

        return obj.substring(braceStart, braceEnd);
    }

    /**
     * Parses a simple JSON object string like {@code {"key":"value","k2":"v2"}}
     * into a {@link Map}. Only handles flat string-valued objects.
     *
     * @param json the JSON object string (including braces)
     * @return parsed map, empty if input is null or unparseable
     */
    private static Map<String, String> parseJsonObjectToMap(String json) {
        Map<String, String> result = new LinkedHashMap<>();
        if (json == null) return result;
        Pattern p = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]+)\"");
        Matcher m = p.matcher(json);
        while (m.find()) {
            result.put(m.group(1), m.group(2));
        }
        return result;
    }

    /**
     * Serializes a {@link Map} of string entries to a JSON object string
     * like {@code {"key":"value","k2":"v2"}}.
     *
     * @param map the map to serialize
     * @return JSON object string
     */
    private static String mapToJsonObject(Map<String, String> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(e.getKey()).append("\":\"").append(e.getValue()).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * Verifies that MCP tool server files exist in the working directory
     * and logs their modification times for deployment diagnostics.
     *
     * <p>This helps diagnose cases where tool server updates fail to reach
     * workers (e.g., git pull failures, stale Docker volumes).</p>
     */
    private void verifyMcpToolFiles() {
        Path workDir = getWorkingDirectory() != null
            ? Path.of(getWorkingDirectory()) : Path.of(System.getProperty("user.dir"));

        String[] toolFiles = {
            "tools/mcp/slack/server.py",
            "tools/mcp/github/server.py"
        };

        for (String toolFile : toolFiles) {
            Path resolved = workDir.resolve(toolFile);
            if (Files.exists(resolved)) {
                try {
                    long lastModified = Files.getLastModifiedTime(resolved).toMillis();
                    long ageSeconds = (System.currentTimeMillis() - lastModified) / 1000;
                    log("MCP tool: " + toolFile + " (modified " + ageSeconds + "s ago)");
                } catch (IOException e) {
                    log("MCP tool: " + toolFile + " (exists, could not read mtime)");
                }
            } else {
                warn("MCP tool missing: " + resolved.toAbsolutePath());
            }
        }
    }

    /**
     * Discovers MCP servers defined in the project's {@code .mcp.json} file
     * and cross-references with {@code .claude/settings.json} to determine
     * which servers are enabled.
     *
     * @return map of server name to Python source file path (relative to working dir)
     */
    private Map<String, String> discoverProjectMcpServers() {
        Map<String, String> servers = new LinkedHashMap<>();
        Path workDir = getWorkingDirectory() != null
            ? Path.of(getWorkingDirectory()) : Path.of(System.getProperty("user.dir"));

        // Read .mcp.json
        Path mcpJson = workDir.resolve(".mcp.json");
        if (!Files.exists(mcpJson)) return servers;

        Map<String, String> allServers = parseMcpJson(mcpJson);
        if (allServers.isEmpty()) return servers;

        // Read enabled servers from .claude/settings.json
        List<String> enabled = parseEnabledServers(workDir.resolve(".claude/settings.json"));

        // Determine centralized server names for skip logic
        Map<String, List<String>> centralized = parseCentralizedConfig();

        // Include enabled servers (or all if no settings file)
        for (Map.Entry<String, String> entry : allServers.entrySet()) {
            String name = entry.getKey();
            // Skip ar-github and ar-slack -- they are handled separately
            // because they need special environment or conditional inclusion
            if ("ar-github".equals(name) || "ar-slack".equals(name)) continue;
            // Skip servers that are centralized
            if (centralized.containsKey(name)) continue;
            if (enabled.isEmpty() || enabled.contains(name)) {
                servers.put(name, entry.getValue());
            }
        }

        return servers;
    }

    /**
     * Parses {@code .mcp.json} to extract server names and their Python
     * source file paths.
     *
     * @return map of server name to source file path (first arg)
     */
    private Map<String, String> parseMcpJson(Path mcpJsonPath) {
        Map<String, String> result = new LinkedHashMap<>();
        try {
            String content = Files.readString(mcpJsonPath, StandardCharsets.UTF_8);

            // Simple parser: find "serverName": { ... "args": ["path"] ... }
            // within "mcpServers": { ... }
            int serversStart = content.indexOf("\"mcpServers\"");
            if (serversStart < 0) return result;

            int braceStart = content.indexOf("{", serversStart + 12);
            if (braceStart < 0) return result;

            // Walk through the mcpServers block finding server entries
            Pattern serverPattern = Pattern.compile(
                "\"([^\"]+)\"\\s*:\\s*\\{[^}]*\"args\"\\s*:\\s*\\[\\s*\"([^\"]+)\"",
                Pattern.DOTALL
            );
            Matcher matcher = serverPattern.matcher(content.substring(braceStart));
            while (matcher.find()) {
                result.put(matcher.group(1), matcher.group(2));
            }
        } catch (IOException e) {
            warn("Failed to read .mcp.json: " + e.getMessage());
        }
        return result;
    }

    /**
     * Parses {@code .claude/settings.json} to extract the list of
     * enabled MCP servers from the {@code enabledMcpjsonServers} field.
     *
     * @return list of enabled server names, or empty list if file doesn't exist
     */
    private List<String> parseEnabledServers(Path settingsPath) {
        List<String> enabled = new ArrayList<>();
        if (!Files.exists(settingsPath)) return enabled;

        try {
            String content = Files.readString(settingsPath, StandardCharsets.UTF_8);
            int idx = content.indexOf("\"enabledMcpjsonServers\"");
            if (idx < 0) return enabled;

            int arrStart = content.indexOf("[", idx);
            int arrEnd = content.indexOf("]", arrStart);
            if (arrStart < 0 || arrEnd < 0) return enabled;

            String arr = content.substring(arrStart + 1, arrEnd);
            Pattern namePattern = Pattern.compile("\"([^\"]+)\"");
            Matcher m = namePattern.matcher(arr);
            while (m.find()) {
                enabled.add(m.group(1));
            }
        } catch (IOException e) {
            warn("Failed to read settings.json: " + e.getMessage());
        }
        return enabled;
    }

    /**
     * Scans a Python MCP server source file for {@code @mcp.tool()}
     * decorated functions and returns their names.
     *
     * @param serverFile path to the Python server source file
     * @return list of tool function names
     */
    private List<String> discoverToolNames(Path serverFile) {
        return McpToolDiscovery.discoverToolNames(serverFile);
    }

    /**
     * Extracts session ID, timing metrics, stop reason, and permission denials
     * from the Claude Code JSON output.
     *
     * @param jsonOutput the raw JSON output from Claude Code
     */
    private void extractOutputMetrics(String jsonOutput) {
        if (jsonOutput == null || jsonOutput.isEmpty()) return;

        // Claude Code with --output-format json emits NDJSON: one JSON object
        // per line, with per-turn objects appearing first and the session-level
        // "type":"result" object last.  Extracting from the full output picks up
        // the FIRST occurrence of each field (a per-turn value), not the session
        // total.  Instead, locate the result object and extract from that.
        String resultJson = io.flowtree.JsonFieldExtractor.extractLastJsonObject(jsonOutput, "result");
        if (resultJson == null) {
            resultJson = jsonOutput;
        }

        // Extract session_id
        sessionId = extractJsonStringValue(resultJson, "session_id");

        // Extract timing metrics from the result object
        durationMs = extractJsonLongValue(resultJson, "duration_ms");
        durationApiMs = extractJsonLongValue(resultJson, "duration_api_ms");
        numTurns = (int) extractJsonLongValue(resultJson, "num_turns");

        // Cost field may be "total_cost_usd" or "cost_usd"
        costUsd = extractJsonDoubleValue(resultJson, "total_cost_usd");
        if (costUsd == 0.0) {
            costUsd = extractJsonDoubleValue(resultJson, "cost_usd");
        }

        // Extract subtype (stop reason: "success", "error_max_turns", etc.)
        subtype = extractJsonStringValue(resultJson, "subtype");

        // Extract is_error boolean
        isError = extractJsonBooleanValue(resultJson, "is_error");

        // Count permission_denials array entries and extract denied tool names
        permissionDenials = countJsonArrayEntries(resultJson, "permission_denials");
        deniedToolNames = io.flowtree.JsonFieldExtractor.extractFieldFromArrayObjects(
            resultJson, "permission_denials", "tool");
    }

    /**
     * Delegates to {@link io.flowtree.JsonFieldExtractor#extractLong(String, String)}.
     */
    private static long extractJsonLongValue(String json, String field) {
        return io.flowtree.JsonFieldExtractor.extractLong(json, field);
    }

    /**
     * Delegates to {@link io.flowtree.JsonFieldExtractor#extractDouble(String, String)}.
     */
    private static double extractJsonDoubleValue(String json, String field) {
        return io.flowtree.JsonFieldExtractor.extractDouble(json, field);
    }

    /**
     * Delegates to {@link io.flowtree.JsonFieldExtractor#extractString(String, String)}.
     */
    private static String extractJsonStringValue(String json, String field) {
        return io.flowtree.JsonFieldExtractor.extractString(json, field);
    }

    /**
     * Delegates to {@link io.flowtree.JsonFieldExtractor#extractBoolean(String, String)}.
     */
    private static boolean extractJsonBooleanValue(String json, String field) {
        return io.flowtree.JsonFieldExtractor.extractBoolean(json, field);
    }

    /**
     * Delegates to {@link io.flowtree.JsonFieldExtractor#countArrayEntries(String, String)}.
     */
    private static int countJsonArrayEntries(String json, String field) {
        return io.flowtree.JsonFieldExtractor.countArrayEntries(json, field);
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

        /** Performs the setPrompts operation. */
        public void setPrompts(String... prompts) {
            String code = String.join(PROMPT_SEPARATOR, prompts);
            set("prompts", base64Encode(code));
        }

        /** Performs the getPrompts operation. */
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

        /** Performs the setAllowedTools operation. */
        public void setAllowedTools(String allowedTools) {
            this.allowedTools = allowedTools;
            set("tools", allowedTools);
        }

        public String getWorkingDirectory() {
            return workingDirectory;
        }

        /** Performs the setWorkingDirectory operation. */
        public void setWorkingDirectory(String workingDirectory) {
            this.workingDirectory = workingDirectory;
            set("workDir", base64Encode(workingDirectory));
        }

        public int getMaxTurns() {
            return maxTurns;
        }

        /** Performs the setMaxTurns operation. */
        public void setMaxTurns(int maxTurns) {
            this.maxTurns = maxTurns;
            set("maxTurns", String.valueOf(maxTurns));
        }

        public double getMaxBudgetUsd() {
            return maxBudgetUsd;
        }

        /** Performs the setMaxBudgetUsd operation. */
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

        /** Performs the setPushToOrigin operation. */
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

        @Override
        public Job nextJob() {
            List<String> p = getPrompts();
            if (index >= p.size()) return null;

            ClaudeCodeJob job = new ClaudeCodeJob(getTaskId(), p.get(index++));
            job.setAllowedTools(allowedTools);
            job.setWorkingDirectory(workingDirectory);
            job.setMaxTurns(maxTurns);
            job.setMaxBudgetUsd(maxBudgetUsd);

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

            // Also handle direct property setting
            switch (key) {
                case "tools":
                    this.allowedTools = value;
                    break;
                case "workDir":
                    this.workingDirectory = base64Decode(value);
                    break;
                case "maxTurns":
                    this.maxTurns = Integer.parseInt(value);
                    break;
                case "maxBudget":
                    this.maxBudgetUsd = Double.parseDouble(value);
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
                case "centralMcp":
                    this.centralizedMcpConfig = base64Decode(value);
                    break;
                case "pushedTools":
                    this.pushedToolsConfig = base64Decode(value);
                    break;
                case "wsEnv":
                    this.workstreamEnv = parseJsonObjectToMap(base64Decode(value));
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
