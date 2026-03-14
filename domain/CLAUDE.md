# domain/ — Domain Models

Concrete domain models that combine foundation and mathematical layers into
specialized representations. Neural network graphs, color and lighting,
3D scene hierarchies, genetic algorithms, physics simulation, and more.

## What Belongs Here

- Neural network layers using the Cell/Receptor/Transmitter pattern (`graph`)
- RGB/RGBA colors, lighting models, shaders, textures (`color`)
- 3D scene representation, spatial hierarchy, object placement (`space`)
- Genetic algorithms, evolution, probabilistic factories (`heredity`)
- Atomic/molecular structures, forces, simulation (`physics`)
- Periodic table, chemical elements (`chemistry`)
- LLVM polyglot integration for code generation (`llvm`)
- StateDictionary-based weight management for neural network models

## What Does NOT Belong Here

- Training loops or optimization algorithms — those belong in `engine/optimize`
- Complete rendering pipelines or ray tracing — those belong in `engine/render`
- Transformer architectures or LLM inference — those belong in `engine/ml`
- External runtime wrappers (ONNX, DJL, Groovy) — those belong in `extern/`
- Audio synthesis or multimedia composition — those belong in `engine/audio` or `studio/`
- Core math types (Vector, Matrix, Ray) — those belong in `compute/`

## Key Conventions

- The `graph` module provides the Cell/Receptor/Transmitter pattern for computation graphs
- Use StateDictionary for all model weight management — never manage weights manually
- Never write training loops here — `ModelOptimizer` in `engine/optimize` owns those
- Domain models should compose foundation + math layer types, not duplicate them
- Color and lighting models define the math, not the rendering pipeline
- Space defines scene structure, not how scenes are rendered or traversed

## Modules

- [graph](graph/README.md) — Neural network layers, computation graphs, backpropagation
- [color](color/README.md) — RGB/RGBA colors, lighting models, shaders, textures
- [space](space/README.md) — 3D scene representation, objects, spatial hierarchy
- [heredity](heredity/README.md) — Genetic algorithms, evolution, probabilistic factories
- [physics](physics/README.md) — Atomic/molecular structures, simulation, forces
- [chemistry](chemistry/README.md) — Periodic table and chemical element representations
- [llvm](llvm/README.md) — LLVM polyglot integration for code generation
