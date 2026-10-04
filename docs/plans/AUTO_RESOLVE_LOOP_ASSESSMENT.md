# Auto-Resolve Loop Assessment: PR #601

**Status:** investigation notes, for a later design discussion. Automatic job
submission was suspended by the owner after this was written.

## What happened

PR #601 (`feature/cl-semaphore-issues`) ran under auto-resolve / auto-review for
roughly a full working day (2026-10-01).

| Measure | Value |
|---|---|
| Jobs | 20, all reported `SUCCESS` |
| Cost | $156.11 (all Opus) |
| Turns | 1,524 |
| Agent wall clock | about 5.7 hours |
| `analysis.yaml` runs on the branch | 21, of which 2 completed |
| Runs since the owner's 17:25 merge | 12, all cancelled |

The pipeline is cancelled when a newer commit lands on the branch, and the agents
pushed every 25–35 minutes. The media lanes (`test-media-cl` in particular) start
hours into a run, so no run that included the owner's fixes reached them. The
verification the owner was waiting for never happened, and the branch accumulated
commits nobody had tested end to end. When jobs were suspended, `test-media` and
`test-media-mac` were failing on the branch although they pass on master.

## The loop

Every job was triggered by a fresh Copilot review of the new head:

1. Copilot reviews the head and raises findings.
2. An agent fixes them and pushes, which cancels the running pipeline.
3. Copilot reviews the new head and raises findings about the code just added.

A representative sequence from the evening:

- 21:14: a helper is introduced (`Destroyable.releaseAll`) to unify three release loops.
- 21:45: Copilot notes it does not catch `Error`; fixed.
- 22:13: further `Error` aggregation on release paths; fixed.
- 22:39: a Metal executor can leak when teardown itself fails; fixed.

Each round addressed a narrower failure path, most of them teardown or cleanup
failures, several in Metal-only code that the Linux agents cannot run.

About 8 of the 20 jobs were "second-pass" reviews that concluded "no edits
warranted" and restated the same list of open owner-level design questions
(`DeferredSemaphore` thread usage, `deferredReference` TOCTOU, `Submittable`
failure-path waits, `KernelMemoryGuard` two-map handoff). These cost money, and
because the same items were repeated rather than escalated, nothing moved them
forward.

## What was worth having

The overnight passes (roughly 03:00–09:00) delivered real fixes:

- `79fd0ba11`: `Submittable.submit` merges the completion of every group member.
  This is a genuine correctness bug, independently found during interactive work
  the same day; the interactive fix was dropped in favour of this one at merge.
- `4835337dd`, `1c8bbbbef`: merged completions settle on failure; deferred work
  holds a scheduling lease; deferred references survive an argument's destroy.
- Regression tests for `ProjectedGene` kernel caching across scoped contexts.

Rough split: perhaps $40–50 of the spend produced durable value; the evening
passes produced little that the pipeline could have confirmed, and their cost
was compounded by the cancelled runs.

Unverified: the $29.94 job submitted with "YOU BROKE THESE TESTS" (08:13)
produced one commit, "Cut per-dispatch allocation on the native execution path",
and whether that addressed the failing tests was not checked.

## Owner's corrections to the first draft of these conclusions

- Long-running loops are sometimes genuinely valuable; on other branches,
  letting them run for multiple days has delivered real results. "Cap passes per
  day" throws that away. The failure here is specific, not general.
- "Don't push while the pipeline is running" is not the answer either. Waiting
  hours for a pipeline only to find its results are moot because design changes
  are pending is its own waste.

## Questions for the design discussion

These are the problems to solve, not settled answers:

1. **Separating design churn from verification.** Agents and reviewers can keep
   refining the design while a pinned commit is verified in parallel, instead of
   each push cancelling the only verification in flight. What should be pinned,
   and when does a newer head supersede it?
2. **Telling a productive loop from a diminishing one.** Signals that were
   visible here: findings concentrating in failure and cleanup paths; findings
   about code the loop itself just added; a rising share of "no edits
   warranted" passes; the same open items repeated pass after pass. Which of
   these could gate continuation automatically?
3. **Escalation instead of repetition.** When the same owner-level items appear
   in consecutive passes, the loop should surface them once to the owner and
   stop paying to restate them.
4. **What triggers a job.** Here every Copilot review triggered a job. Should a
   Copilot finding alone be enough, or only in combination with failing checks,
   severity, or an explicit request?
5. **Branch regressions as a stop signal.** The branch ended worse than master
   on lanes that had been green. A loop that cannot see those lanes (because its
   own pushes cancel them) has no way to notice that it is making things worse.
