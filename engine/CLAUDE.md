# engine/ — Applications & Training

Complete application systems, training infrastructure, inference pipelines,
audio synthesis, and testing framework. This layer assembles domain models
into working engines — training, rendering, inference, and audio.

## What Belongs Here

- Training infrastructure and optimization algorithms (`optimize`)
- Ray tracing engine, rendering pipeline, image output (`render`)
- Transformer architectures, LLM inference, tokenizers, attention (`ml`)
- Audio synthesis, signal processing, filters, sample playback (`audio`)
- Cross-module utilities and core framework services (`utils`)
- HTTP client utilities and REST integration (`utils-http`)
- ModelOptimizer: owns all training loops
- DiffusionSampler: owns all sampling loops
- TestSuiteBase: base class for all test classes

## What Does NOT Belong Here

- External runtime wrappers (ONNX, DJL) — those belong in `extern/`
- Multimedia composition, arrangement, or pattern sequencing — those belong in `studio/`
- Basic mathematical types (Vector, Matrix) — those belong in `compute/`
- Neural network layer definitions (Cell, Receptor) — those belong in `domain/graph`
- Core data structures (PackedCollection) — those belong in `base/collect`

## Key Conventions

- ModelOptimizer owns training loops — never write `for(int epoch...)` outside it
- DiffusionSampler owns sampling loops — never write `for(int step...)` outside it
- All test classes must extend TestSuiteBase
- Use `@TestDepth(2)` for expensive tests; use `if (skipLongTests) return;` for long-running tests
- Use the MCP test runner (`mcp__ar-test-runner__start_test_run`), never `mvn test` directly
- Audio module handles synthesis and signal processing; composition/arrangement is `studio/`

## Modules

- [optimize](optimize/README.md) — Optimization algorithms, ModelOptimizer, graph analysis
- [render](render/README.md) — Ray tracing, rendering pipeline, image output
- [ml](ml/README.md) — Large language models, transformers, attention, tokenizers
- [audio](audio/README.md) — Audio synthesis, signal processing, filters, sample playback
- [utils](utils/README.md) — Cross-module utilities and core framework services
- utils-http — HTTP client utilities and REST integration
