# Test Integrity Check — Improvement Plan

## Background

`tools/ci/agent-protection/detect-test-hiding.sh` enforces invariants on test
file modifications between a feature branch and `origin/master`. The script
has 12 pattern detectors; 11 of them operate per-method using `OWNS_RANGES` /
`filter_added_in_existing_methods` to avoid attributing additions inside
brand-new methods to existing methods.

Pattern 7 (`CODE_COMMENTED_OUT`) is the outlier. It is purely *file-global
line counting*:

```
DELETED_CODE   = count of `-` lines that don't start with `//`
ADDED_COMMENTED = count of `+` lines that start with `//`
fire if DELETED_CODE > 5 AND ADDED_COMMENTED > DELETED_CODE / 2
```

This produces false positives whenever a branch:

- adds new test methods that carry javadoc / leading-comment docstrings
  (10+ comment lines arrive together as harmless documentation), AND
- contains any non-trivial existing-method refactor with ≥6 line changes
  (e.g. renaming a serialised key, threading a new parameter through call
  sites, replacing one assertion message with another).

Both halves of the rule trip individually on legitimate work; together they
indict any refactor + docstring-bearing test addition in the same PR. See
the dispatch test for `CodingAgentJob` on `feature/pluggable-agents` for a
concrete example: 6 deletions are simple 1-for-1 renames with adjacent
replacements, and 10 added comment lines are docstrings inside *new* test
methods. The detector reports `CODE_COMMENTED_OUT` despite no test having
been commented out and no existing assertion having been weakened.

This document records the full range of brainstormed improvements so we can
return to it if the simpler fixes prove insufficient.

## Phased Improvements

### Phase 1 — implementing now

These three changes are surgical, low-risk, and address the dispatch-test
false positive directly. Each strictly narrows Pattern 7 — no new false
*negatives* are introduced.

**1. Method-scope the comment count.** Reuse the existing
`filter_added_in_existing_methods` machinery so that only `//` lines whose
*owning method existed on the base branch* are counted toward
`ADDED_COMMENTED`. Comment lines inside brand-new test methods are
docstrings for new content and never indicate concealment of existing
behaviour.

**2. Pair deletions with adjacent replacements.** For each `-` line, scan
the same hunk's `+` lines for one with token-set similarity ≥ 0.6. If found,
the deletion is a rename/refactor (`foo()` → `foo(Phase.PRIMARY)`,
`::runner:=` → `::defaultRunner:=`) and is excluded from `DELETED_CODE`.
Only *unpaired* removals — code that disappeared without a near-replacement
— count toward the threshold.

**3. Require comments to look like commented-out code.** A real concealment
pattern adds lines such as `// assertEquals(expected, actual);` whose
stripped form resembles a deleted statement. Filter the added-comment count
to lines that contain at least one code-shaped token (`(`, `;`, `=`,
`assert`, `Assert.`, `return`, `throw`, etc.) and are not pure prose
(multiple words ending with a period). Prose docstrings like
`// Phase-2 deserialization must continue to accept legacy keys.` are no
longer counted.

After all three filters, Pattern 7 fires only when:

- ≥ 6 *unpaired* code deletions exist (not balanced by an adjacent
  replacement), AND
- ≥ half as many *code-shaped* comment lines were added *inside methods
  that existed on the base branch*.

That combination is a much tighter description of the actual concealment
pattern this rule is trying to catch.

### Phase 2 — defer until needed

If Phase 1 leaves us with new false positives, or starts missing real
concealment, return to these:

**4. Gate Pattern 7 on per-method assertion deltas.** For each method that
exists on both base and HEAD, count `assertX(...)` calls before/after and
compute the set of assertion *subjects* (the LHS of `assertEquals`, the
single argument to `assertTrue`, etc.). If every existing method's
assertion count is unchanged-or-higher AND its subject set is a superset of
the base version, the test got stronger — suppress Pattern 7 entirely.
This also unlocks better diagnostics: report which specific method lost
which specific assertion subject, instead of a file-global "comments and
deletions" complaint.

**5. Replace Pattern 7 with a wholesale-removal rule.** The legitimate
concern is "an existing method's body got hollowed out." A targeted
replacement: for each method that exists on both base and HEAD, compute
`(executable_lines_before, executable_lines_after)` and
`(comment_lines_before, comment_lines_after)`. Flag only methods where
executable lines dropped by ≥ 50% AND comment lines increased by ≥ 50%.
This rule is per-method, structural, and immune to file-global
miscorrelation between unrelated regions of the file.

## Verification

Phase 1 is verified by:

1. Running the detector against the dispatch test diff on
   `feature/pluggable-agents` (must report **zero** violations).
2. Running it against a synthetic concealment diff that comments out an
   `assertEquals` inside an existing method (must report a
   `CODE_COMMENTED_OUT` violation).
3. Running it against `master...HEAD` on a branch that adds entirely new
   test files with docstrings (must report zero violations — already
   handled by the file-level `M\s` filter, but worth re-checking).

If Phase 2 work is undertaken, add Phase-1-style verification scaffolding
to `tools/ci/agent-protection/` (a small bats or shell-based fixture
runner) so future changes don't silently re-introduce false positives.
