# Layer 6 — Multimedia Composition

This layer provides high-level multimedia composition: musical pattern systems, spatial audio/3D visualization, and scene orchestration. These modules combine signal processing, ML generation, and evolutionary optimization into complete production pipelines.

## Modules

### ar-music (`music/`)
Pattern-based music composition. Organizes audio samples into hierarchical musical patterns with multi-layer synthesis using scales, chord progressions, and genetic algorithm optimization. `PatternSystemManager` orchestrates rendering, `PatternLayerManager` manages up to 32 layers per pattern, and `PatternElement` represents individual musical events. Supports melodic and percussive modes with envelope control and automation.

### ar-spatial (`spatial/`)
Spatial audio visualization bridging frequency-domain analysis with 3D rendering. Converts time-series audio data into 3D spatial representations via `TemporalSpatialContext` (X=time, Y=frequency/channel, Z=layer). Provides pub/sub coordination through `SpatialDataHub` and `SoundDataHub`, enabling visualization of spectrograms and ML-generated audio with threshold-based frequency-to-3D-point conversion.

### ar-compose (`compose/`)
High-level orchestration for audio scene composition, arrangement, and generation. `AudioScene` manages tempo, patterns, effects chains, and automation. Integrates pattern-based music (ar-music), cell-based processing (ar-audio), ML audio generation (ar-ml), and genetic optimization (ar-heredity). Includes `MixdownManager` for effects routing, `AutomationManager` for parameter automation, and `AudioSceneOptimizer` for evolutionary optimization.
