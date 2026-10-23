# The restart after tampering never happens

## The defect

`CodingAgentJob.onGitTampering()` reverts the agent's commits and restarts
the session so the work can be redone without tampering. Every session
launch, that restart included, goes through one chokepoint: the job's
`RestartGovernor`, which refuses a launch once a ceiling is reached.

`tamperingRestartSkipped` caps the governor at one session
(`RestartGovernor.setMaxTotalSessions(1)`) and runs
`aRestartAfterTamperingRepublishesTheWork`. The primary session runs; the
restart is refused. `onGitTampering()` still logs the violation, still
reports the unusual status and still returns `true` — it just never runs the
agent again. No production code changes: the seam is the governor's own
ceiling, and the defect is the one a miscounted or regressed ceiling would
produce.

## The failure it models

The revert destroys whatever the agent's commit held. The restart is the
only thing that replaces it. Without the restart, every tampering incident
becomes total loss of the session's work — and the loss is reported, which
is what makes this subtle: the job says `FAILED` with a truthful-sounding
reason about work being reverted, and nothing distinguishes "the harness
tried to recover and could not" from "the harness never tried".

## Why this fault is in the catalogue

No unit test catches it. With the restart guarded off inside
`onGitTampering()`, every test near it — `CodingAgentJobEventTest`,
`GitManagedJobUnpublishedWorkTest`, `GitManagedJobDegradedStatusTest` —
still passes, and no unit test in `flowtree/runtime` calls
`onGitTampering()` at all. The restart only happens after a real agent
commit is detected and reverted in a real repository.

The suite has two tampering scenarios and needs both, because each alone
permits the defect the other catches:

- `workDestroyedByTheTamperingRevertIsReported` requires the loss to be
  reported. A harness that never restarts satisfies it perfectly — the work
  is destroyed, the report says so, every assertion passes.
- `aRestartAfterTamperingRepublishesTheWork` requires the recovery to
  happen. This fault is what proves that second scenario is doing work.

That scenario also asserts the agent's invocation count, not just the
outcome, because a scenario that quietly stopped tampering would publish and
succeed with no restart at all and satisfy everything else in it. Counting
the invocations is what ties the assertions to the path they are named for —
and it is the assertion this fault trips: one invocation, not two.
