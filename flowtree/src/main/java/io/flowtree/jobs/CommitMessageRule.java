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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Enforcement rule that ensures {@code commit.txt} exists and contains a
 * meaningful, agent-authored commit message.
 *
 * <p>This rule runs LAST in the enforcement order, after every other rule has
 * been satisfied, so that the final commit message reflects the complete set
 * of changes made across all sessions.  Its sole job is to verify and (if
 * needed) recover a usable commit message.</p>
 *
 * <h3>Violation conditions</h3>
 * <ul>
 *   <li>{@code commit.txt} is absent or empty.</li>
 *   <li>{@code commit.txt} is a verbatim copy of the task prompt (the
 *       harness prompt-fallback case).</li>
 *   <li>{@code commit.txt} echoes the rule's own previous correction prompt
 *       (the agent copied the instruction rather than the message).</li>
 * </ul>
 *
 * <h3>Max retries</h3>
 * Two attempts — enough to recover from a single agent mistake without
 * spinning forever.  After two failures the harness falls back to the
 * task prompt and logs a warning.
 *
 * @see ClaudeCodeJob
 */
class CommitMessageRule implements EnforcementRule {

    /** Max retries for this rule — kept low since it is a lightweight housekeeping step. */
    static final int MAX_RETRIES = 2;

    /** Commit message source tag set on the job when this rule recovers a missing message. */
    static final String SOURCE_RECOVERED = "commit_rule_recovered";

    /** The last correction prompt sent, used to detect agent echoing. */
    private String lastCorrectionPrompt;

    @Override
    public String getName() { return "commit-message"; }

    @Override
    public int getMaxRetries() { return MAX_RETRIES; }

    @Override
    public boolean isViolated(ClaudeCodeJob job) {
        String content = readCommitTxt(job);
        if (content == null || content.trim().isEmpty()) {
            return true;
        }
        String trimmed = content.trim();
        // Detect verbatim copy of task prompt
        String taskPrompt = job.getPrompt();
        if (taskPrompt != null && trimmed.equals(taskPrompt.trim())) {
            return true;
        }
        // Detect agent echoing the correction prompt back
        if (lastCorrectionPrompt != null && trimmed.equals(lastCorrectionPrompt.trim())) {
            return true;
        }
        return false;
    }

    @Override
    public String buildCorrectionPrompt(ClaudeCodeJob job) {
        StringBuilder sb = new StringBuilder();
        sb.append("Your changes still need a commit message.\n\n");
        sb.append("Please write a `commit.txt` file at the repo root containing the commit ");
        sb.append("message for your changes. Follow this format:\n\n");
        sb.append("  - **First line:** a short summary of what changed, under 72 characters.\n");
        sb.append("  - **Optionally:** a blank line followed by a short body explaining what ");
        sb.append("you changed and why.\n\n");
        sb.append("**Rules:**\n");
        sb.append("- Do NOT copy the task description into `commit.txt`.\n");
        sb.append("- Do NOT copy these instructions into `commit.txt`.\n");
        sb.append("- Do NOT leave `commit.txt` empty.\n");
        sb.append("- The message must describe the actual work you did — the code or ");
        sb.append("configuration that changed, not the steps you took to do it.\n\n");
        sb.append("Write ONLY `commit.txt`. Do not make any other file changes.");
        String prompt = sb.toString();
        lastCorrectionPrompt = prompt;
        return prompt;
    }

    @Override
    public void onCorrectionAttempted(ClaudeCodeJob job) {
        // If the violation is now resolved, mark the job's commit message source
        // so the telemetry can distinguish recovered messages from primary-session ones.
        if (!isViolated(job)) {
            job.setCommitMessageSource(SOURCE_RECOVERED);
        }
    }

    /**
     * Reads and returns the current {@code commit.txt} content relative to the
     * job's working directory, or {@code null} if it does not exist.
     *
     * @param job the job whose working directory is used
     * @return raw file content, or {@code null}
     */
    private String readCommitTxt(ClaudeCodeJob job) {
        String workDir = job.getWorkingDirectory();
        Path commitFile = workDir != null
                ? Path.of(workDir, "commit.txt")
                : Path.of("commit.txt");
        if (!Files.exists(commitFile)) {
            return null;
        }
        try {
            return Files.readString(commitFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
