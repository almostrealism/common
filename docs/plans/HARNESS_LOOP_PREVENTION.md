# Harness Loop Prevention and Agent Ergonomics

## Problem statement

On 2026-10-03, a FlowTree coding job on `project/plan-20260930-180401` was asked to
merge `origin/master` (578a49330) into the branch. The merge was simple: one Java conflict
(`HardwareMemoryProvider`) that the agent resolved correctly in its first session. Then the
harness ran **more than a dozen consecutive agent sessions** in about 11 minutes. Each one
re-verified the same facts, wrote the same "needs a human, stop resubmitting" memory, and
exited with no changes. The memories are all in the `bugs` namespace on that branch, tagged
`ci-file-lock` / `needs-human`. The merge commit `448e349b6` landed at the end anyway, and it
contains master's CI files, which shows that the guardrail driving the loop was not actually
protecting anything.

The sequence:

1. `GitRepositorySetup` left the merge in progress (MERGE_HEAD present). The index already
   held master's versions of `tools/ci/docker/Dockerfile` and
   `tools/ci/prompts/build-resolve-prompt.sh`. The branch had never touched those files.
2. `FileStager.evaluateFiles` (`flowtree/runtime/.../jobs/FileStager.java`, the
   `ciLocked` computation and `isCiWorkflowFile`) rejected both files as
   "protected - CI/workflow file". It never looks at the file *content*, so it cannot tell
   "the agent edited a CI file" from "the merge brought in master's CI file byte-for-byte".
3. `StagingSkipRule` and `EnforceChangesRule` both evaluate `previewStaging()`, which runs
   the same merge-unaware FileStager. They kept reporting a violation. The agent was told its
   changes "will NOT be committed" and that it must "produce code changes".
4. No agent action could satisfy the rules. The only file-level way to make them pass was to
   restore the branch's old CI files, which would silently revert master's CI changes inside
   the merge commit. Every agent session correctly refused to do that.
5. `EnforcementRunner` does not retire a rule that has exhausted its retries unless it hit
   the entry ceiling or has a fallback. So the violated rules re-entered on every outer pass
   until the global caps (`DEFAULT_MAX_TOTAL_ENFORCEMENT_ATTEMPTS = 25`,
   `RestartGovernor.DEFAULT_MAX_TOTAL_SESSIONS = 30`) stopped it.
6. When the job finished, `GitCommitHandler`'s merge path committed whatever was in the
   index (`diff --cached` was non-empty). That included the "rejected" CI files. The skip list
   in the completion event still reported them as skipped, which was false.

Every session spent its whole budget on "is this still impossible?". The agent had no way to
answer "yes, and here is the proof" in a form the harness could act on.

The same period surfaced several smaller harness problems that cost turns. They are included
below because they share a root cause: guardrails that cannot tell the situation they guard
against from a harmless situation that looks the same on the surface.

## Goals

- A job that cannot make progress must end quickly, with the reason intact, and must never
  spend 10+ sessions confirming the same impossibility.
- Guardrails must judge **what the agent did**, not the raw state of the working tree.
  Content that came from a merge parent is not an agent edit.
- Retry budgets must depend on the type of failure and the realistic chance that another
  attempt will succeed.
- Test-execution limits must stop hours-long runs without forcing 80 separate 16-second
  invocations for one coverage pass.
- Hooks must not block legitimate edits because of a parsing shortcut.

## Non-goals

- Weakening any enforcement: the CI-file lock, the test-integrity detectors, the per-job
  test lock, or the test-execution caps. Every change below makes a guardrail *more precise*,
  not more permissive. An agent edit to a CI file on a non-`ci/` branch is still rejected.
  Running a whole module or a CI shard is still refused.
- Changing the merge strategy or the ci/ branch exemption semantics.

---

## Work item 1: Merge-aware staging (the root cause)

**Files:** `flowtree/runtime/src/main/java/io/flowtree/jobs/FileStager.java`,
`GitCommitHandler.java` (merge path), `GitManagedJob.previewStaging`,
`TestMethodProtection.java`, `flowtree/base/.../GitOperations.java`.

1. When `GitOperations.isMergeInProgress()` is true, classify each changed path against both
   parents: `HEAD` and `MERGE_HEAD`.
   - If the working-tree/index blob **equals the MERGE_HEAD blob** and the path was not
     modified by the branch since the merge-base, the change is *merge-carried*. It is not an
     agent edit. FileStager must not apply the CI-file lock, the sensitive-file guard, or the
     whole-file test protection to it.
   - If the blob differs from both parents, it is an agent edit (conflict resolution or
     otherwise), and every existing guard applies unchanged.
   - If the blob equals HEAD (the agent kept the branch's version over master's), that is a
     potential silent revert of master content. For CI files, keep rejecting it and report it
     explicitly as "would revert base-branch CI change".
2. `TestMethodProtection` uses `git merge-base origin/<base> HEAD`. During a merge it should
   also compare against MERGE_HEAD, so master's own changes to protected test methods are not
   flagged as branch edits.
3. `previewStaging()` (used by `EnforceChangesRule` and `StagingSkipRule`) must use the same
   merge-aware classification. The preview and the real staging must never disagree.
4. Fix the misleading skip list: a file that ends up in the merge commit must not appear in
   `skippedFiles` / the completion event as skipped.
5. Security check: merge-carried exemption must be keyed on blob identity with the *fetched*
   merge parent recorded by the harness when it started the merge, not on whatever
   `.git/MERGE_HEAD` says at commit time. Otherwise an agent could write its own MERGE_HEAD to
   launder a CI edit. `GitRepositorySetup` already knows which ref it merged. It should
   record the SHA in job state, and FileStager should compare against that.

**Tests:** FileStager unit tests for each of the three classifications. Include a CI file
identical to the recorded merge parent (accepted), a CI file the agent edited during the merge
(rejected), and a CI file reverted to HEAD (rejected with the new reason). Add a test that a
forged `.git/MERGE_HEAD` does not exempt anything.

## Work item 2: A first-class "cannot proceed" exit for agents

There is currently **no** machine-readable way for an agent to say "this cannot be done by an
agent; stop retrying". `StagingSkipRule` asks the agent to "say explicitly in your final
message that a human needs to intervene", and `InstructionPromptBuilder` asks it to "ABANDON
the task and report it as impossible". Nothing parses either one, and `EnforceChangesRule`
explicitly forbids "no problem found".

1. Add a structured bail signal. Preferred form is an ar-manager MCP tool, for example
   `job_declare_blocked(reason, category, evidence)`, bound to the job by the existing HMAC
   temp token. A sentinel file such as `.flowtree/blocked.json` is the fallback, for runners
   without MCP. Categories should be a small closed set: `harness_guardrail`,
   `needs_human_decision`, `missing_tool_or_permission`, `external_dependency`,
   `task_impossible`.
2. When a session declares itself blocked, `EnforcementRunner` stops. It runs no further
   PRIMARY restarts and no correction sessions for rules whose violations are covered by the
   declared category. The job ends in a distinct terminal status, `BLOCKED` (not FAILED, not
   DEGRADED), which surfaces the reason and evidence in Slack and in the workstream messages.
3. Abuse resistance. Declaring blocked must not become a way to skip work:
   - `task_impossible` and `needs_human_decision` end the job but are flagged prominently,
     and they count against the workstream's auto-dispatch (no automatic resubmission).
   - `harness_guardrail` must name the guardrail and the paths. The harness re-runs that
     guardrail's own check to confirm the claim, so an agent cannot claim a guardrail fired
     when it did not.
   - The test-integrity and CI-lock rules still apply to whatever the agent did change.
4. Prompt text that currently says "say in your final message..." or "ABANDON..." should
   point at the new tool instead.

## Work item 3: Failure-aware retry calibration

**Files:** `EnforcementRunner.java`, `EnforceChangesRule.java`, `StagingSkipRule.java`,
`RestartGovernor.java`, `CodingAgentJob` constants.

Today every rule gets a fixed retry count (default 5, a few at 2), and an exhausted rule
re-enters on every outer pass until the total cap of 25. Retry budgets should reflect whether
another attempt can change the outcome.

1. **Classify each violation** as one of:
   - *agent-correctable*: the agent's own output is wrong (missing commit.txt, bad commit
     language, policy violation in its code, review findings).
   - *environment-determined*: the outcome depends on tree/harness state the agent did not
     create and cannot legitimately change (merge-carried content, a pre-existing base-branch
     condition, a missing tool).
   - *ambiguous*.
   Rules should report the class along with the violation. FileStager already knows which
   paths are merge-carried (item 1) and which pre-date the job.
2. **No-progress detection.** Fingerprint each violation (rule name plus sorted affected
   paths plus a hash of the relevant content). If the same fingerprint repeats after a session
   in which the agent made **no tree changes**, that session made no progress. Two consecutive
   no-progress sessions on the same fingerprint retire the rule for the job and end it as
   `BLOCKED` with category `harness_guardrail`. This caps the 2026-10-03 scenario at about 3
   sessions even before item 1 lands.
3. **Environment-determined violations get zero corrective retries.** Report them and end.
4. **Retire exhausted rules by default.** Re-entry of an exhausted rule should require
   evidence that the condition changed (a different fingerprint), not just a new outer pass.
5. **EnforceChangesRule** must not fire when the only reason nothing is staged is that the
   preview skipped files (that is `StagingSkipRule`'s job), or when a merge in progress
   already produced a non-empty `diff --cached`. Today the two rules double-count the same
   condition.
6. Log the retry decision and its reason in the completion event, so the workstream timeline
   shows "rule X retired after 2 no-progress sessions", not just "exhausted".

## Work item 4: Correct change detection

**File:** `flowtree/base/.../GitOperations.java` (`findChangedFiles` / porcelain parsing),
`JobWorkOutcome.java`, `CommitMessageRule.java`.

1. Porcelain parsing currently does not tell staged from unstaged, and treats every changed
   path the same. Return a structured record per path: index status, worktree status, and
   (during a merge) the merge classification from item 1.
2. "Agent produced changes" must be computed relative to the job's **starting state**, not
   relative to HEAD. A merge that the harness itself started has changes on disk before the
   agent does anything. Those are the harness's changes, and they must not count as agent
   output. Equally, a session that resolves a merge to exactly HEAD has produced a valid result
   (an empty merge commit) and must not trigger "no changes" retries.
3. `JobWorkOutcome` FAILED for "authored commit.txt but nothing reached the tree" must take
   merge state into account. In the merge case the commit *does* reach the tree.
4. Add tests for: merge with all content carried from MERGE_HEAD; merge resolved identical to
   HEAD; merge with an agent conflict resolution; a normal (non-merge) job with only an
   untracked new file.

## Work item 5: Reconcile test-execution limits across every surface

The repository rule (CLAUDE.md, `InstructionPromptBuilder`, `execution_limits.py`,
`PostCompletionCommandValidator`, the ar-test-runner `run_validation.py`) is now **at most 5
classes and 40 named methods per invocation**. Several surfaces still say *one test per
invocation*, and agents comply with the strictest text they see:

- The job prompt delivered to this session (2026-10-03, task 7d7d25f0) contained
  "Run at most ONE test per invocation ... never a bare `-Dtest=Class`". That text no longer
  exists anywhere in the repository source, which means the deployed runner is **older than
  the repository's prompt builder**. Prompt-rule changes are not reaching agents until the
  runner is redeployed.
- `tools/ci/prompts/coverage.txt` ("one `{class, method}` entry per invocation ... a bare
  class selector is rejected"), which is false now.
- `tools/ci/prompts/build-vm-crash-prompt.sh` ("A bare class name is never accepted ... one
  test at a time").
- Python: `execution_limits.py` and `PostCompletionCommandValidator` still require one pytest
  node id per invocation.

Tasks:

1. Update the stale prompt files to the 5-class / 40-method rule, using the exact wording
   from `InstructionPromptBuilder` so there is one source of truth. Better still, generate the
   paragraph from the constants (`MAX_TEST_CLASSES`, `MAX_TEST_METHODS`) so prompt and
   validator cannot drift.
2. Stamp the prompt-builder version (git SHA of the runner build) into the job prompt header
   and the job's completion event. Have the controller warn when a runner's SHA is behind the
   controller's expected version for prompt-affecting changes.
3. Python: allow a bounded list of explicit node ids (for example up to 40 node ids, from at
   most 5 files, no `-k` expressions, no directories, no bare files). This mirrors the Java
   rule.
4. A regression test that greps every prompt template under `tools/ci/prompts/` and the
   `InstructionPromptBuilder` output for "one test per invocation" style wording, and fails if
   it reappears without matching the validator's actual caps.

## Work item 6: Bounded multi-method runs for new test classes

During one coverage round, an agent made **80+ separate ar-test-runner invocations of about
16 seconds each**, one method per run, because its prompt required it. Most of that time is
JVM and Maven startup, not test time. Even with the 40-method cap, it is a common case to
verify a **new test class the branch itself introduced**.

1. Allow the ar-test-runner (and `execution_limits.py` / `PostCompletionCommandValidator`)
   to run a whole class **when the class does not exist on the base branch**. That is a
   bounded, known set of methods the agent just wrote, and it cannot be "the suite". Determine
   this with `git cat-file -e origin/<base>:<path>`. The 5-class cap still applies.
2. Alternatively, or in addition, ar-test-runner can expand a bare new class into its
   explicit `Class#method` list (parse `@Test` methods). It then enforces the 40-method cap
   on the expansion, so the existing cap semantics are preserved exactly.
3. Report per-method pass/fail in one structured result, so the agent does not need separate
   runs to attribute failures.

## Work item 7: Fix the warn-test-deception tolerance parser

**File:** `.claude/hooks/warn-test-deception.sh`, check 5 ("assertEquals tolerance
widening").

The `tols()` helper takes **the last numeric literal anywhere in the argument list** of every
`assertEquals` / `assertArrayEquals` call. Observed false positive: an edit that *only added
new test methods* to `ScaleTraversalStrategyTest` was **blocked** with "assertEquals tolerance
widened (max 1.0 -> 1024.0)". The trigger was
`Assert.assertEquals("message", 1024, intActual)`, an integer equality with no tolerance at
all. The agent had to move the new tests into a separate class to get past the hook. Other
inputs that produce false tolerances: digits inside identifiers (`layer2Output` gives 2),
digits inside string literals (`"step 5"`), and any two-argument integer assert.

Fix:

1. Strip string literals and comments before parsing. Split the argument list on top-level
   commas (respecting parentheses and generics).
2. Treat an argument as a tolerance only when it is the **final argument of a 3-argument
   `(expected, actual, delta)` call or a 4-argument `(message, expected, actual, delta)`
   call**, and it is a numeric literal (or a simple `double`/`float` constant). In the
   3-argument case, disambiguate `(message, expected, actual)` by checking whether the first
   argument is a string literal.
3. Match numeric literals with word boundaries so digits inside identifiers are ignored.
4. Compare tolerances **per assertion that exists in both old and new text** (matched by its
   expected/actual expressions), not `max(old)` against `max(new)` across the whole hunk. A
   newly added assertion with any tolerance is a new check, not a widened one.
5. Add hook self-tests (the hooks directory already has a `lib/` and Python helpers): the
   ScaleTraversalStrategyTest case, `layer2Output`, string-embedded digits, a genuine 1e-6 to
   1e-2 widening (must still block), and a newly added delta assertion (must not block).

This is a precision fix. A real widening of an existing assertion's delta still blocks.

## Work item 8: Smaller items found along the way

1. **Loop visibility.** The workstream timeline should make a loop obvious: N sessions,
   same rule, same paths, zero tree changes. `workstream_context` could add a
   `repeated_violation` summary when the same fingerprint (item 3) appears in 3 or more
   sessions.
2. **Memory spam from loops.** Each looping session stored a near-duplicate "needs a human"
   memory (more than 12 on one branch in 11 minutes). Once item 2 exists, a blocked job should
   produce one memory. ar-manager could also coalesce near-identical memories written to the
   same branch within a short window.
3. **Plan document drift.** `docs/plans/PLAN-20260930-self-hosted-tiny-lm.md` still describes
   the one-test-per-invocation rule. Plans are not prompts, so this is low priority, but
   agents read it as instructions.

## Suggested order

1. Item 3.2 (no-progress fingerprint) first. It is small, self-contained, and limits the
   damage of *every* future impossible-guardrail loop, not just the merge one.
2. Item 7 (hook parser). Small and independent.
3. Item 1 together with item 4 (merge-aware staging and change detection). They share the
   classification code.
4. Item 2 (bail tool) together with the remainder of item 3.
5. Items 5 and 6 (test limits and runner-version stamping).
6. Item 8.

## Verification

- Unit tests per item as listed above. FileStager, EnforcementRunner and JobWorkOutcome
  already have test classes in `flowtree/runtime/src/test/java/io/flowtree/jobs/`.
- A scripted reproduction of the 2026-10-03 scenario: a fixture repo with a base-branch CI
  file change, a branch with an unrelated conflict, and a job with sensitive-file protection
  on. Assert that the job completes in one session, the merge commit contains the base CI
  content, and the skip list is empty.
- The same fixture with an agent edit to a CI file during the merge. Assert that it is still
  rejected (no weakening).
