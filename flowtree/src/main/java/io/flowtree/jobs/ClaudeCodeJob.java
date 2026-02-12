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

    private String sessionId;
    private String output;
    private int exitCode;

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

        // Working directory and branch context
        String workDir = getWorkingDirectory();
        sb.append("Your working directory is: ");
        sb.append(workDir != null ? workDir : System.getProperty("user.dir"));
        sb.append("\n");
        if (getTargetBranch() != null && !getTargetBranch().isEmpty()) {
            sb.append("Target branch: ").append(getTargetBranch()).append("\n");
        }
        sb.append("\n");

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
     * Currently this is always true since ar-github is included in
     * {@link #buildMcpConfig()}, but this method exists as a guard so the
     * prompt can be made conditional if that changes.
     */
    private boolean isGitHubMcpEnabled() {
        return true;
    }

    @Override
    protected void doWork() {
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

            // Try to extract session ID from JSON output
            extractSessionId(output);

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
     * project MCP servers (discovered via {@code .mcp.json}), GitHub
     * tools, and Slack tools (when workstream URL is configured).
     */
    private String buildAllowedTools() {
        StringBuilder sb = new StringBuilder(allowedTools);
        sb.append(",").append(GITHUB_MCP_TOOLS);
        if (getWorkstreamUrl() != null && !getWorkstreamUrl().isEmpty()) {
            sb.append(",").append(SLACK_MCP_TOOL);
        }

        // Discover and include tools from project MCP servers
        Path workDir = getWorkingDirectory() != null
            ? Path.of(getWorkingDirectory()) : Path.of(System.getProperty("user.dir"));
        Map<String, String> projectServers = discoverProjectMcpServers();
        for (Map.Entry<String, String> entry : projectServers.entrySet()) {
            String serverName = entry.getKey();
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
     * Includes project servers from {@code .mcp.json} (filtered by
     * {@code .claude/settings.json}), ar-github (always), and ar-slack
     * (when a workstream URL is configured).
     * This is passed to Claude Code via the {@code --mcp-config} flag.
     */
    private String buildMcpConfig() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"mcpServers\":{");

        boolean first = true;

        // Include project MCP servers discovered from .mcp.json
        Map<String, String> projectServers = discoverProjectMcpServers();
        for (Map.Entry<String, String> entry : projectServers.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(entry.getKey()).append("\":{");
            sb.append("\"command\":\"python3\",");
            sb.append("\"args\":[\"").append(entry.getValue()).append("\"]");
            sb.append("}");
        }

        // ar-github is always included
        if (!first) sb.append(",");
        first = false;
        sb.append("\"ar-github\":{");
        sb.append("\"command\":\"python3\",");
        sb.append("\"args\":[\"tools/mcp/github/server.py\"]");
        sb.append("}");

        // ar-slack is included only when a workstream URL is configured
        if (getWorkstreamUrl() != null && !getWorkstreamUrl().isEmpty()) {
            sb.append(",\"ar-slack\":{");
            sb.append("\"command\":\"python3\",");
            sb.append("\"args\":[\"tools/mcp/slack/server.py\"]");
            sb.append("}");
        }

        sb.append("}}");
        return sb.toString();
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

        // Include enabled servers (or all if no settings file)
        for (Map.Entry<String, String> entry : allServers.entrySet()) {
            String name = entry.getKey();
            // Skip ar-github and ar-slack -- they are handled separately
            // because they need special environment or conditional inclusion
            if ("ar-github".equals(name) || "ar-slack".equals(name)) continue;
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
        List<String> tools = new ArrayList<>();
        if (!Files.exists(serverFile)) return tools;

        try {
            List<String> lines = Files.readAllLines(serverFile, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).trim().startsWith("@mcp.tool")) {
                    // Look at subsequent lines for the function definition
                    for (int j = i + 1; j < Math.min(i + 5, lines.size()); j++) {
                        Matcher m = Pattern.compile("def\\s+(\\w+)\\s*\\(").matcher(lines.get(j));
                        if (m.find()) {
                            tools.add(m.group(1));
                            break;
                        }
                    }
                }
            }
        } catch (IOException e) {
            warn("Failed to scan MCP server source: " + serverFile + ": " + e.getMessage());
        }
        return tools;
    }

    private void extractSessionId(String jsonOutput) {
        // Simple extraction - look for "session_id":"..."
        int idx = jsonOutput.indexOf("\"session_id\"");
        if (idx >= 0) {
            int start = jsonOutput.indexOf("\"", idx + 12) + 1;
            int end = jsonOutput.indexOf("\"", start);
            if (start > 0 && end > start) {
                sessionId = jsonOutput.substring(start, end);
            }
        }
    }

    @Override
    public String encode() {
        StringBuilder sb = new StringBuilder();
        sb.append(super.encode());
        sb.append("::prompt:=").append(base64Encode(prompt));
        sb.append("::tools:=").append(base64Encode(allowedTools));
        sb.append("::maxTurns:=").append(maxTurns);
        sb.append("::maxBudget:=").append(maxBudgetUsd);
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
        private boolean pushToOrigin = true;
        private String workstreamUrl;
        private String gitUserName;
        private String gitUserEmail;

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
