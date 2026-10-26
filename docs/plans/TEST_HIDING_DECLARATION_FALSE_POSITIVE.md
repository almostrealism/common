# Pattern 2 (NET_ASSERTIONS_REMOVED) — Method-Declaration False Positive

## Background

`tools/ci/agent-protection/detect-test-hiding.sh` enforces invariants on test
file modifications between a feature branch and `origin/master`. Pattern 2,
`NET_ASSERTIONS_REMOVED` (script lines ~467–476), guards against an agent
deleting assertion calls from an existing test file. It works by counting
assertion-shaped lines on the removed (`-`) and added (`+`) side of the file's
diff, netting them per file, and firing when more assertions were removed than
added:

```
DELETED_ASSERTS = count of `-` non-comment lines matching the assert regex
ADDED_ASSERTS   = count of `+` non-comment lines matching the assert regex
fire if DELETED_ASSERTS > 0 AND ADDED_ASSERTS < DELETED_ASSERTS
```

The assert regex is a raw grep over the diff lines:

```
\b(assert|Assert\.|assertEquals|assertTrue|assertFalse|assertNotNull|assertNull|assertThrows|fail\()
```

Unlike the eleven method-scoped detectors (which route additions through
`OWNS_RANGES` / `filter_added_in_existing_methods`), Pattern 2 does no method
scoping at all. This is deliberate and must stay that way — see
[the decision](#decision-already-made-do-not-revisit) below. But because the
regex matches the bare word `assert` anywhere on a line, it also matches a
**method name** that begins with `assert`. A helper such as `assertWithin(...)`
therefore counts as an "assertion" on the line where it is *declared*, not only
on the lines where it is *called*.

## Observed false positive

CI run `35465982025` on branch `feature/sa3-prep`, job `test-integrity-check`,
reported:

```
engine/ml/src/test/java/org/almostrealism/ml/SAMEResamplingParityTest.java:
  NET_ASSERTIONS_REMOVED — Net 1 assertion(s) removed (1 deleted, 0 added)
```

The only removed line that matched the assert regex was the **declaration** of
a shared helper:

```java
protected void assertWithin(String stage, PackedCollection actual, float[] reference, double tolerance) {
```

That helper (together with its `loadShaped`, `loadFlat`, `loadBuffer`,
`firstExisting`, and `report` support) was moved verbatim into
`SAMEResamplingTestBase` so that other parity tests can share it. In the parity
test itself:

- every `@Test` method is byte-identical to `master`;
- all nine `assertWithin(...)` **call sites** remain;
- `validate-agent-commit.sh` passed on the same run.

No assertion behaviour was removed. The net count of `-1` came entirely from
the disappearance of the `protected void assertWithin(...) {` declaration line,
which the regex mistook for a deleted assertion because the method name starts
with `assert`. The moved copy in `SAMEResamplingTestBase` is a brand-new file
(git status `A`), so it never enters Pattern 2's per-file loop — the added
declaration is not counted anywhere to balance the removed one.

## Decision already made (do not revisit)

The owner has fixed the approach; the plan implements it and does not
re-litigate it:

- **Keep counting assertions inside helper methods** of base-branch test
  files. An assertion that lives in a helper the `@Test` bodies call is exactly
  as load-bearing as one written inline.
- **Do NOT scope Pattern 2 to `@Test` method bodies.** Doing so would let an
  agent leave every `@Test` body untouched while stripping the `assertEquals`
  out of the helper those tests call — the very concealment this pattern
  exists to catch.
- **Do NOT net across files.** Pattern 2 stays per-file.

The single change is narrower than any of those: **exclude method
*declaration* lines from what Pattern 2 counts as an assertion, on both the
removed and the added side**, so that a declaration line never contributes to
`DELETED_ASSERTS` or `ADDED_ASSERTS`. A helper's *call sites* and the assertion
calls *inside* its body are untouched and still fully counted.

## The fix

### 1. Exclude declarations from both counts in `detect-test-hiding.sh`

Define one predicate — "this line is an assertion *call*, not a method
*declaration*" — and apply it identically to `DELETED_ASSERTS` and
`ADDED_ASSERTS`. Symmetry matters: excluding declarations on only one side
would let a genuine assertion-for-declaration swap net out incorrectly.

A method-declaration line has a shape a call never has: an assert-like
identifier immediately followed by a **typed parameter list** — parentheses
whose entries are `Type name` pairs — closing with `)` and then `{` or
`throws`, and (for the helpers this rule concerns) usually led by an access
modifier and/or a return type. A call, by contrast, passes *values*:
`assertWithin("stage", actual, reference, 1e-4)`.

Prefer the simpler, well-commented form over an exhaustive grammar. Two
readable options, either acceptable — pick one and comment it:

- **Modifier / return-type lead-in.** Treat as a declaration any line whose
  assert-like token is preceded on the same line by an access modifier
  (`public|private|protected`) or a `void` / type return, and is followed by a
  `(...)` that ends the line with `{` or `throws`. This directly matches the
  `protected void assertWithin(...) {` shape.
- **Typed-parameter-list test.** Treat as a declaration any line where the
  assert-like identifier is immediately followed by a parameter list whose
  entries are `Type name` pairs (two identifiers separated by whitespace),
  rather than a list of value expressions.

Whichever is chosen, the existing comment-line exclusion (removing `//`, `/*`,
and `*` lines to avoid matching the English word "assert" in Javadoc — the
prior Pattern 2 hardening) is **kept as-is**; the declaration exclusion is
layered on top of it. Concretely, the two `grep -cE '\b(assert|...)'` counts
each gain a preceding `grep -vE '<declaration-shape>'` (or an equivalent
inverted match), leaving the netting logic on lines 472–476 unchanged.

Encapsulate the predicate once (a small shell function or a single shared
regex constant) so the removed and added side cannot drift apart — the same
class of symmetry bug the comment-line exclusion already had to get right.

#### Update — predicate broadened after review (PR #531)

The first landed form used the "modifier / return-type lead-in" option above,
requiring the assert-like token to be led by `public|private|protected|void`
and the `(...)` to close with `{`/`throws` on the **same** line. Review flagged
two real shapes it missed:

- **Package-private / typed returns** — `boolean assertWithin(...)`,
  `PackedCollection assertLoaded(...)`, `float[] assertArr(...)` — have neither
  an access modifier nor a `void` return, so they were not recognised.
- **Wrapped multi-line signatures** — `protected void assertWithin(String s,`
  with the parameter list continuing on the next line — end the first line in
  `,`, not `)`+`{`/`throws`, so they were not recognised. That first line is the
  only one carrying the assert-named identifier, hence the only one Pattern 2
  counts.

The predicate now keys on structure instead of an enumerated modifier list:
anchored at the line start (after the diff `+/-` marker and indent), one or more
whitespace-separated identifier tokens (modifiers and the return type — possibly
qualified, generic, or an array) followed by the assert/fail-named identifier
and its opening `(`, with **no `;`** from that `(` to end of line. The trailing
"no `;`" is what separates a declaration (ends in `{`, `throws …`, `)`, or a
wrapped `,`) from a call statement such as `return assertLoaded(x);`, which a
leading token could otherwise make look declaration-shaped; a bare call has no
type token before the identifier and is never matched. Two regression cases
(`moved-wrapped-assert-helper-declaration`, `moved-typed-assert-helper-declaration`)
cover the two shapes.

### 2. Pattern 3 needs no equivalent change — state so explicitly

Pattern 3 (`NET_TEST_METHODS_REMOVED`, lines ~478–485) counts lines matching
`@Test`. A method *declaration* line does not contain `@Test` (the annotation
sits on its own preceding line), and no method is named `@Test`, so Pattern 3
cannot mistake a declaration for a test method the way Pattern 2 mistakes one
for an assertion. **No change to Pattern 3 is required.** The plan records this
explicitly so a future reader does not assume the two patterns share the
defect.

### 3. Regression cases in `test-detect-test-hiding.sh`

Follow the existing fixture style: a `setup_*` function that calls
`scenario_modify base head [filename]`, plus a `run_case` line asserting the
expected exit code. Add three cases:

1. **Helper declaration moved out — no false positive (exit 0).** Base-branch
   test file defines `assertWithin(...)` as a helper and calls it from a
   `@Test`. On the branch, the `assertWithin(...)` declaration is removed from
   this file (moved to another file), while every `@Test` body and every
   `assertWithin(...)` call site is unchanged. Pattern 2 must **not** fire.
   This reproduces the `SAMEResamplingParityTest` scenario. (The destination
   file is a new file and outside Pattern 2's `M`-status loop, matching
   reality; the case does not need to add it.)

2. **Helper body loses an `assertEquals(...)` — still flagged (exit 2).**
   Base-branch test file has a helper whose body contains an
   `assertEquals(...)` call, invoked from an unchanged `@Test`. On the branch,
   the helper *declaration is unchanged* but an `assertEquals(...)` **call**
   inside its body is deleted. Pattern 2 must still fire. This is the property
   the owner insists on preserving: helper-body assertions stay covered.

3. **Assertion call removed from a `@Test` body — still flagged (exit 2).**
   The straightforward true positive: an `assertEquals(...)` call is deleted
   from a `@Test` method body. Pattern 2 must still fire. (Equivalent to the
   existing `real-assertion-removed` case; include a declaration-bearing
   variant so the new predicate is exercised alongside a genuine removal in the
   same file.)

Register all three with `run_case` alongside the existing eight, keeping the
`[false-positive regression]` / `[true positive]` labelling convention.

### 4. Local verification

- Run `tools/ci/agent-protection/test-detect-test-hiding.sh` — all existing
  cases plus the three new ones pass.
- Run the sibling agent-protection self-tests (the `test-*.sh` scripts under
  `tools/ci/agent-protection/`) to confirm nothing else regressed.
- Check out `feature/sa3-prep` at its merge-base with `origin/master` and run
  `detect-test-hiding.sh origin/master`; confirm it reports **no**
  `NET_ASSERTIONS_REMOVED` (and no other violation) for
  `SAMEResamplingParityTest.java`.
- Confirm `validate-agent-commit.sh` is **unchanged** and still passes on that
  branch — the two enforcement points must continue to agree.

### 5. Documentation

- **`detect-test-hiding.sh` header comment.** The Pattern 2 line in the
  file-header pattern list should note that assertion counting excludes method
  *declarations* and covers assertion *calls* — including calls inside helper
  method bodies. Add a short inline comment at the Pattern 2 block itself
  stating why a declaration line is skipped (a method name beginning with
  `assert` is not an assertion) and that the exclusion is applied symmetrically
  to the removed and added side.
- **`CLAUDE.md` "Mechanical Enforcement" paragraph.** The bullet describing
  `detect-test-hiding.sh` (".. net assertion loss .. Java only ..") should
  state that Pattern 2 counts assertion *calls* — in `@Test` bodies and in
  helper methods alike — and does not count method declarations, so moving a
  helper whose name starts with `assert` is not a removed assertion.
- **Any doc that describes Pattern 2** (e.g. `TEST_INTEGRITY_CHECK_IMPROVEMENTS.md`
  if it enumerates the detectors) gets the same clarification: helper bodies
  remain covered; declarations are excluded.

## Constraints

- This branch is named `ci/…`, so edits under `tools/ci/` and `.claude/hooks/`
  are permitted. Change **only** what this goal requires.
- Do **not** touch `.github/workflows/`.
- No `pom.xml` changes.
- Do **not** weaken any other detector. The declaration exclusion applies to
  Pattern 2 alone; Patterns 1 and 3–12 are untouched.
- No author attribution in `commit.txt`.

## Why this is safe

The exclusion removes exactly one class of line from Pattern 2's counting: a
method-declaration line whose name matches the assert regex. A real assertion
is always a *call* — it passes value expressions, not a `Type name` parameter
list, and it is not led by an access modifier or return type. So no genuine
assertion deletion stops being counted:

- Deleting an `assertEquals(...)` **call** from a `@Test` body — still counted.
- Deleting an `assertEquals(...)` **call** from a **helper body** — still
  counted (the owner's non-negotiable requirement).
- Commenting out an assertion — the removed call is still counted and the added
  `// ...` line is dropped by the pre-existing comment exclusion, so it still
  nets as removed.

The only lines newly excluded are declaration lines on *both* sides, which
never represented assertion behaviour in the first place.
