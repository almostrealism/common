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
`engine/ml/src/test/resources/pdsl/test_cache_primitives.pdsl`, and the
`concat_blocks` example in `docs/internals/pdsl-language.md` exercised by
`PdslLanguageReferenceExamplesTest`. In `gru_decoder.pdsl` the `gru_cell` layer
chains two `concat_blocks` statements, so today no gradient reaches `weight_ih`,
`weight_hh` or the biases through either of them.

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
   `accum_blocks` row. `domain/graph/README.md` ("Block Composition", which lists
   `AccumulationBlock`) gains an entry for the new block.

4. **Tests.** A Java gradient test in the style of `AccumulationBlockTests`, and a
   PDSL test that compiles a `.pdsl` layer using `concat_blocks` for training and
   checks that gradients reach the weights inside its parts.

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
simpler to diff but duplicates most of `AccumulationBlock` (its constructor
validation, lifecycle propagation and backward skeleton) and should be rejected on the
duplication rule. The implementer should confirm the base extraction keeps
`AccumulationBlock`'s observable behaviour identical (its tests must pass
unchanged); if the extraction proves to entangle the two in a way that risks that,
fall back to the standalone class and note why.

The new block respects each part's output `TraversalPolicy`/shape: slices are cut
by flat offset and size and reshaped to the part's own output shape, never
assuming a flat part. The slice is
`SlicingFeatures.subset(TraversalPolicy shape, Producer<?> collection, int... position)`
(in `compute/algebra`) over the flat output gradient, with `shape(partSize)` and the
part's running offset, then `.reshape(part.getOutputShape())`. The PDSL `slice`
built-in, `LayerFeatures.subset(inputShape, subsetShape, pos...)`, is the inverse
already in the codebase (subset forward, zero-padding backward) and is a useful
reference. Every gradient is a Producer composition — slicing is a `subset`
producer, accumulation is an `add` producer — so nothing is evaluated or sliced on
the host (the Fundamental Rule).

Like `AccumulationBlock`, the gradient buffer is allocated only when
`getBackward()` is first called, so inference-only users of `concat_blocks` (the
GRU decoder, the cache-primitive fixtures) allocate no gradient memory and run the
same forward cell as today.

### Behaviour that changes besides the backward pass

Moving from a `CellularLayer` built by `layer(...)` to a block modelled on
`AccumulationBlock` changes more than the backward cell. The implementer should
make each of these deliberate and covered by a test:

- **Child setup runs.** `AccumulationBlock.setup()` runs every child's `setup()`;
  the current `concat_blocks` layer never calls its parts' `setup()`. Parts that
  rely on setup (for example to initialise weights) will now be set up.
- **Children are destroyed.** `AccumulationBlock.destroy()` destroys every child;
  the current layer does not. A part block shared with another owner would now be
  destroyed twice — the PDSL interpreter builds fresh parts per statement, so this
  only matters for Java callers of `concatBlocks`.
- **Compute requirements reach the parts.** Today the requirements apply to the
  wrapping layer only; the new block propagates them to every part, as
  `accumBlocks` does.
- **Input-shape validation.** `concatBlocks` does no validation today. The new
  constructor should reject an empty part list and parts whose input size differs
  from the block's input (as `AccumulationBlock` does), but must *not* require the
  parts to share an output shape.
- **`Learning` and `Tracking`.** The block propagates `setParameterUpdate` and
  `setInputTracking` to its parts; the current layer does not, which is part of why
  the parts' weights never train.

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

2. **PDSL gradient test** (new, in `engine/ml/src/test/java/org/almostrealism/dsl/`):
   a `.pdsl` layer whose body is `concat_blocks` of two parts with trainable
   weights, built and compiled for training (`model.compile(true, ...)`, as
   `SdpaPrimitivesTest` and `DynamicTanhPrimitiveTest` already do for other
   primitives), run forward then `backward(...)` with a known output gradient.
   It asserts that each part's weights receive a non-zero gradient equal to the
   hand-computed slice-times-input reference, and that the returned input gradient
   is the summed contribution. No hand-written training loop: if the test steps an
   optimizer, `ModelOptimizer` owns the loop. At least one part should start with
   `slice(...)` (as `gru_cell` does) so the slice and concat backward passes are
   exercised together.

3. **Forward regressions stay green, unchanged:** `CacheReadPrimitiveTest`,
   `PdslLanguageReferenceExamplesTest`, and the GRU decoder tests. The existing
   `AccumulationBlockTests` also stay green unchanged, confirming the base
   extraction did not alter summation behaviour.

4. **Docs:** the "no backward pass" entry is gone from
   `docs/internals/pdsl-language.md`, the `concat_blocks` row states it is
   differentiable, and `domain/graph/README.md` lists the new block.

5. **Behaviour changes are tested:** the new block's tests cover what
   `AccumulationBlockTests` covers for summation — rejecting no parts and
   mismatched input shapes, propagating compute requirements, running every part's
   setup, and destroying parts with or without a built backward cell — and accept
   parts whose output shapes differ.

6. **Build quality:** `mvn clean install -DskipTests` succeeds and the build
   validator (`checkstyle`, `code_policy`, `test_timeouts`, `duplicate_code`)
   reports no new violations — in particular, no Producer-pattern violation in
   the new backward code and no duplicate-code finding from the new block.

Targeted test commands (bounded, CPU): run `AccumulationBlockTests` and the new
concat gradient test class together as a bounded `test_classes` selection; run the
new PDSL gradient test and the named forward tests
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
  implementer should file a tracker task for it.
- **Open question — stale "drops the backward pass" statements.** Two places
  outside `docs/` state the limitation this task removes: the header comment of
  `engine/ml/src/main/resources/pdsl/silu_mlp.pdsl` ("concat_blocks drops the
  backward pass") and the javadoc of
  `DiffusionTransformerFeatures.timestepEmbedding` ("`concat_blocks` drops the
  backward pass"). Both become false the moment this lands, even though the
  Fourier stage stays in Java. The original plan deferred the `silu_mlp.pdsl`
  revision to the Fourier move; the reviewer should decide whether this task
  rewrites both sentences to say the stage *could* now move (recommended, since
  otherwise they misstate the language), or leaves them for the follow-up.
- **Open question — parts with no backward cell.** `AccumulationBlock`'s backward
  calls `child.getBackward().setReceptor(...)` unconditionally, so a child whose
  `getBackward()` is `null` (any `CellularLayer` built with a `null` backward, such
  as the deprecated `LayerRoutingFeatures.product`) throws. `SequentialBlock.add`
  instead warns ("No backward Cell for …") and cuts the chain. The new block must
  pick one behaviour for such parts — skip with a warning (consistent with
  `SequentialBlock`; the part contributes zero input gradient) or reject at build
  time — and test it. PDSL parts are usually `{ ... }` inline blocks, which become
  `SequentialBlock`s and always have a backward cell, so this mostly affects Java
  callers.
- **Risk — parts that ignore their input.** `cache_read` (`LayerFeatures.cacheRead`,
  an operator-form `layer(...)` whose output does not depend on the input) and
  `identity` appear as `concat_blocks` parts in `gru_decoder.pdsl` and
  `test_cache_primitives.pdsl`. Their backward cells are not exercised today
  because `concat_blocks` never calls them. They are only built when a model is
  compiled for training, so the inference forward tests above will not reveal a
  problem; the Java gradient test should include one input-independent part (for
  example `cache_read`) and assert it contributes a zero input gradient without
  failing.
- **Open question — base extraction vs. standalone class.** The recommendation is
  to extract a shared base and refactor `AccumulationBlock` onto it. This touches a
  base-branch production class; the reviewer should decide whether that refactor is
  welcome now, or whether a standalone `ConcatenationBlock` (accepting the
  duplication, or deferring the base extraction to a separate cleanup) is
  preferred. Either way, `AccumulationBlock`'s tests must pass unchanged.
- **Slice API (resolved).** `SlicingFeatures.subset(shape(partSize), gradient,
  offset)` yields a `[partSize]` producer at a flat offset, which is then reshaped
  to the part's output shape; see "Where the new behaviour belongs".

## Estimated complexity

**Medium.** The mechanism to copy is well understood and localized to one block
class plus a thin change to `concatBlocks` and a few documentation edits. The
effort is in getting the backward-pass slicing and shared-buffer accumulation
exactly right as Producer compositions, extracting the shared base without
disturbing `AccumulationBlock`'s behaviour, and writing a correct hand-computed
gradient reference for parts of differing sizes.
