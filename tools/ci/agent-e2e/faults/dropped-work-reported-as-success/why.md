# Work that was dropped is reported as success

## The defect

`GitManagedJob.createEvent()` has two branches that turn a job which
produced work but published none of it into something other than a success:
`hasAllChangesDropped()` reports `degraded` when the staging guardrails
rejected everything, and `describeUnpublishedWork()` reports `failed` when
the agent left changes that never reached a commit.

The patch removes both. Everything that is not an outright exception now
returns `success`.

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
`SUCCESS`. This patch leaves the first true and makes the second false.

That separation is the point. A check that only asks "was anything bad
published?" is blind to a defect that merely stops reporting, and a reviewer
reading a green job list cannot tell the difference. This fault is what
proves the second assertion is load-bearing rather than decorative.

## What this fault replaced

An earlier entry here patched the `validateChanges()` rejection path in
`doWork()` instead, on the assumption that the same test covered it. The
catalogue reported NOT DETECTED, and it was right: in the end-to-end flow
`validateChanges()` never returns `false`. See the "A gate that cannot fire"
section of the end-to-end verification plan — the discovery came directly
from this fault failing to reproduce, which is the catalogue working as
intended on the person writing it.
