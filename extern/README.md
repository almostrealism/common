# extern — External Integrations

This layer provides integration modules that bridge the AR framework with external ML runtimes and scripting environments. Each module wraps a third-party library to extend ar-ml capabilities with specialized tokenization, inference engines, or dynamic model definition.

## Modules

### [ar-ml-djl](ml-djl/README.md)
DJL (Deep Java Library) integration for SentencePiece tokenization. Wraps the DJL SentencePiece tokenizer for language-independent subword tokenization used in models like T5 and BERT. Provides `SentencePieceTokenizer` implementing the core `Tokenizer` interface with encode/decode operations and vocabulary management.

### [ar-ml-onnx](ml-onnx/README.md)
ONNX Runtime integration for model inference. Handles tensor conversion between `OnnxTensor` and `PackedCollection`, session configuration with CPU parallelism and CoreML acceleration, and provides specialized implementations for audio: `OnnxAutoEncoder` (encoder/decoder), `OnnxDiffusionModel` (Diffusion Transformer with cross-attention), and `OnnxAudioConditioner` (conditioning models).

### [ar-ml-script](ml-script/README.md)
Groovy-based scripting for dynamic ML model definition. Provides the `Ops` singleton as a fluent DSL for constructing neural network architectures (convolution, pooling, dense, activation) without Java compilation. Supports interactive REPL experimentation, configuration-driven architecture definition, and command-line training pipelines via `ModelOptimizer`.
