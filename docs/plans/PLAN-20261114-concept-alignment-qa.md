---
status: proposed
created: 2026-11-14
closed:
tracker: []
---

# Concept Alignment QA Round

## 1. Motivation

The platform's value comes partly from the fact that concepts which are mathematically
related are *also* related in the type system. A mesh is a graph; a height field is an
implicit surface; an implicit surface is a scalar function of position with a gradient. When
the implementations share interfaces, a reader of one is pointed at the other, and ideas
travel between domains that nobody would otherwise connect: a graph algorithm applied to a
mesh, a field operator applied to a surface, a renderer that accepts anything with a normal.

Agents (and people) working on a narrow task tend to introduce a new type that solves the
task in isolation. It compiles, it is tested, and it extends nothing but a `Features` mixin.
The code is correct, but the concept is orphaned: it duplicates the *idea* of an existing
abstraction without duplicating any *lines*, so neither `DuplicateCodeDetector`, the
per-job deduplication phase, nor the consolidation round notices.

A concrete case prompted this plan. `ImplicitSurface` (compute/geometry) was introduced
for the per-pixel renderer with its own `value(position)` and `normal(position)` methods,
extending only `VectorFeatures`. The repository already had exactly that concept:
`Differentiable<T>` → `Gradient<T>` (`getNormalAt`) → `Curve<T>` (`getValueAt`), whose own
javadoc uses an implicit sphere as its example. It was realigned to extend
`Curve<PackedCollection>`. Nothing failed before the realignment; something was lost.

This plan proposes an automated QA round, in the same family as `defect-hunt`,
`doc-qa`, `consolidation-qa` and `performance-qa`, that looks for such **concept
alignment gaps** and closes one per round.

## 2. What a concept alignment gap is

A gap exists when two parts of the codebase represent the same (or a specializing)
concept, and the type system does not say so. Shapes it takes:

1. **Orphaned abstraction.** A new interface or class that extends only mixins (`*Features`)
   or `Object`, while an existing interface expresses the same contract under different
   method names (`value` vs `getValueAt`, `normal` vs `getNormalAt`).
2. **Parallel hierarchy.** Two families that mirror each other: a host-valued one
   (`double intersect(Vector, Vector)` on `Volume`, `double estimateDistance(Ray)` on
   `DistanceEstimator`) and a producer-valued one (`Intersectable.intersectAt`). Often the
   older one is a migration that stopped halfway; a leftover comment asking for the older
   type to be replaced with `Intersectable` is a strong signal.
3. **Missing specialization.** A type that is, conceptually, a special case of an existing
   interface (a mesh is a graph; a height field is a 2D grid field; a grid field is a
   discrete field) but does not implement it.
4. **Missing generalization.** Several unrelated types implement the same pair of methods
   with the same meaning and no common interface names that meaning.
5. **Marker without members.** An interface such as `Differentiable<T>` that documents a
   concept but carries no operations, so nothing can be written against it; the right
   members are already declared, ad hoc, on its would-be implementors.

What is **not** a gap:

- Coincidental similarity (same method names, unrelated meaning).
- Types that deliberately sit in different layers because a module cannot see the other
  (check the poms both ways before proposing a link; a link that needs a new dependency is
  out of scope for the round and becomes a tracker task instead).
- A relationship that would force an implementation to satisfy a contract it cannot honor
  (a "is-a" that is really "can-sometimes-be-converted-to").

The test the round applies: **if a reader learned that A is a B, would they think of
something new to do with A, and would every member of B be correct for A?** Both must be yes.

## 3. The round

### 3.1 Shape

A thin caller of `.github/workflows/qa-round.yaml` in
`.github/workflows/master-agent-dispatch.yaml`, like `consolidation-qa`:

| Input | Value |
|---|---|
| `round-name` | `concept-qa` |
| `branch-prefix` | `qa/concept-` |
| `min-interval-days` | 5 (concept work is slower to review than defect fixes) |
| `max-open-prs` | the shared `MAX_OPEN_PRS` literal |
| `ignore-interval` | `github.event.inputs.ignore_interval_concept` |
| `prompt` | path to a local template `tools/ci/prompts/concept-alignment.txt` |
| `protect-test-files` | `"true"` — a realignment must not change what existing tests assert |
| `phase-configs` | `claude/opus`, as for `consolidation-qa`, because the work is judgment-heavy |
| `description` | "Align an orphaned concept with the existing type hierarchy" |

It follows the existing conventions documented in `.github/CLAUDE.md`: its own
`concurrency` group, an `agent` selector value `concept`, an `ignore_interval_concept`
dispatch input, `PR_GRACE_HOURS` on its gate, and an archive step guarded against the
forced reason. It starts as a **pilot** (like `flaky-qa`): it shares the existing open-PR
budget rather than raising `MAX_OPEN_PRS`, until its yield justifies promotion.

### 3.2 The prompt template

`tools/ci/prompts/concept-alignment.txt`, modelled on `consolidation.txt`, instructs the
agent to:

1. **Survey, then choose one candidate on purpose.** Candidate sources, in order:
   - Interfaces and classes added to master since the previous round (`git log` on the
     `qa/concept-` round tags) whose declared supertypes are only `*Features` mixins.
   - `TODO` comments that name another type ("Replace with X", "should be an X").
   - Method-name pairs that recur on unrelated types (`value`/`normal`,
     `getValueAt`/`getNormalAt`, `intersect`/`intersectAt`, `nodes`/`edges`,
     `neighbors`) — the same contract under different names.
   - Marker interfaces with no members (`Differentiable`, `Node`) and their implementors.
   - The concept map (3.3) for families that are known to be disconnected.
2. **Map the existing hierarchy completely** before proposing anything: read every
   interface in the candidate family and every implementor, and write the current tree into
   the PR description.
3. **State the alignment as an "is-a" claim and check it** against the test in section 2,
   and against the poms in both directions (the link must not need a new dependency or a
   new module).
4. **Make the alignment**: add the supertype, rename the duplicated members onto the
   existing contract (narrowing return types covariantly where the new type is more
   specific), update every caller, and keep the change behavior-preserving.
5. **Prove behavior is unchanged** with the tests that exercise the realigned types, and
   add one test that uses the type *through* its new supertype, so the relationship is
   exercised and cannot silently be removed.
6. **Record the relationship** in the concept map and in the javadoc of both types.
7. **File, do not do**, every further alignment it noticed (tracker tasks), so one round is
   one reviewable change.

Prohibited, as in every round: new Maven modules, `pom.xml` edits, weakening tests,
`instanceof` bridges between the two hierarchies (the alignment is in the type, not in a
cast), and adapter/"converter" classes that wrap one concept to look like the other.

### 3.3 The concept map

A durable document, `docs/internals/concept-map.md`, that the round creates on its first
run and every later round updates. For each concept family it lists the interfaces, their
relationships, the modules they live in, and the **known gaps** with the reason each is still
open (for example "`Volume.intersect` returns a host `double`; aligning it with
`Intersectable` requires moving it onto producers first"). It gives `consult` something to
find when an agent is about to introduce a new type, which addresses the problem before a
round has to.

Initial families worth seeding it with: spatial functions (`Differentiable`, `Gradient`,
`Curve`, `ImplicitSurface`, `ShadableCurve`, `Volume`), intersection (`Intersectable`,
`ContinuousField`, `DiscreteField`, `DistanceEstimator`, `ShadableSurface`), graphs
(`Node`, `NodeList`, the graph and mesh types in domain/space), and fields on grids (the
grid field calculus in compute/algebra and `HeightField`).

## 4. A per-branch backstop (optional, second phase)

The round finds gaps after they land. A cheaper complement is a per-job phase in the
FlowTree harness, beside `ObjectOrientedDesignRule` and `DeduplicationRule`: for every
interface or class a branch **adds**, a session is asked "which existing interface
expresses this concept, and why does the new type not extend it?" and must either align the
type or record the reason in its javadoc. This catches the `ImplicitSurface` case at the
branch that introduces it. It belongs in flowtree/runtime and is a separate change from
the round; it should only be built once the round has shown what real gaps look like, so the
phase's prompt is grounded in examples rather than guesses.

## 5. Implementation steps

The workflow and prompt changes are under the CI-file lock, so they land on a `ci/...`
branch:

1. Add `tools/ci/prompts/concept-alignment.txt` (section 3.2).
2. Add the `concept-qa` caller job, the `concept` agent selector value and the
   `ignore_interval_concept` dispatch input to `master-agent-dispatch.yaml`.
3. Extend `tools/tests/test_master_agent_dispatch.py` so the new job is covered by the
   existing checks (intake before gate, `PR_GRACE_HOURS`, forced-archive guard, registered
   only as it submits, pilot does not raise `MAX_OPEN_PRS`), and
   `tools/tests/test_prompt_builders.py` so the template renders.
4. Document the round in `.github/CLAUDE.md` beside the other QA rounds, including its
   pilot status.
5. On an ordinary branch: create `docs/internals/concept-map.md` seeded with the families in
   section 3.3, and add it to `llms.txt` so `consult` indexes it.
6. After several rounds, decide on promotion from pilot and on section 4.

## 6. Open questions

- Should the round be allowed to rename public methods on long-established interfaces, or
  only align *new* types onto old contracts? Proposed: only the latter, with renames of
  established contracts filed as tasks for a person to approve.
- Should the yield measure be "alignments merged" or "alignments merged that a later change
  built on"? The second is the real value, but takes months to observe; the retrospective
  round is the natural place to measure it.
