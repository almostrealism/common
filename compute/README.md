# compute — Mathematical Domains

This layer provides typed mathematical abstractions built on top of the foundation layer's `PackedCollection` and `Producer` primitives. Each module defines a specific mathematical domain — linear algebra, spatial geometry, probability, or temporal processing — giving higher layers a rich vocabulary for expressing domain-specific computations with full hardware acceleration.

## Modules

### [ar-algebra](algebra/README.md)
Linear algebra and hardware-accelerated numerical computing. Provides `CollectionProducer` operations (arithmetic, aggregation, reshaping), automatic differentiation, and `CollectionFeatures` factory methods for constructing computational graphs. The core infrastructure powering all numerical computations across the framework.

### [ar-geometry](geometry/README.md)
3D primitives and ray tracing infrastructure. Defines `Ray` (origin + direction), `TransformMatrix` (4x4 homogeneous transformations), `BasicGeometry` (position/rotation/scale), camera systems (`PinholeCamera`, `OrthographicCamera`), and ray-surface intersection interfaces. Bridges abstract math with rendering and physics systems.

### [ar-stats](stats/README.md)
Probability distributions and statistical sampling. Includes `DistributionFeatures` (discrete sampling via inverse transform), softmax functions, `SphericalProbabilityDistribution` for directional sampling on unit spheres, and BRDF interfaces for physically-based rendering. Used in ML for token sampling and temperature scaling, and in rendering for Monte Carlo path tracing.

### [ar-time](time/README.md)
Temporal processing with hardware-accelerated signal analysis. Provides `Temporal` (tick-based execution), `TemporalRunner` (setup/tick orchestration), `TimeSeries`/`AcceleratedTimeSeries` for time-indexed storage, Fourier transforms (FFT/IFFT), multi-order FIR filters, interpolation, and timing regularization. Enables real-time audio pipelines, animation loops, and frequency-domain analysis.
