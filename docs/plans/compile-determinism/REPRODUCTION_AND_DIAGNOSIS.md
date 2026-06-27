# Cross-Compilation Determinism — Reproduction & Diagnosis

**Branch:** `feature/ml-dit-memory-tokens`
**Status:** Research / diagnostic only. **No framework fix is attempted here**, and the Block-B2
memory-token implementation and its tests are untouched. This document is information-gathering ahead
of an interactive framework-determinism debugging session.
**Companion test:** `engine/ml/src/test/java/org/almostrealism/ml/CompileDeterminismReproductionTest.java`
**Prior diagnosis:** workstream memory `1bd55507` (root cause) and `475e154b` (review follow-up).

> **Location note.** The task brief suggested `docs/research/compile-determinism.md`. Per
> `docs/CLAUDE.md` (investigation notes belong under `docs/plans/`, not the formal docs tree) this
> report is placed under `docs/plans/` instead. It is deliberately self-contained and separable so it
> can be relocated to a dedicated framework-fix branch together with the companion test.

---

## 1. Headline finding

Two **separately compiled, structurally identical** compute graphs (same weights, same input) are
**always byte-identical** at an **even** internal sequence length, but at an **odd** internal sequence
length they **sometimes diverge** (~5e-5 … 4e-3) and sometimes do not — the divergence is
**probabilistic from one compile-pair to the next**. The same *compiled* model run twice is exactly
deterministic (`0.0`), so this is **cross-compilation** nondeterminism, not runtime nondeterminism.

The divergence was narrowed to a **single self-attention sub-block at an odd sequence length** — no
concat, no projections, no convolutions, no reshapes are needed. The feed-forward sub-block is
deterministic.

The profile artifacts show that **the two compiles produce structurally different operation graphs**:
one compile fuses the attention reduction operations into a few compact kernels; the other emits the
*same* reductions as a swarm of hundreds of separate `collectionSumComputation` / `collectionAdd`
kernels (and runs ~3× slower as a result). **Important and slightly counter-intuitive:** this gross
grouping difference is present *whether or not the output diverges* (it appears even in the
byte-identical even-seqLen control). So the grouping nondeterminism is the **root mechanism**, but it
is **numerically inert at even sequence lengths** and only **input-dependently active at odd sequence
lengths**, where the alternative groupings imply a different floating-point reduction order that is
not always bit-equivalent. This **confirms** the prior identity-hash-dependent-grouping hypothesis and
**refines** it (see §5–§6), while being explicit about what is *not* yet pinned down.

---

## 2. Established phenomenon (prior diagnosis)

From memory `1bd55507` (empirical matrix, two separate compiles, same input):

| Configuration | internal seqLen | diff |
|---|---|---|
| PREPEND audioSeqLen=8 → 9 | 9 (odd) | 8.7e-4 *(the failing `LearnedTokensTest` case; real diff 0.00177)* |
| ADALN audioSeqLen=8 → 8 | 8 (even) | 0.0 |
| PREPEND/ADALN + memtokens → 10 | 10 (even) | 0.0 |
| ADALN audioSeqLen=8 + 1 memtoken → 9 | 9 (odd) | 1728 *(random adaLN gates amplify)* |
| same compiled model run twice | any | 0.0 |
| isolated softmax over odd axis | — | 0.0 (**not** the source) |
| isolated scaledDotProductAttention over odd seqLen | — | 0.0 (**not** the source) |

So: even ⇒ identical; odd ⇒ divergent; isolated softmax/SDPA do **not** reproduce ⇒ the divergence is
*emergent* from optimizing a larger graph at an odd sequence length.

---

## 3. Reproduction harness

`CompileDeterminismReproductionTest` builds each candidate graph **twice** (two independent graph
instances over the *same* shared weights), compiles each separately, runs both on the *same* input,
and reports the maximum absolute element-wise difference. It walks a **narrowing ladder**, each rung
removing one component while **preserving an odd internal sequence length**, plus even-seqLen
controls and a same-compile (runtime-determinism) sanity check.

Design choices worth noting:

- **Weights are shared (and, for the DiT cases, cloned) across both compiles**, so the only thing that
  differs between the two graphs is object identity — isolating *compilation* nondeterminism.
- **Assertions cover only the reliable invariants** (`same-compile == 0`, `even-seqLen == 0`). The
  odd-seqLen divergence is probabilistic per compile-pair (see §4), so asserting it would make the
  test flaky. The odd-seqLen diffs are **logged** and the generated code is **dumped** as evidence
  instead. On a fixed framework those diffs become reliably `0.0`, at which point the odd-seqLen checks
  can be promoted to `assertEquals(0.0, diff)` regression guards (see §8).

### Repro command

```
mcp__ar-test-runner__start_test_run module:"engine/ml" test_classes:["CompileDeterminismReproductionTest"]
```

(Equivalent raw Maven, for reference only — prefer the MCP test runner:
`mvn test -pl engine/ml -Dtest=CompileDeterminismReproductionTest`.)

---

## 4. Results (narrowing ladder)

`maxAbsDiff` of two separate compiles of the *same* graph. Two independent runs are shown to make the
**per-run variability explicit** — note how the *set* of divergent cases changes between runs while the
even controls and the same-compile check are stably `0.0`:

| Case | what it is | internal seqLen | run 1 | run 2 |
|---|---|---|---|---|
| full DiT PREPEND (**anchor**) | the real `DiffusionTransformer` | 9 (odd) | **1.465e-3** | 0.0 |
| full DiT ADALN (control) | the real `DiffusionTransformer` | 8 (even) | 0.0 | 0.0 |
| hand DiT-minus-convs | reshape + project_in + concat + 1 block + project_out + strip | 9 (odd) | 1.221e-3 | 3.723e-3 |
| hand concat + block | prepend-concat (8→9) + 1 block, embedding space | 9 (odd) | 4.88e-4 | 1.343e-3 |
| **hand direct block** | **1 transformer block only** | 9 (odd) | 5.49e-4 | 0.0 |
| hand direct block (control) | 1 transformer block only | 8 (even) | 0.0 | 0.0 |
| **hand attn-only** | **pre-norm + selfAttention(RoPE, QK-norm, SDPA) + residual** | 9 (odd) | 4.58e-5 | 7.63e-5 |
| hand ffn-only | gated-linear feed-forward + residual | 9 (odd) | 0.0 | 0.0 |
| same compiled graph, re-run | runtime-determinism sanity | — | 0.0 | 0.0 |

### What this rules in and out

- **Minimal trigger: one self-attention sub-block at an odd sequence length.** Stripping the
  prepend-concat, the input/output projections, the 1×1 convolutions, and the channel reshapes does
  **not** remove the divergence (`attn-only @9` diverged in *both* runs — it is the most consistently
  divergent minimal case; `even @8` never diverges).
- **The feed-forward sub-block is deterministic** (`ffn-only @9 = 0.0` in both runs). The
  self-attention sub-block carries the nondeterminism.
- Consistent with the prior result that **isolated SDPA does not reproduce**, the trigger is SDPA
  *composed with* its surrounding pre-norm (LayerNorm), RoPE, QK-norm, the `to_qkv`/`to_out`
  projections, and the residual — the reduction-grouping ambiguity (§5) only arises once those
  reductions are assembled into the sub-block.
- **The divergence is probabilistic per compile-pair.** Whether any given odd-seqLen case diverges
  depends on the (unseeded) random weights/input of that run: the same gross operation-graph difference
  (§5) produces a non-zero diff for some values and rounds to exactly `0.0` for others. The even
  controls are *never* affected.

---

## 5. Evidence from the profile artifacts

Loading the two compiles of an **identical** source graph with the profile analyzer (`load_profile`)
shows that the optimizer emits **structurally different operation graphs** between the first compile
(A) and the second (B). This is consistent and real (B genuinely runs slower — it is not a
profiling-tree artifact):

| graph | compile A (nodes / runtime) | compile B (nodes / runtime) | output diff |
|---|---|---|---|
| `hand attn-only @9` (run 2) | 463 / 0.79 s — fused into one `OperationList$Runner$$Lambda` (77%) | 1903 / 1.92 s — swarm of `f_collectionSumComputation_*` (340/255/232/228 ms …) | **7.6e-5 (divergent)** |
| `hand direct block @9` | 678 / 1.1 s — fused | 2536 / 3.2 s — swarm of `collectionSumComputation` | varies: 5.5e-4 (run 1) **or** 0.0 (run 2) |
| `hand direct block @8` (even control) | 678 / 3.8 s — fused | 2588 / 9.6 s — swarm of `collectionSumComputation` + `packedCollectionSubset` | **0.0** |

`get_source` on a `collectionSumComputation` node returns the real generated JNI C kernel — a reduction
loop of the form:

```c
// f_collectionSumComputation_10086 → GeneratedOperation134_apply
for (long long global_id = global_index; global_id < global_total; global_id += 10) {
  v5442[...] = 0.0;
  for (int i = 0; i < 32;) {           // sum over a length-32 axis
    v5442[...] = v5443[...] + v5442[...];
    i = i + 1;
  }
}
```

So the artifacts capture **the actual generated code**, diffable per node.

### The careful reading (what the evidence does and does not say)

- **It does say:** across two compiles the optimizer reaches a **different operation grouping/fusion**
  for the attention reductions — one compile fuses them, the other emits them separately. This is a
  genuine difference in *generated, executed* code (B runs ~3× slower).
- **It does *not* say** that this gross grouping difference, by itself, causes the numerical
  divergence. The **even-seqLen control** has the *same* gross difference (678 vs 2588 nodes; fused vs
  swarm) yet is **byte-identical** (`0.0`), and `hand direct block @9` shows the *same* 678-vs-2536
  split in a run where the output was `0.0`. The grouping difference is therefore **necessary context
  but not sufficient** for divergence.
- **The complete causal picture** that fits every data point: the optimizer's reduction grouping is
  nondeterministic across compiles (the root mechanism). At an **even** sequence length the two
  groupings are floating-point bit-equivalent ⇒ always `0.0`. At an **odd** sequence length the two
  groupings imply a **different reduction/accumulation order** that is *not* bit-equivalent ⇒ the
  output differs for some weight/input values and coincides for others (hence the per-run variability
  in §4).

This **confirms** the prior hypothesis — identity-hash-dependent operation grouping in the optimizer /
codegen (`compute/base`) — and **localizes** it to the reduction (`collectionSumComputation`)
operations of the self-attention sub-block. It is the same class of issue seen on
`feature/lora-gradients`, where `KernelTraversalOperationGenerator` cached on an
`IdentityHashMap<String, …>` keyed by freshly built expression strings (memory `1528de11`).

---

## 6. Honesty: what is *not* established here

- **The divergence is probabilistic** (depends on per-run unseeded random weights/input). A single run
  may show a given odd case as `0.0`; that is *not* evidence the bug is absent — re-run, or use the
  most consistently divergent case (`hand attn-only @9`).
- **The gross node-count / fusion difference is not a clean proxy for divergence.** It is present in
  byte-identical (even, and some odd) compiles too. Do not conclude "different node count ⇒ divergent
  output." The mechanism is the *floating-point order* implied by the grouping at odd lengths, not the
  node count per se.
- **The exact bit-flipping operation and optimizer code site are not pinned down here.** This
  diagnostic localizes the symptom (nondeterministic grouping of `collectionSumComputation` reductions
  in the self-attention sub-block, numerically active only at odd seqLen). It does **not** identify the
  specific `IdentityHashMap`/`HashSet` iteration or fusion-eligibility predicate, nor prove whether the
  consistent A-fused / B-unfused pattern is driven by identity-hash ordering or by a first-vs-second
  compile-order effect. Disambiguating that is the interactive session's job (see §8), aided by these
  artifacts.
- **No fix is proposed or applied.** Per the task scope this is reproduction + diagnosis only.

---

## 7. Artifacts: how they are produced and analyzed

### Mechanism (the one the analyzer consumes)

The test attaches an `io.almostrealism.profile.OperationProfileNode` to each compile and saves it to
XML:

```java
OperationProfileNode profile = new OperationProfileNode(label);
Hardware.getLocalHardware().assignProfile(profile);   // wires the compilation listener
CompiledModel compiled = model.compile(false, profile);
profile.save(path);                                    // JavaBeans XML incl. OperationSource (generated code)
```

For the full `DiffusionTransformer` cases the model owns the profile internally; setting
`DiffusionTransformer.enableProfile = true` makes each forward build and populate its own profile,
retrieved via `getProfile()`.

### Where the files go (git-ignored — never commit them)

Per-compile XML profiles are written under `AR_DETERMINISM_OUTPUT_DIR` (default
`results/determinism`, resolved relative to the test working directory, i.e.
`engine/ml/results/determinism/`). This is git-ignored two ways: the root `.gitignore`
(`**/results/**`) and `engine/ml/.gitignore` (`results/**`). The XML files are large (≈0.5–4 MB each)
and **must never be committed**. Pairs written each run:

```
dit_prepend_odd9_{A,B}.xml          # full DiT PREPEND, odd 9 (the anchor)
hand_ditNoConv_odd9_{A,B}.xml       # DiT minus convolutions
hand_concatBlock_odd9_{A,B}.xml     # prepend-concat + block
hand_directBlock_odd9_{A,B}.xml     # one block, odd 9 (block-level repro)
hand_directBlock_even8_{A,B}.xml    # one block, even 8 (negative structural control)
hand_attnOnly_odd9_{A,B}.xml        # self-attention sub-block only, odd 9 (minimal trigger)
```

Each run **overwrites** these files, so the on-disk pair reflects the most recent run (and may be a
`0.0` run for some cases). `hand_attnOnly_odd9_{A,B}.xml` is the recommended pair (most consistently
divergent); `hand_directBlock_even8_{A,B}.xml` is the negative control.

### Complementary raw-kernel dump (optional)

The MCP test runner also sets `AR_INSTRUCTION_SET_OUTPUT_DIR=engine/ml/results/<run_id>` (also
git-ignored). To additionally dump the raw generated instruction-set source for every compiled
operation, enable monitoring — either set the static fields from the test
(`HardwareOperator.enableInstructionSetMonitoring = true` and
`HardwareOperator.instructionSetOutputDir = "<dir>"`, separated per compile) or pass
`-DAR_INSTRUCTION_SET_MONITORING=always`. The XML profiles + `get_source` already expose per-kernel
generated code, so this is a complement, not a requirement.

### Analyzing the two compiles

```
mcp__ar-profile-analyzer__load_profile      path:".../hand_attnOnly_odd9_A.xml"   # node_count, top ops
mcp__ar-profile-analyzer__load_profile      path:".../hand_attnOnly_odd9_B.xml"   # compare node_count / op mix
mcp__ar-profile-analyzer__search_operations path:".../<A>.xml" pattern:"collectionSumComputation"
mcp__ar-profile-analyzer__search_operations path:".../<B>.xml" pattern:"collectionSumComputation"
mcp__ar-profile-analyzer__get_source        path:".../<X>.xml" node_key:"<key>"   # the actual JNI C kernel
mcp__ar-profile-analyzer__list_children     path:".../<X>.xml"                     # top-level grouping
```

To pin down the bit-flipping operation, diff a **confirmed-divergent** pair (re-run until
`hand_attnOnly_odd9` reports a non-zero diff, or use `hand_ditNoConv_odd9`) and compare the *generated
source and the accumulation order* of the matching reduction kernels — not just the node counts.

---

## 8. Recommended next steps (for the interactive fix session)

1. **Diff the minimal divergent pair.** Re-run until `hand_attnOnly_odd9` reports a non-zero diff, then
   compare `…_A.xml` vs `…_B.xml`: the set/order of `collectionSumComputation` reduction kernels and
   their `get_source`. Use `hand_directBlock_even8_{A,B}.xml` as the negative control — it has the same
   *kind* of grouping difference but is numerically inert.
2. **Find the grouping decision.** In `compute/base`, locate the optimization/grouping pass that
   decides whether adjacent reductions (`CollectionSumComputation` and friends) are fused. Look for
   iteration over an `IdentityHashMap`/`HashSet` (or any `System.identityHashCode`-ordered traversal)
   whose order determines fusion. Confirm whether the A-fused / B-unfused pattern is identity-hash
   driven or a first-vs-second compile-order effect.
3. **Make the grouping order deterministic** (e.g. sort operations by a stable structural key before
   the fusion pass, or replace identity-keyed maps with structurally-keyed ones — the
   `KernelTraversalOperationGenerator` fix in memory `1528de11` is the precedent). Validate that the
   even-seqLen output is unchanged and the odd-seqLen output becomes reproducible across compiles.
4. **Promote the harness to a regression guard.** Once the fix lands, change the odd-seqLen checks in
   `CompileDeterminismReproductionTest` from "logged evidence" to `assertEquals(0.0, diff)` (with a few
   repetitions to defeat the probabilistic nature) and relocate the test (and this document) to the
   framework-fix branch.

---

## 9. Scope compliance

- Reproduction + diagnosis only; **no framework fix** was attempted.
- The Block-B2 implementation (`DiffusionTransformer`, `LearnedTokenFeatures`,
  `DiffusionTransformerFeatures`) and the B2 tests (`LearnedTokensTest`) were **not modified**.
- No integrity / build-validation / enforcement configuration was changed.
- The XML profile artifacts and any binaries are git-ignored and were **not** committed; only the new
  test and this report are added.
