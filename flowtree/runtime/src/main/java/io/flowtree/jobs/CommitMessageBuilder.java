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

import io.flowtree.git.GitOperations;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the commit message for a finished {@link CodingAgentJob}.
 *
 * <p>The orchestrator first asks the agent to write {@code commit.txt} in the
 * working directory; that file is the source of truth when present and
 * non-empty. When the agent produced no message — or the message is empty —
 * this builder constructs a deterministic fallback whose subject names the
 * files the job actually changed (never the task prompt, which describes what
 * was asked for rather than what was done), carrying the session id and exit
 * code so a degraded commit still has useful provenance.</p>
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

    /** Maximum length of a generated commit subject, matching git's conventional 72-column subject. */
    private static final int SUBJECT_LIMIT = 72;

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
        return resolveFrom(job, job.resolveWorkingPath("commit.txt"));
    }

    /**
     * Resolves the commit message for the repository rooted at {@code repoRoot}
     * — the primary working directory or a dependent checkout placed beside it.
     *
     * <p>Each repository the job changed carries its own {@code commit.txt} at
     * its root, so a dependent repository commits with the message the agent
     * wrote for it rather than the primary repository's message. The primary
     * repository (identified by {@link CodingAgentJob#getWorkingDirectory()})
     * resolves through {@link #resolve(CodingAgentJob)} so it alone tags
     * {@link #getSource()}; a dependent repository's resolution never touches
     * that source, which describes how the <em>job's</em> message was produced.</p>
     *
     * <p>When a dependent repository's {@code commit.txt} is missing or empty,
     * the fallback names that repository (see
     * {@link #buildDependentFallbackMessage(String)}) so a prompt-fallback
     * message can never be mistaken for the primary one.</p>
     *
     * @param job      the orchestrator holding the working directory and prompt
     * @param repoRoot the repository root whose {@code commit.txt} is resolved
     * @throws IllegalStateException if {@code commit.txt} carries author
     *         attribution that cannot be safely removed
     * @return the commit message text; never {@code null}
     */
    String resolveForRepo(CodingAgentJob job, String repoRoot) {
        if (repoRoot == null || repoRoot.equals(job.getWorkingDirectory())) {
            return resolve(job);
        }
        Path commitFile = Path.of(repoRoot, "commit.txt");
        if (Files.exists(commitFile)) {
            try {
                String agentMessage = Files.readString(commitFile, StandardCharsets.UTF_8).trim();
                if (!agentMessage.isEmpty()) {
                    job.log("Using commit message from commit.txt in dependent repo: " + repoRoot);
                    return sanitizeAttribution(job, agentMessage);
                }
            } catch (IOException e) {
                job.warn("Failed to read commit.txt in dependent repo " + repoRoot
                        + ": " + e.getMessage());
            }
        }
        job.warn("No commit.txt found in dependent repo " + repoRoot
                + " — falling back to a repository-named message");
        return AuthorAttribution.stripLines(buildDependentFallbackMessage(repoRoot));
    }

    /**
     * Resolves the commit message from the given {@code commit.txt} path,
     * tagging {@link #getSource()} the way the primary repository requires.
     *
     * @param job        the orchestrator, used for logging and source tagging
     * @param commitFile the {@code commit.txt} to read, or {@code null}
     * @throws IllegalStateException if the message carries un-removable attribution
     * @return the resolved message; never {@code null}
     */
    private String resolveFrom(CodingAgentJob job, Path commitFile) {
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

        job.warn("No commit.txt found or it was empty — generating a commit message from the"
                + " files the job changed");
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
     * Builds a deterministic fallback commit message for a job that wrote no
     * {@code commit.txt}. The subject is {@link #buildChangeSubject(CodingAgentJob)}
     * — the files the job actually changed, never the task prompt — so the
     * commit (and any pull request that takes its title from this subject)
     * describes what was done. The body records that the harness generated the
     * message plus the session id and exit code for later forensic review; it
     * never echoes the prompt.
     *
     * @param job the orchestrator whose changed files and exit fields are read
     * @return the formatted message; never {@code null}
     */
    String buildFallbackMessage(CodingAgentJob job) {
        StringBuilder msg = new StringBuilder();
        msg.append(buildChangeSubject(job));
        msg.append("\n\nThe harness generated this message because the agent wrote no commit.txt.");

        if (job.getSessionId() != null) {
            msg.append("\nSession: ").append(job.getSessionId());
        }

        msg.append("\nExit code: ").append(job.getExitCode());

        return msg.toString();
    }

    /**
     * Builds the last-resort commit message for a dependent repository whose
     * {@code commit.txt} the agent never wrote. Names the repository so the
     * message cannot be mistaken for the primary repository's, and so a reader
     * of the dependent repository's history can tell it was generated rather
     * than authored.
     *
     * @param repoRoot the dependent repository's root path
     * @return the formatted message; never {@code null}
     */
    String buildDependentFallbackMessage(String repoRoot) {
        String name = finalSegment(repoRoot);
        return "Changes in dependent repository " + name
                + "\n\nThis message was generated by the harness because no commit.txt was"
                + " written at the root of this dependent repository."
                + " It describes a change the primary repository's work required.";
    }

    /**
     * Builds the commit message {@link EnforcementRunner} writes to
     * {@code commit.txt} when {@link CommitMessageRule} exhausts its retries
     * without the agent producing one, so the job can still commit. The message
     * is a single {@link #buildChangeSubject(CodingAgentJob) change-named
     * subject} — never the task prompt, which describes what was asked for
     * rather than what was done, and which produced pull-request titles like
     * the agent's own instructions.
     *
     * @param job the orchestrator whose changed files and task id are read
     * @return the message to write; never {@code null}
     */
    String buildExhaustionFallback(CodingAgentJob job) {
        return buildChangeSubject(job);
    }

    /**
     * Builds a commit subject from the files the job changed in its working
     * tree — the uncommitted changes it is about to commit, read from
     * {@code git status} and stripped of harness scratch paths. Names up to two
     * changed files ({@code "Update Foo and FooTest"}), or the first file and a
     * count of the rest when there are more ({@code "Update Foo and 3 other
     * files"}), keeping the subject within git's conventional 72-column limit.
     * When no changed files can be determined — a clean tree, or a
     * {@code git status} that could not be run — the subject names the job
     * instead ({@code "Job {taskId} commit"}) and never the prompt.
     *
     * @param job the orchestrator whose working tree and task id are read
     * @return the subject line; never {@code null}
     */
    String buildChangeSubject(CodingAgentJob job) {
        List<String> changed = new ArrayList<>();
        for (String path : GitOperations.getChangedFiles(job.getWorkingDirectory())) {
            if (!GitOperations.isExcludedPath(path)) {
                changed.add(path);
            }
        }
        String subject = summarizeChangedFiles(changed);
        return subject != null ? subject : "Job " + job.getTaskId() + " commit";
    }

    /**
     * Summarizes the changed file paths into a commit subject, or returns
     * {@code null} when the list names no files. Base names are deduplicated in
     * order (so {@code .../Foo.java} and {@code .../Foo.java} count once) and
     * stripped of directory and extension, so the subject reads as class or
     * file names rather than full repository paths.
     *
     * @param changedPaths the repository-relative changed paths, or {@code null}
     * @return the {@code "Update ..."} subject, or {@code null} when empty
     */
    static String summarizeChangedFiles(List<String> changedPaths) {
        List<String> names = new ArrayList<>();
        if (changedPaths != null) {
            for (String path : changedPaths) {
                String base = baseName(path);
                if (!base.isEmpty() && !names.contains(base)) {
                    names.add(base);
                }
            }
        }
        if (names.isEmpty()) {
            return null;
        }

        String full = names.size() == 1
                ? "Update " + names.get(0)
                : "Update " + names.get(0) + " and " + names.get(1);
        if (names.size() <= 2 && full.length() <= SUBJECT_LIMIT) {
            return full;
        }

        int others = names.size() - 1;
        String summary = "Update " + names.get(0) + " and " + others
                + (others == 1 ? " other file" : " other files");
        return summary.length() <= SUBJECT_LIMIT
                ? summary
                : summary.substring(0, SUBJECT_LIMIT - 3) + "...";
    }

    /**
     * Returns the base name of {@code path} — its final segment with any file
     * extension removed — so {@code .../jobs/IndexSequence.java} becomes
     * {@code IndexSequence}.
     *
     * @param path a repository-relative file path
     * @return the extension-free final segment, trimmed
     */
    private static String baseName(String path) {
        String name = finalSegment(path);
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        return name.trim();
    }

    /**
     * Returns the final path segment of {@code path} — the text after its last
     * {@code /} or {@code \} separator — or the whole string when it names no
     * directory. Keeps any file extension; {@link #baseName(String)} strips that.
     *
     * @param path a file or directory path
     * @return the final segment, never {@code null}
     */
    private static String finalSegment(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        if (slash >= 0 && slash < path.length() - 1) {
            return path.substring(slash + 1);
        }
        return path;
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
