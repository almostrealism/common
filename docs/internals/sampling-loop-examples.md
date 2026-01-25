# Sampling Loop Architecture - Detailed Examples

This document contains detailed code examples for the diffusion sampling architecture.
See the main [CLAUDE.md](../../CLAUDE.md) for the rules.

## Correct Pattern: Audio Generation

```java
// CORRECT: Thin wrapper that configures and delegates to DiffusionSampler
public class AudioDiffusionGenerator {
    private final DiffusionSampler sampler;
    private final AutoEncoder autoEncoder;

    public AudioDiffusionGenerator(CompiledModel model, AutoEncoder autoEncoder,
                                   DiffusionNoiseScheduler scheduler, TraversalPolicy latentShape) {
        // Create sampler - IT OWNS THE LOOP
        this.sampler = new DiffusionSampler(
            model::forward,
            new DDIMSamplingStrategy(scheduler),
            scheduler.getNumSteps(),
            latentShape
        );
        this.autoEncoder = autoEncoder;
    }

    public WaveData generate(long seed) {
        // Delegate to DiffusionSampler - NO LOOP HERE
        PackedCollection latent = sampler.sample(seed);

        // Decode to audio
        return decodeLatent(latent);
    }
    // NO inner loops. NO custom timestep scheduling. NO inline sampling math.
}
```

## Wrong Pattern (DO NOT USE)

```java
// WRONG: Any timestep loop at all
public class AudioGenerator {
    public PackedCollection generate(long seed) {
        for (int step = 0; step < numSteps; step++) {        // WRONG! DELETE THIS LOOP
            output = model.forward(x, t, conditioning);       // WRONG! Sampler does this
            x = updateSample(x, output, t, tPrev);            // WRONG! Strategy does this
        }
        return x;
    }
}
```

## AutoEncoder Abstraction

Use the `AutoEncoder` interface for encoding/decoding audio. Do NOT assume `CompiledModel`:

```java
// CORRECT: Works with any AutoEncoder implementation
public AudioGenerator(AutoEncoder autoEncoder) {
    this.autoEncoder = autoEncoder;  // Could be OnnxAutoEncoder, CompiledModelAutoEncoder, etc.
}

// If you have a CompiledModel decoder, wrap it:
AutoEncoder autoEncoder = new CompiledModelAutoEncoder(
    compiledDecoder, sampleRate, latentSampleRate, maxDuration
);
```

## Historical Context

This rule exists because both `AudioGenerator` and `AudioDiffusionGenerator` were originally implemented with duplicate sampling loops. Both have been corrected to use `DiffusionSampler` with appropriate `SamplingStrategy` implementations.
