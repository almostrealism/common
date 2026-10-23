# Work destroyed by the tampering revert stops being reported

## The defect

`JobWorkOutcome.computeUnpublishedWork()` opens with
`describeRevertedWork()`: the case where the tampering detector reverted the
agent's own changes and no restart replaced them with a primary commit.

`AgentJobFaultCatalogue.ForgetsTheTamperingRevert` is a `JobWorkOutcome`
whose `describeRevertedWork()` never reports anything; every other check
runs as normal. `revertedWorkNotReported` installs it with
`GitManagedJob.setWorkOutcome` and runs
`workDestroyedByTheTamperingRevertIsReported`. It is the same defect as a
condition inverted inside that check — the branch never fires in the case it
exists for — which is the realistic shape: it survives review and reads as
correct.

## The failure it models

This is the one path where the harness itself destroys the session's output.
The agent commits, the detector calls it tampering, the revert discards what
the commit held — and unlike every other drop, there is nothing left over
afterwards. No working tree holding the changes, no commit, nothing for a
later session to find. The only artefact is the report.

Without this branch the job falls through to the ordinary checks, finds a
clean tree and no commit, and looks exactly like a session that deliberately
did nothing. Observed with the fault installed: the job ends `SUCCESS`.

## Why this fault is in the catalogue

No unit test catches it. With the defect applied as a condition inverted in
the production check, `GitManagedJobUnpublishedWorkTest`,
`CodingAgentJobEventTest` and `GitManagedJobDegradedStatusTest` all still
pass, and no unit test in `flowtree/runtime` exercises
`hasRevertedAgentWork()` at all — a revert needs a real repository, a real
agent commit and the real tampering detector, which only the end-to-end
scenario assembles.

`workDestroyedByTheTamperingRevertIsReported` scripts an agent that edits a
file, writes a commit message, and makes its own git commit. It requires
three things: nothing in the remote, status `FAILED`, and a reason naming
the tampering.

Status is pinned to `FAILED` rather than "not SUCCESS" on purpose. The
difference between `FAILED` and `DEGRADED` is the difference between "this
job lost your work" and "this job finished with a caveat", and a fleet
dashboard is read at exactly that granularity. A test satisfied by either
would let the more serious of the two decay into the milder one without
anyone noticing.
