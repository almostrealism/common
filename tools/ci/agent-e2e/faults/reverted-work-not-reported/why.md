# Work destroyed by the tampering revert stops being reported

## The defect

`JobWorkOutcome.computeUnpublishedWork()` opens with the case where the
tampering detector reverted the agent's own commits and no restart replaced
them:

```java
if (job.hasRevertedAgentWork() && !primaryCommitted) {
```

The patch inverts the second term to `primaryCommitted`, so the branch fires
only when a commit *was* published — that is, never in the case it exists
for. A condition inverted is chosen over a block deleted because it is the
more realistic defect: it survives review, it reads as correct, and it
leaves every symbol in use.

## The failure it models

This is the one path where the harness itself destroys the session's output.
The agent commits, the detector calls it tampering, the revert discards what
the commit held — and unlike every other drop, there is nothing left over
afterwards. No working tree holding the changes, no commit, nothing for a
later session to find. The only artefact is the report.

Without this branch the job falls through to the ordinary checks, finds a
clean tree and no commit, and looks exactly like a session that deliberately
did nothing.

## Why this fault is in the catalogue

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
