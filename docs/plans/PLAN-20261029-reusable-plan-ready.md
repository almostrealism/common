---
status: proposed
created: 2026-10-29
closed:
tracker: [c87ae404-85c9-4a15-b8c4-306d73326e24]
---

# Make Plan Ready a reusable workflow

**Tracker task:** [c87ae404-85c9-4a15-b8c4-306d73326e24] — *Make Plan Ready
(ready-plan.yaml) a reusable workflow so other repositories start
implementation when a plan PR is marked ready for review*
**Release:** Common 0.76

## Motivation

Goal-driven release automation is already reusable at both ends. A consuming
repository can call `task-planning.yaml` to open plan PRs as drafts, and its own
`verify-completion.yaml` can call `implement-plan.yaml` to carry a plan out. The
step *between* those two is not reusable.

In this repository, marking a draft plan PR **ready for review** is how the
owner approves it, and `.github/workflows/ready-plan.yaml` (Plan Ready) turns
that transition into a `workflow_dispatch` of `verify-completion.yaml` on the
PR's head branch. Plan Ready is a plain `pull_request_target` workflow, not a
reusable one, so a consuming repository has no equivalent trigger: marking its
plan PR ready does nothing, and someone has to dispatch Verify Completion by
hand. `implement-plan.yaml`'s own header says exactly this — *"a consumer
without that workflow dispatches the caller by hand."* A consuming repository
that has already adopted `verify-completion.yaml` → `implement-plan.yaml@master`
therefore never implements its plans on ready-for-review.

Extracting Plan Ready into a `workflow_call` workflow closes that gap and makes
this repository run the same code its consumers run — the pattern already
established for decomposition, planning, implementation and docs-only review.

## Scope

Concretely, what will change (all under `.github/workflows/`, `tools/tests/`
and documentation — no application code):

1. **New reusable workflow `/.github/workflows/plan-ready.yaml`** (`workflow_call`).
   It holds today's `dispatch` job — the approver check, the docs-only check,
   the dispatch of the implementation workflow, and the step summary — unchanged
   in behaviour. The three values hard-coded today become inputs with
   this-repository defaults:
   - `docs-directory` (default `docs/`) — the prefix the docs-only rule requires
     every changed file to be under, matching `analysis.yaml`'s docs-only rule;
   - `dispatch-workflow` (default `verify-completion.yaml`) — the workflow file
     dispatched on the PR's head branch;
   - `approvers` (default `""`) — the approver allowlist, passed in by the caller
     from its own `vars.PLAN_APPROVERS`. An empty value approves no one.
2. **`/.github/workflows/ready-plan.yaml` becomes a thin `pull_request_target`
   caller** of `plan-ready.yaml`. It keeps the trigger
   (`types: [ready_for_review]`, `branches: [master]`), the per-head-branch
   concurrency group, and the caller-side `permissions`, and passes
   `approvers: ${{ vars.PLAN_APPROVERS }}`. The `docs-directory` and
   `dispatch-workflow` inputs are left at their defaults, so behaviour here is
   identical to today.
3. **Header documentation on `plan-ready.yaml`** showing a complete consumer
   caller, the `PLAN_APPROVERS` setup, and the merge-to-default-branch caveat.
4. **Documentation updates** in `.github/CLAUDE.md`, `tools/ci/README.md`,
   `implement-plan.yaml`'s header, and `flowtree/runtime/docs/ci-integration.md`.
5. **Tests** in `tools/tests/test_reusable_workflows.py` and
   `tools/tests/test_ready_plan_workflow.py`.

Out of scope (named as dependencies / open questions, not widened into here):

- **The consuming repository's caller file.** It is added in *that* repository,
  from the header's example, once this merges. The completion notes must say so.
- **The `pull_request` → `pull_request_target` token exposure** for workflow
  edits (tracked separately in the ar-manager tracker, *"Keep FlowTree
  controller credentials out of pull_request workflow runs"*). This task neither
  introduces nor closes it; Plan Ready carries no controller secret at all.

## Approach

### The reusable workflow

`plan-ready.yaml` is triggered only by `workflow_call`, like the other four
reusable workflows. Its single job keeps the name `dispatch` and the current
four steps verbatim, with only the hard-coded values swapped for inputs read
through `env:`:

```yaml
on:
  workflow_call:
    inputs:
      docs-directory:
        description: "Dispatch only while every changed file is under this directory"
        required: false
        default: "docs/"
        type: string
      dispatch-workflow:
        description: "Workflow file dispatched on the PR's head branch to start implementation"
        required: false
        default: "verify-completion.yaml"
        type: string
      approvers:
        description: "Space/comma/newline-separated GitHub logins allowed to approve a plan; empty approves no one"
        required: false
        default: ""
        type: string
```

The job body is the current one with three substitutions, each keeping the
"event data reaches the shell only through `env`" rule:

- the approver step reads `APPROVERS: ${{ inputs.approvers }}` instead of
  `${{ vars.PLAN_APPROVERS }}` (the caller supplies the variable);
- the docs-only step reads `DOCS_DIRECTORY: ${{ inputs.docs-directory }}` and
  filters with it (e.g. `grep -v "^${DOCS_DIRECTORY%/}/"`), rather than the
  literal `grep -v '^docs/'`;
- the dispatch step reads `DISPATCH_WORKFLOW: ${{ inputs.dispatch-workflow }}`
  and posts to `repos/${REPO}/actions/workflows/${DISPATCH_WORKFLOW}/dispatches`.

`REPO`, `SENDER`, `HEAD_BRANCH`, `PR_NUMBER` and `GH_TOKEN` keep coming from
`github.repository`, `github.event.sender.login`,
`github.event.pull_request.head.ref`, `github.event.pull_request.number` and
`secrets.GITHUB_TOKEN` — which, inside a called workflow, are all the caller's
(see *GitHub platform findings* below). The fork guard stays on the called job:

```yaml
jobs:
  dispatch:
    if: github.event.pull_request.head.repo.full_name == github.repository
    runs-on: ubuntu-latest
    permissions:
      actions: write
      pull-requests: read
      contents: read
```

### The caller in this repository

`ready-plan.yaml` keeps the trigger, concurrency and permissions and becomes a
one-job caller:

```yaml
on:
  pull_request_target:
    types: [ready_for_review]
    branches: [master]

concurrency:
  group: ready-plan-${{ github.event.pull_request.head.ref }}
  cancel-in-progress: false

jobs:
  dispatch:
    uses: ./.github/workflows/plan-ready.yaml
    permissions:
      actions: write
      pull-requests: read
      contents: read
    with:
      approvers: ${{ vars.PLAN_APPROVERS }}
```

`docs-directory` and `dispatch-workflow` are left at their defaults, so this
repository behaves exactly as before. This mirrors how `verify-completion.yaml`
calls `implement-plan.yaml` and `plan-release-task` calls `task-planning.yaml`.

### The consumer caller (documented, not added here)

The header shows the complete caller a consuming repository adds to its own
`.github/workflows/`:

```yaml
on:
  pull_request_target:
    types: [ready_for_review]
    branches: [main]          # its default branch
concurrency:
  group: plan-ready-${{ github.event.pull_request.head.ref }}
  cancel-in-progress: false
jobs:
  dispatch:
    uses: almostrealism/common/.github/workflows/plan-ready.yaml@master
    permissions:
      actions: write
      pull-requests: read
      contents: read
    with:
      approvers: ${{ vars.PLAN_APPROVERS }}
      # dispatch-workflow / docs-directory only if they differ from the defaults
```

The header also states that the consumer sets `PLAN_APPROVERS` as a repository
variable, and that the caller only takes effect once merged to the consuming
repository's default branch, because `pull_request_target` runs the base
branch's copy of the workflow.

### How the trust properties are kept

- **Nothing from the PR is checked out or run.** Plan Ready's job is inline
  shell over event metadata and GitHub API responses. Unlike the other four
  reusable workflows, it needs **no** framework checkout at all, so there is no
  `framework-repository`/`framework-ref` input and no `ref:` anywhere — and
  therefore no way to point a checkout at PR code. If a future change ever needs
  the framework's scripts, it must follow the established rule (explicit
  framework checkout at `framework-ref`, `persist-credentials: false`, inputs
  into `run:` only through `env:`), but this task adds no checkout.
- **No controller secret.** Plan Ready dispatches through `GITHUB_TOKEN`
  (automatically available to a called workflow) and the GitHub API; it holds
  no Cloudflare Access service token. So `plan-ready.yaml` has **no `secrets:`
  block** — another way it differs from the other reusables.
- **Fork guard retained** on the called job; it still works because
  `github.repository` and `github.event` are the caller's inside a called
  workflow.
- **Separation retained.** Plan Ready stays in its own file and never sits
  beside jobs that build PR code, as `.github/CLAUDE.md` requires for a
  `pull_request_target` workflow.
- **`analysis.yaml` still does not react to `ready_for_review`** — unchanged by
  this task (its `pull_request` trigger uses the default types).

## GitHub platform findings (reusable workflow called from a `pull_request_target` caller)

These govern whether the extraction is behaviour-preserving. They are from
GitHub Actions documentation, *"Reuse workflows"*
(`docs.github.com/en/actions/.../reuse-workflows`); live web verification was
unavailable in this planning environment, so each is corroborated by a
precedent already running in this repository. **The implementer should confirm
each against the live doc page before relying on it.**

- **`github` context is the caller's.** Inside a called workflow the `github`
  context is associated with the *caller* workflow, so `github.event_name`
  (`pull_request_target`), `github.event.pull_request.*`,
  `github.event.sender.login`, `github.repository`, `github.sha` and
  `github.ref` are all the caller's values. This is why the fork guard
  `github.event.pull_request.head.repo.full_name == github.repository` keeps
  working unchanged, and why the approver/docs-only/dispatch steps keep reading
  the same event fields. *Precedent:* `implement-plan.yaml`,
  `task-planning.yaml` and `goal-decomposition.yaml` all read
  `github.event.repository.default_branch`, `github.ref_name` and `github.sha`
  inside `workflow_call` jobs driven by the caller's event; the task
  description itself states the fork expression still holds.
- **`GITHUB_TOKEN` permissions are the caller's, and can only be reduced.** The
  token available to a called workflow is capped by the permissions the caller
  grants; a called job may declare its own (equal or more restrictive)
  `permissions:` but cannot widen them. So the caller must grant
  `actions: write` (plus `pull-requests: read`, `contents: read`), and the
  called job declares the same for hygiene. Under `pull_request_target` the
  caller runs in the base-repository context where that write grant is allowed.
- **`GITHUB_TOKEN` is automatically available** to a called workflow — it is not
  passed as a secret. So `secrets.GITHUB_TOKEN` in the dispatch and files steps
  resolves with no `secrets:` declaration and no `secrets: inherit`.
- **`vars` context is available in a called workflow and reads the caller
  repository's variables.** Technically `vars.PLAN_APPROVERS` would resolve to
  the caller's repository variable even if read implicitly inside
  `plan-ready.yaml`. The design nevertheless passes it as the explicit
  `approvers` input, so the reusable workflow is self-describing, is not coupled
  to a particular variable *name* in the caller, and keeps the "a called
  workflow should not read the caller's variables implicitly" contract. The
  empty-approves-no-one rule is preserved regardless of how the caller sets the
  variable.

## Success criteria

A reviewer can confirm the task is done when:

- `plan-ready.yaml` exists as a `workflow_call` workflow whose header shows a
  complete consumer caller and the `PLAN_APPROVERS` setup, with the three inputs
  (`docs-directory`, `dispatch-workflow`, `approvers`) defaulting to this
  repository's values.
- `ready-plan.yaml` is a thin `pull_request_target` caller of it and behaves
  exactly as before (same trigger, concurrency, permissions; approver,
  docs-only and dispatch logic unchanged).
- **Tests prove it:**
  - `tools/tests/test_reusable_workflows.py` pins Plan Ready's trust rule in a
    dedicated test (loading `plan-ready.yaml` directly, **not** by adding it to
    the `_REUSABLE` tuple — see the open question below):
    - no step checks out any code (no `actions/checkout`);
    - no attacker-influenced input or event field is interpolated into a `run:`
      body (`inputs.approvers`, `github.event.pull_request.head.ref`,
      `github.event.sender.login` reach shell only via `env:`);
    - the fork guard is present on the `dispatch` job's `if:`;
    - an empty approver list dispatches nothing — ideally by running the
      approver step's shell for real with `APPROVERS=""` and asserting
      `approved=false`, following the existing `ImplementPlanDetectStepTests` /
      `PlanReviewDocsCheckTests` "run the step for real" precedent.
  - `tools/tests/test_ready_plan_workflow.py` is updated to follow the caller's
    local `uses:` into `plan-ready.yaml` and reassert the behavioural
    properties there (triggers only on `ready_for_review` against `master`;
    `actions: write`; approver gate reads the allowlist; docs-only gate runs
    only for an approver and reads the PR files API; dispatch only when approved
    and still docs-only; event fields reach shell only through `env:`). This is
    the same "resolve a `uses:` caller into the called workflow's job" pattern
    `test_master_agent_dispatch.py` / `test_verify_completion_workflow.py`
    already use. The existing test must not simply be deleted — `ready-plan.yaml`
    is a base-branch test subject, so the behaviours it pins are carried into
    the called workflow, not dropped.
- **Documentation describes the new workflow:**
  - `.github/CLAUDE.md` — *"What the `Plan Ready` workflow does"* (now a reusable
    workflow with a caller) and *"The other reusable workflows"* (add Plan Ready
    to that set, noting it carries no framework checkout and no controller
    secret);
  - `tools/ci/README.md` — the *"Reusable workflows"* table gains a `plan-ready.yaml`
    row;
  - `implement-plan.yaml`'s header — the *"a consumer without that workflow
    dispatches the caller by hand"* sentence is updated to point at the reusable
    Plan Ready a consumer now calls;
  - `flowtree/runtime/docs/ci-integration.md` — its Verify-Completion section
    describes how implementation is triggered (ready-for-review and manual
    dispatch), so it should name the reusable Plan Ready path for consumers.

## Dependencies

- None blocking: the tracker task has no `blocked_by` entries. It is the
  final piece beside the already-merged reusable `task-planning.yaml`,
  `implement-plan.yaml` and `plan-review.yaml` (PR #706, commit e187e0339),
  which established the pattern this follows.
- The work must stay on this `ci/...` branch, because it changes
  `.github/workflows/` (the CI-file lock). This planning round's own change is
  only this document.

## Open questions

1. **Workflow file name.** The plan proposes `plan-ready.yaml` for the reusable
   workflow, keeping `ready-plan.yaml` as the caller (the task lists
   `plan-ready.yaml` as an example and leaves the choice open). The two names
   are easy to confuse; a reviewer may prefer a more distinct name (e.g.
   `plan-approval.yaml`). Decide before implementation.
2. **Where the trust-rule assertions live, and the `_REUSABLE` tuple.**
   `test_reusable_workflows.py`'s shared `ReusableWorkflowTests` loop asserts a
   *framework checkout* and a *`FLOWTREE_CF_ACCESS_CLIENT_SECRET` secret* for
   every workflow in its `_REUSABLE` tuple. Plan Ready deliberately has
   **neither** (it is inline shell with only `GITHUB_TOKEN`). So Plan Ready
   must **not** be added to `_REUSABLE`; its trust rule belongs in a separate,
   Plan-Ready-specific test class in the same file. The task's wording
   ("`test_reusable_workflows.py` pins its trust rule") is satisfied by that
   dedicated class — confirm this placement is acceptable, since it is a
   deliberate departure from the generic loop.
3. **`vars` visibility confirmation.** The GitHub platform findings above were
   not web-verified in this environment (WebSearch/WebFetch were unavailable).
   The implementer should confirm the `github`-context-is-caller's, permission
   capping, and `vars` behaviours against the live GitHub Actions docs before
   relying on them; all are corroborated by existing reusable workflows in this
   repository, so the risk is low.

## Estimated complexity

**Small.** One job moves, essentially verbatim, into a `workflow_call` file;
three hard-coded values become inputs with this-repository defaults; the
existing file becomes a short caller. The bulk of the effort is the header
documentation, the four doc updates, and reworking the two test files to follow
the caller into the called workflow and to pin the trust rule. No application
code and no new scripts.
