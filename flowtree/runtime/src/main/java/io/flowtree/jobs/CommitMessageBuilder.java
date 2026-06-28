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
 * Resolves the commit message for a finished {@link CodingAgentJob}.
 *
 * <p>The orchestrator first asks the agent to write {@code commit.txt} in the
 * working directory; that file is the source of truth when present and
 * non-empty. When the agent produced no message — or the message is empty —
 * this builder constructs a deterministic fallback from the job's prompt,
 * session id, and exit code so a degraded commit still carries useful
 * provenance.</p>
 *
 * <p>The {@code commit_rule_recovered} source tag is reserved for
 * {@link CommitMessageRule}, which may recover an empty {@code commit.txt}
 * via a focused correction session.</p>
 */
final class CommitMessageBuilder {

    /** Source tag for an agent-authored commit.txt. */
    static final String SOURCE_AGENT = "agent";

    /** Source tag for the prompt-fallback message constructed when no commit.txt was produced. */
    static final String SOURCE_PROMPT_FALLBACK = "prompt_fallback";

    /** Prevents instantiation; this class only exposes static helpers. */
    private CommitMessageBuilder() {
    }

    /**
     * Resolves the commit message for {@code job}.
     *
     * <p>When {@code commit.txt} exists and is non-empty its trimmed content
     * is returned and {@link CodingAgentJob#setCommitMessageSource(String)} is
     * tagged with {@link #SOURCE_AGENT} (unless an earlier source — e.g.
     * {@code commit_rule_recovered} — is already set). When the file is
     * missing, empty, or unreadable, {@link #buildFallbackMessage(CodingAgentJob)}
     * is returned and the source is tagged {@link #SOURCE_PROMPT_FALLBACK}.</p>
     *
     * @param job the orchestrator holding the working directory, prompt, and
     *            session metadata
     * @return the commit message text; never {@code null}
     */
    static String resolve(CodingAgentJob job) {
        Path commitFile = job.resolveWorkingPath("commit.txt");
        if (commitFile != null && Files.exists(commitFile)) {
            try {
                String agentMessage = Files.readString(commitFile, StandardCharsets.UTF_8).trim();
                if (!agentMessage.isEmpty()) {
                    job.log("Using commit message from commit.txt");
                    if (job.getCommitMessageSource() == null) {
                        job.setCommitMessageSource(SOURCE_AGENT);
                    }
                    return agentMessage;
                }
            } catch (IOException e) {
                job.warn("Failed to read commit.txt: " + e.getMessage());
            }
        }

        job.warn("No commit.txt found or it was empty — falling back to task prompt as commit message");
        job.setCommitMessageSource(SOURCE_PROMPT_FALLBACK);
        return buildFallbackMessage(job);
    }

    /**
     * Builds a deterministic fallback commit message from {@code job}'s prompt,
     * session id, and exit code. The first line is a 72-char-capped summary so
     * git log output remains aligned; the body preserves the full prompt and
     * key session metadata for later forensic review.
     *
     * @param job the orchestrator whose prompt and exit fields are read
     * @return the formatted message; never {@code null}
     */
    static String buildFallbackMessage(CodingAgentJob job) {
        StringBuilder msg = new StringBuilder();
        msg.append("Claude Code: ");

        String prompt = job.getPrompt();
        String summary = prompt;
        if (summary.length() > 72) {
            summary = summary.substring(0, 69) + "...";
        }
        msg.append(summary);

        msg.append("\n\nPrompt: ").append(prompt);

        if (job.getSessionId() != null) {
            msg.append("\nSession: ").append(job.getSessionId());
        }

        msg.append("\nExit code: ").append(job.getExitCode());

        return msg.toString();
    }

    /**
     * Captures the current {@code commit.txt} content so a correction session
     * that leaves the file untouched does not lose the primary session's
     * message. {@link CodingAgentJob#executeSingleRun()} deletes a stale
     * {@code commit.txt} at startup, so a correction session would otherwise
     * discard the primary message.
     *
     * @param job the orchestrator whose working directory holds {@code commit.txt}
     * @return the saved commit message, or {@code null} when none was present or it could not be read
     */
    static String captureCommitTxt(CodingAgentJob job) {
        Path commitFile = job.resolveWorkingPath("commit.txt");
        if (commitFile != null && Files.exists(commitFile)) {
            try {
                return Files.readString(commitFile, StandardCharsets.UTF_8);
            } catch (IOException e) {
                job.warn("Could not read commit.txt: " + e.getMessage());
            }
        }
        return null;
    }

    /**
     * Restores a captured {@code commit.txt} only when the correction session
     * did not write its own. A correction that wrote a fresh {@code commit.txt}
     * describes the actual changes it made and must win; restoring the captured
     * message in that case would resurrect a stale primary message.
     *
     * @param job          the orchestrator whose working directory holds {@code commit.txt}
     * @param savedMessage the message captured by {@link #captureCommitTxt(CodingAgentJob)}; {@code null} restores nothing
     */
    static void restoreCommitTxtIfUnwritten(CodingAgentJob job, String savedMessage) {
        if (savedMessage == null) {
            return;
        }
        Path commitFile = job.resolveWorkingPath("commit.txt");
        if (commitFile == null || Files.exists(commitFile)) {
            return;
        }
        try {
            Files.writeString(commitFile, savedMessage, StandardCharsets.UTF_8);
            job.log("Restored primary commit message from commit.txt");
        } catch (IOException e) {
            job.warn("Could not restore commit.txt: " + e.getMessage());
        }
    }
}
