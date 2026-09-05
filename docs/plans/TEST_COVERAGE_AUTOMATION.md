# Test Coverage Automation: A Recurring Coverage-Improvement Pipeline

## Category

Developer Tooling / CI / Automated Quality

## Status

**Implemented.** Every workflow, script, prompt and data file this document
describes has been built, following the plan's own recommendations on each of
its open questions:

- `coverage-qa` lives in `.github/workflows/master-agent-dispatch.yaml`
  alongside `doc-qa`/`defect-hunt` (open question 1), with its own
  non-cancelling `coverage-qa` concurrency group and `coverage` added to the
  `agent` dispatch choices.
- `tools/ci/coverage/fetch-latest-coverage.sh`, `tools/ci/coverage/select-target.py`
  (plus its fixture-driven unit tests in the same directory),
  `tools/coverage-data/coverage-exclusions.txt`, `tools/coverage-data/coverage-history.tsv`, and
  `tools/ci/prompts/coverage.txt` + `build-coverage-prompt.sh` all exist and are
  exercised: `select-target.py`'s test suite passes (ranking, size floor,
  exclusion matching, cooldown, give-up, the Python per-directory rollup and
  tie-breaking, all fixture-XML driven), the new/modified shell scripts pass
  `shellcheck -S warning`, both edited workflow YAML files parse, and
  `select-target.py` was dry-run both against the checked-in fixtures and
  against a real coverage.py report generated from `tools/mcp/common`'s own
  test suite.
- A single global `COVERAGE_THRESHOLD` (default `80`) is exposed as a
  workflow-level env var; `COVERAGE_THRESHOLD_PYTHON` is read by
  `select-target.py` with a fallback to the global value, and is left unset in
  the workflow by design (open question 4) until a Python-specific bar is
  warranted.
- Python measurement omits `test_*.py` from the coverage.py run
  (`--omit='*/test_*.py'` in `fetch-latest-coverage.sh`), and
  `select-target.py` additionally skips any stray `test_*.py` `<class>` entry
  defensively when rolling files up to a directory.
- Coverage rounds are submitted with `PROTECT_TEST_FILES=true` and no
  sensitive-file bypass — full protection stays on. The one integration snag
  the plan flagged (Risks, "Coverage gaming" §2) is real and was fixed rather
  than worked around: see "Deviations" below.
- The assertion-density report (Risks section) runs as a non-blocking,
  report-only `assertion-density-report` job in `analysis.yaml`, scoped to
  `qa/coverage-*` branches, deliberately absent from `all-checks`' `needs` so
  it can never gate a merge.

### Deviations from the proposal, and why

1. **The `validate-agent-commit.sh` integration snag was resolved by moving
   the mutable data files out of the protected path, not by carving an
   exception into protection tooling.** The plan's Risks section proposed
   either (a) a commit trailer marking a round as "tests-only expected," or
   (b) a validator mode permitting new-test-only diffs. An earlier revision
   of this work tried a third option instead — an exact-path
   `PIPELINE_DATA_FILES` allowlist added directly to
   `validate-agent-commit.sh`, exempting `tools/ci/coverage-history.tsv` and
   `tools/ci/coverage-exclusions.txt` from RULE 3's CI-file lock — and that
   revision is what originally shipped. It was wrong: the enforcement
   detectors under `tools/ci/agent-protection/` exist so that no agent job
   can ever widen its own operating latitude by editing the thing that polices
   it, and an allowlist entry is exactly that shape of edit, regardless of how
   narrowly it is scoped or how convincing its inline justification reads.
   CI correctly flagged the change as protection-tooling tampering
   (`test-integrity-check`'s "enforcement infrastructure tampering" gate lists
   `validate-agent-commit.sh` as protected on every non-`ci/...` branch).
   The fix is not to add a carve-out to that gate either — it is to remove
   the reason the coverage pipeline ever needed one. `coverage-history.tsv`
   and `coverage-exclusions.txt` now live under `tools/coverage-data/`,
   entirely outside `tools/ci/` and `.github/workflows/`, so
   `validate-agent-commit.sh` classifies both as ordinary (non-CI) files with
   **no code change to the validator at all** — RULE 3 simply does not apply
   to a path it was never written to lock. `tools/ci/agent-protection/`
   matches `origin/master` byte for byte again. The lesson generalizes: when a
   pipeline's own data needs to travel through the same commit lock its logic
   does, move the data, not the lock.
2. **The extern/ "asset-gated" exclusion category was not implemented as a
   package exclusion.** `extern/ml-onnx` and `extern/ml-djl` both compile
   into the Java package `org.almostrealism.ml` — the same package name
   `engine/ml` uses. Because the merged JaCoCo report aggregates coverage by
   `<package name>` across every module, excluding `org.almostrealism.ml`
   would also exclude `engine/ml`'s well-tested code. The two asset-gated
   test methods the plan names (`OnnxAutoEncoderTests.encode`,
   `OnnxPrototypeDiscoveryTest.discoverWithOnnxFeatures`) are already excluded
   at the *method* level via `@TestProperties(excludeProfiles =
   TestUtils.PIPELINE)`, which is unaffected by this; `tools/coverage-data/coverage-exclusions.txt`
   documents the reasoning in place of a pattern entry.
3. **`fetch-latest-coverage.sh` implements two tiers, not three.** Java
   coverage is either reused (download the `analysis` job's own
   `merged-coverage-report` artifact — the exact already-finished answer) or
   recomputed fully fresh with `FORCE=true`. The plan's sketch of merging raw
   per-job `coverage-*` artifacts against the current checkout's compiled
   classes was dropped as a middle tier: it would risk a class/exec mismatch
   if master had moved since the artifacts' run, for no benefit over the
   already-merged report the same run also produces.
4. **The give-up marker's exclusion-list write rides the round's own PR when
   one opens; a direct push to `master` is only a best-effort fallback for
   the round that doesn't.** The first implementation committed the write
   straight to `master` unconditionally, before the coverage branch was even
   created — a real deviation, reviewed and found wanting: an unreviewed
   direct push to `master` can fail under branch protection or race with a
   concurrent merge, and it denied the exclusion a human review pass the
   rest of the round gets. The revised design:
   - When target selection succeeds (the common case), the append is
     committed onto the round's own `qa/coverage-*` branch — right after
     "Create coverage branch," before the agent is dispatched — so it lands
     in the same PR as the agent's tests and gets the same human review.
   - Only when nothing is eligible this round (no branch is created for the
     append to ride on) does the workflow attempt a direct push to `master`,
     and that attempt is strictly best-effort: `git push origin master`'s
     result is checked explicitly (`if ... ; then ... else ::warning::
     ... fi`), so a failure logs a warning and moves on rather than failing
     the job. It cannot change what was already selected (or not selected)
     this run, because it runs after target selection.
   - Neither path is a correctness requirement. `select-target.py`'s
     `is_given_up()` re-derives give-up state from
     `tools/coverage-data/coverage-history.tsv` (the ledger) at runtime on
     every future run, independent of whether
     `tools/coverage-data/coverage-exclusions.txt` ever received the
     auto-appended entry. So an abandoned `qa/coverage-*` branch, or a failed
     best-effort master push, never lets the selector re-pick a unit that has
     genuinely given up — the exclusion-file write is a human-facing
     convenience (a reviewer skimming the file sees the reason recorded), not
     the mechanism that prevents thrashing. Both properties are covered by
     `GiveUpRuntimeDerivationTests` in `test_select_target.py`: give-up state
     computed from the ledger alone with an empty exclusion list, and
     idempotent re-derivation across two `select()` calls when the
     exclusion-file write is never persisted between them.
   - Because the append can now land on an ordinary `qa/coverage-*` branch
     (not a `ci/...` branch) before the agent's own commits, it must clear
     `validate-agent-commit.sh`'s RULE 3 the same way the ledger append does.
     It clears it for free: both `tools/coverage-data/coverage-exclusions.txt`
     and `tools/coverage-data/coverage-history.tsv` live outside `tools/ci/`,
     so the validator classifies them as ordinary files with no RULE-3
     exposure and no allowlist entry required (see Deviation 1 above).
5. **Superseded by the relocation in Deviation 1 — empirically re-verified.**
   This item originally read: a round that only appends the honesty-clause
   ledger row (no test changes at all) still trips RULE 2's
   no-production-changes check, a warning in CI, not a hard failure, but
   still a quality-gate finding the auto-resolve pipeline would otherwise try
   to "fix." That was true while the ledger lived under `tools/ci/`, where it
   was excluded from `PRODUCTION_FILES` and therefore never counted toward
   RULE 2's substantive-change total. It is no longer true now that the
   ledger and exclusions files live under `tools/coverage-data/`:
   `validate-agent-commit.sh`'s classifier has no notion of "pipeline data"
   at all — a file outside `tools/ci/` and `.github/workflows/`, not a test
   file, and not `pom.xml`/`CLAUDE.md`/`.gitignore`/`.editorconfig`, is
   ordinary `PRODUCTION_FILES`, full stop. Verified empirically against
   master's unmodified `validate-agent-commit.sh --require-production-changes`:
   a commit touching only `tools/coverage-data/coverage-history.tsv` (no test
   changes at all) now exits 0 ("Agent commit validation PASSED," 1
   production file changed) instead of exit 3. This is accepted as-is —
   the file relocation was a deliberate policy choice to keep protection
   tooling untouched, and RULE 2 was already a warning-only, not a hard
   gate, for this exact scenario; a genuinely rare ledger-only "nothing to
   add" round simply stops generating that quality-gate notice rather than
   generating one that was already non-blocking. Re-introducing the
   distinction (a "this file is data, not evidence of real work" check)
   would mean adding pipeline-specific knowledge back into
   `validate-agent-commit.sh`, which is precisely the coupling this
   relocation was done to avoid.

## Motivation

The repository already runs two recurring, autonomous quality jobs that fire on
every merge to `master`:

- **Documentation QA** (`doc-qa`) — reviews documentation for drift against the
  last week of merges and opens a PR with fixes.
- **Defect Hunt** (`defect-hunt`) — hunts for a real defect anywhere in the
  repository, proves it with a failing test, and fixes it if it can.

Both are dispatched from `.github/workflows/master-agent-dispatch.yaml`, both
submit a FlowTree coding-agent job through the same three helper scripts, and
both share a single cadence/dedup mechanism (`tools/ci/qa-cadence.sh`).

Test coverage is the obvious third member of this family. The project has a
large body of code with **no tests at all** in several modules (see the measured
numbers below), JaCoCo is already wired into every module's build, and CI already
merges per-module coverage into a single repository-wide report. What is missing
is a job that *acts* on that report: picks the worst-covered area that can
practically be improved, and dispatches a coding agent to write meaningful tests
for it. This plan proposes that job as a sibling of `doc-qa` and `defect-hunt`,
reusing their infrastructure wherever possible.

---

## The pattern we are mirroring

Before the proposal, a precise description of the existing mechanism, because the
whole point is to feel like a sibling of it.

### Dispatch (`.github/workflows/master-agent-dispatch.yaml`)

The two QA jobs (`doc-qa`, `defect-hunt`) plus the Project Manager
(`plan-next-task`) live in **one** workflow file. Triggers:

- `push` to `master` — runs all three jobs.
- `workflow_dispatch` — an `agent` input (`all` | `project-manager` |
  `quality-assurance` | `defect-hunt`) selects one; a `force` input bypasses the
  cadence gate.

Each job carries its **own** `concurrency` group (`quality-assurance`,
`defect-hunt`), none cancelling in progress, so the jobs serialize independently
rather than queueing behind one another. There is deliberately **no
workflow-level concurrency** — adding one would couple them.

### Per-job step sequence

Both QA jobs run the identical shape (`doc-qa` shown; `defect-hunt` is the same
minus the review window):

1. **Checkout** `master`, full history.
2. **Decide whether to submit** — `tools/ci/qa-cadence.sh`, gated on
   `BRANCH_PREFIX` (`qa/docs-` / `qa/defect-`) and `MIN_INTERVAL_DAYS` (7).
3. **Archive previous rounds** — `tools/ci/archive-stale-workstreams.sh` (only
   when the gate says run; safe because the gate has already proven no prior PR
   is open).
4. **Create branch** `"<prefix><UTC-timestamp>"` and push it (so the agent's
   target branch exists; `AUTO_CREATE_PR` opens the PR when the agent commits).
5. **Register workstream** — `tools/ci/register-workstream.sh`.
6. **Build prompt** — `tools/ci/prompts/build-<x>-prompt.sh` reads a template
   (`tools/ci/prompts/<x>.txt`) and `sed`-substitutes `${BRANCH}` /
   `${BASE_BRANCH}` (and `${REVIEW_SINCE}` for docs).
7. **Submit** — `tools/ci/submit-agent-job.sh` POSTs to
   `<controller>/api/submit`.

### Cadence / dedup (`tools/ci/qa-cadence.sh`)

The gate answers "should another round start now?" with two conditions, in order:

1. **A PR from a previous round is still open** (branch name starts with the
   prefix) → skip (`reason=pr-open`). Failure to reach the GitHub API is treated
   as "assume open, skip" — it never guesses "none," because guessing none is
   the direction that produced dozens of abandoned branches.
2. **The most recent round is younger than `MIN_INTERVAL_DAYS`** → skip
   (`reason=too-recent`). Cadence is derived from the **branch names themselves**
   (`git ls-remote --heads`, timestamp `YYYYMMDD-HHMMSS` parsed from the name),
   not from the GitHub Actions cache, which is not durable enough.

`force=true` bypasses both.

### Submission knobs (`tools/ci/submit-agent-job.sh`)

Relevant environment variables the submit script already understands, all of
which the coverage job can reuse without change:

| Var | Meaning |
|-----|---------|
| `DESCRIPTION` | short label for notifications |
| `PROTECT_TEST_FILES` | block agent edits to base-branch test files |
| `AUTO_CREATE_PR` | open a PR automatically on success |
| `ENFORCE_CHANGES` | require code changes or retry |
| `MAX_TURNS`, `MAX_BUDGET_USD` | per-job turn / dollar caps |
| `STARTED_AFTER` | epoch millis; controller skips if a newer job exists |
| `DELAY_SECONDS` | delay execution |
| `DEFAULT_PHASE_CONFIG`, `PHASE_CONFIGS` | per-phase model/runner pinning |

### Coverage infrastructure that already exists

- **JaCoCo** is configured once in the root `pom.xml`
  (`jacoco-maven-plugin`, version pinned by the `jacoco.version` property), with
  `prepare-agent` and a `report` execution bound to the `test` phase. Every
  module therefore emits `target/jacoco.exec` and a per-module XML report under
  `.qodana/code-coverage/` whenever its tests run.
- **`analysis.yaml`** already collects every `*/target/jacoco.exec` from each
  test job into a `coverage-*` artifact, and the `analysis` job downloads all
  `coverage-*` artifacts, merges them with the JaCoCo CLI
  (`jacococli merge` + `jacococli report`), and produces a single
  repository-wide `coverage.xml` (and HTML) over all `target/classes` and
  `src/main/java`. **An aggregated, cross-module report already exists** — the
  coverage job does not need to invent aggregation, only to *read* the merged
  report.

---

## 1. Coverage measurement

### Java — JaCoCo

**Recommendation: use JaCoCo _line_ coverage as the primary Java metric,
aggregated per Java package, computed from the merged repository report that
`analysis.yaml` already produces.**

Rationale:

- **Line vs branch.** Branch coverage is the stricter, more informative metric —
  it is the one that actually distinguishes "the test executed this `if`" from
  "the test executed both sides of this `if`." But as the *target-selection*
  metric it is noisier and worse at ranking: packages that are mostly data
  classes, DTOs, and linear glue have few branches, so a single uncovered
  `switch` swings their branch percentage wildly, and packages with zero branches
  report `n/a`, which has to be special-cased. Line coverage is stable, always
  defined, monotonic with "amount of untested code," and is what a human means by
  "this package is untested." **Recommendation: rank and threshold on line
  coverage; report branch coverage alongside it as a secondary figure** so a
  reviewer can see when a high-line, low-branch package is hiding untested logic.
  (This mirrors how the agent is later told to write *meaningful* tests, not just
  line-touching ones — see Risks.)
- **Per-package vs per-module aggregation.** The Maven module is too coarse:
  `engine/ml` or `flowtree/runtime` each contain many packages at wildly
  different coverage levels, and "raise `engine/ml` to 80%" is not a task an
  agent can hold in one session. The Java **package** (e.g.
  `org.almostrealism.collect.computations`) is the right unit: it is small
  enough to be a single agent's scope, it is the natural grouping in the JaCoCo
  XML (`<package name=...>` elements), and it maps cleanly to a directory of
  source files. **Recommendation: select and threshold per package**, and record
  which module the package belongs to only for reporting and routing.
- **Aggregated report across modules — needed?** Yes, and it already exists.
  Per-module reports would miss cross-module packages and would force the
  selector to open 30+ XML files. The single merged `coverage.xml` from the
  `analysis` job is the one input the selector reads.

**Measurement command (what the pipeline relies on, already in CI):** the
`analysis` job's merge step. For a standalone coverage computation (e.g. a
scheduled run that recomputes fresh numbers rather than reusing a PR run's
artifacts), the equivalent is:

```bash
# Produce per-module jacoco.exec by running the test suites, then merge.
# In practice the pipeline reuses the coverage-* artifacts from the most recent
# master "Build and Test" run rather than re-running the (hours-long) suite.
mvn -o test -pl <modules> -Dsurefire.failIfNoSpecifiedTests=false   # writes target/jacoco.exec
java -jar jacococli.jar merge $(find . -path '*/target/jacoco.exec') \
  --destfile merged-jacoco.exec
java -jar jacococli.jar report merged-jacoco.exec \
  $(find . -path '*/target/classes' -type d -exec echo --classfiles {} \;) \
  $(find . -path '*/src/main/java' -type d -exec echo --sourcefiles {} \;) \
  --xml coverage.xml
```

The JaCoCo XML gives, per package, `<counter type="LINE" missed=".." covered="..">`
and `<counter type="BRANCH" ...>`. Line % = `covered / (covered + missed)`.

### Python — coverage.py

**Recommendation: use coverage.py _line_ coverage as the primary Python metric,
aggregated per top-level tool directory (the unit the `python-tests` job already
uses).**

Rationale:

- The Python code (`tools/`) does **not** follow the Java package organization;
  it is a set of independent MCP servers and helper tools
  (`tools/mcp/manager`, `tools/mcp/common`, `tools/tracker`, `tools/ci`, ...),
  each with its own `test_*.py` files run via `python3 -m unittest discover`.
  There is no meaningful "package percentage" that spans them.
- The natural unit is therefore the **tool directory** — the same granularity
  the CI `python-tests` job discovers tests at
  (`tools/mcp/manager`, `tools/mcp/common`, `tools/tests`). coverage.py reports
  per-file; the selector rolls per-file numbers up to the directory.
- Line vs branch: same argument as Java, and even more so for Python where
  branch data is noisier. **Recommendation: line coverage primary, branch
  reported secondarily.**

**Measurement commands:**

```bash
pip install coverage
# Run each tool's unittest suite under coverage, appending into one data file:
coverage run  --source=tools/mcp/manager -m unittest discover -s tools/mcp/manager -p 'test_*.py'
coverage run -a --source=tools/mcp/common  -m unittest discover -s tools/mcp/common  -p 'test_*.py'
coverage run -a --source=tools/tests       -m unittest discover -s tools/tests       -p 'test_*.py'
coverage xml -o python-coverage.xml    # machine-readable, per-file line-rate
coverage report                        # human summary
```

`coverage xml` emits Cobertura-style XML with `<class filename=.. line-rate=..>`
per file, which the selector groups by directory.

### Representative current numbers

_Measured on this branch with the commands above; see the Appendix for method
and caveats. These are a representative sample of CPU-testable modules and the
Python suites — not the full-repository census the production pipeline reads._

**Python (coverage.py, line coverage, production code only — `test_*.py`
excluded):**

| Directory | Instrumented lines | Line coverage | Missed |
|-----------|-------------------:|--------------:|-------:|
| `tools/mcp/common`  | 1,140 | **48.8%** | 584 |
| `tools/mcp/manager` | 3,444 | 76.5% | 808 |

`tools/mcp/common` at ~49% is exactly the kind of worst-offender this pipeline
would pick first among Python directories. (`tools/tests` holds only test files,
so it correctly drops out of a production-code measurement.)

**Java (JaCoCo, per-package line coverage):**

`base/io` — a small, lightly-tested utility module:

| Package | Lines | Line coverage | Branch |
|---------|------:|--------------:|-------:|
| `org.almostrealism.lifecycle` | 51  | 0.0% | 0.0% |
| `org.almostrealism.io`        | 610 | 2.0% | 5.1% |
| **module total**              | 661 | **1.8%** | — |

`base/hardware` — 12,629 instrumented lines at **0.3% overall**, and the single
clearest argument for the exclusion list. Its worst packages by absolute untested
lines:

| Package | Lines | Line coverage | Why it is unimprovable in CI |
|---------|------:|--------------:|------------------------------|
| `org.almostrealism.generated`         | 6,034 | 0.0% | **generated code** |
| `org.almostrealism.hardware.mem`      | 1,130 | 0.0% | GPU/native memory model |
| `org.almostrealism.hardware.cl`       |   904 | 0.0% | OpenCL device required |
| `org.almostrealism.hardware.metal`    |   853 | 0.0% | Metal device required |
| `org.almostrealism.hardware.jni`      |   459 | 0.0% | native JNI bridge |
| `org.almostrealism.hardware`          | 1,712 | 2.4% | device-dependent |

Ranked naively by lowest percentage, `org.almostrealism.generated` (6,034 lines,
0%) would win every round forever — and no CPU-only test can move it. This is why
the exclusion list (generated + GPU/hardware) and the give-up marker are not
optional niceties but load-bearing parts of the design.

**Why one metric per language, restated:** two dashboards are two things to keep
honest. Line coverage, per-package (Java) and per-directory (Python), is the
single number the pipeline ranks on and thresholds against; branch coverage
travels with it as context, never as the gate.

---

## 2. Target selection

The selector reads the merged Java `coverage.xml` and the Python
`python-coverage.xml`, applies exclusions, ranks, and emits **one** target: a
Java package or a Python directory.

### Ranking

Rank eligible units by **lowest line coverage percentage**, but guard against the
degenerate "a 3-line package at 0%" win with a **size floor and a size weight**:

- **Size floor.** Ignore any unit below `MIN_LINES` instrumented lines (proposed
  default **50**). A package with 12 lines is not worth a whole agent session and
  its percentage is statistical noise.
- **Weighting.** Among units above the floor, rank by a score that favors "many
  untested lines," not merely "low percentage":

  ```
  score = missed_lines * (1 - coverage_fraction)
  ```

  This is `missed_lines²/total_lines`-shaped: it prefers a 2000-line package at
  30% (1400 missed, big win available) over a 60-line package at 5% (57 missed).
  The agent's effort buys the most real coverage where `score` is highest.
  **Recommendation: rank by `score` descending; expose `MIN_LINES` as a
  workflow input.**

### Exclusions

Some code cannot be meaningfully unit-tested in CI, and selecting it repeatedly
would burn budget for nothing. Maintain an **exclusion list** (a checked-in file,
e.g. `tools/coverage-data/coverage-exclusions.txt`, of package/dir glob patterns) covering:

- **Hardware / GPU-dependent code.** `base/hardware` and any package whose tests
  require a real Metal/OpenCL/CUDA device. The CI Linux lane has no GPU; these
  packages can only be exercised on the self-hosted GPU/CL lanes and cannot be
  driven to 80% by CPU-only unit tests. This is the single most important
  exclusion — `base/hardware` alone is ~6,000 source files.
- **Generated code.** Anything emitted by a generator rather than authored
  (kernel/expression code generation output, protobuf/ONNX-generated stubs).
- **Trivial code.** Packages that are only enums, constants, DTOs, or exception
  types — high effort, no behavior to assert. The `MIN_LINES` floor catches most
  of these; the exclusion list catches the large-but-trivial ones.
- **Test/benchmark scaffolding** and `module-info`/package-info.
- **`extern/` integration shims** whose behavior is entirely a third-party
  runtime (DJL, ONNX) that is unavailable or asset-gated in CI — mirroring the
  three `@TestProperties(excludeProfiles = PIPELINE)` cases already documented in
  `.github/CLAUDE.md`.

The exclusion list is data, not code, so a reviewer can add to it without
touching the workflow, and every exclusion is auditable in one place.

### Tie-breaking

When two units have equal `score` (rare with real numbers, common at 0%):

1. Prefer the unit with **more instrumented lines** (bigger absolute win).
2. Then the one **not selected more recently** (see cooldown below).
3. Then **lexical order of the fully-qualified name**, for determinism — a
   deterministic tie-break means the same repository state always picks the same
   target, which makes the job reproducible and its choice explainable.

### Avoiding repeated selection of an unimprovable target

This is the failure mode that would make the pipeline worthless: it keeps
picking the same package, the agent keeps failing to move it, and every round
wastes a full budget. Three defenses, layered:

1. **Open-PR gate (inherited).** While the previous round's coverage PR is open,
   no new round starts (`qa-cadence.sh`). So a stuck target cannot be re-selected
   until a human has dealt with the last attempt — the same human-in-the-loop
   backpressure the docs/defect jobs rely on.
2. **Cooldown ledger.** Keep an append-only record of recently targeted units and
   the coverage delta each round achieved — the natural store is the **branch
   name plus a small committed ledger file** (e.g.
   `tools/coverage-data/coverage-history.tsv`: `timestamp  unit  before  after`), written by
   the agent as part of its PR, read by the selector. A unit selected within the
   last `COOLDOWN_DAYS` (proposed **30**) is skipped in ranking.
3. **Give-up marker.** If a unit has been targeted `MAX_ATTEMPTS` (proposed
   **2**) times and coverage rose by less than `MIN_PROGRESS` (proposed **5**
   percentage points) each time, the selector adds it to the exclusion list
   automatically (as a distinct "auto-excluded, needs human" section) and moves
   to the next-ranked unit. This converts "silently unimprovable" into a visible,
   reviewable fact instead of an infinite loop — matching the project's rule
   against silent no-ops.

If, after exclusions and cooldown, **no** eligible unit is below the threshold,
the job exits cleanly with "nothing to do" (like a docs round that finds no
drift). That is a valid, valuable outcome and must not manufacture work.

---

## 3. Threshold

**Recommendation: default threshold 80% line coverage, exposed as a workflow
input `COVERAGE_THRESHOLD`, and applied per-package (Java) / per-directory
(Python).** The agent is told to raise the selected target *to the threshold*.

On whether 80% is realistic near-term (grounded in the measured numbers below):

- **80% is aspirational, not a near-term reality for the worst offenders.** The
  measured sample shows worst-offender units at 0–50% (`tools/mcp/common` 48.8%,
  `base/io` 1.8%, and — before exclusions — whole `base/hardware` packages at
  0%). A single agent round can realistically take one mid-sized package from,
  say, 30% to 70–80%; it cannot take a 6,000-line generated package to 80% at
  all. So 80% is the right *definition of done for an eligible target*, not a
  repository-wide state that will exist soon.
- **This is a feature, not a problem.** The pipeline's value is the *slope*: each
  round moves one eligible unit up toward 80%. The threshold defines "improved
  enough to stop working on this unit," and the exclusion/cooldown machinery
  keeps the job from wasting rounds on units 80% cannot apply to.
- **Python likely warrants a slightly lower initial bar.** `tools/mcp/common` at
  49% and `tools/mcp/manager` at 76% suggest Python could reach 80% for
  well-structured tools but will struggle where a tool is mostly I/O glue around
  an external service (subprocess, HTTP, MCP transport) that is awkward to
  exercise without mocks. A `COVERAGE_THRESHOLD_PYTHON` of 70% for the first few
  months, ramping to 80%, is a reasonable stance — hence the recommendation below
  to make a per-language override cheap.


**Per-language / per-module thresholds — warranted?** Recommendation: keep a
single global `COVERAGE_THRESHOLD` for the first iteration (simplicity, one knob
to reason about), but design the config so a **per-language override** is a small
step (`COVERAGE_THRESHOLD_PYTHON` falling back to `COVERAGE_THRESHOLD`). A
per-*module* threshold is **not** recommended: the selection unit is the package,
not the module, and a per-module table is a maintenance burden that duplicates
what the exclusion list already expresses (if a module genuinely cannot reach the
bar, its packages belong on the exclusion list, not on a lower private bar that
hides the gap).

---

## 4. Job flow

End to end, the coverage job is a fourth job in
`master-agent-dispatch.yaml` (or, if the reviewer prefers cadence independence, a
sibling workflow file that reuses the same helper scripts — see Open Questions).

### Trigger and cadence

- **Trigger:** `push` to `master` + `workflow_dispatch`, exactly like its
  siblings. Add `coverage` to the `agent` choice input.
- **Cadence:** reuse `tools/ci/qa-cadence.sh` with `BRANCH_PREFIX="qa/coverage-"`
  and `MIN_INTERVAL_DAYS` (proposed **7**, matching the others). No new cadence
  code.
- **Concurrency group:** its own, `coverage-qa`, non-cancelling.

### Step sequence (proposed YAML outline)

```yaml
  # ── Quality Assurance — Test Coverage ────────────────────────────────
  coverage-qa:
    if: >-
      github.event_name != 'workflow_dispatch' ||
      github.event.inputs.agent == 'all' ||
      github.event.inputs.agent == 'coverage'
    runs-on: [self-hosted, macos, ar-ci]
    permissions:
      contents: write
      pull-requests: write
    concurrency:
      group: coverage-qa
      cancel-in-progress: false
    env:
      BRANCH_PREFIX: "qa/coverage-"
      MIN_INTERVAL_DAYS: "7"
      COVERAGE_THRESHOLD: "80"
      MIN_LINES: "50"
      COOLDOWN_DAYS: "30"
    steps:
      - name: Checkout Code
        uses: actions/checkout@v4
        with: { ref: master, fetch-depth: 0, token: ${{ secrets.GITHUB_TOKEN }} }

      - name: Decide whether to submit
        id: decide
        env:
          BRANCH_PREFIX: ${{ env.BRANCH_PREFIX }}
          MIN_INTERVAL_DAYS: ${{ env.MIN_INTERVAL_DAYS }}
          FORCE: ${{ github.event.inputs.force }}
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: ./tools/ci/qa-cadence.sh

      # Obtain the merged coverage report. Preferred: download the coverage-*
      # artifacts from the most recent successful master "Build and Test" run and
      # merge them (seconds), rather than re-running the suite (hours).
      - name: Fetch latest coverage report
        if: steps.decide.outputs.run == 'true'
        id: coverage
        env: { GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }} }
        run: ./tools/ci/coverage/fetch-latest-coverage.sh    # -> coverage.xml, python-coverage.xml

      - name: Select target
        if: steps.decide.outputs.run == 'true'
        id: select
        env:
          COVERAGE_THRESHOLD: ${{ env.COVERAGE_THRESHOLD }}
          MIN_LINES: ${{ env.MIN_LINES }}
          COOLDOWN_DAYS: ${{ env.COOLDOWN_DAYS }}
        run: ./tools/ci/coverage/select-target.py   # -> target, language, current %, module

      # If nothing is below threshold after exclusions/cooldown, stop cleanly.
      - name: Archive previous rounds
        if: steps.decide.outputs.run == 'true' && steps.select.outputs.target != ''
        env: { BRANCH_PREFIX: ${{ env.BRANCH_PREFIX }}, REPO_URL: git@github.com:${{ github.repository }}.git }
        run: ./tools/ci/archive-stale-workstreams.sh

      - name: Create coverage branch
        if: steps.decide.outputs.run == 'true' && steps.select.outputs.target != ''
        id: branch
        run: |
          BRANCH_NAME="${BRANCH_PREFIX}$(date -u +%Y%m%d-%H%M%S)"
          git checkout -b "$BRANCH_NAME"; git push -u origin "$BRANCH_NAME"
          echo "branch=$BRANCH_NAME" >> "$GITHUB_OUTPUT"

      - name: Register workstream
        if: steps.decide.outputs.run == 'true' && steps.select.outputs.target != ''
        env: { BRANCH: ${{ steps.branch.outputs.branch }}, BASE_BRANCH: master, REPO_URL: git@github.com:${{ github.repository }}.git }
        run: ./tools/ci/register-workstream.sh

      - name: Build coverage prompt
        if: steps.decide.outputs.run == 'true' && steps.select.outputs.target != ''
        env:
          BRANCH: ${{ steps.branch.outputs.branch }}
          BASE_BRANCH: master
          TARGET: ${{ steps.select.outputs.target }}
          TARGET_LANGUAGE: ${{ steps.select.outputs.language }}
          CURRENT_COVERAGE: ${{ steps.select.outputs.current }}
          COVERAGE_THRESHOLD: ${{ env.COVERAGE_THRESHOLD }}
        run: ./tools/ci/prompts/build-coverage-prompt.sh "${{ runner.temp }}/agent-prompt.txt"

      - name: Submit coverage agent job
        if: steps.decide.outputs.run == 'true' && steps.select.outputs.target != ''
        env:
          BRANCH: ${{ steps.branch.outputs.branch }}
          BASE_BRANCH: master
          DESCRIPTION: "Raise test coverage of ${{ steps.select.outputs.target }}"
          PROTECT_TEST_FILES: "true"      # protects *base-branch* test files; agent still adds new ones
          AUTO_CREATE_PR: "true"
          ENFORCE_CHANGES: "true"
        run: ./tools/ci/submit-agent-job.sh "${{ runner.temp }}/agent-prompt.txt"

      - name: Summary
        if: always()
        run: |
          echo "## Quality Assurance — Test Coverage" >> "$GITHUB_STEP_SUMMARY"
          echo "**Decision:** \`${{ steps.decide.outputs.run }}\` (${{ steps.decide.outputs.reason }})" >> "$GITHUB_STEP_SUMMARY"
          echo "**Target:** \`${{ steps.select.outputs.target }}\` @ ${{ steps.select.outputs.current }}% → ${{ env.COVERAGE_THRESHOLD }}%" >> "$GITHUB_STEP_SUMMARY"
```

### New files this introduces (all outside CI-config enforcement — see Risks)

- `tools/ci/coverage/fetch-latest-coverage.sh` — download+merge the latest
  master `coverage-*` artifacts (via `gh`/GitHub API), or fall back to a fresh
  `mvn test` + `coverage run` when none is available.
- `tools/ci/coverage/select-target.py` — parse the two XML reports, apply
  exclusions/floor/cooldown, rank by `score`, emit the winner to `$GITHUB_OUTPUT`.
- `tools/coverage-data/coverage-exclusions.txt` — the exclusion glob list (data).
- `tools/coverage-data/coverage-history.tsv` — the cooldown/attempts ledger (data, appended
  by the agent's PR).
- `tools/ci/prompts/build-coverage-prompt.sh` + `tools/ci/prompts/coverage.txt`
  — the prompt template.

### The target-selection script (logic sketch)

```python
# select-target.py  (sketch — parses JaCoCo + Cobertura XML, ranks, emits one target)
import xml.etree.ElementTree as ET, fnmatch, time, os, sys

THRESHOLD = float(os.environ["COVERAGE_THRESHOLD"])
MIN_LINES = int(os.environ.get("MIN_LINES", "50"))
COOLDOWN  = int(os.environ.get("COOLDOWN_DAYS", "30")) * 86400
excludes  = [l.strip() for l in open("tools/coverage-data/coverage-exclusions.txt")
             if l.strip() and not l.startswith("#")]

def excluded(name):
    return any(fnmatch.fnmatch(name, pat) for pat in excludes)

def recent(name, hist):          # cooldown ledger: name -> last epoch
    return name in hist and (time.time() - hist[name]) < COOLDOWN

units = []   # (name, language, covered, missed, module)

# --- Java: JaCoCo report -> per <package> LINE counters ---
for pkg in ET.parse("coverage.xml").getroot().iter("package"):
    line = next((c for c in pkg.findall("counter") if c.get("type") == "LINE"), None)
    if line is None: continue
    covered, missed = int(line.get("covered")), int(line.get("missed"))
    units.append((pkg.get("name").replace("/", "."), "java", covered, missed, None))

# --- Python: coverage.py Cobertura -> roll files up to top-level tool dir ---
from collections import defaultdict
agg = defaultdict(lambda: [0, 0])
for cls in ET.parse("python-coverage.xml").getroot().iter("class"):
    fn = cls.get("filename")
    d  = "/".join(fn.split("/")[:3])          # e.g. tools/mcp/manager
    lines = cls.find("lines")
    for ln in (lines or []):
        agg[d][0 if ln.get("hits","0") != "0" else 1] += 1
for d, (covered, missed) in agg.items():
    units.append((d, "python", covered, missed, None))

hist = {}    # load tools/coverage-data/coverage-history.tsv -> {name: last_epoch}

def eligible(u):
    name, _lang, covered, missed, _m = u
    total = covered + missed
    frac  = covered / total if total else 1.0
    return (total >= MIN_LINES and frac * 100 < THRESHOLD
            and not excluded(name) and not recent(name, hist))

def score(u):
    _n, _l, covered, missed, _m = u
    total = covered + missed
    frac  = covered / total if total else 1.0
    return (missed * (1 - frac), missed, )      # primary, tie-break: absolute missed

cands = sorted(filter(eligible, units), key=score, reverse=True)
if not cands:
    print("target=", file=open(os.environ["GITHUB_OUTPUT"], "a")); sys.exit(0)

name, lang, covered, missed, _m = cands[0]
pct = round(100 * covered / (covered + missed), 1)
with open(os.environ["GITHUB_OUTPUT"], "a") as out:
    out.write(f"target={name}\nlanguage={lang}\ncurrent={pct}\n")
```

### The agent prompt (`tools/ci/prompts/coverage.txt`, sketch)

The template mirrors the tone of `defect-hunt.txt`: it tells the agent *what*
and *why*, and — critically for this job — it spends most of its words on what
makes a test *meaningful*, because the whole value of the job depends on not
gaming coverage. Substituted variables: `${BRANCH}`, `${BASE_BRANCH}`,
`${TARGET}`, `${TARGET_LANGUAGE}`, `${CURRENT_COVERAGE}`, `${COVERAGE_THRESHOLD}`.

Prompt outline:

- **Goal:** raise line coverage of `${TARGET}` from `${CURRENT_COVERAGE}%` to at
  least `${COVERAGE_THRESHOLD}%` by writing meaningful tests.
- **What "meaningful" means (the anti-gaming core):**
  - Every test asserts on observable behavior — return values, state changes,
    thrown exceptions, emitted events — never merely calls a method for the
    line hit.
  - Prefer testing the public contract of a class over reaching private methods.
  - Test edge cases the happy path misses: empty input, boundaries, second call,
    error/cleanup paths (echoing the defect-hunt guidance).
  - For this framework specifically: honor the Producer/`evaluate()` rules from
    `CLAUDE.md`. Tests are a sanctioned place for `.evaluate()`; production code
    is not. Do not smuggle Java-side math into a test to fake a result.
  - No assertion-free tests, no tautologies (`assertEquals(x, x)`), no
    `assertNotNull` on something that cannot be null, no tests disabled or
    `@TestDepth`-escalated out of CI.
- **Process:** run the module's existing tests first; read the class under test;
  write tests that pin real behavior; run them with the MCP test runner; confirm
  they pass; re-measure coverage locally for the target; iterate until at or
  above threshold or until further honest gains are impractical.
- **Honesty clause (mirrors defect-hunt):** if the target cannot reach threshold
  with meaningful tests (asset-gated, hardware-gated, genuinely trivial), say so
  plainly, record it in `tools/coverage-data/coverage-history.tsv`, and recommend an
  exclusion rather than padding with hollow tests.
- **Guardrails:** `TestSuiteBase` extension, `@Test(timeout=...)`, no
  `@SuppressWarnings`/`var`, Javadoc, and the standard build-validator pass.
- **Deliverable:** new test files + the appended history row; PR opened
  automatically.

---

## 5. Risks and safeguards

### Coverage gaming (the primary risk)

Rewarding a number invites gaming it: assertion-free tests, tautological
assertions, tests that call everything and assert nothing, dimension-shrinking to
make behavior trivial. The project already documents these as known agent
deception patterns and enforces against several mechanically. Layered defenses:

1. **Prompt.** The bulk of the coverage prompt is spent defining "meaningful"
   (above). This is necessary but not sufficient — a prompt cannot be the only
   guard.
2. **Existing mechanical enforcement (reused, not modified).**
   `tools/ci/agent-protection/detect-test-hiding.sh` already flags TestDepth
   escalation, timeout inflation, dimension reduction, tolerance weakening, and
   net assertion loss; `validate-agent-commit.sh` blocks edits to base-branch
   test/CI files and commits with no production code changes. Here is the catch:
   the coverage job's product *is* test files, so `validate-agent-commit.sh`'s
   "must change
   production code" rule would reject a legitimate coverage PR. **This is the one
   real integration snag and it must be resolved by the reviewer, not worked
   around by the agent:** either (a) mark coverage-round commits with a trailer
   that the validator recognizes as "tests-only is expected here," or (b) run
   coverage rounds under a validator mode that permits new-test-only diffs while
   still blocking modification of *existing* base-branch tests. Do **not** weaken
   the validator globally.
3. **Assertion-density check.** Add a *review-phase* check (not a merge gate at
   first) that reports the ratio of assertions to new test methods and flags
   suspiciously assertion-light additions for the human reviewer. Cheap, and it
   turns gaming into a visible signal.
4. **Human PR review (inherited).** Every round produces a PR a human must merge.
   The open-PR cadence gate means rounds cannot outrun that review.

### Test-suite runtime growth

Every round adds tests, and the full suite already takes hours. Safeguards:

- **Target the fast lane.** Selection already excludes GPU/hardware packages, so
  new tests land in CPU-testable code and run on the fast `test`/`test-flowtree`
  lanes, not the GPU lanes.
- **Timeout discipline (inherited).** `test_timeouts` in the build validator
  already requires every `@Test` to carry a timeout, capping any single new
  test's contribution.
- **Watch the trend.** The `analysis` job already has per-run timing; a follow-up
  can alert if a coverage round increases a lane's wall-clock beyond a budget.
  Out of scope for v1 but worth stating.

### Flaky tests

A test that passes locally and flakes in CI is worse than no test — it erodes
trust in the whole gate.

- Prompt instructs: no reliance on wall-clock timing, ordering, network, or
  device state; deterministic seeds; no fixed ports (echoing the
  `EventDeliveryTest` port-8080 lesson in `.github/CLAUDE.md`).
- New tests inherit `@TestProperties(excludeProfiles = PIPELINE)` as the
  sanctioned escape hatch **only** for genuinely un-CI-able cases, never as a way
  to hide a flaky test — and using it counts against the coverage gain, removing
  the incentive to reach for it.
- Because rounds are gated behind human PR review, a flaky addition is caught
  before it compounds.

### Cost / budget controls on spawned jobs

- Reuse `MAX_TURNS` and `MAX_BUDGET_USD` on `submit-agent-job.sh` to cap each
  round's spend; set conservative defaults in the workflow env (a coverage round
  is more mechanical than a defect hunt, so it need not be pinned to `opus` —
  recommendation: leave phase configs at workspace default, unlike `defect-hunt`
  which pins primary to opus).
- One round at a time (concurrency group + open-PR gate) bounds concurrent spend.
- The `MIN_LINES` floor and give-up marker stop the pipeline from paying to
  chase percentages on trivial or unimprovable units.

### Collisions — with the other pipelines and with itself

- **With docs/defect:** independent branch prefix (`qa/coverage-`) and its own
  concurrency group means `qa-cadence.sh` and `archive-stale-workstreams.sh`
  operate on a disjoint set of branches/workstreams. The three jobs never touch
  each other's rounds. (Confirmed: the cadence and archive scripts are keyed
  entirely on `BRANCH_PREFIX`.)
- **With itself:** the open-PR gate + `MIN_INTERVAL_DAYS` prevent overlapping
  rounds; `archive-stale-workstreams.sh` (with the new round as `KEEP_BRANCH`)
  retires abandoned prior rounds; and `STARTED_AFTER` is available on the submit
  script if the controller-side newer-job dedup is also wanted.
- **Selecting the same target twice:** the cooldown ledger + give-up marker
  (Section 2) prevent thrashing a single unit.

---

## Open questions for the reviewer

1. **Same file or sibling workflow?** Folding `coverage-qa` into
   `master-agent-dispatch.yaml` matches "one workflow per lifecycle event" and
   reuses the `agent` dispatch input. A separate file would let coverage run on a
   different cadence (e.g. monthly) without a new input. **Recommendation:** same
   file, `MIN_INTERVAL_DAYS` tuned independently if a slower cadence is wanted.
2. **`validate-agent-commit.sh` tests-only exemption.** The one hard integration
   point (Risks §Gaming.2). Needs an explicit, enforcement-preserving decision
   before implementation — this is why it is called out rather than silently
   coded around.
3. **Coverage source: reuse artifacts vs recompute.** Reusing the latest master
   `coverage-*` artifacts is fast and matches what CI already trusts, but is only
   as fresh as the last master run. Recomputing is authoritative but slow.
   **Recommendation:** reuse, with a `force`-style input to recompute.
4. **Threshold realism** — see Section 3 commentary once the measured numbers are
   reviewed; 80% may warrant a lower Python-specific bar or a phased ramp.

---

## Appendix: how the representative numbers were measured

Recorded so the reviewer can reproduce them. The full Java suite takes hours and
requires GPU/CL hardware for many modules, so the numbers below were produced
from CPU-only modules that build and test without a device, plus the Python
suites, using exactly the commands this plan proposes. They are a **starting-line
sample**, not a full-repository census — the production pipeline reads the
complete merged report from a real master CI run.

**Java.** JaCoCo is already active in every module's build. The per-module
reports were produced by a normal reactor build (`mvn install -pl <modules>
-am`), which writes `<module>/target/jacoco.exec` and a per-module XML report at
`<module>/.qodana/code-coverage/jacoco.xml`. The tables above were parsed
directly from those XML files (`base/io`, `base/hardware`), summing the
`<counter type="LINE">` / `type="BRANCH"` elements under each `<package>`. The
production pipeline does the same parse against the single *merged* report that
`analysis.yaml` already builds from all `coverage-*` artifacts, so it sees every
package in one pass rather than one module at a time.

**Python.** coverage.py wrapping the same `unittest discover` invocations the
`python-tests` CI job uses:

```bash
coverage run    --source=tools/mcp/manager -m unittest discover -s tools/mcp/manager -p 'test_*.py'
coverage run -a --source=tools/mcp/common  -m unittest discover -s tools/mcp/common  -p 'test_*.py'
coverage run -a --source=tools/tests       -m unittest discover -s tools/tests       -p 'test_*.py'
coverage xml -o python-coverage.xml
```

**One important caveat the pipeline must handle:** `coverage.py --source=<dir>`
instruments the *test* files too, which inflates a directory's number. The raw
run reported `tools/mcp/manager` at ~91% and `tools/mcp/common` at ~65%; after
excluding `test_*.py` from the measurement the production-only figures drop to
76.5% and 48.8% respectively. The pipeline must configure coverage.py to omit
test files (e.g. an `omit = */test_*.py` in `.coveragerc`) so it targets
production code, not the tests measuring it. The tables above are the
production-only figures.

