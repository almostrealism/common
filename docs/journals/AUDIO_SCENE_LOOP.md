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
