# Proposal: hooks and CI checks against computations that bypass a TraversalOrdering

**Prompted by:** owner review of PR #724 (branch `project/task-20261023-135029`), comment on
`CollectionFeatures.gatherSplitIndex`.

## What happened

The task was to make index data integer-exact on FP32 backends. The branch added a
`SplitIndexTraversalOrdering` and then, instead of making `c(shape, collection, index)` work when
its index argument was read through that ordering, it added a second gather,
`CollectionFeatures.gatherSplitIndex`. That gather built `new SplitIndexTraversalOrdering(args[2])`
**inside the computation's expression**, so it only worked if the caller passed a raw lane
collection, and it checked the declared shape of its argument (`splitIndex instanceof Shape`) to
guess which representation it had been given.

That is a bypass in three ways:

1. **A computation named a concrete ordering.** Computations must be ordering-agnostic. An
   ordering belongs to the collection being read (it travels on the shape and is bound to kernel
   arguments by `TraversalOrdering.bind`), and the computation only calls `getValueAt`.
2. **A parallel path was added beside the broken one.** `gatherSplitIndex` was `c()` with one
   sub-expression swapped. Nothing tested whether `c()` itself respected an ordered argument, so
   the defect in the existing path (if there was one) stayed hidden behind the new method.
3. **It hid a real framework defect.** Once the gather was expressed the right way (a
   `PackedCollection` configured with the ordering, read by ordinary kernels), it failed at once:
   `PackedCollection.reshape` nested a re-traversed ordered view inside the original view, applying
   the ordering twice and rejecting any ordering that addresses more positions than the view has
   logical elements. The parallel method never exercised that path, so the defect went unseen.

Six automated review passes approved the branch. Each compared `gatherSplitIndex` with the
"`valueAtIndex` precedent", found them structurally identical, and treated that as evidence the
code was right. The similarity was the defect.

## Why the existing guards missed it

| Guard | Why it did not fire |
|---|---|
| `block-interface-bypass.py` | Detects re-exposing an adapted concrete type, forked accessor names, and `instanceof <repo class>`. The gather used `instanceof Shape` (an interface, which the downcast detector excludes) and *constructed* the concrete ordering; construction is not one of its three shapes. |
| `duplicate_code` (build validator) | Requires 10+ identical lines. The parallel gather shared about 5 lines with `c()`, with one substitution. |
| `CodePolicyViolationDetector` | Checks the Producer pattern, the GPU memory model and naming. It has no notion of a computation depending on a particular ordering. |
| `warn-method-placement.py` | Asks *where* a new method goes. It never asks whether the method should exist at all, given an operation that already does the job. |
| Tests | No test ran an existing operation (`c()`, element-wise, reshape/traverse, subset) over a collection read through the new ordering. The branch's own tests only exercised the new method. |
| Review prompts | "Is it consistent with existing patterns?" rewarded the copy. No checklist item asks "does this add a second path beside an existing operation?" |

## Proposed controls

Each control is listed with what it catches, how it works, and who can land it. Hooks under
`.claude/hooks/` and the `.claude/settings.json` registration cannot be written by agent sessions.
The policy detectors under `engine/utils` and anything under `tools/ci/` are locked to `ci/...`
branches or to a human. Those items are therefore proposals for a maintainer.

### 1. Hook: `block-ordering-in-computation.py` (PreToolUse, Edit/Write) — blocks

**Catches:** a computation or `*Features` mixin that constructs, casts to, or tests for a concrete
`TraversalOrdering`.

**Rule:** in any file that is not itself a `TraversalOrdering` implementation, `TraversalPolicy`,
`PackedCollection`/`MemoryData`, `CollectionVariable` or a test, block an added line that matches
`new \w+TraversalOrdering\(`, `instanceof \w+TraversalOrdering`, or a cast `\(\w+TraversalOrdering\)`
when the file is a `*Features.java` interface, lives under a `computations/` package, or declares a
class implementing `Computation`/`TraversableExpression`. The set of ordering types should be
resolved from the repo (`git ls-files` + `implements TraversalOrdering`), the same way
`block-interface-bypass.py` resolves interface identity, so a new ordering is covered automatically.

**Block message:** "A computation must respect whatever ordering its arguments carry, never refer
to a particular one. Configure the ordering on the collection (`new PackedCollection(shape, axis,
data, offset, ordering)`) and read it through an ordinary computation. If an existing operation
reads it wrongly, fix that operation."

The `gatherSplitIndex` body (`new SplitIndexTraversalOrdering(args[2])` in a default method of
`CollectionFeatures`) matches on the first pattern.

### 2. Hook: `warn-parallel-operation.py` (PreToolUse, Edit/Write) — warns, escalating to block

**Catches:** a new public operation that is an existing operation with one sub-expression swapped.

**Rule:** when a new `default`/`public` method is added to a `*Features` interface, or a new
computation class is added, tokenize its body and compare it with every existing method in the
same file (and in the same package's `*Features`). If the normalized token sequence matches an
existing method's to within one differing subtree, or within 85% similarity with the same
computation constructor and the same argument arity, emit: "This looks like `<existing>` with one
substitution. If `<existing>` does not handle your case, fix `<existing>` and add a test showing
it handles the case; do not add a parallel operation." Block if the new method's name contains the
existing method's name, or the name of a type it introduces in the same session (`gatherSplitIndex`
names `SplitIndex`), since that is the shape of a variant added for one representation.

This closes the gap `duplicate_code` leaves for short operations.

### 3. CodePolicyViolationDetector rule: `ORDERING_DEPENDENT_COMPUTATION` (CI) — fails the build

The CI counterpart of control 1, for code that reaches the tree without passing through the hook
(non-agent commits, a hook that was bypassed). Implement it as a rule in `CodePolicyViolationDetector`
(`engine/utils`, locked to humans) using the same type resolution. A detector rule is the durable
control. The hook is the fast feedback.

### 4. Ordering conformance suite (CI test) — fails the build

**Catches:** any `TraversalOrdering` that an ordinary operation does not respect. This is the
control that would have exposed the `reshape` defect on the first day, and would have made the
bypass pointless.

**Design:** an `OrderingConformanceTests` class in `engine/utils` (extends `TestSuiteBase`) with a
small registry of representative instances of every `TraversalOrdering` implementation (explicit,
split, mask, repeat, delegated/composed, and a compact one with absent elements), each paired with
its expected logical contents. For each ordering, on CPU and GPU, it checks:

- element-wise (`cp(x).multiply(2.0)`, `add(cp(x), cp(plain))`);
- `reshape`/`traverse` (including the case where the ordering addresses more positions than the
  view has logical elements);
- `subset`, `pad`, `matmul` where shapes allow;
- `c(shape, cp(x), index)` with the ordered collection as the **collection** argument, and
  `c(shape, data, cp(x))` with it as the **index** argument;
- host reads (`toDouble`, `toArray`) agree with the kernel results.

A meta-test scans the compiled classes for implementations of `TraversalOrdering` and fails if
one is not in the registry. A new ordering then cannot land without proving that every existing
operation respects it. `OrderedCollectionInputTests` already covers part of this matrix for two
orderings, so the suite generalizes it rather than starting over.

This one can be implemented by an agent on an ordinary branch (it is a new test). It is filed as a
follow-up task because it widens beyond this branch's deliverable.

### 5. Review-prompt checklist items (harness) — guides the review sessions

Add to the review prompt's "Design and Architecture" section:

- "Does the change add an operation, overload or computation that duplicates an existing one with
  a substitution? If so, the existing operation is presumed broken for the new case: the change
  must fix it and test it, not route around it."
- "Does any computation refer to a specific `TraversalOrdering`, memory layout or concrete
  argument type? Computations respect what their arguments carry. They never select behavior by
  it."
- "'Matches the precedent' is not evidence of correctness when the new code is a copy of the
  precedent."

### 6. Plan-review gate (harness) — catches it before any code exists

The plan for this task explicitly scoped "a gather overload in `CollectionFeatures` that
substitutes the exact reconstruction", and three plan-review passes approved it. The plan-review
prompt should reject a plan whose scope adds a variant of an existing operation for a new data
representation, and should require the plan to state which existing operations must respect the
new representation and how that will be tested.

### 7. CLAUDE.md rule (landed on this branch)

The "Honor the interface" rule in `CLAUDE.md` now states the ordering case explicitly: computations
never refer to a particular `TraversalOrdering`, and an operation that mishandles an ordering is
fixed, not paralleled.

## Tracker tasks

- `87f6b019` — control 4 (ordering conformance suite).
- `b68f70bc` — controls 1–3 (hooks and detector rule; human-only).
- `af42d29a` — controls 5–6 (review and plan-review prompts).

## Order of implementation

1. Control 4 (conformance suite). It finds real defects, and agents can land it.
2. Controls 1 and 3 (hook and detector rule) together, so that local feedback and CI agree.
3. Controls 5 and 6 (prompt changes). These are cheap, and they address the reviewers that
   approved the copy six times.
4. Control 2 (parallel-operation hook). It is the most heuristic, so start it as a warning and
   tune it on the history of rejected PRs before escalating it to a block.
