# Almost Realism UML Module (`ar-uml`)

The UML Module provides foundational annotations and interfaces for semantic metadata and system introspection in Almost Realism. Despite its name, it does not generate or parse UML diagrams. Instead, it serves as a lightweight annotation library that helps the framework understand class semantics, lifecycle, and identification.

## Purpose

This module exists to:

1. **Provide Semantic Annotations** - Mark classes with metadata about their role and characteristics
2. **Define Naming Contracts** - Named and Nameable interfaces for entity identification
3. **Enable Lifecycle Management** - Destroyable and Lifecycle interfaces for resource cleanup
4. **Support Signature-Based Caching** - Hash-based identification for deduplication
5. **Facilitate Introspection** - Help framework understand code structure and purpose

**Note**: This module does NOT provide UML diagram generation, visualization, or code generation features.

## What It Provides

### 1. Semantic Annotations

```java
import io.almostrealism.uml.*;

// Mark classes that hold expensive state
@ModelEntity
public class BasicGeometry implements Positioned, Oriented, Scaled {
    public Vector location;
    public Vector scale;
    private TransformMatrix transforms[];  // Expensive cached state
}

// Mark types representing computations
@Function
@FunctionalInterface
public interface Evaluable<T> extends Computable {
    T evaluate(Object... args);
}

// Mark stateless, thread-safe types
@Stateless
public class MathUtils {
    public static double sin(double x) { ... }
}

// Mark view model types
@ViewModel
public class RenderViewModel {
    // ...
}
```

### 2. Naming Interfaces

```java
import io.almostrealism.uml.Named;
import io.almostrealism.uml.Nameable;

// Read-only naming
public class Argument<T> implements Named {
    private final String name;

    @Override
    public String getName() { return name; }
}

// Mutable naming
public class Variable<T, V> implements Nameable {
    private String name;

    @Override
    public String getName() { return name; }

    @Override
    public void setName(String name) { this.name = name; }
}

// Helper methods
String name = Named.nameOf(entity);  // Returns name or class simple name
List<Named> unique = Named.removeDuplicates(duplicateList);  // Remove duplicates by name
```

### 3. Lifecycle Management

```java
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.lifecycle.Lifecycle;

// Resource cleanup (implements AutoCloseable)
public class Model implements Destroyable {
    @Override
    public void destroy() {
        // Release GPU memory
        // Free compiled kernels
        // Cleanup native resources
    }
}

// Try-with-resources support
try (Model model = new Model()) {
    model.compile();
    model.forward(input);
}  // destroy() called automatically

// Reset/initialization
public class TemporalCell implements Lifecycle {
    @Override
    public void reset() {
        // Reset state to initial conditions
    }
}
```

### 4. Signature-Based Identification

```java
import io.almostrealism.uml.Signature;

// Compute signatures for caching/deduplication
public class MetalProgram implements Signature {
    @Override
    public String signature() {
        return Signature.md5(uniqueIdentifier);
    }
}

// Static helper methods
String sig = Signature.of(object);          // Get signature or "?"
String hash = Signature.md5(stringData);    // MD5 hash
String hex = Signature.hex(byteArray);      // Hex encoding
```

### 5. Indexed Access Interfaces

```java
import io.almostrealism.uml.Plural;
import io.almostrealism.uml.Multiple;

// Plural - valueAt(pos) pattern
public interface Gene<T> extends Plural<Factor<T>> {
    @Override
    Factor<T> valueAt(int pos);
}

// Multiple - get(index) pattern
public interface Collection<T> extends Multiple<T> {
    @Override
    T get(int index);
}
```

## Key Annotations

### @ModelEntity

Marks types that hold state with potentially expensive computation or persistence concerns.

**Used by:**
- BasicGeometry (transformation matrices)
- Camera classes (projection state)
- ShadableSurface (rendering state)

```java
@ModelEntity
public class PinholeCamera extends OrthographicCamera {
    // Camera maintains complex state: basis vectors, transforms, etc.
}
```

### @Function

Marks types representing computations that can be evaluated for varying parameters.

**Used by:**
- Evaluable<T> interface
- DistanceEstimator
- Intersectable<T>

```java
@Function
@FunctionalInterface
public interface Evaluable<T> extends Computable {
    T evaluate(Object... args);
}
```

### @Stateless

Marks types that are thread-safe with no mutable state.

```java
@Stateless
public class GeometryUtils {
    public static Vector cross(Vector a, Vector b) { ... }
}
```

### @ViewModel

Marks view model types (currently unused in codebase).

```java
@ViewModel
public class SceneViewModel {
    // UI representation of scene
}
```

## Key Interfaces

### Named

```java
public interface Named {
    String getName();

    // Get name or fallback to class simple name
    static <T> String nameOf(T named);

    // Remove duplicates, keeping first occurrence
    static <T extends Named> List<T> removeDuplicates(List<T> list);

    // Remove duplicates with custom chooser
    static <T extends Named> List<T> removeDuplicates(
        List<T> list,
        BiFunction<T, T, T> chooser
    );
}
```

### Nameable

```java
public interface Nameable extends Named {
    void setName(String name);
}
```

### Signature

```java
public interface Signature {
    default String signature() {
        throw new UnsupportedOperationException();
    }

    static <T> String of(T sig);           // Get signature or "?"
    static String md5(String sig);         // MD5 hash
    static String hex(byte[] bytes);       // Hex encoding
}
```

### Destroyable

```java
public interface Destroyable extends AutoCloseable {
    default void destroy() { }

    @Override
    default void close() { destroy(); }

    // Destroy and return the object
    static <T> T destroy(T destroyable);
}
```

### Lifecycle

```java
public interface Lifecycle {
    default void reset() { }
}
```

## Common Patterns

### Pattern 1: Resource Management with Destroyable

```java
public class GpuModel implements Destroyable {
    private NativeBuffer weights;
    private CompiledKernel kernel;

    @Override
    public void destroy() {
        if (weights != null) weights.destroy();
        if (kernel != null) kernel.destroy();
        weights = null;
        kernel = null;
    }
}

// Usage with try-with-resources
try (GpuModel model = new GpuModel()) {
    model.train(data);
}  // Automatically cleaned up
```

### Pattern 2: Named Entity Deduplication

```java
List<Operation> operations = new ArrayList<>();
// ... populate with operations, some with duplicate names

// Remove duplicates by name, keeping first occurrence
List<Operation> unique = Named.removeDuplicates(operations);

// Or keep the better-performing operation
List<Operation> best = Named.removeDuplicates(
    operations,
    (a, b) -> a.getExecutionTime() < b.getExecutionTime() ? a : b
);
```

### Pattern 3: Signature-Based Caching

```java
public class CompiledProgram implements Signature {
    private String sourceCode;
    private Map<String, Object> options;

    @Override
    public String signature() {
        String combined = sourceCode + options.toString();
        return Signature.md5(combined);
    }
}

// Cache programs by signature
Map<String, CompiledProgram> cache = new HashMap<>();

CompiledProgram getOrCompile(CompiledProgram program) {
    String sig = program.signature();
    return cache.computeIfAbsent(sig, k -> {
        program.compile();
        return program;
    });
}
```

### Pattern 4: Semantic Annotation for State Management

```java
// Framework can scan for @ModelEntity to find stateful components
@ModelEntity
public class Scene {
    private List<Surface> surfaces;
    private List<Light> lights;
    private Camera camera;

    // State that needs to be saved/loaded
}

// Serialization system can use annotations to determine what to save
public void saveState() {
    for (Class<?> cls : getAllClasses()) {
        if (cls.isAnnotationPresent(ModelEntity.class)) {
            // Serialize this class's state
        }
    }
}
```

### Pattern 5: Lifecycle Reset for Temporal Systems

```java
public class TemporalCell implements Lifecycle {
    private double time = 0.0;
    private PackedCollection<?> state;

    @Override
    public void reset() {
        time = 0.0;
        if (state != null) {
            state.fill(0.0);  // Clear state
        }
    }
}

// Reset all cells in simulation
for (Lifecycle cell : cells) {
    cell.reset();
}
```

## Integration with Other Modules

### Relation Module
- `Evaluable<T>` marked with `@Function`
- Indicates evaluable computations

### Code Module
- `Variable<T, V>` implements `Nameable`
- `Argument<T>` implements `Named`
- Code generation requires entity names

### Hardware Module
- `MetalProgram` implements `Signature` for kernel caching
- `NativeBuffer` implements `Destroyable` for GPU memory cleanup
- `CompiledOperation` uses signatures for deduplication

### Graph Module
- `Model` implements `Destroyable` for resource cleanup
- `Cell` implements `Lifecycle` for temporal reset

### Geometry Module
- `BasicGeometry` marked with `@ModelEntity`
- Camera classes marked with `@ModelEntity`

### IO Module
- `MetricBase` implements `Named` for logging
- `OperationProfile` implements `Nameable`

### Heredity Module
- Genome classes implement `Signature` for identification
- Chromosomes implement `Lifecycle` for reset

## Dependency Structure

The UML module is **foundational** with zero external dependencies:

```
ar-uml (no dependencies)
  ├── ar-relation (uses @Function, Multiple)
  ├── ar-code (uses Named, Nameable)
  ├── ar-io (uses Named, Destroyable, Lifecycle)
  └── ar-hardware (uses Signature, Destroyable)
      └── All higher-level modules
```

## Maven Dependency

```xml
<dependency>
    <groupId>org.almostrealism</groupId>
    <artifactId>ar-uml</artifactId>
    <version>0.72</version>
</dependency>
```

## Design Philosophy

The UML module follows key design principles:

1. **Opt-In Metadata** - Classes don't require these annotations to work
2. **Zero Dependencies** - Can be safely imported anywhere
3. **Semantic Layer** - Describes meaning, doesn't enforce behavior
4. **Introspection-Friendly** - Enables framework to understand code structure
5. **Resource Safety** - Lifecycle management for GPU/native resources
6. **AI-Aware** - Designed with code generation in mind (see Standards interface)

## Further Reading

- See **ar-relation** module for computation abstractions using @Function
- See **ar-hardware** module for Signature-based kernel caching
- See **ar-graph** module for Destroyable resource cleanup patterns
- See **ar-code** module for Named/Nameable usage in code generation
