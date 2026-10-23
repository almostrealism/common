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
 * <p>Both fallbacks the harness relies on when an agent forgets
 * {@code commit.txt} live here: the one {@link #resolve(CodingAgentJob)}
 * applies at commit time, and the shorter one {@link EnforcementRunner} writes
 * when {@link CommitMessageRule} exhausts its correction attempts. Each job
 * owns one instance ({@link CodingAgentJob#commitMessageBuilder()}), so the
 * whole of "an agent that wrote no message still gets its work committed" is
 * one collaborator, replaceable as a unit.</p>
 *
 * <p>The {@code commit_rule_recovered} source tag is reserved for
 * {@link CommitMessageRule}, which may recover an empty {@code commit.txt}
 * via a focused correction session.</p>
 */
class CommitMessageBuilder {

    /** Source tag for an agent-authored commit.txt. */
    static final String SOURCE_AGENT = "agent";

    /** Source tag for the prompt-fallback message constructed when no commit.txt was produced. */
    static final String SOURCE_PROMPT_FALLBACK = "prompt_fallback";

    /** How the job's commit message was produced: one of the source tags, or {@code null} before it was. */
    private String source;

    /** Creates a builder; each {@link CodingAgentJob} holds its own. */
    CommitMessageBuilder() {
    }

    /**
     * Returns how the job's commit message was produced ({@link #SOURCE_AGENT},
     * {@link #SOURCE_PROMPT_FALLBACK}, or {@link CommitMessageRule#SOURCE_RECOVERED}).
     *
     * @return the source tag, or {@code null} when no message has been resolved or recovered yet
     */
    String getSource() {
        return source;
    }

    /**
     * Records how the job's commit message was produced; called by
     * {@link CommitMessageRule} when a correction session recovers it.
     *
     * @param source the source tag
     */
    void setSource(String source) {
        this.source = source;
    }

    /**
     * Resolves the commit message for {@code job}.
     *
     * <p>When {@code commit.txt} exists and is non-empty its trimmed content
     * is returned and {@link #getSource()} is
     * tagged with {@link #SOURCE_AGENT} (unless an earlier source — e.g.
     * {@code commit_rule_recovered} — is already set). When the file is
     * missing, empty, or unreadable, {@link #buildFallbackMessage(CodingAgentJob)}
     * is returned and the source is tagged {@link #SOURCE_PROMPT_FALLBACK}.</p>
     *
     * <p>Author attribution the agent added — {@code Co-Authored-By:} trailers,
     * {@code Generated with ...} tool credits — is removed on the way through
     * (see {@link AuthorAttribution}). Attribution that cannot be removed
     * without rewriting the agent's own prose fails the job here rather than
     * landing in the repository: this is the last point at which the message
     * can still be rejected, and {@link CommitMessageRule} has already given
     * the agent correction attempts to fix it.</p>
     *
     * @param job the orchestrator holding the working directory, prompt, and
     *            session metadata
     * @throws IllegalStateException if {@code commit.txt} carries author
     *         attribution that cannot be safely removed
     * @return the commit message text; never {@code null}
     */
    String resolve(CodingAgentJob job) {
        Path commitFile = job.resolveWorkingPath("commit.txt");
        if (commitFile != null && Files.exists(commitFile)) {
            try {
                String agentMessage = Files.readString(commitFile, StandardCharsets.UTF_8).trim();
                if (!agentMessage.isEmpty()) {
                    job.log("Using commit message from commit.txt");
                    if (source == null) {
                        source = SOURCE_AGENT;
                    }
                    return sanitizeAttribution(job, agentMessage);
                }
            } catch (IOException e) {
                job.warn("Failed to read commit.txt: " + e.getMessage());
            }
        }

        job.warn("No commit.txt found or it was empty — falling back to task prompt as commit message");
        source = SOURCE_PROMPT_FALLBACK;
        return AuthorAttribution.stripLines(buildFallbackMessage(job));
    }

    /**
     * Returns {@code message} with agent-added author attribution removed.
     *
     * <p>Removal succeeds only when the attribution sits in the message's
     * trailing block on lines of its own, which is the shape agents produce
     * and the only shape that can be deleted without editing the agent's
     * description of its work. Attribution welded into that description —
     * inline in a line of prose, or above further body content — is refused:
     * the harness has no way to rewrite it faithfully, and committing it is
     * not an option, so the job fails with the offending lines named.</p>
     *
     * @param job     the orchestrator, used for logging and to name the failure
     * @param message the trimmed {@code commit.txt} content
     * @throws IllegalStateException if the attribution is not safely removable
     * @return the message with attribution removed; never {@code null}
     */
    private static String sanitizeAttribution(CodingAgentJob job, String message) {
        String sanitized = AuthorAttribution.sanitize(message);
        if (sanitized == null) {
            throw new IllegalStateException(
                    "Job failed: commit.txt contains author attribution that cannot be"
                            + " safely removed: "
                            + String.join(" / ", AuthorAttribution.attributionLines(message))
                            + ". Commit messages must not attribute authorship to the agent"
                            + " or credit the tool that produced them.");
        }
        if (!sanitized.equals(message)) {
            job.warn("Removed author attribution from commit.txt: "
                    + String.join(" / ", AuthorAttribution.attributionLines(message)));
        }
        return sanitized;
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
    String buildFallbackMessage(CodingAgentJob job) {
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
     * Builds the commit message {@link EnforcementRunner} writes to
     * {@code commit.txt} when {@link CommitMessageRule} exhausts its retries
     * without the agent producing one, so the job can still commit. Uses, in
     * order:
     * <ol>
     *   <li>The job prompt's first line, if non-empty and free of author
     *       attribution (a prompt line that names an author would produce the
     *       very message {@link #resolve(CodingAgentJob)} refuses to commit)</li>
     *   <li>{@code "Job {jobId} commit"}</li>
     * </ol>
     *
     * @param job the orchestrator whose prompt and task id are read
     * @return the message to write; never {@code null}
     */
    String buildExhaustionFallback(CodingAgentJob job) {
        String prompt = job.getPrompt();
        if (prompt != null && !prompt.trim().isEmpty()) {
            String firstLine = prompt.trim().split("\n")[0].trim();
            if (!firstLine.isEmpty() && !AuthorAttribution.containsAttribution(firstLine)) {
                return firstLine.length() > 72
                        ? firstLine.substring(0, 69) + "..."
                        : firstLine;
            }
        }
        return "Job " + job.getTaskId() + " commit";
    }

    /**
     * Returns whether the agent wrote a commit message to {@code commit.txt}.
     * That file is its declaration that it produced changes worth describing,
     * which is what lets {@link JobWorkOutcome#describeUnpublishedWork()} tell a job that
     * deliberately changed nothing from one whose changes went missing.
     *
     * <p>A file that exists but cannot be read counts as authored. The
     * alternative reads an unreadable message as an absent one, and since
     * {@code commit.txt} is excluded from the working-tree query as a harness
     * artifact, nothing else would notice it either.</p>
     *
     * @param job the orchestrator whose working directory holds {@code commit.txt}
     * @return {@code true} when {@code commit.txt} holds a message, or exists
     *         and could not be read
     */
    boolean isAuthored(CodingAgentJob job) {
        String message = captureCommitTxt(job);
        if (message != null) return !message.trim().isEmpty();
        Path commitFile = job.resolveWorkingPath("commit.txt");
        return commitFile != null && Files.exists(commitFile);
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
     * Restores a captured {@code commit.txt} unconditionally, overwriting whatever a session wrote
     * in between. Used when a session must never own the commit message — the object-oriented
     * design review fixes code and records a verdict, but the message describes the job's work, not
     * the review. When nothing was captured (the session ran with no pending message), any message
     * the session wrote is deleted so it cannot become the commit message by default.
     *
     * @param job          the orchestrator whose working directory holds {@code commit.txt}
     * @param savedMessage the message captured by {@link #captureCommitTxt(CodingAgentJob)}, or
     *                     {@code null} to delete whatever the session left
     */
    static void restoreCommitTxt(CodingAgentJob job, String savedMessage) {
        Path commitFile = job.resolveWorkingPath("commit.txt");
        if (commitFile == null) {
            return;
        }
        try {
            if (savedMessage != null) {
                Files.writeString(commitFile, savedMessage, StandardCharsets.UTF_8);
            } else {
                Files.deleteIfExists(commitFile);
            }
        } catch (IOException e) {
            job.warn("Could not restore commit.txt: " + e.getMessage());
        }
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
