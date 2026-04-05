# Producer Pattern Violations Audit

Generated: 2026-03-28

This document catalogs all locations in non-test source code where `.toDouble()` and
`.evaluate()` are used. Not all of these are violations — many are legitimate uses at
pipeline boundaries, one-time initialization, or inherently CPU-bound domains. Each
entry is annotated with its status.

---

## .toDouble() Calls in Non-Test Source

### studio/ (Potential Violations — Music/Compose Modules)

| File | Line | Context | Status |
|------|------|---------|--------|
| `studio/music/.../ParameterizedVolumeEnvelope.java` | 140 | `getAttack(dr.toDouble())` | **Review** — envelope parameter extraction |
| `studio/music/.../ChordProgressionManager.java` | 74, 77-79 | `evaluate().toDouble()` | **Review** — genetic algorithm result extraction |
| `studio/music/.../PatternLayerManager.java` | 356 | `evaluate().toDouble()` | **Review** — automation parameter |
| `studio/music/.../ParameterSet.java` | 68-70 | `evaluate().toDouble()` | **Review** — genetic algorithm parameter extraction |
| `studio/music/.../GridSequencer.java` | 126 | `evaluate().toDouble()` | **Review** — sequencer value extraction |
| `studio/compose/.../AudioMeter.java` | 57, 62 | `silenceDuration.toDouble()`, `clipCount.toDouble()` | **Acceptable** — monitoring/metrics |
| `studio/compose/.../ConditionalAudioScoring.java` | 96, 139-140 | `evaluateOptimized().toDouble()` | **Acceptable** — loss/metric computation at pipeline boundary |
| `studio/compose/.../OptimizeFactorFeatures.java` | 122, 130 | `.toDouble()` | **Review** — optimization factor |

### compute/ (Mostly Acceptable)

| File | Line | Context | Status |
|------|------|---------|--------|
| `compute/algebra/.../ArithmeticFeatures.java` | 265, 305 | Constant folding: `evaluate().toDouble()` | **Acceptable** — compile-time constant optimization |
| `compute/algebra/.../CollectionCreationFeatures.java` | 271 | `value.toDouble()` | **Acceptable** — scalar constant creation |
| `compute/algebra/.../CollectionFeatures.java` | 1010, 2750, 2832 | Constant folding | **Acceptable** — compile-time constant optimization |
| `compute/algebra/.../Vector.java` | 478 | `dotProduct().evaluate().toDouble()` | **Review** — could be expressed as Producer |

### domain/ (Legacy Code)

| File | Line | Context | Status |
|------|------|---------|--------|
| `domain/color/.../HighlightShader.java` | 141, 154 | `evaluate(args).toDouble()` | **Review** — shader coefficient extraction |
| `domain/space/.../Sphere.java` | 239-241 | `evaluate(args).toDouble()` | **Review** — ray intersection computation |

### engine/ (Mostly Acceptable)

| File | Line | Context | Status |
|------|------|---------|--------|
| `engine/render/.../RefractionShader.java` | 323 | `id.toDouble()` | **Review** — intersection distance check |
| `engine/utils/.../TestFeatures.java` | 244 | `actual.toDouble()` | **Acceptable** — test assertion helper |
| `engine/audio/.../NoteAudioSourceAggregator.java` | 84 | `evaluate(args).toDouble()` | **Review** — audio source selection |
| `engine/audio/.../SilenceDetectionOutputLine.java` | 60 | `max.toDouble()` | **Acceptable** — silence detection threshold |

---

## .evaluate() Calls in engine/ml/ and studio/ (Non-Test Source)

### engine/ml/ (Acceptable — Pipeline Boundaries)

| File | Line | Context | Status |
|------|------|---------|--------|
| `engine/ml/.../DiffusionFeatures.java` | 118 | `.get().evaluate()` | **Acceptable** — diffusion setup |
| `engine/ml/.../DiffusionSampler.java` | 182, 238 | Step-level evaluate in sampling loop | **Acceptable** — step boundary in autoregressive loop |
| `engine/ml/.../DiffusionTrainingDataset.java` | 172, 175 | Noise sampling / data preparation | **Acceptable** — data loading boundary |

### studio/music/ (Review Needed)

| File | Line | Context | Status |
|------|------|---------|--------|
| `studio/music/.../ParameterizedFilterEnvelope.java` | 91, 96, 97 | Envelope computation | **Review** — could chain as Producers |
| `studio/music/.../ParameterizedLayerEnvelope.java` | 108, 109 | Envelope computation | **Review** — could chain as Producers |
| `studio/music/.../ParameterizedVolumeEnvelope.java` | 105-107 | Envelope computation | **Review** — could chain as Producers |
| `studio/music/.../PatternFeatures.java` | 136, 161 | Pattern computation | **Review** |
| `studio/music/.../ChordProgressionManager.java` | 74, 77-79 | Genetic result extraction | **Acceptable** — heredity domain |
| `studio/music/.../PatternLayerManager.java` | 356 | Automation parameter | **Review** |
| `studio/music/.../GridSequencer.java` | 126 | Sequencer values | **Review** |
| `studio/music/.../ParameterSet.java` | 68-70 | Gene result extraction | **Acceptable** — heredity domain |

### studio/compose/ (Mixed)

| File | Line | Context | Status |
|------|------|---------|--------|
| `studio/compose/.../AudioScene.java` | 1101 | Scene setup | **Acceptable** — pipeline boundary |
| `studio/compose/.../AudioDiffusionGenerator.java` | 248, 270, 277 | Diffusion generation | **Acceptable** — step boundaries |
| `studio/compose/.../AudioGenerator.java` | 237 | Generation output | **Acceptable** — pipeline boundary |
| `studio/compose/.../ConditionalAudioScoring.java` | 77, 85, 115, 150 | Scoring computation | **Review** — some may be internalizable |
| `studio/compose/.../AutoEncoderFeatureProvider.java` | 36, 39 | Feature extraction | **Acceptable** — pipeline boundary |
| `studio/compose/.../LegacyAudioGenerator.java` | 157, 182 | Legacy generation | **Review** — legacy code |
| `studio/compose/.../AudioModulator.java` | 86 | Modulation output | **Acceptable** — pipeline boundary |
| `studio/compose/.../AudioTrainingDataCollector.java` | 246, 254, 277, 304, 313, 361 | Data collection | **Acceptable** — data loading boundary |
| `studio/compose/.../AudioLatentDataset.java` | 280, 287 | Dataset preparation | **Acceptable** — data loading boundary |
| `studio/compose/.../DefaultChannelSectionFactory.java` | 235, 237 | Channel setup | **Acceptable** — configuration |
| `studio/compose/.../OptimizeFactorFeatures.java` | 122, 130 | Optimization factor | **Review** |

---

## Cell/Block Naming Violations

**No violations found.** All 26 classes ending in "Cell" properly implement/extend
`org.almostrealism.graph.Cell`. All 4 classes ending in "Block" properly implement
`org.almostrealism.model.Block`.

---

## GRUCell / GRUDecoder

**Source files not available.** Only compiled `.class` files exist in
`engine/ml/target/classes/org/almostrealism/ml/midi/`. These cannot be audited
or converted without source code. The source must be created or recovered.

---

## Summary

| Category | Total Found | Acceptable | Needs Review | Violations |
|----------|------------|------------|--------------|------------|
| `.toDouble()` in non-test code | ~36 | ~20 | ~14 | 0 confirmed |
| `.evaluate()` in ml/studio | ~49 | ~35 | ~14 | 0 confirmed |
| Cell naming | 26 classes | 26 | 0 | 0 |
| Block naming | 4 classes | 4 | 0 | 0 |

**Note:** Items marked "Review" are in code that may be at legitimate pipeline
boundaries (e.g., genetic algorithm evaluation, envelope parameter extraction)
but could potentially be refactored to use Producers more fully. These are
candidates for future improvement, not build-breaking violations.
