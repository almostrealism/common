# Almost Realism Optimize Module (`ar-optimize`)

The Optimize Module provides optimization algorithms for machine learning and evolutionary computation. It includes population-based evolutionary algorithms, gradient-based optimizers (Adam), loss functions, and complete training loops for neural networks.

## Purpose

This module exists to:

1. **Enable Evolutionary Algorithms** - Population-based optimization with genetic operators
2. **Provide Gradient Optimizers** - Adam optimizer for neural network training
3. **Implement Loss Functions** - MSE, MAE, and NLL for various tasks
4. **Support Model Training** - Complete training loops with datasets and early stopping
5. **Facilitate Health Evaluation** - Concurrent fitness computation for populations

## What It Provides

### 1. Population-Based Evolution

```java
import org.almostrealism.optimize.PopulationOptimizer;

// Create evolutionary optimizer
PopulationOptimizer<GenomeType, Temporal, OutputType, HealthScore> optimizer =
    new PopulationOptimizer<>(
        healthComputationSupplier,     // Fitness evaluation
        childrenFunction,              // Population constructor
        breederSupplier,               // Genome crossover/mutation
        genomeGeneratorSupplier        // Random genome generation
    );

// Configure
PopulationOptimizer.popSize = 100;
PopulationOptimizer.THREADS = 8;  // Parallel evaluation

// Set listeners
optimizer.setHealthListener((signature, score) ->
    log("Genome " + signature + ": " + score.getScore())
);

// Run evolution
for (int generation = 0; generation < 50; generation++) {
    optimizer.iterate();
    log("Gen " + generation +
        " avg=" + optimizer.getAverageScore() +
        " max=" + optimizer.getMaxScore());
}
```

### 2. Model Training with Datasets

```java
import org.almostrealism.optimize.ModelOptimizer;
import org.almostrealism.optimize.MeanSquaredError;

// Prepare dataset
List<ValueTarget<PackedCollection<?>>> data = new ArrayList<>();
for (int i = 0; i < 1000; i++) {
    PackedCollection<?> input = generateInput();
    PackedCollection<?> target = computeTarget(input);
    data.add(ValueTarget.of(input, target));
}

// Create optimizer
ModelOptimizer optimizer = new ModelOptimizer(
    model.compile(),
    () -> Dataset.of(data)
);

// Configure loss function
optimizer.setLossFunction(new MeanSquaredError(outputShape.traverseEach()));
optimizer.setLogFrequency(10);    // Log every 10 epochs
optimizer.setLossTarget(0.001);   // Early stopping threshold

// Train
optimizer.optimize(100);  // Up to 100 epochs

// Evaluate accuracy
double accuracy = optimizer.accuracy((expected, output) ->
    argmax(expected) == argmax(output)
);

System.out.println("Final loss: " + optimizer.getLoss());
System.out.println("Accuracy: " + (accuracy * 100) + "%");
```

### 3. Loss Functions

```java
import org.almostrealism.optimize.*;

// Mean Squared Error (regression)
LossProvider mse = new MeanSquaredError(traverseEach());
double loss = mse.loss(output, target);
Producer<PackedCollection<?>> gradient = mse.gradient(outputProducer, targetProducer);

// Mean Absolute Error (robust regression)
LossProvider mae = new MeanAbsoluteError(traverseEach());

// Negative Log Likelihood (classification)
LossProvider nll = new NegativeLogLikelihood(traverseEach());
```

### 4. Adam Optimizer

```java
import org.almostrealism.optimize.AdamOptimizer;

// Create Adam optimizer
AdamOptimizer adam = new AdamOptimizer(
    0.001,   // Learning rate
    0.9,     // Beta1 (momentum decay)
    0.999    // Beta2 (velocity decay)
);

// Apply parameter updates
Supplier<Runnable> updateOp = adam.apply(
    "layer.weights",
    weightsProducer,
    gradientsProducer
);

// Execute update
updateOp.get().run();
```

### 5. Dataset Management

```java
import org.almostrealism.model.Dataset;
import org.almostrealism.model.ValueTarget;

// Create dataset from pairs
Dataset<PackedCollection<?>> dataset = Dataset.of(inputOutputPairs);

// Split train/validation (80/20)
List<Dataset<PackedCollection<?>>> splits = dataset.split(0.8);
Dataset<PackedCollection<?>> train = splits.get(0);
Dataset<PackedCollection<?>> validation = splits.get(1);

// Batch processing
Dataset<PackedCollection<?>> batched = dataset.batch(32);

// Functional dataset (on-the-fly generation)
Dataset<PackedCollection<?>> functional = Dataset.of(
    inputs,
    input -> computeTarget(input)
);
```

### 6. Health Computation

```java
import org.almostrealism.optimize.*;

// Implement fitness evaluation
public class MyHealthComputation implements HealthComputation<MyOrganism, MyScore> {
    private MyOrganism target;

    @Override
    public void setTarget(MyOrganism t) {
        this.target = t;
    }

    @Override
    public MyScore computeHealth() {
        // Evaluate fitness
        double fitness = evaluateFitness(target);
        return new MyScore(fitness);
    }

    @Override
    public void reset() {
        // Clean up between evaluations
    }
}

// Combine multiple metrics
AverageHealthComputationSet<MyOrganism> composite =
    new AverageHealthComputationSet<>();
composite.add(accuracyHealth);
composite.add(speedHealth);
composite.add(robustnessHealth);

// Average all metrics
HealthScore combined = composite.computeHealth();
```

## Key Interfaces

### PopulationOptimizer

```java
public class PopulationOptimizer<G, T extends Temporal, O, S extends HealthScore> {
    // Configuration
    public static int THREADS = 1;
    public static int popSize = 100;
    public static int maxChildren = 110;
    public static double lowestHealth = 0.0;
    public static boolean enableBreeding = true;

    // Main methods
    public void setPopulation(Population<G, O> population);
    public void iterate();  // One evolutionary cycle
    public double getAverageScore();
    public double getMaxScore();

    // Listeners
    public void setHealthListener(BiConsumer<String, S> listener);
    public void setErrorListener(Consumer<Exception> listener);
}
```

### ModelOptimizer

```java
public class ModelOptimizer {
    public ModelOptimizer(CompiledModel model, Supplier<Dataset<?>> dataset);

    public void setLossFunction(LossProvider loss);
    public void setLogFrequency(int frequency);
    public void setLossTarget(double target);

    public void optimize(int maxIterations);
    public double accuracy(BiPredicate<PackedCollection<?>, PackedCollection<?>> validator);

    public double getLoss();
    public int getTotalIterations();
}
```

### LossProvider

```java
public interface LossProvider {
    double loss(PackedCollection<?> output, PackedCollection<?> target);

    Producer<PackedCollection<?>> gradient(
        Producer<PackedCollection<?>> output,
        Producer<PackedCollection<?>> target
    );
}
```

### HealthComputation

```java
public interface HealthComputation<T extends Temporal, S extends HealthScore>
    extends Lifecycle {
    void setTarget(T target);
    S computeHealth();

    // Lifecycle methods
    void init();
    void reset();
    void destroy();
}
```

### HealthScore

```java
public interface HealthScore {
    double getScore();  // Fitness value (typically 0.0-1.0)
}
```

## Common Patterns

### Pattern 1: Training a Classification Model

```java
// Prepare training data
List<ValueTarget<PackedCollection<?>>> trainingData = loadMNIST();
Dataset<PackedCollection<?>> dataset = Dataset.of(trainingData);

// Build model
Model model = new Model(shape(784));
model.add(dense(weights1, bias1));
model.add(relu());
model.add(dense(weights2, bias2));
model.add(softmax());

// Create optimizer
ModelOptimizer optimizer = new ModelOptimizer(
    model.compile(),
    () -> dataset
);

// Configure for classification
optimizer.setLossFunction(new NegativeLogLikelihood(shape(10).traverseEach()));
optimizer.setLogFrequency(1);
optimizer.setLossTarget(0.01);

// Train
optimizer.optimize(50);

// Evaluate
double accuracy = optimizer.accuracy((expected, output) -> {
    int expectedClass = argmax(expected);
    int predictedClass = argmax(output);
    return expectedClass == predictedClass;
});

System.out.println("Test accuracy: " + (accuracy * 100) + "%");
```

### Pattern 2: Evolutionary Algorithm

```java
// Define genome generator
Supplier<Genome<PackedCollection<?>>> generator = () -> {
    ProjectedGenome genome = new ProjectedGenome(paramCount);
    genome.initWeights();
    return genome;
};

// Define breeder (crossover + mutation)
Supplier<GenomeBreeder<PackedCollection<?>>> breeder = () -> (g1, g2) -> {
    ProjectedGenome offspring = g1.crossover(g2);
    offspring.mutate(0.1, () -> Math.random() * 0.2 - 0.1);
    return offspring;
};

// Define health computation
Supplier<HealthComputation<MyOrganism, MyScore>> healthSupplier = () ->
    new MyHealthComputation();

// Create population function
Function<List<Genome>, Population> population = genomes ->
    new MyPopulation(genomes);

// Create optimizer
PopulationOptimizer optimizer = new PopulationOptimizer(
    healthSupplier,
    population,
    breeder,
    generator
);

// Configure
PopulationOptimizer.popSize = 200;
PopulationOptimizer.THREADS = 16;
PopulationOptimizer.secondaryOffspringPotential = 0.5;

// Run evolution
for (int gen = 0; gen < 100; gen++) {
    optimizer.iterate();
    log("Generation " + gen +
        ": avg=" + optimizer.getAverageScore() +
        ", max=" + optimizer.getMaxScore());
}
```

### Pattern 3: Custom Loss Function

```java
public class HuberLoss implements LossProvider {
    private double delta;
    private CollectionProducer<?> shape;

    public HuberLoss(CollectionProducer<?> shape, double delta) {
        this.shape = shape;
        this.delta = delta;
    }

    @Override
    public double loss(PackedCollection<?> output, PackedCollection<?> target) {
        double totalLoss = 0.0;
        for (int i = 0; i < output.getMemLength(); i++) {
            double diff = Math.abs(output.toDouble(i) - target.toDouble(i));
            if (diff <= delta) {
                totalLoss += 0.5 * diff * diff;
            } else {
                totalLoss += delta * (diff - 0.5 * delta);
            }
        }
        return totalLoss / output.getMemLength();
    }

    @Override
    public Producer<PackedCollection<?>> gradient(...) {
        // Huber gradient: linear for |error| > delta, quadratic otherwise
        // Implementation details...
    }
}
```

### Pattern 4: Train/Validation Split

```java
// Load full dataset
Dataset<PackedCollection<?>> fullDataset = loadData();

// Split 80% train, 20% validation
List<Dataset<PackedCollection<?>>> splits = fullDataset.split(0.8);
Dataset<PackedCollection<?>> trainSet = splits.get(0);
Dataset<PackedCollection<?>> validationSet = splits.get(1);

// Train on train set
ModelOptimizer trainer = new ModelOptimizer(
    model.compile(),
    () -> trainSet
);
trainer.optimize(50);

// Evaluate on validation set
ModelOptimizer validator = new ModelOptimizer(
    model.compile(),
    () -> validationSet
);
double validationAccuracy = validator.accuracy(accuracyPredicate);
```

### Pattern 5: Multi-Metric Optimization

```java
// Define multiple health computations
AverageHealthComputationSet<MyOrganism> healthSet =
    new AverageHealthComputationSet<>();

// Add different metrics
healthSet.add(new AccuracyHealth());
healthSet.add(new SpeedHealth());
healthSet.add(new EnergyEfficiencyHealth());

// Use composite in population optimizer
Supplier<HealthComputation<MyOrganism, HealthScore>> health =
    () -> healthSet;

PopulationOptimizer optimizer = new PopulationOptimizer(
    health,
    population,
    breeder,
    generator
);

// Evolution optimizes average of all metrics
```

## Integration with Other Modules

### Heredity Module
- Uses **Genome**, **Chromosome**, **Gene** for genetic algorithms
- **GenomeBreeder** for crossover and mutation
- **ProjectedGenome** for neural architecture search

### Graph Module
- **Model** and **CompiledModel** for neural networks
- **Block** for layer composition
- Forward and backward pass integration

### Collect Module
- **PackedCollection** for tensor data
- **TraversalPolicy** for shapes
- **Producer/Evaluable** for computation graphs

### Hardware Module
- **ComputeContext** for GPU acceleration
- **MemoryData** for efficient memory management
- Kernel compilation for loss functions

## Configuration

### Population Optimizer

```java
// Population size
PopulationOptimizer.popSize = 100;

// Maximum children (110% of population)
PopulationOptimizer.maxChildren = 110;

// Parallel evaluation threads
PopulationOptimizer.THREADS = 8;

// Offspring probabilities
PopulationOptimizer.secondaryOffspringPotential = 0.25;
PopulationOptimizer.tertiaryOffspringPotential = 0.25;
PopulationOptimizer.quaternaryOffspringPotential = 0.25;

// Fitness threshold
PopulationOptimizer.lowestHealth = 0.0;

// Enable/disable features
PopulationOptimizer.enableBreeding = true;
PopulationOptimizer.enableVerbose = false;
PopulationOptimizer.enableDisplayGenomes = false;
```

### Adam Optimizer

```java
AdamOptimizer adam = new AdamOptimizer(
    0.001,    // Learning rate (alpha)
    0.9,      // Beta1 (exponential decay for first moment)
    0.999     // Beta2 (exponential decay for second moment)
);
// Epsilon: 1e-7 (numerical stability, hardcoded)
```

## Dependencies

```xml
<dependency>
    <groupId>org.almostrealism</groupId>
    <artifactId>ar-graph</artifactId>
    <version>0.72</version>
</dependency>
```

## Maven Dependency

```xml
<dependency>
    <groupId>org.almostrealism</groupId>
    <artifactId>ar-optimize</artifactId>
    <version>0.72</version>
</dependency>
```

## Further Reading

- See **ar-graph** module for Model and CompiledModel
- See **ar-heredity** module for Genome and genetic operators
- See **ar-ml** module for transformer model training
