# The restart after tampering never happens

## The defect

`CodingAgentJob.onGitTampering()` reverts the agent's commits and restarts
the session so the work can be redone without tampering. The patch guards
the restart behind a condition that is never true, so the method still logs
the violation, still reports the unusual status, and still returns `true` —
it just never runs the agent again.

Guarded rather than deleted on purpose. A deleted call is conspicuous; a
guarded one reads as defensive coding, which is what a defect of this shape
would actually look like.

## The failure it models

The revert destroys whatever the agent's commit held. The restart is the
only thing that replaces it. Without the restart, every tampering incident
becomes total loss of the session's work — and the loss is reported, which
is what makes this subtle: the job says `FAILED` with a truthful-sounding
reason about work being reverted, and nothing distinguishes "the harness
tried to recover and could not" from "the harness never tried".

## Why this fault is in the catalogue

The suite has two tampering scenarios and needs both, because each alone
permits the defect the other catches:

- `workDestroyedByTheTamperingRevertIsReported` requires the loss to be
  reported. A harness that never restarts satisfies it perfectly — the work
  is destroyed, the report says so, every assertion passes.
- `aRestartAfterTamperingRepublishesTheWork` requires the recovery to
  happen. This fault is what proves that second test is doing work.

That test also asserts the agent's invocation count, not just the outcome,
because a scenario that quietly stopped tampering would publish and succeed
with no restart at all and satisfy everything else in it. Counting the
invocations is what ties the assertions to the path they are named for.
