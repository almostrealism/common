# engine — Applications & Training

This layer provides high-level application systems that combine domain models into complete pipelines: rendering engines, ML training and inference, optimization algorithms, and cross-cutting framework utilities.

## Modules

### [ar-optimize](optimize/README.md)
Optimization algorithms for machine learning and evolutionary computation. Implements population-based evolutionary algorithms with genetic operators, the Adam optimizer for neural network training, loss functions (MSE, MAE, NLL), and complete training loops with datasets via `ModelOptimizer`. Integrates with ar-graph for models and ar-heredity for genetic components.

### [ar-render](render/README.md)
Complete ray tracing rendering engine. Supports classical ray-surface intersection with multi-light scenes, shadows, reflection, refraction, and anti-aliasing via supersampling. Key components include `RayTracedScene`, `RayIntersectionEngine`, and `LightingEngine` for material shading (diffuse, specular, mirror, glass). Integrates with space, color, and geometry modules.

### [ar-ml](ml/README.md)
Large language model framework with hardware acceleration. Implements transformer architectures (Qwen3) with multi-head/grouped-query attention, QK-normalization, rotary position embeddings (RoPE), and autoregressive generation. Includes `StateDictionary` for protobuf weight loading, byte-level BPE tokenizers, and diffusion model support with LoRA fine-tuning.

### [ar-utils](utils/README.md)
Cross-cutting framework infrastructure and testing. `TestSuiteBase` provides the foundation for all tests with depth filtering and parallel execution via `@TestDepth`. Includes `ModelTestFeatures` for ML testing, `Chart` for ASCII visualization, `KeyUtils` for cryptographic operations, and distributed test execution support.

### [ar-utils-http](utils-http/README.md)
HTTP client utilities and REST integration. Implements `DefaultHttpEventDelivery` for sending events via HTTP POST with JSON serialization. Provides event delivery infrastructure for distributed systems and webhook integration.
