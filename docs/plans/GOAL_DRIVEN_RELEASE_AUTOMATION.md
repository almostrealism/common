# Goal-Driven Release Automation

## Goal

Let people own *what* a release should achieve, and let automated agents do the
rest of the path from a stated goal to a merged implementation:

1. People write and maintain goal documents: the long-term goals of a product,
   and a plan for its next release.
2. An agent reads those documents and keeps the tracker in step with them,
   creating a task for every piece of work the next release needs that is not
   already tracked, in the project of the repository where the work belongs.
3. In each repository, an agent picks up the next ready task for the release
   that repository's master is building and writes a plan for it on a new
   branch, which opens a pull request.
4. The existing review cycle improves the plan. A person approves it and starts
   the implementation by hand, on the same branch and pull request.
5. When the pull request merges, the task closes.

After this, the human job is to curate goals, release plans and approvals.
Finding the next piece of work, decomposing it and planning it become automatic.
Nothing is implemented without a person approving the plan first.

This document covers the general mechanism, which lives in this repository: the
tracker, the agent permissions, the task-driven planning round, and the reusable
goal-decomposition job. A consuming application wires the decomposition job into
its own CI and keeps its goal documents; that wiring is specific to the
application and is planned in the application's own repository.

This pipeline is **in addition to** the existing free-form planning round, not a
replacement for it. The free-form round keeps maintaining this repository for
its own purposes and for every consumer. The task pipeline serves one consuming
application's release goals. The two run side by side. Moving the free-form
round onto the tracker as well may make sense later, but only once this pipeline
has been shown to work.

## What already exists

| Piece | Where | Role in the pipeline |
|---|---|---|
| Tracker: projects, releases, tasks | `tools/tracker`, `tracker_*` tools in `tools/mcp/manager/tracker_tools.py` | Where tasks live. Releases are named `<Project> <version>`. |
| Planning round | `plan-next-task` in `.github/workflows/master-agent-dispatch.yaml`, prompt `tools/ci/prompts/project-planning.txt` | Opens one plan branch at a time; today it invents its own task. |
| One round open at a time; open-PR ceiling | `tools/ci/qa-cadence.sh` with `PR_GRACE_HOURS`; `MAX_OPEN_PRS` | Throttling that already works. |
| Docs-only review | `auto-review` → `tools/ci/prompts/docs-review.txt` in `analysis.yaml` | Reviews a plan; never implements it. |
| Approval gate | `.github/workflows/verify-completion.yaml` (dispatched by hand or via `project_verify_branch`) | The only thing that starts an implementation. |
| Workstream ↔ task link | `workstream_id` on a task | Can already record which branch is working on a task. |
| Per-workstream capability grants | `Workstream.applyCapabilities`, `dispatchCapable` | The precedent for giving one kind of agent a tool others don't have. |

## Gaps

These are what stop the pipeline from working today. Each one is established from
the code, not assumed.

1. **Agents cannot write to the tracker.** `tracker_create_task`,
   `tracker_update_task` and `tracker_delete_task` are in
   `EXCLUDED_TOOLS` (`tools/mcp/manager/tool_capabilities.py`) and
   `McpConfigBuilder.EXCLUDED_AR_MANAGER_TOOLS`.
2. **Agents cannot see the backlog.** `tracker_get_task`, `tracker_list_tasks`
   and `tracker_search_tasks` are workspace-scoped. A task with no
   `workstream_id` — which is every task nobody has started — is filtered out
   of an agent's results, or refused outright. A decomposition agent could not
   check for duplicates, and a planning agent could not find anything to plan.
3. **CI cannot reach the tracker.** CI reaches the controller through its
   Cloudflare Access tunnel. The tracker is only reachable inside the controller's
   compose network, and ar-manager is an MCP server authenticated by job
   tokens, not something a workflow step can call. So any tracker read or write
   has to happen inside an agent job, or through a new controller endpoint.
4. **Tasks carry no readiness, provenance or dependency information.** A task
   has a title, description, `open`/`closed` status, priority, project,
   release and workstream link. Nothing says:
   - whether the task is ready for an agent to pick up;
   - who or what created it;
   - what it is waiting on.

   That matters immediately: the framework's upcoming release already holds
   dozens of open, hand-written backlog tasks, several of them research-scale.
   "Plan any open task in the next release" would start on those.
5. **Nothing closes a task when its work merges.**

## Design

### Pipeline

```
 goal documents change (application repo, master)
        │
        ▼
 ┌──────────────────────┐   creates / updates tasks (own tasks only)
 │ decomposition round  │ ─────────────────────────────────────────────┐
 └──────────────────────┘                                              ▼
                                                                ┌──────────┐
 merge to master (each repo)                                    │ tracker  │
        │                                                       └──────────┘
        ▼                                                              ▲
 ┌──────────────────────┐   claims next ready, unblocked task          │
 │ task planning round  │   in the release this repo's master builds   │
 │                      │ ─────────────────────────────────────────────┤
 └──────────────────────┘                                              │
        │ plan branch + PR (docs only)                                 │
        ▼                                                              │
 auto-review: docs review only (never implements)                      │
        │                                                              │
        ▼                                                              │
 person approves → dispatches Verify Completion → implementation       │
        │                                                              │
        ▼                                                              │
 PR merges ────────────────────────────── task closed ─────────────────┘

 (the free-form planning round keeps running beside this, unchanged)
```

### 1. Tracker changes

Add to the task record:

- **`stage`**: `backlog` or `ready`. This is the readiness gate: the task
  planning round only ever picks up `ready` tasks.
  - Existing tasks, and tasks people create, are `backlog`. None of the existing
    tasks is promoted when this lands; a person promotes a task when they want
    it worked on.
  - Tasks the decomposition round creates are `ready` immediately. The round
    only runs against goal documents on the application's master, which a
    person has already approved, and each task still needs its plan approved
    before anything is implemented.
- **`source`**: who created the task — `person`, or `goals:<document path>`
  for the decomposition round. The decomposition round may only update tasks
  it created itself; a task a person wrote is read-only to every agent.
- **`blocked_by`**: task ids that must close first. This is what makes an
  application task wait for the framework task it depends on, across
  projects. The planning round only picks unblocked tasks.
- **Plan outcome**: when a plan PR is closed without merging, the task must not
  be picked up again and re-planned on the next merge. Recording `declined`
  (a `stage` value, or a separate field) keeps it out of the queue until a
  person returns it to `ready`.

The `open`/`closed` status stays as it is. "Claimed" is not a new state: a
task with a `workstream_id` has a branch working on it.

### 2. Narrow tracker tools for agents, not the general ones

> **Superseded.** The narrow tools and the *steward* capability described
> below were built and then replaced by a simpler model. Every agent now has
> the general tools (`tracker_list_projects`, `tracker_list_releases`,
> `tracker_list_tasks`, `tracker_search_tasks`, `tracker_get_task`,
> `tracker_project_summary`, `tracker_create_task`, `tracker_update_task`,
> `tracker_create_release`); none can delete. Reads are limited to the
> caller's workspace. Anyone may create a task, but a non-planner's task always
> lands in the backlog with its provenance set by the server, and a non-planner
> may edit only the tasks linked to its own workstream. A workstream holding
> the one tracker role, *planner*, may set every field, edit any task in the
> workspace and create releases (a saved *steward* loads as *planner*).
> Claiming moved out of the agent into CI: the task-planning job claims the
> task through the controller (`POST /api/tracker/claim`) before submitting
> the agent, and that claim's link is what lets the agent edit its task. The
> decomposition round holds *planner*, and its prompt — not the tool — keeps it
> to the tasks under its own `goals:` source prefix. The text below records the
> original design.

Rather than restoring `tracker_create_task` and `tracker_update_task` to some
agents, add a small number of purpose-built tools. The server enforces each
tool's limits itself, so they hold even if an agent is careless:

- **`tracker_claim_next_task(project, release)`**: atomically picks the
  highest-priority `ready`, unblocked, unclaimed task in that release, links it
  to the caller's workstream, and returns it; or returns nothing. Because the
  claim is a compare-and-set on `workstream_id`, two planning rounds can never
  take the same task. That makes the parallel phase below safe.
- **`tracker_upsert_goal_task(...)`**: creates or updates a task whose
  `source` is `goals:*`. Updating a `person` task is refused. The tool creates
  the target release (`<Project> <version>`) if it does not exist yet.
- **`tracker_list_release_tasks(project, release)`**: an unscoped, read-only
  view of one release's tasks, so the decomposition round can check what
  already exists before creating anything.

Each tool is granted through a per-workstream capability, following the
`dispatchCapable` precedent in `Workstream.applyCapabilities`:
- a *planner* capability for `tracker_claim_next_task`;
- a *steward* capability for `tracker_upsert_goal_task` and
  `tracker_list_release_tasks`.

Both grants inherit the weakness described in
`AGENT_CAPABILITY_GRANT_AUTHENTICATION.md`: nothing authenticates a grant. That
is acceptable here because each tool is narrow and none can delete anything, but
it should be recorded when the grant is added.

### 3. Task planning round

This is a new job in `master-agent-dispatch.yaml`, alongside the free-form
`plan-next-task`, which it leaves unchanged. The two are independent:
- **Branch prefix:** the task round uses its own (e.g. `project/task-`), so the
  one-round gate on `project/plan-` branches does not count it and it does not
  count them.
- **Manager log:** the task round never writes it. That log records the
  free-form survey's reasoning, not tracked work, and it is the only reason the
  free-form round is limited to one branch.
- **Concurrency group:** each has its own.

It keeps the same controls:
- a grace window for a branch that has no PR yet (`qa-cadence.sh` with
  `PR_GRACE_HOURS`);
- one task round open at a time to begin with (see Parallel planning);
- the same open-PR ceiling (`MAX_OPEN_PRS`), because both kinds of plan compete
  for the same reviewer's attention.

**Which release it serves:** the release this repository's master is building,
`<Project> <version>`, with the version read from the repository's own root
`pom.xml`. The workflow reads it and passes it into the prompt, so the agent
never has to work it out.

That the application may track tasks against a *later* framework release does
not matter here: those tasks wait until this repository's version is moved up
to that release. The two versions are usually the same.

**The gate:** before creating a branch, the workflow asks the controller
whether the release has a claimable task (see *Claimable-task endpoint* below).
If there is none, the job ends there: no branch, no workstream, no agent. The
next merge asks again.

**The round's steps** (as first designed; CI now performs the claim, see the
note in section 2):
1. Call `tracker_claim_next_task` for this repository's project and that
   release. The claim is what actually takes the task; the gate only says one
   was available. If another round took the last task in between, the claim
   comes back empty and the agent ends without committing. That can only
   happen once parallel planning is enabled, and it costs one empty round.
2. If a task is claimed, write a plan for it. The plan document names the task
   id, and the plan's scope is the task's scope.
3. Write the commit message following the existing rule (it frames the work,
   not the plan), naming the task as well.

**Claimable-task endpoint.** CI cannot see the tracker, but it already reaches
the controller through the Cloudflare Access tunnel, and the controller shares
a network with the tracker. So the controller gains a read-only endpoint:

- It takes a project name and a release name (for example
  `GET /api/tracker/claimable?project=<name>&release=<name>`).
- It returns how many tasks in that release are `ready`, unblocked and
  unclaimed, which is the same condition the claim uses. The
  tracker should own that condition, as one query both callers use, so the gate
  and the claim can never disagree about what "claimable" means.
- It changes nothing: it neither claims nor links a task.

The workflow calls it the same way the dispatch jobs already call the
controller: `CONTROLLER_URL` and the Cloudflare Access service token.

**Agent settings:** the task round, like the decomposition round, is submitted
without any model, runner or effort settings. Both take the workspace defaults.

### 4. Decomposition round

This is a reusable job, provided by this repository and run from a consuming
repository's CI. Its pieces:
- a prompt template and builder in `tools/ci/prompts/`, alongside the others;
- the existing `register-workstream.sh` / `submit-agent-job.sh` path, with
  the *planner* capability on its workstream (originally *steward*; see the
  note in section 2).

**Inputs**, all passed in by the calling workflow:
- the goal documents;
- the release plan directory for the next release;
- the tracker project and release for each repository involved;
- the versions each release name is built from.

**What the agent does:**
- reads the goals and the release plan, and the code of each repository
  involved where it needs to;
- lists what the relevant releases already contain;
- creates or updates `goals:*` tasks for work the release plan needs that is not
  yet tracked. Each task goes in the project of the repository where the change
  belongs: general capabilities in the framework project, application-specific
  work in the application's project. Dependencies go in `blocked_by`.

**What it must not do:**
- touch tasks a person wrote;
- delete anything;
- implement anything.

It ends with a summary of what it changed, sent as a workstream message, so a
person sees every change to the queue.

**Trigger:** a merge to the consuming repository's master that changes its goal
documents, its release plans or its version file, plus `workflow_dispatch` on
master only. It never runs against a branch, because its tasks are `ready` on
creation and must only ever come from approved documents. Serialized in its own
concurrency group.

**Idempotence:** every run reconciles against the current documents, so running
it twice creates nothing new. That property is what makes it safe to trigger on
every relevant merge.

**Which releases:** the ones the application specifies.
- Application tasks go in the release named by the application's version file.
- Framework tasks go in the framework release the application pins in its build.

The round does not look at the framework's own version: whether the framework
is ready to work on those tasks is the task planning round's concern (section 3).

**Intermediate libraries:** a library between the framework and the application
is tracked under the application's project. The long-term direction is for such
a library to disappear: general-purpose code moves into the framework, and
application-specific code moves into the application. Tasks that change it
should move code in that direction where they can.

### 5. Close-out

A task whose linked branch merges should close without anyone doing it. The
controller already reads pull-request state for workstreams (`WorkstreamListing`,
`GitHubProxyHandler.listPullRequestsByRepo`), so a controller-side reconciler can
do this deterministically:
- a linked task whose pull request merged → `closed`;
- a linked task whose pull request closed unmerged → `declined`.

That is preferable to asking an agent to reconcile state as a side task.

As built (`TaskCloseOutReconciler`, tracker `/v1/close-out`):
- It applies only to tasks that are open, `ready` and linked. A task a person
  left in `backlog` while linking it to a workstream is never closed for them.
- A decline also unlinks the task, so a person returning it to `ready` makes it
  claimable again.
- A branch with a closed pull request and an open one is left alone.
- Standing and orchestrator workstreams, and a branch that is its own base, are
  skipped.

### 6. Parallel planning

Task rounds never write the manager log, so nothing forces them to one at a time
beyond the claim itself. Since the claim (now made by CI through the
controller) is atomic, the
task round can open up to a configured number of branches at once. Start at
one, and raise it once the pipeline has proven itself. The open-PR ceiling still
applies across everything.

### 7. Adoption by other repositories

A consuming repository should get the whole pipeline by opting in, not by
copying it. Everything that submits agent jobs already lives here: the prompt
templates and builders, `register-workstream.sh`, `submit-agent-job.sh`, the
intake and claimable-task gates. A second copy in each consumer would drift from
the first, and every fix would have to be made twice.

GitHub's mechanism for this is the **reusable workflow**: a workflow in this
repository declares `on: workflow_call` with typed `inputs` and `secrets`, and a
consumer calls it from a job:

```yaml
jobs:
  decompose:
    uses: almostrealism/common/.github/workflows/goal-decomposition.yaml@master
    with:
      project: <tracker project>
      version-file: version.txt
    secrets:
      FLOWTREE_CF_ACCESS_CLIENT_SECRET: ${{ secrets.FLOWTREE_CF_ACCESS_CLIENT_SECRET }}
```

This repository is public, so a private consumer can call its workflows.

**What lives where:**

| Here (reusable, generic) | In the consumer (a thin caller) |
|---|---|
| The job's steps, scripts, prompts and gates | The trigger: which pushes and dispatches start the job |
| Defaults that fit the framework's own layout | Product specifics, passed as inputs: tracker project, version file, goal and plan locations |
| The trusted scripts the job runs with the controller token | The secrets, passed explicitly |

The rules that make this work:

- **The caller owns the trigger.** A called workflow cannot decide when it runs,
  so the caller's `on:` holds the path filters and the default-branch guard's
  first line of defence. The called workflow checks the guard again itself.
- **Scripts come from this repository, never from the consumer's checkout.** A
  plain `actions/checkout` inside a called workflow fetches the *caller's*
  repository, so the reusable workflow checks this repository out explicitly
  (input `framework-ref`, default `master`) and runs every script from that
  checkout. That is the same trust rule Verify Completion follows.
- **No product names here.** This repository is public. Every product-specific
  value reaches a reusable workflow as an input; its defaults describe the
  framework's own layout.
- **This repository will call its own reusable workflows.** The goal is a
  single implementation rather than a framework copy and a consumer copy: as
  each round is extracted and this repository's own triggers are pointed at the
  reusable workflow, `master-agent-dispatch.yaml` (or a sibling caller) calls it
  the way any consumer does, so this repository's pipeline exercises the same
  code every consumer runs. This is not yet true of the first reusable workflow:
  `goal-decomposition.yaml` has no caller in this repository today, so the
  decomposition round is exercised only by its tests and by a consumer's caller
  until this repository's self-caller is wired up.
- **Callers pin `@master` to begin with.** A fix here then reaches every
  consumer immediately; so does a breakage. A release tag can replace it once
  the workflows settle.

**The reusable workflows:**

1. `goal-decomposition.yaml`: the decomposition round (section 4). Built first,
   because it is the stage no consumer can run today.
2. The task planning round (section 3), extracted from
   `master-agent-dispatch.yaml` and parameterised by tracker project and version
   source.
3. The docs-only plan review and the Verify Completion approval workflow, so a
   consumer's plan branches get the same review and the same manual
   implementation trigger.

**The decomposition round as a reusable workflow:**

- **Inputs:** the caller's tracker project; its version file (`pom.xml` is
  read as a Maven project version, anything else as a plain version string);
  the goals document; the directory holding one release-plan directory per
  version; the framework's tracker project and the Maven property in the
  caller's root `pom.xml` that pins the framework version; a cap on new tasks
  per run.
- **Scope:** the release plan directory is `<plans directory>/<version>/`, with
  `PLAN.md` as its entry point. When it does not exist the job stops before it
  asks the controller anything: no workstream, no agent.
- **Workstream:** one standing workstream per repository, on the branch
  `orchestration/goal-decomposition` (the `orchestration/` prefix makes it
  `standing`, so it is never archived). Each run moves that branch to the
  default-branch commit being reconciled, so the agent reads exactly the
  approved documents that triggered it. The agent changes no files and commits
  nothing; its output is tracker writes and a summary message.
- **Framework checkout:** the workstream lists this repository as a dependent
  repository, so the agent can read the framework's code beside the
  application's when it decides where a task belongs.
- **Ownership:** every task the round creates has the source
  `goals:<owner>/<repository>/<document path>`. A run updates only tasks
  carrying its own repository's prefix, so two applications' decomposition
  rounds never edit each other's tasks; the tracker itself refuses any edit to a person's
  task.

**Consumer checklist** (per consuming repository, planned in that repository):

- the caller workflows, one per reusable workflow;
- `FLOWTREE_CF_ACCESS_CLIENT_SECRET` as a secret and `FLOWTREE_CONTROLLER_URL` /
  `FLOWTREE_CF_ACCESS_CLIENT_ID` as variables (or at organisation level);
- the tracker project, and its goals and release-plan documents;
- a docs-only skip in its own build workflow, so plan branches do not occupy
  expensive runners.

## Safety rails

- **No implementation without approval.** Unchanged: docs-only branches are only
  reviewed, and implementation starts from Verify Completion.
- **Readiness is explicit.** People's tasks wait in `backlog` until promoted.
  Goal-derived tasks are `ready` only because they come from approved documents
  on master.
- **Agents never edit a person's task, and never delete.** Enforced in the
  tools, not the prompt.
- **Dependencies are respected.** A blocked task is not planned.
- **Work waits for its release.** A repository only plans tasks for the release
  its own master is building.
- **Floods are bounded.**
  - The decomposition round has a per-run cap on new tasks.
  - The open-PR ceiling and the planning limits apply as today.
- **Every queue change is reported.** The decomposition summary lists what it
  created and changed.
- **Declined plans stay declined** until a person says otherwise.

## Phases

Each phase is useful on its own and can be approved separately. Phases 1, 2 and 4
are built. Phase 3 is being built as the reusable `goal-decomposition.yaml`
(section 7).

1. **Tracker fields and tools.**
   - `stage`, `source`, `blocked_by` and the declined outcome in `tools/tracker`
     (schema migration, API, tests). Every existing task migrates to `backlog`
     and `person`.
   - The three narrow tools in ar-manager, with their allowlist entries in both
     `tool_capabilities.py` and `McpConfigBuilder`.
   - The planner and steward capabilities. (Since replaced: the general tools
     for every agent, one *planner* role, and claiming in CI; see section 2.)
2. **Task planning round in this repository.** The claimable-task endpoint in
   the controller, and the new job in `master-agent-dispatch.yaml` gated on it.
   With phase 1 in place, promoting a task to `ready` is enough to get it
   planned. This is the smallest end-to-end
   slice, and it needs no goal documents at all.
3. **Decomposition round.** The prompt, the builder and the tests here; the
   consuming repository's workflow is its own plan.
4. **Close-out reconciler** in the controller.
5. **Parallel task planning.**
6. **Task planning in a consuming repository.** It uses the same tools and
   prompt. Rather than equivalents written in that repository, the planning
   round, the docs-only review and the approval workflow become reusable
   workflows here, and the consumer calls them (section 7).
7. **Later, optional:** have the free-form planning round record its work in the
   tracker too, once this pipeline is known to work.

## Decisions

- **Existing tasks start in `backlog`.** None is promoted when this lands.
- **Goal-derived tasks are `ready` on creation.** They come only from approved
  goal documents on the application's master.
- **The free-form planning round continues in parallel, unchanged.** Aligning it
  with the tracker is a later, optional step.
- **Each side uses its own version.**
  - The decomposition round tracks tasks against the releases the application
    specifies.
  - Each repository's task round plans only for its own master's version.
- **Intermediate libraries** are tracked with the application's project, and
  work there should move code towards the framework or the application.
- **The task round is gated by a controller endpoint**, so an empty queue creates
  nothing.
- **Agent jobs use the workspace defaults.** Neither round specifies a model,
  runner or effort.

## Future direction

The tracker should eventually be usable without MCP. Today the only way to read
or change it is through ar-manager's tools, which is why CI needs a controller
endpoint just to ask one question. The claimable-task endpoint is the first,
deliberately narrow piece of controller-side tracker access. A general REST
surface, authenticated the way the controller already authenticates CI, would
let workflows, scripts and other services use the tracker directly. That is not
needed for this pipeline, and is not planned here.
