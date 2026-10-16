# Plan: QA Retrospective Job

**Tracker task:** `492ba96e-2638-482a-b143-b757e56f2fac`, "Add a QA retrospective job that
studies recent automated work and proposes how the QA process itself should improve"
**Release:** Common 0.76
**Branch:** `project/task-20261014-023520`

---

## Motivation

`master-agent-dispatch.yaml` runs six automated QA rounds: `doc-qa`, `defect-hunt`,
`coverage-qa`, `consolidation-qa`, `performance-qa` and `pdsl-qa`. Each is gated by
`tools/ci/qa-cadence.sh` and driven by a prompt in `tools/ci/prompts/`. Each one can only
improve on problems we already know about, and only in the ways we already decided to care
about. Nothing looks back at what these rounds actually did, so the process never learns
from them:

- whether their PRs merged or were closed;
- how many review and auto-resolve cycles they took;
- where the agents stalled, looped or failed;
- what they never looked at.

This task adds a seventh round, a **retrospective**, to close that loop. Every run studies
recent automated work in this repository and proposes, with evidence, how to improve:

- the agent harness;
- the existing QA jobs;
- the set of QA jobs itself.

A person decides whether to act on each proposal.

## Scope

In scope:

1. A new `qa-retrospective` job in `.github/workflows/master-agent-dispatch.yaml`, built
   like the other six QA rounds.
2. A prompt template `tools/ci/prompts/retrospective.txt` and its builder
   `tools/ci/prompts/build-retrospective-prompt.sh`.
3. A durable record of each run (the survey ledger, below) that the next run reads.
4. Tests in `tools/tests/` covering the builder and the workflow wiring.
5. Documentation updates:
   - `.github/CLAUDE.md`, "What the `Master Agent Dispatch` workflow does";
   - `tools/ci/README.md`;
   - the workflow header comment.
6. The branch convention for QA work whose subject is the pipeline (`ci/qa-…`). The
   retrospective's prompt uses this convention and the docs above describe it. No
   enforcement code changes, because the convention needs none (see Approach §6).

Out of scope, named here so the decision is visible:

- **Narrowing what a job token can read.** The ar-manager scoping gap noted under Open
  questions belongs to a separate task.
- **The per-job `retrospective` phase** in `docs/plans/SELF_IMPROVEMENT_PHASE.md`. That
  design studies one job's transcript right after the job finishes. This job studies many
  workstreams across two weeks. The two complement each other and share no code.
- **Carrying out any proposal the retrospective makes.** Its own rule is that it only
  proposes.

## Approach

### 1. The dispatch job

Add `qa-retrospective` to `master-agent-dispatch.yaml` with the same step sequence as
`defect-hunt` and `pdsl-qa`:

1. Check out the code.
2. **Automated intake** (`tools/ci/automated-intake.sh`, step id `intake`).
3. **Cadence gate** (`qa-cadence.sh`, step id `decide`, `if: steps.intake.outputs.open == 'true'`).
4. Archive the previous rounds (`archive-stale-workstreams.sh`, guarded by
   `reason != 'forced'`).
5. Create the branch.
6. Register the workstream (`register-workstream.sh`).
7. Build the prompt.
8. Submit the job (`submit-agent-job.sh`).
9. Write the summary.

Settings:

| Setting | Value | Reason |
|---|---|---|
| `BRANCH_PREFIX` (job-level env) | `qa/retro-` | Satisfies `test_every_qa_job_has_its_own_branch_prefix` (unique, starts with `qa/`, ends with `-`). It also keeps the round visible to every existing `qa/` consumer. |
| `MIN_INTERVAL_DAYS` | `7` | Two weeks of window at weekly cadence gives every activity two looks. The second look shows whether a proposal from the earlier run was acted on. At 2 days most of the window would be the retrospective's own previous round. |
| `PR_GRACE_HOURS` | `24` | Same as every QA gate. |
| `IGNORE_INTERVAL` | new `ignore_interval_retrospective` dispatch input | Required by `test_every_qa_job_has_its_own_interval_override`. |
| `agent` selector | new `retrospective` option | Required by `test_every_job_can_be_dispatched_on_its_own`. |
| `concurrency.group` | `qa-retrospective`, not cancelling | Each job serializes under its own group. |
| `PROTECT_TEST_FILES` | `"true"` | The round is docs-only. Locking tests costs nothing and removes a temptation. |
| `AUTO_CREATE_PR` | `"true"` | Same as every QA round. |
| `PHASE_CONFIGS` | `{"primary": {"runner": "claude", "model": "opus", "effort": "max", "provider": "anthropic"}}` | The judgment here is synthesis across many sources, the kind a model under-thinks, the same reason `performance-qa` and `pdsl-qa` carry this pin. (`defect-hunt` and `consolidation-qa` pin `opus` but not `effort: max`.) |
| `REQUIRED_LABELS` | none | The job reads tools and writes markdown. It needs no Metal. |

**The open-PR ceiling.** The retrospective gets the same ceiling as the other QA rounds:
`qa-cadence.sh` condition 1 (one open `qa/retro-*` PR holds the next round off) plus the
grace window. The repository-wide `MAX_OPEN_PRS` backlog check (`open-pr-backlog.sh`)
applies only to the planning rounds today, and the QA rounds do not use it. Adding it here
would make the retrospective the only QA job held off by the backlog. The Decisions section
records the choice not to (Q4) rather than leaving it decided silently.

`MAX_OPEN_PRS` in the planning jobs is documented as "6, the number of automated QA rounds".
It becomes 7 in the same change, and the comment and `.github/CLAUDE.md` are updated with
it.

### 2. The prompt (`tools/ci/prompts/retrospective.txt`)

The builder follows the recurring-round pattern of `build-defect-hunt-prompt.sh` and
`build-doc-qa-prompt.sh`: it validates its required variables, reads a single `TEMPLATE`,
and substitutes them with an inline `sed`. It carries **no** `pr-feedback.txt` fragment.
That review-feedback policy is patched in only by the auto-review and auto-resolve builders
(the `_AUTO_RESOLVE_BUILDERS` set in `tools/tests/test_prompt_builders.py`), which run
against a branch that may already have a pull request. Like every round dispatched from
master, the retrospective starts on a fresh branch with no PR, so it is not held to that —
when its branch later gets a docs-review PR, those remediation jobs carry the policy
themselves. It requires these variables:

- `BRANCH`, `BASE_BRANCH`;
- `REPO_URL`: the only repository the survey may cover;
- `REVIEW_SINCE`: a `git --since` style window, `14 days ago` by default. This reuses
  `doc-qa`'s `REVIEW_SINCE` variable — a `git --since` expression bounding the window —
  but not its value: `doc-qa` passes `1 week ago`, and the retrospective passes a wider
  window so every activity gets two looks (see the `MIN_INTERVAL_DAYS` rationale above);
- `QA_PREFIXES`: the seven `qa/` prefixes — the six existing rounds plus the
  retrospective's own `qa/retro-` — plus `ci/qa-`. The workflow passes this list from one
  place, so the prompt never hard-codes a stale list.

The prompt tells the agent to:

1. **Read its own history first.** Read the survey ledger (§4) and the newest previous
   `docs/qa/retrospectives/` report:
   - Each proposal recorded there gets a status: acted on, rejected, or still open.
   - A proposal still open after two runs is escalated in the new report, not restated.
2. **Survey the QA workstreams of `REPO_URL` since `REVIEW_SINCE`.** Every branch matching
   `QA_PREFIXES`, archived ones included. Sources:
   - `workstream_list` with `include_archived=true`, filtered client-side to `REPO_URL`;
   - `workstream_context` with `repo_url` + `branch`, which gives memories, commits, the
     PR and the job timeline. The timeline comes from the controller's
     `GET /api/workstreams/{id}/jobs`;
   - `workstream_get_job` for the detail of failed or stalled jobs;
   - `memory_recall` with `scope="repo"`;
   - `github_pr_find`, `github_pr_reviews`, `github_pr_review_comments` (with
     `include_resolved=true`) and `github_pr_conversation`;
   - `github_list_workflow_runs`, `github_workflow_run_status` and `github_job_logs` for
     CI results.
3. **Survey the non-QA workstreams of the same repository** (`feature/`, `project/plan-`,
   `project/task-`, and the auto-review and auto-resolve jobs on them). The goal is
   aggregate shape: where effort, cost and failures concentrate. This feeds finding
   category 3, new kinds of QA job.
4. **Produce findings in the task's three categories:**
   1. the FlowTree agent harness;
   2. the existing QA jobs;
   3. new QA job kinds.

   Every finding cites at least one checkable item: a workstream id or branch, a job id,
   a PR number, a commit SHA or a memory id. A finding without evidence is dropped, not
   softened.
5. **Mark every proposal by where it would land:**
   - `ci/qa-…` branch: anything under `.github/workflows/`, `tools/ci/` (prompts,
     builders, gates) or `.claude/hooks/`;
   - ordinary branch: harness Java under `flowtree/`, MCP tools under `tools/mcp/`, or
     documentation;
   - person-only: secrets, runner fleet, controller configuration.
6. **Write only the report, the ledger and nothing else** (see Output and safety).

The prompt also carries the safety rules as hard constraints:

- **Repository scope.** Survey only workstreams whose `repoUrl` equals `REPO_URL`. Other
  repositories' workstreams are reachable with this job's token (see Decisions, Q5) and
  must not be read, quoted or counted.
- **Public repository.** The report must not name private downstream products or quote
  their workstreams. The wording follows the root `CLAUDE.md` section "This Repository Is
  Public".
- **Nothing implemented.** No edits to the harness, the prompts, the workflows or the
  tests.

### 3. Output and safety

Each run produces one docs-only branch `qa/retro-<stamp>`. It holds:

- `docs/qa/retrospectives/RETRO-<YYYYMMDD>.md`, the findings report. Sections:
  - **Window and sources:** what was surveyed and how many workstreams, jobs and PRs.
  - **Status of previous proposals.**
  - **Findings by category:** each with evidence, impact, a proposal and its landing branch.
  - **Proposals ranked.**
- An appended row in the survey ledger (§4).

The report lives under `docs/qa/` rather than `docs/plans/`, which is a deliberate
departure from the task's suggested default:

- `docs/plans/` is for plans that are approved and then implemented on the same branch.
  The `verify-completion.txt` prompt implements whatever plan a docs-only branch carries.
  A retrospective report is a list of independent proposals, not one plan, and must never
  be implemented as a unit.
- Under `docs/`, the branch still takes the docs-only review route (`docs-review.txt`) in
  `analysis.yaml`'s `auto-review`, so it gets reviewed like any plan.
- `docs/qa/` already exists and already holds QA output of exactly this kind:
  `docs/qa/qa_report.md` and `docs/qa/qa_tracking.csv` (both tracked on `master`) are a
  prior documentation-QA report and its tracking table. A retrospective report and its
  ledger are the same kind of durable, reviewed record, so `docs/qa/retrospectives/`
  extends an established convention rather than inventing a location.
- `docs/CLAUDE.md` reserves the `docs/` tree for "formal documentation only" and sends
  temporary planning and investigation notes to `docs/plans/`. A retrospective report is
  neither: it is a durable, reviewed record of what was surveyed and found, not a
  work-in-progress plan to be superseded and deleted — which is also precisely why it must
  not sit in `docs/plans/`, where Verify Completion would try to implement it. The existing
  `docs/qa/` assets show the project already treats that directory as the home for this
  kind of record.

A proposal a person accepts becomes its own unit of work. A person can file it as a
tracker task, or dispatch it on a branch named per §6. The job itself files nothing
(Decisions, Q3).

### 4. The job's own record (survey ledger)

`docs/qa/retrospectives/ledger.tsv` gets one row per run, appended by the agent. This is
the same pattern as coverage-qa's `tools/coverage-data/coverage-history.tsv`, and it lives
outside `tools/ci/` for the same reason: the CI-file lock would drop it. Columns:

| Column | Content |
|---|---|
| `run_utc` | When the run happened |
| `branch` | The run's branch |
| `window_since` | Start of the survey window |
| `workstreams_surveyed` | How many workstreams were surveyed |
| `jobs_surveyed` | How many jobs were surveyed |
| `prs_surveyed` | How many PRs were surveyed |
| `newest_job_seen` | The newest job timestamp seen |
| `proposal_ids` | Short slugs of this run's proposals |
| `report_path` | Path of this run's report |

The next run uses `newest_job_seen` to tell old activity from new. It uses `proposal_ids`
to track each proposal's status across runs. A ledger the builder validates nothing about
is fine: it is advisory input to the agent, not a gate.

The agent also stores one `memory_store` entry per run, in namespace `qa-retrospective`,
summarising the proposals. That entry makes them findable through `consult` and
`memory_recall` by sessions that never open the report.

### 5. Tool access

Investigated against the code; no change is needed for the job to work:

- Every tool the survey needs is already in the agent allowlist:
  - `workstream_list`, `workstream_context`, `workstream_get_job`, `workstream_get_status`;
  - `memory_recall`, `memory_store`;
  - every `github_pr_*` tool, `github_list_workflow_runs`, `github_workflow_run_status`
    and `github_job_logs`.

  The allowlist is `McpConfigBuilder.AR_MANAGER_TOOL_NAMES`
  (`flowtree/runtime/.../jobs/McpConfigBuilder.java`), mirrored in
  `tools/mcp/manager/tool_capabilities.py`. None of these tools is in
  `EXCLUDED_AR_MANAGER_TOOLS`.
- `workstream_list` passes `include_archived=True` through to the controller
  (`workstream_tools.py`).
- `workstream_context` accepts any `repo_url` + `branch` and returns its job timeline.
- A job token's scopes are `read, write, submit, github, memory-read, memory-write`
  (`auth.py`). Its only restriction is by workspace, and nothing limits it to the job's own
  workstream.
- No MCP tool lists every job of a workstream. Only `workstream_context` exposes the
  controller's `GET /api/workstreams/{id}/jobs`, through its `job_limit` parameter, and the
  prompt tells the agent to pass a high `job_limit`.

The workstream needs no tracker capability: the job files nothing (Decisions, Q3). Should
that change, `tracker_file_follow_up_task` is already granted to every job — it creates
backlog-stage, medium-priority, unlinked tasks with server-enforced limits.

### 6. Branch convention for pipeline-subject QA work: `ci/qa-<job>-<stamp>`

Recommendation: **`ci/qa-…`**, for example `ci/qa-retro-20261021-093000-cadence`.

What already happens for a `ci/qa-…` branch:

- **The existing `ci/` exemptions accept it unchanged.** Every check that exempts pipeline
  work matches a start-anchored `ci/`:
  - `CI_BRANCH_PATTERN='^ci/'` in `check-ci-file-lock.sh`;
  - `isCiBranch` / `startsWith("ci/")` in `GitCommitHandler`;
  - the enforcement-tampering and checkstyle-integrity checks in `analysis.yaml`;
  - the exfiltration guard's base comparison (`verify-exfiltration-guard.sh`);
  - `deception-audit.sh`;
  - the `register-workstream.sh` integrity step.
- **None of the `qa/` consumers sees it:**
  - `qa-cadence.sh` (open PRs, grace window, interval);
  - `archive-stale-workstreams.sh`;
  - the `qa/coverage-` assertion-density step in `analysis.yaml`;
  - `test_every_qa_job_has_its_own_branch_prefix`.

  That is the correct outcome, not a loss. Such a branch is follow-up work, not a QA
  round, so it must not hold off the next round of any QA job or be archived by one.
- **Routing.** `auto-review` and `auto-resolve` routing, `verify-completion.yaml` and
  `open-pr-backlog.sh` have no prefix logic at all, so the branch is reviewed and resolved
  like any other.

Alternatives:

- **`qa/ci/…` (rejected).** It would need a second pipeline prefix in every check listed
  above, in the workflows, the harness Java and the agent-protection scripts. That is a
  change to the enforcement layer itself, whose only benefit is a sort order. The
  task asks for that to be argued explicitly, and the argument does not hold up.
- **Plain `ci/…` (rejected).** It loses the signal that the work came from QA, which the
  retrospective's own survey needs to find its follow-ups.

The one thing that must know about the new prefix is **the retrospective's survey**. It
gets `ci/qa-` in `QA_PREFIXES`, so pipeline-subject follow-ups are tracked across runs.

**Who creates such a branch: a person.** The retrospective names the proposals that need
one. A person who accepts a proposal creates `ci/qa-<job>-<stamp>-<slug>` and dispatches
the work on it.

A job cannot do it. Its token cannot push to a branch other than its own, and an agent
creating CI-capable branches would defeat the purpose of the lock.

### 7. How this implementation lands

The implementation edits `.github/workflows/master-agent-dispatch.yaml`, `tools/ci/prompts/`
and `tools/tests/`. The first two are under the CI-file lock, which the plan's own
`project/task-` branch cannot lift by its name. The work therefore splits in two: the
design (this document and the unlocked docs) lands on the plan branch and merges, and the
CI-locked parts land afterward on a branch named `ci/…`, whose name lifts the lock for the
whole pipeline (Q1). That branch is cut once the plan PR has merged, so its PR carries only
the implementation.

A branch name alone lifts the *lock*, but a commit that touches a CI file still needs the
harness to stage that file: the `ci/…` round is dispatched with a controller-signed
`Sensitive-File-Bypass`, which `check-ci-file-lock.sh`, `GitCommitHandler` and the harness's
staging filter all accept. Every commit that touches a CI file carries its own trailer, so a
review or resolve job that later edits those files is submitted the same way;
`verify-completion.yaml` has no input for this, so the implementation is dispatched through
`workstream_submit_task`, not through Verify Completion.

The alternative — a controller-signed bypass on the plan's own `project/task-` branch — would
have kept the design and the implementation on one branch and PR. It was not taken: keeping
the CI-locked change on its own `ci/…` PR keeps the design reviewable on its own.

The new and edited files:

| File | Change |
|---|---|
| `.github/workflows/master-agent-dispatch.yaml` | the `qa-retrospective` job, the `retrospective` selector, the `ignore_interval_retrospective` input, the header comment, `MAX_OPEN_PRS` 6→7 |
| `tools/ci/prompts/retrospective.txt` | new template |
| `tools/ci/prompts/build-retrospective-prompt.sh` | new builder |
| `tools/tests/test_master_agent_dispatch.py` | add `"qa-retrospective": 7` to `test_qa_intervals_match_the_documented_cadence`'s expected map (an addition, not a weakening); new test methods for the retrospective's settings |
| `docs/qa/retrospectives/ledger.tsv` | header row only |
| `docs/qa/retrospectives/README.md` | what the directory is, and that its proposals are not plans |
| `.github/CLAUDE.md`, `tools/ci/README.md` | the seventh round, the `ci/qa-` convention, `MAX_OPEN_PRS` |

### 7a. What landed where

The work landed across the two branches §7 describes.

On the plan branch — everything **not** under the CI-file lock, which stays
consistent without the locked workflow:

- `docs/qa/retrospectives/README.md` — what the directory is and that its reports
  are not plans.
- `docs/qa/retrospectives/ledger.tsv` — the ledger header row.

On the `ci/…` branch — everything that **is** locked, or must move in lockstep
with the locked workflow, in one change:

1. `build-retrospective-prompt.sh` and `retrospective.txt` in `tools/ci/prompts/`;
2. the five `master-agent-dispatch.yaml` edits (new job, new
   `ignore_interval_retrospective` input, new `retrospective` selector option,
   `MAX_OPEN_PRS` 6→7 on both planning jobs, header comment);
3. `test_master_agent_dispatch.py` in `tools/tests/` (the cadence-map addition,
   the two `MAX_OPEN_PRS` assertion bumps, and the two new test methods) — not
   locked, but it must move with the workflow, since the cadence-map addition and
   the `MAX_OPEN_PRS` assertions fail until the job exists and the bump lands;
4. the two `tools/ci/README.md` prompt-table rows and the `.github/CLAUDE.md`
   "seventh round" edits.

The locked parts were first staged verbatim beside this plan and validated together
(the new job against every per-QA-job check in `test_master_agent_dispatch.py`, the
builder against the full `test_prompt_builders.py` contract, and the workflow against
`test_automated_intake.py`'s wiring checks) so the `ci/…` branch could move them into
place with no re-derivation; the staging copy was removed once its contents were in
place, being scaffolding rather than a durable asset.

## Success criteria

1. **Prompt builder tests.** `tools/tests/test_prompt_builders.py` discovers
   `build-retrospective-prompt.sh` automatically (`_builders()` picks up every
   `build-*.sh`). It must pass the checks that run against every builder:
   - required variables declared and documented;
   - no placeholder left in the output;
   - every template placeholder a required variable;
   - a missing or empty variable refused (unset and empty alike).

   The `pr-feedback.txt` and sed-metacharacter checks in that file are scoped to
   `_AUTO_RESOLVE_BUILDERS` and do not apply to the retrospective: it is a recurring round
   on a fresh branch, not a remediation builder, so it carries no `pr-feedback.txt`
   fragment and renders through an inline `sed` like the other recurring rounds. A
   `qa/retro-…` branch name contains no `sed` metacharacters.
2. **Workflow wiring tests**, passing with the new job:
   - in `test_automated_intake.py`, intake before the cadence gate, nothing created
     before the gate, and the summary reporting the intake decision;
   - in `test_master_agent_dispatch.py`, the existing per-QA-job checks: shared round
     scripts, own prefix, interval override, grace window, gated steps, no archive on a
     forced run, tunnel, concurrency group, dispatchable alone.
3. **New test methods** in `test_master_agent_dispatch.py` pin that:
   - the retrospective's primary phase is opus at max effort;
   - its tests are locked;
   - it carries no `REQUIRED_LABELS`;
   - `QA_PREFIXES`, as passed to the builder, covers every QA job's `BRANCH_PREFIX`
     plus `ci/qa-`, so a future eighth QA job cannot drop out of the survey.
4. **Each test is run** as its own `pytest` node, and CI's `python-tests` is green.
5. **First run.** A run dispatched by hand (`agent: retrospective`) produces a
   `qa/retro-…` PR whose report:
   - cites checkable evidence for every finding;
   - covers only this repository;
   - names no private product;
   - labels each proposal's landing branch;
   - appends one ledger row.

   A person reviews that report before the job is left to its cadence.

## Dependencies

- None from the tracker. The task has no blockers.
- The implementation needs the operator-signed bypass or a `ci/` branch (§7).

## Decisions

Each question the design raised was resolved by adopting the plan's own proposal.

1. **How the implementation lands (§7).** The CI-locked parts land on a branch named
   `ci/…`, cut after the plan PR merged, so the design and the implementation stay on
   separate reviewable PRs. The single-branch signed-bypass route was the alternative and
   was not taken.
2. **Report location.** Reports go in `docs/qa/retrospectives/` (§3). `docs/qa/` already
   holds QA output on `master` (`qa_report.md`, `qa_tracking.csv`), so the choice extends
   an existing convention, and `docs/CLAUDE.md`'s "formal documentation only" rule is
   satisfied because a retrospective report is a durable reviewed record rather than a
   temporary planning note. `docs/plans/` is rejected: Verify Completion would treat a
   report there as a plan to implement.
3. **Filing proposals as tracker tasks.** The job does not file them. For the first runs
   the report is the only output, and the person reviewing it files what they accept. This
   is revisited once a few reports have been judged; `tracker_file_follow_up_task` is
   already granted if that changes.
4. **Backlog ceiling.** The retrospective is not held off by `open-pr-backlog.sh`
   (`MAX_OPEN_PRS`), for consistency with the other QA rounds — none of which uses it. A
   backlog is itself something the retrospective should report on.
5. **Repository scoping stays advisory, not enforced.** A job token can read any
   workstream, memory or PR in its workspace, including other repositories'; in legacy
   single-workspace mode it can read everything. The prompt restricts the survey to
   `REPO_URL`, but nothing enforces it, and this plan does not widen into enforcing it.
   The gap is noted and nothing is filed: whether to scope job tokens — including that
   `workstream_context` with `repo_url`, `branch` and `workstream_id` all supplied skips
   the workspace check on its job listing — is a separate decision for a person.
6. **Cadence.** 7 days with a 14-day window, so most activity is looked at twice across
   runs. A slower cadence (14 days, 21-day window) remains available should weekly reports
   prove repetitive.

## Estimated complexity

**Medium.** The job, builder and tests follow existing patterns closely; the work is in
writing a prompt that produces evidence-backed findings rather than impressions, and in
reviewing the first report. One change; no split needed.
