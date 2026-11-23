---
status: proposed
created: 2026-11-19
closed:
tracker: []
---

# Plan: make the `ci: ready for review` label respect drafts and pending review

Status: **investigation and options only.** Nothing here has been implemented, and
the owner has asked that nothing change until an option is chosen.

Source is referenced by file and symbol name, not line number, so the references
survive edits (see `docs/CLAUDE.md`).

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
`ci:` label.

There is one other **reader**. ar-manager's `github_pr_labels` tool
(`tools/mcp/manager/github_tools.py`) returns a PR's labels together with its
`draft` flag. It is strictly read-only, and its docstring already states the rule
this plan is about: "`ci: ready for review` is only meaningful once `draft` is
False". So today the agent-facing tool, not the label, carries the draft
qualification. Any option below that changes the label set (a new `ci: draft`
or `ci: awaiting review` state) should update that docstring too.

The stage label comes from `derive` in `pr-status-labels.py`:

| Label | Set when (`derive`) |
|-------|---------------------|
| `ci: validating` | the head commit's validate run (`pull_request`) is still running, or no run exists yet |
| `ci: auto-review` | the validate run finished and `auto-review-submit` or `auto-resolve-python-submit` succeeded |
| `ci: full tests` | the test run (`deployment`) is running, or failed less than `RETRY_DECISION_MINUTES` (15) ago on an attempt before `MAX_ATTEMPTS` |
| `ci: auto-fix` | the test run failed and an agent was sent (submit job succeeded, or an `auto-resolve-request` artifact was staged in the latest attempt) |
| **`ci: ready for review`** | **the test run succeeded, the newest `Test Checks` and `All Checks` check runs passed, and `mergeable_state != "dirty"`** |
| `ci: needs attention` | CI stopped with no agent (several branches of `derive`) |
| `ci: review limit` | an agent was due but the `remediation-cap` commit status is `failure` (`capped_at`) |

Two markers can sit beside the stage label (`label_changes`):

| Marker | Set when |
|--------|----------|
| `ci: stalled` | the stage has lasted longer than its `LABELS` hour limit (`is_stalled`) |
| `ci: ready to launch` | the PR is a draft plan PR and an approver marking it ready now would launch an implementation agent (`ready_to_launch`, see below) |

Triggers (`pr-status-labels.yaml`, `on:`): `workflow_run` of `Build and Test` and
`Auto-Resolve Submit` (requested and completed), a 30-minute cron, and manual
dispatch. **No `pull_request`, `pull_request_target` or `pull_request_review`
event triggers it.**

What the labeler already does with draft, and what it doesn't do:
- **Draft is read, but only for the `ci: ready to launch` marker.** `gather`
  fetches the full PR (`pulls/{n}`), and `draft_plan_pr` reads `pr["draft"]` to
  decide whether to fetch the PR's changed files. `ready_to_launch` then requires
  the draft flag, an own-repo head, a default-branch base, a docs-only change set
  and a newly added `docs/plans/*.md`. That marker came from the
  `ci/ready-to-launch-label` branch, merged after this investigation began.
- **The stage derivation (`derive`) ignores draft.** Its only read of the PR
  detail is `mergeable_state` (plus `updated_at` when no run exists). So a draft
  PR whose test run passed gets `ci: ready for review`. A draft plan PR can carry
  `ci: ready for review` **and** `ci: ready to launch` at the same time.
- **Reviews are not read.** `gather` doesn't fetch reviews, review threads or
  requested reviewers. Beyond the PR detail (and files for draft plan PRs) it
  reads only workflow runs, run jobs, check runs, the `auto-resolve-request`
  artifact and commit statuses.

### 2. How draft status flows

- **Created as a draft**: planning jobs set `PR_DRAFT: "true"`, namely
  `task-planning.yaml` and `master-agent-dispatch.yaml`, along with the
  `workstream_register` plan job. That becomes `createPrAsDraft`
  (`submit-agent-job.sh`; `workstream_submit_tools.py`;
  `CodingAgentJobFactory`, `SubmissionConfigResolver`). The controller passes the
  draft flag when it opens the auto-created PR (`AutoPullRequestPublisher` →
  `GitHubProxyHandler.createGitHubPullRequest`, whose `pullRequestPayload` emits
  `"draft": true` only for drafts). QA rounds never draft (`qa-round.yaml`).
- **Marked ready**: only by a person. No code calls GraphQL
  `markPullRequestReadyForReview` or `convertPullRequestToDraft`.
- **Reacting to the transition**: only `ready-plan.yaml`
  (`pull_request_target: types: [ready_for_review]`, `branches: [master]`), which
  calls `plan-ready.yaml`. That dispatches `verify-completion.yaml` only when the
  sender is in `PLAN_APPROVERS` **and** every changed file is still under `docs/`
  (`.github/CLAUDE.md`, "What the `Plan Ready` workflow does"). `analysis.yaml`'s
  `pull_request` trigger uses the default types, so it does not run on
  `ready_for_review`, and that is intentional. Nothing reacts to
  `converted_to_draft`.
- **Reading the draft flag before the stage label**: nothing does (see §1). The
  settled-CI trigger doesn't read it either (below).

### 3. Timeline of a normal PR, and where Copilot fits

- **Settled** means any job on the workstream ends `SUCCESS` with nothing
  committed or pushed (`JobCompletionEvent.isSuccessWithoutChanges`). The job
  completion handler in `FlowTreeApiEndpoint` then calls
  `Workstream.startSettledCi` → `RepositoryBinding.startSettledCi` →
  `CiTrigger.start`, which creates a `deployment:test` for the head. None of these
  looks at draft state, reviews, or open threads.
- **Copilot wait**: the `auto-review-submit` job in `analysis.yaml` sets
  `DELAY_SECONDS: "300"` so the agent job "starts with" Copilot's review. It
  drops the delay to 0 only when `copilot-review-status.py` reports
  `copilot_available=false`, i.e. Copilot's latest review is a quota notice
  (`copilot_available`, `github_api.is_copilot_quota_notice`). With **no**
  Copilot review at all, the script reports `copilot_available=true`, and the job
  waits 300 s for a review that may never come (on a draft, it never does).
- **Copilot reviews that arrive later**: nothing notices them. No workflow or
  controller path triggers on `pull_request_review` (the event name shows up in
  `analysis.yaml` and `.github/CLAUDE.md` only in comments). The label is
  re-derived on the labeler's next run — a `Build and Test` run on the PR's own
  branch, a completed `Auto-Resolve Submit` run or the cron tick (both of which
  re-label every open PR, so a completion on another branch can re-derive this
  one), or a manual dispatch — and even then `derive` ignores reviews, so the
  label does not change on any of them. A late Copilot review is reflected only
  when a later push starts a new validate run, whose auto-review agent then reads
  it (the scope rule in `tools/ci/prompts/general-review.txt`).
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

**Partly, and more directly than it first appears.** The evidence shows three
separate defects:

1. **The stage derivation ignores draft.** The label went on at 04:11 while the
   PR was a draft (it looked like 04:25 only because that's when the owner
   looked). A draft PR should never read "ready for review"; the two meanings
   collide literally. (The labeler does read draft, but only for the separate
   `ci: ready to launch` marker; see §1.)
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

### Option A: draft-aware stage label

**What changes**
- `derive`: when `pr["draft"]` is true, never return READY. Either return a new
  `ci: draft` stage, or keep the CI-stage label and add a sibling marker
  `ci: draft` the way `ci: stalled` and `ci: ready to launch` work (a new
  parameter on `label_changes` and a new `LABELS` entry). The "green CI on a draft"
  case then needs a decided label (see open question 1).
- `pr-status-labels.yaml`: add `pull_request_target: types: [ready_for_review,
  converted_to_draft]`. This is safe because the job already checks out the
  default branch only and runs no PR code. It must stay in this file and never
  gain a PR checkout. Today, without this trigger, a ready transition is picked up
  only at the next cron tick (up to 30 min), so the A-only fix would also show a
  stale `ci: draft` for that long.
- Update the label list in the `pr-status-labels.yaml` header comment, the
  `pr-status-labels.py` module docstring, and the `github_pr_labels` docstring.

**Interaction with `ci: ready to launch`**: on a draft plan PR the two markers
would usually appear together (`ci: draft` + `ci: ready to launch`). That is
consistent, but it is redundant: `ready to launch` implies draft. Decide whether
`ci: draft` is suppressed when `ready to launch` is set (open question 1).

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
- `gather` additionally reads `requested_reviewers` (already in the `pulls/{n}`
  detail it fetches), `pulls/{n}/reviews`, and the GraphQL
  `reviewThreads { isResolved }` for the PR. The reviews and threads calls
  should be made only for PRs whose CI is otherwise green, the way `gather`
  fetches files only for draft plan PRs, to keep the per-tick call count flat.
- `derive`: CI-green becomes READY only when all of these hold:
  - no unresolved review threads;
  - no pending Copilot review request;
  - Copilot has reviewed the head SHA (a review in `pulls/{n}/reviews` whose
    `commit_id` is the head), or its latest review is a quota notice (reuse
    `github_api.is_copilot_quota_notice`, as `copilot-review-status.py` already
    does), or Copilot was never requested — but `requested_reviewers` cannot
    decide that last disjunct; see the failure mode below and open question 8.

  Otherwise it returns `ci: awaiting review`, plus `ci: stalled` after N hours so
  a dark Copilot can't hold it silently. N must be chosen; the other waiting
  states use 1–10 h in `LABELS`.
- `pr-status-labels.yaml`: add `pull_request_review: [submitted, dismissed]` and
  `pull_request_target: [review_requested]`, so a submitted review or a new
  review request flips the label within seconds. **A thread being resolved or
  unresolved has no workflow trigger.** `pull_request_review_thread` is a webhook
  event but not one GitHub Actions can start a workflow on — the Actions event
  reference lists `pull_request_review` and `pull_request_review_comment`, not it
  — so a thread resolve/unresolve is reflected only on the next cron tick (up to
  30 min), unless a webhook-to-`repository_dispatch` bridge is added to carry
  that event in. Criterion 3's `ci: awaiting review` is still derived correctly
  whenever the labeler next runs; only its latency for the thread-only case is
  the cron interval rather than seconds. Fork PRs give read-only tokens on
  `pull_request_review`, so route through `workflow_run` or `pull_request_target`,
  or accept the 30-min cron for forks. (Fork PRs are already excluded by
  `open_pulls`, which keeps only own-repo heads, so in practice the cron path is
  enough.)
- Combine with Option A's draft check.

**Drafts / plan approval**: read-only with respect to the draft flag. While the
PR is a draft Copilot is never requested, so the gate needs A's draft rule
first, or it would report READY ("never requested").

**Failure modes**:
- A thread resolved without a fix still clears the gate (only resolution is
  measured, not correctness).
- Copilot quota pages vary in wording, and a misdetected notice holds the PR in
  `awaiting review` until stalled.
- More API calls per PR per tick. GraphQL with `GITHUB_TOKEN` is fine, and the
  job already has `pull-requests: write`, which covers the read.
- Human reviewers who are requested but never respond: decide whether only the
  Copilot request counts.
- **`requested_reviewers` lists only pending requests, so "Copilot was never
  requested" is not decidable from it.** Once Copilot's request is fulfilled or
  removed it drops off that list, so a non-draft, CI-green PR with no head-SHA
  review and no pending request looks identical whether Copilot was never asked
  or was asked and the request vanished without producing a review (a review on
  an older SHA is still visible in `pulls/{n}/reviews`, so only the
  no-review-at-all case is ambiguous). Deciding between them needs a durable
  request-history signal — the issue-timeline `review_requested` /
  `review_request_removed` events, which `gather` does not fetch today — or an
  explicit rule that this case holds at `ci: awaiting review` rather than READY.
  Until that is chosen, success criterion 4's "Copilot was never requested →
  READY" path is not implementable from the listed data. See open question 8.
- A Copilot review on an older SHA: "has reviewed the head SHA" means its
  `commit_id` equals the head. Since Copilot re-reviews every push to a non-draft
  PR, each push re-opens the gate until the new review lands. That is the intended
  behavior, but it lengthens every PR's time out of READY by Copilot's latency.

**Effort**: medium (about 100–150 lines plus tests, plus a workflow trigger
review).

**Trade-off**: fixes defects 1 and 3 at the label level, and the label then
tells the truth. It doesn't make the **agent** act on the late review; that
still waits for a push or a person.

### Option C: make "settled" review-aware in the loop (controller + CI)

**What changes**
- `Workstream.startSettledCi` (or its caller in `FlowTreeApiEndpoint`'s job
  completion handling) declines while the PR is a draft or a Copilot review is
  pending on the head SHA. It records the deferral, and a later event resumes it.
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
to reviewers until settled. It would also fire `ci: ready to launch`'s chain on
every docs-only PR the controller flips.

**Effort**: medium, plus a convention change.

**Trade-off**: makes the draft flag carry loop state, which overloads it further.
Listed for completeness; **not recommended**.

## Recommendation

> **Recommendation:** do **A + B together** on one `ci/...` branch, as a
> labeler-only change: no controller change and no change to the draft or
> plan-approval convention. It makes the label truthful in all three observed
> failure windows, is fully unit-testable in `tools/tests/test_pr_status_labels.py`,
> and is derived from scratch each run, as the labeler already is. Treat **C** as a
> follow-up once B's `ci: awaiting review` state shows how often late reviews
> actually leave work behind. Reject **D** unless the plan-approval signal is
> moved off the draft flag.

If A + B is too much for one change, A alone (with the `pull_request_target`
trigger) is a safe first step: it shares no code path with B beyond `derive` and
`LABELS`.

## Success criteria (for A + B)

The work is done when each of these is shown by a unit case in
`tools/tests/test_pr_status_labels.py`, plus the trigger check:

1. A draft PR whose test run, `Test Checks` and `All Checks` all passed never
   gets `ci: ready for review` (the #993 04:11–04:25 window).
2. A non-draft, CI-green PR with a pending Copilot review request, or no Copilot
   review on the head SHA while Copilot was requested, gets
   `ci: awaiting review` (the 04:25–04:28 window).
3. A non-draft, CI-green PR with an unresolved review thread gets
   `ci: awaiting review` (the 04:28–05:33 window).
4. A non-draft, CI-green PR whose latest Copilot review is a quota notice, or on
   which Copilot was never requested, with no unresolved threads, gets
   `ci: ready for review`. The "never requested" half of this is **not yet
   implementable** from the listed data and is held by open question 8: until
   that question is decided, `requested_reviewers` cannot distinguish "never
   requested" from "requested, then the request vanished with no review", so
   this criterion's "never requested → READY" path is provisional.
5. `ci: awaiting review` gains `ci: stalled` after its `LABELS` hour limit.
6. The existing `ReadyToLaunchTest`, `LabelChangesTest` and `SummaryRowTest`
   cases still pass, so `ci: ready to launch` is unchanged.
7. `pr-status-labels.yaml` runs on `ready_for_review`, `converted_to_draft`,
   `pull_request_review` (submitted/dismissed) and `review_requested`, still
   checks out only the default branch, and runs no PR code. (Thread
   resolve/unresolve has no Actions trigger, so that case is left to the cron;
   see Option B.) `WorkflowTest` already asserts the triggers, the
   default-branch checkout and the job permissions, so its `test_triggers` case
   is extended to the new events and the other two cases still pass.

## Open questions for the owner

1. Should a draft PR show its CI stage plus a `ci: draft` marker, or a single
   `ci: draft` label that hides the stage? And should `ci: draft` be left off
   when `ci: ready to launch` already marks the PR (which implies draft)?
2. Is the label meant to say "CI is green" or "a person should look now"? B
   assumes the latter. If the former, perhaps rename it (e.g. `ci: passed`) and
   put the review gate in a separate label. Note that the `READY` entry's
   `LABELS` description today reads "Validate and full test runs passed on the
   head commit", which is the former meaning.
3. Should requested **human** reviewers also hold READY, or only Copilot?
4. Should an unresolved thread from the owner hold READY even when CI and
   Copilot are clean? (B as written says yes.)
5. Is it intended that implementation commits land on a plan PR while it is
   still a draft (as on #993)? If not, that is a separate dispatch question and
   affects how much Option A's `ci: draft` will show up.
6. Is "review draft pull requests" deliberately off in the Copilot ruleset? Turning
   it on would remove defect 2 at the source, at the cost of Copilot quota spent
   on plan drafts.
7. Is the agent-facing rule in `github_pr_labels` ("ready for review is only
   meaningful once draft is False") enough for agents in the meantime? If the
   label becomes draft-aware, that caveat can be dropped from the docstring.
8. When a non-draft, CI-green PR has no Copilot review on the head SHA and no
   pending Copilot request, should it read READY or `ci: awaiting review`?
   `requested_reviewers` cannot tell "never requested" from "requested, then the
   request disappeared with no review" (see Option B's failure modes). Resolving
   this either adds a durable request-history source to `gather` (the
   issue-timeline review-request events) or fixes an explicit rule for the
   ambiguous case; success criterion 4 is written assuming the latter is
   answered.
