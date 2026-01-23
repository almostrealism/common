/*
 * Copyright 2025 Michael Murray
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

    @Override
    protected void doWork() {
        File outputDir = new File("claude-output");
        if (!outputDir.exists()) outputDir.mkdir();

        String outputFile = "claude-output/" + KeyUtils.generateKey() + ".json";

        List<String> command = new ArrayList<>();
        command.add("claude");
        command.add("-p");
        command.add(prompt);
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

        System.out.println("[ClaudeCodeJob] Starting: " + getTaskString());
        System.out.println("[ClaudeCodeJob] Tools: " + allowedTools);
        if (getTargetBranch() != null) {
            System.out.println("[ClaudeCodeJob] Target branch: " + getTargetBranch());
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(command);

            String workDir = getWorkingDirectory();
            if (workDir != null) {
                pb.directory(new File(workDir));
            }

            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder outputBuilder = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    outputBuilder.append(line).append("\n");
                    System.out.println("[ClaudeCode] " + line);
                }
            }

            exitCode = process.waitFor();
            output = outputBuilder.toString();

            // Save output to file
            try (FileWriter writer = new FileWriter(outputFile)) {
                writer.write(output);
            }

            // Try to extract session ID from JSON output
            extractSessionId(output);

            System.out.println("[ClaudeCodeJob] Completed with exit code: " + exitCode);
            System.out.println("[ClaudeCodeJob] Output saved to: " + outputFile);

            if (getOutputConsumer() != null) {
                getOutputConsumer().accept(new ClaudeCodeJobOutput(
                    getTaskId(), prompt, output, sessionId, exitCode
                ));
            }

        } catch (IOException | InterruptedException e) {
            System.err.println("[ClaudeCodeJob] Error: " + e.getMessage());
            e.printStackTrace();
            exitCode = -1;
        }
    }

    @Override
    protected String getCommitMessage() {
        StringBuilder msg = new StringBuilder();
        msg.append("Claude Code: ");

        // Create a summary from the prompt
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
