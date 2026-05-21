# REVIEW Phase for Coding-Agent Jobs

## Goal

Introduce a new lifecycle phase — `REVIEW` — that runs between the primary
implementation phase and the rest of the enforcement pipeline (deduplication,
organizational placement, etc.). The phase exists so that a *different*
agent runner (typically a cheaper / faster / locally-served model) can take
a second look at the diff that the primary phase produced, make small
surgical fixes when they are unambiguous, and defer anything substantial
to a memory entry and an in-code TODO rather than risk getting it wrong.

The motivating deployment is:

- `PRIMARY` → Claude Opus (cloud, expensive, strong reasoning)
- `REVIEW`  → opencode against a local Qwen-3 27B-or-similar
  (`AgentRunnerRegistry.OPENCODE`, see
  `flowtree/agents/src/main/java/io/flowtree/jobs/agent/AgentRunnerRegistry.java`)

We get a second-pass sanity check without paying for two Opus runs.

The reviewer's mandate is intentionally restrained: the bias is to **defer**,
not to **edit**. A reviewer that files a useful memory and a useful TODO
comment is a *successful* reviewer even if it changes zero lines.

---

## 1. Where the phase sits in the pipeline

The pipeline order today (built by
`CodingAgentJob.buildActiveRules()` at
`flowtree/runtime/src/main/java/io/flowtree/jobs/CodingAgentJob.java:845`) is:

```
ENFORCE_CHANGES → DEDUPLICATION → ORGANIZATIONAL_PLACEMENT
                → POST_COMPLETION → MAVEN_DEPENDENCY_PROTECTION
                → (custom rules) → COMMIT_MESSAGE
```

The `REVIEW` phase inserts immediately after `EnforceChangesRule` and
immediately before `DeduplicationRule`. The resulting order becomes:

```
ENFORCE_CHANGES → REVIEW → DEDUPLICATION → ORGANIZATIONAL_PLACEMENT
                → POST_COMPLETION → MAVEN_DEPENDENCY_PROTECTION
                → (custom rules) → COMMIT_MESSAGE
```

Reasoning for that position:

- `ENFORCE_CHANGES` must run first because there is nothing to review until
  the primary phase has actually produced changes. If
  `EnforceChangesRule` re-runs the primary prompt and still produces no
  changes, the review pass has no diff to inspect and `isViolated()` will
  return `false`.
- The review pass must run *before* `DEDUPLICATION` and
  `ORGANIZATIONAL_PLACEMENT` so any small surgical fixes it makes are still
  subject to the standard deduplication and placement checks (the existing
  outer `do { … } while (anyRuleCorrectionRan …)` loop in
  `CodingAgentJob.runEnforcementRules()` at
  `flowtree/runtime/src/main/java/io/flowtree/jobs/CodingAgentJob.java:885`
  already re-walks earlier rules when later rules change the working tree).
- `COMMIT_MESSAGE` stays last so that whatever message the reviewer (or any
  other rule) wrote to `commit.txt` is the message that gets used.

### Phase enum entry

Add a new value to `Phase`
(`flowtree/agents/src/main/java/io/flowtree/jobs/agent/Phase.java`):

```java
/** Review session — second-pass sanity check by a different runner. */
REVIEW("review", "Review session — second-pass sanity check by a different runner."),
```

Add the matching entry to `Phase.fromRuleName(String)` so the rule's
`getName()` (`"review"`) maps to `Phase.REVIEW`. The wire name and the
rule name will both be `"review"`, so no special case is needed beyond a
`case "review": return REVIEW;` line.

Place the enum constant between `ENFORCE_CHANGES` and `DEDUPLICATION` so
the declaration order matches the runtime execution order — both
`encodeRunnerMap` and `decodeRunnerMap` iterate in enum declaration order
and the docs (`flowtree/docs/architecture/PHASES.md`) list phases in
declaration order.

---

## 2. The `ReviewRule`

A new top-level class
`flowtree/runtime/src/main/java/io/flowtree/jobs/ReviewRule.java`
implementing `EnforcementRule` (parallel to the other rules in this
package). Package-private, like the others.

```java
class ReviewRule implements EnforcementRule {

    /** Default per-job cap; overridable via {@link CodingAgentJob#setMaxReviewPasses(int)}. */
    static final int DEFAULT_MAX_PASSES = 1;

    private final int maxPasses;

    /** Set to true after the first pass so subsequent isViolated() calls return false. */
    private boolean alreadyRan;

    ReviewRule()                  { this(DEFAULT_MAX_PASSES); }
    ReviewRule(int maxPasses)     { /* validate positive; assign */ }

    @Override public String getName()    { return "review"; }
    @Override public int  getMaxRetries(){ return maxPasses; }

    @Override
    public boolean isViolated(CodingAgentJob job) {
        if (alreadyRan) return false;
        return !job.extractNewFilePaths().isEmpty()
            || job.hasUncommittedChanges();
    }

    @Override
    public String buildCorrectionPrompt(CodingAgentJob job) {
        return ReviewPromptBuilder.build(job);
    }

    @Override
    public void onCorrectionAttempted(CodingAgentJob job) {
        alreadyRan = true;
    }
}
```

Key behaviours, in plain prose:

- **`isViolated()`** — returns `true` exactly when the branch contains
  something the reviewer could look at: either new files relative to the
  base branch (`extractNewFilePaths()`) or uncommitted modifications
  (`hasUncommittedChanges()`). If primary produced no changes the review
  pass is skipped entirely. This mirrors the gating logic in
  `OrganizationalPlacementRule` (which exits when `extractNewFilePaths()`
  is empty) but uses an additive condition so modified-but-not-new files
  also count.
- **`runCorrectionSession()`** is handled by the framework
  (`CodingAgentJob.runCorrectionSession(prompt, ruleName)`); we just
  return a prompt. Setting `currentActivity = "review"` in that helper
  makes `resolveCurrentPhase()` return `Phase.REVIEW`, and
  `resolveRunner(Phase.REVIEW)` then picks up the runner configured for
  this phase through the standard ladder.
- **Single-pass exit condition.** Unlike `DeduplicationRule` /
  `OrganizationalPlacementRule` (which extend `SetComparisonRule` and
  loop until the item set stabilises), `ReviewRule` is one-shot.
  `onCorrectionAttempted()` sets `alreadyRan = true`; the next
  `isViolated()` call short-circuits to `false`. There is no
  "review loops until it produces no changes" behaviour because the
  outer loop in `runEnforcementRules()` already gives dedup, placement,
  etc. a chance to react to anything review changed.
- **Max-passes cap.** Default `1`. Configurable per job via
  `setMaxReviewPasses(int)` and via a new MCP arg
  `max_review_passes` on `workstream_submit_task` (mirrors
  `max_deduplication_passes`). The cap is enforced exactly like
  `DeduplicationRule.maxPasses` and `PostCompletionCommandRule.maxPasses`:
  it is returned from `getMaxRetries()`, and
  `runEnforcementRules()` uses that value to bound the inner `while`.
- **Idempotence.** Because `alreadyRan` is per-rule-instance and the rule
  is built once per `buildActiveRules()` call, a second outer-loop pass
  (triggered when, say, dedup changes the tree) does **not** re-run
  the review pass. This is intentional: the v1 contract is "one review
  pass per job, no matter what subsequent rules do."

### Activation in `CodingAgentJob.buildActiveRules()`

Insert the rule between the `EnforceChangesRule` block and the
`DeduplicationRule` block:

```java
if (enforceChanges) rules.add(new EnforceChangesRule());
if (reviewEnabled)  rules.add(new ReviewRule(maxReviewPasses));   // NEW
if (DEDUP_LOCAL.equals(deduplicationMode))
    rules.add(new DeduplicationRule(maxDeduplicationPasses));
...
```

Default for `reviewEnabled` is `true` (see §4).

---

## 3. The review prompt

This prompt is the heart of the feature. It lives in a new
package-private helper class
`flowtree/runtime/src/main/java/io/flowtree/jobs/ReviewPromptBuilder.java`
(parallel to `InstructionPromptBuilder`). It must:

1. **State the role clearly.** "You are reviewing changes made by another
   agent. You did not write this code. Your job is to catch obvious
   problems before the rest of the pipeline runs."
2. **Provide the diff** — the actual file changes the primary phase
   produced, captured by running `git diff origin/<baseBranch>...HEAD`
   plus `git diff` for any uncommitted-but-staged work in the working
   tree. Cap at, say, 60 000 chars; if the diff is larger, include a
   file-by-file summary plus the first ~30 000 chars and the last
   ~30 000 chars, and tell the reviewer the diff was truncated.
3. **Provide brief task context.**
   - The original task prompt (`job.getPrompt()`)
   - The branch name (`getTargetBranch()`) and the base branch
     (`getBaseBranch()`)
   - The workstream URL (`resolveWorkstreamUrl()`) so the reviewer can
     `memory_store` and `workstream_context` calls land on the right
     workstream
4. **List "do directly" categories** (limit the reviewer to genuinely
   trivial fixes):
   - Typos in strings, comments, or javadoc
   - Obviously-missing imports
   - Trivial null checks or guard clauses (one-line, unambiguous)
   - One-line bug fixes where the fix is unambiguous (e.g. an
     off-by-one whose correctness can be read straight off the diff)
   - A missing JUnit test that exercises a single newly-introduced
     method (one new test method, in an existing test class)
5. **List "defer" categories** (catch-all bias: when unsure, defer):
   - Anything that touches more than a handful of lines
   - Anything that crosses multiple files
   - Architectural / design concerns
   - Performance concerns
   - Style or naming concerns
   - Anything that requires reading code outside the diff to evaluate
   - Anything where the reviewer is not certain about the right fix
6. **Make the bias explicit.** A paragraph that says, in so many words:
   *"When in doubt, defer. A reviewer that defers correctly is doing
   their job. A reviewer that makes a wrong edit is worse than one that
   makes no edit."*
7. **Show how to defer.** Two concrete examples, copy-paste ready:

   - `memory_store` call:

     ```text
     memory_store
       namespace="default"
       tags=["review-followup", "workstream:<workstream-id>"]
       content="Reviewer note (file:line): <one-paragraph description of
                the issue and the suggested approach>"
     ```

   - In-code TODO comment:

     ```java
     // TODO(review): <one-line description of the issue and suggestion>
     ```

     ```python
     # TODO(review): <one-line description of the issue and suggestion>
     ```

8. **Forbid heavy actions.** No `git restore`, no `git checkout --`, no
   `git reset`, no rewriting of pre-existing code, no refactors, no
   renames, no adding dependencies. The reviewer touches only files in
   the diff, and only with surgical edits, OR adds a TODO comment, OR
   adds a single new test.
9. **State the expected output shape.** The reviewer either:
   - Made one or more surgical edits + (optionally) added memories +
     left a short note in the conversation describing what was done, or
   - Made no edits + filed one or more memories + (optionally) added
     `TODO(review)` comments + left a short note saying no surgical
     fixes were warranted.
10. **Pull in prior `review-followup` memories from the same
    workstream/branch.** Before building the prompt, query the
    memory store for the most recent 5–10 memories tagged
    `review-followup` on this workstream and inline them in a "Prior
    review notes" section so the reviewer does not re-flag the same
    things across runs. (See §8 open questions for the discussion of
    why this is recommended.)

Concretely:

```
ReviewPromptBuilder.build(job)
  ├── header (role + bias statement)
  ├── original task prompt (truncated to 2 000 chars)
  ├── branch context (base, target, workstream URL)
  ├── prior review notes (pulled via memory_recall, up to 10)
  ├── "DO directly" list
  ├── "DEFER" list
  ├── "How to defer" — memory_store and TODO comment templates
  ├── forbidden actions
  ├── expected output shape
  └── the diff (truncated as described)
```

The builder is package-private so the rule can call it; expose a
`static String build(CodingAgentJob job)` entry point for tests.

---

## 4. Configuration surface

### Java side

On `CodingAgentJob`, add three fields and matching setters/getters:

```java
private boolean reviewEnabled = true;
private int     maxReviewPasses = ReviewRule.DEFAULT_MAX_PASSES;

public boolean isReviewEnabled()      { return reviewEnabled; }
public void setReviewEnabled(boolean v){ this.reviewEnabled = v; }

public int  getMaxReviewPasses()      { return maxReviewPasses; }
public void setMaxReviewPasses(int v) { /* validate > 0 */ }
```

The factory (`CodingAgentJobFactory`) gets identical fields propagated
to every job it produces (mirrors `maxDeduplicationPasses` /
`deduplicationMode`).

`encode()` / `set(String,String)` round-trip:

- `::reviewEnabled:=false` is emitted only when the flag differs from
  the default (`true`) — matches the encoding pattern for
  `enforceOrganizationalPlacement`.
- `::maxReviewPasses:=N` is emitted only when N differs from
  `ReviewRule.DEFAULT_MAX_PASSES`.

### MCP side

In `tools/mcp/manager/server.py`, the `workstream_submit_task` tool
gains:

```python
max_review_passes: int = 0     # 0 means "use server default"
review_enabled: bool = True
```

Their wire mapping mirrors `max_deduplication_passes`:

```python
if max_review_passes > 0:
    payload["maxReviewPasses"] = max_review_passes
if not review_enabled:
    payload["reviewEnabled"] = False
```

The same two parameters should also be plumbed through the workstream-
config tools (`workstream_register`, `workstream_update_config`) and the
workspace-config tools (`workspace_update_config`) so the defaults can
be set durably.

### Runner routing

Routing comes for free from the existing
`getRunnerForPhase(Phase.REVIEW)` lookup; the precedence ladder
documented in `flowtree/docs/architecture/PHASES.md` applies as-is. To
route the review phase to opencode permanently for a workstream:

```json
{ "runners": { "review": "opencode" } }
```

### "Skip the phase" convention

Look at the existing convention used in this codebase before inventing
one. `OrganizationalPlacementRule` is disabled via a boolean
(`setEnforceOrganizationalPlacement(false)`); the runners map does
**not** support a sentinel like `"none"` —
`AgentRunnerRegistry.validateName(...)` rejects any unregistered name
(`flowtree/agents/src/main/java/io/flowtree/jobs/agent/AgentRunnerRegistry.java:109`).
We follow the same convention: `setReviewEnabled(false)` (per-job,
per-factory, or via MCP) is the documented way to skip the phase. We
do **not** add a `"none"` runner.

If we later want a `"none"` runner that means "skip this phase," that
is a separate cross-cutting change to the registry and is explicitly
out of scope for this plan.

---

## 5. Output handling

- Surgical edits made during the review session land in the working
  tree alongside the primary work. The harness commits everything in
  one commit at session end — there is no separate "review commit."
  This matches how `DeduplicationRule` and
  `OrganizationalPlacementRule` already operate.
- Memories stored during the review session are tagged
  `review-followup` (the standard tag). Optionally a second tag of the
  form `workstream:<id>` lets future `workstream_context` calls filter
  to a single workstream cleanly. The agent is given both tag
  templates in the prompt; a workstream-context query for
  `tags=["review-followup"]` returns the deferred items the next
  primary-phase session should consider.
- `TODO(review):` comments live in the code and survive PR review like
  any other comment.
- `commit.txt` snapshot / restore is handled by the existing logic in
  `CodingAgentJob.runCorrectionSession()`
  (`flowtree/runtime/src/main/java/io/flowtree/jobs/CodingAgentJob.java:978`)
  — if the reviewer happens to write a `commit.txt` we restore the
  primary's message only when the reviewer did not write one.

---

## 6. Telemetry

The existing per-phase telemetry (`runnerByPhase`, `runnerStats`,
`costUsd`, `numTurns`, `durationMs`, `durationApiMs`,
`permissionDenials`, all rolled up in `CodingAgentJob.absorbResult` at
`CodingAgentJob.java:1207`) is generic over phase, so the `REVIEW`
phase is captured automatically — verify in the integration test
(§7) that `runnerByPhase` contains a `"review"` entry with the
runner that actually executed it.

Add three new fields to `CodingAgentJobEvent` for the review phase
specifically:

```java
private int     reviewFilesModified;   // # of files touched during REVIEW phase
private int     reviewMemoriesStored;  // # of memory_store calls during REVIEW phase
private boolean reviewExitedCleanly;   // exitCode == 0 from the review session
```

Counts are computed as follows:

- `reviewFilesModified` — diff `getWorkingDirectory()` immediately
  before and immediately after the review session and count files
  whose status differs. Snapshot is captured in
  `CodingAgentJob.runCorrectionSession()` when the `activity` argument
  equals `"review"`.
- `reviewMemoriesStored` — count of `memory_store` invocations during
  the review session. The MCP `send_message` tool already records
  `activity` per call (see `AR_AGENT_ACTIVITY` plumbing); add the same
  per-activity counter for `memory_store` on the manager side, or
  scrape the count from the agent's NDJSON output (which already
  contains tool-use events) inside `absorbResult` / `AgentRunResult`.
  Pick whichever is simpler; both surfaces already exist.
- `reviewExitedCleanly` — whether the review session's `exitCode`
  was 0.

Populate the event in
`CodingAgentJob.populateEventDetails(JobCompletionEvent event)` at
`CodingAgentJob.java:1263`, alongside the existing
`withPostCompletionCapHit(...)` call.

---

## 7. Tests

Place new tests in `flowtree/runtime/src/test/java/io/flowtree/jobs/`
alongside the existing `CodingAgentJobEnforcementTest`,
`McpToolDiscoveryTest`, etc. All tests extend `TestSuiteBase` per
project convention.

### Unit tests for `ReviewRule`

1. **`isViolated_returnsTrueWithNewFiles`** — feeds the rule a job
   whose `extractNewFilePaths()` returns a non-empty list and asserts
   the rule reports a violation.
2. **`isViolated_returnsTrueWithUncommittedModifications`** —
   `extractNewFilePaths()` empty but `hasUncommittedChanges()` true.
3. **`isViolated_returnsFalseOnCleanTree`** — both empty → no violation.
4. **`isViolated_returnsFalseAfterFirstRun`** — after one
   `onCorrectionAttempted()` call, `isViolated()` returns `false`
   regardless of subsequent state. This is the single-pass contract.
5. **`maxRetries_respectsDefault`** — fresh rule reports
   `getMaxRetries() == 1`.
6. **`maxRetries_respectsConstructorOverride`** — `new ReviewRule(3)`
   reports `3`.
7. **`maxRetries_rejectsNonPositive`** — constructor throws on `0` and
   negative values.
8. **`getName_returnsReview`** — the rule's name is `"review"`, the
   wire name in `Phase`.

### Phase serialization round-trip

Extend the existing `PhaseTest` (or add `PhaseReviewTest`):

- `Phase.fromWireName("review") == Phase.REVIEW`
- `Phase.fromRuleName("review") == Phase.REVIEW`
- `Phase.encodeRunnerMap(...)` includes `review=opencode` when set
- `Phase.decodeRunnerMap(...)` round-trips `review=opencode`

### `CodingAgentJob` activation tests

In `CodingAgentJobEnforcementTest`:

- **`buildActiveRules_includesReviewByDefault`** — assert the active
  rule list contains a `ReviewRule` in the expected position
  (after `EnforceChangesRule`, before `DeduplicationRule` when both
  are present).
- **`buildActiveRules_excludesReviewWhenDisabled`** — after
  `setReviewEnabled(false)`, no `ReviewRule` in the list.
- **`reviewPhase_routesToConfiguredRunner`** — set
  `setRunnerForPhase(Phase.REVIEW, "opencode")` and assert
  `resolveRunner(Phase.REVIEW)` returns the opencode runner.
- **`reviewPhase_inheritsDefaultRunner`** — with no per-phase override,
  `resolveRunner(Phase.REVIEW)` returns the default runner.

### Wire-format tests

In `CodingAgentJobEnforcementTest` or a sibling:

- **`encode_omitsReviewEnabledWhenTrue`** — default `true` → field
  absent from `encode()` output.
- **`encode_emitsReviewEnabledWhenFalse`** — `false` →
  `::reviewEnabled:=false` present.
- **`encode_emitsMaxReviewPassesWhenOverridden`** — set to `3` →
  `::maxReviewPasses:=3` present; default → absent.
- **`set_parsesReviewEnabledAndMaxReviewPasses`** — `set("reviewEnabled",
  "false")` and `set("maxReviewPasses", "3")` populate the fields.

### Workstream / workspace runner-config propagation

Extend `SubmissionRunnerResolverTest` (or its sibling):

- **`reviewRunner_resolvesFromWorkstreamConfig`** — workstream has
  `runners: {"review": "opencode"}`, no job override → resolved
  runner is `opencode`.
- **`reviewRunner_jobOverrideBeatsWorkstream`** — job sets
  `runners: {"review": "claude"}`; workstream sets opencode → job
  wins.
- **`reviewRunner_fallsBackToWorkspaceDefault`** — only workspace
  defines `runners: {"review": "opencode"}` → that wins.

### Prompt-builder integration test

In a new `ReviewPromptBuilderTest`:

- Construct a fake `CodingAgentJob` with a stubbed diff.
- Assert the resulting prompt contains:
  - The role-and-bias header
  - Both "DO directly" and "DEFER" sections
  - The `memory_store` / `TODO(review):` templates
  - The diff text
  - Prior review-followup memories (when the memory recall hook is
    stubbed to return them)
- Assert the prompt size is within reason (e.g. < 200 KB) and that
  large diffs are truncated with a marker.

### End-to-end behaviour: dedup re-run after review edits

In `CodingAgentJobEnforcementTest`:

- **`reviewMakingChangesTriggersDedupRecheck`** — wire a fake review
  runner that injects an edit into a Java file; assert that after the
  review session, the `do { … } while (anyRuleCorrectionRan)` outer
  loop walks the dedup and placement rules again. This validates the
  position of `REVIEW` in the pipeline.

### Telemetry tests

- **`reviewPhaseRecordedInRunnerByPhase`** — assert the
  `CodingAgentJobEvent` produced by a job that ran a review session
  has a `runnerByPhase` entry keyed by `"review"`.
- **`reviewFilesModifiedCountIsAccurate`** — fake review runner
  modifies two files → `reviewFilesModified == 2`.

---

## 8. Open questions — confirm before implementation

1. **Default behaviour: enabled-by-default vs. opt-in.**
   **Recommendation: enabled-by-default.** The "bias toward deferring"
   built into the prompt makes the cost-and-risk of a default-on
   review pass low: a reviewer that finds nothing actionable spends
   one cheap session and exits. Opt-out is one boolean
   (`setReviewEnabled(false)`). Operators who don't want it pay only
   the first time. Confirm before implementation.

2. **Memory tag convention for deferred items.**
   **Recommendation: `["review-followup", "workstream:<id>"]`.**
   `review-followup` is the cross-workstream filter; the
   `workstream:<id>` tag lets `workstream_context` scope to a
   single workstream cleanly. Both tags are emitted by the reviewer
   per the prompt templates in §3. Pick exactly this pair so we can
   build filtering on top of it later without a migration. Confirm
   the exact strings before shipping.

3. **Should the reviewer be able to signal "this work looks bad enough
   that the primary phase should retry"?**
   **Recommendation: no, for v1.** Adding a "primary should retry"
   exit code requires a new outer-loop construct (the existing loop
   only re-walks subsequent rules, not earlier ones) and risks
   getting stuck in primary↔review loops. The v1 contract is:
   review either edits, or defers. Anything stronger than that
   waits for a future iteration.

4. **Should the reviewer see prior review memories on the same
   workstream/branch?**
   **Recommendation: yes** — see §3 step 10. The reviewer pulls the
   most recent 5–10 `review-followup` memories for the current
   workstream and inlines them in the prompt. Two reasons: (a) it
   prevents the reviewer from re-flagging the same items every run,
   and (b) it gives the reviewer context about issues that have
   already been intentionally deferred (so it knows not to "fix" them
   on this pass either). The recall happens inside
   `ReviewPromptBuilder.build()` via the same MCP `memory_recall`
   tool the agent itself can call.

5. **Diff size cap.** §3 step 2 proposes a 60 000-char cap with
   head/tail-and-summary truncation. The actual right number depends
   on the budget of the cheaper runner (opencode + Qwen 27B has a
   smaller usable context than Claude Opus). Confirm a number before
   implementation, ideally one that matches the
   `MAX_OUTPUT_CHARS = 8 000` pattern used by
   `PostCompletionCommandRule` even if it ends up being larger.

---

## 9. Out of scope

- Multi-round review loops with back-and-forth between primary and
  review phases.
- Auto-creating GitHub issues from deferred memories. (A future
  manager-side scheduled job could do that against the
  `review-followup` namespace; not this plan.)
- Specialized reviewer prompts per file type (Java vs Python vs YAML).
  One prompt for everything; the reviewer is told to read the diff.
- Severity grading (info / warning / error) on deferred items.
- A `"none"` sentinel runner that means "skip this phase." We use the
  `setReviewEnabled(false)` boolean instead, matching the convention
  already established by `setEnforceOrganizationalPlacement(false)`.
- Reworking `Phase.fromRuleName(...)` precedence — adding a single
  case is sufficient; no consolidation required.

---

## 10. Documentation updates

The following docs need to be updated as part of the implementation
(do not skip — these are the canonical references operators read):

- `flowtree/docs/architecture/PHASES.md` — add the `REVIEW` row to the
  phase table, mention it in the "DEDUPLICATION and local-model
  routing" discussion (the same argument applies to REVIEW), and
  update the "wire serialization" example if it grows a
  `review=opencode` entry.
- `flowtree/runtime/src/main/java/io/flowtree/jobs/CodingAgentJob.java`
  class-level javadoc — list `ReviewRule` in the built-in-rules
  paragraph.
- `tools/mcp/manager/server.py` — docstrings for the
  `workstream_submit_task`, `workstream_register`,
  `workstream_update_config`, and `workspace_update_config` tools all
  need to mention `max_review_passes` and `review_enabled`.
- A short "Review phase" subsection in the README of
  `flowtree/runtime` (or wherever the built-in rules are catalogued)
  explaining what the phase does and how to disable it.

No changes to `.github/workflows/analysis.yaml` are needed — the
phase runs inside the agent job process and does not interact with
CI.

---

## 11. Implementation order (suggested)

1. `Phase.REVIEW` enum value + `Phase.fromRuleName(...)` case + unit
   tests.
2. `ReviewRule` skeleton (no prompt yet; returns a stub string) +
   unit tests for `isViolated()` / `getMaxRetries()` /
   single-pass exit.
3. `CodingAgentJob.reviewEnabled` + `maxReviewPasses` fields,
   getters/setters, `buildActiveRules()` insertion, wire-format
   round-trip + tests.
4. `CodingAgentJobFactory` propagation + tests.
5. `ReviewPromptBuilder` with all of §3 + `ReviewPromptBuilderTest`.
6. MCP plumbing (`workstream_submit_task` and friends) + Python tests
   in `tools/mcp/manager/test_server.py`.
7. Telemetry fields on `CodingAgentJobEvent` + `populateEventDetails`
   wiring + tests.
8. Documentation updates (§10).
9. End-to-end smoke: submit a real job with `runners:
   {"review": "opencode"}` against a small test branch and visually
   inspect the produced prompt, the memories stored, and the
   resulting commit.
