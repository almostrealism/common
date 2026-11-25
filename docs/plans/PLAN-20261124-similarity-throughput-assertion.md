---
status: completed
created: 2026-11-24
closed: 2026-11-24
tracker: [ac6793a5-dfdf-499a-8ca9-292ed2e602f9]
---

# Give pairwise similarity-at-scale a measured throughput floor

**Tracker task:** ac6793a5-dfdf-499a-8ca9-292ed2e602f9 — *Give pairwiseSimilarityAtScale
a throughput assertion instead of relying on its timeout*

## Motivation

`SimilarityOverheadTest` (in `engine/utils`) exercises the per-call
`productSimilarity` test helper — the expression-tree-per-comparison pattern that
`WaveDetailsFactory`'s similarity path (`similarity()` → `cachedProductSimilarity()`)
used before it was optimized — to measure the Java overhead of
building `CollectionProducer` graphs versus native kernel execution.

Its `pairwiseSimilarityAtScale` method runs 1000 tensors → ~499,500 comparisons
under a deliberately tight `@Test(timeout = 600_000)`. The only per-call figure
the method's javadoc records is ~0.8–1.5 ms/comparison measured **on a CPU-only
host**, where waking native threads from deep CPU sleep on an otherwise-idle
host roughly doubles the cost; at that order of magnitude the ~499,500-comparison
workload already fills most of the 600 s budget. The test is GPU-gated (see
"Where the baseline must be measured") precisely to avoid that CPU-only-host
variance, so the per-call cost on the GPU lanes where it actually runs is not
separately documented — but the workload is large enough that warmup or host/GPU
contention on a busy self-hosted lane can still push a run across 600 s. Either
way the test has **no assertion about performance at all**: its only failure
mechanism is the timeout, which fails opaquely rather than reporting a measured
number saying "the path got slower."

The task asks for a real, measured guard: assert a floor on comparisons per
second on the per-call `productSimilarity` path the test exercises, sized so a healthy run
finishes well inside its timeout, so that a genuine slowdown fails with a
reported throughput number instead of a timeout. The assertion is additive: no
existing test is loosened or removed.

## Scope

In `engine/utils/src/test/java/org/almostrealism/collect/test/SimilarityOverheadTest.java`:

- **Add** a new `@Test` method (proposed name `pairwiseSimilarityThroughputFloor`)
  that:
  - keeps the existing GPU assumption guard
    (`Assume.assumeTrue(... isAvailable(ComputeRequirement.GPU))`) so it runs only
    where an accelerator is present, matching the configuration whose timing
    matters;
  - drives the **same** `productSimilarity(cp(a), cp(b), FRAMES)` helper the
    existing `pairwiseSimilarityAtScale` uses, so it measures the identical
    per-call expression-tree pattern (see the note under "Dependencies and open
    questions" on how that relates to the current production path);
  - uses a **right-sized** workload (a fresh, smaller `count`) chosen so that a
    healthy CI run completes in a small fraction of the method's timeout, leaving
    enough headroom that normal variance never approaches it;
  - computes comparisons-per-second over the timed region and asserts it is at or
    above a documented floor derived from the measured baseline on the CI GPU
    lane, with a generous safety margin;
  - on failure, the assertion message reports the **measured** comparisons/sec
    and the floor, so a regression is diagnosed by a number, not a timeout.

- **Leave `pairwiseSimilarityAtScale` and every other existing method unchanged.**
  The at-scale method stays as the full-scale stress/profiling test (it is the
  one meant to run under JMX). Nothing existing is resized, re-timed, skipped, or
  weakened.

Out of scope (name as follow-ups / open questions, do not widen here):
- Optimising the per-call `productSimilarity` path itself.
- Changing `WaveDetailsFactory` or the production similarity implementation.
- Retiring or reworking `pairwiseSimilarityAtScale`'s timeout.

## Approach

### Why a new method rather than editing `pairwiseSimilarityAtScale`

Two constraints push toward adding a sibling method instead of resizing the
existing one:

1. **The task wants the workload "sized to finish well inside the timeout."**
   `pairwiseSimilarityAtScale` runs at `count = 1000` (~500K comparisons), which
   at baseline nearly fills its 600 s budget — that is the root of the flake.
   Making it "finish well inside the timeout" requires a *smaller* workload.

2. **Shrinking the existing workload would trip the test-integrity detectors.**
   `tools/ci/agent-protection/detect-test-hiding.sh` flags, on modified
   base-branch test files, both a reduced `int count = N` assignment
   (Pattern 10, `DIMENSIONS_REDUCED`, fires when a value > 4 is more than halved)
   and large numeric literals removed without replacement (Pattern 11,
   `NUMERIC_LITERALS_SHRUNK`). Reducing `pairwiseSimilarityAtScale`'s `count`
   from 1000 would read as dimension reduction — a known deception pattern — and
   fail `test-integrity-check`.

A new test method carries a *fresh* (smaller) `count` literal and its own
timeout; it adds assertions rather than removing any, so none of the detectors
fire, and the existing test is untouched. This satisfies the task's literal ask
("add a new assertion … do not loosen or remove anything existing") while giving
the suite the throughput gate the title calls for. `pairwiseSimilarityAtScale`
keeps its timeout as a backstop; the new method is what turns a slowdown into a
measured failure.

### Shape of the new method

Mirror the timed region of `pairwiseSimilarityAtScale`:

- build the random tensors with `createRandomTensors(count, FRAMES, BINS)`
  (reuse the existing helper, same `FRAMES = 100`, `BINS = 40`);
- record `System.nanoTime()`, run the nested pairwise loop calling
  `productSimilarity(cp(tensors[i]), cp(tensors[j]), FRAMES)`, count comparisons;
- after the loop, compute
  `comparisonsPerSecond = totalComparisons / elapsedSeconds`, `log(...)` it
  alongside ms/comparison (consistent with the other methods' logging), and
  `Assert.assertTrue(message, comparisonsPerSecond >= FLOOR)`.

Express the floor as a named constant with javadoc stating the measured baseline,
the machine/lane it was measured on, and the margin applied — for example a floor
set well below the observed healthy rate so that only a real regression (not
ordinary host variance) trips it. The javadoc records the number so a future
reader can see what "healthy" meant when the floor was chosen; `check-temporary-identifiers`
and the docs rules are respected (no plan-document reference, no ephemeral ids in
the test).

### Sizing

Pick `count` so the healthy run is a small fraction of the method's timeout.
The implementer measures the actual per-comparison cost on the CI GPU (Metal)
lane and sets both `count` and the method `timeout` from that measurement:

- `count` large enough that the comparisons-per-second figure is stable (not
  dominated by first-call warmup), e.g. a few tens of thousands of comparisons;
- `timeout` comfortably above the healthy runtime (but still a real guard), so
  that under a 2–3x slowdown the throughput assertion fires *before* the timeout
  would — the whole point is a measured failure, not a timeout.

The exact `count`, `timeout`, and `FLOOR` are set from the measured baseline
during implementation; the plan fixes the structure, not the magic numbers,
because they depend on the CI hardware.

### Where the baseline must be measured

The timed path only runs where `ComputeRequirement.GPU` is available, and the
class's `@Before requireReclaimingGpuProvider` additionally skips the whole suite
when OpenCL is the only GPU backend. In `.github/workflows/analysis.yaml` that
leaves **two** self-hosted lanes on which the new method actually executes:

- the **macOS / Metal** lane `test-mac` (`runs-on: [self-hosted, macos, ar-ci]`,
  `AR_HARDWARE_DRIVER=*`), and
- the **Linux / CUDA** lane `test-cuda` (`runs-on: [self-hosted, linux, ar-ci-cuda]`,
  `AR_HARDWARE_DRIVER=native,cuda`) — the `@Before` guard does not fire there
  because no OpenCL context is present.

The OpenCL lane `test-cl` (`native,cl`) is skipped by the `@Before` guard, and the
`test` / `test-media` lanes run `AR_HARDWARE_DRIVER=native` (CPU) and skip via the
GPU assumption. The key consequence for sizing the floor: a **pull request** runs
only the Metal lane (plus flowtree), while CUDA (and OpenCL) run **after merge to
master** — see the `analysis.yaml` note "the Metal and flowtree lanes only; master
runs every backend after the merge". So the floor is verified on the PR against
Metal alone, but once merged it must also hold on the CUDA lane; a Metal-only
measurement is not by itself evidence that the floor is safe there. The
implementation session is given a macOS node to measure the Metal baseline
(`task-20261124-213950-workstream.yaml`, `requiredLabels: platform: macos`) and
has no CUDA node, so the CUDA throughput is unmeasured during implementation. That
is what makes a generous margin (below) load-bearing rather than cosmetic: it is
the only thing standing between the floor and a fresh flake on the CUDA lane after
merge. See the open questions on the floor margin and on whether the floor should
be scoped to a single backend.

## Success criteria

- A new `@Test` method exists in `SimilarityOverheadTest` that asserts a
  comparisons-per-second floor on the `productSimilarity` path, with the measured
  baseline and margin documented in the test (constant javadoc).
- The new method's failure message reports the measured throughput and the floor
  (a slowdown fails with a number, not a timeout).
- `pairwiseSimilarityAtScale` and all other existing methods are byte-for-byte
  unchanged except where unavoidable; no dimension, timeout, tolerance, depth, or
  assertion change to any existing method.
- The new method passes on the CI lanes:
  - skips cleanly (via the GPU assumption) on the Linux CPU lanes;
  - runs and passes on the macOS/Metal lane with comfortable headroom below the
    timeout.
- `test-integrity-check` passes (no detector fires), and the build validator
  (`checkstyle`, `code_policy`, `test_timeouts`, `duplicate_code`) is clean —
  note the new `@Test` must carry a `timeout`.

**Tests that prove it:** run the new method (and, to confirm nothing regressed,
`cachedResultsMatchBaseline` / `pairwiseSimilarityBaseline` as neighbours) via
`mcp__ar-test-runner__start_test_run` with
`test_classes: ["SimilarityOverheadTest"]`, `test_methods` naming the new method,
on the Metal lane. CI's macOS lane is the authoritative confirmation.

## Dependencies and open questions

- **Dependency: a macOS/Metal node for implementation.** The baseline must be
  measured where the GPU path actually runs. Captured in
  `docs/plans/task-20261124-213950-workstream.yaml`.
- **Open question — floor margin.** How much safety margin below the measured
  healthy rate? The margin has to absorb two sources of variance at once: ordinary
  self-hosted-host contention on the Metal lane, and the *unmeasured* throughput of
  the CUDA lane, where the test also runs after merge (see "Where the baseline must
  be measured"). A floor set at a bare ~2x below the Metal healthy rate risks
  simply relocating the timeout-flake to a throughput-floor-flake on either lane.
  Recommended: derive the rate from a warm, steady-state region (exclude first-call
  warmup) and set the floor **several-fold** below it rather than ~2x. The approver
  may prefer a specific multiplier; the implementer sets the concrete number from
  the Metal measurement and documents it.
- **Open question — should the floor be backend-scoped?** Because the new method
  runs on both Metal and CUDA but the baseline can only be measured on Metal, one
  option is to restrict the throughput assertion to the Metal backend (an added
  assumption on the Metal context) so the floor is a gate on the one hardware whose
  healthy rate is known, and leave CUDA covered only by `pairwiseSimilarityAtScale`'s
  timeout as today. The alternative — the plan's current default — is to run the
  floor on every GPU backend and make the margin wide enough to cover CUDA sight
  unseen. This is a genuine design trade-off (a narrower, better-characterised gate
  vs. broader coverage with a looser threshold); the approver decides. Note a
  backend scope here is a design choice for a *new* assertion, not a weakening of an
  existing test — but it should be written so a reviewer can see that plainly.
- **Open question — is the 600 s budget actually near-full on the GPU lanes?**
  The premise that `pairwiseSimilarityAtScale` "nearly fills" its timeout rests
  on the ~0.8–1.5 ms/comparison the javadoc records for a **CPU-only host**; the
  GPU lanes where the test actually runs have no separately documented per-call
  cost. The implementation session measures the real GPU-lane per-call cost on
  the Metal node anyway (to size `count`/`timeout`/`FLOOR`). If that cost turns
  out to be well below the implied budget — the at-scale run finishing
  comfortably inside 600 s on the GPU lanes — then the "opaque timeout flake"
  this task sets out to replace is rarer than the motivation implies, and the
  approver should weigh the throughput floor on its own merits (a measured
  regression signal on the graph-building path) rather than as a timeout-flake
  fix. Either way the floor is sized from the GPU-lane measurement, not from the
  CPU-only-host figure.
- **Open question — `blocked_by`.** The task record lists no blockers; nothing
  else needs to land first.
- **Not a blocker, noted for context:** the production similarity path
  `WaveDetailsFactory.similarity()` (via its private `cachedProductSimilarity()`)
  now routes through the cached/optimized `cosineSimilarityEvaluable`. There is no
  production method literally named `productSimilarity` — that name survives only on
  the test's private helper and in some of the test's javadoc. The test's per-call
  `productSimilarity` helper deliberately replicates the *unoptimized*
  expression-tree-per-call pattern to isolate Java-side graph-building overhead, so
  the floor guards framework graph-building cost (what `pairwiseSimilarityAtScale`
  measures today), not the current shipping similarity path. This plan keeps
  measuring that same per-call pattern; it does not switch to the cached evaluable.

## Estimated complexity

**Small.** One new self-contained test method plus a documented constant in a
single test file, no production code change. The only non-trivial part is
measuring the baseline on the Metal lane and choosing `count`/`timeout`/`FLOOR`
from it.
