# Almost Realism Graph Module (`ar-graph`)

The Graph Module provides the foundational architecture for building neural network layers and computation graphs in Almost Realism. It implements a cell-based design that enables flexible composition of forward and backward propagation paths, supporting both inference and training with automatic differentiation.

## Purpose

This module exists to:

1. **Enable Neural Network Construction** - Provides abstractions for building trainable neural network layers
2. **Support Backpropagation** - Implements automatic gradient computation through computation graphs
3. **Enable Flexible Composition** - Allows layers and blocks to be chained and composed seamlessly
4. **Provide Temporal Operations** - Supports time-based signal processing (audio, sequences)
5. **Abstract Hardware Execution** - Integrates with hardware acceleration layer for GPU/CPU execution

## Core Architecture

### Cell-Receptor-Transmitter Pattern

The fundamental design uses three interfaces:

- **Cell<T>** - A processing unit that can receive input, perform computation, and send output
- **Receptor<T>** - An interface for receiving data and producing computation operations
- **Transmitter<T>** - An interface for transmitting data to downstream receptors

```java
// Create a simple cell that doubles its input
Cell<PackedCollection<?>> doubleCell = Cell.of(input -> input.multiply(2.0));

// Chain cells together
Cell<PackedCollection<?>> pipeline = doubleCell
    .andThen(Cell.of(x -> x.add(c(1.0))))    // Add 1
    .andThen(Cell.of(x -> x.sqrt()));        // Square root

// Execute
Producer<PackedCollection<?>> input = cp(data);
Supplier<Runnable> operation = pipeline.push(input);
operation.get().run();  // Execute the computation
```

### Layer Hierarchy

**Layer** - Base interface for trainable components with weights
**CellularLayer** - Layer implemented using cells
**DefaultCellularLayer** - Primary implementation connecting forward and backward cells

### Block Composition

**Block** - Composable neural network unit with forward/backward propagation
**SequentialBlock** - Container for multiple blocks in sequence
**Model** - Top-level neural network container

## What It Provides

### 1. Layer Building Blocks

```java
import org.almostrealism.layers.*;
import static org.almostrealism.layers.LayerFeatures.*;

// Create a dense (fully connected) layer
TraversalPolicy inputShape = shape(784);
TraversalPolicy outputShape = shape(128);
PackedCollection<?> weights = new PackedCollection<>(shape(128, 784));
PackedCollection<?> bias = new PackedCollection<>(shape(128));

Block denseLayer = dense(weights, bias);

// Chain layers
Block network = layer(inputShape)
    .andThenDense(weights1, bias1)  // Hidden layer 1
    .andThenDense(weights2, bias2)  // Hidden layer 2
    .andThenDense(weights3, bias3); // Output layer
```

### 2. Model Construction

```java
import org.almostrealism.model.*;

// Create a model
Model model = new Model(shape(784), 0.001);  // Input shape, learning rate

// Add layers
model.add(denseLayer);
model.add(activationLayer);
model.add(outputLayer);

// Compile for execution
CompiledModel compiled = model.compile();

// Forward pass
PackedCollection<?> input = loadData();
PackedCollection<?> output = compiled.forward(input);

// Training (with gradient)
PackedCollection<?> gradient = computeLoss(output, target);
PackedCollection<?> inputGradient = compiled.backward(gradient);
```

### 3. Custom Cells

```java
import org.almostrealism.graph.*;

// Stateful cell (maintains internal state)
public class CustomCell extends CachedStateCell<PackedCollection<?>> {
    @Override
    public Supplier<Runnable> setup() {
        // Initialize state
        return () -> () -> {
            getCached().setMem(0, initialValue);
        };
    }

    @Override
    public Supplier<Runnable> push(Producer<PackedCollection<?>> protein) {
        // Process input and update state
        return () -> () -> {
            double value = protein.get().evaluate().toDouble(0);
            getCached().setMem(0, transform(value));
        };
    }

    @Override
    public Supplier<Runnable> tick() {
        // Propagate state to output
        return () -> () -> {
            getOutput().setMem(0, getCached().toDouble(0));
        };
    }
}
```

### 4. Temporal Processing

```java
import org.almostrealism.graph.temporal.*;

// Audio wave cell for signal processing
WaveCell audioCell = new WaveCell(sampleData, sampleRate);
audioCell.setAmplitude(1.0);
audioCell.setRepeat(true);

// Time-based iteration
TimeCell clock = new TimeCell();
clock.tick();  // Advance time
```

### 5. Block Composition Patterns

```java
// Sequential composition
Block sequential = block1.andThen(block2).andThen(block3);

// Branching (parallel paths)
Block branched = block.branch();

// Residual connection
Block residual = identity.accum(transformBlock);

// Element-wise operations
Block combined = block1.product(block2);  // Element-wise multiply
```

## Key Classes

### Core Interfaces
- **Cell** - `org.almostrealism.graph.Cell`
- **Receptor** - `org.almostrealism.graph.Receptor`
- **Transmitter** - `org.almostrealism.graph.Transmitter`
- **CellularPropagation** - `org.almostrealism.graph.CellularPropagation`

### Layer Classes
- **Layer** - `org.almostrealism.layers.Layer`
- **CellularLayer** - `org.almostrealism.layers.CellularLayer`
- **DefaultCellularLayer** - `org.almostrealism.layers.DefaultCellularLayer`
- **LayerFeatures** - `org.almostrealism.layers.LayerFeatures`

### Model Classes
- **Block** - `org.almostrealism.model.Block`
- **SequentialBlock** - `org.almostrealism.model.SequentialBlock`
- **Model** - `org.almostrealism.model.Model`
- **CompiledModel** - `org.almostrealism.model.CompiledModel`

### Specialized Cells
- **CachedStateCell** - `org.almostrealism.graph.CachedStateCell`
- **FilteredCell** - `org.almostrealism.graph.FilteredCell`
- **MultiCell** - `org.almostrealism.graph.MultiCell`
- **WaveCell** - `org.almostrealism.graph.temporal.WaveCell`
- **TimeCell** - `org.almostrealism.graph.TimeCell`

## Integration with Other Modules

### Collect Module
- Uses **PackedCollection<?>** as primary data type
- **TraversalPolicy** for shape/layout information
- **Producer/Evaluable** for lazy computation

### ML Module
- Provides foundation for high-level models (Llama, Qwen, etc.)
- **AttentionFeatures** builds on graph layers
- **StateDictionary** loads weights into graph layers

### Hardware Module
- Cells compile to hardware-accelerated operations
- GPU kernel generation for forward/backward passes

## Shape Validation

Layers enforce strict shape validation at creation time. When a layer operator is created, the framework validates that it produces the expected output shape. If there's a mismatch, an `IllegalArgumentException` is thrown immediately rather than allowing silent errors at runtime.

**Key validation methods in LayerFeatures:**
- `validateFactorShape()` - Validates operator output shape at layer creation
- `isShapeCompatible()` - Checks if two shapes are compatible (same dimensions, ignoring traversal axis)
- `into()` - Throws on shape mismatch when copying data between producers

**Layer Implementation Responsibility:** Each layer's operator must produce output matching its declared output shape. If internal computations (e.g., matmul) produce different shapes, the operator must include an explicit `.reshape(outputShape)` call.

## Environment Configuration

```bash
# Enable/disable various diagnostics
export AR_GRAPH_CELL_WARNINGS=true          # Warn on receptor replacement
export AR_GRAPH_IO_TRACKING=true            # Track layer inputs/outputs
export AR_GRAPH_PROPAGATION_WARNINGS=true   # Warn on backprop issues
export AR_GRAPH_SHAPE_WARNINGS=true         # Log shape information (validation always enabled)
```

## Usage Examples

### Simple Neural Network

```java
import org.almostrealism.model.*;
import org.almostrealism.layers.*;
import static org.almostrealism.layers.LayerFeatures.*;

// Input: 28x28 image = 784 pixels
TraversalPolicy inputShape = shape(784);

// Create model
Model mnist = new Model(inputShape, 0.01);  // Learning rate: 0.01

// Hidden layer: 784 -> 128 with ReLU
PackedCollection<?> w1 = initializeWeights(128, 784);
PackedCollection<?> b1 = zeros(128);
mnist.add(dense(w1, b1).andThen(relu()));

// Output layer: 128 -> 10 (digit classes)
PackedCollection<?> w2 = initializeWeights(10, 128);
PackedCollection<?> b2 = zeros(10);
mnist.add(dense(w2, b2));

// Compile
CompiledModel model = mnist.compile();

// Training loop
for (PackedCollection<?> batch : trainingData) {
    PackedCollection<?> predictions = model.forward(batch);
    PackedCollection<?> loss = computeLoss(predictions, labels);
    model.backward(loss);  // Updates weights automatically
}
```

### Custom Layer with Backpropagation

```java
public class CustomLayer implements CellularPropagation<PackedCollection<?>> {
    private final Cell<PackedCollection<?>> forward;
    private final Cell<PackedCollection<?>> backward;

    public CustomLayer(PackedCollection<?> weights) {
        // Forward: y = x * weights
        this.forward = Cell.of(input ->
            input.enumerate(1, 1).multiply(cp(weights)).sum(1)
        );

        // Backward: dx = gradient * weights^T
        this.backward = Cell.of(gradient ->
            gradient.enumerate(1, 1).multiply(cp(weights).transpose()).sum(1)
        );
    }

    @Override
    public Cell<PackedCollection<?>> getForward() { return forward; }

    @Override
    public Cell<PackedCollection<?>> getBackward() { return backward; }
}
```

## Dependencies

- **ar-relation** - Producer/Evaluable abstraction
- **ar-collect** - PackedCollection data structures
- **ar-algebra** - Vector/Matrix types
- **ar-hardware** - GPU compilation and execution
- **ar-code** - Code generation utilities

## Maven Dependency

```xml
<dependency>
    <groupId>org.almostrealism</groupId>
    <artifactId>ar-graph</artifactId>
    <!-- Check pom.xml for current version -->
</dependency>
```

## Further Reading

- See **ar-ml** module for high-level model implementations
- See **ar-collect** module for PackedCollection fundamentals
- See **ar-hardware** module for acceleration setup
