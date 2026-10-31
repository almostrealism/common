# AR CI Tools

Scripts used by the GitHub Actions CI pipeline (`.github/workflows/analysis.yaml`)
to build prompts, parse test results, and submit agent jobs to the FlowTree controller.

## Directory Structure

| Directory | Purpose |
|---|---|
| `agent-protection/` | Anti-deception enforcement scripts (test write locks, audit) |
| `coverage/` | Test-coverage automation: target selection, report fetching, assertion density |
| `docker/` | Linux CPU runner fleet (`ar-ci`), Docker Compose |
| `macos/` | macOS GPU runners (`ar-ci`): `tools/bin/fleet macos up [N]` installs them as LaunchDaemons |
| `rocm/` | AMD/ROCm OpenCL runner fleet (`ar-ci-cl`), Docker Compose |
| `cuda/` | NVIDIA CUDA runner fleet (`ar-ci-cuda`), Docker Compose on the host's NVIDIA runtime: `tools/bin/fleet cuda up [N]` |
| `monitor/` | Host monitoring tools for CI infrastructure |
| `prompts/` | Prompt builders and templates for agent jobs |

## Root Scripts

| Script | Purpose |
|---|---|
| `archive-stale-workstreams.sh` | Archive the workstreams of a recurring job's previous rounds, keeping the ones still in progress (`KEEP_BRANCHES`) |
| `parse-surefire-failures.sh` | Extract failing tests from Surefire XML reports |
| `qa-cadence.sh` | Decide whether a recurring round (`BRANCH_PREFIX`) is due: fewer than `MAX_ROUNDS` in progress (reported as `live`), the interval passed, and with `CHECK_BACKLOG` room in the open-PR backlog |
| `open-pr-backlog.sh` | Decide whether the open-PR backlog leaves room for a round (`MAX_OPEN_PRS`); run by both planning jobs, and by `qa-cadence.sh` for the QA rounds |
| `plan_workstream_config.py` | Resolve the `docs/plans/<prefix>-workstream.yaml` settings a `project/` branch declares (longest matching prefix; strict schema) |
| `tracker-claimable.sh` | Ask the controller whether a tracker release has a claimable task; fails closed |
| `tracker-claim.sh` | Claim the next task of a tracker release for a workstream through the controller (`POST /api/tracker/claim`); writes the task to a file and emits `claimed`, `task_id` and `task_file`; fails closed |
| `automated-intake.sh` | Ask the controller whether it accepts automated jobs, before a dispatch round creates anything; fails closed |
| `register-workstream.sh` | Register a workstream with the FlowTree controller (emits `workstream_id`, also to `$GITHUB_OUTPUT`), optionally granting tracker roles (`TRACKER_CAPABILITIES`) and checking out other repositories beside it (`DEPENDENT_REPOS`) |
| `resolve-release-scope.sh` | Goal decomposition: read the next version from a repository (`pom.xml` or a plain version file), the framework version it pins, and whether `<plans dir>/<version>/PLAN.md` exists (`in_scope`) |
| `remediation-required-labels.sh` | Pin an auto-resolve submission to a macOS Node (`REQUIRED_LABELS`) when a Metal lane (`test-mac`, `test-media-mac`) failed in the attempt |
| `rerun-flaky-tests.sh` | Retry gate in `auto-resolve-submit.yaml`: re-run a failed run's long-running test jobs until attempt `MAX_ATTEMPTS`; never retries a run whose python-tests failed |
| `stage-submit-request.sh` | Write a built prompt and its submission parameters to a request directory for a remediation job to upload |
| `submit-agent-job.sh` | Submit an agent job to the FlowTree controller, creating the workstream for the repository and branch when none is registered; `REQUIRED_LABELS` routes it to a Node with matching capability labels. `PROTECT_TEST_FILES` defaults to `"false"`: it turns on the harness's per-job test lock (see `flowtree/runtime/docs/file-staging.md`), which only the jobs sent to make failing tests pass, and a few QA rounds, request — everything else is held to `test-integrity-check`. `ENFORCE_CHANGES` (default `"false"`) requires the job to produce a change. A run that leaves none is re-run up to `MAX_ENFORCE_CHANGES_RETRIES` times over the whole job (omitted: the controller default; `"0"`: never re-run), and a job that still commits nothing once that cap is spent is reported DEGRADED rather than SUCCESS |
| `submit-staged-request.sh` | Submit a staged request directory from a trusted (default-branch) checkout, exporting only the documented `submit.env` keys |
| `sync-music-samples.sh` | Seed the curated audio sample library onto a runner (any fleet) |

## Reusable workflows

Some workflows are called by other repositories rather than triggered here, so
a repository that builds on this one can adopt a round without copying its
scripts. A called workflow runs every script from an explicit checkout of this
repository (input `framework-ref`), never from the caller's tree.

| Workflow | Purpose |
|---|---|
| `goal-decomposition.yaml` | Decomposition round of goal-driven release automation: on the caller's default branch, resolve its next release and release plan (`resolve-release-scope.sh`), register a standing `orchestration/goal-decomposition` workstream with the tracker `planner` role and this repository as a dependent repository, and submit an agent job that creates or updates the release's tracker tasks |
| `task-planning.yaml` | Task-planning round: on the caller's default branch, gate on intake, a round in progress (`qa-cadence.sh`), the open-PR backlog (`open-pr-backlog.sh`) and a claimable task (`tracker-claimable.sh`) in `<project> <version>`, then create a `project/task-*` branch, register its workstream, claim the task (`tracker-claim.sh`) and submit an agent that plans it and opens the PR. This repository's `plan-release-task` calls it |
| `implement-plan.yaml` | The manual "implement the plan" trigger, dispatched by the caller on a plan branch: detect the plan, resolve its workstream settings (`plan_workstream_config.py`), register the workstream, and submit the implementation through a token-free prompt build and `submit-staged-request.sh`. This repository's `verify-completion.yaml` calls it |
| `plan-review.yaml` | Docs-only review for a repository without this pipeline: when every change on the branch is under `docs/`, submit the `docs-review.txt` agent, which improves the documents and never implements the plan |
| `qa-round.yaml` | One recurring QA round: ask the controller about intake, gate on the shared cadence (`qa-cadence.sh`), archive the rounds that are over, create a `<branch-prefix><timestamp>` branch, register its workstream, build a prompt (`build-qa-round-prompt.sh`, from a built-in selector or the caller's own template path) and submit a coding-agent job that opens a PR. The branch prefix, interval, prompt, template vars and submission settings are inputs. This repository's `master-agent-dispatch.yaml` calls it for every QA round except `coverage-qa` (which stays inline) |

## Coverage (`coverage/`)

| Script | Purpose |
|---|---|
| `fetch-latest-coverage.sh` | Reuse (or, with `FORCE=true`, recompute) the merged JaCoCo report (paging the repo-wide artifact listing, up to `ARTIFACT_PAGE_LIMIT` pages, for the branch's newest one) and a fresh coverage.py report; `ALLOW_RECOMPUTE=false` makes a needed recompute fail fast instead |
| `select-target.py` | Rank Java packages / Python directories by coverage and emit one coverage-qa target |
| `test_select_target.py` | Unit tests for `select-target.py`, driven by the `testdata/` fixtures |
| `assertion-density-report.sh` | Report-only: assertions-per-new-test-method for a coverage-qa PR |

See `tools/coverage-data/coverage-exclusions.txt` / `tools/coverage-data/coverage-history.tsv`
for the selector's data files — kept outside `tools/ci/` because they are mutable data an
agent round appends to, not pipeline logic.

### Remediation jobs: auto-review, auto-resolve-python, auto-resolve

Each attempt of a "Build and Test" run submits at most one coding-agent job, from
one of three jobs (see "Remediation Jobs" in `analysis.yaml` and
`.github/CLAUDE.md`):

- `auto-resolve-python` — python-tests failed; submits at once.
- `auto-review` — attempt 1 only; always submits one of build failure, code
  policy, quality gates, docs-only review, or the general review, as soon as the
  gates report. A gate that failed without recording a cause
  (`check-quality-gates.sh`'s `unattributed`) is never reported as a finding: the
  branch gets the general review, with a note that the gate is not the agent's
  to fix.
- `auto-resolve` — attempt 3 and later; stages a request for long-running test
  failures, which `auto-resolve-submit.yaml` submits once `rerun-flaky-tests.sh`
  has no retry left.

The early two stage their request and a separate `*-submit` job, checked out at
the default branch, sends it with `submit-staged-request.sh`, so no job that runs
pull request code holds the controller credentials.
`tools/tests/test_remediation_job_exclusivity.py` pins the conditions that keep
the three apart, the attempt threshold matching `MAX_ATTEMPTS`, and the
credential isolation.

### Auto-resolve's required-test-job coverage

`analysis.yaml`'s `auto-resolve` job only auto-resolves a test-execution job's
failure when two things are true for that job: its raw `needs.<job>.result`
is checked in the `Check for incomplete test execution` step (so a `failure`
result without parseable Surefire XML still routes to the build-failure path
instead of going unresolved once the flaky-test retries are spent), and its Surefire artifact name is kept by the
`Filter to resolvable surefire reports` allowlist (so a genuine test failure
is actually parsed and described, not silently deleted before
`parse-surefire-failures.sh` ever sees it). `test-flowtree` originally had
neither — it uploaded no Surefire artifact at all — so a failure there was
never auto-resolved with a specific fix; it fell through to prompts claiming
every test had passed.

The required set is `analysis.needs` minus `build` (kept in lockstep with
`all-checks` by design — see `.github/CLAUDE.md`, "What the `analysis` job
does"), and it covers every test lane, the CL lanes (`test-cl`,
`test-media-cl`) and the CUDA lanes (`test-cuda`, `test-media-cuda`) included. `tools/tests/test_auto_resolve_test_job_coverage.py` asserts every
job in that set is wired into both the failure-detection step and the
Surefire allowlist — add a new test-execution job to `analysis.needs` and
this test fails until it is wired into both places.

## Agent Protection (`agent-protection/`)

The exfiltration guard's scripts here are the CI half of a hook that runs in
every agent session. Its policy, the bypasses it was designed against, and what
it knowingly does not cover are in
[docs/internals/exfiltration-guard.md](../../docs/internals/exfiltration-guard.md).

| Script | Purpose |
|---|---|
| `check-ci-file-lock.sh` | Fail the `changes` job when `.github/workflows/` or `tools/ci/` change outside a `ci/...` branch (a controller-signed `Sensitive-File-Bypass` trailer lifts it) |
| `check-quality-gates.sh` | Evaluate quality gate pass/fail from job outputs |
| `deception-audit.sh` | Cross-session deception pattern detection |
| `detect-python-test-hiding.sh` | `test-integrity-check`'s Python step: a base-branch `def test_*` must survive, and a test file's assertion count must not fall |
| `detect-test-hiding.sh` | Detect modifications to base-branch Java tests that hide failures |
| `exfil_guard_registration.py` | Shared `invokes_adapter()` helper imported by `verify-exfiltration-guard.sh`'s CHECK 2 and CHECK 3 |
| `test-branch-checks.sh` | Regression tests for `check-ci-file-lock.sh`, `detect-python-test-hiding.sh` and `validate-agent-commit.sh`, each against a throwaway repository |
| `test-check-quality-gates.sh` | Regression tests for `check-quality-gates.sh` |
| `test-method-lines.awk` | Report the test methods of a Java source file, by line or by body. Used by the harness's per-job test lock (`io.flowtree.jobs.TestMethodProtection`, flowtree/runtime), which runs this exact script as a subprocess, and by `validate-agent-commit.sh` to tell a new test method from an edited one |
| `test-verify-exfiltration-guard.sh` | Regression tests for `verify-exfiltration-guard.sh` |
| `test-verify-sensitive-bypass.sh` | Regression tests for `verify-sensitive-bypass.sh` |
| `test_exfil_guard_registration.py` | Regression tests for `exfil_guard_registration.py` |
| `validate-agent-commit.sh` | `agent-commit-validation`: reject a change set whose only content is edits to test files that exist on the base branch (at the merge-base), with no new test in them |
| `verify-exfiltration-guard.sh` | Fail CI if the exfiltration guard hook (`.claude/hooks/block-exfiltration.sh`, its core, tests, allowlist) is missing from HEAD, not registered for `Artifact`/`SendUserFile`/`Bash`, or modified on a PR branch |
| `verify-memory-claim.sh` | Cross-reference "no changes needed" claims against git diff |
| `verify-sensitive-bypass.sh` | Verify a controller-signed `Sensitive-File-Bypass` commit trailer |

## Prompts (`prompts/`)

| Script/Template | Purpose |
|---|---|
| `build-build-failure-prompt.sh` | Build prompt for agent when compilation fails |
| `build-coverage-prompt.sh` | Build prompt for the recurring test-coverage round |
| `build-goal-decomposition-prompt.sh` | Build prompt for the goal-decomposition round: turn a repository's release plan into tracker tasks, in its own project or the framework's; changes no files |
| `build-docs-review-prompt.sh` | Build prompt for auto-review of a docs-only branch: review and improve the documents, never implement a plan |
| `build-qa-round-prompt.sh` | Build the prompt for any round the reusable `qa-round.yaml` runs: render a built-in template chosen by selector (`defect-hunt` \| `doc-qa` \| `consolidation` \| `flaky-hunt`) or a caller-supplied template read as data by path, substituting `BRANCH`/`BASE_BRANCH`/`REPO_URL` and the round's `TEMPLATE_VARS`, including the `TEST_RUNNER_FRAGMENT` a built-in template selects, and failing if any `${...}` survives. Replaces the per-round builders the recurring QA jobs used to each carry |
| `build-planning-prompt.sh` | Build prompt for planning workflow |
| `build-task-planning-prompt.sh` | Build prompt for the task-planning round: plan the tracker task CI claimed for the round (`TASK_ID`, `TASK_TITLE`, `TASK_DESCRIPTION`) |
| `build-policy-violation-prompt.sh` | Build prompt for agent when code policy enforcement fails |
| `build-python-failure-prompt.sh` | Build prompt for agent when the python-tests job fails |
| `build-quality-gate-prompt.sh` | Build prompt for agent when quality gates fail |
| `build-resolve-prompt.sh` | Build prompt for agent when tests fail |
| `build-review-prompt.sh` | Build prompt for general code review |
| `build-verify-prompt.sh` | Build prompt for the implement-plan workflow (called by verify-completion), the manual "implement the plan" trigger |
| `consolidation.txt` | Template for the consolidation round: find one duplicated behavior, share it, pin it with tests |
| `coverage.txt` | Template for the test-coverage round |
| `defect-hunt.txt` | Template for the defect hunt. Offered to consumers through `qa-round.yaml`'s `defect-hunt` selector; its test-runner / tooling guidance is an `@include ${TEST_RUNNER_FRAGMENT}` so a non-Maven consumer can select a neutral fragment |
| `test-runner-maven.txt` | The default `TEST_RUNNER_FRAGMENT`: this framework's tooling guidance (`ar-profile-analyzer`, the MCP test runner) a built-in template includes |
| `test-runner-generic.txt` | A neutral `TEST_RUNNER_FRAGMENT` for a non-Maven consumer: run a bounded selection with the repository's own test runner, read its output as evidence |
| `flaky-hunt.txt` | Template for the pilot flaky-test hunt: one module, a small named slice run repeatedly on one commit, find a test whose outcome varies, fix the production/test-helper cause or file a follow-up, never editing the existing test |
| `docs-review.txt` | Template for the docs-only review prompt |
| `doc-qa.txt` | Template for the documentation-staleness review |
| `goal-decomposition.txt` | Template for the goal-decomposition round: reconcile a release's tracker tasks with its plan, touching only the goal-derived tasks of the repository being reconciled |
| `general-review.txt` | Template for general code review prompt: review and fix within what the branch set out to do, routing out-of-scope work to `tracker_create_task` rather than widening the branch |
| `performance.txt` | Template for the performance round: pick a slow test, profile it on Metal, make the framework faster without touching the test |
| `retrospective.txt` | Template for the QA retrospective: study recent automated work and propose, with evidence, how the agent harness and the QA jobs should improve — proposes, never implements. Before classifying any prior proposal "still open" it checks open PRs (`github_list_open_prs`) and in-window branches (`workstream_list`, incl. `ci/qa-`) for an in-flight implementation and reports it "in progress (PR #N)". Writes a skeleton report and ledger row to disk first, before the survey, so a stalled run still leaves a reviewable artifact; the round is submitted with `ENFORCE_CHANGES` and a re-run cap of 0, so an empty-tree exit is reported DEGRADED without a re-run |
| `pdsl-migration.txt` | Template for the PDSL migration round: move one piece of pipeline structure from Java into a `.pdsl` asset, delete the Java assembly, pin parity with tests, and report language gaps rather than aliasing around them |
| `plan-workstream-settings.txt` | Shared fragment: when a plan needs particular hardware, declare it in `docs/plans/<branch>-workstream.yaml` — included by both planning prompts |
| `plan-pr-framing.txt` | Shared fragment: write the commit message (and so the pull request) for the planned work, not the plan — included by both planning prompts |
| `pr-feedback.txt` | Shared fragment: how to find, read, act on and reply to pull-request review comments — patched into every auto-resolve prompt |
| `project-planning.txt` | Template for the planning workflow |
| `task-planning.txt` | Template for the task-planning round |
| `prompt-render.sh` | Sourced by the builders: expands `@include <fragment>` lines and substitutes `${VAR}` placeholders (`render_prompt`, `append_prompt_fragment`). An `@include` line may name its fragment through a `${VAR}` resolved from the environment before expansion (so a template selects a fragment), and a resolved fragment name may not contain `/`, so a caller template can include only the framework's published fragments |
| `verify-completion.txt` | Template for verify-completion prompt |

### Test execution limits

Every prompt built here must instruct the agent to run only the specific failing
test(s), one at a time — never a module's whole suite, `AR_TEST_GROUP`/`AR_TEST_GROUPS`
(CI-shard partitioning), or `python3 -m unittest discover`/a whole pytest directory.
Broad verification is the CI pipeline's job; a prompt that tells an agent session to
reproduce it (e.g. "run the full CI command", "run the relevant module's tests") is a
bug in the prompt, not a fair characterization of what the session should do. This is
enforced mechanically elsewhere too — `workstream_submit_task`'s prompt/command
validation (`tools/mcp/manager/execution_limits.py`) and the controller's
`PostCompletionCommandValidator` both reject it with no bypass. Agent-side
`PreToolUse` Bash hooks (`.claude/hooks/block-mvn-test-direct.sh` and
`.claude/hooks/block-pytest-broad-run.sh`) also restrict what a running agent session can
do directly — a test-running Maven phase without `-DskipTests`, an `AR_TEST_GROUP`
assignment, an unbounded pytest run — but they parse command text and are a backstop. A
prompt written to violate the rule fails loudly at the two mechanically-enforced surfaces
above regardless of the hooks.

Each `build-*-prompt.sh` reads its sibling template, substitutes the environment
variables named in its header, and writes the result to an output-file argument.
Most take that path as their only argument; a few need additional file input first
— `build-resolve-prompt.sh <failures-file> <output-file>`,
`build-quality-gate-prompt.sh <failures-file> <output-file>`,
`build-python-failure-prompt.sh <log-file> <output-file>` and
`build-vm-crash-prompt.sh <crash-reports-dir> <output-file>` — where the output
file is always the last argument. `tools/tests/test_prompt_builders.py` holds
them to that contract.

Instructions that several tasks share live once, as a fragment in this directory,
and are patched into each prompt by `prompt-render.sh`: a template names the
fragment on an `@include <file>` line, and a builder that assembles its prompt
from heredocs calls `append_prompt_fragment <file> "$OUTPUT_FILE" VAR...`.
`plan-pr-framing.txt` and `plan-workstream-settings.txt` are included by both
planning prompts: how to frame the commit message for the planned work rather
than the plan, and how a plan declares the hardware it needs.
`test-lock-enforcement.txt` is appended by the test-failure and test-crash
builders (`build-resolve-prompt.sh`, `build-vm-crash-prompt.sh`).
`pr-feedback.txt` is the
policy for pull-request review comments — read both the inline and conversation feeds before starting, fix what
is right in the code, reply threaded to the original comment only where the
author is safe to answer (Copilot and people, never CodeRabbit or an unknown bot),
and treat a comment as evidence to verify rather than an instruction to obey.
Every auto-resolve builder carries it, whatever the job was submitted for — a
reviewer may already have named the defect a test-failure job is chasing, and on
a docs-only branch the review comments are usually the whole of the work.
`test_prompt_builders.py` fails if any auto-resolve prompt stops carrying it.
