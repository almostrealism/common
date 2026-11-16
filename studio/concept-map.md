# studio — Concept Map

The multimedia-composition layer. It orchestrates the layers below into complete production
pipelines: pattern-based music, audio-scene arrangement and effects, spatial audio
visualization, and the place where a domain model meets the ML engine in a finished application
(biological connectomes run as trained networks). Composition works at a *higher abstraction than
synthesis*: it arranges, sequences and renders scenes, and `compose` generates audio from ML
models, but the synthesis primitives it assembles (oscillators, filters, envelopes) are defined
in `engine/audio`, not here.

Companion to [studio/README.md](README.md) and the summary map in
[docs/internals/concept-map.md](../docs/internals/concept-map.md).

## How this layer builds on the layers below

`studio` is an integration layer. It sequences `engine/audio` `Cell`s, renders over
`compute/time`, optimises arrangements with `engine/optimize`'s `PopulationOptimizer`, and (in
`compose` and `bioscience`) drives `engine/ml` models. Nothing below depends on it.

## Concepts each module contributes

### music — pattern-based composition
| Concept | Role |
|---|---|
| `PatternSystemManager` | Owns all pattern rendering; coordinates the layer managers. |
| `PatternLayerManager`, `PatternElement`, `PatternNote` | A pattern of up to 32 hierarchical layers, its individual musical events, and the sample adapters they resolve to. |
| `PatternFeatures`, `AudioSceneContext`, `ParameterizedEnvelope` | The rendering mixin, the measure/frame/destination context, and the ADSR envelope-generator contract (implemented by `ParameterizedVolumeEnvelope` and `ParameterizedFilterEnvelope`). |

### spatial — audio visualization
| Concept | Role |
|---|---|
| `TemporalSpatialContext` | Maps audio into 3D space (X = time, Y = frequency/channel, Z = layer). |
| `SpatialDataHub`, `SoundDataHub` | Pub/sub coordination for timeseries data, selection and playback. |
| `FrequencyTimeseries`, `GenomicNetwork` | Frequency-to-spatial conversion and visualization of ML-generated audio. |

### compose — audio-scene orchestration
| Concept | Role |
|---|---|
| `AudioScene` | The top-level composition orchestrator — tempo, patterns, effects, automation; owns the setup/tick/reset cycle. |
| `MixdownManager`, `AutomationManager`, `EfxManager` | Effects routing and master/stem output, parameter automation, and per-channel effects. |
| `AudioSceneOptimizer`, `AudioComposer` | Evolutionary optimization of scene parameters and autoencoder-based latent-space generation — the integration point for `engine/optimize` and `engine/ml`. |

### bioscience — connectomes as networks
Where a `domain` model (biology) meets the ML engine.

| Concept | Role |
|---|---|
| `ConnectomeNetwork` | Runs the spiking whole-brain model over a connectome, composing `ar-biology` neurons with a delay line and a sparse synaptic product in PDSL. |
| `NeuronConnectome`, `CellTypeConnectome` | Neuron-level and cell-type connectomes, stored as signed compressed-sparse-row matrices. |

## What stays out

Low-level synthesis (oscillators, filters, envelopes) is `engine/audio`; FFT and core signal
math are `compute/time`; model definitions and training are `engine/ml`/`engine/optimize`. See
[studio/CLAUDE.md](CLAUDE.md).
