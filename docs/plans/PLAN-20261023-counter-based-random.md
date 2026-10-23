# Plan: Counter-based random numbers generated inside kernels

- **Tracker task:** 18c76a7a-f289-4807-bb07-1c362e563220 — "Counter-based random numbers generated inside kernels"
- **Project / release:** Common / Common 0.76
- **Estimated complexity:** Large (foundation-layer additions plus a new computation and cross-backend tests). See "Dependencies and open questions" for a proposed split.

## Motivation

`org.almostrealism.collect.computations.Random` (`rand`/`randn` in
`CollectionCreationFeatures`) draws its values on the **host** with
`java.util.Random` and caches them in a `PackedCollection` until `refresh()` is
called. Any graph built over a `Random` producer therefore reads data that was
computed on the CPU and copied to the device; fresh noise every step means host
work inside the step loop.

The connectome simulation ([CONNECTOME_MODEL_ORGANISM.md](CONNECTOME_MODEL_ORGANISM.md),
where this work is Phase 1 task 5 [18c76a7a]) needs Poisson drive on stimulated
neurons in its spiking dynamics (Phase 1 task 6 [04470395], which is filed
`after 5`) at every 0.1 ms step — up to
10,000 steps per simulated second — so a per-step host round-trip is exactly the
overhead the simulation cannot afford. The same capability is useful for dropout
masks and diffusion noise without host round-trips.

The requirement is a **stateless, counter-based generator**: its value is a pure
function of `(seed, step counter, element index)`, computed *in the kernel*, so
it is reproducible, independent across counters, and identical across backends.
The step counter is a producer (a device-resident counter like
`AutoregressiveModel`'s `position`), so advancing it needs no recompile.

## Scope

**In scope (what will change):**

1. A new counter-based random **computation** that generates its values inside
   the compiled kernel as a function of `(seed, counter, element index)`, with a
   **uniform** variant (values in `[0, 1)`) and a **normal** variant (standard
   Gaussian). Placement: `compute/algebra`, next to `Random`, in package
   `org.almostrealism.collect.computations` (the placement table in
   `CONNECTOME_MODEL_ORGANISM.md` — "Counter-based device random →
   `compute/algebra` (next to `Random`) or `compute/stats`; General"). See the
   open question on algebra-vs-stats placement.
2. Convenience factory methods alongside `rand`/`randn` in
   `CollectionCreationFeatures` (e.g. `counterRand(shape, seed, counter)` /
   `counterRandn(...)`), so the new generator is reachable through the same
   `Features` mixin, taking the seed and the counter **as producers**.
3. The integer/bitwise `Expression` primitives the hash needs that do not exist
   yet in `base/code` (`io.almostrealism.expression`) — see the Approach. This
   is the part that turns a "new computation" into foundation work, and is the
   main reason the task is sized Large.
4. Javadoc on the new computation explaining precisely how it differs from
   `Random` (stateless, in-kernel, pure function of `(seed, counter, index)`, no
   host cache, no `refresh()`).
5. Tests (new test class(es) under `engine/utils` or `compute/algebra` test
   sources, following `TestSuiteBase`) covering: distribution moments,
   reproducibility given `(seed, counter)`, independence across counters, and
   agreement between the Metal and native backends.

**Explicitly out of scope (named as dependencies / later work, not widened
into here):**

- The Poisson/LIF spiking dynamics that consume this generator (Phase 1 task 6,
  `04470395`) — a separate task that is `after` this one.
- Any change to `Random` itself; it stays as the host-cached generator.
- A general bitwise-operations feature set beyond the specific primitives the
  hash requires (do not speculatively add every bit operation; add what the
  chosen hash uses, placed correctly — see Approach).

## Approach

### Where the new behaviour lives

- **The computation** is a `TraversableExpressionComputation` subclass in
  `compute/algebra/.../collect/computations`, modelled on
  `ArithmeticSequenceComputation`. Like it, the new class overrides
  `getExpression(TraversableExpression...)` to return a `CollectionExpression`
  whose `getValueAt(Expression<?> index)` builds the random value symbolically,
  and overrides `signature()` so distinct `(seed-source, distribution)` graphs
  do not collide in the instruction cache. Unlike `ArithmeticSequenceComputation`
  (which can fall back to a host stream), this computation must always compile a
  kernel — producing the value on the host would defeat the task.
- **The element index** inside `getValueAt` is the per-output index already
  threaded through `TraversableExpression` (`io.almostrealism.kernel.KernelIndex`
  is the underlying global-thread index). This is the "element index" term of
  the hash.
- **The counter** is a `Producer<PackedCollection>` input to the computation
  (a single-element collection read with `p(...)`/`cp(...)`), so advancing it is
  a device-side assignment with no recompile — the exact pattern
  `AutoregressiveModel` uses for `position`
  (`a(cp(position), add(cp(position), c(1.0)))`, read via `p(position)`). The
  plan does **not** introduce a new counter owner; it consumes whatever
  single-element producer the caller advances (the simulation loop owner, task
  `6d8bbb62`, will own the advance). The counter value enters the hash as a
  second key alongside the element index.
  **Precision limit:** a `PackedCollection` holds floating-point values, which
  are FP32 on Metal, so the counter is exact only up to 2^24 (≈16.7M steps —
  about 28 simulated minutes at 0.1 ms per step) before consecutive counters
  round to the same value and the streams repeat. The computation reads the
  counter with a `Cast` to `int`; the Javadoc must state this ceiling, and a
  longer-running consumer needs a wider counter (e.g. a two-word counter, or
  task 2's integer-exact storage [2c0793a7]) — see open question 6.
- **The seed** is a compile-time constant key (folded into the hash and into
  `signature()`), or optionally a producer if a caller needs to vary it without
  recompiling; the default and simplest form is a constant seed.

### The hash

A counter-based RNG hashes the key `(seed, counter, index)` to a pseudo-random
word. Good hashes in this family (Philox, Threefry, PCG, the "squares" RNG,
MurmurHash finalizers, SplitMix64) are built from **integer multiply**,
**add**, **XOR**, and **bit-rotations/shifts**. The decisive finding of this
investigation:

> The `Expression` layer (`base/code/io.almostrealism.expression`) today has
> integer `Product`, `Sum`, `Mod`, `Quotient`, `And`, and `Cast` to `int`/`long`,
> and renders real `int`/`long`/`double` types in generated code — **but it has
> no XOR, no left/right bit-shift, no bitwise OR, and no bitwise NOT**. The only
> bitwise expression is `And` (added for `Mod`'s power-of-two optimization).

`And` is also typed `Integer`-only (its constructor rejects any other operand
type), so it cannot mask a `Long` intermediate down to 32 bits either.

Two further gaps matter for *any* hash, including Route B below:

- **No unsigned type.** `Expression` values are `Integer`, `Long` or `Double`,
  and `Cast` knows only `int`, `long` and `double`. Counter-based hashes are
  specified over `uint32` with modulo-2^32 wraparound. Signed `int` overflow is
  undefined behaviour in C and OpenCL C (the native compiler is free to
  optimise on the assumption it never happens), so a multiply that is meant to
  wrap cannot simply be emitted as `int * int`. The implementation must either
  add an unsigned 32-bit type/cast to the expression layer and every
  `LanguageOperations`, or do the arithmetic in `long` and reduce modulo 2^32
  explicitly (which needs a `Long`-capable mask or `Mod`).
- **The host fold does not wrap.** `Product.computeValue` and `Sum.computeValue`
  accumulate `Integer` children in `long` and return a `long` when the result
  leaves the `int` range; they never truncate to 32 bits. Any partially
  constant-folded hash term (for example `seed * MULTIPLIER` with a constant
  seed) would therefore fold on the host to a different value than the kernel
  computes. Wraparound semantics must be explicit in the expression tree, not
  left to the rendered C type.
- **32×32-bit products are 64-bit.** Multiplying two values below 2^32 gives a
  result up to 2^64: beyond `IndexRange.MAX_EXACT` (2^53, where
  `IndexRange.exact` throws `InexactValueException`) and, as signed `long`,
  beyond `Long.MAX_VALUE`. "Stay in 32 bits" therefore holds only for values
  *between* operations; each multiply must be truncated (or computed as a
  `mulhi`/`mullo` pair, as Philox does) and the bound/sequence analysis must not
  try to enumerate these intermediates exactly.

So the hash cannot be expressed today. Two routes:

- **Route A (recommended): add the minimal bitwise `Expression` primitives**
  the chosen hash needs — at least `BitXor` and a bit-shift (left and right);
  a rotate is composable from two shifts and an `or`, so an `or` may also be
  needed. These are small `BinaryExpression`/`UnaryExpression` subclasses next
  to `And`, each of which must (a) render the correct operator for every
  backend language and (b) fold on the host with Java semantics that **match the
  generated code exactly** — the same fold-vs-codegen discipline `Mod` already
  follows (`Math.floorMod` vs `%`), and must return conservative
  `upperBound`/`lowerBound`/`kernelSeries`/`arithmeticSequence` so they do not
  mislead the kernel optimizer. This route yields a statistically strong,
  well-understood hash (e.g. a 32-bit Philox round or a squares-style hash) and
  the primitives are generally useful (the connectome ingest and integer-exact
  indexing tasks also want integer bit manipulation).
- **Route B (fallback): a multiply/add/mod-only hash** (an LCG combined with a
  Weyl sequence, or a multiplicative finalizer using only `Product`, `Sum`,
  `Mod`), avoiding any new `Expression` class. This keeps the change inside
  `compute/algebra` but gives weaker mixing; it may be adequate for Poisson
  drive but is harder to defend against the "distribution moments" and
  "independence across counters" tests, and tends to show structure in low
  bits. If chosen, the moment/independence tolerances must be set with that in
  mind. Route B does **not** escape the wraparound/host-fold gaps above: an LCG
  multiply overflows 32 bits just as a Philox round does, so it still needs
  either explicit `Mod` by 2^32 on `Long` intermediates (and those
  intermediates still exceed 2^53) or a small multiplier chosen so that no
  intermediate exceeds the exact range — which weakens it further. "Avoiding
  any new `Expression` class" is therefore only true for a deliberately weak
  generator.

The plan recommends **Route A** and treats the bitwise-primitive additions as a
sub-deliverable of this task (see the split proposal). The hash should be a
named, citable construction (reference in the Javadoc), not an ad-hoc mix.

### Cross-backend identity

"Identical across backends" is achievable for the **integer** stage: unsigned
32-bit arithmetic (multiply and add modulo 2^32, XOR, shifts) produces the same
bits on Metal (an FP32 backend, but with real integer registers) and on the
native C and CUDA backends (whose floating-point `Precision` may be FP64) — *provided*
the wraparound is well defined in the emitted code, which, per the gaps listed
under "The hash", means unsigned types or explicit modulo-2^32 reduction rather
than signed `int` overflow. The design must therefore keep the entire
hash in integer registers and convert to floating point **only at the very end**
(`uint → [0,1)`), never routing an intermediate through `float`, because an
FP32 backend is exact only to 2^24 and would diverge. Two consequences:

- Keep the hash in **32-bit** space (values `< 2^32`). This also keeps the
  host-side constant-fold / index-analysis path exact: `IndexRange.exact` throws
  `InexactValueException` beyond ~2^53, and staying in 32 bits avoids it.
- The final `uint → float` division (`× 2^-32`) is the one step that is FP32 on
  Metal and FP64 on native, so the uniform value agrees only to **float
  epsilon** (~1e-7), not bit-for-bit. The Metal/native agreement test must
  assert equality to a float-precision tolerance, not exact equality. (This is
  worth confirming on hardware as an early spike — see open questions.)

### Uniform and normal variants

- **Uniform:** final `uint → [0,1)` conversion of the hashed word.
- **Normal:** derive from two uniforms via **Box–Muller**
  (`sqrt(-2 ln u1) · cos(2π u2)`) using the existing `Logarithm` (`log()`),
  `Exponent` (`pow(0.5)` — there is no dedicated square-root expression) and
  `Cosine`/`Sine` expressions, or via a **Moro inverse-CDF**
  polynomial. Box–Muller is simplest and composes from primitives that already
  exist; it consumes two counter sub-streams (e.g. index `2k` and `2k+1`, or two
  distinct seed lanes) so each normal sample stays a pure function of the key.
  `u1` must be mapped to `(0, 1]` (not `[0, 1)`), otherwise `ln(0)` yields
  `-inf` and the sample is infinite for the one key that hashes to zero.

### Reference files

| Concern | File / symbol |
|---|---|
| Host-cached generator to contrast with | `compute/algebra/.../collect/computations/Random.java` |
| Factory methods (`rand`/`randn`) to sit beside | `compute/algebra/.../collect/CollectionCreationFeatures.java` |
| Computation shape to model on | `compute/algebra/.../collect/computations/ArithmeticSequenceComputation.java`; `TraversableExpressionComputation`, `DefaultTraversableExpressionComputation` |
| Symbolic per-index value | `io.almostrealism.collect.TraversableExpression#getValueAt`, `io.almostrealism.collect.ArithmeticSequenceExpression` |
| Kernel element index | `io.almostrealism.kernel.KernelIndex` |
| Existing integer/bitwise expressions (and the gap) | `base/code/.../expression/{Product,Sum,Mod,Quotient,And,Cast}.java` (no `BitXor`/shift/unsigned type today; `Product`/`Sum` `computeValue` widen to `long` rather than wrap) |
| Exact-range guard for host index analysis | `base/code/.../sequence/IndexRange.java` (`MAX_EXACT`, `exact(long)`, `InexactValueException`) |
| Pinning a computation to one backend in a test | `setComputeRequirements(List.of(ComputeRequirement.MTL))`, as in `engine/utils/.../hardware/test/MemoryDataCopyChainingTest.java` |
| Device-resident counter pattern | `engine/ml/.../ml/AutoregressiveModel.java` (`position`, `advancePosition`) |
| Distribution helpers (possible stats home) | `compute/stats/.../stats/DistributionFeatures.java` |

## Success criteria

A reviewer can confirm the task is done when:

1. A counter-based random **computation** exists in `compute/algebra`, reachable
   through `CollectionCreationFeatures`, producing uniform and normal variants
   whose values are generated **in the compiled kernel** (verifiable in the
   generated source via `ar-profile-analyzer`: the random value is computed from
   the index/counter, not read from a host-filled buffer), with the counter
   supplied as a producer so advancing it triggers no recompilation.
2. **Distribution-moment tests:** a large sample's mean and variance match the
   target distribution within tolerance (uniform ≈ mean 0.5, var 1/12; normal ≈
   mean 0, var 1), with the tolerance stated and justified by sample size.
3. **Reproducibility test:** two evaluations with the same `(seed, counter)`
   produce identical output.
4. **Independence test:** outputs for consecutive counters (and distinct seeds)
   are uncorrelated — e.g. sample correlation near zero and no collisions — so
   advancing the counter yields a genuinely different stream.
5. **Metal↔native agreement test:** the same `(seed, counter, shape)` evaluated
   on the Metal backend and the native backend agree to the documented
   tolerance (float epsilon for the uniform; a correspondingly looser bound for
   the normal). This is the test that requires a macOS node. It can run in one
   JVM by pinning each evaluation to a backend with `setComputeRequirements`
   (see the reference table), provided both drivers are enabled for the run.
   Before comparing, the test should also assert that the hash's integer stage
   is bit-identical (e.g. by comparing the integer words, or the uniform output
   scaled back by 2^32 and rounded), so a wraparound bug is not hidden inside
   the float tolerance.
6. **Javadoc** on the new class explains how it differs from `Random`
   (stateless, in-kernel, pure function of `(seed, counter, index)`, no cache,
   no `refresh()`), and names the hash construction used.
7. The build validator passes (checkstyle, code_policy, test_timeouts,
   duplicate_code) and the targeted tests above pass; CI's full matrix is left
   to confirm backend breadth.

## Dependencies and open questions

**This task is large and is a good candidate to split.** Proposed split (all
three land to satisfy "done when", but they are reviewable in sequence):

- **(a) Bitwise `Expression` primitives** in `base/code` (`BitXor`, bit-shift,
  and any `or`/`not` the chosen hash needs, plus well-defined 32-bit
  wraparound — an unsigned type/cast or explicit modulo-2^32 reduction — for
  multiply and add), with host-fold/codegen parity and
  sound bound/kernel-series behaviour, plus their own unit tests. *If the
  reviewer prefers, this can be filed as a separate foundation task that this
  one is `blocked_by`.* (Route B avoids the bitwise classes at a quality cost,
  but not the wraparound work — see "The hash".)
- **(b) The counter-based computation + factory methods + Javadoc** in
  `compute/algebra`.
- **(c) The test suite**, including the Metal↔native agreement test.

**Open questions for the approver:**

1. **Route A vs Route B:** add bitwise `Expression` primitives for a strong
   named hash, or restrict to a multiply/add/mod-only hash to avoid
   foundation-layer changes? The recommendation is Route A; the decision sets
   the scope boundary and whether a separate foundation task is filed.
2. **Home module — `compute/algebra` vs `compute/stats`:** the CONNECTOME plan
   allows either. `Random` and the `rand`/`randn` factories are in
   `compute/algebra`, which argues for co-location; `DistributionFeatures` is in
   `compute/stats`, which argues the normal/Box–Muller logic belongs there —
   although today `DistributionFeatures` holds only discrete sampling
   (`sample`, evaluated on the host) and `softmax`, not any continuous
   distribution, so it is not yet an established home for Gaussian code.
   Proposed: the raw uniform generator and its factory next to `Random` in
   `compute/algebra`; distribution shaping (normal) may live in `compute/stats`
   if that keeps `DistributionFeatures` the single home for distribution code.
   Approver to confirm.
3. **Seed as constant vs producer:** default to a compile-time constant seed
   (folded into `signature()`); offer a producer-seed overload only if a
   consumer needs to rejig the seed without recompiling. Confirm whether the
   simulation needs the latter.
4. **Exact vs tolerance for "identical across backends":** confirmed above that
   the integer stage is bit-identical but the final float conversion is not
   (FP32 vs FP64). The test asserts float-epsilon agreement. An early
   **hardware spike** on a macOS node should confirm the integer ops render and
   wrap identically on Metal before the full computation is built — this is the
   single highest-risk assumption.
5. **Normal method:** Box–Muller (simple, uses existing primitives) vs Moro
   inverse-CDF (single uniform per sample, no trig). Recommendation: Box–Muller.
6. **Counter width:** a single FP32-backed counter repeats after 2^24 steps
   (≈28 simulated minutes at 0.1 ms per step). `CONNECTOME_MODEL_ORGANISM.md`
   does not state the simulated durations of the Phase 1 runs; the approver
   should confirm they stay well under this, or require a wider counter in the
   first version.
7. **Unsigned arithmetic in the expression layer:** adding a `uint32` type is a
   cross-cutting change to `base/code` and to every `LanguageOperations`
   (C, OpenCL, Metal, CUDA); emulating wraparound with `long` + explicit
   reduction is local but produces intermediates above 2^53 that the index
   analysis cannot treat exactly. The approver should choose before (a) starts,
   because it decides (a)'s size.

**Follow-up filed:** none required beyond the existing Phase 1 tasks; the
consumer (spiking dynamics, `04470395`) is already filed and lists this task as
a predecessor. If the approver chooses to split (a) into its own foundation
task, that task should be created and set as a blocker of this one.

## Machine requirement

The Metal↔native agreement test (success criterion 5) can only run where Metal
is available, which on FlowTree means a macOS node. The implementation workstream
is therefore pinned to `platform: macos` via
`docs/plans/task-20261023-170808-workstream.yaml`.
