---
status: proposed
created: 2026-11-19
closed:
tracker: []
---

# Plan: make the `ci: ready for review` label respect drafts and pending review

Status: **investigation and options only.** Nothing here has been implemented, and
the owner has asked that nothing change until an option is chosen.

## Problem

`ci: ready for review` is applied to pull requests that are not ready. On PR #993
(`feature/github-update-pr`) the label was on the PR while GitHub Copilot's first
review was still to come. That review landed at 04:28Z on 2026-11-19 with 4 open
issues, and the label stayed until the next push, about an hour later.

## Current behavior

### 1. Who writes `ci:` labels

There is exactly **one writer**: `tools/ci/pr-status-labels.py`, run by
`.github/workflows/pr-status-labels.yaml`. Nothing in the FlowTree controller
(`flowtree/runtime`) or in ar-manager (`tools/mcp/manager`) adds or removes a
`ci:` label. A grep over `flowtree/runtime/src/main`, `tools/mcp/manager` and
`tools/ci` finds no other `ci: ` label strings and no label endpoints.

| Label | Set when (`derive`, `pr-status-labels.py:156-212`) |
|-------|-----------------------------------------------------|
| `ci: validating` | the head commit's validate run (`pull_request`) is still running, or no run exists yet (`:198-201`, `:212`) |
| `ci: auto-review` | the validate run finished and `auto-review-submit` or `auto-resolve-python-submit` succeeded (`:203-207`) |
| `ci: full tests` | the test run (`deployment`) is running, or failed less than 15 min ago on an attempt before the last (`:169-171`, `:192-195`) |
| `ci: auto-fix` | the test run failed and an agent was sent (`:185-191`) |
| **`ci: ready for review`** | **the test run succeeded, the newest `Test Checks` and `All Checks` passed, and `mergeable_state != dirty` (`:175-182`)** |
| `ci: needs attention` | CI stopped with no agent (several branches of `derive`) |
| `ci: review limit` | the `remediation-cap` commit status is `failure` (`:187-189`, `:204-206`) |
| `ci: stalled` | added beside any state that has lasted too long (`is_stalled`, `:215-221`) |

Triggers (`pr-status-labels.yaml:30-36`): `workflow_run` of `Build and Test` and
`Auto-Resolve Submit` (requested and completed), a 30-minute cron, and manual
dispatch. **No `pull_request`, `pull_request_target` or `pull_request_review`
event triggers it.**

What `derive` does **not** read:
- `pr["draft"]`. It fetches the full PR (`gather`, `:303`) but only reads
  `mergeable_state`.
- reviews, review threads, or requested reviewers. `gather` (`:299-334`) reads
  only workflow runs, jobs, check runs, artifacts and commit statuses.

### 2. How draft status flows

- **Created as a draft**: planning jobs set `PR_DRAFT=true`, namely
  `task-planning.yaml:307-309`, `master-agent-dispatch.yaml:327-329` and the
  `workstream_register` plan job. That becomes `createPrAsDraft`
  (`submit-agent-job.sh:113,251,262`; `workstream_submit_tools.py:782-783`;
  `CodingAgentJobFactory.java:1279-1296`). The controller sends `"draft": true`
  when it opens the auto-created PR (`AutoPullRequestPublisher.java:184,242` →
  `GitHubProxyHandler.java:342-381`). QA rounds never draft (`qa-round.yaml:107`).
- **Marked ready**: only by a person. No code calls GraphQL
  `markPullRequestReadyForReview` or `convertPullRequestToDraft`.
- **Reacting to the transition**: only `ready-plan.yaml:16-19`
  (`pull_request_target: ready_for_review`), which calls `plan-ready.yaml`. It
  dispatches `verify-completion.yaml` only when the sender is in `PLAN_APPROVERS`
  **and** every changed file is still under `docs/` (`.github/CLAUDE.md`, "What
  the `Plan Ready` workflow does"). `analysis.yaml:25-27` uses the default
  `pull_request` types, so it does not run on `ready_for_review`, and that is
  intentional. Nothing reacts to `converted_to_draft`.
- **Reading the draft flag before labeling**: nothing does. The settled-CI
  trigger doesn't read it either (below).

### 3. Timeline of a normal PR, and where Copilot fits

- **Settled** means any job on the workstream ends `SUCCESS` with nothing
  committed or pushed (`JobCompletionEvent.isSuccessWithoutChanges`,
  `JobCompletionEvent.java:469-476`). `FlowTreeApiEndpoint.java:1349-1351` then
  calls `Workstream.startSettledCi` → `RepositoryBinding.startSettledCi`
  (`RepositoryBinding.java:170-189`) → `CiTrigger.start`
  (`CiTrigger.java:144-170`), which creates a `deployment:test` for the head.
  None of these looks at draft state, reviews, or open threads.
- **Copilot wait**: `auto-review-submit` holds the agent job for
  `DELAY_SECONDS=300` so it "starts with" Copilot's review
  (`analysis.yaml:4524-4531`). It drops the hold only when Copilot's latest
  review is a quota notice (`analysis.yaml:4565-4571`,
  `copilot-review-status.py:42-55`). With **no** Copilot review at all, the
  script reports `copilot_available=true` and the job waits 300 s for a review
  that may never come.
- **Copilot reviews that arrive later**: nothing notices them. No workflow or
  controller path triggers on `pull_request_review`. The label is re-derived
  only on the next `Build and Test` run or cron tick, and even then `derive`
  ignores reviews. A late Copilot review is picked up only when a later push
  starts a new validate run, whose auto-review agent then reads it (the
  `general-review.txt:39` scope rule).
- **Open threads before labeling**: not checked anywhere.

```
push ──► validate run ──► auto-review-submit (holds 300s) ──► agent job
  ▲                                                             │
  │ agent pushes                                                │ no changes
  └─────────────────────────────────────────────────────────────┤
                                                                ▼
                                    controller: startSettledCi → deployment:test
                                                                ▼
                                    test run passes → pr-status-labels → READY
   (Copilot: reviews ~3–8 min after a push to a NON-draft PR; nothing in this
    loop waits for it beyond the one 300s hold, and nothing re-labels on it)
```

### 4. PR #993 reconstructed

This is from the public issue-timeline and workflow-runs APIs (read-only). All
times are 2026-11-19 UTC.

| Time | Event |
|------|-------|
| 01:56 | PR opened from a plan commit (a draft plan PR: the next row shows it left draft at 04:25) |
| 01:56–03:23 | 8 pushes (plan edits, then implementation). The label cycles validating ↔ auto-review. **No "Running Copilot Code Review" run happens on any of them.** |
| 03:35 | Last validate run passes → `ci: auto-review`; the agent finds nothing to change → settled |
| 03:47:49 | `deployed` (settled CI, `deployment:test`) → `ci: full tests` |
| 04:11:53 | Test run passes → **`ci: ready for review`, PR still a draft** |
| 04:25:15 | Owner marks the PR ready for review (`ready_for_review`); Plan Ready runs and dispatches nothing (the PR is no longer docs-only) |
| 04:25:17–27 | Copilot is requested and its first review run starts, **10 s after leaving draft** |
| 04:28:48 | Copilot review: "Changes recommended", 4 open issues. The label stays `ci: ready for review` |
| 05:33 | Next push → `ci: validating` replaces ready; Copilot reviews again; the auto-review agent runs |

The implementation commits landed while the PR was a draft, without a
`verify-completion` run on this branch. How that implementation was started
isn't visible in GitHub data.

## Is draft status the root cause?

**Partly, and more directly than it first appears.** The evidence shows two
separate defects:

1. **The labeler ignores draft.** The label went on at 04:11 while the PR was a
   draft (it looked like 04:25 only because that's when the owner looked). A
   draft PR should never read "ready for review"; the two meanings collide
   literally.
2. **Draft suppresses Copilot, and the loop doesn't know it.** No Copilot run
   happened during 2.5 hours of draft pushes, and one started 10 s after the
   PR left draft. That fits Copilot's automatic review skipping drafts (the
   default unless "review draft pull requests" is enabled). So the whole review
   loop, including the 300 s Copilot hold, ran without Copilot, and settled
   CI started with Copilot never having seen the code.
3. **Not draft-related: late reviews are invisible.** Even on a non-draft PR,
   READY is derived from CI alone. A Copilot (or human) review arriving after
   settle never removes the label (`derive` doesn't read reviews, and no
   review event re-runs the labeler). On #993 this is what kept the label wrong
   from 04:28 to 05:33.

So fixing draft alone would have kept the label off until 04:25. It would
**not** have stopped the label from being wrong between 04:28 and 05:33.

## Options

All options touch `.github/workflows/` and/or `tools/ci/`, so they must land on
a `ci/...` branch (CI-file lock). Option C also touches `flowtree/runtime`.

### Option A: draft-aware labeler

**What changes**
- `derive`: when `pr["draft"]` is true, never return READY. Return a new
  `ci: draft` state, or keep the CI-stage label and add a sibling marker such as
  `ci: draft` the way `ci: stalled` works.
- `pr-status-labels.yaml`: add `pull_request_target: [ready_for_review,
  converted_to_draft]`. This is safe because the job already checks out the
  default branch only and runs no PR code. It must stay in this file and never
  gain a PR checkout.
- New unit cases in `tools/tests/test_pr_status_labels.py`.

**Drafts / plan approval**: doesn't touch the draft flag, so Plan Ready is
unaffected.

**Failure modes**: plan PRs that carry implementation while still draft (as #993
did) show `ci: draft` even after green CI. That is honest, but the owner must
then remember to mark them ready.

**Effort**: small (about 20 lines plus tests).

**Trade-off**: fixes defect 1 only. After the owner marks ready, the PR
immediately shows READY, then Copilot lands minutes later, which is the #993
04:25–05:33 window again.

### Option B: review-aware READY gate (new `ci: awaiting review` state)

**What changes**
- `gather` additionally reads `requested_reviewers`, `pulls/{n}/reviews` and the
  GraphQL `reviewThreads { isResolved }` for the PR.
- `derive`: CI-green becomes READY only when all of these hold:
  - no unresolved review threads;
  - no pending Copilot review request;
  - Copilot has reviewed the head SHA, or its latest review is a quota notice
    (reuse `github_api.is_copilot_quota_notice`, as `copilot-review-status.py`
    already does), or Copilot was never requested.

  Otherwise it returns `ci: awaiting review`, plus `ci: stalled` after N hours so
  a dark Copilot can't hold it silently.
- `pr-status-labels.yaml`: add `pull_request_review: [submitted, dismissed]`,
  `pull_request_review_thread: [resolved, unresolved]` and
  `pull_request_target: [review_requested]`, so a late review flips the label
  within seconds. Fork PRs give read-only tokens on `pull_request_review`, so
  route through `workflow_run` or `pull_request_target`, or accept the 30-min
  cron for forks.
- Combine with Option A's draft check.

**Drafts / plan approval**: read-only with respect to the draft flag. While the
PR is a draft Copilot is never requested, so the gate needs A's draft rule
first, or it would report READY ("never requested").

**Failure modes**:
- A thread resolved without a fix still clears the gate (only resolution is
  measured, not correctness).
- Copilot quota pages vary in wording, and a misdetected notice holds the PR in
  `awaiting review` until stalled.
- More API calls per PR per tick; GraphQL with `GITHUB_TOKEN` is fine but needs
  `pull-requests: read`.
- Human reviewers who are requested but never respond: decide whether only the
  Copilot request counts.

**Effort**: medium (about 100–150 lines plus tests, plus a workflow trigger
review).

**Trade-off**: fixes defects 1 and 3 at the label level, and the label then
tells the truth. It doesn't make the **agent** act on the late review; that
still waits for a push or a person.

### Option C: make "settled" review-aware in the loop (controller + CI)

**What changes**
- `startSettledCi` (or the call at `FlowTreeApiEndpoint.java:1349-1351`)
  declines while the PR is a draft or a Copilot review is pending on the head
  SHA. It records the deferral, and a later event resumes it.
- A new trigger (a review-event workflow posting to the controller, or the
  labeler's `ci: awaiting review` → submit) dispatches an auto-review pass with
  the `pr-feedback` prompt when a review with open issues lands after settle,
  and on `ready_for_review` for a non-docs-only PR.
- `auto-review-submit`'s 300 s hold becomes "wait for Copilot on this SHA, or
  for the PR to be a draft (no Copilot coming)" rather than a fixed delay.

**Drafts / plan approval**: a draft PR would get no test run until it leaves
draft. That matches "draft = not under review", but it changes today's flow
where plan-plus-implementation work is tested while draft. The
`ready_for_review` → auto-review dispatch must not collide with Plan Ready's
dispatch on a docs-only PR (Plan Ready owns docs-only; this path owns the rest).

**Failure modes**:
- More remediation passes, so `RemediationBudgetGuard` / `ci: review limit` is
  hit sooner.
- A deferred settle that is never resumed (lost event) stalls the PR, so it
  needs a cron sweep like the labeler's.
- A cross-system change (controller and CI) that is harder to test end to end.

**Effort**: large.

**Trade-off**: the only option that makes the PR actually **be** ready
(Copilot's issues handled by an agent) instead of just labeled honestly.

### Option D: keep PRs draft until settled; the controller marks them ready

**What changes**: every auto-created PR opens as a draft. On settle plus a green
test run, the controller calls `markPullRequestReadyForReview`.

**Drafts / plan approval**: **dangerous as stated.** The controller's GitHub
token acts as the owner (the `deployed` event on #993 is attributed to the owner
login). A ready transition the controller makes therefore has a `sender` that
is likely in `PLAN_APPROVERS`, and on a still-docs-only plan PR it would make
Plan Ready dispatch the implementation, i.e. the system approving its own plan.
It needs a separate token identity or a different approval signal, which means
redefining the plan-approval convention.

**Failure modes**: Copilot starts only when the PR leaves draft, so READY would
still precede Copilot by minutes unless combined with B. Every PR is invisible
to reviewers until settled.

**Effort**: medium, plus a convention change.

**Trade-off**: makes the draft flag carry loop state, which overloads it further.
Listed for completeness; **not recommended**.

## Recommendation

> **Recommendation:** do **A + B together** on one `ci/...` branch, as a
> labeler-only change: no controller change and no change to the draft or
> plan-approval convention. It makes the label truthful in all three observed
> failure windows, is fully unit-testable in `test_pr_status_labels.py`, and is
> derived from scratch each run, as the labeler already is. Treat **C** as a
> follow-up once B's `ci: awaiting review` state shows how often late reviews
> actually leave work behind. Reject **D** unless the plan-approval signal is
> moved off the draft flag.

## Open questions for the owner

1. Should a draft PR show its CI stage plus a `ci: draft` marker, or a single
   `ci: draft` label that hides the stage?
2. Is the label meant to say "CI is green" or "a person should look now"? B
   assumes the latter. If the former, perhaps rename it (e.g. `ci: passed`) and
   put the review gate in a separate label.
3. Should requested **human** reviewers also hold READY, or only Copilot?
4. Should an unresolved thread from the owner hold READY even when CI and
   Copilot are clean? (B as written says yes.)
5. Is it intended that implementation commits land on a plan PR while it is
   still a draft (as on #993)? If not, that is a separate dispatch question and
   affects how much Option A's `ci: draft` will show up.
6. Is "review draft pull requests" deliberately off in the Copilot ruleset? Turning it on
   would remove defect 2 at the source, at the cost of Copilot quota spent on plan
   drafts.
