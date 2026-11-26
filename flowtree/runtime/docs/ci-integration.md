# CI Integration and Auto-Resolve Workflow

This document describes how Flowtree integrates with CI/CD pipelines to automatically detect and resolve failures. It covers the auto-resolve workflow, failure detection mechanisms, prompt generation, the job submission API, quality gate evaluation, concurrency model, and test file protection.

---

## Table of Contents

1. [Overview](#overview)
2. [Verify-Completion Workflow](#verify-completion-workflow)
3. [QA Round Workflow](#qa-round-workflow)
4. [Auto-Resolve Workflow](#auto-resolve-workflow)
5. [Failure Detection](#failure-detection)
6. [Prompt Generation](#prompt-generation)
7. [Job Submission API](#job-submission-api)
8. [Workstream Registration API](#workstream-registration-api)
9. [Completion Listeners](#completion-listeners)
10. [Quality Gate Evaluation](#quality-gate-evaluation)
11. [Concurrency Model](#concurrency-model)
12. [Test File Protection](#test-file-protection)
13. [Surefire Report Parsing](#surefire-report-parsing)
14. [End-to-End Flow Diagram](#end-to-end-flow-diagram)

---

## Overview

The auto-resolve system creates a feedback loop between CI pipelines and coding agents. When a CI pipeline detects a failure (build error, test failure, or quality gate violation), it submits a job to the Flowtree controller describing the failure. The controller dispatches the job to an available agent, which runs Claude Code to diagnose and fix the issue. The fix is committed and pushed, triggering a new CI run that validates the resolution.

The key components involved are:

- **CI Pipeline** (GitHub Actions workflows): Detects failures and submits resolution jobs
- **FlowTreeApiEndpoint**: The controller's HTTP API that receives job submissions
- **SlackNotifier**: Routes jobs to the appropriate workstream and tracks progress
- **ClaudeCodeJob.Factory**: Constructs the job that will execute on an agent
- **ClaudeCodeJob**: Executes the Claude Code prompt and manages git operations
- **detect-test-hiding.sh**: A validation script that audits changes before commit

---

## Verify-Completion Workflow

The verify-completion workflow (`verify-completion.yaml`) implements a self-improvement loop where coding agents implement and verify plan goals on feature branches. It is triggered manually via `workflow_dispatch` on any non-master branch, and it is the only thing that starts implementing a plan: until someone dispatches it, a plan branch (whose changes are all under `docs/`) receives only the docs-only review from `auto-review` in `analysis.yaml`, which improves the documents and never carries out the plan.

`verify-completion.yaml` itself is a thin caller: its one job calls the reusable `implement-plan.yaml`, which holds the jobs described below. Other repositories call `implement-plan.yaml` the same way for their own plan branches, so it checks this repository out explicitly (inputs `framework-repository` and `framework-ref`, by default `almostrealism/common` at `master`) and runs every script and prompt template from that checkout. In this repository that is the default branch.

Implementation is triggered two ways. A person can dispatch `verify-completion.yaml` by hand (or via `project_verify_branch`). The automatic path is the owner approving the plan — marking its draft PR **ready for review** — which the `Plan Ready` workflow (`ready-plan.yaml`) turns into that same dispatch on the PR's head branch. A consuming repository gets the automatic path by calling the reusable `plan-ready.yaml` (`workflow_call`, in this repository) from its own `pull_request_target` caller and setting its `PLAN_APPROVERS` variable; without that caller it dispatches `verify-completion.yaml` by hand. `plan-ready.yaml` is inline shell over event metadata and the GitHub API — it checks out nothing, runs no framework script and holds no controller secret — so unlike `implement-plan.yaml` it needs no framework checkout.

### Four-Phase Pipeline

The workflow runs as a four-phase pipeline of five jobs with conditional execution:

```
detect-plan ───────────┐
                       ├──>  register-workstream  ──>  build-prompt  ──>  verify
resolve-plan-settings ─┘
```

No job runs any of the branch's scripts, and no job that holds the
controller's Cloudflare Access service token installs anything from the
network. Every job checks out the framework and runs its copies of the scripts
and prompt templates; the branch is read only as data. The jobs that need the
token (`register-workstream` and `verify`) run nothing but a controller script;
the prompt is rendered in `build-prompt`, which never sees the token and hands
its result to `verify` as an artifact, and the job that installs PyYAML to read
the plan's settings (`resolve-plan-settings`) never sees it either and hands
its result to `register-workstream` as job outputs. This is the same stage-then-submit pattern the
`auto-review` / `auto-review-submit` jobs in `analysis.yaml` use.

This protects against a branch's edits to *scripts*, not to the *workflow file*:
`workflow_dispatch` runs the workflow definition from the dispatched ref, so a
branch that edits `verify-completion.yaml` (or its copy of `implement-plan.yaml`) controls every job in the run.
That is the same exposure `analysis.yaml` has for `pull_request` runs, and is
covered by the same mitigations — see "No job that runs pull request code holds
controller credentials" in `.github/CLAUDE.md`.

**Phase 1: Detect Plan** (`detect-plan`)

Identifies the plan document for the branch. The plan file can be provided explicitly via the `plan_file` workflow input, or auto-detected by scanning for new `.md` files in `docs/plans/` (input `plans-directory`) relative to the default branch. It reads only the branch's changed file names. The job outputs:
- `plan_file`: Path to the detected plan document
- `is_new_plan`: Whether the plan file is newly added on this branch

**Phase 2: Resolve Settings and Register Workstream** (`resolve-plan-settings`, then `register-workstream`)

Both jobs run on every dispatch; registration is idempotent, and an existing workstream is updated. `register-workstream` checks out the framework and calls its copy of `tools/ci/register-workstream.sh`, which POSTs to the controller's `POST /api/workstreams` endpoint.

Registration applies the workstream settings the plan declares for itself, which `resolve-plan-settings` reads first. For a branch under `project/`, the branch name without `project/` is matched against the `<prefix>-workstream.yaml` files in the branch's `docs/plans/`: a prefix matches when the name equals it or continues it at a `-`, and the longest matching prefix wins. So for `project/plan-20260930-180401`, `plan-20260930-180401-workstream.yaml` overrides `plan-20260930-workstream.yaml`, and `plan-2026093-workstream.yaml` never matches. The only setting a file may contain today is `requiredLabels` (e.g. `platform: macos`), which becomes the workstream's default node labels, so the implementation and every later job run on a matching node. Only the branch's `docs/plans/` is checked out (a sparse checkout into a directory of its own, without credentials), and it is read by the framework's `tools/ci/plan_workstream_config.py`, so no branch code runs. Reading it installs PyYAML from the network, and a package's install or import code could rewrite the checked-out scripts or append to `$GITHUB_ENV`/`$GITHUB_PATH` for later steps of the same job, so this happens in `resolve-plan-settings`, which holds no controller token; only the resolved JSON reaches `register-workstream`, as job outputs read through `env:`. It is read from the dispatched commit (`github.sha`), the same commit every other job in the run uses, so a push made after the plan was approved cannot change the settings the implementation runs with. An invalid settings file (unknown key, wrongly shaped value), or a dispatched commit that cannot be checked out, fails `resolve-plan-settings`; `register-workstream` is then skipped, and `build-prompt` requires `resolve-plan-settings` to have succeeded, so the implementation is not submitted. Because the settings take effect only here, approving a plan and dispatching its implementation also approves the environment it asks for. Settings files stay in `docs/plans/` after their branch merges, so a short prefix keeps applying to later branches it matches.

Registration:
- Creates a new `SlackWorkstream` with the branch name, base branch, repo URL, and plan document path
- Auto-creates a **private** Slack channel named `w-<branch>` (with slashes replaced by hyphens)
- Invites the configured `channelOwnerUserId` to the new channel
- Persists the workstream to the YAML config file

**Phase 3: Build Prompt** (`build-prompt`)

Runs after detect-plan, resolve-plan-settings and register-workstream succeed (not if registration failed, and not if the settings could not be resolved). Checks out the framework, builds the implementation prompt with its `build-verify-prompt.sh` (the plan document itself reaches the agent through the workstream's planning document), stages it with `stage-submit-request.sh` (which writes `agent-prompt.txt` and `submit.env`), and uploads the result as the `verify-request` artifact. This job holds no controller credentials.

**Phase 4: Verify** (`verify`)

Runs only when build-prompt succeeded. Checks out the framework, downloads the `verify-request` artifact, and submits it with the framework's `submit-staged-request.sh`. The target branch, base branch and `PROTECT_TEST_FILES` come from this job's own environment, never from the staged request, and `submit-staged-request.sh` reads `submit.env` through a key allowlist, so nothing in the request can redirect the submission. Because no job in the run executes the branch's code, nothing the branch controls has touched `$GITHUB_ENV` or `$GITHUB_PATH` before the token is attached. The agent then implements any unfinished goals, verifies all goals are complete, and ensures each goal has meaningful test coverage.

### Register Workstream Script

The `tools/ci/register-workstream.sh` script handles workstream registration from CI:

**Required environment variables:**
- `BRANCH` -- target branch for the workstream
- `BASE_BRANCH` -- base branch (e.g., `master`)

**Optional environment variables:**
- `PLAN_FILE` -- path to the planning document; when the workstream already exists, it is updated with this document
- `TRACKER_CAPABILITIES` -- comma-separated tracker roles for the workstream's agents; the one role is `planner` (`steward` is accepted as a legacy alias of it)
- `REQUIRED_LABELS_JSON` -- JSON object of default node labels (e.g. `{"platform":"macos"}`); applied to a new or existing workstream
- `CHANNEL_NAME` -- explicit Slack channel name (the controller derives one from the branch when absent)
- `CONTROLLER_URL` -- controller base URL; takes precedence over `CONTROLLER_HOST`/`CONTROLLER_PORT`, and is how a GitHub-hosted runner reaches the controller through its Cloudflare Access tunnel
- `CONTROLLER_HOST` -- FlowTree controller hostname, used only when `CONTROLLER_URL` is unset (default: `localhost`)
- `CONTROLLER_PORT` -- FlowTree controller port (default: `7780`)
- `CF_ACCESS_CLIENT_ID` / `CF_ACCESS_CLIENT_SECRET` -- Cloudflare Access service token, sent as request headers when set
- `REPO_URL` -- repository clone URL

**Channel name derivation:**
The channel name is derived from the branch name: `project/plan-20260223-foo` becomes `w-project-plan-20260223-foo`. The `w-` prefix is always added (idempotently) to distinguish workstream channels from other channels.

**Claiming a tracker task:**
Agents do not claim tracker tasks themselves. A workflow that hands a tracker task to an agent first registers the workstream, then calls the controller's `POST /api/tracker/claim` with `{"project": "<name>", "release": "<name>", "workstreamId": "<id>"}`. The controller forwards the claim to the tracker, which links the next ready task of the release to the workstream, and answers `{"ok": true, "task": {...}}` (or `"task": null` when nothing is claimable); the workflow puts that task in the agent's prompt. The endpoint answers 400 when a field is missing or blank, 404 when the workstream is not registered, 503 when the controller has no tracker configured, and 502 when the tracker call fails. `GET /api/tracker/claimable?project=<name>&release=<name>` answers how many tasks could be claimed without claiming one.

---

## QA Round Workflow

The QA-round workflow (`qa-round.yaml`) is the recurring-quality counterpart of
the plan-then-implement reusable workflows. Each recurring round — a defect
hunt, a documentation-staleness review, a consolidation pass, and so on — runs
the same ordered steps, and `qa-round.yaml` carries them once so a repository
building on this one can adopt a round with only a caller job and inputs, rather
than copying the plumbing.

It is triggered only by `workflow_call`. Its single job: checks out the caller's
default branch (to create and push the round's branch) and the framework at
`framework-ref` with `persist-credentials: false` (for every script and built-in
template); asks the controller whether it accepts automated jobs
(`automated-intake.sh`); decides from the shared cadence gate
(`qa-cadence.sh`) whether a round may start — no round of this prefix in
progress, room in the open-PR backlog, and the interval passed; archives the
rounds that are over; creates a `<branch-prefix><UTC timestamp>` branch;
registers its workstream; builds the prompt; and submits a coding-agent job with
`AUTO_CREATE_PR` (never as a draft — a QA round's PR is real work meant to be
merged as it stands). With the gate declined nothing is created.

The per-round differences are inputs: `branch-prefix`, `min-interval-days`,
`grace-hours`, `max-open-prs`, `force`, `ignore-interval`, the submission
settings (`protect-test-files`, `enforce-changes`, `max-enforce-changes-retries`,
`phase-configs`, `required-labels`, `required-labels-json`, `description`), and
the prompt. The `prompt` input is either a built-in template selector
(`defect-hunt` | `doc-qa` | `consolidation` | `flaky-hunt` | `retrospective`,
rendered from the framework checkout) or a path to a template the caller
supplies in its own checkout, read as *data* by
`tools/ci/prompts/build-qa-round-prompt.sh` and rendered but never executed.
`template-vars` is a JSON map of extra `${NAME}` substitutions, and
`test-runner-fragment` chooses the language-specific tooling
guidance a built-in template includes (default `test-runner-maven.txt`; a
non-Maven consumer passes `test-runner-generic.txt`).

The `retrospective` selector surveys a repository's recent automated work and
proposes how its QA process should improve. It is generalised through
`template-vars` so each consuming repository runs its own, scoped to its own
`REPO_URL` and written into its own tree, and never reading or quoting another
repository: `REVIEW_SINCE` and `QA_PREFIXES` set the survey window and the QA
branch prefixes; `REPORT_DIR` is where the report and ledger live (default
`docs/qa/retrospectives`); `VISIBILITY_FRAGMENT` selects the public rule
(default) or a private variant; and `FINDINGS_FRAGMENT` with
`FRAMEWORK_TRACKER_PROJECT` routes a consumer's shared-harness findings to the
framework's tracker project as tasks, since a consumer cannot edit harness
files it does not own. The trust rule is the same
as the other reusable workflows: no input is interpolated into a `run:` script,
the controller URL and client ID are inputs, and the client secret is the
declared `workflow_call` secret `FLOWTREE_CF_ACCESS_CLIENT_SECRET`.

This repository's `master-agent-dispatch.yaml` calls `qa-round.yaml` for every
QA round except `coverage-qa`, which stays inline because its target selection
runs before (and gates) branch creation and it needs `actions: read` to reuse
master's coverage artifact.

---

## Auto-Resolve Workflow

The auto-resolve workflow is triggered by CI pipeline failures and follows this sequence:

### Step 1: CI Pipeline Runs

The CI pipeline (GitHub Actions or equivalent) runs the standard build and test steps. This includes compilation, unit tests, integration tests, and any quality gates defined in the workflow.

### Step 2: Failure Detection

When a step fails, the pipeline's failure handling logic activates. This is typically implemented in a dedicated job or step that runs on `failure()` condition. The failure handler collects:

- The failing step name and exit code
- Build output or test output (truncated to a reasonable size)
- Surefire XML reports (for test failures)
- The branch name and commit that triggered the pipeline
- The repository URL

### Step 3: Job Submission

The failure handler POSTs a JSON payload to the Flowtree controller's submit endpoint. The payload includes the failure information formatted as a prompt that describes what went wrong and what the agent should investigate.

```
POST /api/workstreams/{workstreamId}/submit
Content-Type: application/json

{
  "prompt": "The CI pipeline failed on branch feature/my-work...",
  "targetBranch": "feature/my-work",
  "protectTestFiles": true
}
```

### Step 4: Controller Dispatches Job

The controller's `FlowTreeApiEndpoint` receives the submission, resolves the target workstream (by branch name match or explicit workstream ID), constructs a `ClaudeCodeJob.Factory`, and dispatches it to an available agent via the Flowtree task queue.

### Step 5: Agent Executes Fix

The agent receives the job, clones or updates the repository, runs Claude Code with the failure prompt, and the agent diagnoses and fixes the issue. After Claude Code completes, the git management layer commits and pushes the changes.

### Step 6: New CI Run

The push triggers a new CI run. If the fix resolves the failure, the pipeline succeeds. If not, the cycle can repeat (subject to concurrency limits and budget constraints).

### Settled CI: starting the long-running tests when the review loop ends

A push to a pull request only starts a *validate* run of "Build and Test".
The long-running test lanes run in a separate *test* run, which a deployment
of the branch starts. "Validate and Test Runs" in `.github/CLAUDE.md`
describes that design.

The controller starts the test run when the review loop ends. A workstream's
`settledCiTrigger` names the CI to start when one of its coding jobs finishes
successfully with nothing to commit (`JobCompletionEvent.isSuccessWithoutChanges()`).
That is how the loop ends: the review that finds nothing to fix.

| Value | Effect |
|-------|--------|
| `deployment:<environment>` | Creates a deployment of the branch to that environment. `deployment:test` starts this repository's test run, whose `Test Checks` gate is a required status check. |
| `workflow:<file>` | Dispatches that workflow on the branch. Checks from a dispatched run do not satisfy required status checks. |
| empty | Nothing (the default). |

`CiTrigger.start` checks whether the branch's head commit already has a
deployment to that environment, or a run of that workflow, and does nothing if
it does. That makes it safe to fire on every change-free job. The trigger never
fires for the workstream's base branch.

Set it with `workstream_update_config(settled_ci_trigger="deployment:test")`,
or at registration. The controller uses the workstream organisation's GitHub
token, and that token must be able to create deployments. CI does not depend on
the controller for this: when the controller is down, a person starts the same
test run with the `Start Tests` workflow or one REST call.

In this repository CI sets it on every branch's workstream, so nothing has to
be done by hand. The `register-workstream` job passes
`SETTLED_CI_TRIGGER` (the `SETTLED_CI_TRIGGER` repository variable, default
`deployment:test`) to `register-workstream.sh`, which sends it on registration
and re-applies it to a workstream that already exists, however that workstream
was created. A docs-only branch has no `register-workstream` run, so the early
submit jobs pass the same value to `submit-agent-job.sh`. When that submission
creates the workstream, the controller gives the new workstream the trigger
too. It is the one submission field that becomes a workstream setting.

---

## Failure Detection

The CI pipeline detects three categories of failures, each requiring different information in the prompt.

### Build Failures

Build failures occur when `mvn compile` or `mvn package` fails. The failure information includes:

- The Maven module that failed to compile
- The compiler error output (truncated)
- The file and line number of the error, when available

The prompt instructs the agent to read the specific error, understand the compilation issue, and fix the source code. Since build failures typically have clear error messages with file paths and line numbers, they are the most straightforward to resolve.

### Test Failures

Test failures occur when `mvn test` completes but one or more tests fail. The information collected includes:

- The test class and method names that failed
- The assertion failure message or exception stack trace
- The Surefire XML report path (for structured failure data)
- The Maven module containing the failing tests

The prompt includes the specific test names and failure messages, instructing the agent to investigate whether the test is correct (requiring a production code fix) or whether the test itself needs updating (subject to test file protection rules).

### Quality Gate Failures

Quality gate failures are custom checks that enforce code quality standards beyond compilation and tests. These include:

- The `detect-test-hiding.sh` script that audits for attempts to hide test failures
- Custom validation steps defined in workflow files
- Any post-test analysis that produces a non-zero exit code

The prompt includes the quality gate output and the specific rule that was violated.

---

## Prompt Generation

The prompt sent to the agent is the most critical part of the auto-resolve workflow. It must contain enough context for the agent to understand and fix the issue without requiring interactive clarification.

### ClaudeCodeJob Instruction Prompt

The `ClaudeCodeJob.buildInstructionPrompt()` method constructs a comprehensive instruction prompt that wraps the user's request (the failure description) with operational context. The prompt includes several conditional sections:

**Autonomous Agent Context:**
The prompt begins with instructions for autonomous, non-interactive execution. The agent is told there is no TTY and no interactive session, so it should not attempt to wait for user input.

**Slack Communication (when workstream URL is configured):**
The agent is instructed to use the Slack MCP tool for status updates throughout its work. It must send updates when starting, reaching milestones, making key decisions, and finishing. If tool calls are denied, the agent must immediately report the denial via Slack.

**Test Integrity Policy (when protectTestFiles is enabled):**
The agent is explicitly told it must not modify test files that exist on the base branch. It must fix the production code instead. Tests introduced on the current branch may be modified.

**Git Commit Instructions:**
The agent is told not to make git commits directly. Instead, it should write its desired commit message to a `commit.txt` file in the working directory root. The git management harness handles the actual commit after Claude Code finishes.

**Merge Conflict Instructions (when base branch has diverged):**
If the `prepareWorkingDirectory()` step detected merge conflicts between the target branch and the base branch, the prompt includes specific instructions for resolving those conflicts. It lists the conflicted files and instructs the agent to run `git merge`, resolve conflicts, and complete the merge before proceeding with any other work.

**Branch Context:**
The prompt reminds the agent that all branch references in user instructions refer to remote branches (`origin/<branch>`), since the local branches in the sandboxed environment may be stale or absent.

**Branch Awareness and Anti-Loop Guidance:**
The agent is told it is not the first to work on this branch and that previous agent sessions have made changes reflected in the git history. It is instructed to:
- Use `workstream_context` to understand prior work
- Record decisions and discoveries via the memory system
- Avoid add/revert loops where changes are made, CI fails, changes are reverted, and the cycle repeats

**Planning Document (when configured):**
If the workstream has a planning document configured, the agent is instructed to read it before starting work. The planning document describes the broader goal of the branch, and the current task is a sub-task of that goal.

**Budget and Turn Limits:**
The prompt includes the configured budget (in USD) and maximum turns so the agent can pace itself accordingly.

**User Request:**
Finally, the actual failure description (the prompt from the CI pipeline) is included between `--- BEGIN USER REQUEST ---` and `--- END USER REQUEST ---` markers.

### Prompt Flow from CI to Agent

The prompt flows through several stages:

1. **CI Pipeline** constructs the failure description as a plain text prompt
2. **Controller API** receives the prompt via the `/submit` endpoint
3. **ClaudeCodeJob.Factory** stores the prompt and encodes it with Base64 for wire transmission
4. **ClaudeCodeJob.buildInstructionPrompt()** wraps the prompt with operational context
5. **Claude Code CLI** receives the complete instruction prompt via the `-p` flag

---

## Job Submission API

The Flowtree controller exposes an HTTP API via `FlowTreeApiEndpoint` for receiving job submissions from CI pipelines and other external systems.

### Submit Endpoint

```
POST /api/workstreams/{workstreamId}/submit
Content-Type: application/json
```

**Request Body:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `prompt` | `string` | Yes | The task description or failure information to send to the agent |
| `targetBranch` | `string` | No | The branch to commit changes to. When provided, also used for workstream resolution by branch name |
| `baseBranch` | `string` | No | The base branch for new branch creation (default: workstream's configured default) |
| `protectTestFiles` | `boolean` | No | Whether to enable test file protection (default: workstream's configured default) |
| `workstreamId` | `string` | No | Explicit workstream ID override. When present, takes priority over URL path and branch resolution |
| `repoUrl` | `string` | No | Repository the branch belongs to. Disambiguates branch resolution, and identifies a workstream created by `createWorkstreamIfMissing`. Required with that flag |
| `createWorkstreamIfMissing` | `boolean` | No | Register a workstream for `targetBranch` + `repoUrl` when none matches, instead of rejecting the submission (default: `false`) |
| `maxTurns` | `integer` | No | Maximum turns for the Claude Code session |
| `maxBudgetUsd` | `number` | No | Maximum budget in USD for the session |
| `maxWallClockHours` | `integer` | No | Wall-clock ceiling in hours for this job. Overrides the workstream default when present (`0` disables the ceiling); omitted lets the workstream default or `RestartGovernor.DEFAULT_MAX_WALL_CLOCK` apply |
| `allowedTools` | `string` | No | Comma-separated allowed tools override |

**Response:**

On success (200):
```json
{
  "status": "submitted",
  "jobId": "abc123",
  "workstreamId": "ws-456",
  "targetBranch": "feature/my-work"
}
```

On error (400):
```json
{
  "error": "Missing required field: prompt"
}
```

### Workstream Resolution

The submit endpoint resolves the target workstream through a multi-step process:

1. **Explicit `workstreamId` in body**: If the request body contains a `workstreamId` field and it matches a registered workstream, that workstream is used.

2. **Branch name match**: If `targetBranch` is provided, the controller searches all registered workstreams for one whose `defaultBranch` matches. This allows CI pipelines to submit jobs without knowing the workstream ID.

3. **URL path fallback**: The `{workstreamId}` from the URL path is used if neither of the above resolves to a valid workstream.

4. **Creation on demand**: If nothing matches and the body sets `createWorkstreamIfMissing`, the controller registers a workstream for `targetBranch` and `repoUrl` — deriving the Slack channel name from the branch, exactly as `POST /api/workstreams` does — and runs the job on it. The response then carries `"workstreamCreated": true`.

This multi-step resolution ensures that auto-resolve jobs from CI pipelines (which typically know only the branch name) are routed to the correct workstream with the right agent pool and configuration.

#### Creating a workstream on submission

Auto-resolve only runs on branches that have a workstream. Requiring one to be
registered in advance means a contributor who simply pushes a branch gets no
automated run — the common case for work that did not start from a plan
document. `createWorkstreamIfMissing` closes that gap: a project's auto-resolve
job submits with the flag, and the controller creates whatever workstream the
branch needs on first use.

Two properties make this safe to leave on:

- **Repository identity, not URL equality.** A repository is written several
  ways — `git@github.com:owner/repo.git`, `https://github.com/owner/repo.git`,
  and the suffix-less URL a CI system reports. Resolution compares
  `owner/repo` slugs (`GitOperations.repositorySlug`), so a submission that
  names the repository differently than the registration did still finds the
  existing workstream instead of creating a second one. A workstream that names
  no repository at all — registered before the field existed — matches any
  repository, but only when no workstream on that branch names the requested
  one.
- **Ambiguity is still an error.** When two workstreams already share the
  branch on different repositories, the submission is rejected as ambiguous
  rather than resolved by creating a third.

A workstream created this way takes only the branch, base branch, and
repository from the submission. Job-level fields such as `requiredLabels` and
`phaseConfigs` are *not* copied onto it — they mean "for this job" on a
submission and "for every job on this workstream" in a registration, and a
one-off constraint must not silently become a permanent default. Everything
else takes the defaults a bare `POST /api/workstreams` would produce and can be
changed afterwards through the update endpoint or `/flowtree config`.

`repoUrl` is required with the flag: a workstream is identified by repository
and branch together, and one created without a repository would neither match
the next submission nor have anything to check out. The URL is also what the
agent clones, so pass a URL it can clone from — `tools/ci/submit-agent-job.sh`
defaults to `git@github.com:$GITHUB_REPOSITORY.git`, matching
`register-workstream.sh`.

For CI, no per-project configuration is needed: `submit-agent-job.sh` sends the
flag by default and derives `repoUrl` from `GITHUB_REPOSITORY`. Set
`CREATE_WORKSTREAM=false` to restore the previous behaviour of rejecting
submissions for unregistered branches. Callers going through ar-manager pass
`create_workstream_if_missing=True` to `workstream_submit_task`.

### Messages Endpoint

```
POST /api/workstreams/{workstreamId}/messages
Content-Type: application/json
```

Used by agents to store messages and send notifications during job execution. `ar-manager`'s `send_message` MCP tool calls this endpoint.

**Request Body:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `text` | `string` | Yes | The message text to send to the workstream's Slack channel |

### Job-Level Messages Endpoint

```
POST /api/workstreams/{workstreamId}/jobs/{jobId}/messages
Content-Type: application/json
```

Sends a message to the specific Slack thread associated with a job, rather than the channel. The `{jobId}` is used to look up the thread timestamp.

### Status Event Endpoint

The workstream URL itself (same as the messages endpoint but without the `/messages` suffix) receives job completion events posted by `GitManagedJob.fireJobCompleted()`:

```
POST /api/workstreams/{workstreamId}/jobs/{jobId}
Content-Type: application/json
```

The JSON payload includes all fields from `JobCompletionEvent` and `ClaudeCodeJobEvent`:

| Field | Type | Description |
|-------|------|-------------|
| `jobId` | `string` | The job identifier |
| `status` | `string` | `SUCCESS`, `FAILED`, or `STARTED` |
| `description` | `string` | Job description (truncated prompt) |
| `targetBranch` | `string` | The target branch |
| `commitHash` | `string` | The commit hash if a commit was made |
| `pushed` | `boolean` | Whether the commit was pushed |
| `stagedFiles` | `string[]` | List of staged file paths |
| `skippedFiles` | `string[]` | List of skipped files with reasons |
| `pullRequestUrl` | `string` | The PR URL if one was detected |
| `errorMessage` | `string` | Error message if the job failed |
| `prompt` | `string` | The original prompt |
| `sessionId` | `string` | Claude Code session ID |
| `exitCode` | `int` | Claude Code exit code |
| `durationMs` | `long` | Total duration in milliseconds |
| `durationApiMs` | `long` | API call duration in milliseconds |
| `costUsd` | `double` | Total cost in USD |
| `numTurns` | `int` | Number of turns consumed |
| `subtype` | `string` | Stop reason subtype |
| `sessionIsError` | `boolean` | Whether the session ended in error |
| `permissionDenials` | `int` | Number of tool permission denials |
| `deniedToolNames` | `string[]` | Names of denied tools |

---

## Workstream Registration API

The controller provides endpoints for dynamic workstream management, used by CI pipelines to create and update workstreams at runtime.

### Register Endpoint

```
POST /api/workstreams
Content-Type: application/json
```

Creates a new workstream. When a `channelName` is provided and Slack is configured, a private channel is created automatically.

**Request Body:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `defaultBranch` | `string` | Yes | The git branch for this workstream |
| `baseBranch` | `string` | No | Base branch (default: `"master"`) |
| `repoUrl` | `string` | No | Repository clone URL |
| `planningDocument` | `string` | No | Path to the plan document relative to repo root |
| `channelName` | `string` | No | Slack channel name (private channel auto-created) |
| `completionListeners` | `string[]` | No | Workstream IDs woken when a job on this workstream finishes; see [Completion Listeners](#completion-listeners) |

**Response (200):**

```json
{
  "ok": true,
  "workstreamId": "ws-abc123",
  "channelId": "C0123456789",
  "channelName": "w-project-plan-foo"
}
```

The workstream is registered in memory and persisted to the YAML config file via `SlackListener.registerAndPersistWorkstream()`.

### Update Endpoint

```
POST /api/workstreams/{id}/update
Content-Type: application/json
```

Updates fields on an existing workstream. All fields are optional; only provided non-empty fields are applied. Supports updating: `channelId`, `channelName`, `defaultBranch`, `baseBranch`, `repoUrl`, `planningDocument`. `completionListeners` and `dormantForCompletionListeners` are also accepted; unlike the string fields they are applied whenever the field is present, so an empty `completionListeners` array clears the list (see [Completion Listeners](#completion-listeners)).

### Health Check Endpoint

```
GET /api/health
```

Returns:
```json
{
  "status": "ok"
}
```

### Stats Endpoint

```
GET /api/stats?workstream={workstreamId}&period=weekly
```

Returns aggregated job statistics for the current and previous week, optionally filtered by workstream. The `JobStatsStore` persists job timing data in an embedded database.

### Daily Agent Time Endpoint

```
GET /api/stats/daily?from=2026-10-01&to=2026-10-20&tz=America/Los_Angeles
```

Returns agent wall-clock time per calendar day across every workstream, for `from` to `to` inclusive (at most 366 days), with days counted in the zone `tz` (default `UTC`):

```json
{
  "tz": "America/Los_Angeles",
  "days": [
    {"date": "2026-10-01", "wallClockMs": 14400000, "runningMs": 0, "jobCount": 2, "costUsd": 1.5}
  ]
}
```

- Every day in the range is listed, with zeros where nothing ran.
- A job's time is split at local midnight, so each day holds only the time spent on it. Jobs in parallel each count in full, so a day can hold more than 24 hours.
- `wallClockMs` is finished jobs' time. `runningMs` is the time so far of jobs still running. A running job that has been silent longer than the unresponsive threshold stops accruing at its last heartbeat.
- `jobCount` and `costUsd` belong to the day a job started, as in the weekly statistics. Jobs that were submitted but never started are not counted.
- Bad dates, an unknown zone, or a reversed or oversized range return HTTP 400. A controller without a stats store answers `{"error":"Stats not configured"}`.

See `DailyAgentTime` for the arithmetic.

---

## Completion Listeners

A completion listener is a workstream that is woken automatically when a job on another workstream finishes. The usual shape is an orchestrator that delegates work to several worker workstreams: each worker lists the orchestrator as a listener, and every time a worker job reaches a terminal status the controller submits a short "wake-up" job to the orchestrator, so it can read the results and decide what to do next.

The feature does nothing until you set it up. A workstream with no listeners configured never causes a wake-up.

### Configuring the `completionListeners` field

`completionListeners` is a list of **workstream IDs** (not branch names or repository URLs) on the *source* workstream: the one whose completions should wake somebody. It is stored on `Workstream` (`getCompletionListeners()` / `setCompletionListeners(List)`) and persisted in `workstreams.yaml` by `WorkstreamConfig.WorkstreamEntry`. Blank and `null` entries are dropped by the setter, and the list defaults to empty.

To make worker `ws-worker` wake orchestrator `ws-orch`, configure the **worker**:

**REST (register):**

```
POST /api/workstreams
Content-Type: application/json

{"defaultBranch": "feature/foo", "completionListeners": ["ws-orch"]}
```

**REST (update):**

```
POST /api/workstreams/ws-worker/update
Content-Type: application/json

{"completionListeners": ["ws-orch"]}
```

On update the field is applied whenever it is present (`WorkstreamRegistrationHandler` checks `JsonFieldExtractor.hasField`). Leaving it out keeps the current list. Sending `"completionListeners": []` clears it. The controller reads the field only as a JSON array of strings.

**MCP (`ar-manager`):** `workstream_register` and `workstream_update_config` both take a `completion_listeners` string. `_parse_completion_listeners` in `tools/mcp/manager/server.py` accepts either a comma-separated list (`"ws-orch,ws-audit"`) or a JSON array string (`'["ws-orch"]'`), trims every entry and drops empty ones.

- `workstream_register` sends `completionListeners` only when the parsed list is non-empty.
- `workstream_update_config`: an empty string (the default) means "no change". Any non-empty value is sent, even one that parses to an empty list such as `","`, which clears the listeners.
- Both tools call `_require_dispatch_capable` (`tools/mcp/manager/workspace_map.py`). An agent can configure listeners only from a workstream with `dispatchCapable: true`. Operator tokens that aren't bound to a workstream are always allowed.

**YAML:** see [Configuration: Completion listeners in YAML](configuration.md#completion-listeners-in-yaml).

The listener-side opt-out `dormantForCompletionListeners` (described below) can be set through REST and YAML only. The MCP tools have no parameter for it.

Wake-up jobs run *as the listener*. `CompletionListenerFanout.buildWakeUpFactory` takes the allowed tools, max turns, budget, branches, repository, planning document, required labels, phase configuration and agent environment from the **listener's** configuration, not the source's. The job description is `wake-up: <listener channel name> <- <source workstream ID>`. The prompt names the finished job (ID, status, description, commit, PR URL), the chain ID and the chain depth. The prompt names only the single completion that fired the wake-up — never the completions coalesced behind it within the window (`buildWakeUpPrompt` reads only the firing event). It also tells the listener to reconcile *every* workstream it has delegated to instead of acting on that single job. That rule is what makes it safe to drop or coalesce wake-ups: the next wake-up that does fire catches up on whatever the dropped ones would have reported.

### Cycle rejection

Before a `completionListeners` value is saved, the controller checks it with `ListenerCycleChecker.check` (`io.flowtree.workstream`). It runs a depth-first search over the whole listener graph as it would look after the change, and the request is refused if that graph has a cycle:

| Configuration | Result | Error text (from `WorkstreamRegistrationHandler.checkListenerCycle`) |
|---|---|---|
| `A` lists `A` | rejected | `self-listing: workstream A cannot list itself as a completion listener` |
| `A -> B`, then `B` lists `A` | rejected | `cycle: B -> A -> B` (the path is closed back to its first node) |
| `A -> B`, `A -> C`, `B -> D`, `C -> D` (diamond) | allowed | — |

A rejected register or update returns the error response, and nothing is written to the YAML config. That does not mean the request has no effect:

- **Update.** `handleUpdate` applies the other fields in the body (channel, branches, repository, planning document, plan instructions, phase configuration, required labels, dependent repos) to the live in-memory workstream *before* it runs the cycle check. When the check fails, those changes are not rolled back. They take effect at once, and the next successful persist writes them to disk. The `completionListeners` value is left unchanged, and so is every field the handler applies *after* the check (for example `dispatchCapable`, `trackerCapabilities`, `dormantForCompletionListeners` and `kind`), so a rejected update can apply only part of its body. To be safe, send a listener change in its own update request, or fix the cycle and resend the whole body.
- **Register.** The workstream is never registered. But if the body named a `channelName`, the Slack channel was already created before the check ran, so it is left behind.

Listener IDs that don't belong to any registered workstream are **not** rejected when the config is saved. They are skipped at wake-up time with a `wakeup_listener_missing` warning.

To have a job wake its *own* workstream once, use a shell job submitted with `selfNotify` instead of a standing self-listing ([Job Completion Events: Self-Notify Field](events.md)). That path goes through `CompletionListenerFanout.fanoutSelf` and has the same limits as an ordinary listener.

### Fan-out limits

`FlowTreeApiEndpoint.completeJob` calls `CompletionListenerFanout.fanout` for every terminal event. `STARTED` events never cause a fan-out. `dispatchToListener` then runs the gates below, in order, for each listener. The first gate that fails drops that wake-up (it is not queued or retried) and logs the reason shown:

| # | Gate | Limit | Constant in `CompletionListenerFanout` | Log line |
|---|------|-------|-----------------------------------------|----------|
| 1 | Kill switch | `acceptAutomatedJobs == false` | — (see below) | `wakeup_kill_switch_active` |
| 2 | Listener exists | listener ID must be a registered workstream | — | `wakeup_listener_missing` |
| 3 | Listener dormancy | listener has `dormantForCompletionListeners: true` | — | `wakeup_listener_dormant` |
| 4 | Coalesce window | at most 1 wake-up per (source, listener) pair per **30 s**; the first completion fires the wake-up and later completions in the window are dropped | `DEFAULT_COALESCE_WINDOW_SECONDS = 30` | none (the later job ID is appended to an in-memory bookkeeping list — see below) |
| 5 | Chain depth | depth must be below **8** | `DEFAULT_MAX_CHAIN_DEPTH = 8` | `ceiling_hit ... reason=maxChainDepth(8)` |
| 6 | Per-source-chain breadth | at most **25** wake-ups from one source completion | `DEFAULT_MAX_WAKE_UPS_PER_SOURCE_CHAIN = 25` | `ceiling_hit ... reason=maxWakeUpsPerSourceChain(25)` |
| 7 | Per-listener flood window | at most **6** wake-ups per listener in a sliding **600 s** (10 min) window | `DEFAULT_MAX_WAKE_UPS_PER_WINDOW = 6`, `DEFAULT_MAX_WAKE_UP_WINDOW_SECONDS = 600` | `ceiling_hit ... reason=maxWakeUpsPerWindow(6/600s)` |
| 8 | Per-listener debounce | after a wake-up is submitted to a listener, no new wake-up for **300 s** (5 min) | `DEFAULT_DEBOUNCE_SECONDS = 300` | `wakeup_listener_recently_woken ... sinceMs=<n>` |

Things to know when reading these limits:

- **None of the limits can be configured.** All of them are `public static final` constants. The debounce has a setter (`setDebounceSeconds`), but only tests call it; `CompletionListenerFanout.bind`, which the controller uses through `CompletionListenerFanoutWiring.wire`, keeps the 300 s default.
- **The debounce ends early when the wake-up finishes.** When the wake-up job reaches a terminal status, `notifyListenerWakeUpCompleted` clears that listener's debounce, so the next completion can wake it right away. It recognises wake-up jobs by the `wake-up:` description prefix (`WAKE_UP_DESCRIPTION_PREFIX`).
- **A wake-up uses one slot of the flood window only once it actually fires.** A wake-up dropped by the debounce does not count toward the 6-per-600 s limit.
- **Coalesced completions do not travel in the wake-up prompt.** When a later completion lands inside the 30 s window, its job ID is appended to the firing wake-up's in-memory `consolidatedJobIds` bookkeeping list, but `buildWakeUpPrompt` reads only the firing event, so the submitted wake-up names only the first completion. The coalesced IDs are visible to tests (`coalesceStateView`) but are not sent to the listener. This loses no work because of the reconciliation invariant: the listener re-reads every workstream it delegated to on every wake, so the one wake-up that does fire covers the completions coalesced behind it.
- **The chain-depth limit cannot trip yet.** `computeChainDepth` returns only `0` (an ordinary job) or `1` (a job whose description starts with `wake-up:`), because `JobCompletionEvent` has no depth field. In practice the flood window and the debounce are what limit recurrence.
- **Every listener woken by one completion shares a chain ID.** The ID (`ch-<uuid>`) is created once per source completion, so the 25 limit caps how many listeners a single completion can wake. It does not limit repeated wake-ups across completions.
- **All of this state is in memory.** Coalesce, window, debounce and chain counters reset when the controller restarts.
- **The limits are not atomic under concurrent completions.** `dispatchToListener` holds no lock across the gates: each one checks its state at one point (the coalesce map, the window deque's size, `wakeUpDispatchedAt`) and records the dispatch later, in step 9. Completions that arrive on different threads at the same moment can therefore all pass gates 4, 6, 7 and 8 before any of them records its wake-up. In that case a listener can get more than one wake-up inside the coalesce or debounce window, or more than 6 in 600 s. Read the numbers in the table as the limits for completions that arrive one after another, not as hard ceilings.
- **The fan-out never throws.** Any error is logged under a `wakeup_*` WARN line, and the source job's completion is still recorded. The names are:
  - submission and dispatch failures: `wakeup_dispatch_failed`, `wakeup_fanout_failed`, `wakeup_self_notify_failed`, `wakeup_submit_failed`
  - a source workstream that is not registered: `wakeup_source_missing`
  - a fan-out that has no server to submit to: `wakeup_no_server`
  - Slack thread-root failures: `wakeup_listener_notifier_lookup_failed`, `wakeup_listener_notifier_missing`, `wakeup_submit_notify_failed`
  - failure to mint the wake-up's ar-manager token: `wakeup_ar_token_failed`

  A thread-root failure or a token failure does not block the wake-up. The job is still submitted. A thread-root failure only skips the job's Slack thread root, and a token failure only leaves the job without ar-manager credentials.

A successful wake-up logs `Submitted wake-up chain=<id> source=<ws> listener=<ws> triggerJob=<job> wakeJob=<job>`. Grep the controller log for `wakeup_`, `ceiling_hit` and `Submitted wake-up` to follow a chain.

### Stopping wake-ups

**Globally: `acceptAutomatedJobs`.** `FlowTreeApiEndpoint.acceptAutomatedJobs` is the kill switch. It defaults to `true`. When it is `false`, gate 1 drops every wake-up, and the submit endpoint also rejects every submission that carries `"automated": true` (for example CI auto-resolve jobs). Manual submissions keep working.

```
GET  /api/config/accept-automated-jobs              -> {"acceptAutomatedJobs": true}
POST /api/config/accept-automated-jobs {"accept": false}
                                                    -> {"ok": true, "acceptAutomatedJobs": false}
```

The MCP equivalent is `controller_update_config accept_automated_jobs:"false"`; call it without arguments to read the current value. It needs an unscoped (superadmin) token, because the switch affects every workspace. The value is held only in memory: a controller restart sets it back to `true`. A POST body without an `accept` field leaves the value unchanged.

**One listener: `dormantForCompletionListeners`.** Set this to `true` on the *listener* (`POST /api/workstreams/{id}/update` with `{"dormantForCompletionListeners": true}`, or in YAML) to drop only the wake-ups aimed at it. Manual submissions to that workstream are not affected. Setting it back to `false` does not replay the wake-ups that were dropped, and it does not reset the coalesce or debounce state: the dormancy gate (gate 3) sits ahead of those gates, so if the listener was woken shortly before it went dormant, a completion arriving soon after you resume it can still be dropped by the per-listener debounce (gate 8) or the coalesce window (gate 4). Once those windows have elapsed, the next completion wakes it. The field defaults to `false` (`Workstream.isDormantForCompletionListeners`).

To stop a single source from waking anyone, clear its list instead: `{"completionListeners": []}`.

### Reading `trigger_reason`

The `job_timing` table in `JobStatsStore` has a `trigger_reason VARCHAR(64)` column with default `'manual'`. Existing databases get it through a schema migration. The values that are actually written today are:

| Value | Written by |
|-------|-----------|
| `manual` | the default, for every job that nothing marked otherwise (including CI and other automated submissions) |
| `completion_listener` | `CompletionListenerFanout`, through `JobStatsStore.setTriggerReason(jobId, "completion_listener")`, right before the wake-up is submitted |

The `setTriggerReason` javadoc also mentions `auto_resolve`, but nothing in the code writes that value yet. Because the fan-out calls `setTriggerReason` before it submits the wake-up, the `job_timing` row normally does not exist yet. The reason is therefore staged in memory (`pendingTriggerReasons`) and applied when `recordJobStarted` inserts the row. `setTriggerReason` also updates the row in place if one already exists. That covers the usual orderings, but not every one:

- When no row exists by the time the job completes (the controller restarted while the wake-up was running, or its `STARTED` event never reached the controller), `recordJobCompleted` inserts the row itself, and that insert does not set `trigger_reason`. The row gets the default `'manual'` even though it was a wake-up.
- The pending reasons are held only in memory, so a controller restart between `setTriggerReason` and the job's first row loses them as well.

So `trigger_reason = 'completion_listener'` reliably finds wake-ups, but `trigger_reason <> 'completion_listener'` can still include a few of them. Where that matters, also exclude descriptions that start with `wake-up:` (see below).

No REST endpoint or MCP tool returns this column. Query the HSQLDB file database directly. It is named `stats` and lives in the controller's data directory: the directory holding `workstreams.yaml`, or `~/.flowtree` when the controller runs without a config file (`FlowTreeController.dataDirectory`). The connection URL is `jdbc:hsqldb:file:<dataDir>/stats` with user `SA` and an empty password. A running controller holds the database lock, so query a copy of the `stats.*` files or stop the controller first.

```sql
-- Wake-up jobs per listener workstream over the last day
SELECT workstream_id, COUNT(*) AS wakeups, SUM(cost_usd) AS cost
FROM job_timing
WHERE trigger_reason = 'completion_listener'
  AND started_at > CURRENT_TIMESTAMP - 1 DAY
GROUP BY workstream_id;

-- Manual job history with wake-ups filtered out
SELECT job_id, workstream_id, status, description
FROM job_timing
WHERE trigger_reason <> 'completion_listener'
  AND (description IS NULL OR description NOT LIKE 'wake-up:%')
ORDER BY started_at DESC;
```

When you can't query the database, the job description works as a stand-in: every wake-up job's description starts with `wake-up:`, and that prefix shows up in `GET /api/workstreams/{id}/jobs` listings and in Slack.

### Future work

Ideas for later versions, none of them implemented:

- Richer listener filters, such as waking only on `FAILED`, or only for jobs that opened a PR.
- Listeners that span workspaces.
- A real depth field on `JobCompletionEvent`, so that the chain-depth limit can take effect.
- Returning `completionListeners` and `trigger_reason` from the read APIs. Today `GET /api/workstreams` does not include the listener list; the YAML file is where to check it.

---

## Quality Gate Evaluation

Quality gates are checks that run after the agent completes its work but before the changes are committed. They serve as a last line of defense against problematic changes.

### ClaudeCodeJob validateChanges()

The `ClaudeCodeJob.validateChanges()` method is called after `doWork()` completes but before git operations begin. When `protectTestFiles` is enabled, it runs the `detect-test-hiding.sh` script:

```java
protected boolean validateChanges() throws Exception {
    if (!isProtectTestFiles()) {
        return true;
    }

    Path auditScript = resolveWorkingPath("tools/ci/agent-protection/detect-test-hiding.sh");
    if (auditScript == null || !Files.exists(auditScript)) {
        return true;  // Skip if script not found
    }

    // Run: bash detect-test-hiding.sh origin/<baseBranch>
    ProcessBuilder pb = new ProcessBuilder("bash", auditScript.toString(),
        "origin/" + baseBranch);
    // ...
    int code = p.waitFor();

    if (code == 2) {
        // Exit code 2 = violations found
        warn("Test-hiding violations detected - aborting commit");
        return false;
    }

    return true;
}
```

### detect-test-hiding.sh

This shell script audits the diff between the current working state and the base branch for patterns that indicate an agent is hiding test failures rather than fixing production code. It checks for:

- Tests that have been deleted or renamed
- Test assertions that have been weakened (e.g., `assertEquals` replaced with `assertTrue(true)`)
- Test methods that have been emptied or had their bodies replaced with `return`
- `@Ignore` or `@Disabled` annotations added to existing tests
- Test timeout values that have been significantly increased

**Exit codes:**
- `0`: No violations detected
- `1`: Script error (bad arguments, missing git ref, etc.)
- `2`: Violations detected -- changes should be rejected

### File Staging Guardrails

In addition to the `validateChanges()` quality gate, the `FileStager` class applies guardrails during file staging (before commit). These guardrails are applied in order:

1. **Pattern exclusion**: Files matching any excluded glob pattern are skipped
2. **Test file protection**: If enabled, files matching protected path patterns that exist on the base branch are blocked
3. **File size**: Files exceeding the maximum size threshold are skipped (deleted files are exempt)
4. **Binary detection**: Files with more than 10% null bytes in the first 8000 bytes are skipped (deleted files are exempt)

The `FileStager.evaluateFiles()` method returns a `StagingResult` that classifies each changed file as "staged" (passed all guardrails) or "skipped" (failed a guardrail, with a reason string).

A path absent from the working tree is staged as a deletion with `git rm --cached --ignore-unmatch` (`GitOperations.stage`), because `git add` fails on a path that is also gone from the index, which is what an agent leaves when it resolves a modify/delete conflict with `git rm`. A staging command that fails is recorded as a skip whose reason is the command and git's own message.

#### Merge resolutions the guardrails reject

While a merge of the base branch is in progress, `GitCommitHandler.restoreRejected` settles every path that was not staged:

1. If the owner accepted the agent's resolution of the path (see below), the agent's version is staged as it is.
2. If a clean merge has a version for it (`MergeSides.cleanSource`: `HEAD` for a path the base branch did not change, the base commit for one only it changed while the merge is the harness's own), the path returns to that version.
3. If the merge is the harness's own and the agent's resolution equals the base branch's version (both sides end with a file deleted as the base branch deleted it, for instance), the resolution is committed: it is the base branch's reviewed content and cannot revert it.

Any other rejected path fails the commit. For each one, the failure names the guardrail or failed git command that rejected it, says whether the resolution keeps the target branch's version (reverting the base branch's change), equals the base version, or matches neither, and explains why neither side can stand in for it.

Once the owner has reviewed such a resolution, re-submitting the job with the operator-only `accepted_merge_resolutions` option (`acceptedMergeResolutions` on `/api/submit`) naming the paths commits the agent's resolution of them. The controller signs the grant with `AR_AGENT_BYPASS_SECRET`, exactly as it signs the sensitive-file bypass (`OperatorGrants`), drops a grant it cannot sign, and never takes a signature from the request. The commit records the grant in a `Merge-Resolution-Accepted: <jobId>=<signature>; paths=...` trailer, after stripping any copy the agent wrote. CI's own locks still apply to the result.

#### Rescue of a failed job's work

When a job ends in failure (it threw, every change was dropped, or work was left unpublished) after its work changed the working tree, `WorkingTreeRescue` commits the whole tree in a temporary index and pushes it to `rescue/<jobId>`. The job's own index and working tree are not touched. During a merge the rescue commit's second parent is `MERGE_HEAD`, so a rescued merge resolution can be accepted with one fast-forward push: `git push origin rescue/<jobId>:<targetBranch>`. The failure message and the completion event's `rescueRef` name the branch. When the job does not push, or the push fails, the commit is written as a patch to `flowtree-rescue/<jobId>.patch` beside the working directory instead. Either way it is kept locally as `refs/rescue/<jobId>`.

The rescue is for a person to retrieve, so a test-lock finding is listed in its commit message rather than blocking it. Content whose publication is itself the harm is withheld and left at the version a clean merge would give it: CI configuration under the CI-file lock (a pushed branch runs the workflows its own tree defines), files matching the excluded patterns (credentials and keys among them), harness artifacts, and oversized or binary files. Nothing is created for a job that fails before its work changes the tree, or for one whose work the harness reverted as git tampering.

---

## Concurrency Model

The auto-resolve system enforces a one-job-per-branch concurrency model to prevent conflicting changes.

### One Auto-Resolve Per Branch

Only one auto-resolve job can be active for a given branch at any time. This is enforced at multiple levels:

**At the controller level:** The `SlackNotifier` tracks active jobs per workstream. When a new submission arrives for a branch that already has an active job, the controller can either queue it or reject it depending on configuration.

**At the git level:** The `prepareWorkingDirectory()` method in `GitManagedJob` ensures that the working directory is in a clean, up-to-date state before the agent starts work. If a previous job left uncommitted changes, they are discarded (with a warning log):

```java
private void prepareWorkingDirectory() {
    // 1. Check for uncommitted changes
    List<String> dirtyFiles = checkForUncommittedChanges();
    if (!dirtyFiles.isEmpty()) {
        warn("Uncommitted changes found: " + fileList + " -- resetting");
        executeGit("checkout", ".");
        executeGit("clean", "-fd");
    }

    // 2. Fetch latest from origin
    executeGit("fetch", "origin");

    // 3. Checkout target branch
    ensureOnTargetBranch();

    // 4. Pull latest (fast-forward only, reset if diverged)
    // 5. Synchronize with base branch
}
```

### Base Branch Synchronization

Before each job starts, the `synchronizeWithBaseBranch()` method merges the latest remote base branch into the working branch. This ensures the agent works on code that includes all recent changes to the base branch, reducing merge conflicts at PR time.

If the merge produces conflicts, they are recorded and the merge is aborted. The agent's prompt is augmented with conflict resolution instructions, and the agent is expected to resolve the conflicts as part of its work.

### Push Semantics

The job uses an explicit refspec when pushing: `targetBranch:targetBranch`. This ensures the push always targets the correct remote branch regardless of any inherited upstream tracking configuration. The `-u` flag sets the upstream for subsequent operations.

---

## Test File Protection

Test file protection is a multi-layer defense against agents hiding test failures by modifying tests instead of fixing production code.

### Layer 1: Prompt Instructions

When `protectTestFiles` is enabled, the instruction prompt includes a "Test Integrity Policy" section:

```
## Test Integrity Policy
You MUST NOT modify test files that exist on the base branch (master).
Fix the production code instead. Tests you introduced on this branch
may be modified. The commit harness will reject changes to protected
test files.
```

### Layer 2: File Staging Protection

During file staging, the `FileStager` (and the equivalent logic in `GitManagedJob.stageFiles()`) checks whether each changed file:
1. Matches a protected test path pattern (`**/src/test/**`, `**/src/it/**`)
2. Exists on the base branch (checked via `git cat-file -e origin/<baseBranch>:<file>`)

If both conditions are true, the file is blocked from staging with the reason "protected - exists on base branch". Branch-new test files (files that do not exist on the base branch) are allowed through.

CI configuration (`.github/workflows/**`, `.github/actions/**`, `tools/ci/**`) is handled by the separate CI file lock (`protectCiFiles`), not by this test check: under that lock a CI file is blocked whether or not it exists on the base branch.

The base branch existence check fails safe: if the `git cat-file` command errors out for any reason, the file is treated as protected (existing on the base branch), preventing accidental modifications.

### Layer 3: detect-test-hiding.sh Validation

Even if file staging protection is bypassed (e.g., through test files that pass the pattern check), the `validateChanges()` method runs `detect-test-hiding.sh` as a final audit. This script performs a diff-level analysis to detect semantic test hiding patterns that a simple file-level check would miss.

### Layer 4: CI Pipeline Detection

The CI pipeline itself can also run `detect-test-hiding.sh` as a separate step, providing an additional check that runs on the pipeline's infrastructure rather than the agent's. This is the ultimate backstop because it runs in an environment the agent cannot influence.

### Flow Summary

```
CI detects failure
    |
    v
Submit auto-resolve job (protectTestFiles=true)
    |
    v
Agent receives job with test integrity prompt
    |
    v
Agent fixes production code (not tests)
    |
    v
File staging blocks protected test files
    |
    v
detect-test-hiding.sh validates the diff
    |
    v
Changes committed and pushed
    |
    v
CI re-runs and validates the fix
    |
    v
CI also runs detect-test-hiding.sh (backstop)
```

---

## Surefire Report Parsing

When test failures are the trigger for auto-resolve, the CI pipeline typically includes Surefire XML report data in the prompt. Surefire reports are Maven's standard output format for test results, stored as XML files in `target/surefire-reports/`.

### Report Structure

Each test class produces a Surefire XML file with this structure:

```xml
<testsuite name="org.example.MyTest" tests="5" failures="1" errors="0" skipped="0">
  <testcase name="testMethodA" classname="org.example.MyTest" time="0.123"/>
  <testcase name="testMethodB" classname="org.example.MyTest" time="0.456">
    <failure type="junit.framework.AssertionError" message="expected 42 but was 0">
      Stack trace here...
    </failure>
  </testcase>
</testsuite>
```

### Information Extracted for Prompts

The CI pipeline extracts the following from Surefire reports for inclusion in the auto-resolve prompt:

- **Test class name**: The fully-qualified class name (e.g., `org.almostrealism.ml.OobleckComponentTests`)
- **Failing method name**: The specific `@Test` method that failed
- **Failure message**: The assertion error message or exception message
- **Stack trace**: Truncated stack trace showing where the failure occurred
- **Test count summary**: Total tests, passed, failed, errored, and skipped counts

### Prompt Format for Test Failures

A typical auto-resolve prompt for test failures includes:

```
The CI pipeline on branch feature/my-work failed with test failures.

Module: ml
Test class: org.almostrealism.ml.OobleckComponentTests
Failing tests:
  - testDecoderBlock: AssertionError: expected shape [1, 512] but was [1, 256]
  - testEncoderBlock: NullPointerException at OobleckEncoder.java:142

Recent commits on this branch:
  abc1234 - Added new decoder implementation
  def5678 - Updated encoder configuration

Please investigate these test failures and fix the production code.
Do not modify the test files.
```

---

## End-to-End Flow Diagram

The complete auto-resolve cycle, from CI failure to resolution, follows this sequence:

```
1. Developer pushes to feature/my-work
2. CI pipeline triggers on push
3. Maven build and tests run
4. Test failures detected in surefire reports
5. CI failure handler collects:
   - Branch name
   - Failing test names and messages
   - Surefire XML content
   - Recent commit history
6. CI POSTs to controller:
   POST /api/workstreams/{ws}/submit
   { "prompt": "...", "targetBranch": "feature/my-work", "protectTestFiles": true }
7. Controller resolves workstream by branch match
8. Controller creates ClaudeCodeJob.Factory with:
   - Prompt (failure description)
   - Target branch
   - Workstream configuration (tools, budget, turns)
   - MCP server configuration
   - Test file protection enabled
9. Factory is dispatched to available agent
10. Agent receives and deserializes the factory
11. Factory.nextJob() creates ClaudeCodeJob
12. Job.run() executes:
    a. resolveAndCloneRepository() - Clone/update the repo
    b. prepareWorkingDirectory() - Fetch, checkout, pull, sync with base
    c. doWork() - Execute Claude Code with instruction prompt
    d. validateChanges() - Run detect-test-hiding.sh
    e. handleGitOperations() - Stage, commit, push
    f. fireJobCompleted() - POST status event to controller
13. Controller receives completion event
14. SlackNotifier sends completion message to Slack
15. Push triggers new CI run
16. CI validates the fix
```

Each step in this flow has error handling and logging. Failures at any stage are reported back to the controller via the status event mechanism, and Slack notifications keep the team informed of progress and issues.
