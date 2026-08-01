# domain — Domain Models

This layer provides concrete domain models for graphics, neural networks, and simulation. These modules combine the foundation and mathematical layers to implement domain-specific systems — color and lighting, neural network graphs, 3D scene management, physical simulation, and evolutionary computation.

## Modules

### [ar-color](color/README.md)
RGB/RGBA color representation, light sources (point, ambient, directional), composable shader systems (diffuse, specular, blending), and texture mapping with multiple projection modes. Bridges low-level color arithmetic with high-level rendering through `ShaderContext` for dynamic scene lighting.

### [ar-heredity](heredity/README.md)
Genetic algorithms and evolutionary computation. Provides genes, chromosomes, and genomes for evolutionary optimization through breeding, crossover, and mutation. Supports flexible gene representations (numeric, projected, choice-based) and integrates with the `Producer`/`Factor` pattern for hardware acceleration. Used for material evolution, neural architecture search, and population-based optimization.

### [ar-graph](graph/README.md)
Neural network layers and computation graphs with automatic differentiation. Implements a cell-receptor-transmitter architecture for composable trainable layers with forward/backward propagation. Provides dense layers, activation functions, normalization (group norm, RMS norm), LoRA for parameter-efficient fine-tuning, and temporal processing for audio/sequence operations.

### [ar-physics](physics/README.md)
Quantum and classical physics simulation. Models atomic structures (electrons, orbitals, shells, quantum mechanics), photon fields for light propagation and absorption, rigid body dynamics, and physical constants. Integrates with chemistry for element construction and space for surface interactions.

### [ar-space](space/README.md)
3D scene management, meshes, and spatial acceleration. Provides surface primitives (spheres, planes, cylinders, cones, meshes), scene organization with lights and cameras, CSG operations (union, difference, intersection), spatial acceleration structures (BSP trees, KdTrees), and ray-surface intersection for rendering pipelines.

### [ar-chemistry](chemistry/README.md)
Periodic table and chemical element representations. Provides all 118 elements with electron configurations, organized by groups, periods, families, and blocks. Supports `Alloy` creation for probabilistic element mixing and integrates with physics for atomic modeling and heredity for evolutionary material composition.

