# engine — Concept Map

The applications-and-training layer. It assembles domain models into complete working systems:
training, rendering, language-model inference, audio synthesis, and the testing framework every
module is built on. Where `domain` defines *what a model is*, `engine` defines *what to do with
one* — and it owns the loops.

Companion to [engine/README.md](README.md) and the summary map in
[docs/internals/concept-map.md](../docs/internals/concept-map.md).

## How this layer builds on the layers below

`engine` consumes the domain vocabulary wholesale: it trains `Model`s and composes `Block`s
(`domain/graph`), renders `ShadableSurface` `Scene`s with `RGB` and `Light` (`domain/space`,
`domain/color`), and runs audio `Cell`s over `Temporal` time (`compute/time`). Its defining
contribution is a set of **application-level loop-owning classes**: the project forbids
hand-written `for` loops around a compiled step, and this layer owns the training, sampling and
generation loops. (The recurrent-simulation loop owner, `SimulationRunner`, lives one layer down
in `compute/time`.)

| Loop | Owner | Reference |
|---|---|---|
| Training epochs | `ModelOptimizer` (optimize) | [Training Loop Examples](../docs/internals/training-loop-examples.md) |
| Diffusion sampling steps | `DiffusionSampler` (ml) | [Sampling Loop Examples](../docs/internals/sampling-loop-examples.md) |
| Evolutionary generations | `PopulationOptimizer` (optimize) | — |
| Autoregressive token steps | `AutoregressiveModel` (ml) | — |

## Concepts each module contributes

### optimize — training and evolution
Turns a `Model` or a `Genome` into a trained artifact.

| Concept | Role |
|---|---|
| `ModelOptimizer` | Owns the neural-network training loop (forward → loss → backward → update). |
| `AdamOptimizer`, `LossProvider` (MSE/MAE/NLL) | The update rule and the loss contract, both producer-valued. |
| `PopulationOptimizer`, `HealthComputation`, `Dataset` | Evolutionary iteration, fitness evaluation, and train/validation data. |

### render — ray tracing
A complete classical ray tracer over `domain/space` scenes.

| Concept | Role |
|---|---|
| `RayTracedScene` | The pipeline orchestrator that realizes an image from a scene. |
| `RayIntersectionEngine` | Traces rays against surfaces to find hits. |
| `LightingEngine` | Per-intersection shading: diffuse, specular, reflection, refraction, shadows. |
| `RenderParameters`, `RealizableImage` | Image dimensions and supersampling; the composable per-pixel colour producer. |

### ml — language and diffusion models
Transformer architectures and the weight/tokenizer infrastructure around them.

| Concept | Role |
|---|---|
| `StateDictionary` | The weight store — loads protobuf/safetensors checkpoints by key; the mandated way to manage weights. See [StateDictionary](../docs/internals/state-dictionary.md). |
| `AttentionFeatures` | Multi-head / grouped-query attention, QK-norm, RoPE and feed-forward, as composable `Block`s. |
| `AutoregressiveModel`, `DiffusionSampler`, `DiffusionTransformer` | The generation-loop owners and the diffusion architecture. |
| `Tokenizer` and implementations (`SentencePieceBPETokenizer`, byte-level BPE) | Text ↔ token-id conversion. See [Text Tokenization](../docs/internals/text-tokenization.md). |

### audio — synthesis and signal processing
Sound generation as `Cell` graphs stepped over time.

| Concept | Role |
|---|---|
| `CellFeatures`, `CellList` | The audio factory mixin and the hierarchical tick-ordered container of `Cell`s. The `Cell` interface itself is a `domain/graph` contract this module consumes. See [CellList Architecture](../docs/internals/celllist-architecture.md). |
| Oscillators, `AudioPassFilter`, envelopes | Signal generators and filters whose parameters are producers. |
| `WaveData`, `KeyboardTuning` | Sample storage with frequency analysis, and semitone→frequency lookup. |

### utils — the testing framework and cross-cutting services
Infrastructure every other module's tests depend on.

| Concept | Role |
|---|---|
| `TestSuiteBase`, `TestFeatures`, `@TestDepth` | The base class all tests extend, its assertion/kernel-test mixin, and the depth filter CI runs. |
| `ModelTestFeatures`, `Chart`, `KeyUtils` | ML test helpers, ASCII visualization, and cryptographic utilities. |

### utils-http — REST integration
A thin HTTP layer (`DefaultHttpEventDelivery` and authentication contracts) for event delivery
to distributed systems.

## What stays out

`engine` assembles; it does not redefine. External-runtime wrappers (ONNX, DJL, Groovy) live in
`extern`; multimedia arrangement and composition live in `studio`; the core math and the layer
definitions it trains stay in `compute` and `domain`. See [engine/CLAUDE.md](CLAUDE.md).
