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

import java.util.ArrayList;
import java.util.List;

/**
 * Enforcement rule that runs a deduplication session over the new code on a
 * branch: the new top-level types that resemble existing types, always, and the
 * new Java methods introduced since the base branch when
 * {@link CodingAgentJob#getDeduplicationMode()} is
 * {@link CodingAgentJob#DEDUP_LOCAL}.
 *
 * <p>The type audit runs for every job, whatever its deduplication mode:
 * {@link EnforcementRunner} adds this rule to every job, with the method audit
 * switched on only for {@link CodingAgentJob#DEDUP_LOCAL}. The types come from
 * {@link NewTypeAudit#unaudited}: those the type-equivalence search found to
 * resemble an existing type and that do not extend, implement or delegate to
 * it. The session examines each against its candidates, the documentation and
 * memory search, and the session's own justification in
 * {@value NewTypeAudit#LEDGER_PATH}, and folds the type into the existing
 * abstraction unless the justification actually holds. Once the session has
 * run, {@link NewTypeAudit#markAudited} records the types it was given, which
 * is what lets {@link FileStager} stage a type the session kept.</p>
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
 * @see DeduplicationRule#buildDeduplicationPrompt(List, boolean, int, String)
 * @see SetComparisonRule
 * @see EnforcementRule
 */
class DeduplicationRule extends SetComparisonRule {

    /** Prefix distinguishing a new-type item from a method item in the tracked set. */
    static final String TYPE_ITEM_PREFIX = "type ";

    /** Maximum number of correction sessions allowed per job. */
    private final int maxPasses;

    /** Whether new methods are audited as well as new types. */
    private final boolean auditMethods;

    /** The types given to the session in progress; recorded as audited when it ends. */
    private List<NewTypeAssessment> pendingTypes = List.of();

    /** Creates a rule with the default pass cap ({@link CodingAgentJob#DEFAULT_MAX_DEDUP_PASSES}). */
    DeduplicationRule() {
        this(CodingAgentJob.DEFAULT_MAX_DEDUP_PASSES);
    }

    /**
     * Creates a rule with an explicit pass cap that audits both new methods
     * and new types.
     *
     * @param maxPasses maximum deduplication sessions before the enforcement
     *                  framework moves on; must be positive
     * @throws IllegalArgumentException if {@code maxPasses} is not positive
     */
    DeduplicationRule(int maxPasses) {
        this(maxPasses, true);
    }

    /**
     * Creates a rule with an explicit pass cap.
     *
     * @param maxPasses    maximum deduplication sessions before the enforcement
     *                     framework moves on; must be positive
     * @param auditMethods {@code true} to audit new methods as well as new
     *                     types; new types are always audited
     * @throws IllegalArgumentException if {@code maxPasses} is not positive
     */
    DeduplicationRule(int maxPasses, boolean auditMethods) {
        if (maxPasses <= 0) {
            throw new IllegalArgumentException(
                    "maxPasses must be positive, got: " + maxPasses);
        }
        this.maxPasses = maxPasses;
        this.auditMethods = auditMethods;
    }

    /** Returns whether this rule audits new methods as well as new types. */
    boolean isAuditingMethods() { return auditMethods; }

    @Override
    public String getName() { return "deduplication"; }

    /**
     * Returns the pass cap so the enforcement framework bounds the correction loop.
     * When exhausted while duplicates remain, the framework logs a warning and continues.
     */
    @Override
    public int getMaxRetries() { return maxPasses; }

    /**
     * Returns the new methods (when the method audit is on) and, prefixed with
     * {@link #TYPE_ITEM_PREFIX}, the new types the type audit has still to
     * examine.
     */
    @Override
    protected List<String> extractItems(CodingAgentJob job) {
        List<String> items = new ArrayList<>(auditMethods ? job.extractNewMethodNames() : List.of());
        for (NewTypeAssessment type : job.getNewTypeAudit().unaudited()) {
            items.add(TYPE_ITEM_PREFIX + type.getFqn());
        }
        return items;
    }

    @Override
    public String buildCorrectionPrompt(CodingAgentJob job) {
        pendingTypes = job.getNewTypeAudit().unaudited();
        List<String> newMethods = auditMethods ? job.extractNewMethodNames() : List.of();
        String typePrompt = pendingTypes.isEmpty() ? null
                : buildTypeAuditPrompt(pendingTypes, job.getBaseBranch());
        if (newMethods.isEmpty()) {
            return typePrompt != null ? typePrompt
                    : "DEDUPLICATION AUDIT\n\nNothing remains to audit: no new methods or new "
                    + "types resembling existing ones. Make no changes.";
        }
        List<String> capped = newMethods.size() > DeduplicationSpawner.MAX_DEDUP_METHODS
                ? newMethods.subList(0, DeduplicationSpawner.MAX_DEDUP_METHODS) : newMethods;
        String methodPrompt = buildDeduplicationPrompt(capped,
                newMethods.size() > DeduplicationSpawner.MAX_DEDUP_METHODS,
                newMethods.size(),
                job.getBaseBranch());
        return typePrompt == null ? methodPrompt : typePrompt + "\n\n" + methodPrompt;
    }

    /**
     * Applies the set comparison, then records the types the session was given
     * as audited, so that {@link NewTypeAudit#refusals} can release the ones it
     * kept and {@link #extractItems} does not hand them out again.
     */
    @Override
    public void onCorrectionAttempted(CodingAgentJob job) {
        super.onCorrectionAttempted(job);
        job.getNewTypeAudit().markAudited(pendingTypes);
        pendingTypes = List.of();
    }

    /**
     * Builds the aggressive deduplication prompt sent to the follow-up agent session.
     *
     * @param methodNames the (possibly capped) list of new method names
     * @param truncated   {@code true} if the list was capped due to size
     * @param totalCount  the total number of methods found (before capping)
     * @param baseBranch  the configured base branch (e.g. {@code "master"},
     *                    {@code "main"}); {@code null} or blank falls back to
     *                    {@code "master"}. Must match the ref used by
     *                    {@link CodingAgentJob#extractNewMethodNames()} so the
     *                    diff/log commands the prompt cites resolve the same
     *                    method set that produced {@code methodNames}.
     * @return the full prompt string
     */
    static String buildDeduplicationPrompt(List<String> methodNames,
                                           boolean truncated,
                                           int totalCount,
                                           String baseBranch) {
        String baseRef = "origin/"
                + (baseBranch == null || baseBranch.isBlank() ? "master" : baseBranch);
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
        sb.append("  git diff ").append(baseRef).append("...HEAD --name-only   # files changed on this branch\n");
        sb.append("  git log ").append(baseRef).append("..HEAD --oneline       # commits on this branch\n\n");
        sb.append("Files in that diff are the work this audit is about. Methods you ");
        sb.append("are introducing, moving, or modifying inside those files are the ");
        sb.append("audit subjects. Equivalent copies elsewhere — whether in branch-");
        sb.append("modified files or in untouched files that are RELATED — are in ");
        sb.append("scope to consolidate.\n\n");
        sb.append("To inspect a pre-branch version of a file, use:\n");
        sb.append("  git show ").append(baseRef).append(":<path>\n\n");

        sb.append("PROCEDURE FOR EACH METHOD\n");
        sb.append("1. Locate the file on HEAD that defines the method. Confirm the ");
        sb.append("file IS in `git diff ").append(baseRef).append("...HEAD --name-only` — if not, ");
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

    /**
     * Builds the type-audit prompt: for each new type that resembles existing
     * types, the session decides whether it should exist at all, treating the
     * ledger justification as a claim to verify, and folds the type into the
     * existing abstraction unless the justification holds.
     *
     * @param types      the new types to audit, each with its candidates
     * @param baseBranch the configured base branch; {@code null} or blank
     *                   falls back to {@code "master"}
     * @return the full prompt string
     */
    static String buildTypeAuditPrompt(List<NewTypeAssessment> types, String baseBranch) {
        String baseRef = "origin/"
                + (baseBranch == null || baseBranch.isBlank() ? "master" : baseBranch);
        StringBuilder sb = new StringBuilder();
        sb.append("NEW TYPE AUDIT -- SHOULD EACH OF THESE TYPES EXIST?\n\n");
        sb.append("This branch adds new top-level Java types that resemble types the ");
        sb.append("repository already has. You did not write them, and you are not here ");
        sb.append("to check whether they are correct. You are here to decide whether each ");
        sb.append("one should exist at all.\n\n");

        sb.append("THE RULE\n");
        sb.append("An agent must not add a type that re-implements a concept the codebase ");
        sb.append("already has. The problem is solved with the existing abstraction, ");
        sb.append("improving it if necessary. A second implementation of an existing ");
        sb.append("concept is a defect however correct it is.\n\n");

        sb.append("THE TYPES AND THEIR CANDIDATES\n");
        for (NewTypeAssessment type : types) {
            sb.append(type.describeWithJustification());
        }
        sb.append('\n');

        sb.append("SEARCH BEYOND THE CANDIDATE LIST\n");
        sb.append("The candidates come from a lexical search: shared name words, javadoc ");
        sb.append("overlap, shared methods and supertypes, package locality. It misses ");
        sb.append("equivalents that use different words. For each new type, also:\n");
        sb.append("  - call mcp__ar-manager__consult with the concept the type represents ");
        sb.append("(what it IS, in plain words), not its name;\n");
        sb.append("  - call mcp__ar-docs__search_ar_docs and mcp__ar-manager__memory_recall ");
        sb.append("with the same concept;\n");
        sb.append("  - Grep for the behaviour the type implements, not just for its name.\n");
        sb.append("Any equivalent you find this way is a candidate too.\n\n");

        sb.append("THE REMEDIES, IN ORDER -- take the first that works\n");
        sb.append("  1. Use the candidate as it is.\n");
        sb.append("  2. Change the signature or caller that made a new type seem necessary. ");
        sb.append("Find every caller (Grep); when the only callers are code on this branch, ");
        sb.append("the signature can change to the existing type.\n");
        sb.append("  3. Extend or improve the candidate -- add the method, constructor or ");
        sb.append("capability it lacks -- or introduce an interface the candidate implements ");
        sb.append("and code against that interface.\n");
        sb.append("  4. Only then, keep the new type.\n\n");

        sb.append("THESE ARE NOT REASONS TO KEEP A TYPE\n");
        sb.append("Signatures and hierarchies can be changed, so none of these hold:\n");
        sb.append("  - \"the existing type does not implement interface X\" -- make it implement ");
        sb.append("X, or introduce an interface both satisfy;\n");
        sb.append("  - \"the method signature requires Y\" -- change the signature;\n");
        sb.append("  - \"the existing type cannot be used as-is, so nothing can be done\" -- ");
        sb.append("improving the existing type is the expected remedy, and reverting the whole ");
        sb.append("change is not one;\n");
        sb.append("  - a renamed copy -- renaming a type does not make it a new concept.\n\n");

        sb.append("VERIFY THE JUSTIFICATION\n");
        sb.append("Treat each ledger reason as a claim. Read the candidate's source and ");
        sb.append("check it: can the candidate be extended, or the capability added to it? ");
        sb.append("Who calls the signature that seemed to need the new type -- can it change? ");
        sb.append("Would an interface the candidate implements serve? Does the named test ");
        sb.append("exist, and does it actually demonstrate a behavioural distinction the ");
        sb.append("candidate cannot provide? A reason you cannot confirm from the code does ");
        sb.append("not hold.\n\n");

        sb.append("WHAT TO DO\n");
        sb.append("For each type, either:\n");
        sb.append("  (a) FOLD IT IN: move whatever the new type adds onto the existing ");
        sb.append("abstraction (remedies 1-3), update every caller, delete the new type and ");
        sb.append("its entry in ").append(NewTypeAudit.LEDGER_PATH).append(", and run the ");
        sb.append("tests that exercise the callers; or\n");
        sb.append("  (b) KEEP IT, only when the justification holds for EVERY candidate after ");
        sb.append("you verified it. Then record your verdict in the type's ledger entry: ");
        sb.append("\"audit\": {\"verdict\": \"").append(NewTypeAssessment.VERDICT_KEPT);
        sb.append("\", \"reason\": \"<what you verified>\"}.\n");
        sb.append("The harness refuses to commit a type this audit listed until it is folded ");
        sb.append("in or carries that verdict. A verdict written before this audit ran does ");
        sb.append("not count.\n\n");

        sb.append("To see what the branch changed: git diff ").append(baseRef);
        sb.append("...HEAD --name-only, and git status for uncommitted work.\n\n");

        sb.append("EDITING RULES\n");
        sb.append("- Use the Edit tool. Preserve every other change in every file.\n");
        sb.append("- NEVER use git restore, git checkout --, git reset or any command that ");
        sb.append("reverts a file; folding a type in is an edit, not a revert of the branch.\n");
        sb.append("- Do not create a new Maven module.\n\n");

        sb.append("OUTPUT\n");
        sb.append("For each type: folded in (where its behaviour went, which callers changed) ");
        sb.append("or kept (each candidate, and the evidence that each justification holds).");
        return sb.toString();
    }
}
