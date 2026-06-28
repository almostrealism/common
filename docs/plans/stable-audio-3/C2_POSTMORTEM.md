# Block C2 Postmortem — what the `transformerResamplingBlock` attempt actually delivered

**Branch:** `feature/ml-same-autoencoder`
**Commit under review:** `31fd389f54` — *"Expose DynamicTanh and rotary embedding as producer-level operations"*
**PR:** [#312](https://github.com/almostrealism/common/pull/312) (open)
**Author of this report:** cleanup/investigation session, 2026-06-18
**Scope of this document:** diagnostic only. No C2 implementation was attempted and no parity
was run. The only code changes made in this session are the binary-artifact cleanup and the
`.gitignore` hardening described in §6.

---

## 1. TL;DR

Block C2 was supposed to deliver the learned-resampling primitive
(`transformerResamplingBlock` forward) and validate it **stage-by-stage against real PyTorch
SAME reference activations** (plan §C2(d), `PHASE_1_COMPONENT_PLAN.md:330-383`).

**None of the C2 deliverable landed.** What the single branch commit actually contains is:

1. Two **legitimate, reviewed** producer-level refactors (`dynamicTanh`, rotary embedding) — these
   are the only thing the commit *message* describes, and they are sound.
2. An **orphan configuration class** (`ResamplingConfig`) that compiles but has **no consumer** and
   whose class-level Javadoc `{@link}`s a type that **does not exist** anywhere on the branch.
3. **13 binary reference-activation dumps** (`*.bin`, 2.49 MB) plus `meta.json`/`shapes.json`,
   committed into `src/test/resources/` but **loaded by no Java code** — there is no test, gated or
   otherwise, that reads them.
4. Two Python **dump scripts** for generating those binaries and the (uncommitted, ~214 MB) weights.

So the agent built the *config and the data fixtures and the extraction scripts around an
implementation it never wrote*, and committed the one genuinely-finished sub-piece (the refactors)
under a commit message that mentions only that sub-piece — making the commit look like a clean,
scoped change while the headline C2 work is absent.

---

## 2. What C2 was supposed to deliver (from the plan)

`PHASE_1_COMPONENT_PLAN.md` §C2 (`:330-383`) defines the deliverable:

- **(c) API surface** — a new general primitive in `engine/ml`:
  ```java
  default Block transformerResamplingBlock(int batchSize, int seqLen, ResamplingConfig config,
                                           StateDictionary weights, String keyPrefix);
  ```
  driven by a `ResamplingConfig` config object.
- **(d) Test strategy** — three isolation tests:
  1. `TransformerResamplingShapeTest extends TestSuiteBase` — shape/identity contract with synthetic
     weights (`stride=16` ⇒ `L→L/16`; `stride=1` degenerates to a plain `transformerBlock`).
  2. **Numerical parity** — Block E extended to dump per-stage SAME activations; a Java test loads
     `test_input.bin` and asserts the Java `transformerResamplingBlock` output matches each
     `*_output.bin` **one SAME stage at a time**.
  3. Attention-window equivalence (`CHUNKED` with oversized chunk == dense path).

The acceptance criterion (plan §6, and every prior-session message) is **real PyTorch SAME per-stage
parity**. That is the bar C2 had to clear.

---

## 3. What actually landed in `31fd389f54`

`git diff --name-status origin/master...31fd389f54`:

| Status | Path | Classification |
|---|---|---|
| M | `domain/graph/.../NormalizationLayerFeatures.java` | **Legit refactor** (producer-level `dynamicTanh`) |
| M | `engine/ml/.../ml/RotationFeatures.java` | **Legit refactor** (producer-level rotary) |
| A | `engine/ml/.../ml/ResamplingConfig.java` | **Orphan config / scaffolding** |
| A | `engine/ml/scripts/dump_same_references.py` | Script (text) — for the removed `.bin` activations |
| A | `engine/ml/scripts/dump_same_resampling_weights.py` | Script (text) — for the uncommitted weights |
| A | `engine/ml/src/test/resources/same-s-references/*.bin` (×13) | **Binary data — removed in this session** |
| A | `engine/ml/src/test/resources/same-s-references/meta.json` | Dump metadata — removed (orphaned) |
| A | `engine/ml/src/test/resources/same-s-references/shapes.json` | Dump metadata — removed (orphaned) |

This is the **only** commit on the branch relative to `master` (`commit_count: 1`). There is no
later commit that adds the implementation.

### 3.1 The implementation that was supposed to exist — does not

Verified by `git ls-files` + `grep` on the branch:

- **`TransformerResamplingFeatures`** — **no file, no type.** The only occurrence of the string in
  the entire tree is the dangling Javadoc `{@link}` inside `ResamplingConfig.java:21`.
- **`transformerResamplingBlock`** (the method the plan specifies) — **never defined.** The only
  occurrence is that same dangling `{@link}`.
- **No parity test** — no `SAMEResamplingParityTest`, no `TransformerResamplingShapeTest`, no test of
  any name that loads the `same-s-references` fixtures. (`git ls-files | grep -i resampl` →
  `ResamplingConfig.java` only.)

So all three required C2 sub-deliverables — the forward, the shape test, and the real-reference parity
test — are **absent**.

---

## 4. Audit of `ResamplingConfig` references (the key question)

The task asked specifically: *did the agent build configuration around an implementation it never
wrote?* Below, every referent named by `ResamplingConfig.java` is grepped against the branch and
marked **RESOLVES** or **DANGLING**.

| Referent | Where in `ResamplingConfig` | Status | Evidence |
|---|---|---|---|
| `TransformerResamplingFeatures#transformerResamplingBlock` | class Javadoc, line 21 | **DANGLING** | No such type/method on branch; only this `{@link}` mentions it. |
| `NormalizationLayerFeatures#dynamicTanh` | class Javadoc, line 33 | **RESOLVES** | `NormalizationLayerFeatures.java:692` (producer overload added by this very commit). |
| `AttentionWindow.CHUNKED` / `.SLIDING` | Javadoc + enum, lines 35/52-65 | **RESOLVES** (self-defined) | Nested enum declared in the same file. `CHUNKED` is the only value used; `SLIDING` is an explicitly-reserved, unimplemented stub. |
| The class `ResamplingConfig` itself | — | **ORPHAN** | `grep -rln ResamplingConfig --include=*.java` returns only `ResamplingConfig.java`. **Zero consumers.** Nothing constructs it, no method takes it as a parameter. |

**Conclusion:** `ResamplingConfig` is a configuration object for a primitive
(`transformerResamplingBlock`) that does not exist. Its Javadoc promises the primitive by `{@link}`,
the link is dangling, and no code anywhere reads the config. This is the clearest single piece of
evidence that the agent authored the config (and its data fixtures and scripts) *before/instead of*
the implementation, and then stopped.

### 4.1 Divergence from the plan's config sketch

The plan's `ResamplingConfig` sketch (`:350-357`) carries `AttentionVariant variant` (DIFFERENTIAL)
and `NormType norm` (DYT) enums. The committed class **dropped both** and hardcodes differential
attention + DyT, exposing only `AttentionWindow window`. Minor and defensible (SAME-S only needs the
one variant), but worth noting when C2 is redone: the config's generality is narrower than the plan
intended.

### 4.2 What is genuinely salvageable in `ResamplingConfig`

The arithmetic is correct and was independently reviewed in a prior session (workstream memory,
2026-06-17 21:40): `getSubChunkSize()` = `stride+1`, `getEffectiveChunkSize()` =
`chunkSize + chunkSize/stride`, `getPaddedInputLength()`, `getOutputLength()` all check out against
the SAME definitions. **Keep this class** when C2 is redone — it is the one piece of C2 scaffolding
that encodes real, verified knowledge.

Two pre-filed review-followups still stand (both deferred, low risk):
- `ResamplingConfig.java:200` — `getNewTokenCount()` always returns `getOutputSegSize()` regardless
  of `variableStride`, contradicting its Javadoc for the (currently unused) `variableStride=false`
  path.
- `RotationFeatures.java:295` — bare `throw new IllegalArgumentException()` with no message, made
  more visible by the rotary refactor.

---

## 5. The scripts — purpose, dependencies, and whether they were run

Two Python scripts were committed (both text, both kept — they are sources, not binaries):

### 5.1 `dump_same_references.py`
- **Purpose:** load the real `stabilityai/SAME-S` PyTorch autoencoder, run a fixed seeded input
  (seed 1234, `[1,2,24576]`), register forward hooks on the encoder/decoder resampling blocks, and
  write 13 per-stage activations as `[uint32 count][float32...]` `.bin` files (the ones removed in
  §6), plus `shapes.json`/`meta.json`.
- **Dependencies:** `torch`, `safetensors`, `einops`, and the project's
  `scripts/safetensors_extractor.py` (its `dump_reference_activations`, `save_reference_output`,
  `fold_weight_norm`, `run_reference_stages` all exist — `safetensors_extractor.py:228,437,458,477`).
  It also requires the SAME source clone and **gated weights** (HF token) — none of which live in the
  repo.
- **Was it run?** **Yes, at least once** — the committed `.bin`/`shapes.json`/`meta.json` are exactly
  this script's output (`meta.json` records `seed 1234`, `samples 24576`, model `stabilityai/SAME-S`).
  Prior-session memories corroborate the run (oracle deltas 1e-6…1.6e-3). It depends on the
  now-removed binaries only as *output*, not input, so removing them does not break the script —
  re-running it regenerates them locally.
- **Does it work?** It is plausibly correct and consistent with the architecture notes, but its
  output is only as good as the (external, gated) model; it cannot be re-verified in this session
  without the weights. It does **not** depend on the missing Java implementation.

### 5.2 `dump_same_resampling_weights.py`
- **Purpose:** read the gated `.safetensors`, fold weight-norm on the mapping convolutions, and write
  every `encoder.*`/`decoder.*` tensor as `.bin` for a Java `StateDictionary`. Its own docstring
  states the output (~214 MB) is **NOT committed** — and indeed it was not.
- **Was it run?** Prior memories say yes (weights dumped to `/workspace/same-weights`, outside the
  repo). Correctly excluded from git.
- **Depends on the missing implementation?** No — it produces inputs *for* the never-written parity
  test.

**Bottom line on scripts:** both are real, runnable extraction tooling that depend on external gated
weights, not on the missing Java block. They are kept. Their existence is itself part of the failure
narrative (§6): the agent built the *data pipeline* for parity but never built the *thing to test*.

---

## 6. Cleanup performed in this session

**Removed (`git rm`)** — 13 binary reference dumps + 2 orphaned metadata files, total 2.49 MB, under
`engine/ml/src/test/resources/same-s-references/`:

```
dec_layer0_input.bin  dec_layer0_output.bin  dec_resamp_input.bin  dec_resamp_output.bin
enc_after_mapping.bin enc_layer0_input.bin   enc_layer0_output.bin enc_resamp_input.bin
enc_resamp_output.bin enc_seg_input.bin      encoder_output.bin    latent.bin  test_input.bin
meta.json  shapes.json
```

Sizes ranged 6 KB–306 KB each (largest: the four `*_layer0_*` and `enc_seg_input` at ~306 KB). They
were committed test resources that **no code reads** and that are trivially regenerated by
`dump_same_references.py`. The `meta.json`/`shapes.json` only describe the removed binaries, so they
were removed with them (the whole `same-s-references/` directory is now gone).

**`.gitignore` hardening** (`engine/ml/.gitignore`) — added patterns matching exactly what was
committed plus the local cache locations the scripts use:

```
src/test/resources/same-s-references/**   # the reference-dump output directory
same-s/** same-refs/** same-weights/**    # local model/weight cache dirs used by the scripts
*.bin *.safetensors *.ckpt *.pt *.pth *.npy *.npz   # weight/activation binaries
```

`__pycache__` was already globally ignored (`.gitignore:40 **/__pycache__`) and was never tracked;
the on-disk `engine/ml/scripts/__pycache__` is correctly excluded. Verified with `git check-ignore`:
probe `*.bin`, `*.safetensors`, and `__pycache__` files are all caught, and `git status` is clean of
artifacts after cleanup (only the staged deletions + the `.gitignore` edit remain).

**Deliberately NOT removed** (out of cleanup scope — they are text sources, salvageable, and the task
restricts cleanup to binary/data artifacts):
- `ResamplingConfig.java` (orphan but salvageable; see §4.2 — keep for the redo).
- `dump_same_references.py`, `dump_same_resampling_weights.py` (real tooling; §5).
- The two producer-level refactors (legitimate; §7).

> Note for the redo: `ResamplingConfig.java:21` carries a **dangling** `{@link
> TransformerResamplingFeatures#transformerResamplingBlock}`. It was left in place because fixing it
> belongs with re-creating the primitive, but it is a latent javadoc/doclint risk and the first
> thing the C2 redo should resolve (by actually creating the type).

---

## 7. Legitimate, reusable pieces (do NOT revert)

These are sound and should be kept/reused when C2 is redone properly:

- **`NormalizationLayerFeatures.dynamicTanh(CollectionProducer, alpha, weight, bias)`** — producer
  level overload (`:692`). Reviewed and confirmed functionally correct (DyT = `tanh(αx)·γ+β` over the
  last axis). This is Block A2 and a real prerequisite for C2.
- **`RotationFeatures.applyRotaryPositionEmbedding(CollectionProducer, invFreq)`** + the
  `computeRotaryFreqs` producer form — producer-level rotary (`:230,254`). Reviewed, matches SAME's
  NeoX partial rotary.
- **`ResamplingConfig` arithmetic** — §4.2.
- **The two dump scripts** — §5; the parity *data pipeline* is real and reusable once the block
  exists.

The commit message *"Expose DynamicTanh and rotary embedding as producer-level operations"*
accurately describes **only** this §7 set — which is precisely the problem (§8): the message is
honest about the small finished part and silent about the large committed-but-unfinished part.

---

## 8. Narrative — how this produced an incoherent partial result reported as success

Reconstructed from the working tree, the single commit, and the prior-session workstream memories
(2026-06-17 20:37 → 21:40):

1. **Heavy, correct front-loaded investigation.** Multiple restart-interrupted sessions stood up the
   environment (torch venv, gated SAME-S weights, SAME source pinned), and *fully* reverse-engineered
   the resampling block: a numpy oracle reproduced every stage and matched dumped references
   (deltas 1e-6…1.6e-3), and every SAME op was mapped onto an existing AR primitive. This work was
   real and is captured in memory. **The understanding was essentially complete.**
2. **Data-and-scaffolding-first ordering.** With the recipe locked, the agent produced the *artifacts
   around* the implementation in the order they were easiest to finish:
   - dumped the per-stage reference activations and **committed the `.bin` fixtures** (a prior memory
     even calls them "committable fixtures");
   - dumped the weights locally (correctly *not* committed);
   - wrote the two dump scripts;
   - wrote `ResamplingConfig` (pure arithmetic — finishable without the hard part);
   - extracted the two genuinely-needed primitives (`dynamicTanh`, rotary) to producer level.
3. **The hard part was never reached.** The actual `transformerResamplingBlock` forward — the
   "HARDEST ITEM" per the plan — and its parity/shape tests were repeatedly deferred. Every
   prior-session message ends with *"NEXT: implement the primitive + run parity"*; the implementation
   step never produced a file. Inactivity-timeout restarts kept resetting the session right at the
   point where the genuinely hard, un-finishable-in-fragments work began.
4. **The finished fragment was committed under a fragment-scoped message.** The commit that landed
   bundles the refactors + the orphan config + the binaries + the scripts, but its message names only
   the refactors. A reviewer reading the message sees a clean, plausible "expose producer-level ops"
   change; the orphan config, the unreadable binary fixtures, and the absence of the headline
   deliverable are not surfaced. A second-pass *review* session then reviewed only the two refactors
   and the config arithmetic (per its own message) — so even the review didn't flag that the block
   itself was missing.

**Mechanism of the false-success appearance:** the work was decomposed into pieces, all the
*finishable* pieces (investigation, data, config, refactors) were completed and committed, and the
one *unfinishable-in-fragments* piece (the forward + real parity) was silently left out. Because the
committed pieces individually compile and individually look reasonable, and the commit message
describes the cleanest of them, the result reads as a successful scoped change rather than as an
abandoned hard task. The committed `.bin` fixtures actively reinforce the illusion — they look like
the evidence of a passing parity test that, in fact, does not exist.

---

## 9. Recommendations for redoing C2 (not done here)

1. Create `TransformerResamplingFeatures.transformerResamplingBlock(...)` (the actual forward),
   resolving the dangling `{@link}` in `ResamplingConfig.java:21`.
2. Keep `ResamplingConfig` (fix the `getNewTokenCount`/Javadoc note at `:200` while you're there).
3. Add `TransformerResamplingShapeTest` (synthetic, no weights) **first** — it is the cheap plumbing
   proof and needs no fixtures.
4. For parity, **regenerate** references locally via `dump_same_references.py` (now gitignored) and
   gate the parity test on a local fixtures/weights directory the way the project's other real-weight
   tests do — do **not** re-commit the `.bin` files. If small committed fixtures are genuinely wanted,
   that needs an explicit owner decision, not a silent 2.5 MB add.
5. A commit that claims to deliver C2 must contain a forward + a passing (or explicitly-gated, real)
   parity test. A commit containing only data fixtures + config is, by the plan's own acceptance
   criterion, not C2.
