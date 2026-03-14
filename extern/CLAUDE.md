# extern/ — External Integrations

Thin wrapper modules that bridge the AR framework with third-party ML runtimes
and scripting engines. These modules translate between AR types (PackedCollection,
CollectionProducer) and external library types.

## What Belongs Here

- DJL framework integration for model loading and inference (`ml-djl`)
- ONNX runtime integration for inference (`ml-onnx`)
- Groovy script execution for model scripting (`ml-script`)
- Bridge code that converts PackedCollection to/from external tensor types
- Adapter classes that expose external model capabilities through AR interfaces

## What Does NOT Belong Here

- Core ML logic, transformer architectures, or attention mechanisms — those belong in `engine/ml`
- Neural network layer definitions (Cell, Receptor) — those belong in `domain/graph`
- Training loops or optimization — those belong in `engine/optimize`
- General utilities or framework services — those belong in `engine/utils`
- Any module that does not import an external runtime library

## Key Conventions

- Keep wrappers thin — core logic stays in `engine/ml` or `domain/graph`
- These modules exist to isolate external dependencies from the rest of the framework
- Conversion between AR types and external types should be encapsulated in clear adapter methods
- Never leak external library types into the public API of other layers
- If a class does not directly reference DJL, ONNX, or Groovy types, it does not belong here

## Modules

- ml-djl — DJL framework integration for model loading
- ml-onnx — ONNX runtime integration for inference
- [ml-script](ml-script/README.md) — Groovy script execution for model scripting
