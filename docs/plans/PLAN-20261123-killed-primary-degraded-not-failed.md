---
status: proposed
created: 2026-11-23
closed:
tracker: [d4c94654-16d7-41a0-acf4-9019d996bfd9]
---

# Grade a signal-killed primary by what it delivered, not by a self-reported 0s

**Tracker task:** d4c94654-16d7-41a0-acf4-9019d996bfd9 — *"A primary session
killed after doing its work is reported as '0s, no work performed' and fails a
job that then committed verified work"*

**Release:** Common 0.78

## Motivation

On 2026-11-05 a primary agent session ran for ~44 minutes, left a complete
change in the working tree, and was then killed by **SIGTERM (exit 143)**. The
harness recorded `PRIMARY complete — failed (exit 143) in 0s` and the job error
became *"Primary phase hard-failed (non-zero exit, 0s duration, no work
performed)."* Both statements were false: the session had worked and the later
review phase verified the change (`SiluMlpAssetTest` 7/7) and committed and
pushed it. The job was nonetheless marked **FAILED**, so its PR was never
auto-created, the owner was told the work had failed, and the PR had to be
opened by hand. The primary session's cost was never recorded.

The harness already has the right instinct elsewhere — a transient git failure
is reported `DEGRADED`, not `FAILED`, and the working-tree rescue preserves a
finished tree a failing job produced. This task extends the same principle to a
killed-but-productive primary: **grade the job by what was delivered.**

## Root cause (verified against `master`)

The failure is a chain of three defects, each independently load-bearing:

1. **Duration is self-reported, so a killed session reports zero.**
   `ClaudeCodeRunner.parseClaudeNdjson` (`flowtree/agents`) reads
   `AgentRunResult.durationMs` from the `duration_ms` field of the Claude CLI's
   *final* result JSON line. A subprocess killed by a signal never emits that
   line, so `durationMs` stays `0` even after 44 minutes.
   `AgentProcessRunner.Result` does not measure the process's own wall-clock, so
   nothing corrects the zero.

2. **The "hard failure" predicate reads that zero as "no work performed."**
   `CodingAgentJob.isHardPrimaryFailure()` is

   ```java
   accumulator.getExitCode() != 0
       && accumulator.getDurationMs() == 0L
       && !restartGovernor.wasKilledForInactivity();
   ```

   A SIGTERM kill satisfies all three (143 ≠ 0; 0 ms; the inactivity watchdog
   did not fire — the signal came from elsewhere), so the primary is flagged a
   hard failure. The predicate cannot tell a session that *crashed instantly*
   from one that *ran for 44 minutes and was killed*, because both report 0 ms.
   It also reads the **accumulated** duration, which sums across inactivity
   restarts, so even a measured duration would need to be the final attempt's.

3. **The grading turns that flag into FAILED, and FAILED blocks the PR.**
   `CodingAgentJobEvent.statusFor()` returns
   `failed("Primary phase hard-failed (non-zero exit, 0s duration, no work
   performed)")` whenever `isPrimaryPhaseHardFailed()` is set and the final exit
   code is 0 (the review phase's clean exit). Then
   `FlowTreeApiEndpoint.handleStatus` runs `autoPrPublisher.publish(event)`
   **only when the status is `SUCCESS`**; every other status calls `discard`.
   So even if the status were corrected to `DEGRADED`, no PR would open today.

A further observation constrains the "who killed it" work (fix item 2 in the
task): the harness's *own* kill paths use `Process.destroyForcibly()`, which
sends `SIGKILL` (exit 137). An exit of **143 (SIGTERM)** is therefore not a
harness-initiated kill — it is external (container stop, cgroup OOM, node
restart, or a supervising CLI timeout). Separately, `AgentProcessRunner.Result`
carries a `killedForLooping` flag that `OpencodeRunner` propagates but
`ClaudeCodeRunner` silently drops, so a loop-detector kill under Claude is today
indistinguishable from an external one.

## Scope

In `flowtree/agents` and `flowtree/runtime`, make the harness report a killed
primary truthfully and grade the job by what it delivered:

1. **Measure the primary phase's real wall-clock duration** so a killed session
   no longer reports `0s`, and use it wherever the self-reported `duration_ms`
   is absent.
2. **Classify how the primary ended** — ran-and-killed-by-signal (143/137)
   vs. could-not-start (launch failure) vs. inactivity/loop kill vs. clean — and
   record a named kill cause on the completion event / job record.
3. **Redefine the hard-failure predicate** to mean *no usable work was
   produced*, so a signal kill after real runtime is no longer "hard."
4. **Grade a killed-but-productive primary `DEGRADED`, not `FAILED`**, when the
   later phases verified and committed its work, and let the normal
   `autoCreatePr` path run for that `DEGRADED` outcome.
5. **Record the killed session's cost**, marking it incomplete when the runner
   died before emitting a cost.
6. **Refactor for room**: both `CodingAgentJob.java` (1589 lines) and
   `FlowTreeApiEndpoint.java` (1597 lines) sit at the 1600-line Checkstyle
   `FileLength` cap, so extracting a cohesive collaborator is part of this
   change, not a follow-up (per `flowtree/CLAUDE.md`).

**Out of scope / explicitly not widening into:** detecting OOM or node-restart
causes from outside the JVM (cgroup/host inspection) beyond what the exit code
and existing flags already tell us — see open questions; changing the
inactivity or loop watchdogs themselves; any change to the `SUCCESS` path or to
the existing `DEGRADED` reasons.

## Approach

### 1. Honest duration (`flowtree/agents`)

- `AgentProcessRunner.runAttempt(...)` already holds the process from
  `start()` to `waitFor()`/kill. Capture a monotonic start (`System.nanoTime()`)
  and compute elapsed on exit, and add it to `AgentProcessRunner.Result` (e.g.
  `long wallClockMs`). This is the one place that observes the true lifetime of
  a process that was killed before it could report its own.
- `ClaudeCodeRunner.parseClaudeNdjson` (and the `OpencodeRunner` equivalent)
  take the measured wall-clock and use it as the `AgentRunResult.durationMs`
  when the parsed `duration_ms` is `0`/absent. The self-reported value stays
  authoritative on a clean exit; the measured value is the fallback on a kill.
  This fixes both the `harness_status` `"... in 0s"` line
  (`HarnessStatusReporter.formatPhaseExit`) and every downstream consumer at the
  same source, rather than patching each reader.

### 2. Classify the end state (`flowtree/agents`)

- Plumb the dropped `killedForLooping` flag from `AgentProcessRunner.Result`
  through `ClaudeCodeRunner` into `AgentRunResult`, matching `OpencodeRunner`,
  so a loop kill is not misattributed.
- Add a small, named classification of how a session ended, derived from
  `(exitCode, killedForInactivity, killedForLooping, wall-clock)`:
  - *could-not-start* — launch failure (`AgentRunResult.launchFailed()`, exit
    `-1`, no output, ~0 ms);
  - *killed-by-signal* — exit `143`/`137` (or `>128`) not already attributed to
    inactivity/loop, with measurable runtime;
  - *inactivity* / *loop* — the existing watchdog flags;
  - *clean* — exit `0`.
  Express it as a method/enum on `AgentRunResult` (the type that owns the exit
  code and flags), not as a helper on the job. A SIGTERM (143) the harness did
  not initiate is labelled external; a 137 that no harness flag claims is a
  force-kill/OOM candidate. The classification drives both the kill-cause string
  and the predicate below.

### 3. Redefine the predicate and own it in a collaborator (`flowtree/runtime`)

- `isHardPrimaryFailure()` becomes *"the primary produced no usable work"*:
  non-zero exit, not killed-for-inactivity, **and the session did no measurable
  work** — i.e. the *could-not-start* classification, or clean-zero-runtime with
  no output — using the **final attempt's** duration, not the accumulated sum
  (addressing the known fragility across inactivity restarts).
- Extract the primary-phase outcome state
  (`primaryPhaseHardFailed`, the predicate, the final-attempt duration, and the
  kill-cause classification) out of `CodingAgentJob` into a focused collaborator
  (e.g. `PrimaryPhaseOutcome`), with `CodingAgentJob` delegating. This is both
  the cohesive-grouping the file needs to stay under the cap and the natural
  home for the new classification, as an earlier review already suggested.

### 4. Grade by delivery (`flowtree/runtime`)

- In `CodingAgentJobEvent.statusFor()`, add a branch *before* the blanket
  `exitCode != 0` → FAILED and the hard-fail → FAILED branches: when the primary
  ended *killed-by-signal* (not could-not-start) and the job delivered work —
  `hasPublishedCommit()` / `hasAgentCommitted()` true, i.e. the later phases
  verified and committed — return `degraded(...)` with a reason naming the kill
  cause and the measured primary duration. `FAILED` remains for the genuine
  *could-not-start* / nothing-delivered case ("no work performed").
- `JobWorkOutcome` already answers "was work delivered" (`publishedNothing()`,
  `allChangesDropped()`, `hasPublishedCommit()`); reuse it rather than
  re-deriving.

### 5. PR path for DEGRADED-with-commit (`flowtree/runtime`)

- `FlowTreeApiEndpoint.handleStatus` currently publishes only on `SUCCESS`.
  Widen the gate so a `DEGRADED` event that carries a commit
  (`event.getCommitHash() != null`) and requested `autoCreatePr` also calls
  `autoPrPublisher.publish(event)`; a `DEGRADED` event with no commit (e.g.
  `allChangesDropped`) still `discard`s. `AutoPullRequestPublisher.publish`
  already no-ops without a PR context/target branch, so the condition to add is
  precisely "has a commit to open a PR for." This is the handler region to
  extract if `FlowTreeApiEndpoint` needs the room (it is at 1597 lines).

### 6. Cost of the killed session (`flowtree/runtime`)

- `executeSingleRun` already calls `costTracker.markIncomplete()` on an
  inactivity kill. Extend that to a signal kill so a primary that died before
  emitting a cost is recorded as incomplete (`withCostIncomplete(true)`), and
  carry whatever partial cost the runner did emit.

### Kill cause on the record (fix item 2)

The kill cause classified in step 2 is written onto the completion event (and so
into the job record the controller persists and `workstream_get_job` returns)
and logged. The honest limit — that an external SIGTERM/137 can be *named as
external* but not always attributed to the specific external agent (OOM vs node
restart vs CLI timeout) from inside the JVM — is stated in the message rather
than guessed. See open questions.

## Success criteria

A reviewer can confirm the task is done by these tests (home: `flowtree/runtime`
`JobStatusRollupTest`, which already scripts `AgentRunResult`s and committed
flags through its `RollupTestJob`; plus a controller-level test for the PR gate):

- **Killed-but-productive primary → DEGRADED + PR + honest duration.** A test
  scripts a primary result with a signal exit (143) and a measurable duration,
  followed by a committing review phase, and asserts: job status `DEGRADED`
  (not `FAILED`); the error/reason names the kill cause and a **non-zero**
  primary duration; `autoCreatePr` opens the PR (assert `publish` is invoked for
  the `DEGRADED`-with-commit event where today only `SUCCESS` invokes it).
- **Could-not-start → FAILED, "no work performed."** A launch-failure result
  (`AgentRunResult.launchFailed`, exit −1, no output) with nothing committed
  still rolls up to `FAILED` with the no-work-performed reason.
- **Duration fallback.** A unit test of the runner parse path asserts that when
  the result JSON carries no `duration_ms`, the measured wall-clock is used, so
  `formatPhaseExit` no longer prints `in 0s` for a killed session.
- **Predicate near-misses preserved.** The existing
  `hardFailurePredicateRejectsNearMisses` cases still hold (a true instant crash
  is still hard; an inactivity kill is still not), extended with the new case: a
  signal kill with measurable runtime is **not** a hard failure.
- **No regression on the SUCCESS path** (`primarySuccessRollsUpToSuccess`,
  `normalPrimaryFailureWithRecoveryRollsUpToSuccess`) and on the existing
  `DEGRADED` reasons (`GitManagedJobDegradedStatusTest`).
- `mcp__ar-build-validator__start_validation` passes (checkstyle `FileLength`
  included — both touched files must end **below** the cap after the extraction).

## Dependencies

- Builds on the already-landed working-tree rescue ("never lose a finished
  working tree", `WorkingTreeRescue`/`JobWorkOutcome`) and the job-status rollup
  (`primaryPhaseHardFailed`, `CodingAgentJobEvent.statusFor`). No external
  dependency; all work is in `flowtree/agents` and `flowtree/runtime`, which
  build and test on the default Linux Docker node. **No special machine
  required** (no `-workstream.yaml`).

## Open questions

1. **How far to attribute an external kill.** From inside the worker JVM we can
   say a 143 was *not harness-initiated* (the harness uses SIGKILL) and label it
   external, but distinguishing cgroup-OOM from node-restart from a CLI timeout
   may need host/cgroup inspection that is out of scope here. Proposal: name what
   we can prove and label the rest "external SIGTERM (not harness-initiated)";
   if richer attribution is wanted, file it as a follow-up. **Decision needed
   from the approver.**
2. **Should any other `DEGRADED` reason also open a PR?** This plan opens a PR
   only for the killed-but-productive case (gated on a commit existing). If the
   approver wants, e.g., the `postCompletionCapHit` DEGRADED to also publish when
   a commit exists, the gate generalises cleanly to "`DEGRADED` with a commit",
   but that broadens behaviour for existing reasons and should be an explicit
   decision rather than a silent side effect.
3. **Collaborator boundaries.** The `PrimaryPhaseOutcome` extraction and the
   `FlowTreeApiEndpoint` status-handler extraction are proposed to create room;
   the exact seams are an implementation detail the object-oriented-design review
   will scrutinise. If a cleaner existing seam is found, prefer it.

## Estimated complexity

**Medium–large.** The logic changes are individually small and well-localised,
but they span two modules and four layers (process runner → result record →
job/outcome collaborator → event grading → controller PR gate), require a wire/
record field for the kill cause, and force a file-length refactor on two files
already at the cap. Several existing tests pin the current behaviour and must be
extended (not weakened) in lockstep.
