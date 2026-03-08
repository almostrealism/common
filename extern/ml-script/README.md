# Almost Realism ML-Script Module (`ar-ml-script`)

The ML-Script Module provides a Groovy-based DSL (Domain-Specific Language) for dynamically defining and executing machine learning models without Java compilation. It enables rapid prototyping and experimentation with neural network architectures using a scripting interface.

## Purpose

This module exists to:

1. **Enable Dynamic Model Creation** - Define models via Groovy scripts without recompilation
2. **Provide Fluent API** - Simple, readable syntax for layer construction
3. **Support Rapid Prototyping** - Quick iteration on model architectures
4. **Integrate with AR-ML** - Full access to Almost Realism ML capabilities
5. **Facilitate Experimentation** - Interactive model development and testing

## What It Provides

### 1. Groovy-Based Model Construction

```groovy
import org.almostrealism.Ops
import org.almostrealism.model.Model
import org.almostrealism.collect.PackedCollection

// Get operations interface
def ml = Ops.ops()

// Create model with input shape
def model = new Model(ml.shape(28, 28))

// Add layers using fluent API
model.addLayer(ml.convolution2d(1, 32))  // 1 input channel, 32 output
model.addLayer(ml.pool2d(2))             // 2x2 pooling
model.add(ml.flatten())                  // Flatten for dense layers
model.addLayer(ml.dense(128))            // Fully connected layer
model.addLayer(ml.softmax())             // Output layer

// Setup and run
model.setup().get().run()

// Inference
def input = new PackedCollection(ml.shape(28, 28))
input.fill(Math::random)
def output = model.forward(input)
```

### 2. Layer Operations

```groovy
def ml = Ops.ops()

// Convolutional layers
def conv = ml.convolution2d(inputChannels, outputChannels)

// Pooling layers
def maxPool = ml.pool2d(poolSize)

// Dense/Fully connected layers
def dense = ml.dense(outputSize)

// Activation functions
def relu = ml.relu()
def softmax = ml.softmax()

// Utility operations
def flatten = ml.flatten()
def reshape = ml.reshape(newShape)
```

### 3. Shape Specification

```groovy
def ml = Ops.ops()

// 1D shape (vector)
def vector = ml.shape(784)

// 2D shape (image/matrix)
def image = ml.shape(28, 28)

// 3D shape (channels, height, width)
def colorImage = ml.shape(3, 256, 256)

// 4D shape (batch, channels, height, width)
def batch = ml.shape(32, 3, 64, 64)
```

## Key Classes

### Ops

```groovy
public class Ops implements LayerFeatures {
    public static Ops ops();  // Get singleton instance

    // Layer construction
    Block convolution2d(int inputChannels, int outputChannels);
    Block pool2d(int poolSize);
    Block flatten();
    Block dense(int outputSize);
    Block softmax();
    Block relu();

    // Shape utilities
    TraversalPolicy shape(int... dimensions);
}
```

### Model (from ar-graph)

```groovy
public class Model implements Setup, Destroyable {
    Model(TraversalPolicy inputShape);

    void add(Block layer);
    void addLayer(Block layer);

    Supplier<Runnable> setup();
    CompiledModel compile();

    PackedCollection<?> forward(PackedCollection<?> input);
}
```

## Common Patterns

### Pattern 1: Simple CNN for MNIST

```groovy
import org.almostrealism.Ops
import org.almostrealism.model.Model

def ml = Ops.ops()

// Define architecture
def model = new Model(ml.shape(28, 28, 1))

// Convolutional block 1
model.addLayer(ml.convolution2d(1, 32))
model.addLayer(ml.relu())
model.addLayer(ml.pool2d(2))

// Convolutional block 2
model.addLayer(ml.convolution2d(32, 64))
model.addLayer(ml.relu())
model.addLayer(ml.pool2d(2))

// Classifier
model.add(ml.flatten())
model.addLayer(ml.dense(128))
model.addLayer(ml.relu())
model.addLayer(ml.dense(10))
model.addLayer(ml.softmax())

// Compile
model.setup().get().run()
def compiled = model.compile()

println "Model ready for inference"
```

### Pattern 2: ResNet-style Skip Connections

```groovy
def ml = Ops.ops()
def model = new Model(ml.shape(64, 64, 3))

// Main path
def conv1 = ml.convolution2d(3, 64)
def relu1 = ml.relu()

// Skip connection (identity)
def skip = ml.identity()

// Residual block
model.add(conv1.andThen(relu1).accum(skip))

// accum() adds skip connection output to main path
```

### Pattern 3: Dynamic Model from Configuration

```groovy
// Model config loaded from file/database
def config = [
    inputShape: [224, 224, 3],
    layers: [
        [type: 'conv2d', in: 3, out: 64],
        [type: 'pool2d', size: 2],
        [type: 'conv2d', in: 64, out: 128],
        [type: 'pool2d', size: 2],
        [type: 'flatten'],
        [type: 'dense', out: 256],
        [type: 'dense', out: 1000]
    ]
]

// Build model dynamically
def ml = Ops.ops()
def model = new Model(ml.shape(*config.inputShape))

config.layers.each { layerConfig ->
    switch(layerConfig.type) {
        case 'conv2d':
            model.addLayer(ml.convolution2d(layerConfig.in, layerConfig.out))
            break
        case 'pool2d':
            model.addLayer(ml.pool2d(layerConfig.size))
            break
        case 'dense':
            model.addLayer(ml.dense(layerConfig.out))
            break
        case 'flatten':
            model.add(ml.flatten())
            break
    }
}

model.setup().get().run()
```

### Pattern 4: Interactive Experimentation

```groovy
// Groovy REPL/shell for live experimentation
groovysh

// In shell:
import org.almostrealism.Ops

def ml = Ops.ops()
def model = new Model(ml.shape(100))

// Try different architectures interactively
model.addLayer(ml.dense(50))
model.addLayer(ml.relu())

model.setup().get().run()

// Test with random input
def input = new PackedCollection(ml.shape(100))
input.fill({ Math.random() } as DoubleSupplier)

def output = model.forward(input)
println output

// Modify and re-test immediately
```

## Integration with Other Modules

### AR-ML Module
- Uses **Model**, **Block**, **LayerFeatures**
- Full access to attention, transformer layers
- Can load pre-trained weights

### AR-Graph Module
- **Block** composition and chaining
- **CompiledModel** for optimized execution
- **Setup** for initialization

### AR-Collect Module
- **PackedCollection** for tensor data
- **TraversalPolicy** for shapes
- GPU-ready memory layout

## Running Groovy Scripts

### Command Line

```bash
# Run Groovy script directly
groovy my_model.groovy

# With classpath
groovy -cp "ar-ml-script.jar:ar-ml.jar:..." model_script.groovy
```

### From Java

```java
import groovy.lang.GroovyShell;

GroovyShell shell = new GroovyShell();
Object result = shell.evaluate(new File("model.groovy"));
```

### Gradle

```groovy
plugins {
    id 'groovy'
}

dependencies {
    implementation 'org.almostrealism:ar-ml-script:0.72'
}

task runModel(type: JavaExec) {
    main = 'org.codehaus.groovy.tools.GroovyStarter'
    args = ['--main', 'groovy.ui.GroovyMain', 'model.groovy']
    classpath = sourceSets.main.runtimeClasspath
}
```

## Example: Complete Training Script

```groovy
import org.almostrealism.Ops
import org.almostrealism.model.Model
import org.almostrealism.optimize.ModelOptimizer
import org.almostrealism.optimize.MeanSquaredError

// Setup
def ml = Ops.ops()

// Define model
def model = new Model(ml.shape(784))
model.addLayer(ml.dense(128))
model.addLayer(ml.relu())
model.addLayer(ml.dense(10))
model.addLayer(ml.softmax())

// Load data
def trainingData = loadMNIST()

// Configure training
def optimizer = new ModelOptimizer(
    model.compile(),
    { trainingData }
)

optimizer.setLossFunction(new MeanSquaredError(ml.shape(10).traverseEach()))
optimizer.setLogFrequency(10)

// Train
println "Training model..."
optimizer.optimize(50)

println "Final loss: ${optimizer.getLoss()}"

// Test
def accuracy = optimizer.accuracy { expected, output ->
    argmax(expected) == argmax(output)
}

println "Test accuracy: ${accuracy * 100}%"
```

## Benefits of Scripting

1. **Rapid Prototyping** - No compilation step for architecture changes
2. **Interactive Development** - REPL-driven experimentation
3. **Configuration-Driven** - Models defined in config files
4. **Easy Testing** - Quick iterations for testing ideas
5. **Educational** - Simpler syntax for learning
6. **Automation** - Script model generation and training

## Limitations

- **Performance** - Slightly slower than compiled Java (negligible for ML workloads)
- **Type Safety** - Less compile-time checking than Java
- **IDE Support** - Fewer Groovy-specific tools than Java
- **Debugging** - Stack traces less clear than pure Java

## Dependencies

```xml
<dependency>
    <groupId>org.almostrealism</groupId>
    <artifactId>ar-ml</artifactId>
    <!-- Check pom.xml for current version -->
</dependency>

<dependency>
    <groupId>org.apache.groovy</groupId>
    <artifactId>groovy</artifactId>
    <version>4.0.2</version>
</dependency>
```

## Maven Dependency

```xml
<dependency>
    <groupId>org.almostrealism</groupId>
    <artifactId>ar-ml-script</artifactId>
    <!-- Check pom.xml for current version -->
</dependency>
```

## Further Reading

- See **ar-ml** module for full ML capabilities
- See **ar-graph** module for Model and Block composition
- See **Apache Groovy documentation** for language features
