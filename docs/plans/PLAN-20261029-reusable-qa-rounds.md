---
status: proposed
created: 2026-10-29
closed:
tracker: [4c1b3c4e-30e4-461f-bdcc-841d5a24a254]
---

# Make the Master Agent Dispatch QA rounds reusable by other repositories

- **Tracker task:** `4c1b3c4e-30e4-461f-bdcc-841d5a24a254` — "Make the Master
  Agent Dispatch QA rounds reusable by other repositories, as task-planning /
  implement-plan / goal-decomposition already are"
- **Release:** Common 0.76

## Motivation

Commit `e187e0339` made the project-management half of the agent pipeline
reusable: `goal-decomposition.yaml`, `task-planning.yaml`, `implement-plan.yaml`
and `plan-review.yaml` are `workflow_call` workflows that another repository
invokes for its own tracker project, and `master-agent-dispatch.yaml`'s
`plan-release-task` job now *calls* `task-planning.yaml` with `project: Common`,
so this repository runs exactly what its consumers run.

The QA half never got that treatment. `master-agent-dispatch.yaml` (~2000 lines)
holds eight QA-style rounds — `doc-qa`, `defect-hunt`, `flaky-qa`, `coverage-qa`,
`consolidation-qa`, `performance-qa`, `pdsl-qa`, `qa-retrospective` — each
written inline and each a copy of the one before it. Every round hard-codes this
repository (`ref: master`, `BASE_BRANCH: master`, `./tools/ci/...` from the
caller's own checkout, templates written for this codebase). A consuming
repository that wants QA rounds on merge must copy an entire job and its
scripts, and copies drift: a fix to cadence, intake, archiving or the backlog
gate would have to be made in every copy. That is precisely the duplication the
reusable workflows exist to prevent, and the inline copies are also why the
dispatch file is as large and as fragile as it is (the whole reason
`test_master_agent_dispatch.py` exists is to catch a step dropped or a
`BRANCH_PREFIX` left unchanged when the next round was copied from the last).

Extracting the shared round makes the duplicated steps exist once, lets a
consumer adopt QA rounds with a caller job and inputs, and keeps this repository
running the same code its consumers run.

## Scope

In scope (all on the `ci/reusable-qa-rounds` branch, under the CI-file lock):

1. **A new reusable workflow `.github/workflows/qa-round.yaml`** (`workflow_call`
   only) that carries the shared QA-round steps once, parameterised by inputs. Its
   header shows a complete caller example, as `task-planning.yaml`'s does.
2. **Rewriting the QA jobs in `master-agent-dispatch.yaml` that fit the generic
   shape as thin callers of `qa-round.yaml`** (the `plan-release-task`
   precedent), so the intake / cadence / archive / branch / register / submit /
   summary steps live only in the reusable workflow.
3. **Built-in, framework-provided prompt templates** the reusable workflow can
   render (defect hunt, doc QA, consolidation, flaky hunt), plus a
   mechanism for a caller to supply its own template from its own repository,
   **read as data, never executed**.
4. **Generalising the Java/Maven-specific wording** of the built-in templates
   offered to consumers, via a caller-selectable language fragment and/or
   template variables, so a non-Maven repository gets correct instructions.
   Which of the four templates in item 3 can be made honestly generic — and
   whether only the defect hunt (and perhaps the flaky hunt) is offered first,
   leaving `doc-qa` and `consolidation` as this repository's own callers until
   a concrete non-Maven consumer appears — is Open question 3; the "Generalising
   the built-in templates" section shows why the `doc-qa` and `consolidation`
   templates are the doubtful ones.
5. **Test changes** so that `test_master_agent_dispatch.py`,
   `test_automated_intake.py` and `test_reusable_workflows.py` keep holding by
   following each QA caller into the workflow it calls, and a new test proves a
   non-Maven fixture repository can render at least the defect hunt and the doc
   QA for real (or the defect hunt alone, if Open question 3 narrows the first
   offered set).
6. **Documentation**: `.github/CLAUDE.md` (the Master Agent Dispatch section and
   "The other reusable workflows"), `tools/ci/README.md` ("Reusable workflows"),
   and `flowtree/runtime/docs/ci-integration.md`.

Explicitly **out of scope** (named as dependencies / open questions rather than
widened into here):

- **No new Maven module and no `pom.xml` edits.** None are needed; a workflow is
  not a module.
- **Making `coverage-qa` a generic offered round.** Its target-selection pipeline
  reads JaCoCo and coverage.py and interleaves a branch-side commit with branch
  creation (see Approach); forcing it into the single-job generic shape is a
  larger change and is treated as an open question, with a recommendation to
  leave it dedicated/inline for this change.
- **Making `performance-qa` / `pdsl-qa` / `qa-retrospective` into *offered*
  built-in rounds.** Their prompts are about this framework's own concerns (Metal,
  PDSL; the retrospective's `docs/qa/retrospectives/` ledger, `ci/qa-` branches
  and the `flowtree/` / `tools/mcp/` harness layout). They can still
  *call* the reusable workflow to remove the duplicated plumbing, passing their
  own local template as data, but they are not generalised into templates a
  consumer would select.
- **The Plan Ready (`ready-plan.yaml`) analogue for consumers.** That is a
  separate concern (a prior session's note already records it) and is not part of
  this task.

## Approach

### The shared shape, as it is today

Every QA round in `master-agent-dispatch.yaml` runs the same ordered steps
(confirmed by reading `doc-qa`, `defect-hunt`, `flaky-qa`, `coverage-qa`,
`consolidation-qa`, `performance-qa`, `pdsl-qa`, `qa-retrospective`):

1. **Checkout** master with `fetch-depth: 0`.
2. **Intake** — `tools/ci/automated-intake.sh`; the cadence gate runs only when
   `steps.intake.outputs.open == 'true'`.
3. **Cadence** — `tools/ci/qa-cadence.sh` with the round's `BRANCH_PREFIX`,
   `MIN_INTERVAL_DAYS`, `PR_GRACE_HOURS=24`, `CHECK_BACKLOG=true`,
   `IGNORE_INTERVAL` (the round's `ignore_interval_*` dispatch input) and `FORCE`.
4. **Archive** — `tools/ci/archive-stale-workstreams.sh`, gated
   `run == 'true' && reason != 'forced'`.
5. **Create branch** `<prefix><UTC timestamp>` and push it.
6. **Register workstream** — `tools/ci/register-workstream.sh` (optionally with
   `REQUIRED_LABELS_JSON`).
7. **Build prompt** — `tools/ci/prompts/build-<round>-prompt.sh` rendering
   `<round>.txt` with round-specific variables.
8. **Submit** — `tools/ci/submit-agent-job.sh` with `AUTO_CREATE_PR=true` and the
   per-round submission settings; then a **Summary** step that reports intake and
   the gate's `reason`.

The only per-round differences are the `BRANCH_PREFIX`, the interval, the prompt
template and its variables, and the submission settings. Tabulated from the
current workflow:

| Round | prefix | interval | protect-test-files | enforce-changes (retries) | phase pin | required-labels | extra |
|---|---|---|---|---|---|---|---|
| `doc-qa` | `qa/docs-` | 5 | false | — | — | — | `REVIEW_SINCE` |
| `defect-hunt` | `qa/defect-` | 2 | false | — | opus | — | — |
| `flaky-qa` | `qa/flaky-` | 7 | true | — | opus | — | pilot |
| `coverage-qa` | `qa/coverage-` | 2 | false | true | — | — | target selection, `actions: read`, exclusion commits |
| `consolidation-qa` | `qa/consolidate-` | 2 | true | — | opus | — | — |
| `performance-qa` | `qa/performance-` | 2 | true | — | opus/max | `{"platform":"macos"}` | `REQUIRED_LABELS_JSON` on register |
| `pdsl-qa` | `qa/pdsl-` | 5 | true | — | opus/max | — | — |
| `qa-retrospective` | `qa/retro-` | 7 | true | true (0) | opus/max | — | `REVIEW_SINCE`, `QA_PREFIXES` |

### The reusable workflow: `qa-round.yaml`

One `workflow_call` workflow with a **single job** holding all of steps 1–8
above. A caller job that `uses:` a reusable workflow cannot run steps of its own,
so the checkout of the caller's tree (step 1) happens inside the reusable job:
it checks out `github.event.repository.default_branch`, not a literal `master`,
with credentials persisted so the job can push the round's branch; a second
`persist-credentials: false` checkout of the framework at `framework-ref`
supplies every script and template (the layout `task-planning.yaml` already
uses). Inputs, grouped:

- **Identity / cadence:** `round-name` (used in labels, descriptions and the
  concurrency group), `branch-prefix`, `min-interval-days`, `grace-hours`
  (default 24), `max-open-prs` (default 7), `force`, `ignore-interval`.
  A caller's workflow-level `env` does not reach a called workflow, and the
  called job must not read the caller's dispatch inputs by name (it sees the
  caller's `github.event`, but `ignore_interval_docs` and friends are this
  repository's names, not a contract), so each caller passes these explicitly: `max-open-prs` as the same literal the
  workflow's `MAX_OPEN_PRS` holds (as `plan-release-task` already does with a
  comment pointing at it), and `force` / `ignore-interval` as
  `${{ github.event.inputs.<name> || 'false' }}`, since on a `push` event the
  dispatch inputs are empty.
- **Prompt:** `prompt` — a selector for a built-in template
  (`defect-hunt` | `doc-qa` | `consolidation` | `flaky-hunt`),
  or a caller-supplied template **path** within the caller's checkout. Plus
  `template-vars` — a small JSON map (e.g. `{"REVIEW_SINCE": "...", "QA_PREFIXES":
  "..."}`) rendered into the chosen template. A built-in template is read and
  rendered from the framework checkout; a caller template is read as *data* from
  the caller's tree and rendered by the framework's builder — it is never
  executed, exactly as `implement-plan.yaml` reads a dispatched branch's plan as
  data.

  *"The framework's builder" does not exist yet.* Today each round has its own
  `build-<round>-prompt.sh` that validates a fixed list of variables
  (`for var in ...`) and substitutes them with a hand-written `sed` — none of
  the QA builders (`build-doc-qa-prompt.sh`, `build-defect-hunt-prompt.sh`,
  `build-consolidation-prompt.sh`, `build-flaky-hunt-prompt.sh`,
  `build-retrospective-prompt.sh`, `build-performance-prompt.sh`,
  `build-pdsl-migration-prompt.sh`) sources `prompt-render.sh`. The
  implementation therefore adds one generic builder (e.g.
  `build-qa-round-prompt.sh`) that turns `template-vars` into environment
  variables, always supplies `BRANCH`, `BASE_BRANCH` and `REPO_URL`, renders
  through `render_prompt`, and fails if any `${...}` placeholder survives. That
  builder does not fit the shape `test_prompt_builders.py` checks for (a fixed
  `for var in ...` list documented in a `Required environment variables:`
  header), so the plan must say whether the per-round builders are kept as thin
  wrappers that pass their fixed variable list to the generic builder (keeping
  those tests meaningful), or are deleted and the builder tests extended to
  cover the generic one (see Open questions).
- **Submission settings:** `protect-test-files`, `enforce-changes`,
  `max-enforce-changes-retries`, `phase-configs`, `required-labels` (and the
  matching workstream `required-labels-json`), `description`. `pr-draft` is not
  an input (or is fixed to `false`): a QA round's PR is real work meant to be
  merged as it stands, never a draft (a draft PR marked ready would dispatch an
  implementation of a branch with nothing to implement).
- **Trust / controller:** `framework-repository` (default `almostrealism/common`),
  `framework-ref` (default `master`), `controller-url`, `cf-access-client-id`,
  and the declared `workflow_call` secret `FLOWTREE_CF_ACCESS_CLIENT_SECRET`
  (`required: false`) — identical to the four existing reusable workflows.

The job's own `concurrency` group includes the calling repository **and** the
round, e.g. `qa-round-${{ github.repository }}-${{ inputs.round-name }}`
(`cancel-in-progress: false`), so two merges to one repository cannot both pass a
round's gate, and different rounds in one repository do not serialize against
each other. This mirrors `task-planning.yaml`'s
`task-planning-${{ github.repository }}` group.

Default branch, base branch and `REPO_URL` all derive from
`github.event.repository.default_branch` / `github.repository`, never a literal
`master` / `almostrealism/common`.

### Which rounds become callers, and which stay

- **Plain callers with a built-in template:** `doc-qa`, `defect-hunt`,
  `consolidation-qa`, `flaky-qa`. Each becomes a `uses: ./.github/workflows/qa-round.yaml`
  job in `master-agent-dispatch.yaml` passing its prefix, interval, template
  selector, template vars and submission settings.
- **Callers with a local, framework-specific template (stay "local" in content,
  deduplicated in plumbing):** `performance-qa`, `pdsl-qa`, `qa-retrospective`.
  Each calls `qa-round.yaml` but passes its own this-repository template (read as
  data) and its phase pin / `required-labels` / `enforce-changes`. Their prompts
  are not offered to consumers.
- **Stays dedicated/inline (recommended):** `coverage-qa`. It does not fit the
  single-job generic shape: `select-target.py` runs *before* branch creation and
  gates the whole round; an auto-exclusion commit is made either to master (when
  no round opens) or to the round's own new branch (when one does), interleaved
  with branch creation; and it needs `permissions: actions: read` to reuse
  master's coverage artifact. Offering it generically also requires pluggable
  target selection (its selector reads JaCoCo + coverage.py, this framework's
  tooling). Recommendation: leave `coverage-qa` as it is for this change and list
  it among the rounds that stay local, with its reason — unless the approver
  wants the larger `coverage-round.yaml` extraction now (open question below).

Each caller job keeps its own `concurrency` group (`quality-assurance`,
`defect-hunt`, …, unchanged), its `agent`-selector `if` and its `ignore_interval_*`
dispatch input, so the dispatch interface `master-agent-dispatch.yaml` exposes is
unchanged and `tools/mcp/manager/project_tools.py` (which dispatches the file by
name with `agent: project-manager`) keeps working.

### Generalising the built-in templates

No built-in QA template is generic today. What each one carries that a
non-Maven consumer must not receive:

- `defect-hunt.txt` is the closest: no Java/Maven/`pom.xml` wording, but its
  step 3 names this framework's `ar-profile-analyzer` ("reads the generated
  kernel source and argument bindings") and "the MCP test runner". Those two
  sentences move into the fragment below; the rest is offered as-is.
- `flaky-hunt.txt` — "a new Maven module is out of scope" and two
  `mcp__ar-test-runner__start_test_run module:... test_classes:[...]` examples.
- `consolidation.txt` — reading both `pom.xml` files to verify a dependency
  edge, "no Maven module / `pom.xml` / dependency", `timeout 2400 mvn clean
  install -DskipTests`, and the Java code standards (no `var`, no
  `@SuppressWarnings`, Javadoc, `@Test` timeouts, `TestSuiteBase`).
- `doc-qa.txt` — more than test-runner wording: its review *scope* is Java
  (`git diff ... -- '**/*.java' '**/pom.xml'`, Javadoc on public classes,
  `pom.xml` as the source of truth for versions and module structure,
  `mvn install -DskipTests` after a Javadoc edit).
- `retrospective.txt` is **not** offered (see "Which rounds become callers"): it
  is built around this repository's `docs/qa/retrospectives/ledger.tsv`, the
  `ci/qa-` branch convention and CI-file lock, "harness Java under `flowtree/`,
  MCP tools under `tools/mcp/`", and the root `CLAUDE.md` "This Repository Is
  Public" section.

The plan moves that language into caller-selectable fragments rendered by
`prompt-render.sh`, which already supports one-level `@include <file>`
expansion and `${VAR}` substitution — e.g. a `test-runner-guidance` fragment
with a Maven/Java default that this repository selects, so a non-Maven consumer
selects its own (or a neutral) fragment and does not receive instructions to
run `mvn` or extend `TestSuiteBase`. Two mechanical constraints the
implementation has to handle:

- `expand_prompt_includes` resolves an `@include` line *before* any `${VAR}` is
  substituted, and only from `tools/ci/prompts/` of the framework checkout. A
  line like `@include ${TEST_RUNNER_FRAGMENT}` therefore does not work today;
  either the generic builder picks the fragment and substitutes it as a
  variable, or the renderer learns to substitute a fragment name first.
- Because a caller template is rendered with the same `render_prompt`, an
  `@include ../...` line in it would read any file of the framework checkout.
  That is not a secret leak (the checkout is public and holds no credentials),
  but the renderer should reject a fragment name containing `/` so a caller
  template can include only the framework's published fragments.

`doc-qa.txt`'s Java scope may not reduce to one fragment at all (see Open
questions). Rounds whose templates cannot be made honestly generic stay local
(see above).

### Trust rule (unchanged from the existing four)

Every script and every built-in template runs from the explicit
`framework-repository`@`framework-ref` checkout (`persist-credentials: false`),
never the caller's tree. No input is interpolated into a `run:` script; inputs
reach scripts through `env`. `controller-url` and `cf-access-client-id` are
inputs (default to this repository's tunnel / an empty string); the client secret
is the declared `workflow_call` secret. A caller-supplied prompt template is read
as data and rendered by the framework's builder.

### Test adaptation (guarantees unchanged, discovery extended)

`test_master_agent_dispatch.py` and `test_automated_intake.py` already follow a
caller into the workflow it calls via `called_job()` and `controller_url()` (that
is how they cover `plan-release-task` today). The QA-round assertions must do the
same:

- `_qa_jobs()` currently discovers rounds by `BRANCH_PREFIX` in the job's `env`.
  After extraction a QA caller has `with:` inputs, not that `env`, so the helper
  must discover QA callers (by `uses:` pointing at `qa-round.yaml`, or by their
  `agent` selectors) and resolve each into `qa-round.yaml`'s single job, reading
  the caller's `with:` where the inline job read a step `env` — exactly the
  mapping `controller_url(caller, env)` already performs for the controller URL.
- Discovery has to return a **mixed** set: the callers resolved into
  `qa-round.yaml`, plus `coverage-qa` still inline with its `BRANCH_PREFIX`
  env. `test_the_flaky_pilot_does_not_raise_the_open_pr_limit` counts seven
  full-cadence rounds and `test_the_qa_jobs_are_found` guards against vacuous
  passes, so a discovery that finds only one of the two kinds must fail.
- Several assertions read a value the reusable job now receives as an input, and
  need the caller-`with:` mapping `test_both_planning_rounds_share_one_backlog_limit`
  already applies to `plan-release-task` (where the called job's value is
  `${{ inputs.max-open-prs }}` and the real number is the caller's `with:`):
  `test_every_qa_round_applies_the_shared_backlog_limit` (today it asserts no
  `MAX_OPEN_PRS` in the job or gate `env`; in `qa-round.yaml` the gate *must*
  carry it from the input, and the check becomes "every caller passes the
  workflow's value"), `test_every_qa_job_builds_a_prompt_that_exists` (today it
  expects exactly one `build-<round>-prompt.sh` per job; with a generic builder
  it must check the caller's `prompt` selector or template path instead), and
  the per-round submission settings below (read from `with:` instead of the
  submit step's `env`). The guarantee each asserts is unchanged; the place it
  reads the value from changes.
- The remaining assertions stay as they are: unique branch prefixes
  (`test_every_qa_job_has_its_own_branch_prefix`), the documented interval map
  (`test_qa_intervals_match_the_documented_cadence`), a per-round
  `ignore_interval_*` boolean input, a positive `PR_GRACE_HOURS`, no `PR_DRAFT`
  on a QA round, the shared `MAX_OPEN_PRS` with no per-round override, each
  caller's own non-cancelling concurrency group, the dispatch selectors, and the
  per-round submission settings (`performance-qa`'s `required-labels` + opus/max,
  `pdsl-qa`'s opus/max + test lock, `qa-retrospective`'s enforce-changes + cap 0,
  `flaky-qa`'s test-locked opus pilot that does not raise `MAX_OPEN_PRS`).
- `test_reusable_workflows.py`: add `qa-round.yaml` to `_REUSABLE` so the trust
  rule (only-called, optional explicit secret, scripts from the framework
  checkout, pinned `framework-ref` + `persist-credentials: false`, token only
  through the environment) is pinned for it too.

A **new** fixture-driven test (in `test_reusable_workflows.py` or a sibling
`test_qa_round.py`) runs `qa-round.yaml`'s cadence-gate and prompt-build steps
for real against a **non-Maven** fixture repository — the way
`TaskPlanningReleaseStepTests` and `PlanReviewDocsCheckTests` run the release and
docs-only steps — and asserts that the defect-hunt and doc-QA prompts render with
no surviving `${...}` placeholders and (for the non-Maven caller) without
Maven-only instructions.

## Success criteria

A reviewer can confirm the task is done when:

1. `.github/workflows/qa-round.yaml` exists, is `workflow_call`-only, and its
   header shows a complete caller example (as `task-planning.yaml`'s header
   does).
2. Every QA round in `master-agent-dispatch.yaml` except `coverage-qa` calls
   `qa-round.yaml` (seven callers); `coverage-qa` stays inline, and the rounds
   whose *templates* stay local (`performance-qa`, `pdsl-qa`,
   `qa-retrospective`) are listed with their reasons in `.github/CLAUDE.md`. The
   intake / cadence / archive / branch / register / submit / summary steps exist
   once in `qa-round.yaml`, plus the one remaining copy in `coverage-qa` (unless
   Open question 1 is decided the other way).
3. A consuming repository can run at least the defect hunt and the doc QA with
   only a caller job and inputs, proven by the new non-Maven fixture test running
   those rounds' gate and prompt-render steps for real. (If Open question 3 is
   decided to offer only the defect hunt first, this criterion narrows to the
   defect hunt alone, as that question notes.)
4. `test_reusable_workflows.py` pins the trust rule for `qa-round.yaml` (and any
   other new reusable workflow).
5. `test_master_agent_dispatch.py` and `test_automated_intake.py` pass and still
   assert every guarantee they assert today (reading a value from the caller's
   `with:` where it moved there, never dropping the check), following each QA caller into
   `qa-round.yaml` as they already do for `plan-release-task`. In particular every
   guarantee the task enumerates still holds: intake checked before anything is
   created and reported in every summary; per-round `ignore_interval_*` +
   `PR_GRACE_HOURS` + forced-run-archives-nothing; one shared `MAX_OPEN_PRS` no
   round overrides and the flaky pilot not raising it; each round's own
   concurrency group with no workflow-level concurrency; QA rounds never
   `PR_DRAFT`; the rounds that keep the test lock keep it; hosted runners only
   with no `CONTROLLER_HOST` fallback.
6. The dispatch interface is unchanged: the `agent` selector options, the
   `ignore_interval_*` inputs, and `project_tools.py`'s dispatch by filename all
   still work.
7. `.github/CLAUDE.md`, `tools/ci/README.md` and
   `flowtree/runtime/docs/ci-integration.md` describe the new workflow.

The relevant tests to run (bounded selections, each within limits): the three
pinning tests named above, plus `test_qa_cadence.py` and `test_prompt_builders.py`
(the generic builder and the fragment change both touch the builders). These are Python tests under
`tools/tests/`, run as individual pytest node ids — broad verification is CI's
job.

## Dependencies

- **CI-file lock:** the workflow and `tools/ci/` changes must land on this
  `ci/...` branch (already the case).
- No external or tracker dependencies; the task's `blocked_by` is empty. The
  reusable-workflow precedent (`e187e0339`) is already merged on master.

## Open questions

1. **`coverage-qa` disposition.** Recommended: leave it dedicated/inline and list
   it as a round that stays local, because its interleaved target-selection +
   branch-side exclusion commit + `actions: read` do not fit the single-job
   generic shape and its selector reads framework-specific coverage tooling.
   Alternative the approver may prefer: extract a dedicated `coverage-round.yaml`
   reusable workflow now (larger change). **Decision needed before
   implementation.**
2. **One generic workflow vs. a small set.** The plan recommends a single
   `qa-round.yaml` plus (optionally) `coverage-round.yaml`. If the approver wants
   the built-in templates offered as distinct named workflows
   (`defect-hunt.yaml`, `doc-qa.yaml`, …) instead of one parameterised workflow,
   that is a larger surface and should be decided here.
3. **Language-fragment granularity.** Is a single `test-runner-guidance` fragment
   enough, or do consumers need finer control (build command, test framework,
   style rules) as separate fragments / template vars? The template survey
   above suggests one fragment is not enough: `consolidation.txt` needs a
   build command, a dependency-verification rule and code standards, and
   `doc-qa.txt`'s whole review scope (`*.java`, Javadoc, `pom.xml`) is
   language-specific. A smaller first step the approver may prefer: offer only
   `defect-hunt` (two sentences to move) and, if needed, `flaky-hunt` (one
   sentence and two examples), and keep `doc-qa` and `consolidation` as callers
   with this repository's templates until a concrete non-Maven consumer's needs
   are known. That would change success criterion 3 to the defect hunt alone.
4. **What happens to the per-round builders.** Keep each
   `build-<round>-prompt.sh` as a thin wrapper over the generic builder (so
   `test_prompt_builders.py`'s required-variable and no-leftover-placeholder
   checks keep covering each round's template), or delete them and extend the
   builder tests to the generic builder plus each built-in template. The plan
   leans towards keeping thin wrappers, because the builder tests are how a
   template placeholder without a matching variable is caught today.
5. **`qa-retrospective`'s `QA_PREFIXES`.** It lists every QA round's prefix by
   hand, and `test_the_retrospective_survey_covers_every_qa_prefix` already
   pins that it covers every discovered `BRANCH_PREFIX` plus `ci/qa-`. That
   test reads the list from the job's `env` and the build step's `env`; after
   extraction the list arrives through `template-vars`, so this is a fourth
   assertion that needs the caller-`with:` mapping. It must not be dropped:
   it is what stops a new round from silently falling out of the survey.
6. **Follow-up filed:** none at this time. If, during implementation, the
   `performance-qa` / `pdsl-qa` template content turns out to be partially
   generalisable, that improvement should be filed as a separate backlog task
   rather than widening this change.

## Estimated complexity

**Large.** The reusable workflow itself is moderate (it mirrors `task-planning.yaml`),
but the breadth makes it large: seven caller rewrites in a ~2000-line
file, a new prompt-fragment mechanism for language-specific wording, a careful
rework of the three pinning tests' *discovery* (while keeping every guarantee
they assert), a new fixture test, and three documentation updates — all under the
CI-file lock where a mistake fails the pipeline before anything builds.
