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

import java.util.List;

/**
 * Builds the second-pass review prompt sent to the {@link ReviewRule}'s
 * agent session. The prompt establishes the reviewer's narrow mandate:
 * make surgical fixes when the issue is unambiguous, defer everything
 * else via a {@code review-followup} memory and an inline
 * {@code TODO(review):} comment.
 *
 * <p>Structure:</p>
 * <ol>
 *   <li>Role + bias statement ("when in doubt, defer")</li>
 *   <li>Branch context (target/base, workstream URL)</li>
 *   <li>Original task prompt (truncated)</li>
 *   <li>List of new files</li>
 *   <li>"DO directly" categories</li>
 *   <li>"DEFER" categories</li>
 *   <li>How to defer (memory_store + TODO templates)</li>
 *   <li>Forbidden actions</li>
 *   <li>Expected output shape</li>
 *   <li>The diff (truncated as needed)</li>
 * </ol>
 *
 * <p>Package-private; tests use {@link #build(CodingAgentJob)}.</p>
 *
 * @author Michael Murray
 * @see ReviewRule
 */
final class ReviewPromptBuilder {

    /** Maximum number of diff characters embedded in the prompt. */
    static final int MAX_DIFF_CHARS = 60_000;

    /** Number of head characters retained when truncating a too-large diff. */
    static final int DIFF_HEAD_CHARS = 30_000;

    /** Number of tail characters retained when truncating a too-large diff. */
    static final int DIFF_TAIL_CHARS = 30_000;

    /** Maximum number of characters from the original task prompt included. */
    static final int MAX_TASK_PROMPT_CHARS = 2_000;

    /** Maximum number of new file paths listed in the prompt. */
    static final int MAX_NEW_FILES_LISTED = 50;

    /** Static-only helper. */
    private ReviewPromptBuilder() {}

    /**
     * Extracts the workstream id from a workstream URL of the shape
     * {@code .../api/workstreams/<wsId>[/jobs/...]}.
     *
     * @param url the workstream URL or {@code null}
     * @return the workstream id, or {@code null} when none could be parsed
     */
    static String extractWorkstreamId(String url) {
        if (url == null || url.isEmpty()) return null;
        int idx = url.indexOf("/workstreams/");
        if (idx < 0) return null;
        int start = idx + "/workstreams/".length();
        int end = url.indexOf('/', start);
        String id = end < 0 ? url.substring(start) : url.substring(start, end);
        return id.isEmpty() ? null : id;
    }

    /**
     * Builds the review prompt for {@code job}.
     *
     * @param job the job whose primary phase has just completed
     * @return the prompt string sent to the review agent
     */
    static String build(CodingAgentJob job) {
        StringBuilder sb = new StringBuilder();
        appendHeader(sb);
        appendContext(sb, job);
        appendTaskPrompt(sb, job);
        appendNewFiles(sb, job);
        appendDoDirectly(sb);
        appendDefer(sb);
        appendHowToDefer(sb, job);
        appendForbidden(sb);
        appendExpectedOutput(sb);
        appendDiff(sb, job);
        return sb.toString();
    }

    /** Appends the role-and-bias header. */
    private static void appendHeader(StringBuilder sb) {
        sb.append("SECOND-PASS REVIEW\n\n");
        sb.append("You are reviewing changes made by another agent in the same ");
        sb.append("pipeline. You did NOT write this code. Your job is to catch ");
        sb.append("obvious problems before the rest of the pipeline runs.\n\n");

        sb.append("BIAS STATEMENT\n");
        sb.append("When in doubt, DEFER. A reviewer that files a useful memory ");
        sb.append("and a useful TODO comment is a successful reviewer even if ");
        sb.append("they change zero lines. A wrong edit is worse than no edit. ");
        sb.append("The downstream phases (deduplication, organizational placement, ");
        sb.append("post-completion command) will also see this diff; you do not ");
        sb.append("have to catch everything yourself.\n\n");
    }

    /** Appends the target/base/workstream context. */
    private static void appendContext(StringBuilder sb, CodingAgentJob job) {
        sb.append("BRANCH CONTEXT\n");
        String target = job.getTargetBranch();
        String base = job.getBaseBranch();
        sb.append("  target branch: ").append(target != null ? target : "(unset)").append('\n');
        sb.append("  base branch:   ").append(base != null ? base : "(unset)").append('\n');
        String wsUrl = job.resolveWorkstreamUrl();
        if (wsUrl != null && !wsUrl.isEmpty()) {
            sb.append("  workstream:    ").append(wsUrl).append('\n');
        }
        sb.append('\n');
    }

    /** Appends the original task prompt, truncated to {@link #MAX_TASK_PROMPT_CHARS}. */
    private static void appendTaskPrompt(StringBuilder sb, CodingAgentJob job) {
        String prompt = job.getPrompt();
        if (prompt == null || prompt.isEmpty()) return;
        sb.append("ORIGINAL TASK PROMPT\n");
        if (prompt.length() <= MAX_TASK_PROMPT_CHARS) {
            sb.append(prompt);
        } else {
            sb.append(prompt, 0, MAX_TASK_PROMPT_CHARS);
            sb.append("\n[... truncated, original prompt was ").append(prompt.length())
              .append(" chars ...]");
        }
        sb.append("\n\n");
    }

    /** Appends the list of new files on the branch, capped at {@link #MAX_NEW_FILES_LISTED}. */
    private static void appendNewFiles(StringBuilder sb, CodingAgentJob job) {
        List<String> newFiles = job.extractNewFilePaths();
        if (newFiles.isEmpty()) return;
        sb.append("NEW FILES ON THIS BRANCH\n");
        int shown = Math.min(newFiles.size(), MAX_NEW_FILES_LISTED);
        for (int i = 0; i < shown; i++) {
            sb.append("  ").append(newFiles.get(i)).append('\n');
        }
        if (newFiles.size() > shown) {
            sb.append("  (... ").append(newFiles.size() - shown)
              .append(" more not listed ...)\n");
        }
        sb.append('\n');
    }

    /** Appends the "fixes you may make directly" category list. */
    private static void appendDoDirectly(StringBuilder sb) {
        sb.append("FIXES YOU MAY MAKE DIRECTLY (and only these)\n");
        sb.append("  1. Typos in strings, comments, or javadoc.\n");
        sb.append("  2. Obviously-missing imports that any compiler would flag.\n");
        sb.append("  3. Trivial null checks or guard clauses (1-2 lines, the ");
        sb.append("correctness must be unambiguous from the diff alone).\n");
        sb.append("  4. One-line bug fixes where the correct behavior can be ");
        sb.append("read straight off the diff (e.g. an off-by-one with an ");
        sb.append("obvious correct value).\n");
        sb.append("  5. A single missing JUnit test method that exercises one ");
        sb.append("newly-introduced method, added inside an existing test class. ");
        sb.append("Do not create a new test class.\n\n");
    }

    /** Appends the "issues you must defer" category list. */
    private static void appendDefer(StringBuilder sb) {
        sb.append("ISSUES YOU MUST DEFER (do NOT change yourself)\n");
        sb.append("  - Architectural or design concerns.\n");
        sb.append("  - Cross-cutting changes that would touch multiple files.\n");
        sb.append("  - Performance concerns.\n");
        sb.append("  - Style or naming concerns.\n");
        sb.append("  - Anything that requires reading code outside the diff to ");
        sb.append("evaluate.\n");
        sb.append("  - Anything where you are not certain of the right fix.\n");
        sb.append("  - Anything that touches more than a handful of lines.\n\n");
    }

    /** Appends the {@code memory_store} + {@code TODO(review):} templates. */
    private static void appendHowToDefer(StringBuilder sb, CodingAgentJob job) {
        sb.append("HOW TO DEFER\n");
        sb.append("For each item you want to flag for follow-up, do BOTH:\n\n");

        sb.append("  (a) Call memory_store with tags including \"review-followup\" ");
        sb.append("and \"workstream:<workstream-id>\":\n\n");
        sb.append("      memory_store(\n");
        sb.append("        content=\"Reviewer note (path/to/file:LINE): <one-paragraph\\n");
        sb.append("                  description of the issue and the suggested approach>\",\n");
        sb.append("        namespace=\"default\",\n");
        sb.append("        tags=[\"review-followup\", \"workstream:<workstream-id>\"]\n");
        sb.append("      )\n\n");
        String wsId = extractWorkstreamId(job.resolveWorkstreamUrl());
        if (wsId != null && !wsId.isEmpty()) {
            sb.append("      Use workstream:").append(wsId)
              .append(" as the second tag value.\n\n");
        }

        sb.append("  (b) Add a TODO comment at the relevant code location ");
        sb.append("(language-appropriate marker):\n\n");
        sb.append("      Java:        // TODO(review): <one-line description>\n");
        sb.append("      Python:      # TODO(review): <one-line description>\n");
        sb.append("      YAML/shell:  # TODO(review): <one-line description>\n");
        sb.append("      JS/TS:       // TODO(review): <one-line description>\n\n");

        sb.append("You may also call memory_recall with tags=[\"review-followup\"] ");
        sb.append("to check what prior review passes on this workstream already ");
        sb.append("flagged, so you do not duplicate notes. ");
        sb.append("workstream_context is also a fine way to see prior notes.\n\n");
    }

    /** Appends the forbidden-actions list. */
    private static void appendForbidden(StringBuilder sb) {
        sb.append("FORBIDDEN ACTIONS\n");
        sb.append("  - No git restore, git checkout --, git reset, git stash, or ");
        sb.append("any git command that reverts or rewrites the working tree.\n");
        sb.append("  - No refactors, renames, or reorganizations.\n");
        sb.append("  - No new dependencies (no pom.xml or requirements.txt edits).\n");
        sb.append("  - No changes to files outside the diff above.\n");
        sb.append("  - No deletions of tests, assertions, or quality gates.\n");
        sb.append("  - No git commits — the harness will commit at session end.\n\n");
    }

    /** Appends the expected-outcome shape statement. */
    private static void appendExpectedOutput(StringBuilder sb) {
        sb.append("EXPECTED OUTCOME\n");
        sb.append("Either:\n");
        sb.append("  (1) Make one or more surgical edits from the allowed list, ");
        sb.append("optionally also filing memories for deeper issues you noticed, ");
        sb.append("and leave a short note in the conversation describing what you ");
        sb.append("changed.\n");
        sb.append("  (2) Make NO edits, file one or more review-followup memories ");
        sb.append("(and optionally add TODO(review) comments), and leave a short ");
        sb.append("note saying no surgical fixes were warranted.\n");
        sb.append("  (3) If you find nothing to address at all, say so explicitly ");
        sb.append("and exit.\n\n");
    }

    /** Appends the unified branch-vs-base diff, truncating per {@link #MAX_DIFF_CHARS}. */
    private static void appendDiff(StringBuilder sb, CodingAgentJob job) {
        String base = job.getBaseBranch() != null ? job.getBaseBranch() : "master";
        String diff = GitOperations.captureBranchDiff(
                job.getWorkingDirectory(), base, job::warn);
        sb.append("DIFF (branch vs base, plus uncommitted working tree)\n");
        if (diff == null || diff.isEmpty()) {
            sb.append("(empty)\n");
            return;
        }
        if (diff.length() <= MAX_DIFF_CHARS) {
            sb.append(diff);
            if (!diff.endsWith("\n")) sb.append('\n');
            return;
        }
        sb.append("[NOTE: diff was ").append(diff.length()).append(" chars, ")
          .append("truncated to first ").append(DIFF_HEAD_CHARS).append(" + last ")
          .append(DIFF_TAIL_CHARS).append(" chars]\n");
        sb.append(diff, 0, DIFF_HEAD_CHARS);
        sb.append("\n\n[... ").append(diff.length() - DIFF_HEAD_CHARS - DIFF_TAIL_CHARS)
          .append(" chars omitted ...]\n\n");
        sb.append(diff, diff.length() - DIFF_TAIL_CHARS, diff.length());
        if (!diff.endsWith("\n")) sb.append('\n');
    }
}
