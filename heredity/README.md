# Almost Realism Heredity Module (`ar-heredity`)

The Heredity Module provides a genetic algorithm and evolutionary computation framework built on the Producer/Factor pattern. It enables encoding data as genes, chromosomes, and genomes that can evolve through breeding and mutation operations.

## Purpose

This module exists to:

1. **Define Genetic Data Structures** - Gene, Chromosome, and Genome hierarchies
2. **Enable Evolutionary Operations** - Breeding/crossover and mutation mechanisms
3. **Support Probabilistic Selection** - Factory pattern for stochastic genetic choices
4. **Integrate with Temporal Systems** - Time-stepped evolution for optimization
5. **Provide Flexible Gene Representations** - Numeric, projected, choice, and transformable genes

## What It Provides

### 1. Genetic Hierarchy

```java
import org.almostrealism.heredity.*;

// Gene - Collection of factors
Gene<PackedCollection<?>> gene = HeredityFeatures.getInstance().g(0.5, 1.0, -0.3);

// Chromosome - Collection of genes
Chromosome<PackedCollection<?>> chromosome = HeredityFeatures.getInstance().c(gene1, gene2, gene3);

// Genome - Collection of chromosomes
Genome<PackedCollection<?>> genome = ...; // Custom implementation
```

**Hierarchy:**
```
Genome<T>
  └─ Contains multiple: Chromosome<T>
       └─ Contains multiple: Gene<T>
            └─ Contains multiple: Factor<T>
```

### 2. Gene Types

```java
import org.almostrealism.heredity.*;

// Numeric gene - Simple numeric factors
Gene<PackedCollection<?>> numericGene = HeredityFeatures.getInstance().g(0.1, 0.5, 0.9);

// Projected gene - Weighted transformation of source data
ProjectedGene projected = new ProjectedGene(sourceData, weights, outputDimension);

// Choice gene - Discrete selection from options
ChoiceGene choice = new ChoiceGene(underlyingGene, choices);

// Transformable gene - With custom transformations
TransformableGene transformable = new TransformableGene(length);
transformable.setTransform(pos, value -> sigmoid(value));
transformable.setTransform(value -> normalize(value));  // Global transform
```

### 3. Projected Genome (Advanced)

```java
import org.almostrealism.heredity.ProjectedGenome;

// Create genome with parameter collection
ProjectedGenome genome = new ProjectedGenome(parameterCount);

// Add chromosome
ProjectedChromosome chr = genome.addChromosome();

// Add genes with projection dimensions
ProjectedGene gene1 = chr.addGene(projectionDim);
ProjectedGene gene2 = chr.addGene(projectionDim);

// Initialize weights
genome.initWeights();
genome.refreshValues();

// Create variations (offspring with mutations)
ProjectedGenome offspring = genome.variation(
    min,              // Minimum parameter value
    max,              // Maximum parameter value
    mutationRate,     // Probability of mutation
    deltaSupplier     // Magnitude of mutations
);
```

### 4. Probabilistic Selection

```java
import org.almostrealism.chem.Alloy;
import org.almostrealism.chem.PeriodicTable;

// Alloy uses genes for probabilistic element selection
List<Atomic> components = Arrays.asList(
    PeriodicTable.Iron,
    PeriodicTable.Carbon,
    PeriodicTable.Nickel
);

// Create gene with probabilities (70% Fe, 20% C, 10% Ni)
Gene<PackedCollection<?>> composition = HeredityFeatures.getInstance().g(0.7, 0.2, 0.1);

// Alloy probabilistically selects components
Alloy steel = new Alloy(components, composition);
Atom randomElement = steel.construct();  // Selects based on gene values
```

### 5. Breeding and Crossover

```java
import org.almostrealism.heredity.*;

// Define breeding operation
ChromosomeBreeder<PackedCollection<?>> breeder = (c1, c2) -> {
    // Custom crossover logic
    Chromosome<PackedCollection<?>> offspring = ...;
    return offspring;
};

GenomeBreeder<PackedCollection<?>> genomeBreeder = (g1, g2) -> {
    // Combine genomes
    Genome<PackedCollection<?>> offspring = ...;
    return offspring;
};

// Breed two chromosomes
Chromosome<PackedCollection<?>> child = breeder.combine(parent1, parent2);
```

## Key Interfaces

### Gene

```java
public interface Gene<T> extends Plural<Factor<T>>, IntFunction<Factor<T>> {
    int length();                                    // Number of factors
    Factor<T> valueAt(int pos);                     // Get factor at position
    String signature();                              // Unique identifier

    Producer<T> getResultant(int pos, Producer<T> input);  // Apply factor to input
}
```

### Chromosome

```java
public interface Chromosome<T> extends Plural<Gene<T>>, IntFunction<Gene<T>> {
    int length();                         // Number of genes
    Gene<T> valueAt(int pos);            // Get gene at position
    Factor<T> valueAt(int gene, int factor);  // Direct factor access
    String signature();                   // Unique identifier

    void forEach(Consumer<Gene<T>> consumer);  // Iterate genes
}
```

### Genome

```java
public interface Genome<T> extends Plural<Chromosome<T>> {
    int count();                                  // Number of chromosomes
    Chromosome<T> valueAt(int chromosome);       // Get chromosome
    Gene<T> valueAt(int chromosome, int gene);   // Get specific gene
    Factor<T> valueAt(int chromosome, int gene, int factor);  // Get specific factor
    String signature();                           // Unique identifier
}
```

### Breeding Interfaces

```java
public interface ChromosomeBreeder<T> {
    Chromosome<T> combine(Chromosome<T> c1, Chromosome<T> c2);
}

public interface GenomeBreeder<T> {
    Genome<T> combine(Genome<T> g1, Genome<T> g2);
}
```

## Common Patterns

### Pattern 1: Creating Simple Genes and Chromosomes

```java
import static org.almostrealism.heredity.HeredityFeatures.*;

HeredityFeatures features = HeredityFeatures.getInstance();

// Create genes from scalar values
Gene<PackedCollection<?>> gene1 = features.g(0.1, 0.5, 0.9);
Gene<PackedCollection<?>> gene2 = features.g(-1.0, 0.0, 1.0);
Gene<PackedCollection<?>> gene3 = features.g(0.3, 0.7);

// Create chromosome from genes
Chromosome<PackedCollection<?>> chromosome = features.c(gene1, gene2, gene3);

// Access values
Factor<PackedCollection<?>> factor = chromosome.valueAt(0, 1);  // Gene 0, Factor 1
```

### Pattern 2: Population Evolution

```java
import org.almostrealism.optimize.PopulationOptimizer;

// Define optimizer (in ar-optimize module)
PopulationOptimizer<Genome, Temporal, Organism, Score> optimizer =
    new PopulationOptimizer<>(
        healthSupplier,           // Fitness evaluation
        genomeList -> new MyPopulation(genomeList),
        breederSupplier,          // Breeding strategy
        genomeGeneratorSupplier   // Random genome generation
    );

// Set initial population
optimizer.setPopulation(initialGenomes);

// Run evolution iterations
optimizer.optimize();
```

### Pattern 3: Projected Genome with Neural-Style Evolution

```java
// Create genome for neural network weights
ProjectedGenome genome = new ProjectedGenome(totalParameterCount);

// Add chromosomes for different layers
ProjectedChromosome layer1 = genome.addChromosome();
ProjectedChromosome layer2 = genome.addChromosome();

// Add genes (neurons/weights)
for (int i = 0; i < neuronCount; i++) {
    layer1.addGene(inputDimension);
    layer2.addGene(hiddenDimension);
}

// Initialize with random weights
genome.initWeights();

// Create offspring with mutations
ProjectedGenome child = genome.variation(
    -1.0,                    // Min weight value
    1.0,                     // Max weight value
    0.1,                     // 10% mutation rate
    () -> Math.random() * 0.2 - 0.1  // Mutation delta ±0.1
);
```

### Pattern 4: Choice-Based Genes

```java
// Define discrete choices
List<String> strategies = Arrays.asList("aggressive", "defensive", "balanced");

// Create choice gene
Gene<PackedCollection<?>> continuousGene = features.g(0.0, 1.0);  // 0-1 range
ChoiceGene choiceGene = new ChoiceGene(continuousGene, strategies.size());

// Use gene to select strategy
int choiceIndex = evaluateChoiceIndex(choiceGene);  // 0, 1, or 2
String selectedStrategy = strategies.get(choiceIndex);
```

### Pattern 5: Custom Transformations

```java
TransformableGene gene = new TransformableGene(10);

// Per-position transformation (e.g., sigmoid for position 0)
gene.setTransform(0, producer ->
    sigmoid(producer)
);

// Global transformation applied to all positions
gene.setTransform(producer ->
    normalize(producer)
);

// Resulting value: global(position-specific(raw-value))
```

### Pattern 6: Mutation with Bounded Perturbation

```java
import org.almostrealism.heredity.Breeders;

// Mutate value within bounds
double originalValue = 0.5;
double bounds1 = 0.0;
double bounds2 = 1.0;
double magnitude = 0.3;

double mutated = Breeders.perturbation(bounds1, bounds2, magnitude);
// Result: 0.5 + mutated (clamped to [0.0, 1.0])
```

## Integration with Other Modules

### Optimize Module
- **PopulationOptimizer** - Uses genomes and breeders for evolutionary algorithms
- **HealthComputation** - Evaluates genome fitness
- **Population** - Maps genomes to temporal organisms for evaluation

### Chemistry Module
- **Alloy** - Extends `ProbabilisticFactory<Atom>`
- Uses genes for element composition probabilities
- Example: Bronze (88% Cu, 12% Sn)

### Time Module
- **Temporal** interfaces - Enable time-stepped evolution
- **TemporalFactor** - Factors that change over time
- **Cellular** - Integration with graph module cells

### Graph Module
- **Cell<T>** - Implements `Cellular` for evolutionary computation graphs
- Cells can evolve their behavior over time

## Architecture

The heredity module is intentionally minimal, focusing on clean abstractions:

**Design Principles:**
- **Composability** - Genes compose into chromosomes, chromosomes into genomes
- **Flexibility** - Generic type T allows any data representation
- **Integration** - Works seamlessly with Producer/Factor pattern
- **Separation of Concerns** - Data structures separate from evolution algorithms
- **Extensibility** - Easy to create custom gene/chromosome/genome types

**What This Module Does NOT Provide:**
- Selection algorithms (fitness proportionate, tournament, etc.) - handled by optimize module
- Specific crossover algorithms - provided by user via breeders
- Mutation rate schedules - controlled externally
- Fitness functions - defined by user applications

## Dependencies

```xml
<dependency>
    <groupId>org.almostrealism</groupId>
    <artifactId>ar-time</artifactId>
    <version>0.72</version>
</dependency>

<dependency>
    <groupId>org.almostrealism</groupId>
    <artifactId>ar-relation</artifactId>
    <version>0.72</version>
</dependency>
```

## Maven Dependency

```xml
<dependency>
    <groupId>org.almostrealism</groupId>
    <artifactId>ar-heredity</artifactId>
    <version>0.72</version>
</dependency>
```

## Further Reading

- See **ar-optimize** module for population-based evolution algorithms
- See **ar-time** module for temporal evolution concepts
- See **ar-chemistry** module for probabilistic selection example (Alloy)
