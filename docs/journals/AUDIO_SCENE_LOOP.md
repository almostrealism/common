# AudioScene Loop Optimization — Decision Journal

This file is a running log of decisions, observations, and reasoning made
during the implementation of optimizations described in
[../plans/AUDIO_SCENE_LOOP.md](../plans/AUDIO_SCENE_LOOP.md).

**Purpose:** Commit messages are too brief to capture *why* a decision was
made, what alternatives were considered, and what tradeoffs were accepted.
This journal fills that gap. When a reviewer comes back to assess progress,
this file should make it possible to understand the reasoning behind each
change without re-deriving it from scratch.

## How to Use This File

**Before starting work on any optimization item**, add a dated entry below
with:
- Which optimization you're working on (reference the # from the plan)
- What you understand the problem to be
- What approach you're considering and why

**After making a change**, update the entry with:
- What you actually did and why (especially if it differs from the plan)
- What you observed in the generated C code (line counts, pattern changes)
- Any unexpected behavior or side effects
- What you decided NOT to do and why

**When you hit an obstacle**, document:
- What you tried
- Why it didn't work
- What you're going to try instead
- If you're deferring something, why

**When you can't run a verification step** (e.g., a test won't run in your
environment), document:
- What you tried to run and what error you got
- What you did instead to verify your change
- What remains unverified and what risk that carries

**Format:** Use reverse-chronological order (newest entries first). Use the
template below.

---

## Entry Template

```
### YYYY-MM-DD — [Brief description]

**Goal:** [Which optimization # and what you're trying to achieve]

**Context:** [What you understood about the current state before starting]

**Approach:** [What you decided to do and why]

**Alternatives considered:** [What else you could have done and why you didn't]

**Observations:** [What you saw in the generated code, test results, etc.]

**Outcome:** [What happened — did it work? Any regressions?]

**Open questions:** [Anything unresolved]
```

---

## Entries

> **Attribution convention:** Entries written by the developer agent have no
> special label. Entries written during the independent review are marked with
> *(review)* in the title and include an **Author** line.

### 2026-03-03 — Algebraic strength reduction for pow(), Goal 2 root cause, timing verification

**Goal:** Goals 1, 2, and 3 — Reduce inner-loop pow() count, investigate argument
count regression, achieve real-time performance

**Context:** Prior sessions implemented LICM Phases 1-4, CachedStateCell optimization,
and TimeCellReset optimization. All 26 existing optimization tests pass. The generated
C code baseline (run e82e15a2) showed 150 `pow()` occurrences in the inner loop (126
lines with pow), 389 arguments, avg buffer time 443.71ms (target 92.88ms, ratio 0.21x).

**Approach for Goal 1:** Implemented algebraic strength reduction in `Exponent.create()`
to replace `pow()` calls with equivalent multiply/divide operations for small integer
exponents. This is a standard compiler optimization that avoids the expensive `pow()`
library call in the generated C code.

Reductions implemented:
- `pow(x, 2.0)` → `x * x` (via `Product.of(base, base)`)
- `pow(x, 3.0)` → `x * x * x` (via `Product.of(Product.of(base, base), base)`)
- `pow(x, -1.0)` → `1.0 / x` (via `Quotient.of(1.0, base)`)
- `pow(x, -2.0)` → `1.0 / (x * x)` (via `Quotient.of(1.0, Product.of(base, base))`)
- `pow(x, -3.0)` → `1.0 / (x * x * x)` (via `Quotient.of(1.0, Product.of(Product.of(base, base), base))`)

The reduction is placed after existing constant-folding (exp=0→1, exp=1→base,
both-constants→evaluate) and before the fallback `new Exponent(base, exponent)`.

**Classification of the baseline 150 pow() calls:**
- 50 with exponent `3.0` → now strength-reduced to multiplications
- 24 with exponent `2.0` → now strength-reduced to multiplications
- 76 with variable exponents (e.g., `pow(expr, f_licm_4)` where `f_licm_4` is a
  genome-derived expression like `(pow((- genome[offset]) + 1.0, -1.0) + -1.0) * 10.0`)
  → these CANNOT be strength-reduced because the exponent depends on user-controlled
  genome parameters at runtime

**Approach for Goal 2:** Investigated the 49→389 argument count "regression" by reading
`docs/plans/REALTIME_AUDIO_SCENE.md`. The 49 was measured with effects disabled
(`effectsEnabledPerformance` was not the test used for that metric). With the full
effects pipeline enabled (6 channels, filters, envelopes), 389 arguments is expected.
No code change on this branch affects argument collection — the branch only modified
`Repeated.java`, `CachedStateCell.java`, `TimeCellReset.java`, `SystemUtils.java`,
`ScopeSettings.java` (javadoc only), and now `Exponent.java`.

**Changes made:**
1. `code/src/main/java/io/almostrealism/expression/Exponent.java`: Added strength
   reduction cases in `create()` for exponents 2.0, 3.0, -1.0, -2.0, -3.0. Added
   javadoc documenting the reductions.
2. `utils/src/test/java/org/almostrealism/algebra/ExponentStrengthReductionTest.java`:
   Created 9 unit tests covering all reduction cases, non-reducible preservation,
   constant folding preservation, numerical correctness, and nested pow patterns.

**Observations (generated C code, run 1e366c2c):**
- Inner-loop `pow()` calls: **76** (down from 150 baseline, -49%)
- ALL remaining 76 have variable exponents (`f_licm_*`) — zero constant-exponent pow() remains
- `f_licm` declarations: **140** (up from 133 — more sub-expressions extracted because
  strength reduction creates more Product/Quotient nodes that Phase 4 can decompose)
- `f_assignment` declarations: **36** (down from 50)
- All hoisted declarations correctly appear before the inner loop
- Total file: 4835 lines (down from 4842)
- 389 arguments (unchanged, as expected)

**Timing results comparison:**

| Metric | Baseline (e82e15a2) | With SR (1e366c2c) |
|--------|---------------------|---------------------|
| Avg buffer time | 443.71 ms | 433.24 ms |
| Min buffer time | — | 386.49 ms |
| Max buffer time | — | 894.76 ms |
| Real-time ratio | 0.21x | 0.21x |
| Meets real-time | NO | NO |

The ~2.4% improvement from strength reduction is modest because the 76 remaining
variable-exponent `pow()` calls dominate the inner loop's runtime. These cannot be
eliminated without changing the DSP algorithm itself (the envelope expressions use
genome parameters as exponents to create nonlinear transfer functions).

**Testing:**
- All 9 ExponentStrengthReductionTest tests pass
- All 26 existing LICM + CachedStateCell tests pass
- `effectsEnabledPerformance` test PASSES (run 1e366c2c, 540.3s)
- Full build (`mvn clean install -DskipTests`) succeeds across all 35 modules

**Alternatives considered:**
- Strength reduction for `pow(x, 4.0)` via `x*x*x*x`: Not implemented. The plan
  limits reduction to exponents where the multiply chain is shorter than `pow()`.
  For exp=4, `x*x*x*x` is 3 multiplies vs one `pow()` call — marginal benefit and
  no occurrences exist in current generated code.
- Approximation for variable-exponent pow(): Could use lookup tables or polynomial
  approximations. Rejected — this would change numerical behavior of the DSP pipeline
  and requires domain-specific accuracy analysis.
- Moving strength reduction to `LanguageOperations.pow()`: Rejected — the reduction
  must happen at the expression tree level (not during code generation) so that
  Phase 4 LICM can further decompose the resulting Product/Quotient nodes.

**Outcome:**
- **Goal 1:** PARTIAL — reduced from 150 to 76 pow() calls (-49%). Target was <30.
  The remaining 76 have variable genome-derived exponents and cannot be strength-reduced
  at compile time. This is a structural limitation of the DSP algorithm.
- **Goal 2:** COMPLETE — root cause identified. The 49→389 is effects-disabled vs
  effects-enabled, not a code regression. No fix needed.
- **Goal 3:** NOT MET — still 0.21x real-time (433ms vs 93ms target). Achieving
  real-time would require either (a) eliminating the 76 variable-exponent pow() calls
  by changing the envelope algorithm, or (b) hardware acceleration (GPU/SIMD).

**Open questions:**
- Could the envelope expressions be reformulated to avoid variable exponents? This
  would require understanding the musical semantics of the genome-parameterized
  nonlinear transfer functions.
- Would OpenCL/Metal backends handle pow() more efficiently through GPU parallelism?

---

### 2026-03-02 — Completion assessment, test gaps filled, #7/#8 root cause analysis

**Goal:** Assess branch completeness against all plan goals; fill test gaps; investigate #7 and #8

**Context:** All core optimizations (#1, #3, #5, #6, #9, #10) were implemented by prior
sessions. LICM is unconditionally enabled (`enableLoopInvariantHoisting = true`, no env var).
Phase 4 sub-expression extraction is working (133 `f_licm_*` extractions). CSE limit is
reverted to 12. TimeCellReset generates compact loop. CachedStateCell has SummationCell bypass.

**Work done:**

1. **Added 2 missing CachedStateCell tests** (plan requirement):
   - `wrappedSummationCellUsesStandardPath`: Verifies that a `ReceptorCell` wrapping a
     `SummationCell` triggers the standard (non-optimized) path, because `instanceof
     SummationCell` does not see through the wrapper. Documents the behavior gap.
   - `outValueAvailableViaNextAfterTick`: Verifies that `outValue` is populated during
     the standard-path tick and is accessible via `next()` for downstream readers.

2. **Root cause analysis for #7 (remaining 12 copy-zero pairs):**
   - Traced the cell hierarchy: `Receptor.to()` creates composite lambda receptors that
     are NOT `instanceof SummationCell`. `ReceptorCell` wraps a `SummationCell` but hides
     it from the `instanceof` check.
   - In the AudioScene pipeline, `CellList.branch()` uses `Receptor.to(d)` for multi-cast
     routing, and `MixdownManager` uses `Receptor.to()` for effects routing. These patterns
     create composite receptors that bypass the optimization.
   - **Conclusion:** The 12 remaining copy-zero pairs are from cells wired through composite
     receptors or wrapper cells. Fixing this would require either (a) an interface method
     on `Receptor` to indicate accumulation support, or (b) unwrapping composite receptors
     to detect single-SummationCell targets. Both approaches carry correctness risk and the
     impact is low (simple memory ops, not expensive `pow()` calls).

3. **Assessment for #8 (WaveCellPush copies):**
   - WaveCellPush writes to `state[offset+18]`, then CachedStateCell copies to `dest[offset]`.
     Eliminating the copy requires the downstream consumer to read directly from WaveCell's
     internal state buffer, which is an architectural change to the cell graph wiring.
   - **Conclusion:** This is a low-priority optimization requiring significant architectural
     changes that cannot be safely made without extensive integration testing.

4. **Verification:**
   - All 26 optimization tests pass (20 LICM + 6 CachedStateCell)
   - Full build (`mvn clean install -DskipTests`) succeeds across all 35 modules
   - AudioScene `effectsEnabledPerformance` test PASSED (run d761f39a, 541.8s / ~9min)
   - Generated C code verified: 50 f_assignments hoisted, 133 f_licm extractions, 4842 lines

**Alternatives considered for #7:**
- Could modify `CachedStateCell.tick()` to unwrap `ReceptorCell` before the `instanceof`
  check, but this couples CachedStateCell to the ReceptorCell implementation
- Could add an `isAccumulator()` method to the `Receptor` interface, but this changes a
  foundational interface for a low-impact optimization
- Decided not to implement either approach without integration test coverage on the real
  AudioScene pipeline

**Outcome:** All plan goals that are feasible to implement are complete. #7 and #8 have
documented root causes and clear fix paths for future work, but are deferred due to
low impact and high risk without integration-level verification.

**Open questions:** None blocking. Future sessions can use the root cause analysis above
to implement #7 and #8 when integration testing is available.

---

### 2026-03-02 — Phase 4 diagnostic deep-dive: dual-loop structure clarified *(review)*

**Author:** Review agent (independent review of developer agent's work)

**Goal:** #10, #6 — Understand Phase 4 behavior on the real AudioScene

**Context:** The agent's commit `110b1b1d2` reverted Phase 4 from `markVariantNodes()` back to
`isLoopInvariant()`. Diagnostic logging was added to trace Phase 4's behavior. Initial analysis
of the diagnostics reported "Phase 4 extracted: 0 sub-expressions", leading to a premature
conclusion that Phase 4 was not working.

**CORRECTION (from deeper analysis of generated C files):**

The diagnostic "0 extractions" was from the **OUTER** `global_id` loop's Phase 4 pass. The
AudioScene has a **dual Repeated structure**: an outer loop and an inner 4096-iteration loop
(`_32879_i`). Phase 4 runs independently on each loop.

**The inner loop's Phase 4 successfully extracts 133 sub-expressions.** This was confirmed by
analyzing the generated C file (`jni_instruction_set_130.c` from run b03b99a4):

- **133 `f_licm_*` declarations** (Phase 4 extraction — WORKING)
- **50 `f_assignment_*` declarations** (Phase 3 hoisting — WORKING)
- **307 total `f_licm_*` references** throughout the inner loop body
- Inner loop body: lines 2060–4835 (~2,775 lines)
- Inner loop still contains: 126 `pow()`, 62 `sin()`
- Total file: 4,842 lines, 3.2MB

The outer loop's Phase 4 correctly finds nothing additional to extract — the inner loop's
Phase 4 has already pulled out the invariant sub-expressions.

**460K-line "regression" debunked:** Run 63a240d0 used `-DAR_HARDWARE_METADATA=enabled`, which
injects operation tree metadata as `//` comment lines. The file grew from 4,842 to 459,938 lines
but 455,096 of those are comments. The actual code is identical to the baseline. This was NOT a
CSE blowup — just metadata comments.

**Outcome:** Phase 4 IS working on the real AudioScene. The `isLoopInvariant()` revert was
correct and effective. The remaining optimization opportunity is reducing the 126 `pow()` and
62 `sin()` calls still inside the inner loop body.

---

### 2026-03-02 — Fix Phase 4 sub-expression extraction by reverting to isLoopInvariant() *(review)*

**Author:** Review agent (fix applied during independent review)

**Goal:** #10, #6 — Fix Phase 4 sub-expression extraction that extracts 0 sub-expressions

**Context:** The independent review (see entry below) identified that commit `054552e4c`
broke Phase 4 by replacing `isLoopInvariant()` with `markVariantNodes()` + `HashSet<Expression<?>>`.
The `markVariantNodes()` approach only checks `getDependencies()` for leaf nodes (no children),
while `isLoopInvariant()` calls `getDependencies()` on the root expression which traverses the
full subtree. Non-leaf expression types that store variable references internally (not as child
expressions) are missed by `markVariantNodes()`, causing Phase 4 to incorrectly classify
variant expressions as invariant and skip them entirely.

**Approach:** Reverted Phase 4 (`extractSubExpressionsFromAssignment` and
`replaceInvariantSubExpressions`) to use `isLoopInvariant()` for invariance checking.
Removed the `markVariantNodes()` method entirely. Kept the Phase 2 `propagateVariance()`
optimization (using `collectAllReferencedNames`) which is correct — it uses string-based
name collection and is only used for declaration-level variance classification.

**Alternatives considered:** Could have tried to fix `markVariantNodes()` to also check
`getDependencies()` for non-leaf nodes, but this would negate the O(N) optimization and
effectively reimplement `isLoopInvariant()`. The plan document explicitly says "Do NOT
attempt to re-optimize Phase 4 to O(N) until it is working correctly."

**Changes made:**
1. `Repeated.java`: Changed `extractSubExpressionsFromAssignment()` to use
   `!isLoopInvariant(expr, variantNames, loopIndices)` instead of `markVariantNodes()`.
2. `Repeated.java`: Changed `replaceInvariantSubExpressions()` to accept
   `variantNames`/`loopIndices` and use `isLoopInvariant(child, ...)` instead of
   `!variantNodes.contains(child)`.
3. `Repeated.java`: Removed `markVariantNodes()` method (no longer referenced).
4. `LoopInvariantHoistingTest.java`: Added 2 new tests:
   - `helperFunctionWithOwnLoopIndexNotHoisted`: Nested helper loop (simulating
     timeSeriesValueAt) with its own loop index — verifies inner declarations are
     not hoisted to outer loop.
   - `staticReferenceVariancePropagation`: Transitive variance via StaticReference
     chain — verifies that declarations depending on variant declarations through
     StaticReference are not hoisted.

**Testing:** All 20 LICM tests pass (18 original + 2 new). All 4 CachedStateCellOptimization
tests pass. Full build (`mvn clean install -DskipTests`) succeeds across all 35 modules.

**Outcome:** Phase 4 is now using `isLoopInvariant()` which correctly handles all expression
types by calling `getDependencies()`, `getIndices()`, and `containsIndex()` on the full
subtree. This should fix the "0 sub-expressions extracted" issue on the real AudioScene scope
tree.

**Open questions:** Need to verify Phase 4 on the real AudioScene scope tree by running
`effectsEnabledPerformance` and checking for `f_licm_*` declarations in the generated C file.

---

### 2026-03-02 — Independent review: Phase 4 root cause identified (markVariantNodes bug) *(review)*

**Author:** Review agent (independent review of developer agent's work)

**Goal:** Verify Phase 4 sub-expression extraction on real AudioScene scope tree

**Context:** The prior agent claimed Phase 4 was "WORKING" with "133 f_licm_*
declarations extracted" and specific metrics (150 pow, 124 sin, 5,042 lines,
423 args). An independent review was conducted to verify these claims.

**Findings at the time:** The review found the files being analyzed contained
0 `f_licm` occurrences. This was because the `markVariantNodes()` bug in commit
`054552e4c` prevented Phase 4 from extracting anything.

**CORRECTION (later in the same session):** After reverting `markVariantNodes()`
to `isLoopInvariant()` (commit `110b1b1d2`), a re-analysis of the generated
C files confirmed Phase 4 IS working: 133 `f_licm_*` declarations extracted
from the inner 4096-iteration loop. The "0 extractions" diagnostic was from
the OUTER loop's Phase 4 pass, not the inner loop's. See corrected journal
entry above.

**Diagnostic evidence (pre-revert, using `markVariantNodes`):**
- `loopIndices` count: 0 (scope index `_33718_i` is a `Variable`, not `Index`)
- `variantNames` correctly contains `_33718_i`
- All 50 declarations are invariant, Phase 3 hoists all 50
- Phase 4 extracted: 0 sub-expressions (due to markVariantNodes bug)

**Root cause:** Commit `054552e4c` changed Phase 4 from `isLoopInvariant()`
(which calls `expr.getDependencies()` on the full subtree) to
`markVariantNodes()` + `HashSet<Expression<?>>` (which only checks
`getDependencies()` for leaf nodes and relies on child recursion). If any
expression type has variable dependencies not captured by `getChildren()`,
`markVariantNodes()` misses them, and the expression is incorrectly classified
as invariant, causing `exprIsVariant` to be `false` and Phase 4 to skip it.

Additionally, the `HashSet<Expression<?>>` approach introduces structural
equality concerns — `Expression.equals()` uses a 16-bit hash and structural
comparison, which may produce false positives between structurally-similar
variant and invariant expressions.

**Note:** The Phase 2 optimization in the same commit (using
`collectAllReferencedNames()` for `propagateVariance()`) is CORRECT because
it uses string-based name collection, matching the original `isLoopInvariant`
semantics. Only Phase 4's `markVariantNodes`/`variantNodes.contains()` is
problematic.

**Fix recommendation:** Revert Phase 4 to use `isLoopInvariant()` while
keeping the Phase 2 optimization. See "Fix Plan for Phase 4" in the plan
document.

**Outcome:** Plan document updated with corrected metrics, root cause analysis,
and fix plan.

---

### 2026-03-02 — Extended Phase 4 to non-declaration assignments with two-tier threshold

**Goal:** #6 — Extract and hoist genome sub-expressions from envelope accumulate lines

**Context:** Phase 4 sub-expression extraction only processed declaration assignments
(`isDeclaration() == true`). The real AudioScene loop body has envelope accumulate lines
that are non-declaration assignments (array element writes like `source[offset] = expr`).
These contain genome-only invariant sub-expressions that could be hoisted. A prior session's
diagnostic run confirmed LICM was working (all 50 declarations hoisted) but the loop body
still had 2,871 lines with 126 pow() calls because the envelope lines were not being processed.

**Approach:** Extended `extractFromScope()` to process both declaration and non-declaration
assignments. Also added processing for `scope.getVariables()` (deprecated path).

**Problem encountered:** With the original `treeDepth >= 1` threshold, the extension extracted
33,249 sub-expressions from the AudioScene scope — every trivially cheap invariant sub-expression
(array accesses, simple arithmetic) was being extracted. This would cause severe code blowup.

**Solution:** Implemented a two-tier threshold in `isSubstantialForExtraction`:
- **Declarations** (`isDeclaration() == true`): `treeDepth >= 1` (preserves original behavior,
  extracts even simple `pow(genome, 3.0)` — these appear in moderate numbers)
- **Non-declarations** (`isDeclaration() == false`): `treeDepth >= 3` (filters out trivially
  cheap sub-expressions while capturing genome-derived computations like
  `pow((- pow(genome[offset+1], 3.0)) + 1.0, -1.0)`)

The `minDepth` parameter is passed from `extractSubExpressionsFromAssignment` through
`replaceInvariantSubExpressions` to `isSubstantialForExtraction`.

**Debugging note:** Initial test failures after implementing the two-tier threshold were
caused by not recompiling the `code` module before running `utils` tests. Running
`mvn install -pl code -DskipTests` first resolved this — the `utils` module picks up
`Repeated.class` from the local Maven repo, not from the source tree.

**Testing:** Added 2 new tests to `LoopInvariantHoistingTest`:
- `nonDeclarationAssignmentSubExpressionExtracted`: Deep invariant sub-expression
  (treeDepth >= 3) in non-declaration assignment is extracted
- `shallowSubExpressionsNotExtractedFromNonDeclaration`: Shallow invariant sub-expression
  (treeDepth = 1) in non-declaration assignment is NOT extracted

**Outcome:** All 18 LICM tests pass (16 original + 2 new). AudioScene test pending.

**Open questions:** Need to verify the extraction count on real AudioScene scope is
reasonable (target ~72 per plan) and not causing code blowup.

---

### 2026-03-01 — Phase 4 sub-expression extraction: treeDepth threshold fix

**Goal:** #6 — Extract and hoist genome sub-expressions from envelope lines

**Context:** Phase 4 sub-expression extraction was implemented in a prior session
and added to `Repeated.hoistLoopInvariantStatements()`. Four new tests were added
to `LoopInvariantHoistingTest`. The deeply-nested `genomeSubExpressionPattern` test
passed but the simpler `invariantSubExpressionExtracted` and `duplicateSubExpressionsDeduped`
tests failed. The `trivialSubExpressionsNotExtracted` negative test passed.

**Approach:** Added diagnostic logging to `extractFromScope()` and
`findMaximalInvariantSubExpressions()` to trace the exact behavior during
simplification. Key finding: `pow(genome_val, 3.0)` (an `Exponent` expression)
has `treeDepth() == 1` (root + two leaf children). The `isSubstantialForExtraction()`
filter was using `treeDepth() > 1`, which incorrectly rejected depth-1 expressions
like binary `pow()` calls.

**Alternatives considered:** Could have lowered the threshold to `> 0` (any
non-leaf) but `>= 1` is equivalent and more explicit. Could have removed the
treeDepth check entirely since `Constant` and `StaticReference` instanceof checks
already filter leaf nodes, but keeping the depth check provides a safety net.

**Observations:**
- `Exponent(StaticReference, DoubleConstant)` has treeDepth=1, childCount=2
- The deeply-nested genome pattern has treeDepth >> 1, which is why it passed
- Product sorts children by depth but this does not affect extraction
- Debug logging confirmed invariance detection works correctly — only the
  `isSubstantialForExtraction` filter was wrong

**Outcome:** Changed `treeDepth() > 1` to `treeDepth() >= 1`. All 4 Phase 4
tests now pass. Full LICM + CachedStateCell test suite (20 tests) passes with
0 failures. Full build (`mvn clean install -DskipTests`) succeeds across all
modules.

**Open questions:** None for this fix.

---

### 2026-03-01 — Reverted CSE limit from 48 to 12

**Goal:** #9 (BLOCKING) — Revert maxReplacements increase that caused 11x code blowup

**Context:** A prior session increased `ScopeSettings.getMaximumReplacements()`
from 12 to 48 to accommodate genome-only sub-expression extraction for LICM.
Regression analysis in the plan showed this caused the AudioScene loop body to
blow up from 251 to 2,783 lines because the CSE pass extracts loop-variant
sub-expressions indiscriminately — it does not prioritize loop-invariant ones.

**Approach:** Reverted `getMaximumReplacements()` to return 12. Updated javadoc
to explain why the revert was necessary and document the regression. The correct
approach to genome sub-expression extraction is via Phase 4 LICM (targeted
extraction) rather than increasing the CSE limit (untargeted extraction).

**Alternatives considered:** Could have modified CSE to prioritize loop-invariant
sub-expressions, but that would require significant changes to `Scope.processReplacements()`
and the `ExpressionCache` infrastructure. Phase 4 LICM is simpler and targeted.

**Outcome:** Reverted successfully. No test regressions.

---

### 2026-03-01 — Prior session work summary

**Goal:** #5, #6, #7 — LICM, sub-expression extraction, copy-zero elimination

**Context:** Multiple prior agent sessions implemented foundational work on this
branch:

1. **LICM (Phases 1-3)** in `Repeated.java`: 3-phase fixed-point algorithm for
   hoisting loop-invariant declarations. Covers base variant name collection,
   variance propagation, and recursive hoisting from descendant scopes.

2. **CachedStateCell SummationCell optimization** in `CachedStateCell.java`:
   Bypasses intermediate outValue copy when downstream receptor is a SummationCell.

3. **TimeCellReset loop generation** in `TimeCellReset.java`: Generates compact
   loop instead of if-else chain for frame counter resets.

4. **Phase 4 sub-expression extraction** in `Repeated.java`: Finds invariant
   sub-trees within variant expressions, extracts them into `f_licm_*` declarations,
   and deduplicates shared sub-expressions.

5. **Test suite**: `LoopInvariantHoistingTest` (16 tests) and
   `CachedStateCellOptimizationTest` (4 tests).

**Outcome:** All implementations were present on the branch. This session fixed
the treeDepth threshold bug in Phase 4 and reverted the CSE limit regression.

*(Add new entries above this line, newest first)*
