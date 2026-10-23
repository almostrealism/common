# The harness stops supplying a commit message the agent did not write

## The defect

Two independent fallbacks supply a commit message the agent did not write.
Both live on the job's `CommitMessageBuilder`:

- `buildExhaustionFallback()` — when the `commit-message` enforcement rule
  exhausts its retries, `EnforcementRunner` writes this into `commit.txt`;
- `buildFallbackMessage()` — `resolve()` builds this from the task prompt
  when `commit.txt` is absent or empty at commit time.

`AgentJobFaultCatalogue.NoFallbackMessage` is a `CommitMessageBuilder` whose
two fallbacks both produce an empty message. `missingCommitMessageDropsWork`
installs it with `CodingAgentJob.setCommitMessageBuilder` and runs
`editsWithoutACommitMessageStillReachTheRemote`.

Breaking either fallback alone changes nothing observable — the other
covers. That is the honest shape of the defect: losing this property means
losing both, and it is why both live on one collaborator that can be
replaced as a unit.

## The failure it models

Forgetting to write `commit.txt` is the most ordinary thing an agent gets
wrong, and the harness covers for it so that forgetting costs a worse
subject line rather than the work. Without the cover, a session that did
everything asked leaves its edits in a working tree on an agent host, and
the only symptom is a branch that did not move.

That is the same externally visible signature as the outage — a job that ran
and changed nothing — reached by a completely different route. Which is why
the catalogue needs more than one entry for it: the signature is common, the
causes are not, and covering one cause is not covering the shape.

## How the shape of it was established

Worth recording, because two reasonable guesses were wrong and the
catalogue is what said so, rather than any amount of reading.

The first version removed only the prompt fallback in `resolve()`, which
looks like the place a missing message is handled. NOT DETECTED. The second
removed only the enforcement runner's write. Also NOT DETECTED. Running the
first faulted scenario and reading the job log explained the first miss:

```
EnforcementRunner: commit-message rule: exhausted 2 retries without resolution
EnforcementRunner: commit-message rule: wrote fallback commit message to commit.txt
CodingAgentJob: Using commit message from commit.txt
```

By the time `resolve()` runs, `commit.txt` already exists — the agent wrote
it, or the enforcement runner did. So in the normal flow the prompt fallback
never executes; it is a backstop that only matters if the first fallback is
gone. Which is exactly what the second attempt demonstrated by removing the
first and watching the backstop hold.

Unlike the other dominated code paths found in this subsystem, this one is
not a defect. It is redundancy that works: two layers, either sufficient.
The lesson for the catalogue is narrower — a fault must remove every layer
that supplies the property, and "I removed the obvious one and nothing
happened" is information about the system, not a reason to weaken the test.

## Why this fault is in the catalogue

`editsWithoutACommitMessageStillReachTheRemote` scripts an agent that edits a
file and writes no message, and requires the edit to reach the remote. The
unit tests of `CommitMessageBuilder` pin each fallback's text; none of them
runs the enforcement loop, the stager and the commit together, which is the
only place the two layers meet.

The test it replaced asserted `published || reported` — satisfied whichever
way the system behaved, and it would have gone on passing if the behaviour
flipped. It was green, it could not fail, and it was written here by the same
process writing this file. Observing the real behaviour and pinning that is
what makes this fault detectable at all; against the disjunction it was not.
