# Order-dependent wrong result after instruction-cache eviction

A test that passes alone fails when earlier tests in the same JVM compile enough
distinct kernels. Found while migrating `TraversableDeltaComputationTests`
assertions off host loops: adding three whole-collection comparisons — which add
kernels but change no assertion logic — made an untouched test in the same class
fail.

This document tracks that investigation alone.

## Symptom

`TraversableDeltaComputationTests.divideProduct3` fails at
`TraversableDeltaComputationTests:705`, inside `divideProduct`'s validate lambda
(`assertSimilar`), comparing a computed gradient against a host-side reference.

## Established facts

Measured, not inferred. Read the whole table before concluding anything — the
early rows invite a conclusion the later rows destroy.

| # | Configuration | Tests | Result |
| --- | --- | --- | --- |
| 1 | `divideProduct3` alone, HEAD | 1 | pass |
| 2 | Whole class, HEAD, default cache | 44 | pass |
| 3 | Whole class, **with** conversions, default cache | 44 | **1 fail** (`divideProduct3`) |
| 4 | Whole class, with conversions, `AR_INSTRUCTION_CACHE_SIZE=5000` | 44 | pass |
| 5 | Whole class, HEAD, `AR_INSTRUCTION_CACHE_SIZE=50` | 44 | pass |
| 6 | Whole class, with conversions, `AR_INSTRUCTION_CACHE_SIZE=50` | 44 | pass |
| 7 | Whole class, with conversions, default cache, **×3** | 58 ×3 | pass, pass, pass |

**The failure has been observed exactly once (row 3) and has not reproduced
since — including three consecutive repeats of that identical configuration
(row 7).**

### What this means

The failure is **intermittent**, not deterministic. Two conclusions drawn from
row 3 alone must therefore be withdrawn:

- *"The conversions caused it."* Rows 3 and 2 were single runs each. One failure
  against one pass is not causation when the failure does not repeat under the
  same configuration.
- *"It is order-dependent."* Consistent with row 1, but not established — an
  intermittent fault also passes in isolation most of the time.

What survives: **there is a real, rare, nondeterministic wrong answer** in this
class. `assertSimilar` compared a computed gradient against a host reference and
they differed. That is a genuine defect regardless of what provoked it, and it is
worth more attention than a deterministic one, not less — it is the kind that
reaches CI as an unexplained flake.

### Second anomaly — found and fixed (tooling bug)

Rows 1–6 report **44** tests; row 7 reports **58**. This was a defect in
`ar-test-runner`, not in the test selection.

`_copy_surefire_reports_to_invocation` copied the module's entire
`target/surefire-reports` directory, on the stated assumption that *"Maven
overwrites reports each time, no time filtering is needed."* That assumption is
false: Maven overwrites the report for a class it actually runs and leaves every
other report in place, so the directory accumulates results from earlier runs of
other classes indefinitely. The single-invocation path had always filtered by
modification time; the repetitions path did not.

Confirmed by inspection of `engine/utils/target/surefire-reports` at the time:

| Class | Tests |
| --- | --- |
| `TraversableDeltaComputationTests` | 44 |
| `ReplicationMismatchOptimizationTest` | 5 |
| `MultiOrderFilterConvolutionTest` | 4 |
| `TemporalFeaturesTest` | 5 |
| | **58** |

Exactly the reported figure. The three extra classes were left by earlier runs in
the same session and were never part of the run being measured.

Fixed by stamping wall-clock time before each invocation launches and applying
the same modification-time filter the single-invocation path uses. Regression
tests are in `tools/mcp/test-runner/test_server.py`
(`InvocationReportCopyTest`).

**Consequence for this investigation:** row 7's *counts* were inflated, but its
*pass/fail* verdict was not — the stale reports all recorded zero failures, and
`divideProduct3` genuinely passed three times. The withdrawal above stands.

The three conversions that trigger it are in `multiply()`, `embedded2()` and the
enumerate/reshape test. None is in `divideProduct`'s call path. Each replaces a
host index loop with a whole-collection comparison, so each **adds** an evaluated
producer (`diagonal(cp(f))`, `identity(4)`) that did not previously exist.

## The test's inputs are random and unseeded

This was missed for the whole first pass of the investigation and reframes it.

```java
public void divideProduct3() {
    divideProduct("divideProduct3", c, () -> rand(shape(c)).divide(10.0).evaluate());
}

public void divideProduct(String name, int c, Supplier<PackedCollection> source) {
    PackedCollection o = source.get();
    PackedCollection g = rand(shape(c)).multiply(4.0).add(1.0).evaluate();
```

Both `o` and `g` are drawn fresh from `rand` on every run, with no seed. **The test
is nondeterministic by construction**, so intermittency needs no exotic explanation
— a run that fails is a run that drew unlucky inputs.

Note that `divideProduct4` is the same computation with fixed inputs
(`fill(1.0, 1.01)`) and has never been seen to fail. That contrast is the strongest
available evidence that the input draw, not the machinery, is the variable.

This makes an input-conditioning hypothesis the leading one, ahead of everything
below. The computation is

```java
input.subtractMean().divide(mean(sq(subtractMean(input))).add(c(eps)).sqrt())
```

with `eps = 1e-5`. When the drawn values are close together the variance approaches
zero, `eps` dominates the denominator, and the gradient becomes ill-conditioned —
exactly where a relative-tolerance comparison against a host reference would part
company. `o` is scaled by `1/10`, so its spread is already small.

**H0 — the drawn inputs are ill-conditioned for this gradient.** Confirmed if the
inputs recorded at a failure reproduce it when substituted as fixed values.

### Isolation attempt

`divideProduct3` alone, 25 consecutive runs (each drawing new inputs): **25 passed**.
So the failing region of the input space is rarer than 1 in 25 draws, or the
class context contributes as well. This does not yet distinguish those.

**H1 — evicting a cached kernel destroys one a later computation still uses.**

`DefaultComputer` holds

```java
this.instructionsCache = new FrequencyCache<>(
        SystemUtils.getInt("AR_INSTRUCTION_CACHE_SIZE").orElse(500), 0.4);
this.instructionsCache.setEvictionListener((key, mgr) -> mgr.destroy());
```

The cache is keyed by signature alone, is shared JVM-wide, and its eviction
listener **destroys** the evicted `ScopeInstructionsManager`. Adding kernels
pushes the cache past its eviction threshold; if a manager is destroyed while a
later computation still resolves to that signature, the later computation runs
against a destroyed kernel and produces a wrong value rather than an error.

This fits the shape of the symptom exactly: nothing about the failing test
changed, only the *number of distinct kernels compiled before it*.

### Predictions

H1 is confirmed if both hold, and invalidated if either fails:

- **P1** — whole class **with** the conversions but a large
  `AR_INSTRUCTION_CACHE_SIZE` (e.g. 5000) **passes**.
- **P2** — whole class **at HEAD** with a small `AR_INSTRUCTION_CACHE_SIZE`
  (e.g. 50) **fails**, ideally on `divideProduct3`.

P2 is the important one: it reproduces the defect with no source change at all,
which would establish this as a framework bug that the migration merely exposed,
not a defect in the migration.

## Alternative hypotheses

**H2 — signature collision.** Two structurally different computations hash to the
same signature, so the added `identity`/`diagonal` kernels are *reused* by
`divideProduct3` rather than evicted. Distinguished from H1 by cache size having
no effect while the failure tracks which kernels were added.

**H3 — cross-context reuse.** A known prior defect: a kernel compiled under one
`ComputeContext` reused under another because the signature omitted the context.
Recorded as fixed by refreshing the baked signature after
`setComputeRequirements`. Would predict sensitivity to `AR_HARDWARE_DRIVER`, not
to cache size.

**H4 — memory reclamation.** Destroying the manager frees native memory that a
live `PackedCollection` still delegates into, so the corruption is in the data
rather than the kernel. Distinguished by whether the wrong value is stale, zero,
or garbage.

## Status

- [x] Observe the failure (once)
- [x] P1 — large cache with the conversions: **passed**, so not the discriminator it looked like
- [x] P2 — small cache at HEAD: **passed**, invalidating H1 in its naive form
- [x] Repeat the failing configuration ×3: **all passed** — the fault is intermittent
- [ ] **Get a reliable reproduction.** Everything else is blocked on this.
- [ ] Capture the actual wrong value when it happens (stale / zero / garbage) — this is
      the single most discriminating piece of evidence and nobody has it yet
- [ ] Explain the 44 versus 58 test-count discrepancy

## Detection now in place

Since the failure could not be cornered by repetition, the next occurrence is
instrumented to be self-reproducing rather than merely observed.

`assertSimilar` already reported both values through `warn(b + " != " + a + ...)`;
that output was simply never retrieved when it failed. What it did **not** report
was the inputs, which is what a random-input test needs.

- `TestFeatures` gained `assertSimilar(String msg, ...)` overloads, beside the
  `assertEquals(String, ...)` overloads already there, and the failure detail is now
  on the `AssertionError` as well as the log, so it survives into the surefire report.
- `divideProduct` passes the generated `o` and `g` and the failing index.

A future failure therefore names the exact inputs, which convert directly into a
fixed-value case alongside `divideProduct4`. **When one is seen, capture that line
first — it is the whole investigation.**

## How to attack this next

The bottleneck is reproduction rate, so spend effort there before theorising:

1. **Run the class many times, unattended.** `repetitions` on the test runner
   takes up to 100. A fault seen once in ~7 runs needs tens of runs to characterise.
   Record how many failures per hundred, and whether it is always `divideProduct3`.
2. **Capture the value, not just the assertion.** `assertSimilar` reports only that
   two numbers differ. Log both sides when it fails. Whether the wrong value is a
   stale previous result, exactly zero, or garbage discriminates between every
   hypothesis below in one observation.
3. **Widen the JVM, do not narrow it.** The instinct is to shrink to one test, but
   an intermittent same-JVM fault gets *rarer* as the JVM does less. Use
   `test_group` to reproduce CI's ordering, which is where this will actually bite.
4. Only once it reproduces reliably, bisect the conversions — and prefer
   `ar-profile-analyzer` on the generated source over adding `log()` probes.

Note that `divideProduct` is one of the few tests here whose expected value comes
from a **host-side reference computation** (`dlDxGroup`, `normBackwards`) rather
than a literal. If the nondeterminism is in the reference rather than the kernel,
that would not be a framework bug at all — check both sides.

## Notes for whoever continues

- The three conversions are recorded in memory (`bugs` namespace, tagged
  `TraversableDeltaComputationTests`, `divideProduct3`, `order-dependent`) and are
  small enough to redo from that note.
- Run the whole class, never the single method — the failure does not reproduce
  in isolation.
- `mcp__ar-test-runner__start_test_run` with `test_group` reproduces CI's
  same-JVM ordering, which is the right tool if the class-level repro proves
  fragile.
