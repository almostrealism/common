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
 * Enforcement rule that detects new Java methods introduced since the base
 * branch and runs a deduplication session to remove any that duplicate
 * existing functionality. Active when {@link CodingAgentJob#getDeduplicationMode()}
 * is {@link CodingAgentJob#DEDUP_LOCAL}.
 *
 * <p>Uses set-snapshot comparison (inherited from {@link SetComparisonRule}) to
 * determine when to stop looping: after a correction session that produces no
 * change in the new-method set, the rule marks itself resolved and exits.</p>
 *
 * <p>A per-job pass cap ({@link #maxPasses}) provides a cost ceiling independent
 * of the set-comparison exit condition. The cap is enforced via
 * {@link #getMaxRetries()}, which the enforcement framework uses to bound the
 * correction loop. When the cap is reached and duplicates still exist, the
 * framework logs a warning (via {@link CodingAgentJob#warn(String)}) and moves on.
 * The default cap is {@link CodingAgentJob#DEFAULT_MAX_DEDUP_PASSES}.</p>
 *
 * @author Michael Murray
 * @see CodingAgentJob#extractNewMethodNames()
 * @see DeduplicationRule#buildDeduplicationPrompt(List, boolean, int)
 * @see SetComparisonRule
 * @see EnforcementRule
 */
class DeduplicationRule extends SetComparisonRule {

    /** Maximum number of correction sessions allowed per job. */
    private final int maxPasses;

    /** Creates a rule with the default pass cap ({@link CodingAgentJob#DEFAULT_MAX_DEDUP_PASSES}). */
    DeduplicationRule() {
        this(CodingAgentJob.DEFAULT_MAX_DEDUP_PASSES);
    }

    /**
     * Creates a rule with an explicit pass cap.
     *
     * @param maxPasses maximum deduplication sessions before the enforcement
     *                  framework moves on; must be positive
     * @throws IllegalArgumentException if {@code maxPasses} is not positive
     */
    DeduplicationRule(int maxPasses) {
        if (maxPasses <= 0) {
            throw new IllegalArgumentException(
                    "maxPasses must be positive, got: " + maxPasses);
        }
        this.maxPasses = maxPasses;
    }

    @Override
    public String getName() { return "deduplication"; }

    /**
     * Returns the pass cap so the enforcement framework bounds the correction loop.
     * When exhausted while duplicates remain, the framework logs a warning and continues.
     */
    @Override
    public int getMaxRetries() { return maxPasses; }

    @Override
    protected List<String> extractItems(CodingAgentJob job) {
        return job.extractNewMethodNames();
    }

    @Override
    public String buildCorrectionPrompt(CodingAgentJob job) {
        List<String> newMethods = job.extractNewMethodNames();
        List<String> capped = newMethods.size() > DeduplicationSpawner.MAX_DEDUP_METHODS
                ? newMethods.subList(0, DeduplicationSpawner.MAX_DEDUP_METHODS) : newMethods;
        return buildDeduplicationPrompt(capped,
                newMethods.size() > DeduplicationSpawner.MAX_DEDUP_METHODS, newMethods.size());
    }

    /**
     * Builds the aggressive deduplication prompt sent to the follow-up agent session.
     *
     * @param methodNames the (possibly capped) list of new method names
     * @param truncated   {@code true} if the list was capped due to size
     * @param totalCount  the total number of methods found (before capping)
     * @return the full prompt string
     */
    static String buildDeduplicationPrompt(List<String> methodNames,
                                           boolean truncated,
                                           int totalCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("DEDUPLICATION AUDIT — MANDATORY PRE-COMMIT REVIEW\n\n");
        sb.append("This branch has new commits relative to its base branch. Those ");
        sb.append("commits add or substantially modify some Java files, and inside ");
        sb.append("those files they introduce new methods. The methods listed below ");
        sb.append("are the names of those new methods");
        if (truncated) {
            sb.append(" (showing ").append(methodNames.size())
              .append(" of ").append(totalCount).append(" total)");
        }
        sb.append(":\n\n");
        for (String name : methodNames) {
            sb.append("  - ").append(name).append("\n");
        }
        sb.append("\n");
        sb.append("YOUR TASK\n");
        sb.append("Determine whether any of these NEW methods duplicate a method ");
        sb.append("that ALREADY EXISTED in the codebase BEFORE this branch. If you ");
        sb.append("find a real duplicate, remove the new one and call the existing ");
        sb.append("one instead. If you find none, say so explicitly.\n\n");

        sb.append("DEFINITION OF A DUPLICATE\n");
        sb.append("A duplicate exists when ALL of the following are true:\n");
        sb.append("  (a) Another method exists somewhere in the repository that ");
        sb.append("performs the same operation on the same kind of data.\n");
        sb.append("  (b) That other method is defined in a file that was NOT added ");
        sb.append("by this branch and was NOT substantially modified by this branch.\n");
        sb.append("  (c) The two methods are clearly redundant — not just superficially ");
        sb.append("similar.\n\n");
        sb.append("If a method on the list above appears to 'already exist' in a file ");
        sb.append("that THIS branch added or substantially modified, that is NOT a ");
        sb.append("duplicate. That is just the file where the method was introduced. ");
        sb.append("A method cannot be a duplicate of itself.\n\n");

        sb.append("HOW TO TELL WHAT IS NEW ON THIS BRANCH\n");
        sb.append("Before flagging anything, you MUST run these commands and read ");
        sb.append("their output:\n\n");
        sb.append("  git fetch origin\n");
        sb.append("  git diff origin/master...HEAD --name-only   # files changed on this branch\n");
        sb.append("  git log origin/master..HEAD --oneline       # commits on this branch\n\n");
        sb.append("Any file in the `git diff origin/master...HEAD --name-only` output ");
        sb.append("is part of this branch's work. Methods defined there are candidates ");
        sb.append("for the audit, not candidates to be the 'pre-existing duplicate'.\n\n");
        sb.append("To inspect a pre-branch version of a file, use:\n");
        sb.append("  git show origin/master:<path>\n\n");

        sb.append("PROCEDURE FOR EACH METHOD\n");
        sb.append("1. Locate the file on HEAD that defines the method. Confirm the ");
        sb.append("file IS in `git diff origin/master...HEAD --name-only` — if not, ");
        sb.append("this method was not introduced by this branch and should not be ");
        sb.append("on the list; skip it.\n");
        sb.append("2. Search the rest of the repository (Grep by behaviour, not just ");
        sb.append("by name) for a candidate match.\n");
        sb.append("3. For each candidate match, check whether its file appears in ");
        sb.append("`git diff origin/master...HEAD --name-only`. If it does, it is ");
        sb.append("ALSO new on this branch — and unless the two methods are clearly ");
        sb.append("redundant clones of each other added in the same set of commits, ");
        sb.append("they are not a duplicate of each other; skip.\n");
        sb.append("4. If a candidate is in a file that is NOT in that diff list, ");
        sb.append("read both methods and judge whether they truly perform the same ");
        sb.append("operation. If yes, you have a real duplicate.\n\n");

        sb.append("EVIDENCE REQUIRED TO FLAG A DUPLICATE\n");
        sb.append("Before reporting a duplicate, you MUST cite:\n");
        sb.append("  - file:line of the new method on HEAD\n");
        sb.append("  - file:line of the pre-existing candidate\n");
        sb.append("  - one sentence on why they perform the same operation\n");
        sb.append("If you cannot produce all three, you do not have a duplicate. ");
        sb.append("Do not flag anything that you cannot back with these citations.\n\n");

        sb.append("EDITING RULES (only when a real duplicate is proven)\n");
        sb.append("- Use the Edit tool to remove the new method body and its ");
        sb.append("declaration surgically. Preserve every other change in the file.\n");
        sb.append("- Update call sites of the removed method to call the pre-existing ");
        sb.append("one.\n");
        sb.append("- NEVER use git restore, git checkout --, git reset, or any other ");
        sb.append("git command that reverts a file. Those discard all changes in the ");
        sb.append("file, not just the duplicate method, and destroy work that must ");
        sb.append("be preserved.\n\n");

        sb.append("OUTPUT\n");
        sb.append("If you find no duplicates after a thorough audit, say so. ");
        sb.append("Briefly note what you checked (the diff list, the candidates you ");
        sb.append("considered) so a reviewer can see the audit happened. ");
        sb.append("Do not invent duplicates to satisfy the framing of this prompt. ");
        sb.append("A correctly empty result (\"no duplicates found\") is the right ");
        sb.append("answer when the new methods are genuinely new.");
        return sb.toString();
    }
}
