# Almost Realism Quick Reference

> Condensed API reference for coding agents. For details, see module documentation.

## Environment (REQUIRED)

```bash
export AR_HARDWARE_LIBS=/tmp/ar_libs/   # Any writable directory
export AR_HARDWARE_DRIVER=native        # native|opencl|metal|external
```

---

## PackedCollection

### Creation
```java
// Shapes
new PackedCollection<>(shape(3))              // Vector [3]
new PackedCollection<>(shape(4, 4))           // Matrix [4x4]
new PackedCollection<>(shape(b, c, h, w))     // Tensor [batch, channels, height, width]

// From data
PackedCollection.of(1.0, 2.0, 3.0)            // From values
PackedCollection.factory().apply(shape, pos -> initValue)  // Factory
```

### Access
```java
collection.valueAt(0)                // Single value
collection.toDouble(0)               // As double
collection.range(shape, offset)      // Slice
collection.traverse(axis, visitor)   // Iterate axis
collection.toArray(double[].class)   // Export
collection.doubleStream()            // Stream
```

### Shape Operations
```java
collection.getShape()                // TraversalPolicy
collection.reshape(newShape)         // Reshape
collection.getMemLength()            // Total elements
shape.length(axis)                   // Axis size
shape.getTotalSize()                 // Total size
```

---

## Producer/Evaluable Pattern

```
Producer<T>  ──.get()──▶  Evaluable<T>  ──.evaluate()──▶  T
(describes)              (compiled)                    (result)
```

### Building Computations
```java
Producer<PackedCollection<?>> a = p(inputA);
Producer<PackedCollection<?>> b = p(inputB);
Producer<PackedCollection<?>> sum = a.add(b);           // Deferred
Producer<PackedCollection<?>> result = sum.multiply(c); // Chain
```

### Execution
```java
// One-shot
PackedCollection<?> value = producer.evaluate();

// Reusable
Evaluable<PackedCollection<?>> eval = producer.get();   // Compile once
PackedCollection<?> r1 = eval.evaluate(args1);          // Run many
PackedCollection<?> r2 = eval.evaluate(args2);
```

### Hardware Requirements
```java
import static org.almostrealism.hardware.ComputeRequirement.*;
producer.get(GPU);      // Force GPU
producer.get(CPU);      // Force CPU
```

---

## CollectionProducer Operations

### Arithmetic
| Op | Method | Notes |
|----|--------|-------|
| + | `a.add(b)` | Element-wise |
| - | `a.subtract(b)` | Element-wise |
| * | `a.multiply(b)` | Element-wise |
| / | `a.divide(b)` | Element-wise |
| - | `a.minus()` | Negate |
| ^ | `a.pow(n)` | Power |
| √ | `a.sqrt()` | Square root |
| exp | `a.exp()` | e^x |
| log | `a.log()` | Natural log |

### Reduction
| Op | Method | Notes |
|----|--------|-------|
| Σ | `a.sum()` | Sum all |
| Π | `a.product()` | Product all |
| max | `a.max()` | Maximum |
| min | `a.min()` | Minimum |
| mean | `a.sum().divide(c(count))` | Average |

### Matrix
| Op | Method | Notes |
|----|--------|-------|
| A×B | `a.matmul(b)` | Matrix multiply |
| Aᵀ | `a.transpose()` | Transpose |
| A·B | `a.dotProduct(b)` | Dot product |

### Shape
| Op | Method | Notes |
|----|--------|-------|
| reshape | `a.reshape(shape)` | Change shape |
| repeat | `a.repeat(n)` | Repeat n times |
| enumerate | `a.enumerate(axis, len)` | Expand axis |
| range | `a.range(shape, offset)` | Slice |
| traverse | `a.traverse(axis)` | Iterate |
| each | `a.each()` | Per-element |

### Comparison
| Op | Method | Notes |
|----|--------|-------|
| > | `a.greaterThan(b)` | 1.0 if true |
| ≥ | `a.greaterThanOrEqual(b)` | |
| < | `a.lessThan(b)` | |
| ≤ | `a.lessThanOrEqual(b)` | |
| = | `a.eq(b)` | Equality |

### Special
| Op | Method | Notes |
|----|--------|-------|
| softmax | `a.softmax()` | Softmax |
| relu | `a.max(c(0))` | ReLU |
| sigmoid | `c(1).divide(c(1).add(a.minus().exp()))` | Sigmoid |
| tanh | `a.tanh()` | Hyperbolic tan |

---

## Graph Module (Neural Networks)

### Cell-Receptor-Transmitter
```java
// Cell: computation unit
Cell<PackedCollection<?>> cell = new DenseLayer(inSize, outSize);

// Receptor: input handler
cell.setReceptor(receptor);

// Transmitter: output source
Receptor<?> downstream = cell.getTransmitter();
```

### Layer Types
| Layer | Class | Purpose |
|-------|-------|---------|
| Dense | `DenseLayer` | Fully connected |
| Softmax | `SoftmaxLayer` | Probability output |
| Pool | `Pool2d` | Spatial pooling |
| Norm | `RMSNorm` | RMS normalization |

### Model Building
```java
Model model = new Model(shape(inputDim));
model.addLayer(dense(hiddenDim));
model.addLayer(activation(RELU));
model.addLayer(dense(outputDim));
model.addLayer(softmax());

CompiledModel compiled = model.compile();
PackedCollection<?> output = compiled.forward(input);
```

### Block Composition
```java
Block block = new Block(shape(dim));
block.add(layer1);
block.add(layer2);
block.add(residual(innerBlock));  // Skip connection
```

---

## ML Module

### StateDictionary (Weight Loading)
```java
StateDictionary weights = new StateDictionary(weightsDir);
PackedCollection<?> embed = weights.get("model.embed_tokens.weight");
PackedCollection<?> wq = weights.get("model.layers.0.self_attn.q_proj.weight");
```

### Attention Pattern
```java
// In AttentionFeatures
attention(
    headCount, kvHeadCount, headSize,
    wq, wk, wv, wo,           // Projection weights
    qkNormQ, qkNormK,         // Optional QK-Norm (null if unused)
    freqCis,                  // RoPE frequencies
    position,                 // Position producer
    requirements              // GPU/CPU
);
```

### Common Weight Keys
```
model.embed_tokens.weight           # Token embeddings
model.layers.{i}.self_attn.q_proj.weight
model.layers.{i}.self_attn.k_proj.weight
model.layers.{i}.self_attn.v_proj.weight
model.layers.{i}.self_attn.o_proj.weight
model.layers.{i}.mlp.gate_proj.weight
model.layers.{i}.mlp.up_proj.weight
model.layers.{i}.mlp.down_proj.weight
model.layers.{i}.input_layernorm.weight
model.norm.weight                   # Final norm
lm_head.weight                      # Output projection
```

---

## Time Module

### FFT/IFFT
```java
PackedCollection<?> freqDomain = fft(timeDomain);
PackedCollection<?> timeDomain = ifft(freqDomain);
```

### FIR Filtering
```java
PackedCollection<?> coefficients = designLowPass(cutoff, sampleRate, taps);
PackedCollection<?> filtered = fir(signal, coefficients);
```

### Temporal Scalar
```java
TemporalScalar ts = new TemporalScalar();
ts.setClock(clock);
ts.setFrequency(440.0);  // Hz
double value = ts.getValue(time);
```

---

## Optimize Module

### Loss Functions
```java
Loss mse = Loss.mse();
Loss crossEntropy = Loss.crossEntropy();
Producer<?> loss = mse.apply(predicted, target);
```

### Optimizer
```java
Adam optimizer = new Adam(learningRate, beta1, beta2);
optimizer.step(parameters, gradients);
```

### Training Loop Pattern
```java
for (int epoch = 0; epoch < epochs; epoch++) {
    for (Batch batch : dataset) {
        PackedCollection<?> output = model.forward(batch.input());
        PackedCollection<?> loss = lossFunction.apply(output, batch.target());
        PackedCollection<?> gradients = model.backward(loss);
        optimizer.step(model.parameters(), gradients);
    }
}
```

---

## Hardware Module

### Backends
| Driver | Use Case |
|--------|----------|
| `native` | CPU with JNI acceleration |
| `opencl` | GPU via OpenCL |
| `metal` | Apple Silicon GPU |
| `external` | External executable |

### Hardware Context
```java
Hardware hw = Hardware.getLocalHardware();
hw.getComputeContext().getDataContext();  // Memory management
```

### Memory Management
```java
// Explicit memory control
MemoryData data = collection.getMemoryData();
data.reallocate(newSize);
data.destroy();  // Release
```

---

## Testing Pattern

```java
public class MyTest implements TestFeatures, ConsoleFeatures {
    @Test
    public void testOperation() {
        // Setup logging
        Console.root().addListener(OutputFeatures.fileOutput("results.txt"));

        // Test with hardware
        PackedCollection<?> a = tensor(shape(3, 3), (i, j) -> i + j);
        PackedCollection<?> b = tensor(shape(3, 3), (i, j) -> i * j);

        PackedCollection<?> result = a.add(b).evaluate();

        // Assertions
        assertEquals(expectedValue, result.valueAt(0), 1e-6);
        log("Test passed: " + result);
    }
}
```

### Run Tests
```bash
export AR_HARDWARE_LIBS=/tmp/ar_libs/ && \
export AR_HARDWARE_DRIVER=native && \
mvn test -pl <module> -Dtest=<TestName>
```

---

## Common Errors

| Error | Cause | Fix |
|-------|-------|-----|
| `NoClassDefFoundError: PackedCollection` | Missing env vars | Set AR_HARDWARE_LIBS, AR_HARDWARE_DRIVER |
| `Shape mismatch` | Incompatible dimensions | Check tensor shapes before operations |
| `OutOfMemoryError` | GPU memory exhausted | Reduce batch size, use CPU |
| `NullPointerException` in evaluate | Producer not compiled | Call `.get()` before `.evaluate()` |

---

## Import Cheatsheet

```java
// Core
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.TraversalPolicy;
import static org.almostrealism.collect.Shape.shape;

// Producers
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Evaluable;

// Operations
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.PairFeatures;

// Hardware
import org.almostrealism.hardware.Hardware;
import static org.almostrealism.hardware.ComputeRequirement.*;

// Graph
import org.almostrealism.graph.Cell;
import org.almostrealism.layers.DenseLayer;
import org.almostrealism.model.Model;

// ML
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.ml.AttentionFeatures;

// Testing
import org.almostrealism.util.TestFeatures;
import org.almostrealism.io.ConsoleFeatures;
```
