# extern — Concept Map

The external-integrations layer. Each module here is a **thin adapter** that bridges the
framework to a third-party ML runtime or scripting engine, translating between AR types
(`PackedCollection`, `CollectionProducer`, `Tokenizer`, `Model`) and an external library's types.
The layer exists to isolate those dependencies: core logic stays in `engine/ml` and
`domain/graph`, and external types never leak into the public API of another layer.

Companion to [extern/README.md](README.md) and the summary map in
[docs/internals/concept-map.md](../docs/internals/concept-map.md).

## How this layer builds on the layers below

`extern` is a *consumer* of `engine/ml` and `domain/graph`: it implements their interfaces with
an external backend. The conceptual pattern is always the same — take an AR contract
(`Tokenizer`, a model that produces a `PackedCollection`) and satisfy it by delegating to a
native runtime, converting tensors at the boundary.

## Concepts each module contributes

### ml-djl — SentencePiece tokenization
| Concept | Role |
|---|---|
| `SentencePieceTokenizer` | Implements `engine/ml`'s `Tokenizer` by wrapping DJL's native `SpTokenizer` and a `.model` file. Distinct from `engine/ml`'s own `SentencePieceBPETokenizer` — see [Text Tokenization](../docs/internals/text-tokenization.md). |

### ml-onnx — ONNX Runtime inference
| Concept | Role |
|---|---|
| `OnnxAutoEncoder`, `OnnxDiffusionModel`, `OnnxAudioConditioner` | Expose ONNX models through AR interfaces, converting between `OnnxTensor` and `PackedCollection`. An alternative backend for the same model contracts `engine/ml` defines natively. |

### ml-script — Groovy model scripting
| Concept | Role |
|---|---|
| `Ops` | A fluent Groovy DSL (convolution, pooling, dense, activation) for building networks without Java compilation, delegating to `LayerFeatures`/`engine/ml`. Supports an interactive REPL and command-line training via `ModelOptimizer`. |

## The rule this layer encodes

Keep the wrapper thin. If a class does not directly reference DJL, ONNX or Groovy types, it does
not belong here — it belongs in `engine/ml` or `domain/graph`. Conversion between AR and external
types is confined to clear adapter methods at the boundary. See [extern/CLAUDE.md](CLAUDE.md).
