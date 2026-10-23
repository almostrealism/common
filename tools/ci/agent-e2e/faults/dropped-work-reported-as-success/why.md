# Work that was dropped is reported as success

## The defect

`CodingAgentJobEvent.statusFor()` asks the job's `JobWorkOutcome` two
questions that turn a job which produced work but published none of it into
something other than a success: `allChangesDropped()` reports `degraded`
when the staging guardrails rejected everything, and
`describeUnpublishedWork()` reports `failed` when the agent left changes that
never reached a commit.

`AgentJobFaultCatalogue.ReportsNoLostWork` is a `JobWorkOutcome` that
answers both with "nothing was lost". `droppedWorkReportedAsSuccess`
installs it with `GitManagedJob.setWorkOutcome` and runs
`rejectedChangesAreReportedNotSilentlyDropped`. Everything that is not an
outright exception now ends `SUCCESS`.

## The failure it models

This is the silent no-op, and it is the failure mode that is hardest to see
from outside. The job says `SUCCESS`. The branch is unchanged. There is
nothing in the result to distinguish "the agent examined the problem and
correctly decided no change was needed" from "the agent did the work and
every line of it was thrown away".

A fleet in this state looks exactly like a fleet with nothing to do. The
first half of the outage this tooling was built after — a policy killing
every job — was at least loud. This half is not, which is why the reporting
branches exist and why removing them has to break something.

## Why this fault is in the catalogue

`rejectedChangesAreReportedNotSilentlyDropped` asserts two things at once:
the weakened test must not reach the remote, **and** the job must not report
`SUCCESS`. This fault leaves the first true and makes the second false.

That separation is the point. A check that only asks "was anything bad
published?" is blind to a defect that merely stops reporting, and a reviewer
reading a green job list cannot tell the difference. The unit tests of
`JobWorkOutcome` and `CodingAgentJobEvent` each pin one side of the
classification; only this scenario shows that a real rejected change set,
staged by the real guardrails, reaches the status a person sees.

## What this fault replaced

An earlier entry here broke the `validateChanges()` rejection path in
`doWork()` instead, on the assumption that the same test covered it. The
catalogue reported NOT DETECTED, and it was right: in the end-to-end flow
`validateChanges()` never returns `false`. The discovery came directly from
this fault failing to reproduce, which is the catalogue working as intended
on the person writing it.

The entry that guarded the other assertion of the same scenario — the
base-branch test-method lock (`test-method-lock-lifted`) — was dropped when
the catalogue moved into code: lifting the lock fails five methods of
`TestMethodProtectionTest` and two of `GitManagedJobDegradedStatusTest`, so
the unit tests already watch it fail.
