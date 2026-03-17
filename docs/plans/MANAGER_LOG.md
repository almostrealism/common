# Project Manager Log

This is the living journal of the Almost Realism project management process. It captures
the current thinking about where the platform is heading, why specific tasks are chosen,
and how each piece of work connects to the larger vision.

This document is maintained by the automated project planning agent. It is not a historical
archive — it is a snapshot of current thinking. Older entries are pruned to keep the
document focused and under 50,000 characters.

---

## Current State

The Almost Realism platform is a monorepo of four Maven projects (`common`, `flowtree`,
`rings`, `ringsdesktop`) providing high-performance scientific computing, ML, and
multimedia generation with pluggable native acceleration (CPU, OpenCL, Metal, JNI).

### Strategic Direction

The platform is pursuing a layered strategy:

1. **Foundation** — Solid documentation, clean code, comprehensive tests
2. **Performance** — Hardware-accelerated computation that rivals dedicated frameworks
3. **Proof of Value** — Real-world demonstrations (model replication, audio generation,
   distributed training) that show what the platform can do
4. **Self-Understanding** — The long-term aspiration: training modest language models
   locally that understand the platform itself, creating software that grows and evolves
   alongside its own capabilities

### Task Categories (Priority Order)

1. **Documentation** — Can the platform be understood by humans and AI systems?
2. **Code Quality** — Is the codebase clean, consistent, and well-tested?
3. **Performance** — Can the platform handle real workloads efficiently?
4. **Proof of Value** — Can we demonstrate compelling real-world capabilities?

---

## Planning History

### 2026-03-12 — TODO/FIXME Individual Fixes

**Category:** Code Quality
**Branch:** `project/plan-20260311-202910`

#### What Happened

This cycle began as a "TODO/FIXME triage and dead code cleanup" plan — a mass effort to
categorize 506 TODOs and delete files with auto-generated stubs. The project owner rejected
the mass-triage approach as destructive (converting TODOs to javadoc and deleting stub
classes loses intent and removes placeholder code that may still be needed). The branch
pivoted to **individually fixing TODO items with real implementations**, one at a time.

This turned out to be far more valuable than the original plan. Instead of shuffling
comments around, 13 genuine bugs and missing features were implemented.

#### Completed TODO Fixes (13 of 18 candidates)

1. **`DefaultPhotonField.removePhotons()`** — Implemented using iterator/remove pattern
   with distance checking. Also fixed a pre-existing `ClassCastException` in `getEnergy()`.

2. **`RGB.equals()`** — Epsilon-based floating-point comparison with bucketed `hashCode()`
   to maintain the equals/hashCode contract. Added NPE null check.

3. **`PhotonFieldContext.clone()`** — Full deep clone of `ShaderContext` state plus
   `PhotonFieldContext`-specific fields (field, film).

4. **`ElectronCloud.getNextEmit()`** — Verified the override IS necessary (queue-based
   emission model differs from `HarmonicAbsorber`). Documented why.

5. **`SetIntervalScale.valueAt()`** — Modular interval indexing for multi-octave support,
   replacing `UnsupportedOperationException`.

6. **`MidiSynthesizerBridge` pitch bend** — 14-bit MIDI value conversion to semitones.
   Added `setPitchBend()` on `AudioSynthesizer` and `PolyphonicSynthesizer`.

7. **`GraphFileSystem.rename()`/`move()`** — Copy via resource factory + delete. Also
   fixed a content transfer bug in `move()` (was not copying resource data).

8. **`KernelSeries` period** — Changed from product-of-distinct to LCM for correct
   periodic computation. Added `long` intermediate arithmetic to prevent overflow.

9. **`SurfaceGroup.getNormalAt()`** — Delegates to the closest child surface instead of
   returning null. Fixed distance calculation for non-`AbstractSurface` children.

10. **`FlowTreeCliServer` static field** — Converted static mutable state to instance
    fields for thread safety.

11. **`NodeGroup` separator** — Fixed a real parsing bug: was using `":"` instead of
    `ENTRY_SEPARATOR` (which is `"::"`).

12. **`Quotient.getDenominator()`** — Supports >2 operands by converting to a chain of
    2-operand divisions. Fixed multi-operand evaluation bug in `Quotient.create()`.

13. **`ReflectionShader`** — Removed dead commented-out code.

#### Other Fixes (from auto-review and CI)

- **`ImageResource.clip()`** — Fixed bug where `cx` parameter was unused in pixel loop
- **`CollectionVariable`** — Documented the generics type hierarchy issue via javadoc
- **`AlmostCache`** — Deleted (the one genuinely dead file, never instantiated)
- **`AudioSynthesizer`** — Extracted `applyFrequency()` to eliminate code duplication
  between `setFrequency()` and `setPitchBend()`
- **`RGB.hashCode()`** — Added javadoc documenting bucket-boundary limitation
- **`MetricBase`** — Reverted `ConcurrentHashMap` migration (caused contention under
  profiling); restored `Collections.synchronizedMap`
- **`PlanarLight`** — Fixed `setColor()`/`getColor()` which were no-ops

#### Remaining Hard Items (not attempted, left for future work)

These were identified during the TODO survey but are too complex or risky for this cycle:

1. **`CollectionProducerComputation.enableShapeTrim`** — Always `false`; removing it may
   affect the compilation pipeline in subtle ways
2. **`TraversableRepeatedProducerComputation`** — `KernelIndex` vs `globalIndex` confusion
   requires deep understanding of the kernel dispatch model
3. **`NativeExecution` blocking wait** — Thread coordination issue in native code execution
4. **`RefractionShader` algorithm** — Physics-correct refraction requires careful
   implementation and testing
5. **`SlackListener.handleCancelCommand()`** — Requires understanding FlowTree job
   lifecycle; low priority

#### Why This Task

The original mass-triage plan would have touched hundreds of files to recategorize
comments. The pivot to individual fixes produced concrete improvements: 13 methods that
previously threw `UnsupportedOperationException`, returned null, or had incorrect behavior
now work correctly, each with dedicated tests.

#### What Comes Next

The foundation work is complete. Four cycles have covered:
- Cycle 1-2: Documentation (compilation pipeline, ML inference) — **complete, merged**
- Cycle 3: Code Quality (@TestDepth standardization) — **complete, merged**
- Cycle 4: Code Quality (individual TODO fixes) — **complete on this branch**

The next planning cycle should be the first **proof-of-value** task — something that
exercises the platform's capabilities end-to-end. Candidates:
- ML inference optimization (benchmarking attention performance)
- New model replication (exercising the full pipeline)
- Audio generation demonstration
- Self-hosted training of a small model

The base/code module still has 201 undocumented public classes, and 5 hard TODO items
remain. These can be addressed opportunistically as proof-of-value work reveals friction.

---

### 2026-03-10 — @TestDepth Standardization and TestSuiteBase Compliance

**Plan:** [`PLAN-20260310-testdepth-standardization.md`](PLAN-20260310-testdepth-standardization.md)
**Category:** Code Quality

Evaluated documentation (confirmed excellent: 20 internals docs, 84% module READMEs, all
CLAUDE.md files present). Moved to Code Quality. Found @TestDepth adoption at only 5.2%
(99/1,898 methods) — far worse than the "~50%" estimate from Cycle 1. Created a three-phase
plan: (1) annotate timeout-bearing expensive tests, (2) fix non-TestSuiteBase classes,
(3) broader review. Work was executed and merged. See plan document for details.

---

### 2026-03-09 — Document the ML Inference Pipeline Internals

**Plan:** [`PLAN-20260309-ml-inference-pipeline-docs.md`](PLAN-20260309-ml-inference-pipeline-docs.md)
**Category:** Documentation

Documented the ML inference pipeline internals: KV cache allocation and write-time GQA
expansion, `AutoregressiveModel` two-phase generation loop, RoPE position tracking, causal
masking, and cross-attention for encoder-decoder models. Created
`docs/internals/ml-inference-pipeline.md`. This was prioritized over memory management
(which had substantial coverage in `hardware/README.md` already) because it directly
enables proof-of-value ML work.

---

### 2026-03-08 — Document the Compilation Pipeline

**Plan:** [`PLAN-20260308-compilation-pipeline-docs.md`](PLAN-20260308-compilation-pipeline-docs.md)
**Category:** Documentation

First planning cycle. Comprehensive survey found the compilation pipeline (Producer graphs
→ Process trees → native kernels) as the #1 documentation gap. Created three internals
docs: `computation-graph-to-process-tree.md`, `process-optimization-pipeline.md`,
`backend-compilation-and-dispatch.md`. Code quality was graded A overall with the TODO/FIXME
and @TestDepth issues noted for future cycles.
