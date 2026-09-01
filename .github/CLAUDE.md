# CI Pipeline — Guidelines for Claude Code

This file is the authoritative reference for understanding and modifying the CI
pipeline in `.github/workflows/analysis.yaml`. **Read this file before touching
any CI configuration.**

---

## Module Dependency Graph

The project has a strict layered architecture. Dependencies only flow downward
(higher layers consume lower layers; lower layers never depend on higher ones).

```
Layer 6 — Studio
  studio/compose    → music, ml
  studio/music      → audio
  studio/spatial    → compose
  studio/experiments→ compose, ml-onnx, ml-djl, ml-script, utils

Layer 5 — Extern
  extern/ml-djl     → ml
  extern/ml-onnx    → ml
  extern/ml-script  → ml

Layer 4 — Engine
  engine/utils      → space, chemistry, optimize
  engine/utils-http → utils
  engine/ml         → utils
  engine/audio      → utils
  engine/optimize   → graph
  engine/render     → space, utils

Layer 3 — Domain
  domain/graph      → geometry, heredity
  domain/space      → graph, physics
  domain/physics    → time, color
  domain/color      → geometry, stats
  domain/chemistry  → physics, heredity
  domain/heredity   → time

Layer 2 — Compute
  compute/algebra   → hardware
  compute/geometry  → algebra
  compute/stats     → algebra
  compute/time      → geometry

Layer 1 — Base
  base/hardware     → collect
  base/collect      → code
  base/code         → relation, io
  base/relation     → meta
  base/io           → meta
  base/meta         (root — no AR dependencies)
```

### Standalone Modules (above engine layer)

These modules are **not part of any named layer** and have their own test jobs or
run in the build job. They depend on engine-layer modules but nothing in the
named layers depends on them.

```
flowtree/api          → utils
flowtree/base         → io
flowtree/graphpersist → utils
flowtree/agents       → flowtree/base, meta
flowtree/python       → flowtree/api
flowtree/runtime      → flowtree/api, flowtree/base, flowtree/agents, flowtree/python, flowtree/graphpersist, utils-http
tools                 → ml
```

(Artifact IDs: `ar-flowtreeapi`, `ar-flowtree-base`, `ar-flowtree-agents`, `ar-flowtree-python`, `ar-flowtree-runtime`, `ar-graphpersist`.)

**Critical facts:**
- `flowtree/runtime` CONSUMES engine-layer modules. No named layer depends on the flowtree family.
- `tools` CONSUMES `ml` (engine layer). Tools tests run in `code-policy-check`
  and `test-timeout-check`, not in any layer-gated job.
- `flowtree/api`, `flowtree/base`, `flowtree/agents`, `flowtree/graphpersist`, and `flowtree/python` are only consumed by the flowtree family.
- `flowtree/agents` holds the `AgentRunner` abstraction + `ClaudeCodeRunner`; the runtime
  depends on it (not the reverse), and the registry's hard-wired `ClaudeCodeRunner::new`
  default is now in this module — runners that need flowtree/runtime types must
  register themselves via `AgentRunnerRegistry.register(...)` from a higher module.

---

## How Layer-Gating Works

The `changes` job detects which top-level directories changed and sets flags:

| Flag               | Directory                  | Jobs gated on it                |
|--------------------|----------------------------|----------------------------------|
| `base_changed`     | `base/`                    | `test`                           |
| `compute_changed`  | `compute/`                 | `test`                           |
| `domain_changed`   | `domain/`                  | `test`                           |
| `engine_changed`   | `engine/`                  | `test`, `test-media`             |
| `extern_changed`   | `extern/`                  | `test-media`                     |
| `studio_changed`   | `studio/`                  | `test-media`                     |
| `python_changed`   | any `*.py` + `tools/mcp/requirements.txt` | `python-tests`    |
| `agent_isolation_changed` | agent compose/entrypoint + isolation validator (+ `analysis.yaml`) | `agent-volume-isolation` |
| `images_changed`   | Dockerfiles, `.dockerignore`, compose, `tools/mcp/`, `tools/tracker/`, `docs/`, `CLAUDE.md` | `docker-build` |

**No flag exists for `flowtree/` or `tools/` Java code.**
Changes to those directories set `code_changed=true` (triggering the build) but
no layer flag — so all layer-gated test jobs are skipped. This is intentional:
flowtree tests always run in the `test-flowtree` job regardless of what changed.
The `python_changed` flag is a path-based (not layer-based) flag that gates
`python-tests`; Python sources are not part of the layered Java module graph.
The `agent_isolation_changed` flag is likewise path-based and gates
`agent-volume-isolation` (folded in from a former standalone workflow).

### What the `agent-volume-isolation` job covers

Runs the Python validator (`tools/ci/validate_agent_volume_isolation.py`) and its
unit tests, enforcing that FlowTree agent containers cannot share a writable
volume. Path-gated on `agent_isolation_changed`; depends only on `changes` (no
Maven build). Does not upload coverage. Part of the `all-checks` gate (skipped →
treated as passing).

### What the `auto-resolve` job covers (and the `Auto-Resolve Submit` split)

`auto-resolve` parses the pipeline results, decides which agent prompt to build,
and **stages** the request as the `auto-resolve-request` artifact. It carries no
`environment:` and never submits to the controller itself. A separate
`workflow_run`-triggered workflow (`.github/workflows/auto-resolve-submit.yaml`)
downloads that artifact and performs the `worker`-environment-gated submission.

This split exists because a job with `environment:` in a `pull_request` run
attaches a GitHub Deployment status to the PR head; an abandoned/cancelled
`worker` deployment then shows as a spurious "had a problem deploying" red X on
the PR. Running the environment-gated submit from `workflow_run` attaches the
deployment to the default-branch context instead, keeping it off the PR while
preserving the required-reviewers approval gate. `auto-resolve` is excluded from
`all-checks`; neither it nor the submit workflow is a quality signal.

### What the `build` job covers

The `build` job always runs when `code_changed=true`. It is the critical path
blocker: every downstream job depends on it, so it MUST stay as short as
possible. It does one thing: `mvn install -DskipTests`. It does not run tests
and does not upload coverage.

### Every module with tests must be named by some job

A Maven module whose tests no job runs is worse than one with no tests: new
tests get added there and silently never execute, so the module reads as
covered. **Whenever you add a module to the root `pom.xml`, add it to a test job
in the same change**, even if it has no tests yet — that way the first test
committed to it runs immediately.

To audit the current state:

```bash
CI_RUN="base/hardware base/io engine/utils engine/utils-http engine/ml engine/render \
engine/audio extern/ml-onnx studio/music studio/compose studio/spatial studio/experiments \
flowtree/api flowtree/base flowtree/python flowtree/agents flowtree/graphpersist \
flowtree/runtime tools"
for f in $(find . -maxdepth 3 -name pom.xml | grep -v target | sort); do
  m=$(dirname $f | sed 's|^\./||'); [ "$m" = "." ] && continue
  n=$(find "$m/src/test" -name '*Test*.java' 2>/dev/null | wc -l); [ "$n" -eq 0 ] && continue
  case " $CI_RUN " in *" $m "*) ;; *) echo "UNCOVERED: $m ($n test files)";; esac
done
```

Keep `CI_RUN` above in sync with the `-pl` arguments in the job steps.

### What the `test-flowtree` job covers

Runs one Maven invocation over **every** flowtree module —
`flowtree/api,flowtree/base,flowtree/python,flowtree/agents,flowtree/graphpersist,flowtree/runtime`
— and uploads JaCoCo coverage as `coverage-flowtree`. `api` and `python` carry
no tests today and are listed anyway, per the rule above. Gated on the same
validation prerequisites as the `test` matrix (`code-policy-check`,
`test-timeout-check`, `duplicate-code-check`, `test-integrity-check`) and runs in
parallel with `test`. Extracted from `build` because flowtree tests are slow and
would otherwise block every other job.

### What the `test` job covers

Runs the main test matrix (8 groups) for engine/domain/compute/base layers:
`base/hardware`, `engine/utils`, `engine/ml`, plus `engine/render` and
`base/io,engine/utils-http` on group 0 only. Skipped when none of those layers
change. Uploads `coverage-group-{0..7}`.

### What the `test-media` job covers

Runs `engine/audio`, `studio/music`, `studio/compose`, `studio/spatial`, and
`extern/ml-onnx,studio/experiments` on a self-hosted runner. Runs after `test`
(CPU lane, below). Skipped when none of
studio/extern/engine/domain/compute/base change. Uploads `coverage-media`.

### Tests excluded from the pipeline profile

`@TestProperties(excludeProfiles = TestUtils.PIPELINE)` makes `TestDepthRule`
skip a method via `Assume.assumeTrue()` when `AR_TEST_PROFILE=pipeline`, while
leaving it runnable locally. This is the sanctioned mechanism for a test CI
cannot host — prefer it over an early `return`, which reports the test as
**passed**. Note that `@TestDepth` and `longRunning()` will not do this: the
pipeline profile deliberately ignores both.

Three methods carry it, each because it needs an asset or port the runners do
not have. Drop the annotation if that ever changes:

| Test | Reason |
|------|--------|
| `OnnxPrototypeDiscoveryTest.discoverWithOnnxFeatures` (studio/experiments) | `Assert.fail()`s without the ONNX encoder/decoder models and a real sample library |
| `OnnxAutoEncoderTests.encode` (extern/ml-onnx) | loads a model from `assets/stable-audio`; NPEs from the ONNX runtime when absent |
| `EventDeliveryTest.deliver` (engine/utils-http) | binds fixed port 8080 (`BindException` if occupied) and sleeps 57s while asserting nothing |

In all three cases the **module** stays in the test matrix, so a test added
there later runs with no further wiring. That is the point: exclude a method,
never a module.

### A `needs` chain requires `!cancelled()` to tolerate a skipped stage

GitHub applies an implicit `success()` to every job in `needs` when a job-level
`if:` contains no status check function (`always()`, `!cancelled()`, `failure()`,
`success()`). A `needs.<job>.result == 'skipped'` clause is therefore **dead
code** on its own — the dependent job is skipped before the `if` is evaluated.

Every lane-chained job (`test-media`, `test-media-mac`, `test-cl`,
`test-media-cl`) starts its `if:` with `!cancelled() &&` for this reason. Their
explicit `result == 'success'` checks on `build` and the four validation jobs
already exclude every failure path, so `!cancelled()` does not weaken the gate —
it is what makes the gate run at all. Never add a lane stage that depends on a
layer-gated stage without it.

### Three lanes: CPU (linux), GPU (macOS), and CL (linux/ROCm)

The self-hosted test jobs run as three independent, parallel lanes, each
serialised internally so heavy suites do not contend on their fleet:

- **CPU lane (linux, `ar-ci`):** `test` → `test-media`.
- **GPU lane (macOS, `ar-ci`):** `test-mac` → `test-media-mac`.
  Only one GPU-heavy Metal suite runs on the macOS fleet at a time — this keeps
  the studio benchmark tick tails from ballooning under GPU contention (the
  mechanism behind the PDSL hot-path timeouts).
- **CL lane (linux/ROCm, `ar-ci-cl`):** `test-cl` → `test-media-cl`.
  Serialised for the same reason: the ROCm host has a single GPU.

The CL lane was formerly the third and fourth stages of the macOS GPU lane. It
moved to its own AMD/ROCm fleet (`tools/ci/rocm`) to give the OpenCL backend a
real, non-deprecated OpenCL implementation. The cross-lane gates on `test-mac`
and `test-media-mac` went with it: they existed only to serialise the macOS
GPUs, so keeping them would have left the CL lane idle waiting on unrelated
Metal work.

The `ar-ci-cl` label is deliberately distinct from `ar-ci`. If the ROCm host
also carried `ar-ci` it would start picking up general CPU test jobs, putting
the CL lane behind an unrelated queue.

Each stage gates on the four validation checks directly (it no longer inherits
them by depending on `test`). Within a lane, each stage gates on ALL earlier
stages with success-or-skipped, so a failure anywhere earlier skips the rest of
the lane; a stage skipped by its own layer gate is tolerated. Because the lanes
run in parallel, the mac jobs now run even when the linux `test` job fails.
(Follow-up idea, deferred: replace the coarse job-level layer skip — which loses
the reason a job was skipped and forced the cross-stage gating — with per-job
change-awareness that only runs the modules that changed.)

### What the `test-cl` and `test-media-cl` jobs cover

OpenCL-backend duplicates of `test-mac` and `test-media-mac`, running with
`AR_HARDWARE_DRIVER=native,cl` instead of `*` — under `*`, Metal always wins GPU
context selection, so the CL backend is otherwise never exercised by CI. They
are the two stages of the CL lane (`test-media-cl` after `test-cl`). Every step
in the CL variants uses `native,cl` where its counterpart uses `*`.

They run on the **ROCm fleet** — `[self-hosted, linux, ar-ci-cl]`, an AMD host
with a real OpenCL implementation, set up per `tools/ci/rocm/README.md`. Because
the fleet is Linux, every step uses `LD_LIBRARY_PATH` where the macOS jobs use
`DYLD_LIBRARY_PATH`; `AR_HARDWARE_LIBS` is unchanged and remains the primary
mechanism. `test-media-cl` additionally sets `AR_RINGS_LIBRARY` and
`AR_RINGS_PATTERNS` at job level, pointing at the curated sample library
bind-mounted into the runner container. Do not drop those: the studio benchmarks
either fail outright without the library (a GPU is available, so
`AudioSceneTestBase.requireCuratedLibrary()` fails rather than skips) or fall
back to synthetic samples and report timings that measure nothing real.

The memory settings below (`AR_HARDWARE_MEMORY_SCALE=7`, the 8-group split, the
`_JAVA_OPTIONS` direct-memory caps, `AR_HARDWARE_NATIVE_DIRECT_BUFFERS=disabled`)
were all tuned for Apple's OpenCL on the macOS fleet and moved across unchanged.
The AMD device exposes far more memory, so several are likely over-constrained —
retune them one variable at a time, as measured follow-ups, so a regression stays
attributable.

Most self-hosted jobs use an 8-group matrix (`test`, `test-media`, `test-mac`,
`test-cl`, `test-media-cl`). The CL backend hits its memory ceiling under large
per-group loads even at `AR_HARDWARE_MEMORY_SCALE=7` (the highest scale used
anywhere — the scale is exponential, so raising it further is not an option), so
spreading the tests across eight JVMs keeps each group's load small. Eight groups
also shrink the retry unit: re-running failed jobs re-runs only the failed group,
not the whole suite.

`test-media-mac` is the exception: it uses **4 groups at `max-parallel: 2`**
(up to two groups at a time) rather than eight. As the second GPU-lane stage it
already runs alone on the fleet (no other GPU-heavy stage runs concurrently), so
running two of its four groups at once trades some GPU contention on the
studio benchmark tick tails for shorter wall-clock time.

The `test-mac` and `test-media-mac` (Metal) jobs upload surefire reports
(`surefire-mac-group-*`, `surefire-media-mac-group-*`) so a Metal-specific test
failure a Linux run would not surface is still parsed and auto-resolved. The CL
jobs (`test-cl`, `test-media-cl`) upload no surefire and are the **only** test
results not eligible for auto-resolution.

The CL jobs upload no coverage, so neither appears in `analysis` needs, and
**neither is part of the `all-checks` merge gate**: the CL backend has not been
a focus for some time and carries known flakiness/timeouts predating this
coverage, so the jobs are informational. They report their own pass/fail status
on the PR as independent checks; they just do not decide mergeability. Restore
them to `all-checks` (needs + env + `check_job` + summary lines) once the CL
backend is considered stable again.

### What the `docker-build` job covers

Builds the controller-stack images (`ar-manager`, `ar-memory`, `ar-tracker`) so
a packaging break surfaces on the PR instead of at deploy time. Build only —
nothing is pushed or started. Path-gated on `images_changed` **and nothing
else** (see the flag-contract exception above), depends only on `changes` (no
Maven build). Uploads no coverage, so it does **not** appear in
`analysis`'s `needs`; it **is** part of `all-checks` (skipped → treated as
passing), like `agent-volume-isolation`.

The `images_changed` flag covers everything that goes *into* an image: the
Dockerfiles, `.dockerignore`, the controller compose file, `tools/mcp/`,
`tools/tracker/`, and — because ar-manager bakes the documentation corpus into
its image — `docs/` and `CLAUDE.md`. A docs-only change genuinely produces a
different image, so it is not treated as a docs-only skip here.

The job asserts more than "the build exits 0": it counts the markdown/HTML
files inside the built ar-manager image and fails if the corpus is missing or
truncated, and fails if any `.java` file survived the pruning stage.

**It does not build `flowtree-controller` or the agent image.** Those need the
Maven artifacts (`flowtree/runtime/target`) first, which would put a full
reactor build on this job. The gap is real and has already cost one failed
deploy: a root `.dockerignore` rule added for ar-manager excluded
`**/target/`, which both of those images COPY from, and nothing caught it until
the deploy ran. `tools/tests/test_dockerignore_consistency.py` now covers that
specific class of break statically — it cross-references every Dockerfile's
COPY sources against the ignore rules with no daemon required. A break that
static analysis cannot see (a bad base image, a missing file) still surfaces
only at deploy. This
matters because `_get_docs()` in `server.py` degrades **silently** when the
corpus is absent — a broken image would start cleanly and simply answer without
documentation grounding, which no startup check would catch.

### What the `Deploy Controller Stack` workflow does

Lives in its own file (`.github/workflows/deploy.yaml`), triggered by
`workflow_run` on a **successful master** run of "Build and Test", plus
`workflow_dispatch` for manual deploys.

**It is a separate `workflow_run` workflow for the same reason
`auto-resolve-submit.yaml` is:** a job declaring `environment:` inside a
`pull_request` run attaches a deployment status to the PR head, and a pending
deployment later cancelled shows as a spurious "had a problem deploying" red X.
Never move a deploy job into `analysis.yaml`, and never give a
`pull_request`-reachable job an `environment:`.

It runs on `[self-hosted, macos, ar-deploy]` — a **native** macOS runner on the
host that owns the stack, with Docker available to the runner user. The label is
deliberately distinct from `ar-ci` (same reasoning as `ar-ci-cl`): a deploy must
not queue behind the macOS test lane, and the test lane must not pick up
deploys.

Restarting `ar-manager` drops the MCP connection of every in-flight coding-agent
job, so the workflow drains first: it closes job intake via
`POST /api/config/accept-automated-jobs {"accept": false}`, waits with
`tools/ci/drain-agent-jobs.sh` until no job reports an active status, and
**fails rather than forcing** when the wait expires (`skip_drain: true` on a
manual run overrides this deliberately). Intake is reopened in an `always()`
step so a failed deploy cannot leave the controller permanently quiesced.

`concurrency` does **not** cancel in progress: interrupting a half-finished
container rebuild is worse than queueing behind it.

### What the `Master Agent Dispatch` workflow does

Lives in `.github/workflows/master-agent-dispatch.yaml` and holds the three
agent jobs that fire on a merge to master: `plan-next-task` (Project Manager),
`doc-qa` (Quality Assurance) and `defect-hunt`. They were three separate
workflows with byte-identical triggers; merging them keeps the Actions sidebar
navigable without changing what any of them does.

Each job carries its **own** `concurrency` group (`project-manager`,
`quality-assurance`, `defect-hunt`, none cancelling in progress), so they
serialize independently rather than queueing behind one another. Workflow-level
concurrency would couple them — do not add one.

A `workflow_dispatch` selects a single job via the `agent` input
(`all` | `project-manager` | `quality-assurance` | `defect-hunt`); `force` is
passed through to whichever job runs. Each job's `if` is written as
`github.event_name != 'workflow_dispatch' || ...` so a push to master runs all
three.

`tools/mcp/manager/project_tools.py` dispatches this workflow by filename with
`agent: project-manager` (the `project_create_branch` MCP tool). Renaming the
file or the input breaks that tool — update it in the same change.

The policy this reflects: **one workflow per lifecycle event**, with two
deliberate exemptions. `workflow_dispatch`-only workflows stay separate because
dispatch inputs are per-workflow and merging them produces one form of mostly
inert fields (`Deploy Controller Stack` is triggered by hand often enough to
earn its own entry on those grounds alone). Privileged triggers stay separate
too — see `Cancel Merged PR Runs` below.

### What the `Cancel Merged PR Runs` workflow does

Lives in `.github/workflows/cancel-merged-pr-runs.yaml`. When a PR is merged it
cancels that PR's still-running "Build and Test" runs, via
`tools/ci/cancel-merged-pr-runs.sh`. Merging is already a decision that the
checks passed, and the merge commit's own master pipeline re-runs them; the
pre-merge pipeline is holding runners for a settled question.

**It must stay in its own file.** It is triggered by `pull_request_target`,
which grants a writable token in the base-repository context. That is safe here
only because the file contains nothing that checks out or executes PR code —
`actions/checkout` with no `ref` takes the base branch, and the job's only
action is an API call driven by event metadata. Putting it in a file alongside
jobs that build PR code is how `pull_request_target` becomes a token-exfiltration
vector. Never add a `ref:` to that checkout, and never move this job into
`analysis.yaml`.

Scope is `event=pull_request` runs of `analysis.yaml` matching the PR's head
branch **and** head repository. The event filter is load-bearing: under a
fast-forward or rebase merge, master's new head can be the same SHA as the PR
head, so a SHA-based filter would cancel the master pipeline. The
head-repository match keeps a fork's branch from matching a same-named branch
here.

### What the `analysis` job does

Waits for `build`, `test`, `test-flowtree`, `test-media`, `test-mac`, and
`test-media-mac` (any may be skipped). The mac jobs upload no coverage; they are
in `needs` so that the input to `analysis` is not narrower than the input to
`all-checks` — `auto-resolve` depends on `analysis`, so it does not proceed until
the same set of jobs that decide `all-checks` has reported. Downloads all
`coverage-*` artifacts, merges them with JaCoCo CLI, generates an XML report for
Qodana. The `mkdir -p all-coverage` guard ensures it tolerates missing artifacts
when test jobs are skipped.

---

## Rules for Modifying the CI

### Before making any change

1. **Read this file first.**
2. **Read the actual pom.xml files** for every module you are reasoning about.
   Do not state a dependency relationship without quoting pom.xml evidence.
   Use: `grep -o '<artifactId>ar-[^<]*</artifactId>' <module>/pom.xml`
3. **Check both directions.** "Does A depend on B?" and "Does B depend on A?"
   are different questions. Check both by grepping ALL pom.xml files.

### Adding a new test job

- Identify exactly which modules the job tests.
- Trace which layer flags those modules depend on (using the graph above).
- Gate the job on the correct `*_changed` flags.
- Add the job to `analysis`'s `needs` list.
- Add coverage upload following the `coverage-*` naming pattern.

### Adding a new module

- Determine which layer it belongs to based on its dependencies.
- If it introduces a new top-level directory, add a `*_changed` flag to the
  `changes` job and gate the appropriate test job on it.
- If it depends on the `flowtree` family, add its tests to `test-flowtree`
  (this job runs on every code change) — do NOT create a separately-gated job.
- If it depends on `tools`, add its tests to the appropriate tools-using
  job (`code-policy-check` or `test-timeout-check`).

### Changing layer flags

- The `set_all_flags_true` shortcut and the detection loop must list exactly
  the same set of layer names. Keep them in sync.
- Never add a flag that no job consumes — dead flags confuse future agents.
- Non-layer path-based flags (e.g., `python_changed`) follow the same
  contract: detection must run in the `pull_request` branch, `set_all_flags_true`
  must include the flag, and any job gated on the flag must AND it with
  `code_changed == 'true'` so docs-only PRs still skip everything.

  **One sanctioned exception: `docker-build` is gated on `images_changed`
  alone.** The `code_changed` conjunction exists so a docs-only PR skips the
  *test* pipeline, and every other flag-gated job is a test job. `docker-build`
  is not: it verifies a build artifact, and documentation is one of that
  artifact's inputs, because ar-manager bakes the corpus into its image. ANDing
  it with `code_changed` would skip the corpus check on precisely the change
  that alters the corpus — `code_changed` is false for a docs-only PR, since
  the detector excludes `docs/` and `*.md`. Do not "fix" this back to the
  general rule. Any future job in the same position (verifying an artifact
  whose inputs include documentation) belongs in this exception too.

---

## Common Mistakes to Avoid

**Mistake: Assuming nothing depends on a module after one grep.**
Always grep ALL pom.xml files for `ar-<module-name>` to find every consumer.

**Mistake: Confusing dependency direction.**
`flowtree/runtime` depends on `flowtree/api` (core is the consumer).
`flowtree/api` does NOT depend on `flowtree/runtime`.

**Mistake: Adding a layer flag for a standalone module.**
`flowtree/` and `tools/` are not layers. Their tests run in
specific jobs (`test-flowtree`, `code-policy-check`) that always execute on
code changes. Do not create spurious layer flags for them.

**Mistake: Adding test steps to the `build` job.**
`build` is the critical path — every downstream job waits for it. Keep it
limited to `mvn install -DskipTests`. Add new test coverage in a dedicated
job that gates on `build` (and whatever validation jobs make sense) so the
rest of the pipeline is not blocked.

**Mistake: Forgetting to add a new test job to `analysis` needs.**
Every job that uploads `coverage-*` artifacts must appear in `analysis`'s
`needs` list, or analysis will run before coverage is available.

**Mistake: Removing `test` from `analysis` needs.**
The `test` matrix generates the bulk of coverage data. `analysis` must
always wait for it even though it may be skipped on flowtree-only branches.
