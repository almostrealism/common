# compute/ — Mathematical Domains

Typed mathematical abstractions built on the foundation layer's PackedCollection
and CollectionProducer. Provides vectors, matrices, geometric primitives,
statistical distributions, and temporal/frequency-domain processing.

## What Belongs Here

- Vector, Matrix, Scalar, and algebraic operation types
- Geometric primitives: Ray, TransformMatrix, coordinate transforms
- Statistical distributions, probability operations, sampling
- Temporal processing, frequency analysis, FFT operations
- Mathematical operations that produce CollectionProducers for hardware acceleration
- Type-safe wrappers that give semantic meaning to PackedCollection data

## What Does NOT Belong Here

- Neural network layers or graph structures — those belong in `domain/graph`
- Rendering pipelines or ray tracing engines — those belong in `engine/render`
- Audio effects, synthesis, or signal processing chains — those belong in `engine/audio`
- Training loops or model optimization — those belong in `engine/optimize`
- Application-specific logic that references models, scenes, or compositions

## Key Conventions

- All operations should work through CollectionProducer for hardware acceleration
- No raw array manipulation — use the Producer pattern from `base/collect`
- These types are building blocks: they should be composable and domain-agnostic
- Geometric types define shapes and transforms, not how to render them
- Statistical types define distributions and operations, not how to train with them
- Time/frequency types define signal transforms, not audio-specific processing

## Modules

- [algebra](algebra/README.md) — Vector, matrix, scalar, and algebraic operations
- [geometry](geometry/README.md) — Rays, vectors, transformations, ray-tracing infrastructure
- [stats](stats/README.md) — Statistical operations and probability distributions
- [time](time/README.md) — Temporal operations, timing, frequency, FFT, sequences
