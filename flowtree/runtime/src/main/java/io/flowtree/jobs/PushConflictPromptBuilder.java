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
 * Builds the focused prompt for the conflict-resolution session that
 * {@link CodingAgentJob#onPushConflict(String, List)} runs when a completion-time
 * push reconciliation merge leaves conflict markers.
 *
 * <p>The mandate is deliberately narrow: resolve the listed conflict markers and
 * nothing else. The merge has already been started by {@link GitPushReconciler};
 * the agent only edits the conflicted files in place, and the reconciler makes
 * the merge commit afterward. This is the opposite of a primary session — there
 * is no new feature work to do and no commit for the agent to make.</p>
 *
 * <p>Package-private; tests use {@link #build(CodingAgentJob, String, List)}.</p>
 *
 * @author Michael Murray
 * @see GitPushReconciler
 * @see CodingAgentJob#onPushConflict(String, List)
 */
final class PushConflictPromptBuilder {

    /** Static-only helper. */
    private PushConflictPromptBuilder() {}

    /**
     * Builds the conflict-resolution prompt.
     *
     * @param job             the job whose push is being reconciled
     * @param repoPath        repository containing the conflicted files
     * @param conflictedFiles paths, relative to {@code repoPath}, left unmerged
     * @return the prompt string sent to the conflict-resolution session
     */
    static String build(CodingAgentJob job, String repoPath, List<String> conflictedFiles) {
        StringBuilder sb = new StringBuilder();

        sb.append("MERGE CONFLICT RESOLUTION\n\n");
        sb.append("A push of branch '").append(job.getTargetBranch()).append("' was rejected ");
        sb.append("because the remote advanced while this job was running. The remote ");
        sb.append("changes have already been merged into the local branch, and the merge ");
        sb.append("is in progress with conflict markers. Your ONLY job is to resolve those ");
        sb.append("conflict markers so the merge can be committed and the push retried.\n\n");

        sb.append("REPOSITORY\n");
        sb.append("  ").append(repoPath).append("\n");
        if (job.getWorkingDirectory() != null
                && !repoPath.equals(job.getWorkingDirectory())) {
            sb.append("  (this is a dependent repository, NOT your primary working directory; ");
            sb.append("operate on the files at the path above)\n");
        }
        sb.append('\n');

        sb.append("CONFLICTED FILES (resolve every one)\n");
        if (conflictedFiles.isEmpty()) {
            sb.append("  (none reported — run `git status` in the repository above to find them)\n");
        } else {
            for (String file : conflictedFiles) {
                sb.append("  ").append(file).append('\n');
            }
        }
        sb.append('\n');

        sb.append("HOW TO RESOLVE\n");
        sb.append("  1. Open each conflicted file and reconcile the regions between the ");
        sb.append("<<<<<<<, =======, and >>>>>>> markers, keeping BOTH sides' intent where ");
        sb.append("they are independent additions (these are typically append-mostly scratch ");
        sb.append("files, so the usual resolution is to keep all entries from both sides).\n");
        sb.append("  2. Remove every conflict marker. Leave the file syntactically valid.\n");
        sb.append("  3. Do not invent content that was not on either side of the conflict.\n\n");

        sb.append("FORBIDDEN ACTIONS\n");
        sb.append("  - Do NOT run git commit, git merge, git merge --abort, git reset, ");
        sb.append("git checkout --, git restore, or git stash. The harness owns all git ");
        sb.append("operations and will commit the merge once you finish.\n");
        sb.append("  - Do NOT edit files that are not in the conflicted list above.\n");
        sb.append("  - Do NOT start new feature work or redo the original task.\n");
        sb.append("  - Do NOT add or remove dependencies.\n\n");

        sb.append("EXPECTED OUTCOME\n");
        sb.append("Every listed file has its conflict markers removed and a coherent merged ");
        sb.append("result, with no other changes. When done, leave a one-line note describing ");
        sb.append("how you resolved each file and exit.\n");

        return sb.toString();
    }
}
