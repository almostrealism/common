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
flowtreeapi     → utils
graphpersist    → utils
flowtree-python → flowtreeapi
flowtree        → flowtreeapi, flowtree-python, graphpersist, utils-http
tools           → ml
```

**Critical facts:**
- `flowtree` CONSUMES engine-layer modules. No named layer depends on flowtree.
- `tools` CONSUMES `ml` (engine layer). Tools tests run in `code-policy-check`
  and `test-timeout-check`, not in any layer-gated job.
- `graphpersist` and `flowtreeapi` are only consumed by the flowtree family.

---

## How Layer-Gating Works

The `changes` job detects which top-level directories changed and sets flags:

| Flag               | Directory | Jobs gated on it                |
|--------------------|-----------|----------------------------------|
| `base_changed`     | `base/`   | `test`                           |
| `compute_changed`  | `compute/`| `test`                           |
| `domain_changed`   | `domain/` | `test`                           |
| `engine_changed`   | `engine/` | `test`, `test-media`             |
| `extern_changed`   | `extern/` | `test-media`                     |
| `studio_changed`   | `studio/` | `test-media`                     |

**No flag exists for `flowtree/`, `flowtreeapi/`, `graphpersist/`, or `tools/`.**
Changes to those directories set `code_changed=true` (triggering the build) but
no layer flag — so all layer-gated test jobs are skipped. This is intentional:
flowtree tests always run in the `test-flowtree` job regardless of what changed.

### What the `build` job covers

The `build` job always runs when `code_changed=true`. It is the critical path
blocker: every downstream job depends on it, so it MUST stay as short as
possible. It does one thing: `mvn install -DskipTests`. It does not run tests
and does not upload coverage.

### What the `test-flowtree` job covers

Runs `mvn test -pl flowtree` and uploads JaCoCo coverage as `coverage-flowtree`.
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

---

## Common Mistakes to Avoid

**Mistake: Assuming nothing depends on a module after one grep.**
Always grep ALL pom.xml files for `ar-<module-name>` to find every consumer.

**Mistake: Confusing dependency direction.**
`flowtree` depends on `flowtreeapi` (flowtree is the consumer).
`flowtreeapi` does NOT depend on `flowtree`.

**Mistake: Adding a layer flag for a standalone module.**
`flowtree/`, `tools/`, `graphpersist/` are not layers. Their tests run in
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
