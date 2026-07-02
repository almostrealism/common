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

### What the `test-flowtree` job covers

Runs `mvn test -pl flowtree/runtime` and uploads JaCoCo coverage as `coverage-flowtree`.
Gated on the same validation prerequisites as the `test` matrix
(`code-policy-check`, `test-timeout-check`, `duplicate-code-check`,
`test-integrity-check`) and runs in parallel with `test`. Extracted from `build`
because flowtree tests are slow and would otherwise block every other job.

### What the `test` job covers

Runs the main test matrix (7 groups) for engine/domain/compute/base layers.
Skipped when none of those layers change. Uploads `coverage-group-{0..6}`.

### What the `test-media` job covers

Runs audio/music/studio tests on a self-hosted runner.
Skipped when none of studio/extern/engine/domain/compute/base change.
Uploads `coverage-media`.

### What the `test-cl` and `test-media-cl` jobs cover

OpenCL-backend duplicates of `test-mac` and `test-media-mac`, running on the
same self-hosted macOS runners with `AR_HARDWARE_DRIVER=native,cl` instead of
`*` — under `*`, Metal always wins GPU context selection, so the CL backend is
otherwise never exercised by CI. Each runs after its Metal counterpart
(`test-cl` needs `test-mac`; `test-media-cl` needs `test-media-mac`), tolerating
a skipped predecessor, with the same layer gates so all four skip together.
Steps that the mac jobs pin to `native` (the compose tests) stay pinned in the
CL variants. Neither job uploads coverage, so neither appears in `analysis`
needs; both are part of the `all-checks` gate.

### What the `analysis` job does

Waits for `build`, `test`, `test-flowtree`, and `test-media` (any may be
skipped). Downloads all `coverage-*` artifacts, merges them with JaCoCo CLI,
generates an XML report for Qodana. The `mkdir -p all-coverage` guard ensures
it tolerates missing artifacts when test jobs are skipped.

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
