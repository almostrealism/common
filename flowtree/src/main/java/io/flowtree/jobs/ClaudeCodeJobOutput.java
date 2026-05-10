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

import org.almostrealism.io.JobOutput;

/**
 * Output record produced by a completed {@link ClaudeCodeJob}.  Carries the
 * prompt, the Claude Code session ID, and the final process exit code.
 */
public class ClaudeCodeJobOutput extends JobOutput {
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

    /** Returns the prompt that was submitted to Claude Code. */
    public String getPrompt() { return prompt; }

    /** Returns the Claude Code session identifier. */
    public String getSessionId() { return sessionId; }

    /** Returns the process exit code (0 typically indicates success). */
    public int getExitCode() { return exitCode; }

    @Override
    public String toString() {
        return "ClaudeCodeJobOutput{taskId=" + getTaskId() + ", exitCode=" + exitCode
                + ", sessionId=" + sessionId + "}";
    }
}
