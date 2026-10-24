# The all-changes-dropped branch stops reporting

## The defect

`CodingAgentJobEvent.statusFor()` reports `degraded` when the job's
`JobWorkOutcome.allChangesDropped()` says the staging guardrails accepted no
file and nothing was committed. `AgentJobFaultCatalogue.MissesDroppedChanges`
is a `JobWorkOutcome` whose `allChangesDropped()` always answers `false`.
`allChangesDroppedNotReported` installs it with `GitManagedJob.setWorkOutcome`
and runs `aCommitMessageWithNothingToPublishIsNotASuccess`.

## Why this branch, and not the commit-message term

The heuristic asked for after the outage was: *if the commit message phase
wrote a commit message, but we have zero changes to push — that's a failure
not a success.* `JobWorkOutcome.computeUnpublishedWork()` contains a term
written for exactly that, `messageAuthored`.

That term never decides anything for a coding-agent job. Tracing it:

- `hasAuthoredCommitMessage()` is true exactly when `commit.txt` exists;
- `commit.txt` is an excluded pattern, so writing it puts an entry in the
  skipped list;
- a non-empty skipped list with nothing staged and nothing committed is the
  definition of `allChangesDropped()`;
- and that branch is reached first.

So the rule holds, but the branch enforcing it is not the one written for
it, and the reason it reports — "All changes were dropped by staging
guardrails: commit.txt (excluded pattern)" — points a reader at the
guardrails rather than at a session that wrote a commit message and produced
nothing. Nothing was dropped by a guardrail; there was nothing to drop.

This was found by writing the obvious fault first — deleting the
`messageAuthored` term — and watching the catalogue report NOT DETECTED.

## Why this fault is in the catalogue

`aCommitMessageWithNothingToPublishIsNotASuccess` asserts the status is not
`SUCCESS` **and** that the reason names `commit.txt`. With this branch
silenced the job still fails, via `describeUnpublishedWork()` and its
long-dormant `messageAuthored` term, but the reason no longer names the file
— so the scenario fails on the reason.

That is the intended sensitivity, and no unit test has it: the interplay
above — an excluded `commit.txt` making the guardrail branch the one that
fires — only exists when the real stager, the real exclusion list and the
real event classification run together. The scenario is pinned to the
behaviour rather than to whichever of the two overlapping branches currently
supplies it, and this fault is what proves the reason assertion is
load-bearing rather than decorative. It is narrower than
`dropped-work-reported-as-success`, which silences both branches and takes
the status down with them.
