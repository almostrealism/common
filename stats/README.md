# AR-Stats Module

**Probability distribution utilities and statistical sampling for graphics and physics simulations.**

## Overview

The `ar-stats` module provides utilities for working with probability distributions, particularly those used in physically-based rendering, Monte Carlo simulations, and statistical modeling.

## Core Components

### DistributionFeatures

Interface providing methods for sampling from probability distributions and applying statistical transformations.

#### Discrete Distribution Sampling

Sample from discrete probability distributions using inverse transform sampling:

```java
DistributionFeatures features = new DistributionFeatures() {};

// Create a probability distribution
PackedCollection<?> probs = new PackedCollection<>(3);
probs.set(0, 0.2);  // 20% probability
probs.set(1, 0.5);  // 50% probability
probs.set(2, 0.3);  // 30% probability

// Sample an index based on probabilities
int index = features.sample(probs);  // Returns 0, 1, or 2
```

**How it works:**
- Generates a uniform random value between 0 and 1
- Finds the first index where cumulative probability exceeds that value
- Returns the sampled index

**Applications:**
- Token sampling in language models (see ML module)
- Monte Carlo sampling in graphics
- Probabilistic selection in simulations

#### Softmax Function

Convert logits to probabilities using the softmax function:

```java
CollectionProducer<?> logits = ...;

// Standard softmax with max subtraction for numerical stability
CollectionProducer<?> probs = features.softmax(logits);

// Without max subtraction (less stable)
CollectionProducer<?> probs = features.softmax(logits, false);
```

**Softmax Formula:**
```
softmax(x_i) = exp(x_i) / sum(exp(x_j))
```

**Numerical Stability:**
By default, the maximum value is subtracted before exponentiation to prevent overflow:
```
softmax(x_i) = exp(x_i - max(x)) / sum(exp(x_j - max(x)))
```

**Applications:**
- Final layer activation in neural networks
- Converting scores to probabilities
- Attention mechanisms in transformers

### SphericalProbabilityDistribution

Interface for probability distributions over directions on a sphere.

```java
public interface SphericalProbabilityDistribution {
    Producer<Vector> getSample(double[] in, double[] orient);
}
```

**Parameters:**
- `in` - Incoming direction vector (typically normalized)
- `orient` - Surface orientation/normal vector (typically normalized)

**Returns:**
- A producer that generates a sampled outgoing direction

#### Use Cases

**Physically-Based Rendering:**
```java
// Define a BRDF as a spherical distribution
SphericalProbabilityDistribution brdf = new LambertianBRDF();

// Given an incoming light direction and surface normal
double[] incomingLight = {0.0, 1.0, 0.0};  // From above
double[] surfaceNormal = {0.0, 0.0, 1.0};   // Pointing up

// Sample an outgoing reflection direction
Producer<Vector> reflectedDir = brdf.getSample(incomingLight, surfaceNormal);
```

**Common Distribution Types:**
- **Lambertian (Diffuse):** Cosine-weighted hemisphere sampling
- **Specular:** Perfect mirror reflection
- **Glossy:** Distribution concentrated around specular direction
- **Transmission:** Refraction through transparent materials

### BRDF Interface

Interface for objects that have a Bidirectional Reflectance Distribution Function.

```java
public interface BRDF {
    SphericalProbabilityDistribution getBRDF();
    void setBRDF(SphericalProbabilityDistribution brdf);
}
```

**What is a BRDF?**

A BRDF describes how light is reflected at an opaque surface. It defines the relationship between:
- Incoming light direction
- Outgoing view direction
- Surface properties

BRDFs are fundamental to:
- Physically-based rendering
- Realistic material simulation
- Global illumination algorithms

**Example Implementation:**
```java
public class Material implements BRDF {
    private SphericalProbabilityDistribution brdf;

    public Material(SphericalProbabilityDistribution brdf) {
        this.brdf = brdf;
    }

    @Override
    public SphericalProbabilityDistribution getBRDF() {
        return brdf;
    }

    @Override
    public void setBRDF(SphericalProbabilityDistribution brdf) {
        this.brdf = brdf;
    }
}
```

## Integration with Other Modules

### ML Module

The stats module is used extensively in machine learning:

**Token Sampling:**
```java
// Convert logits to probabilities and sample
CollectionProducer<?> logits = model.forward(tokens);
CollectionProducer<?> probs = features.softmax(logits);

// Sample next token from probability distribution
PackedCollection<?> probArray = probs.get().evaluate();
int nextToken = features.sample(probArray);
```

**Temperature Scaling:**
```java
// Scale logits before softmax for controlled randomness
CollectionProducer<?> scaledLogits = logits.divide(temperature);
CollectionProducer<?> probs = features.softmax(scaledLogits);
```

### Rendering/Graphics Modules

Used for physically-based rendering and path tracing:

**Material System:**
```java
// Materials define how they reflect light via BRDFs
Material metal = new Material(new MicrofacetBRDF());
Material diffuse = new Material(new LambertianBRDF());
```

**Monte Carlo Rendering:**
```java
// Sample directions for path tracing
for (int i = 0; i < samples; i++) {
    Vector dir = brdf.getSample(incoming, normal).get().evaluate();
    // Trace ray in sampled direction
}
```

## Dependencies

```xml
<dependency>
    <groupId>org.almostrealism</groupId>
    <artifactId>ar-collect</artifactId>
</dependency>
<dependency>
    <groupId>org.almostrealism</groupId>
    <artifactId>ar-algebra</artifactId>
</dependency>
```

## Common Patterns

### Weighted Random Selection

```java
// Select from options with different weights
DistributionFeatures features = new DistributionFeatures() {};

PackedCollection<?> weights = new PackedCollection<>(4);
weights.set(0, 0.1);  // Option A: 10%
weights.set(1, 0.3);  // Option B: 30%
weights.set(2, 0.4);  // Option C: 40%
weights.set(3, 0.2);  // Option D: 20%

int choice = features.sample(weights);
```

### Monte Carlo Sampling

```java
// Generate samples for Monte Carlo integration
SphericalProbabilityDistribution distribution = ...;

List<Vector> samples = new ArrayList<>();
for (int i = 0; i < numSamples; i++) {
    Producer<Vector> sample = distribution.getSample(incoming, normal);
    samples.add(sample.get().evaluate());
}
```

### Top-K Sampling

```java
// Sample from top-k most probable options
DistributionFeatures features = new DistributionFeatures() {};

// Get top K indices and their probabilities
PackedCollection<?> topKProbs = ...;  // Renormalized probabilities

int sampledIndex = features.sample(topKProbs);
```

## Maven Dependency

```xml
<dependency>
    <groupId>org.almostrealism</groupId>
    <artifactId>ar-stats</artifactId>
    <version>0.72</version>
</dependency>
```

## License

Licensed under the Apache License, Version 2.0.
