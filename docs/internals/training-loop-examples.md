# Training Loop Architecture - Detailed Examples

This document contains detailed code examples for the training loop architecture.
See the main [CLAUDE.md](../../CLAUDE.md) for the rules.

## Correct Pattern: Domain-Specific Training

```java
// CORRECT: Dataset handles domain-specific data preparation
public class DiffusionTrainingDataset implements Dataset<PackedCollection> {
    @Override
    public Iterator<ValueTarget<PackedCollection>> iterator() {
        return new Iterator<>() {
            @Override
            public ValueTarget<PackedCollection> next() {
                // Sample timestep, add noise, return (noisy_input, noise)
                return ValueTarget.of(noisyLatent, noise).withArguments(timestep);
            }
        };
    }
}

// CORRECT: Thin wrapper that configures and delegates to ModelOptimizer
public class DiffusionFineTuner {
    public Result fineTune(Dataset sourceData) {
        // 1. Create domain-specific dataset
        DiffusionTrainingDataset dataset = new DiffusionTrainingDataset(sourceData, scheduler);

        // 2. Create ModelOptimizer
        ModelOptimizer optimizer = new ModelOptimizer(model, () -> dataset);

        // 3. Configure it
        optimizer.setLossFunction(new MeanSquaredError(outputShape));
        optimizer.setLogFrequency(10);

        // 4. Call optimize ONCE - NO LOOP
        optimizer.optimize(epochs);

        // 5. Return
        return new Result(optimizer.getLoss(), optimizer.getTotalIterations());
    }
    // NO inner classes for progress. NO loops. NO forward/backward calls.
}
```

## Wrong Patterns (DO NOT USE)

```java
// WRONG: Any loop at all
public class DiffusionFineTuner {
    public void train(Dataset data) {
        for (int epoch = 0; epoch < epochs; epoch++) {        // WRONG! DELETE THIS LOOP
            optimizer.optimize(1);                             // WRONG! Don't wrap optimize in a loop
        }
    }
}

// WRONG: Custom progress class
public class DiffusionFineTuner {
    public static class TrainingProgress { ... }  // WRONG! Use ModelOptimizer's reporting
}

// WRONG: Calling forward/backward yourself
public void train() {
    model.forward(input);    // WRONG! ModelOptimizer does this
    model.backward(grad);    // WRONG! ModelOptimizer does this
}
```

## Historical Context

This rule exists because `AudioDiffusionFineTuner` was originally implemented with a duplicate training loop, violating this principle. See `/workspace/project/common/DESIGN_PROCESS_FAILURE.md` for the full analysis.
