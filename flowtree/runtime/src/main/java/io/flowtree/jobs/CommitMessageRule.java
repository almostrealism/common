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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Enforcement rule that ensures {@code commit.txt} exists and contains a
 * meaningful, agent-authored commit message.
 *
 * <p>This rule runs LAST in the enforcement order, after every other rule has
 * been satisfied, so that the final commit message reflects the complete set
 * of changes made across all sessions.  Its sole job is to verify and (if
 * needed) recover a usable commit message.</p>
 *
 * <p>The rule checks every repository the job changed — the primary working
 * directory and each dependent checkout beside it — and is violated when any
 * of them holds uncommitted changes without a valid {@code commit.txt} at its
 * own root. A repository the agent left untouched is not checked: a session
 * that deliberately changed nothing — a review that found no defect, an
 * investigation that only reported — has nothing to describe, and demanding a
 * message from it would both waste a correction session and destroy the file's
 * value as a signal: an authored message with no commit behind it is what tells
 * {@link JobWorkOutcome#describeUnpublishedWork()} that a job's changes went
 * missing, and that only holds if a message means the agent had changes.</p>
 *
 * <h3>Violation conditions</h3>
 * <ul>
 *   <li>a changed repository's {@code commit.txt} is absent or empty.</li>
 *   <li>{@code commit.txt} is a verbatim copy of the task prompt (the
 *       harness prompt-fallback case).</li>
 *   <li>{@code commit.txt} echoes the rule's own previous correction prompt
 *       (the agent copied the instruction rather than the message).</li>
 *   <li>{@code commit.txt} carries author attribution &mdash; a
 *       {@code Co-Authored-By:} trailer or a tool credit &mdash; that cannot
 *       be removed without rewriting the agent's own prose (see
 *       {@link AuthorAttribution}). Attribution that sits on its own lines at
 *       the end of the message is <em>not</em> a violation: it is deleted
 *       during {@link CommitMessageBuilder#resolve(CodingAgentJob)} without
 *       spending a correction session.</li>
 * </ul>
 *
 * <h3>Max retries</h3>
 * Two attempts — enough to recover from a single agent mistake without
 * spinning forever.  After two failures the harness falls back to the
 * task prompt and logs a warning.
 *
 * @see CodingAgentJob
 */
class CommitMessageRule implements EnforcementRule {

    /** Max retries for this rule — kept low since it is a lightweight housekeeping step. */
    static final int MAX_RETRIES = 2;

    /** Commit message source tag set on the job when this rule recovers a missing message. */
    static final String SOURCE_RECOVERED = "commit_rule_recovered";

    /** The last correction prompt sent, used to detect agent echoing. */
    private String lastCorrectionPrompt;

    /**
     * Whether the primary working directory was among the repositories needing a
     * message when the most recent correction prompt was built. The commit
     * message source tag describes the <em>primary</em> repository's message, so
     * {@link #onCorrectionAttempted(CodingAgentJob)} tags it recovered only when
     * the primary itself was recovered — not when a correction supplied only a
     * dependent checkout's message.
     */
    private boolean primaryNeededMessage;

    @Override
    public String getName() { return "commit-message"; }

    @Override
    public int getMaxRetries() { return MAX_RETRIES; }

    /** Writing {@code commit.txt} is exactly how this rule is resolved. */
    @Override
    public Set<String> getProgressPaths() { return Set.of("commit.txt"); }

    @Override
    public boolean isViolated(CodingAgentJob job) {
        return !repositoriesNeedingMessage(job).isEmpty();
    }

    /**
     * Returns the root path of every repository the job changed that still
     * lacks a valid {@code commit.txt} — the primary working directory and each
     * dependent checkout beside it. A repository is checked only when it holds
     * uncommitted changes (fail-closed: a tree that cannot be read is treated
     * as changed, so it still owes a message), because a repository the agent
     * left untouched has nothing to describe.
     *
     * @param job the orchestrator whose repositories are checked
     * @return the repository roots missing a valid message, in primary-first order
     */
    private List<String> repositoriesNeedingMessage(CodingAgentJob job) {
        List<String> missing = new ArrayList<>();
        JobWorkOutcome outcome = job.workOutcome();
        addIfMissing(job, outcome, job.getWorkingDirectory(), missing);
        for (String dep : job.getDependentRepoPaths()) {
            addIfMissing(job, outcome, dep, missing);
        }
        return missing;
    }

    /**
     * Adds {@code repoRoot} to {@code missing} when it holds uncommitted changes
     * but its {@code commit.txt} is absent, empty, a copy of the task prompt or
     * correction prompt, or carries un-removable author attribution.
     *
     * @param job      the orchestrator (for its prompt and work outcome)
     * @param outcome  the job's work outcome, queried per repository
     * @param repoRoot the repository root to check, or {@code null}
     * @param missing  the collection that receives a repository needing a message
     */
    private void addIfMissing(CodingAgentJob job, JobWorkOutcome outcome, String repoRoot,
            List<String> missing) {
        if (repoRoot == null || !outcome.hasUncommittedChanges(repoRoot)) {
            return;
        }
        if (!isValidMessage(job, readCommitTxt(repoRoot))) {
            missing.add(repoRoot);
        }
    }

    /**
     * Returns whether {@code content} is a usable, agent-authored commit message:
     * non-empty, not a verbatim copy of the task prompt, not an echo of the
     * rule's own last correction prompt, and free of un-removable attribution.
     *
     * @param job     the orchestrator whose prompt the content is compared against
     * @param content the raw {@code commit.txt} content, or {@code null}
     * @return {@code true} when the content is a valid commit message
     */
    private boolean isValidMessage(CodingAgentJob job, String content) {
        if (content == null || content.trim().isEmpty()) {
            return false;
        }
        String trimmed = content.trim();
        String taskPrompt = job.getPrompt();
        if (taskPrompt != null && trimmed.equals(taskPrompt.trim())) {
            return false;
        }
        if (lastCorrectionPrompt != null && trimmed.equals(lastCorrectionPrompt.trim())) {
            return false;
        }
        return AuthorAttribution.sanitize(trimmed) != null;
    }

    @Override
    public String buildCorrectionPrompt(CodingAgentJob job) {
        List<String> missing = repositoriesNeedingMessage(job);
        String primary = job.getWorkingDirectory();
        primaryNeededMessage = primary != null && missing.contains(primary);

        // A repository whose commit.txt carries un-removable attribution needs
        // the rewrite guidance; the common case is simply a missing message.
        for (String repoRoot : missing) {
            String content = readCommitTxt(repoRoot);
            String trimmed = content == null ? "" : content.trim();
            if (!trimmed.isEmpty() && AuthorAttribution.sanitize(trimmed) == null) {
                return buildAttributionCorrectionPrompt(repoRoot, trimmed);
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Your changes still need a commit message.\n\n");
        sb.append("Write a `commit.txt` file at the root of EACH repository below that you ");
        sb.append("changed, containing that repository's own commit message:\n");
        for (String repoRoot : missing) {
            sb.append("  - `").append(repoRoot).append("`");
            sb.append(repoRoot.equals(primary) ? " (the primary repository)\n" : " (a dependent repository)\n");
        }
        sb.append("\nFollow this format for each file:\n");
        sb.append("  - **First line:** a short summary of what changed, under 72 characters.\n");
        sb.append("  - **Optionally:** a blank line followed by a short body explaining what ");
        sb.append("you changed and why.\n\n");
        sb.append("**Rules:**\n");
        sb.append("- Each `commit.txt` describes the changes in ITS OWN repository, not another's.\n");
        sb.append("- Do NOT copy the task description into any `commit.txt`.\n");
        sb.append("- Do NOT copy these instructions into any `commit.txt`.\n");
        sb.append("- Do NOT leave any `commit.txt` empty.\n");
        sb.append("- Do NOT add author attribution of any kind — no `Co-Authored-By` ");
        sb.append("trailer, no \"Generated with\" or \"Created by\" tool credit, no ");
        sb.append("agent name, e-mail address, or product URL. The commit is authored ");
        sb.append("by the configured git identity; you are not a co-author of it.\n");
        sb.append("- The message must describe the actual work you did — the code or ");
        sb.append("configuration that changed, not the steps you took to do it.\n\n");
        sb.append("Write ONLY the `commit.txt` files named above. Do not make any other file changes.");
        String prompt = sb.toString();
        lastCorrectionPrompt = prompt;
        return prompt;
    }

    /**
     * Builds the correction prompt for the attribution case: {@code commit.txt}
     * names an author or credits a tool in a position the harness cannot
     * delete on its own. Names the offending lines so the agent rewrites the
     * message rather than guessing at what was rejected.
     *
     * @param repoRoot the repository root whose {@code commit.txt} is offending
     * @param message the current (trimmed) {@code commit.txt} content
     * @return the correction prompt
     */
    private String buildAttributionCorrectionPrompt(String repoRoot, String message) {
        StringBuilder sb = new StringBuilder();
        sb.append("The `commit.txt` at `").append(repoRoot);
        sb.append("` attributes authorship of the commit. ");
        sb.append("That is not permitted, and it is written in a way the harness ");
        sb.append("cannot strip automatically, so you must rewrite it.\n\n");
        sb.append("The offending content:\n");
        for (String line : AuthorAttribution.attributionLines(message)) {
            sb.append("  - ").append(line).append("\n");
        }
        sb.append("\nRewrite `commit.txt` describing only what changed and why. ");
        sb.append("Do NOT include a `Co-Authored-By` trailer, a \"Generated with\" or ");
        sb.append("\"Created by\" tool credit, an agent name, an e-mail address, or a ");
        sb.append("product URL — anywhere in the message, including inside a sentence. ");
        sb.append("The commit is authored by the configured git identity; you are not a ");
        sb.append("co-author of it.\n\n");
        sb.append("Write ONLY `commit.txt`. Do not make any other file changes.");
        String prompt = sb.toString();
        lastCorrectionPrompt = prompt;
        return prompt;
    }

    /**
     * Tags the job's commit message source {@link #SOURCE_RECOVERED} when the
     * correction supplied the <em>primary</em> repository's message.
     *
     * <p>The source tag describes how the primary repository's message was
     * produced, so a correction that recovered only a dependent checkout's
     * {@code commit.txt} must not be misreported as recovering the primary one.
     * The tag is set only when the primary itself needed a message at prompt
     * time ({@link #primaryNeededMessage}) and no longer does.</p>
     *
     * @param job the orchestrator whose message source may be tagged
     */
    @Override
    public void onCorrectionAttempted(CodingAgentJob job) {
        String primary = job.getWorkingDirectory();
        boolean primaryStillMissing = primary != null
                && repositoriesNeedingMessage(job).contains(primary);
        if (primaryNeededMessage && !primaryStillMissing) {
            job.commitMessageBuilder().setSource(SOURCE_RECOVERED);
        }
    }

    /**
     * Reads and returns the {@code commit.txt} content at the root of the
     * repository {@code repoRoot}, or {@code null} if it does not exist.
     *
     * @param repoRoot the repository root whose {@code commit.txt} is read
     * @return raw file content, or {@code null}
     */
    private static String readCommitTxt(String repoRoot) {
        Path commitFile = repoRoot != null
                ? Path.of(repoRoot, "commit.txt")
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
