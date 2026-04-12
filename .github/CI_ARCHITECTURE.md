# CI Architecture Reference — Almost Realism Common

This document is the authoritative reference for the CI pipeline of the Almost Realism Common project. It is comprehensive enough that a developer or AI agent should never need to reverse-engineer the pipeline from the YAML file alone. Read this document before touching `.github/workflows/analysis.yaml`.

---

## Table of Contents

1. [Project Overview](#project-overview)
2. [Maven Module Architecture](#maven-module-architecture)
3. [Full Dependency Graph](#full-dependency-graph)
4. [CI Workflow Overview](#ci-workflow-overview)
5. [Job Reference: changes](#job-reference-changes)
6. [Job Reference: build](#job-reference-build)
7. [Job Reference: test](#job-reference-test)
8. [Job Reference: test-media](#job-reference-test-media)
9. [Job Reference: code-policy-check](#job-reference-code-policy-check)
10. [Job Reference: test-timeout-check](#job-reference-test-timeout-check)
11. [Job Reference: analysis](#job-reference-analysis)
12. [Coverage Artifact Naming Convention](#coverage-artifact-naming-convention)
13. [Layer-Gating Reference Table](#layer-gating-reference-table)
14. [Dependency Direction Convention](#dependency-direction-convention)
15. [How to Add a New Test Job](#how-to-add-a-new-test-job)
16. [How to Add a New Module](#how-to-add-a-new-module)
17. [How to Modify Layer Flags](#how-to-modify-layer-flags)
18. [Troubleshooting](#troubleshooting)
19. [Pre-Edit Checklist for analysis.yaml](#pre-edit-checklist-for-analysisyaml)
20. [Historical Fixes and Why They Exist](#historical-fixes-and-why-they-exist)
21. [File Locations Reference](#file-locations-reference)

---

## Project Overview

**Almost Realism Common** is a layered Java/Maven monorepo providing mathematical, machine-learning, rendering, physics, audio, and workflow infrastructure. The codebase is organized into strict dependency layers: no module in a lower layer may depend on a module in a higher layer. This invariant is enforced both structurally (Maven) and in CI.

The CI pipeline (`analysis.yaml`) is designed around this layered structure. Jobs are gated on _which layer changed_, so that changes to low-level modules trigger broad test coverage while changes to standalone modules (flowtree, tools) run only the tests relevant to those modules.

---

## Maven Module Architecture

The project uses a strict 6-layer architecture plus several standalone modules that sit above the named layers.

```
Layer 1 — Base:
    base/meta
    base/io
    base/relation
    base/code
    base/collect
    base/hardware

Layer 2 — Compute:
    compute/algebra
    compute/geometry
    compute/stats
    compute/time

Layer 3 — Domain:
    domain/graph
    domain/space
    domain/physics
    domain/color
    domain/chemistry
    domain/heredity

Layer 4 — Engine:
    engine/optimize
    engine/render
    engine/ml
    engine/audio
    engine/utils
    engine/utils-http

Layer 5 — Extern:
    extern/ml-djl
    extern/ml-onnx
    extern/ml-script

Layer 6 — Studio:
    studio/music
    studio/spatial
    studio/compose
    studio/experiments

Standalone (above engine layer, not part of any named layer):
    flowtreeapi     → engine/utils
    graphpersist    → engine/utils
    flowtree-python → flowtreeapi
    flowtree        → flowtreeapi, flowtree-python, graphpersist, engine/utils-http
    tools           → engine/ml
```

### What "standalone" means

Standalone modules are not part of any named layer. They sit conceptually above the engine layer because they depend on engine-layer modules, but they are not part of the engine layer themselves. The CI pipeline does not assign a layer flag to changes in these directories. Instead:

- Changes to `flowtree/`, `flowtreeapi/`, `graphpersist/`, or `flowtree-python/` set only `code_changed=true`. Their tests run inside the `build` job, which always executes on code changes.
- Changes to `tools/` set only `code_changed=true`. Tools tests run inside `code-policy-check`.

This means layer-gated jobs (`test`, `test-media`) are correctly skipped when only standalone modules change — their tests are handled elsewhere.

### Layer boundary enforcement

No module in the named layers (base through studio) depends on any standalone module. Specifically:

- Nothing in base/compute/domain/engine/extern/studio depends on `flowtree`, `flowtreeapi`, `graphpersist`, `flowtree-python`, or `tools`.
- `flowtree` depends on the engine layer — it sits _above_ the engine layer, not alongside it.

This is verified in both directions using `pom.xml` inspection (see [Dependency Direction Convention](#dependency-direction-convention)).

---

## Full Dependency Graph

The following graph is verified from `pom.xml` files. The notation `A → B` means "A depends on B" (A's `pom.xml` contains B as a `<dependency>`).

```
# Layer 1 — Base
base/meta         (root — no AR dependencies)
base/relation     → base/meta
base/io           → base/meta
base/code         → base/relation, base/io
base/collect      → base/code
base/hardware     → base/collect

# Layer 2 — Compute
compute/algebra   → base/hardware
compute/geometry  → compute/algebra
compute/stats     → compute/algebra
compute/time      → compute/geometry

# Layer 3 — Domain
domain/graph      → compute/geometry, domain/heredity
domain/space      → domain/graph, domain/physics
domain/physics    → compute/time, domain/color
domain/color      → compute/geometry, compute/stats
domain/chemistry  → domain/physics, domain/heredity
domain/heredity   → compute/time

# Layer 4 — Engine
engine/optimize   → domain/graph
engine/render     → domain/space, engine/utils
engine/ml         → engine/utils
engine/audio      → engine/utils
engine/utils      → domain/space, domain/chemistry, engine/optimize
engine/utils-http → engine/utils

# Layer 5 — Extern
extern/ml-djl     → engine/ml
extern/ml-onnx    → engine/ml
extern/ml-script  → engine/ml

# Layer 6 — Studio
studio/compose    → engine/audio, engine/ml
studio/music      → engine/audio
studio/spatial    → studio/compose
studio/experiments→ studio/compose, extern/ml-onnx, extern/ml-djl, extern/ml-script, engine/utils

# Standalone
flowtreeapi       → engine/utils
graphpersist      → engine/utils
flowtree-python   → flowtreeapi
flowtree          → flowtreeapi, flowtree-python, graphpersist, engine/utils-http
tools             → engine/ml
```

### Reading the graph

Because dependencies flow downward through layers, a change to a low-level module (e.g., `base/collect`) may affect everything above it. This is why the layer-gating logic in CI is designed so that a change to `base/` triggers both the `test` job and the `test-media` job — the affected scope is potentially the entire codebase.

Conversely, a change to `studio/music` only affects studio-layer code and its consumers (none in the named layers), so only `test-media` needs to run.

---

## CI Workflow Overview

The CI pipeline is defined in `.github/workflows/analysis.yaml`. It consists of the following jobs:

```
changes
  │
  ├── build           (when code_changed)
  ├── test            (when base/compute/domain/engine changed)
  ├── test-media      (when studio/extern/engine/domain/compute/base changed)
  ├── code-policy-check (when code_changed)
  └── test-timeout-check (when code_changed)
                │
          analysis     (after build, test, test-media complete or are skipped)
```

The `changes` job is the gateway. All other jobs depend on its outputs. The `analysis` job runs last, merging coverage from all test jobs and running Qodana static analysis.

### Trigger events

The pipeline triggers on:
- Every push to any branch
- Every pull request

On the `master` branch (and on force-rebuild triggers), the `changes` job sets all flags to `true`, ensuring the full test suite always runs on main.

---

## Job Reference: changes

**Purpose**: Detect which top-level directories changed and set output flags that gate downstream jobs.

**Needs**: none (runs unconditionally)

**Runner**: `ubuntu-latest`

### Output flags

| Flag | Set when... |
|------|-------------|
| `code_changed` | Any code file changed — gates `build`, `code-policy-check`, `test-timeout-check` |
| `base_changed` | Changes detected in `base/` |
| `compute_changed` | Changes detected in `compute/` |
| `domain_changed` | Changes detected in `domain/` |
| `engine_changed` | Changes detected in `engine/` |
| `extern_changed` | Changes detected in `extern/` |
| `studio_changed` | Changes detected in `studio/` |

### Directories with NO dedicated flag

- `flowtree/`
- `flowtreeapi/`
- `flowtree-python/`
- `graphpersist/`
- `tools/`

Changes in any of these directories set `code_changed=true` only. No layer flag is set. This is intentional — see [Layer-Gating Reference Table](#layer-gating-reference-table) for the implications.

### `set_all_flags_true` shortcut

On the `master` branch and on force-rebuild triggers, the job bypasses the per-directory detection loop and directly sets all flags to `true`. This ensures the full test suite runs on every master push.

**Critical invariant**: The list of flags in `set_all_flags_true` and the list of flags in the detection loop MUST be identical. Adding a flag to one section without the other creates a silent bug where certain branches always skip a layer. See [How to Modify Layer Flags](#how-to-modify-layer-flags) for the verification procedure.

---

## Job Reference: build

**Purpose**: Compile all modules and run flowtree-specific tests. Serves as the baseline build that ensures the project compiles before any other tests run.

**Needs**: `changes`

**Condition**: `code_changed == 'true'`

**Runner**: `ubuntu-latest`

### Steps

1. **Checkout** — full clone of the repository
2. **Set up Java** — Temurin distribution (version from project configuration)
3. **Build all modules** — `mvn install -DskipTests`
   - Builds every Maven module in the monorepo
   - Skips tests to keep the build step fast
   - Installs artifacts to local Maven cache for downstream steps
4. **Run flowtree tests** — `mvn test -pl flowtree`
   - Runs the flowtree test suite explicitly
   - Runs unconditionally (not gated on any layer flag) because flowtree changes only set `code_changed`, not any layer flag
5. **Collect JaCoCo coverage** — `find . -path "*/target/jacoco.exec"`
   - Finds all JaCoCo execution data files produced by the flowtree tests
   - Collects from all submodule target directories
6. **Upload coverage artifact** — `coverage-flowtree`
   - `retention-days: 1`
   - `if-no-files-found: ignore`

### Why flowtree tests run here

Flowtree changes only set `code_changed=true`. Layer-gated test jobs (`test`, `test-media`) require at least one of `base_changed`, `compute_changed`, etc. to be true. On a flowtree-only branch, those jobs are skipped entirely. If flowtree tests were in a layer-gated job, they would never run on flowtree-only branches.

Moving flowtree tests into `build` — which runs on every `code_changed=true` — guarantees coverage regardless of which files changed.

### What build does NOT cover

- Layer unit tests (base through studio) — those are in `test` and `test-media`
- Tools tests — those are in `code-policy-check`
- Code policy enforcement — that is in `code-policy-check`

---

## Job Reference: test

**Purpose**: Run unit tests for the base, compute, domain, and engine layers in parallel groups.

**Needs**: `changes`

**Condition**: `base_changed OR compute_changed OR domain_changed OR engine_changed`

**Runner**: `ubuntu-latest` (matrix strategy — 7 parallel groups)

### Steps

1. **Checkout** — full clone
2. **Set up Java** — Temurin distribution
3. **Run test matrix** — 7 groups (labeled 0 through 6), each covering a subset of modules
   - Groups are chosen to balance test duration across parallel runners
   - Each group runs `mvn test -pl <modules>` for its subset
4. **Upload coverage per group** — `coverage-group-{0..6}`
   - Each group uploads its own `coverage-group-N` artifact
   - `retention-days: 1`, `if-no-files-found: ignore`

### Coverage artifacts produced

`coverage-group-0`, `coverage-group-1`, `coverage-group-2`, `coverage-group-3`, `coverage-group-4`, `coverage-group-5`, `coverage-group-6`

All 7 must appear in `analysis.needs` (they are listed via the `test` job dependency, since `analysis` depends on the `test` job completing).

### When skipped

This job is skipped entirely when only the following change:
- `flowtree/`, `flowtreeapi/`, `flowtree-python/`, `graphpersist/` (standalone flowtree family)
- `tools/` (standalone tools)
- `extern/` (extern layer, covered by `test-media`)
- `studio/` (studio layer, covered by `test-media`)

This skip is intentional and correct. The test categories covered by this job are in the base-through-engine layers, and those layers' tests are not needed when only standalone or studio/extern modules change.

---

## Job Reference: test-media

**Purpose**: Run tests that require physical hardware (GPU, audio devices) — covering the studio and extern layers plus any layer those depend on.

**Needs**: `changes`

**Condition**: `studio_changed OR extern_changed OR engine_changed OR domain_changed OR compute_changed OR base_changed`

**Runner**: self-hosted (requires physical GPU and audio hardware not available on GitHub-hosted runners)

### Steps

1. **Checkout** — full clone
2. **Set up Java** — Temurin distribution
3. **Run audio/music/studio tests** — tests requiring hardware media capabilities
4. **Upload coverage** — `coverage-media`
   - `retention-days: 1`, `if-no-files-found: ignore`

### Coverage artifacts produced

`coverage-media`

### When skipped

Skipped when only flowtree-family or tools/ change (no layer flag is set by those directories). This is correct because:

- Studio and extern tests require the base-through-engine layers to function
- If only standalone modules changed, the engine/studio/extern test infrastructure has not changed and does not need re-verification

### Why self-hosted runner

Studio, audio, and extern-ML modules require:
- Physical GPU (for hardware acceleration tests)
- Audio device presence (for audio synthesis tests)
- Potentially large model files cached on the runner

GitHub-hosted runners do not provide these capabilities. The self-hosted runner is a dedicated machine with the required hardware.

---

## Job Reference: code-policy-check

**Purpose**: Enforce the GPU memory model across all modules and run tests for the `tools/` standalone module.

**Needs**: `changes`

**Condition**: `code_changed == 'true'`

**Runner**: `ubuntu-latest`

### Steps

1. **Checkout** — full clone
2. **Set up Java** — Temurin distribution
3. **Run `CodePolicyViolationDetector` tests** — scans all modules for violations of the GPU memory model
4. **Run tools/ tests** — `tools/` has no layer flag and is not covered by any other test job

### What CodePolicyViolationDetector enforces

The GPU memory model prohibits:
- `System.arraycopy` on PackedCollection data
- `Arrays.copyOf` on PackedCollection data
- Tight `setMem` loops (element-by-element memory writes)

The correct pattern for all PackedCollection operations is the Producer pattern:
```java
cp(source).multiply(2.0).evaluate()
```

**Do not circumvent this policy** by:
- Extracting violating code to helper methods
- Naming methods to match an allowlist
- Adding suppression comments

Fix the violating code using the Producer pattern.

### Why tools/ tests are here

The `tools/` module has no layer flag. It depends on `engine/ml` (engine layer). Its changes set only `code_changed=true`. Since `code-policy-check` runs on every `code_changed`, it is the natural home for tools tests.

---

## Job Reference: test-timeout-check

**Purpose**: Verify that tests annotated `@TestProperties(knownIssue = true)` complete within their extended timeout budget.

**Needs**: `changes`

**Condition**: `code_changed == 'true'`

**Runner**: `ubuntu-latest`

### Steps

1. **Checkout** — full clone
2. **Run `@TestProperties(knownIssue = true)` tests** — verifies extended-timeout tests do not hang indefinitely
3. **Run tools/ timeout-specific tests** — tools timeout scenarios

### Purpose of `knownIssue` tests

Some tests are marked `@TestProperties(knownIssue = true)` to indicate they have known intermittent behavior. These tests run with an extended timeout. The `test-timeout-check` job verifies they complete successfully within that extended budget, catching cases where a known-intermittent test has regressed into a permanent hang.

### Coverage artifacts

This job does not upload coverage artifacts. It does not appear in `analysis.needs`.

---

## Job Reference: analysis

**Purpose**: Merge all JaCoCo coverage data from test jobs, generate a combined coverage report, and run Qodana static analysis.

**Needs**: `[build, test, test-media]`

**Condition**: always (runs after needs complete or are skipped)

**Runner**: `ubuntu-latest`

### Steps

1. **Checkout** — full clone
2. **Download all `coverage-*` artifacts** into `all-coverage/`
   - Downloads every artifact whose name starts with `coverage-`
   - This includes: `coverage-flowtree`, `coverage-group-0` through `coverage-group-6`, `coverage-media`
   - **`mkdir -p all-coverage` MUST come before `find`** — see critical invariant below
3. **Merge JaCoCo `.exec` files** — uses JaCoCo CLI to merge all `.exec` files into a single combined file
4. **Generate XML coverage report** — produces the XML report consumed by Qodana
5. **Run Qodana static analysis** — static analysis with coverage overlay

### Critical invariant: `mkdir -p all-coverage`

```yaml
# CORRECT — directory exists before find runs
- name: Merge Coverage Data
  run: |
    mkdir -p all-coverage
    find all-coverage/ -name "*.exec" ...
```

```yaml
# WRONG — find crashes if no artifacts were downloaded
- name: Merge Coverage Data
  run: |
    find all-coverage/ -name "*.exec" ...
```

When all test jobs are skipped (e.g., only flowtree-family files changed), no coverage artifacts are uploaded. The download step produces an empty `all-coverage/` directory — or does not create it at all. Without `mkdir -p`, the `find` command fails with `No such file or directory`, crashing the analysis job.

The `mkdir -p` guard was added in commit 291ea829c. **Do not remove it.**

### Critical invariant: `analysis.needs` completeness

Every job that uploads a `coverage-*` artifact MUST appear in `analysis.needs`. If a coverage-producing job is missing from `needs`, the analysis job may start before that job's artifact is available, causing either missing coverage or a race condition.

Current `analysis.needs`:
```yaml
needs: [build, test, test-media]
```

This covers:
- `build` → produces `coverage-flowtree`
- `test` → produces `coverage-group-0` through `coverage-group-6`
- `test-media` → produces `coverage-media`

If you add a new job that uploads `coverage-*`, you MUST add it to `analysis.needs`.

### Why `code-policy-check` and `test-timeout-check` are not in `analysis.needs`

These jobs do not upload coverage artifacts. There is nothing for analysis to wait on from them. They run in parallel with the test jobs and do not affect the coverage merge.

---

## Coverage Artifact Naming Convention

All coverage artifacts follow the naming pattern `coverage-<descriptor>`. The `retention-days: 1` setting keeps storage costs low — artifacts only need to persist until the `analysis` job consumes them.

| Artifact Name      | Produced By  | Contents                              |
|--------------------|--------------|---------------------------------------|
| `coverage-flowtree` | `build`     | JaCoCo `.exec` from flowtree tests    |
| `coverage-group-0` | `test`       | JaCoCo `.exec` from test group 0      |
| `coverage-group-1` | `test`       | JaCoCo `.exec` from test group 1      |
| `coverage-group-2` | `test`       | JaCoCo `.exec` from test group 2      |
| `coverage-group-3` | `test`       | JaCoCo `.exec` from test group 3      |
| `coverage-group-4` | `test`       | JaCoCo `.exec` from test group 4      |
| `coverage-group-5` | `test`       | JaCoCo `.exec` from test group 5      |
| `coverage-group-6` | `test`       | JaCoCo `.exec` from test group 6      |
| `coverage-media`   | `test-media` | JaCoCo `.exec` from media/studio tests |

All artifacts use:
- `retention-days: 1`
- `if-no-files-found: ignore`

The `if-no-files-found: ignore` setting is important: if a job runs but produces no `.exec` files (e.g., no tests in a group produced coverage), the upload step should not fail. The analysis job handles empty or missing coverage gracefully via the `mkdir -p` guard.

---

## Layer-Gating Reference Table

This table summarizes which CI jobs run for each changed directory. Read each row as: "When files in this directory change..."

| Changed Directory  | Flags Set                | `build` | `test` | `test-media` | `code-policy-check` | `test-timeout-check` |
|--------------------|--------------------------|---------|--------|--------------|---------------------|----------------------|
| `base/`            | `code_changed`, `base_changed` | YES | YES | YES | YES | YES |
| `compute/`         | `code_changed`, `compute_changed` | YES | YES | YES | YES | YES |
| `domain/`          | `code_changed`, `domain_changed` | YES | YES | YES | YES | YES |
| `engine/`          | `code_changed`, `engine_changed` | YES | YES | YES | YES | YES |
| `extern/`          | `code_changed`, `extern_changed` | YES | NO  | YES | YES | YES |
| `studio/`          | `code_changed`, `studio_changed` | YES | NO  | YES | YES | YES |
| `flowtree/`        | `code_changed` only      | YES | NO  | NO  | YES | YES |
| `flowtreeapi/`     | `code_changed` only      | YES | NO  | NO  | YES | YES |
| `flowtree-python/` | `code_changed` only      | YES | NO  | NO  | YES | YES |
| `graphpersist/`    | `code_changed` only      | YES | NO  | NO  | YES | YES |
| `tools/`           | `code_changed` only      | YES | NO  | NO  | YES | YES |

### Observations

1. `build`, `code-policy-check`, and `test-timeout-check` run on **every code change** regardless of which directory changed.
2. `test` only runs when a base/compute/domain/engine layer module changes.
3. `test-media` runs when any named layer changes (it catches changes through the whole dependency graph that could affect hardware-dependent tests).
4. Standalone modules (`flowtree*`, `graphpersist`, `tools`) never trigger `test` or `test-media` — their tests are handled in `build` and `code-policy-check`.

---

## Dependency Direction Convention

This is one of the most common sources of confusion and errors when working with this codebase. The convention is:

```
"A → B" means "A depends on B"
               A's pom.xml lists B as a <dependency>
               B does NOT depend on A
```

### What this means concretely

- `flowtree → flowtreeapi` means flowtree DEPENDS ON flowtreeapi
- flowtreeapi does NOT depend on flowtree
- flowtree depends on the engine layer — it sits ABOVE the engine layer
- Nothing in the named layers (base/compute/domain/engine/extern/studio) depends on flowtree

### How to verify dependency direction

Never make a claim about dependency direction from a single grep. Always check both directions:

```bash
# Does flowtree depend on engine/utils?
grep 'ar-utils' flowtree/pom.xml
# → yes (flowtree → engine/utils via transitive flowtreeapi)

# Does engine/utils depend on flowtree?
grep -rl 'ar-flowtree' engine/utils/pom.xml
# → no (engine/utils has no dependency on flowtree)
```

A grep that finds no match proves only that the searched module does not import the target. It says nothing about the reverse direction.

### Common mistake

"flowtree uses engine/utils" is NOT the same as "engine/utils uses flowtree."

These are two different facts. If you can only cite evidence for one direction, you have only established one direction. State what you verified and do not conflate the two.

---

## How to Add a New Test Job

Follow these steps exactly when adding a new CI test job:

1. **Identify the modules** the job tests. List them explicitly.

2. **Identify the layer flags** those modules depend on. Use the dependency graph above to trace upward. If the modules are in layer N, they transitively depend on layers 1 through N-1.

3. **Choose the gating condition**. The job should run when any of the flags for its dependent layers are true. Use OR logic:
   ```yaml
   if: needs.changes.outputs.domain_changed == 'true' || needs.changes.outputs.compute_changed == 'true'
   ```

4. **Determine coverage artifact name**. Follow the `coverage-<jobname>` convention.

5. **Add to `analysis.needs`** if the job uploads any `coverage-*` artifact. This is mandatory — missing it means coverage data may be unavailable when analysis runs.

6. **Add the upload step**:
   ```yaml
   - name: Upload Coverage
     uses: actions/upload-artifact@v4
     with:
       name: coverage-<jobname>
       path: "**/*.exec"
       retention-days: 1
       if-no-files-found: ignore
   ```

7. **Document the new job** in this file (add a row to the layer-gating table, add a job reference section) and in `.github/CLAUDE.md`.

8. **Verify `analysis.needs`** contains the new job name.

---

## How to Add a New Module

### Module in an existing layer directory

1. Create the module directory and source files. Do NOT create a `pom.xml` — module creation is externally controlled. See the code rules section of CLAUDE.md.

2. Identify which layer it belongs to by examining its dependencies.

3. **If it belongs to base/compute/domain/engine**: No CI changes are needed. The existing `base_changed`, `compute_changed`, `domain_changed`, or `engine_changed` flags will detect changes in its directory.

4. **If it belongs to extern/**: The `extern_changed` flag already covers it. No CI changes needed.

5. **If it belongs to studio/**: The `studio_changed` flag already covers it. No CI changes needed.

6. Consider whether the new module should be added to one of the 7 test groups in the `test` job. This may require rebalancing test group assignments to keep parallel execution times even.

7. **Document the module** in `.github/CLAUDE.md` and in this document's dependency graph section.

### Module in a new top-level directory

This is a rare and significant change. Proceed carefully.

1. Determine whether it is a **new named layer** or a **standalone module**.
   - Named layer: a cohesive set of modules at a consistent abstraction level, with no upward dependencies to standalone modules
   - Standalone: a module with a specific purpose that sits above the named layers

2. **If new named layer**:
   - Add a new `*_changed` flag to the `changes` job
   - Add the flag to BOTH the `set_all_flags_true` block AND the detection loop (they must stay in sync — see [How to Modify Layer Flags](#how-to-modify-layer-flags))
   - Decide which test job gates on this flag (`test` or `test-media` or a new job)
   - If creating a new test job, follow the [How to Add a New Test Job](#how-to-add-a-new-test-job) steps
   - Update the layer-gating table in this document
   - Update `.github/CLAUDE.md`

3. **If standalone**:
   - Do NOT add a layer flag
   - Add tests to the `build` job (runs on every `code_changed`) or `code-policy-check`
   - Update the [Layer-Gating Reference Table](#layer-gating-reference-table) to document that the directory has no flag
   - Update `.github/CLAUDE.md`

---

## How to Modify Layer Flags

The `changes` job has two critical sections that MUST stay in sync:

### Section 1: `set_all_flags_true`

Used on master branch and force-rebuild triggers. Sets all flags simultaneously:
```yaml
- name: Set all flags true
  id: set_all_flags_true
  run: |
    echo "code_changed=true" >> $GITHUB_OUTPUT
    echo "base_changed=true" >> $GITHUB_OUTPUT
    echo "compute_changed=true" >> $GITHUB_OUTPUT
    echo "domain_changed=true" >> $GITHUB_OUTPUT
    echo "engine_changed=true" >> $GITHUB_OUTPUT
    echo "extern_changed=true" >> $GITHUB_OUTPUT
    echo "studio_changed=true" >> $GITHUB_OUTPUT
```

### Section 2: Detection loop

Inspects changed files and sets flags based on directory prefixes:
```yaml
- name: Detect changes
  run: |
    # For each changed file, set the appropriate flag
    if [[ "$file" == base/* ]]; then echo "base_changed=true" >> $GITHUB_OUTPUT; fi
    if [[ "$file" == compute/* ]]; then echo "compute_changed=true" >> $GITHUB_OUTPUT; fi
    # ... and so on
```

### Verification

After any modification to layer flags, run this verification:

```bash
# Extract flags from set_all_flags_true block
grep -A50 'set_all_flags_true' .github/workflows/analysis.yaml | grep '_changed=true' | sort

# Extract flags from detection loop  
grep '_changed=true' .github/workflows/analysis.yaml | grep -v 'set_all_flags_true' | sort
```

Both outputs must list exactly the same set of flags. Any discrepancy is a bug.

### What happens when they diverge

If a flag is in the detection loop but not in `set_all_flags_true`:
- On feature branches: the flag works correctly (detected from changed files)
- On master: the flag is never set, so the associated test job never runs on master

If a flag is in `set_all_flags_true` but not in the detection loop:
- On master: the job always runs (correct)
- On feature branches: the job never runs even when relevant files change (tests are silently skipped)

Both scenarios are silent bugs — no CI failure, just missing test coverage.

---

## Troubleshooting

### Symptom: `find: 'all-coverage': No such file or directory`

**Location**: `analysis` job, `Merge Coverage Data` step

**Cause**: The `analysis` job ran but no coverage artifacts were uploaded (all test jobs that produce coverage were skipped).

**Root cause chain**:
1. Only flowtree-family or tools/ files changed
2. No layer flags were set (`base_changed`, `compute_changed`, etc. all false)
3. Both `test` and `test-media` were skipped (no layer flags)
4. `build` ran but its coverage upload step produced `coverage-flowtree` — however if flowtree tests also produced no `.exec` files, even that artifact may be empty
5. No artifacts were downloaded into `all-coverage/` (or the directory was not created)
6. `find all-coverage/` failed because the directory does not exist

**Fix**: Ensure `mkdir -p all-coverage` appears before any `find` command in the merge step:
```yaml
run: |
  mkdir -p all-coverage
  find all-coverage/ -name "*.exec" ...
```

**Status**: Already fixed in commit 291ea829c. Do not remove the `mkdir -p` guard.

---

### Symptom: Coverage missing from Qodana report

**Cause**: A test job that uploads coverage is not in `analysis.needs`.

**Diagnosis**:
1. Find all `upload-artifact` steps whose `name` starts with `coverage-`:
   ```bash
   grep -n 'name: coverage-' .github/workflows/analysis.yaml
   ```
2. Note which job each upload step belongs to
3. Check whether each of those job names appears in `analysis.needs`:
   ```bash
   grep -A5 'job: analysis' .github/workflows/analysis.yaml | grep needs
   ```
4. Any job name from step 2 not appearing in step 3 is the bug

**Fix**: Add the missing job name to `analysis.needs`.

---

### Symptom: Flowtree tests not running on flowtree-only branch

**Cause**: Flowtree tests are not in the `build` job (or were accidentally moved to a layer-gated job).

**Diagnosis**:
1. Check whether `mvn test -pl flowtree` appears in the `build` job
2. Check whether any layer-gated job (e.g., `test`) runs flowtree tests that should be in `build`

**Fix**: Flowtree tests MUST remain in the `build` job. The `build` job runs whenever `code_changed=true`, which covers flowtree-only branches. No layer-gated job will run on a flowtree-only branch.

---

### Symptom: Test job runs when only flowtree changed

**This is not a bug** — investigate whether the `changes` job is incorrectly setting a layer flag.

**Diagnosis**:
1. Examine the detection loop in the `changes` job
2. Check whether any of the flowtree directories (`flowtree/`, `flowtreeapi/`, etc.) are being matched by a layer detection rule intended for a different directory
3. Common cause: an overly broad directory prefix match (e.g., `flowtree/` matching a rule intended for a different `fl*` pattern)

**Fix**: Correct the detection loop pattern to not match flowtree directories with layer flags.

---

### Symptom: `set_all_flags_true` branch skips a layer

**Cause**: A new flag was added to the detection loop but not to `set_all_flags_true` (or vice versa).

**Diagnosis**: Run the verification command from [How to Modify Layer Flags](#how-to-modify-layer-flags) and compare outputs.

**Fix**: Bring both sections into sync. The list of flags must be identical.

---

### Symptom: analysis job starts before a test job finishes

**Cause**: A test job is producing coverage artifacts but is not in `analysis.needs`.

**Diagnosis**:
- Check all `upload-artifact` steps — any producing `coverage-*` artifacts whose job is not in `analysis.needs` is the issue
- Note: this can be a timing-dependent race that only manifests occasionally

**Fix**: Add the job to `analysis.needs`.

---

### Symptom: HardwareException or OutOfMemoryError in test job

**Cause**: GPU/memory resource exhaustion. Not a CI configuration issue.

**Diagnosis**: Use `ar-jmx` for JVM memory diagnostics. See `tools/mcp/jmx/README.md`.

**Mitigation**: Set `AR_HARDWARE_MEMORY_SCALE` appropriately:
```bash
export AR_HARDWARE_MEMORY_SCALE=6   # 16GB equivalent
```

Do NOT set `AR_HARDWARE_LIBS` manually — the system auto-detects a suitable directory.

---

### Symptom: Build fails at `mvn install -DskipTests`

**Cause**: Compilation error or dependency resolution failure.

**This is a blocking issue** — all downstream test jobs depend on the `build` job completing successfully. Investigate the build output directly. Common causes:
- New code introduced an API incompatibility
- A transitive dependency changed
- A module was added to source without being added to the parent `pom.xml` (module creation is externally controlled — do not create `pom.xml` files)

---

## Pre-Edit Checklist for analysis.yaml

Before making ANY change to `.github/workflows/analysis.yaml`, complete this checklist:

- [ ] **Read `.github/CLAUDE.md`** — the hook `guard-ci-file.sh` injects it automatically when you open `analysis.yaml`, but confirm you have read it
- [ ] **Read this file** (`.github/CI_ARCHITECTURE.md`) in full
- [ ] **For any module dependency claim**, grep ALL `pom.xml` files to verify BOTH directions:
  ```bash
  # What does module A depend on?
  grep -o '<artifactId>ar-[^<]*</artifactId>' A/pom.xml

  # What depends on module A?
  grep -rl 'ar-A' $(find . -name pom.xml -not -path "*/target/*")
  ```
- [ ] **Verify `set_all_flags_true` and detection loop** have exactly the same flags (run the verification command)
- [ ] **Verify `analysis.needs`** includes every job that uploads a `coverage-*` artifact
- [ ] **Verify `mkdir -p all-coverage`** is still present in the analysis merge step before any `find` command
- [ ] **After editing**, run the `validate-ci-edit.sh` check (the hook does this automatically for Edit/Write tool calls targeting `analysis.yaml`)

### If in doubt

When in doubt about a CI change, state your reasoning explicitly, cite the pom.xml evidence for any dependency claim, and verify the structural invariants above before proceeding. The most common CI bugs in this project come from:

1. Missing `mkdir -p all-coverage` guard
2. Missing job in `analysis.needs`
3. `set_all_flags_true` and detection loop out of sync
4. Incorrect dependency direction claim leading to wrong layer gate

All four are caught by following this checklist.

---

## Historical Fixes and Why They Exist

Understanding why past fixes were made prevents re-introducing the same bugs.

### `mkdir -p all-coverage` in analysis merge step (added 2026-04, commit 291ea829c)

**Problem**: On branches where only flowtree-family files changed, both `test` and `test-media` were skipped. No coverage artifacts were uploaded. The `find all-coverage/` command in the analysis job's merge step ran against a directory that did not exist (no artifact download step creates it when there are no artifacts to download). The command exited with `find: 'all-coverage': No such file or directory`, crashing the entire analysis job.

**Fix**: Add `mkdir -p all-coverage` as the first line of the merge step, before any `find` command. This ensures the directory always exists even when no artifacts were uploaded.

**Lesson**: Never assume that a download-artifacts step creates the target directory when there are no artifacts. Always create the directory explicitly.

---

### `coverage-flowtree` artifact from build job (added 2026-04)

**Problem**: Flowtree has its own test suite that runs inside the `build` job (`mvn test -pl flowtree`). Without collecting and uploading JaCoCo `.exec` files from this test run, flowtree code was invisible to Qodana coverage analysis. The coverage report showed flowtree modules as uncovered, despite tests running.

**Fix**: After `mvn test -pl flowtree`, collect `.exec` files with `find . -path "*/target/jacoco.exec"` and upload them as `coverage-flowtree`. Added `build` to `analysis.needs` to ensure analysis waits for this artifact.

**Lesson**: Every test execution that produces JaCoCo data must have a corresponding upload step. Simply running tests is not enough — the data must be captured and surfaced to the analysis job.

---

### `analysis.needs: [build, test, test-media]` (expanded from `[test-media]`)

**Problem**: The original `analysis` job only listed `test-media` in its `needs`. This meant:
1. When `test-media` was skipped, analysis ran immediately — potentially before `build` finished uploading `coverage-flowtree`
2. Even when `test-media` was not skipped, `test` could still be running while analysis started

**Fix**: Add `build` and `test` to `analysis.needs`. GitHub Actions treats needs as: "wait for all listed jobs to complete or be skipped." This ensures analysis always waits for all coverage producers.

**Lesson**: Every job that produces a `coverage-*` artifact must appear in `analysis.needs`. This is not optional — it is a correctness requirement.

---

### Flowtree tests moved into `build` job

**Problem**: Flowtree tests were originally in a layer-gated job (or simply not running in CI). On flowtree-only branches, no layer flag was set, so all layer-gated test jobs were skipped, and flowtree tests were never executed.

**Fix**: Move `mvn test -pl flowtree` into the `build` job, which runs on every `code_changed=true`. Flowtree-only branches set `code_changed=true`, so `build` always runs and flowtree tests are always executed.

**Lesson**: Standalone modules (those without a layer flag) must have their tests in a job that runs on `code_changed`, not in a layer-gated job.

---

## File Locations Reference

| File | Purpose |
|------|---------|
| `.github/workflows/analysis.yaml` | The CI pipeline definition — the single source of truth for what runs |
| `.github/CLAUDE.md` | Authoritative CI reference injected by hook when `analysis.yaml` is accessed |
| `.github/CI_ARCHITECTURE.md` | This file — comprehensive CI architecture guide |
| `.claude/settings.json` | Claude Code settings including hook configuration |
| `.claude/hooks/guard-ci-file.sh` | Hook: inject CI context when reading `analysis.yaml` |
| `.claude/hooks/guard-pom-read.sh` | Hook: inject dependency check guidance when reading `pom.xml` |
| `.claude/hooks/validate-ci-edit.sh` | Hook: validate `analysis.needs` consistency before editing `analysis.yaml` |
| `.claude/hooks/stop-guard.sh` | Hook: block session termination before 6pm PT |
| `docs/internals/module-dependency-architecture.md` | Full module dependency reference |
| `docs/internals/standalone-modules.md` | Standalone module reference (flowtree, tools, graphpersist) |
| `docs/internals/ci-investigation-protocol.md` | Step-by-step guide for CI failure investigation |
| `docs/QUICK_REFERENCE.md` | Condensed API cheatsheet |
| `llms.txt` | Documentation index for AI assistants |

### Hook behavior summary

| Hook | Trigger | Effect |
|------|---------|--------|
| `guard-ci-file.sh` | Read of `analysis.yaml` | Injects `.github/CLAUDE.md` contents into context |
| `guard-pom-read.sh` | Read of `pom.xml` | Injects reminder to verify both dependency directions |
| `validate-ci-edit.sh` | Edit/Write targeting `analysis.yaml` | Validates that `analysis.needs` covers all `coverage-*` producers; checks flag sync |
| `stop-guard.sh` | Session stop attempt | Blocks stopping before 6pm PT if work is in progress |

Hooks are enforced automatically by Claude Code. They cannot be bypassed by the agent. If a hook fires and reports an issue, that issue must be resolved before proceeding.

---

## Appendix: Quick Reference for Common Tasks

### "Did I break anything by changing module X?"

1. Find which layer X is in
2. Find all layers that depend on X (upward transitive closure in the dependency graph)
3. The test jobs covering those layers will run in CI — verify they pass

### "Why is the analysis job failing?"

Most common causes (in order of frequency):
1. Missing `mkdir -p all-coverage` — add it before the `find` command
2. A new test job produces coverage but is not in `analysis.needs` — add it
3. Qodana configuration issue — check Qodana-specific logs

### "Why are no tests running on my branch?"

1. Check whether your branch changed any files (some branches are documentation-only)
2. If code files changed but tests didn't run, check whether `code_changed` was set
3. If `code_changed` was set but layer jobs didn't run, verify your changed directories correspond to a layer that gates those jobs (see [Layer-Gating Reference Table](#layer-gating-reference-table))

### "How do I know if two flags are in sync?"

```bash
grep -A50 'set_all_flags_true' .github/workflows/analysis.yaml | grep '_changed=true' | sort > /tmp/all_flags.txt
grep '_changed=true' .github/workflows/analysis.yaml | grep -v 'set_all_flags_true' | grep -v '^#' | sort > /tmp/detection_flags.txt
diff /tmp/all_flags.txt /tmp/detection_flags.txt
```

Empty diff output means they are in sync.

### "What test jobs run on a pure documentation change?"

None. Documentation files (`.md`, files under `docs/`) do not set `code_changed=true`. The `changes` job detects only code file changes.

### "What happens on a merge to master?"

The `set_all_flags_true` shortcut runs, setting all flags to true. Every test job runs. This ensures master always has full test coverage regardless of what files were changed in the PR.
