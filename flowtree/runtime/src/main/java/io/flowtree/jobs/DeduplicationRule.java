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

        sb.append("THE GOAL\n");
        sb.append("This audit exists to IMPROVE CODE REUSE around the work being done ");
        sb.append("on this branch. The goal is NOT to police newly-added duplication. ");
        sb.append("The goal is to LEAVE THE CODEBASE WITH LESS DUPLICATION than you ");
        sb.append("found it — in the area you are working in. \"Net change in ");
        sb.append("duplication is zero\" is not a passing result. If a method you are ");
        sb.append("creating, moving, or modifying has equivalent copies elsewhere ");
        sb.append("(even in files you didn't otherwise touch, even small ones, even ");
        sb.append("ones that pre-date your branch), consolidating them IS in scope. ");
        sb.append("You are in the area now. Fix it.\n\n");

        sb.append("YOUR TASK\n");
        sb.append("For each NEW method below, find every equivalent copy elsewhere in ");
        sb.append("the codebase that is RELATED to the work this branch is doing ");
        sb.append("(definition of \"related\" below). If you find equivalents, ");
        sb.append("consolidate them: move the implementation to a shared location ");
        sb.append("accessible to every call site, update every caller to use it, and ");
        sb.append("describe in your summary what you consolidated. If a new method ");
        sb.append("has no related equivalents anywhere, say so explicitly.\n\n");

        sb.append("WHAT COUNTS AS A DUPLICATE\n");
        sb.append("Two methods are duplicates when they perform the same operation on ");
        sb.append("the same kind of data, regardless of:\n");
        sb.append("  - Whether the other copy was added by this branch or pre-dated it. ");
        sb.append("Pre-existing copies are still duplicates. The fact that the ");
        sb.append("duplication existed before your branch is NOT a reason to skip ");
        sb.append("consolidation. You are touching this area now; fix it now.\n");
        sb.append("  - How short the method is. Detector thresholds (e.g. the ");
        sb.append("`duplicate_code` linter's 10-line floor) are for automated flagging ");
        sb.append("of unambiguous cases. They are a FLOOR, not a CEILING. During this ");
        sb.append("audit, if you SEE a 3-line method existing in four places, that is ");
        sb.append("in scope to consolidate. \"Below the detector threshold\" is not a ");
        sb.append("valid rationale here.\n");
        sb.append("  - Whether the branch's net duplication count would stay the same. ");
        sb.append("\"My branch did not ADD new cross-file duplication\" is not the bar. ");
        sb.append("The bar is whether the area you touched is LESS duplicated than ");
        sb.append("when you started.\n\n");
        sb.append("A method cannot be a duplicate of itself. If a new method appears ");
        sb.append("to \"already exist\" only because grep found its own definition in ");
        sb.append("the file your branch introduced it in, that is not a duplicate.\n\n");

        sb.append("WHAT \"RELATED TO THIS WORK\" MEANS (the scope boundary)\n");
        sb.append("To prevent this audit from turning into a global cleanup mission, ");
        sb.append("consolidate ONLY duplication that is RELATED to the work this ");
        sb.append("branch is doing. A duplicate is related when ANY of the following ");
        sb.append("is true:\n");
        sb.append("  - You are creating, moving, modifying, or extracting this method ");
        sb.append("on this branch (it is on the list above, or you are about to ");
        sb.append("relocate an existing copy of it). Equivalents anywhere in the ");
        sb.append("repository are in scope.\n");
        sb.append("  - The other copy lives in the same package, an adjacent package, ");
        sb.append("or the same module/area of functionality as a file your branch ");
        sb.append("modified.\n");
        sb.append("  - The other copy is in a class that calls into, or is called by, ");
        sb.append("code your branch modified.\n");
        sb.append("Duplication that is NOT related — different module, no relationship ");
        sb.append("to your work, just something you happened to notice — is OUT of ");
        sb.append("scope. Do not chase it here. Leave it for a separate cleanup pass.\n\n");

        sb.append("WORKED EXAMPLE — THE RIGHT ANSWER\n");
        sb.append("Suppose this branch is extracting a `truncate(String, int)` helper ");
        sb.append("from `FooHandler` into `BarHandler`. While auditing, you notice ");
        sb.append("that `BazListener` and `QuxNotifier` already contain identical ");
        sb.append("copies of `truncate(String, int)`, and those copies pre-date this ");
        sb.append("branch. The right answer is to:\n");
        sb.append("  (a) Place the helper in a shared utility location accessible to ");
        sb.append("all four call sites (a common base class, a shared helper class in ");
        sb.append("a lower-level package, or an existing utility class — whatever the ");
        sb.append("organizational hierarchy supports).\n");
        sb.append("  (b) Update all four classes (`FooHandler`/`BarHandler`, ");
        sb.append("`BazListener`, `QuxNotifier`) to call the shared implementation.\n");
        sb.append("  (c) State in your summary that you consolidated four pre-existing ");
        sb.append("copies into one shared helper.\n");
        sb.append("The WRONG answers — all of which leave the duplication in place — ");
        sb.append("are: \"the other three copies pre-date the branch,\" \"the branch's ");
        sb.append("net cross-file duplicate count did not increase,\" or \"the method ");
        sb.append("body is only 3 lines, below the detector threshold.\" None of those ");
        sb.append("are valid rationales for skipping consolidation when you are ");
        sb.append("already touching one of the copies.\n\n");

        sb.append("WORKED EXAMPLE — OUT OF SCOPE\n");
        sb.append("Suppose this branch is extracting `truncate(String, int)` from ");
        sb.append("`FooHandler` and you also notice that `AudioBufferReader` and ");
        sb.append("`MidiFileParser` (different module, unrelated to your work) share ");
        sb.append("some unrelated utility method. That is NOT in scope for this audit. ");
        sb.append("The audit consolidates duplication that touches or is touched by ");
        sb.append("your work — not every duplication you happen to discover.\n\n");

        sb.append("HOW TO TELL WHAT IS NEW ON THIS BRANCH\n");
        sb.append("Before consolidating anything, run these commands and read the ");
        sb.append("output so you know which files your branch is responsible for:\n\n");
        sb.append("  git fetch origin\n");
        sb.append("  git diff origin/master...HEAD --name-only   # files changed on this branch\n");
        sb.append("  git log origin/master..HEAD --oneline       # commits on this branch\n\n");
        sb.append("Files in that diff are the work this audit is about. Methods you ");
        sb.append("are introducing, moving, or modifying inside those files are the ");
        sb.append("audit subjects. Equivalent copies elsewhere — whether in branch-");
        sb.append("modified files or in untouched files that are RELATED — are in ");
        sb.append("scope to consolidate.\n\n");
        sb.append("To inspect a pre-branch version of a file, use:\n");
        sb.append("  git show origin/master:<path>\n\n");

        sb.append("PROCEDURE FOR EACH METHOD\n");
        sb.append("1. Locate the file on HEAD that defines the method. Confirm the ");
        sb.append("file IS in `git diff origin/master...HEAD --name-only` — if not, ");
        sb.append("the method was not introduced by this branch and should not be on ");
        sb.append("the list; skip it.\n");
        sb.append("2. Search the rest of the repository (Grep by behaviour, not just ");
        sb.append("by name) for every equivalent copy. Do NOT stop at the first hit; ");
        sb.append("find all of them.\n");
        sb.append("3. For each equivalent copy, decide whether it is RELATED (same/");
        sb.append("adjacent package, same area of functionality, or in a class that ");
        sb.append("interacts with the code your branch modified). If related, it is ");
        sb.append("in scope to consolidate. If unrelated, leave it alone and note ");
        sb.append("that you did so.\n");
        sb.append("4. For every in-scope set of equivalent copies (the new method ");
        sb.append("plus its related equivalents), consolidate into one shared ");
        sb.append("implementation. Place it where every call site can reach it without ");
        sb.append("introducing a circular dependency, update every caller, and verify ");
        sb.append("the project still compiles.\n\n");

        sb.append("EVIDENCE REQUIRED WHEN YOU CONSOLIDATE\n");
        sb.append("When you report a consolidation, cite:\n");
        sb.append("  - file:line of every copy you found (the new one plus each ");
        sb.append("equivalent)\n");
        sb.append("  - the shared location you moved the implementation to\n");
        sb.append("  - one sentence on why the copies perform the same operation\n");
        sb.append("When you report that an in-scope candidate was NOT a duplicate ");
        sb.append("after closer reading, cite the file:line of both methods and one ");
        sb.append("sentence on the behavioural difference that distinguishes them.\n\n");

        sb.append("EDITING RULES\n");
        sb.append("- Use the Edit tool to relocate or remove method bodies surgically. ");
        sb.append("Preserve every other change in every file.\n");
        sb.append("- Update every call site to reference the consolidated implementation.\n");
        sb.append("- NEVER use git restore, git checkout --, git reset, or any other ");
        sb.append("git command that reverts a file. Those discard all changes in the ");
        sb.append("file, not just the duplicate method, and destroy work that must be ");
        sb.append("preserved.\n");
        sb.append("- Do NOT create a new Maven module to hold the shared helper. If no ");
        sb.append("suitable existing location exists, place the helper on the most ");
        sb.append("specific common ancestor type or in an existing utility class in a ");
        sb.append("lower-level package shared by the call sites.\n\n");

        sb.append("OUTPUT\n");
        sb.append("If you consolidated duplication, describe each consolidation: the ");
        sb.append("copies that were merged, where the shared implementation now lives, ");
        sb.append("and the call sites that were updated. If after a thorough audit ");
        sb.append("you found no in-scope duplication to consolidate, say so explicitly ");
        sb.append("and briefly list the candidates you considered and ruled out (with ");
        sb.append("the reason). Do not invent consolidations to satisfy the framing ");
        sb.append("of this prompt — but do not skip real ones by appealing to ");
        sb.append("\"pre-existing,\" \"below the threshold,\" or \"net change is zero.\" ");
        sb.append("Those rationales are explicitly rejected.");
        return sb.toString();
    }
}
