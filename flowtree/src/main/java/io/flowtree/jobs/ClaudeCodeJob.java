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
 */
public class ClaudeCodeJob extends GitManagedJob {
    public static final String PROMPT_SEPARATOR = ";;PROMPT;;";
    public static final String DEFAULT_TOOLS = "Read,Edit,Write,Bash,Glob,Grep";

    private String prompt;
    private String allowedTools;
    private int maxTurns;
    private double maxBudgetUsd;

    private String slackApiUrl;
    private String slackChannelId;
    private String slackThreadTs;

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
     * Returns the Slack API endpoint URL for this job.
     */
    public String getSlackApiUrl() {
        return slackApiUrl;
    }

    /**
     * Sets the Slack API endpoint URL. When set, the Claude Code process
     * will have access to the ar-slack MCP server for sending messages.
     *
     * @param slackApiUrl the HTTP URL of the SlackApiEndpoint
     */
    public void setSlackApiUrl(String slackApiUrl) {
        this.slackApiUrl = slackApiUrl;
    }

    /**
     * Returns the Slack channel ID for this job.
     */
    public String getSlackChannelId() {
        return slackChannelId;
    }

    /**
     * Sets the Slack channel ID for messages from this job.
     *
     * @param slackChannelId the Slack channel ID (e.g., "C0123456789")
     */
    public void setSlackChannelId(String slackChannelId) {
        this.slackChannelId = slackChannelId;
    }

    /**
     * Returns the Slack thread timestamp for this job.
     */
    public String getSlackThreadTs() {
        return slackThreadTs;
    }

    /**
     * Sets the Slack thread timestamp for replies from this job.
     *
     * @param slackThreadTs the thread timestamp
     */
    public void setSlackThreadTs(String slackThreadTs) {
        this.slackThreadTs = slackThreadTs;
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
     */
    private String buildInstructionPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("You are working autonomously as a coding agent. ");
        sb.append("There is no TTY and no interactive session — do not attempt to wait ");
        sb.append("for user input or interactive chat responses.\n\n");

        sb.append("You can communicate about the project using the Slack MCP tools ");
        sb.append("(slack_send_message, slack_send_thread_reply). ");
        sb.append("Use these freely to ask questions, report progress, or share results. ");
        sb.append("However, do not wait for a reply — continue working after sending a message.\n\n");

        sb.append("Do NOT make git commits. Your work will be committed by the harness ");
        sb.append("after you finish. If you want to control the commit message, write it ");
        sb.append("to a file called `commit.txt` in the working directory root.\n\n");

        sb.append("--- BEGIN USER REQUEST ---\n");
        sb.append(prompt);
        sb.append("\n--- END USER REQUEST ---");

        return sb.toString();
    }

    @Override
    protected void doWork() {
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
        command.add(allowedTools);
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

        // Add MCP config for the Slack tool if a Slack API URL is set
        if (slackApiUrl != null && !slackApiUrl.isEmpty()) {
            command.add("--mcp-config");
            command.add(buildSlackMcpConfig());
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(command);

            String workDir = getWorkingDirectory();
            if (workDir != null) {
                pb.directory(new File(workDir));
            }

            // Set Slack environment variables for the MCP server
            if (slackApiUrl != null && !slackApiUrl.isEmpty()) {
                pb.environment().put("AR_SLACK_API_URL", slackApiUrl);
            }
            if (slackChannelId != null && !slackChannelId.isEmpty()) {
                pb.environment().put("AR_SLACK_CHANNEL_ID", slackChannelId);
            }
            if (slackThreadTs != null && !slackThreadTs.isEmpty()) {
                pb.environment().put("AR_SLACK_THREAD_TS", slackThreadTs);
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
     * Builds a JSON MCP configuration string pointing to the ar-slack server.
     * This is passed to Claude Code via the {@code --mcp-config} flag.
     */
    private String buildSlackMcpConfig() {
        return "{\"mcpServers\":{\"ar-slack\":{" +
               "\"command\":\"python3\"," +
               "\"args\":[\"tools/mcp/slack/server.py\"]" +
               "}}}";
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
        if (slackApiUrl != null) {
            sb.append("::slackApiUrl:=").append(base64Encode(slackApiUrl));
        }
        if (slackChannelId != null) {
            sb.append("::slackChannelId:=").append(base64Encode(slackChannelId));
        }
        if (slackThreadTs != null) {
            sb.append("::slackThreadTs:=").append(base64Encode(slackThreadTs));
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
            case "slackApiUrl":
                this.slackApiUrl = base64Decode(value);
                break;
            case "slackChannelId":
                this.slackChannelId = base64Decode(value);
                break;
            case "slackThreadTs":
                this.slackThreadTs = base64Decode(value);
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
        private String workstreamId;
        private String gitUserName;
        private String gitUserEmail;
        private String slackApiUrl;
        private String slackChannelId;
        private String statusReportUrl;

        /**
         * Default constructor for deserialization.
         */
        public Factory() {
            super(KeyUtils.generateKey());
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

        public String getWorkstreamId() {
            return workstreamId;
        }

        /**
         * Sets the workstream ID for jobs created by this factory.
         * Used for routing completion events to the correct Slack channel.
         *
         * @param workstreamId the workstream identifier
         */
        public void setWorkstreamId(String workstreamId) {
            this.workstreamId = workstreamId;
            set("workstream", base64Encode(workstreamId));
        }

        /**
         * Returns the Slack API endpoint URL for jobs created by this factory.
         */
        public String getSlackApiUrl() {
            return slackApiUrl;
        }

        /**
         * Sets the Slack API endpoint URL for jobs created by this factory.
         *
         * @param slackApiUrl the HTTP URL of the SlackApiEndpoint
         */
        public void setSlackApiUrl(String slackApiUrl) {
            this.slackApiUrl = slackApiUrl;
            set("slackApiUrl", base64Encode(slackApiUrl));
        }

        /**
         * Returns the Slack channel ID for jobs created by this factory.
         */
        public String getSlackChannelId() {
            return slackChannelId;
        }

        /**
         * Sets the Slack channel ID for jobs created by this factory.
         *
         * @param slackChannelId the Slack channel ID
         */
        public void setSlackChannelId(String slackChannelId) {
            this.slackChannelId = slackChannelId;
            set("slackChannelId", base64Encode(slackChannelId));
        }

        /**
         * Returns the status report URL for jobs created by this factory.
         */
        public String getStatusReportUrl() {
            return statusReportUrl;
        }

        /**
         * Sets the URL where job status events will be POSTed as JSON.
         *
         * @param statusReportUrl the HTTP URL of the controller's job event endpoint
         */
        public void setStatusReportUrl(String statusReportUrl) {
            this.statusReportUrl = statusReportUrl;
            set("statusReportUrl", base64Encode(statusReportUrl));
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

            // Workstream settings
            if (workstreamId != null) {
                job.setWorkstreamId(workstreamId);
            }

            // Status reporting
            if (statusReportUrl != null) {
                job.setStatusReportUrl(statusReportUrl);
            }

            // Slack MCP settings
            if (slackApiUrl != null) {
                job.setSlackApiUrl(slackApiUrl);
            }
            if (slackChannelId != null) {
                job.setSlackChannelId(slackChannelId);
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
                case "workstream":
                    this.workstreamId = base64Decode(value);
                    break;
                case "slackApiUrl":
                    this.slackApiUrl = base64Decode(value);
                    break;
                case "slackChannelId":
                    this.slackChannelId = base64Decode(value);
                    break;
                case "gitUserName":
                    this.gitUserName = base64Decode(value);
                    break;
                case "gitUserEmail":
                    this.gitUserEmail = base64Decode(value);
                    break;
                case "statusReportUrl":
                    this.statusReportUrl = base64Decode(value);
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
