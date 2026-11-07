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
import java.util.List;
import java.util.Set;

/**
 * Enforcement rule that holds an auto-review / auto-resolve pass to the rule
 * that it may not finish while a reviewer finding on the pull request is still
 * unaccounted for. A finding is accounted for when it is fixed, when a reply is
 * posted after its latest comment explaining why no change is made, or when a
 * reply cites a tracker task filed for it; resolving threads is left to people.
 *
 * <p>The worker that runs the pass has no GitHub access — that lives in the
 * controller and in CI — so the pass's own agent is the party that both reads
 * the PR's threads (through its {@code github_pr_*} tools) and answers them. It
 * records the findings it could <em>not</em> account for in the scratch ledger
 * {@link #LEDGER}, one per line, and clears a line as it answers it. This rule
 * reads that ledger: while it names a finding, the rule is violated, so the
 * enforcement loop runs a correction session that answers the rest, and
 * {@link CodingAgentJobEvent} reports the pass as {@code DEGRADED} rather than
 * {@code SUCCESS} if any remain when the loop gives up. The ledger lives under
 * the {@code .flowtree/} scratch directory, which is never committed.</p>
 *
 * <p>This is the worker-side half of the rule; CI is the backstop. Each later
 * CI run (and the review-feedback trigger) re-reads the PR's threads from
 * GitHub and dispatches another pass while anything stands, so a pass that
 * answers nothing and writes no ledger — the case this rule cannot see — is
 * still caught there.</p>
 *
 * @author Michael Murray
 * @see CodingAgentJobEvent
 */
class OutstandingFeedbackRule implements EnforcementRule {

    /**
     * Scratch-space ledger of reviewer findings the pass has not yet accounted
     * for, one per line. Under {@code .flowtree/}, so it is never committed.
     */
    static final String LEDGER = ".flowtree/outstanding-feedback.txt";

    @Override
    public String getName() { return "outstanding-feedback"; }

    @Override
    public boolean isViolated(CodingAgentJob job) {
        return hasUnaccountedFeedback(job.getWorkingDirectory());
    }

    @Override
    public String buildCorrectionPrompt(CodingAgentJob job) {
        String findings = readLedger(job.getWorkingDirectory());
        return "Reviewer feedback on this pull request is not yet accounted for. "
            + "The following findings remain unanswered (from " + LEDGER + "):\n\n"
            + findings + "\n\n"
            + "For each one, do exactly one of: fix it; or reply to its thread after "
            + "its latest comment explaining why no change is made; or file a follow-up "
            + "task with tracker_create_task and reply citing the task id. Do not resolve "
            + "threads — that is a person's decision; only reply. As you account for each "
            + "finding, remove its line from " + LEDGER + ". Leave the file empty when every "
            + "finding is accounted for.";
    }

    @Override
    public Set<String> getProgressPaths() { return Set.of(LEDGER); }

    /**
     * Returns whether the scratch ledger at {@link #LEDGER} names a reviewer
     * finding not yet accounted for — any non-blank line. A missing or
     * unreadable ledger reads as nothing outstanding, so a pass that recorded
     * no unanswered finding is not held.
     *
     * @param workingDirectory the job's working directory, or {@code null}
     * @return {@code true} when a finding remains unaccounted for
     */
    static boolean hasUnaccountedFeedback(String workingDirectory) {
        return !readLedger(workingDirectory).isEmpty();
    }

    /**
     * Returns the non-blank lines of the scratch ledger, joined by newlines, or
     * an empty string when the directory is unset, the file is absent, or it
     * cannot be read.
     *
     * @param workingDirectory the job's working directory, or {@code null}
     * @return the outstanding findings, one per line
     */
    private static String readLedger(String workingDirectory) {
        if (workingDirectory == null) return "";
        Path ledger = Path.of(workingDirectory, LEDGER);
        if (!Files.isRegularFile(ledger)) return "";
        try {
            List<String> lines = Files.readAllLines(ledger, StandardCharsets.UTF_8);
            StringBuilder kept = new StringBuilder();
            for (String line : lines) {
                if (!line.isBlank()) {
                    if (kept.length() > 0) kept.append('\n');
                    kept.append(line.strip());
                }
            }
            return kept.toString();
        } catch (IOException e) {
            return "";
        }
    }
}
