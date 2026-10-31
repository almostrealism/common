---
status: proposed
created: 2026-10-31
closed:
tracker: [3c14e3d9-4c2e-498a-abb9-15d8951f9e33]
---

# Give `concat_blocks` a backward pass so gradients reach every concatenated part

- **Tracker task:** `3c14e3d9-4c2e-498a-abb9-15d8951f9e33` — "Give concat_blocks a backward pass so gradients reach every concatenated part"
- **Release:** Common 0.76

## Motivation

The PDSL composition statement `concat_blocks(a, b, ...)` has no backward pass.
`LayerRoutingFeatures.concatBlocks` builds its layer with
`layer("concat_blocks", inputShape, outputShape, Cell.of(...), null, requirements)`,
where the `null` is the back-propagation cell. The forward cell pushes the input
into each part, captures every part's output through a `Cell.CaptureReceptor`, and
pushes `concat(parts)`. Nothing routes a gradient back into the parts or to the
layer's input, so any trainable PDSL model that uses `concat_blocks` silently
fails to train the weights inside its parts. `docs/internals/pdsl-language.md`
records this under "Known limitations": "`concat_blocks` has no backward pass…
Whether any gradient reaches the parts is not verified, and none is expected."

This is already shaping other work. PR #796 (`qa/pdsl-20261030-215315`), which
migrated the SiLU timestep-embedding MLP to
`engine/ml/src/main/resources/pdsl/silu_mlp.pdsl`, left the Fourier feature stage
of the timestep embedding in Java specifically because "the language has no
differentiable concatenation of two parallel sub-blocks (concat_blocks drops the
backward pass), so moving the Fourier stage into PDSL would drop the gradient that
reaches its frequency matrix." Today's `concat_blocks` users also include
`engine/ml/src/main/resources/pdsl/midi/gru_decoder.pdsl`,
`engine/ml/src/test/resources/pdsl/test_cache_primitives.pdsl`, and the reference
examples.

The N-block summation counterpart, `accumBlocks`, is already differentiable
through `AccumulationBlock`, whose backward pass reaches every sub-block. This
task brings `concat_blocks` to the same standard, following that existing pattern
rather than inventing a new mechanism.

## Scope

Concretely, the following will change:

1. **A differentiable block behind `concat_blocks`.** `concatBlocks` will build a
   new block whose backward pass splits the incoming output gradient
   `[sum of the parts' sizes]` into one slice per part, matching each part's
   output shape; sends each slice into that part's backward cell; sums the
   gradients the parts emit toward the shared input; and pushes that sum upstream.
   The forward output is unchanged: the flat concatenation in part order, with
   output shape `[sum of the parts' sizes]`.

2. **`LayerRoutingFeatures.concatBlocks`** will return that block instead of the
   current `layer("concat_blocks", …, null, …)` construction with a `null`
   backward cell.

3. **Documentation.** The "`concat_blocks` has no backward pass" bullet is removed
   from `docs/internals/pdsl-language.md` ("Known limitations"), and the
   `concat_blocks` statement row in the "Statements and their block semantics"
   table gains a description of its backward pass, mirroring the existing
   `accum_blocks` row.

4. **Tests.** A Java gradient test in the style of `AccumulationBlockTests`, and a
   PDSL `.pdsl` training test that shows a `concat_blocks` layer's parts' weights
   change under the optimizer.

Out of scope (named as a follow-up, not done here): actually moving the Fourier
feature stage of the timestep embedding out of Java and into
`silu_mlp.pdsl`. This task only makes that move possible; see Dependencies and
open questions.

The forward contract and all existing forward tests must stay green unchanged:
`CacheReadPrimitiveTest`, `PdslLanguageReferenceExamplesTest`, and the GRU
decoder tests.

## Approach

### Where the new behaviour belongs

`accumBlocks` and `concatBlocks` live side by side in
`domain/graph/src/main/java/org/almostrealism/layers/LayerRoutingFeatures.java`.
`accumBlocks` delegates to `org.almostrealism.model.AccumulationBlock`, a
`Block, Learning, Tracking` that:

- applies each child to the same input and, in the forward pass, captures each
  child's output via `Cell.CaptureReceptor` and combines them (element-wise sum);
- in the backward pass, allocates a single `inputShape`-sized gradient buffer,
  sets an aggregator `Receptor` on every child's backward cell that accumulates
  each emitted input-gradient into that buffer
  (`a(... p(gradient.each()), add(p(gradient.each()), input))`), pushes the
  children's output-gradient into each child's backward chain, then pushes the
  accumulated buffer upstream and clears it. The buffer is accumulated as each
  child emits (not captured and summed afterwards) because a child whose backward
  path ends in a `BranchBlock` clears the gradient it just emitted;
- propagates `setParameterUpdate`, `setInputTracking`, `setComputeRequirements`,
  `setup` and `destroy` to its children.

Concatenation shares almost all of this. The structural differences are exactly
two:

- **Forward combine:** `concat(parts)` reshaped to the output shape, instead of a
  running element-wise sum. (This is already what `concatBlocks` does today.)
- **Backward distribute:** each child receives a *slice* of the output gradient
  (`[offset … offset + part size)` along the flat axis, via a `subset`/slice
  producer from `SlicingFeatures`, reshaped to that part's output shape) instead
  of the full output gradient. The per-child input-gradient accumulation into the
  shared buffer, the clear, and the upstream push are identical to
  `AccumulationBlock`.

Because the two blocks differ only in the forward-combine and backward-distribute
steps, the recommended shape is to **extract the shared parallel-children
machinery into an abstract base** (child validation and storage, the gradient
buffer and aggregator, `setup`/`setParameterUpdate`/`setInputTracking`/
`setComputeRequirements`/`destroy`, and the backward skeleton) and have both
`AccumulationBlock` and a new `ConcatenationBlock` extend it, each supplying only
how outputs are combined forward and how the gradient is distributed backward.
This avoids duplicating the lifecycle and accumulation boilerplate (the project
forbids 3+ structurally similar lines), and it respects "a class IS what its name
says": `AccumulationBlock` stays a summation and does not grow a concat mode. The
alternative — a standalone `ConcatenationBlock` that copies the boilerplate — is
simpler to diff but duplicates ~150 lines and should be rejected on the
duplication rule. The implementer should confirm the base extraction keeps
`AccumulationBlock`'s observable behaviour identical (its tests must pass
unchanged); if the extraction proves to entangle the two in a way that risks that,
fall back to the standalone class and note why.

The new block respects each part's output `TraversalPolicy`/shape: slices are cut
by flat offset and size and reshaped to the part's own output shape, never
assuming a flat part. Every gradient is a Producer composition — slicing is a
`subset`/slice producer, accumulation is an `add` producer — so nothing is
evaluated or sliced on the host (the Fundamental Rule).

### Wiring `concatBlocks` to it

`concatBlocks` will construct and return the new block (applying
`setComputeRequirements` when requirements are supplied), exactly as `accumBlocks`
constructs and returns `AccumulationBlock`. Its declared return type changes from
`CellularLayer` to `Block`; this is compatible with every caller, because the PDSL
path reaches it through `SequentialBlock.concat(List<Block>)`, which does
`add(concatBlocks(getOutputShape(), blocks, requirements))`, and `add` accepts any
`Block` (a `CellularLayer` is only one kind of `Block`). `PdslInterpreter`'s
`interpretConcatBlocks` already calls `block.concat(subBlocks)` and is unaffected.

### Documentation edits

In `docs/internals/pdsl-language.md`:

- Remove the "`concat_blocks` has no backward pass." bullet from "Known
  limitations".
- Rewrite the `concat_blocks(a, b, ...)` row of the "Statements and their block
  semantics" table so it describes the backward pass, mirroring the adjacent
  `accum_blocks` row ("The backward pass gives each part the full output gradient
  and sums their input gradients") — for concat, "the backward pass gives each
  part the slice of the output gradient for its segment and sums the parts' input
  gradients."

## Success criteria

A reviewer can confirm the task is done by:

1. **Java gradient test** (new, in the style of
   `engine/utils/src/test/java/org/almostrealism/layers/test/AccumulationBlockTests.java`,
   and most naturally alongside it): builds `concat_blocks` of at least two
   trainable parts with **different output sizes** (e.g. bias-free dense children
   `y_k = W_k·x` with distinct `NODES_k`), runs forward then backward against a
   known output gradient, and asserts against a hand-computed reference that
     - the forward output is still the flat concatenation in part order with shape
       `[Σ part sizes]`;
     - each part's weight gradient is correct (the outer product of that part's
       gradient slice with the input); and
     - the gradient with respect to the shared input is the sum of the per-part
       contributions (`Σ_k W_kᵀ·g_k`).
   A second backward pass with a different output gradient reports only its own
   gradients, proving the shared buffer is cleared between passes (as
   `AccumulationBlockTests.backwardFeedsEveryChildAndSumsInputGradients` does).

2. **PDSL training test** (new): a `.pdsl` layer that uses `concat_blocks` of
   trainable parts trains under the optimizer — its parts' weights change between
   the start and end of training.

3. **Forward regressions stay green, unchanged:** `CacheReadPrimitiveTest`,
   `PdslLanguageReferenceExamplesTest`, and the GRU decoder tests. The existing
   `AccumulationBlockTests` also stay green unchanged, confirming the base
   extraction did not alter summation behaviour.

4. **Docs:** the "no backward pass" entry is gone from
   `docs/internals/pdsl-language.md`, and the `concat_blocks` row states it is
   differentiable.

5. **Build quality:** `mvn clean install -DskipTests` succeeds and the build
   validator (`checkstyle`, `code_policy`, `test_timeouts`, `duplicate_code`)
   reports no new violations — in particular, no Producer-pattern violation in
   the new backward code and no duplicate-code finding from the new block.

Targeted test commands (bounded, CPU): run `AccumulationBlockTests` and the new
concat gradient test class together as a bounded `test_classes` selection; run the
new PDSL training test and the named forward tests
(`CacheReadPrimitiveTest`, `PdslLanguageReferenceExamplesTest`) as a bounded
selection. No full-module or suite runs.

## Dependencies and open questions

- **No external dependency and no special machine.** The gradient and training
  tests run on the default CPU backend, like `AccumulationBlockTests`; Metal is
  not required, so no `*-workstream.yaml` is written for this branch and the work
  can run on any node.
- **Follow-up (not this task):** with `concat_blocks` differentiable, the Fourier
  feature stage of the timestep embedding (currently kept in Java in
  `DiffusionTransformerFeatures`, feeding `silu_mlp.pdsl`) can move into PDSL. This
  plan records that this becomes possible; the move itself is separate work. The
  implementer should file a tracker task for it (and the header note in
  `silu_mlp.pdsl` explaining why the Fourier stage stays in Java should be revised
  once the move lands, not in this task).
- **Open question — base extraction vs. standalone class.** The recommendation is
  to extract a shared base and refactor `AccumulationBlock` onto it. This touches a
  base-branch production class; the reviewer should decide whether that refactor is
  welcome now, or whether a standalone `ConcatenationBlock` (accepting the
  duplication, or deferring the base extraction to a separate cleanup) is
  preferred. Either way, `AccumulationBlock`'s tests must pass unchanged.
- **Open question — slice API.** The backward slicing should use the existing
  `subset`/slice producers (`SlicingFeatures`) so it respects each part's
  `TraversalPolicy`; the implementer should confirm the exact slice helper that
  yields a `[part size]` producer at a flat offset and reshapes cleanly to the
  part's output shape.

## Estimated complexity

**Medium.** The mechanism to copy is well understood and localized to one block
class plus a thin change to `concatBlocks` and two documentation edits. The
effort is in getting the backward-pass slicing and shared-buffer accumulation
exactly right as Producer compositions, extracting the shared base without
disturbing `AccumulationBlock`'s behaviour, and writing a correct hand-computed
gradient reference for parts of differing sizes.
