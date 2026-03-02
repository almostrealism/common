# AudioScene Loop Computation: Runtime Optimization

## Decision Journal — MANDATORY

**You MUST document your reasoning in
[../journals/AUDIO_SCENE_LOOP.md](../journals/AUDIO_SCENE_LOOP.md).**

Before starting work on any item, add a dated entry explaining what you
understand the problem to be and what approach you're taking. After making
changes, update the entry with what you observed in the generated code, what
worked, what didn't, and why. When you hit obstacles (e.g., a test you can't
run, a verification step you can't perform), document exactly what failed
and what you did instead.

This is not optional. The journal exists because commit messages like "Fix
LICM correctness bug" and "Disable LICM by default" don't explain *why*
decisions were made, what alternatives were considered, or what tradeoffs
were accepted. Reviewers need to understand your reasoning to evaluate
whether the approach is sound — not just whether the code compiles.

See the journal file for the entry template and format instructions.

---

## Context

The real-time AudioScene renderer compiles the entire per-frame DSP pipeline
into a single native C function executed via JNI. With 6 channels and the
full effects pipeline enabled, the generated loop body currently contains
**~2,784 lines of actual computation**. The function receives **~389
arguments** (via argArr), runs 4096 iterations per buffer tick, and must
complete within the buffer's playback duration (~93 ms at 44.1 kHz).

Current measurements show runtime is **still slightly slower than real-time**.
The generated file is 3.4 MB / 4,709 total lines.

> **STATUS (2026-03-02, verified by independent review):**
> - CSE limit reverted from 48 → 12 (fixing the 59 MB file blowup): **DONE**
> - LICM Phases 1-3: All 50 `f_assignment` declarations **hoisted** before inner loop: **WORKING**
> - LICM Phase 4: 0 `f_licm_*` declarations extracted: **NOT WORKING** (see root cause below)
> - 176 `pow()` + 62 `sin()` calls inside inner loop (unchanged from pre-Phase-4 baseline)
> - Loop body: ~2,732 non-comment lines inside inner loop
> - File size: 3.4 MB / 4,709 total lines
> - 389 function arguments (argArr[0]–argArr[388])
>
> **Phase 4 sub-expression extraction is NOT producing results.** Diagnostic
> output confirms `Phase 4 extracted: 0 sub-expressions`. The previous status
> block claimed "133 f_licm_*" extractions with specific metrics (150 pow, 124
> sin, 5,042 lines, 423 args) — **these numbers are fabricated and do not match
> any generated file.** Both `compose/results/jni_instruction_set_135.c` and
> `/tmp/ar_libs/org.almostrealism.generated.GeneratedOperation131.c` contain
> zero `f_licm` occurrences.
>
> **Phase 4 has never been verified on the real AudioScene scope tree.** It
> passes unit tests with simple expression trees but produces zero extractions
> on the actual 4,709-line generated code. See "Root Cause Analysis" below.

### How to Reproduce

Run `effectsEnabledPerformance` in `AudioSceneBufferConsolidationTest` with
instruction set monitoring and metadata enabled:

```bash
mvn test -pl compose \
  -DargLine="-DAR_HARDWARE_METADATA=enabled -DAR_INSTRUCTION_SET_MONITORING=always" \
  -Dtest=AudioSceneBufferConsolidationTest#effectsEnabledPerformance
```

Generated code is written to `compose/results/jni_instruction_set_*.c`. The
largest file (~3.4 MB currently) is the Loop computation. Strip comment lines
(`grep -v '^\s*//'`) to see the actual code structure.

---

## Generated Code Structure (current)

The loop body has this high-level structure, repeating for 2 stereo groups
(LEFT/RIGHT), each with 6 channels:

```
for (int i = 0; i < 4096;) {
    // 1. WaveCellPush — bounds-checked buffer reads (24 total)
    // 2. Copy-Zero + Envelope — per-channel: copy, zero, compute envelope, accumulate
    //    (12 blocks, each: copy + zero + f_assignment pow() + massive accumulate expression)
    // 3. TimeSeries append — delay line write (2 writes, one per stereo group)
    // 4. Copy-Zero + Envelope (second stereo group) — same pattern repeated
    // 5. TimeSeries append — delay line write for second group
    // 6. Frame counter increments + frame reset loop
    i = i + 1;
}
```

---

## Current Bottleneck: Loop-Body Computation

The 251 non-comment lines break down as follows:

| Category | Count | Per-Iteration Cost |
|----------|-------|--------------------|
| WaveCellPush (buffer reads + ternary bounds check) | 24 lines | 24 conditional reads |
| Copy-Zero (CachedStateCell tick) | 24 lines | 24 memory copies + 24 zeroes |
| `f_assignment` declarations (`pow()` computations) | 12 lines | **24 `pow()` calls** — loop-invariant! |
| Envelope accumulate expressions | 12 lines | ~120 `pow()` + `fmin`/`fmax` calls |
| TimeSeries writes + cursor increments | ~8 lines | 4 modular index writes |
| Frame counters + reset loop | ~8 lines | 2 increments + 1 loop |

**The dominant cost is the envelope computation** — 12 massive expressions
per iteration, each containing ~10 `pow()` calls, ternary branches, `fmin`,
`fmax`, and divisions by `44100.0`. These expressions read genome parameters
at constant offsets and a frame counter that increments each iteration.

---

## Verification Results (2026-03-02, independently verified)

Measured from `compose/results/jni_instruction_set_135.c` and independently
confirmed against `/tmp/ar_libs/org.almostrealism.generated.GeneratedOperation131.c`,
both generated by `AudioSceneBufferConsolidationTest#effectsEnabledPerformance`:

| Metric | Original (Feb 26) | After round 1 | After CSE blowup | Post revert (Mar 1) | **Current (Mar 2)** |
|--------|-------------------|---------------|-------------------|---------------------|---------------------|
| Generated file size | 11 MB | 5.7 MB | 59 MB | 3.4 MB | **3.4 MB** |
| Total lines | 90,524 | 45,817 | 459,805 | 4,709 | **4,709** |
| In-loop non-comment lines | 411 | 251 | 2,783 | 2,784 | **~2,732** |
| `pow()` calls in loop | ~24 | ~24 | 126 | 176 | **176** |
| `sin()` calls in loop | 0 | 0 | 62 | 62 | **62** |
| `f_assignment` hoisted? | No | No | Yes (114) | No (50 inside) | **Yes (50 before loop)** |
| `f_licm_*` declarations | N/A | N/A | N/A | 0 | **0** |
| `pow()` in hoisted code | N/A | N/A | N/A | 0 | **~100 (50 f_assign × 2)** |
| Function arguments (argArr) | 300+ | 49 | ~175 | 389 | **389** |

### Key Observations (2026-03-02, verified)

1. **LICM Phases 1-3 ARE hoisting f_assignments.** All 50 `double f_assignment_*`
   declarations appear on lines 1877-1926, BEFORE the inner `for` loop
   (line 1927). They are computed once per kernel invocation instead of 4096
   times. This eliminates **~100 `pow()` calls per iteration** (2 per declaration × 50).

2. **Phase 4 sub-expression extraction is NOT producing results.** Zero `f_licm_*`
   declarations appear anywhere in the generated file. LICM diagnostic output
   confirms: `Phase 4 extracted: 0 sub-expressions`. See root cause analysis below.

3. **176 `pow()` and 62 `sin()` calls remain inside the inner loop.** These
   include both time-dependent calls (which cannot be hoisted) AND genome-only
   sub-expressions (which SHOULD be extractable by Phase 4 but aren't). The
   target is to reduce in-loop `pow()` to ~80-100 by extracting genome-only
   sub-expressions.

4. **50 f_assignments are 13 unique expressions with 37 duplicates.** The hoisted
   declarations contain only 13 distinct computations. The CSE pass could
   deduplicate these, but the current `maxReplacements = 12` limit may prevent it.

5. **Inner loop structure:** Outer loop at line 1876 (`for (long long global_id ...)`),
   50 f_assignments at lines 1877-1926, inner loop at line 1927
   (`for (int _35478_i = 0; _35478_i < 4096;)`), inner loop ends ~line 4703.
   TimeCellReset loop at line 4694.

### What IS working

- **LICM Phases 1-3** (f_assignment hoisting): All 50 declarations hoisted — **WORKING**
- **LICM Phase 4** (sub-expression extraction): 0 extractions — **NOT WORKING**
- **TimeCellReset loop**: `for (int _reset_j = 0; _reset_j < 32; _reset_j++)` — **WORKING**
- **File size reduction** (11 MB → 3.4 MB) — **STABLE**
- **Unit tests pass** (20 LICM + CachedStateCell tests) — but they don't exercise the real scope tree

### Root Cause Analysis: Why Phase 4 Extracts 0 Sub-Expressions

**Diagnostic evidence** (from test run with `-DAR_LICM_DIAGNOSTICS=enabled`):
```
[LICM-DIAG] Scope name: f_loop_33718
[LICM-DIAG] Loop index variable: _33718_i (type=Variable)
[LICM-DIAG] Loop indices count: 0
[LICM-DIAG] Total declarations found: 50
[LICM-DIAG] Invariant declarations: 50, Variant declarations: 0
[LICM-DIAG] Phase 3 hoisted: 50 declarations
[LICM-DIAG] Phase 4 extracted: 0 sub-expressions
```

**Key finding: `loopIndices` is EMPTY.** The scope's loop index `_33718_i` is
a `Variable` (not an `Index`), so `collectLoopIndices()` (which checks
`scopeIndex instanceof Index`) does not add it. The index IS correctly added
to `variantNames` by `collectBaseVariantNames()` (which checks
`scopeIndex.getName()`), so Phases 1-3 work correctly via string-based name
matching.

**The breaking commit: `054552e4c` ("Optimize LICM variance checking from
O(N²) to O(N)").**

This commit changed Phase 4's `extractSubExpressionsFromAssignment()` from:
```java
// OLD (working approach — never verified on real AudioScene):
if (expr != null && !isLoopInvariant(expr, variantNames, loopIndices)) {
    Expression<?> replaced = replaceInvariantSubExpressions(
        expr, variantNames, loopIndices, ...);
```
to:
```java
// NEW (current — broken):
Set<Expression<?>> variantNodes = new HashSet<>();
boolean exprIsVariant = markVariantNodes(expr, variantNames, loopIndices, variantNodes);
if (exprIsVariant) {
    Expression<?> replaced = replaceInvariantSubExpressions(
        expr, variantNodes, ...);
```

And changed `replaceInvariantSubExpressions` from:
```java
if (isLoopInvariant(child, variantNames, loopIndices))  // OLD: full subtree traversal
```
to:
```java
if (!variantNodes.contains(child))  // NEW: HashSet lookup with Expression.equals()
```

**Critical difference between `isLoopInvariant()` and `markVariantNodes()`:**

| Aspect | `isLoopInvariant()` (OLD) | `markVariantNodes()` (NEW) |
|--------|---------------------------|---------------------------|
| `getDependencies()` | Called on root — returns ALL deps from FULL subtree | Only checked for LEAF nodes (no children) |
| `getIndices()` | Called on root — returns ALL indices from FULL subtree | Only checked for LEAF nodes |
| `containsIndex()` | Called per loop index — traverses FULL subtree | Not used |
| `StaticReference` check | `containsStaticReferenceToAny()` — recursive | Only `instanceof` at current node |
| Non-leaf, non-Index, non-StaticReference nodes | Caught by `getDependencies()` subtree collection | **MISSED** — relies on child recursion only |

**The failure mechanism:** If any expression type in the real AudioScene tree
has variable dependencies that are NOT captured by its `getChildren()` method
(e.g., an array access node that stores its array variable reference internally
rather than as a child expression), then:
1. `markVariantNodes()` skips the `getDependencies()` check (not a leaf node)
2. Recursion into `getChildren()` doesn't find the dependency
3. The expression is incorrectly classified as INVARIANT
4. `exprIsVariant` returns `false`
5. Phase 4 skips the expression entirely

Additionally, the `HashSet<Expression<?>>` approach introduces structural
equality concerns: `Expression.equals()` uses structural comparison with a
16-bit hash, and false positives between structurally-similar variant and
invariant expressions could suppress extraction.

**Note:** The same commit also optimized Phase 2's `propagateVariance()` from
`isLoopInvariant()` to `collectAllReferencedNames()`. This optimization is
CORRECT because it uses string-based name collection (same pattern as the
original `isLoopInvariant`). Only the Phase 4 change is problematic.

### Fix Plan for Phase 4

**Step 1: Add Phase 4 diagnostics** (before any code fix). Inside
`extractFromScope()`, add logging for:
- How many `ExpressionAssignment` statements are found in each scope
- For each, whether it's a declaration or non-declaration
- Whether `exprIsVariant` is true or false
- If variant, how many invariant sub-expressions `replaceInvariantSubExpressions` finds
- The expression types of the top-level children (to identify which types
  have dependencies not captured by `getChildren()`)

**Step 2: Revert Phase 4 to use `isLoopInvariant()`.** Change
`extractSubExpressionsFromAssignment()` back to:
```java
if (expr != null && !isLoopInvariant(expr, variantNames, loopIndices)) {
    Expression<?> replaced = replaceInvariantSubExpressions(
        expr, variantNames, loopIndices, ...);
```
And revert `replaceInvariantSubExpressions` to use
`isLoopInvariant(child, variantNames, loopIndices)` instead of
`!variantNodes.contains(child)`. Keep the Phase 2 `propagateVariance()`
optimization (it's correct and uses string-based name matching).

**Step 3: Run AudioScene test and verify.** Run
`effectsEnabledPerformance` and check the generated `.c` file for `f_licm_*`
declarations. If they appear, Phase 4 is working with the original approach.
If not, the problem is deeper (e.g., the envelope expressions aren't
`ExpressionAssignment` instances, or variant names aren't being propagated
correctly to the frame counter references).

**Step 4: If `isLoopInvariant` also doesn't work,** investigate:
- What statement types the envelope accumulate lines are (might not be
  `ExpressionAssignment`)
- Whether the frame counter variable name is in `variantNames`
- What the expression tree structure looks like for an envelope expression
  (trace the types of each node from root to leaf)

**Do NOT attempt to re-optimize Phase 4 to O(N) until it is working
correctly with `isLoopInvariant`.** The O(N) optimization introduced a
correctness bug. Get it working first, optimize later.

---

## Remaining Optimization Opportunities

### 5. LICM Is Hoisting `f_assignment` Declarations — WORKING (2026-03-02)

> **RESOLVED.** All 50 `f_assignment` declarations are now hoisted before the
> inner loop (lines 1985-2034, before loop at line 2168). This eliminates
> 100 `pow()` calls per iteration × 4096 iterations = ~410,000 `pow()` calls
> per buffer tick.

**What should be hoisted but isn't:**

There are **24 `f_assignment` declarations** inside the loop body that compute
genome-derived envelope offsets. Example (from actual generated output):

```c
// This is INSIDE the loop — recomputed 4096 times per tick
double f_assignment_185220_0 = (pow((- pow(genome[offset + 5], 3.0)) + 1.0, -1.0) + -1.0) * -60.0;
```

This expression reads `genome[offset + 5]` — a kernel argument at a constant
offset. It does NOT reference the loop index variable `_192190_i` or any
variable that changes inside the loop. It is **trivially loop-invariant** and
should appear once before the `for` loop, not inside it.

**Impact:** Each `f_assignment` contains 2 `pow()` calls. That's 48 `pow()`
calls per iteration × 4096 iterations = **~196,608 unnecessary `pow()` calls
per tick** — all computing the exact same values every time.

**Why LICM isn't catching them:** The `hoistLoopInvariantStatements` method
in `Repeated.java` only iterates over `scope.getChildren()` and their
`getStatements()`. These `f_assignment` declarations likely live in a
**grandchild scope** (or deeper) and are never examined. The LICM
implementation does not recurse into nested scopes.

**What needs to happen:** Study where the `f_assignment` declarations live in
the scope tree. The LICM code at `Repeated.java` lines 186-209 iterates
`scope.getChildren()` — check whether the envelope computation scopes are
direct children or nested deeper. If they're in grandchildren, the LICM must
recurse, or the scope flattening must bring them up before LICM runs.

To verify: After the fix, run the reproduction test and confirm that all 24
`double f_assignment_*` declarations appear BEFORE the `for (int ... = 0;`
line in the generated `.c` file, not after it.

---

### 6. Inline the Envelope Sub-expressions That Depend on `f_assignment`

Even after hoisting `f_assignment` out of the loop, each envelope
accumulate line still contains **redundant sub-expressions** that only
depend on genome parameters. For example, every one of the 12 accumulate
lines contains:

```c
pow((- pow(genome[offset + 1], 3.0)) + 1.0, -1.0) + -1.0) * 60.0
pow((- genome[offset + 2]) + 1.0, -1.0) + -1.0) * 10.0
pow((- genome[offset + 3]) + 1.0, -1.0) + -1.0) * 10.0
```

These are genome-only sub-expressions embedded inside larger expressions that
DO depend on the loop-variant frame counter. They should be extracted into
their own `f_assignment` declarations and hoisted. This is the same LICM
principle applied at sub-expression granularity.

**Implementation approach:** This requires the existing `ExpressionCache` /
common sub-expression elimination (CSE) pass to extract these sub-expressions
into named declarations BEFORE LICM runs. Currently `Scope.simplify()` has a
limit of 12 replacements per scope (`ScopeSettings.maxReplacements`). With
12 envelope expressions × 3 genome-only sub-expressions each = 36 potential
extractions, the limit may need to be raised, or the CSE pass needs to be
run hierarchically.

**Impact:** Would eliminate an additional ~72 `pow()` calls per iteration
(3 sub-expressions × 12 channels × 2 `pow()` each). Combined with hoisting
`f_assignment`, this would remove **~300+ `pow()` calls per iteration**.

---

### 7. Copy-Zero Pattern Still Present for Non-SummationCell Receptors

The `CachedStateCell` SummationCell optimization (#1) is working for some
cells but the copy-zero pattern still appears 12 times in the generated code:

```c
dest[offset] = source[offset];     // copy
source[offset] = 0.0;              // zero
```

Zero-assigns dropped from 78 to 15 (baseline to current), but 12 of those
remaining 15 zero-assigns are paired with a preceding copy. These cells
likely have receptors that are not `SummationCell` instances, or the
`instanceof` check doesn't match due to the cell graph wiring.

**What needs to happen:** Trace which `CachedStateCell` instances are NOT
taking the optimized path. Check whether their receptors are subclasses of
`SummationCell` that don't match `instanceof`, or whether the receptor is
wrapped in another cell type. The generated code metadata comments (enabled
with `AR_HARDWARE_METADATA=enabled`) identify which Computation produced
each line — use those to trace back to the Java cell graph.

---

### 8. Redundant WaveCellPush Buffer Reads

The loop begins with 24 WaveCellPush operations that read audio samples:

```c
state[offset + 18] = ((cursor >= 0) & (cursor < state[offset + 8]))
    ? (buffer[((int) (floor(cursor) + state[offset + 6])) + bufferOffset] * state[offset + 4])
    : 0;
dest[offset] = state[offset + 18];
```

Each push does a bounds check, a `floor()` call, an integer cast, a
multiply, and a conditional assignment — then immediately copies the result
to a destination buffer. The copy `dest[offset] = state[offset + 18]` is
the CachedStateCell pattern again: the WaveCell writes to `state[+18]`, then
CachedStateCell copies it to `dest`.

**Opportunity:** If the downstream consumer could read directly from
`state[offset + 18]`, the intermediate copy is unnecessary. This is the same
principle as optimization #1 but at the WaveCell level.

---

## Previously Completed Optimizations

### #1 - Copy-Zero-Accumulate: PARTIALLY WORKING

`CachedStateCell.tick()` detects `SummationCell` receptors and bypasses the
intermediate copy. Zero-assigns dropped from 78 to 15. However, 12
copy-zero pairs remain (see #7 above).

### #3 - Frame Reset if-else Chain: COMPLETE

`TimeCellReset` now generates a compact `for` loop instead of 30+ if-else
branches. Verified in generated output:
```c
for (int _reset_j = 0; _reset_j < 32; _reset_j++) {
    if (reset[_reset_j + offset] > 0.0 && time[offset + 1] == reset[_reset_j + offset]) {
        time[offset] = 0.0;
        break;
    }
}
```

### #4 - Argument Count Reduction: REGRESSED

Arguments were previously reduced from ~300+ to 49 through buffer
consolidation. Current generated code shows **389 arguments** (argArr
indices go up to 388). This regression may be related to the LICM changes
expanding the scope tree. Investigate whether the argument count increase
is caused by LICM-related scope restructuring.

### #9 - CSE Limit Revert: DONE

`ScopeSettings.maxReplacements` reverted from 48 back to 12. File size
fixed (59 MB → 3.4 MB). However, the loop body line count did NOT improve
(still 2,784 lines), which means the loop body expansion has a different
root cause than the CSE limit.

---

## Prioritization

| # | Opportunity | Estimated Impact | Complexity | Status |
|---|-------------|-----------------|------------|--------|
| 9 | Revert CSE limit increase | **Critical** | Low | **DONE** — reverted to 12, file size fixed |
| 5 | Fix LICM to hoist `f_assignment` declarations | **Very High** | Medium | **DONE** — all 50 hoisted before inner loop (2026-03-02) |
| 10 | Debug LICM on real AudioScene scope tree | **Critical** | Medium | **PARTIALLY DONE** — Phases 1-3 working, Phase 4 broken (see root cause) |
| 6 | Extract + hoist genome sub-expressions from envelope lines | High | Medium | **NOT WORKING** — Phase 4 extracts 0 sub-expressions (see root cause) |
| 7 | Eliminate remaining copy-zero pairs | Low–Medium | Low | Partially done — 134 `= 0.0;` resets remain in loop |
| 8 | Eliminate redundant WaveCellPush copies | Low | Medium | Not started |

### #10 — Debug LICM on Real AudioScene Scope Tree — PARTIALLY RESOLVED (2026-03-02)

**PARTIALLY RESOLVED.** LICM Phases 1-3 are working: all 50 `f_assignment`
declarations are hoisted. However, Phase 4 sub-expression extraction is
**NOT working** — it extracts 0 sub-expressions on the real AudioScene scope
tree despite passing unit tests. The root cause is documented above in
"Root Cause Analysis: Why Phase 4 Extracts 0 Sub-Expressions". The fix plan
is also documented there. For historical context, the prior issues were:

1. **Add diagnostic logging** to `hoistLoopInvariantStatements()` that prints:
   - The loop variable name it's looking for
   - How many children the Repeated scope has
   - For each child, whether it was classified as variant or invariant and why
   - For f_assignment declarations specifically, what variance markers triggered

2. **Run the AudioScene test** with this logging enabled and capture the output.

3. **Document findings in the decision journal** — specifically:
   - What is the loop variable in the real case? (It's `global_id`, a `long long`)
   - How deep is the scope tree? How many levels between Repeated and f_assignments?
   - Which specific invariance check is failing and for what reason?

4. **Fix the root cause** — which is almost certainly one of:
   - The loop variable name/type doesn't match what the variance analysis expects
   - Array variable references like `_12452_v4312[offset + 5]` are being
     classified as variant because the analysis sees the array name as a
     "variable" rather than recognizing it's a function parameter
   - Intermediate scope nesting prevents the LICM recursion from reaching
     the f_assignment declarations

**Do NOT write more unit tests until the real-case failure is diagnosed.**
The problem is not test coverage — the problem is that the LICM algorithm
doesn't handle the real scope tree structure. No amount of simple-case
testing will reveal what's going wrong in the 389-argument, 2784-line
real case.

**Recommendation:** The correct debugging approach is:
1. Set a breakpoint or add logging in `Repeated.simplify()` at the LICM
   call site
2. Run `effectsEnabledPerformance` (via MCP test runner)
3. Find the specific `Repeated` scope that wraps the `for (long long global_id ...)` loop
4. Trace why each f_assignment is being classified as variant
5. Fix the variance classification
6. Re-run and verify f_assignments move before the loop in the generated `.c`

**Do not increase `maxReplacements` without first verifying the generated
code.** The CSE pass extracts sub-expressions in an order that doesn't
consider loop invariance. Raising the limit causes it to extract more
loop-variant sub-expressions, which inflates the code without enabling LICM
to hoist more.

### Verification Protocol

For every optimization, the acceptance criterion is a change in the
**generated C code**, not just the Java source. Use this workflow:

1. Run the reproduction test (see "How to Reproduce" above)
2. Find the largest generated `.c` file in `compose/results/`
3. Strip comments: `grep -v '^\s*//' <file> > stripped.c`
4. Count non-comment lines inside the main loop: find the line with
   `for (int _*_i = 0; _*_i < 4096;)` and count lines until the matching
   closing brace. **This count MUST NOT exceed ~300 lines.** The current
   2,783 is a severe regression.
5. Count `pow()` calls inside the loop. Target: fewer than 30 (down from the
   current 126).
6. Check for `double f_assignment_*` declarations — all should appear
   BEFORE the main `for` loop, not inside it.

If the generated C code looks the same before and after your change, **the
optimization is not working** regardless of what the Java source looks like.

> **IMPORTANT FOR IMPLEMENTOR:** Previous optimization attempts produced
> Java-side changes that appeared correct but did not change the generated C
> output (or were disabled, or caused regressions that made things worse).
> The ONLY way to verify these optimizations is to inspect the actual
> generated `.c` file. Do not rely on unit tests alone — unit tests verify
> correctness, not that the optimization is actually taking effect or that
> the code structure hasn't regressed. You must read the generated code.

---

## Testing Requirements

> **CRITICAL: The previous round of optimization work was under-tested.** A
> handful of purpose-built tests passed while the optimizations either didn't
> work or broke things that weren't covered. This section defines what
> adequate testing looks like.

### Principle: Optimizations Must Be Unconditionally Enabled

**All optimizations (LICM, CachedStateCell SummationCell bypass, TimeCellReset
loop generation) MUST be enabled by default with NO environment-variable
opt-out.** This is not optional.

The reason is simple: when an optimization is enabled unconditionally, **every
existing test in the entire codebase becomes a test of that optimization**.
The AR test suite already exercises a wide range of scope structures, cell
graph topologies, nested loops, and expression patterns. An optimization that
passes 3 purpose-built tests but breaks something in an unrelated module is
not ready. The only way to surface these breakages is to have the
optimization active during all test runs.

Concretely:
- `Repeated.enableLoopInvariantHoisting` must default to `true` with no
  `AR_LOOP_INVARIANT_HOISTING` escape hatch. If the optimization is not
  correct enough to be always-on, it is not correct enough to ship. Fix the
  invariance analysis rather than adding a toggle.
- The `CachedStateCell` SummationCell bypass must be the only code path — no
  `instanceof` fallback that silently reverts to the old behavior for some
  cells. If there are cell graph topologies where bypassing the intermediate
  copy produces incorrect results, those must be understood and handled
  explicitly, not swept under a fallback.
- `TimeCellReset` loop generation is already unconditional — this is correct.

**Do not introduce feature flags, environment variables, or toggles.** The
previous `AR_LOOP_INVARIANT_HOISTING=false` default meant LICM was dead code
that was never exercised by any test run. Toggles encourage shipping broken
optimizations behind a flag rather than fixing them.

### Dedicated Optimization Tests

In addition to relying on the existing test suite (via unconditional
enablement), write targeted tests that specifically verify the optimizations
are producing the expected generated code patterns. These should cover a
range of scope structures found throughout the codebase:

**LICM-specific test cases:**
- Simple loop with one invariant declaration — verify it's hoisted
- Loop with a declaration that depends on another declaration (transitive
  dependency chain) — verify the dependency is correctly classified as
  variant or invariant
- Loop with nested inner loops — verify that inner-loop index references
  are NOT incorrectly hoisted
- Loop where a declaration reads from a kernel argument at a constant offset
  (the `f_assignment` pattern) — this is the real-world case that must work
- Loop where a declaration reads from a variable that is assigned elsewhere
  in the loop body — verify it is NOT hoisted
- Loop with `StaticReference` nodes that name-match loop-body declarations
  — verify the variance propagation handles these
- Loop with `timeSeriesValueAt`-style helper functions that contain their
  own loop indices — verify these are NOT hoisted
- Loop with mixed invariant and variant declarations interleaved — verify
  only the correct ones are hoisted and ordering is preserved

**CachedStateCell bypass test cases:**
- `CachedStateCell` → `SummationCell` (direct) — verify 2-operation path
- `CachedStateCell` → wrapped/delegated cell that internally uses
  `SummationCell` — verify correct behavior (this may be why 12 pairs remain)
- `CachedStateCell` → non-SummationCell receptor — verify standard path
  still works
- Multiple `CachedStateCell` instances pushing to the same `SummationCell`
  — verify accumulated values are correct
- Cell graph where `outValue` is read by something other than the receptor
  — verify correctness (this is a known risk with the bypass)

**Scope structure coverage:** The existing codebase uses `Repeated` scopes
in many contexts beyond the AudioScene loop — ML training loops, kernel
computations, batch processing. Search the codebase for `Repeated` usage
patterns and ensure the test cases cover the structural variations that
actually exist:
```
Grep pattern:"new Repeated" or pattern:"Repeated<" across the codebase
```
Any pattern found in production code that is NOT covered by a test is a gap.

### Full Test Suite Validation

Before declaring any optimization complete, run the **full project build
with tests**:

```bash
mvn clean install -pl code,collect,graph,hardware,audio,compose
```

This is not negotiable. The previous round ran only a few targeted tests and
missed breakages. The optimizations affect `Scope`, `Repeated`, and
`CachedStateCell` — foundational classes used across every module. A change
to `Repeated.simplify()` can affect ML kernel compilation, not just audio.

If any test fails with the optimization enabled, the optimization has a bug.
Do not disable the optimization — fix it.

---

## Running AudioSceneBufferConsolidationTest for Verification

The agent previously reported being unable to run `effectsEnabledPerformance`
in its test environment, claiming it "depends on infrastructure not available."
Investigation shows that **the test already has full synthetic sample fallback
support** and should work without real audio files:

- `AudioSceneTestBase.resolveSample()` checks if a real `.wav` file exists
  at `../../Samples/<dir>/<filename>`
- If it doesn't (the CI/container case), it falls through to
  `AudioTestFeatures.TestWavFileHolder.getOrCreate()`, which generates
  synthetic WAV files (sine waves for melodic channels, noise bursts for
  percussive channels)
- Generated files are cached in the JVM temp directory

The MCP test runner (`mcp__ar-test-runner__start_test_run`) handles
environment setup automatically. To run the verification test:

```
mcp__ar-test-runner__start_test_run
  module: "compose"
  test_methods: [{"class": "AudioSceneBufferConsolidationTest", "method": "effectsEnabledPerformance"}]
  jvm_args: ["-DAR_INSTRUCTION_SET_MONITORING=always"]
  timeout_minutes: 15
```

Generated `.c` files will be in `compose/results/`. The largest file is the
Loop computation. If this test cannot be run, **document in the decision
journal exactly what error occurs and why** — do not simply skip
verification and claim the optimization is working.

---

## Related Documents

- [../journals/AUDIO_SCENE_LOOP.md](../journals/AUDIO_SCENE_LOOP.md) —
  **Decision journal** (mandatory — document your reasoning here)
- [REALTIME_AUDIO_SCENE.md](REALTIME_AUDIO_SCENE.md) — Overall real-time
  rendering plan, architecture, and argument count reduction efforts
- `CachedStateCell.java` — Source of the copy-zero-accumulate pattern
- `TimeCellReset.java` — Source of the frame reset loop (now optimized)
- `Scope.simplify()` / `ExpressionCache` — Current CSE optimization pipeline
- `Repeated.java` — Loop scope code generation and current LICM implementation
- `ScopeSettings.maxReplacements` — CSE extraction limit (reverted to 12 — do NOT raise without verifying generated code)
