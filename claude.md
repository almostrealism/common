# Almost Realism Common - Development Guidelines for Claude Code

## Hardware Acceleration Setup

⚠️ **CRITICAL**: All Almost Realism modules that use hardware acceleration require environment variables to be set before running any code.

### Required Environment Variables

```bash
export AR_HARDWARE_LIBS=/home/developer/.libs/
export AR_HARDWARE_DRIVER=native
```

### Setup Instructions

1. **Create the hardware libs directory** (if not already present):
   ```bash
   mkdir -p /home/developer/.libs
   ```

2. **Set environment variables** before running Java code:
   ```bash
   export AR_HARDWARE_LIBS=/home/developer/.libs/
   export AR_HARDWARE_DRIVER=native
   ```

3. **For Maven tests**, always prefix test commands with the environment variables:
   ```bash
   export AR_HARDWARE_LIBS=/home/developer/.libs/ && \
   export AR_HARDWARE_DRIVER=native && \
   mvn test -pl <module>
   ```

### What These Variables Do

- **`AR_HARDWARE_LIBS`**: Specifies the directory where hardware acceleration libraries (JNI .so files, OpenCL kernels, etc.) will be generated and loaded from
- **`AR_HARDWARE_DRIVER`**: Specifies which hardware backend to use:
  - `native`: Standard JNI operations with runtime-generated native code (default)
  - `opencl`: OpenCL acceleration (CPU/GPU)
  - `metal`: Metal GPU acceleration (Apple Silicon)
  - `external`: Generated executable approach

### Common Issues

❌ **Forgetting to set these variables** will result in:
- `NoClassDefFoundError: Could not initialize class org.almostrealism.collect.PackedCollection`
- Runtime errors when trying to compile operations
- Missing library errors
- Failures during model inference

✅ **Always verify** these are set before running:
```bash
echo $AR_HARDWARE_LIBS
echo $AR_HARDWARE_DRIVER
```

---

## Code Organization Principles

### Use StateDictionary for Model Weights

**Standard Pattern**: All model implementations should use `StateDictionary` for weight management.

```java
// GOOD: Use StateDictionary directly
StateDictionary stateDict = new StateDictionary(weightsDirectory);
PackedCollection<?> embeddings = stateDict.get("model.embed_tokens.weight");
PackedCollection<?> wq = stateDict.get("model.layers.0.self_attn.q_proj.weight");
```

```java
// AVOID: Creating separate weight container classes
// Unless there's a compelling reason (e.g., weight transformations, caching)
public class ModelWeights {
    PackedCollection<?> wq;  // Duplicates StateDictionary storage
    PackedCollection<?> wk;
    // ...
}
```

**When to use a wrapper class**:
- Weight transformations are needed (e.g., transposing, reshaping)
- Complex weight organization logic
- Caching computed values (e.g., RoPE frequencies)

**If needed**, make it a subclass or thin wrapper:
```java
public class ModelWeights extends StateDictionary {
    // Add specialized methods only
}
```

### Generalize, Don't Duplicate

**Principle**: Extend and generalize existing code rather than creating model-specific copies.

```java
// GOOD: Generalize existing method with optional parameters
default Block attention(int heads, int kvHeads, int headSize,
                       PackedCollection<?> wq, PackedCollection<?> wk,
                       PackedCollection<?> wv, PackedCollection<?> wo,
                       PackedCollection<?> qkNormQ, PackedCollection<?> qkNormK,  // Optional
                       ...) {
    // Single implementation that handles all cases
    if (qkNormQ != null && qkNormK != null) {
        // Apply QK-Norm
    }
}
```

```java
// AVOID: Creating model-specific duplicate methods
default Block llamaAttention(...) { /* ... */ }
default Block qwenAttention(...) { /* ... */ }  // Copy-paste with minor changes
default Block mistralAttention(...) { /* ... */ }
```

**Benefits of generalization**:
- Single source of truth for attention logic
- Bugs fixed once, not per model
- Easier to add new features
- Better testing coverage

**When duplication is acceptable**:
- Fundamentally different architectures (encoder-decoder vs decoder-only)
- Performance-critical paths requiring specialization
- Temporary experimentation (mark with TODO to generalize)

### Deprecation Guidelines

**Mark deprecated code clearly**:
```java
/**
 * @deprecated Use StateDictionary constructor instead.
 * Binary checkpoint format is deprecated and will be removed in a future version.
 * This constructor remains for backward compatibility only.
 */
public ModelWeights(FloatBuffer buffer) {
    // Legacy code...
}
```

**Common deprecated patterns**:
- Binary checkpoint constructors (use StateDictionary)
- Model-specific weight container classes (use StateDictionary directly)
- Duplicate attention/layer implementations (generalize existing code)

---

## Development Workflow

### Before Starting a Task

1. **Check for existing implementations**: Don't reinvent the wheel
2. **Identify generalization opportunities**: Can existing code be extended?
3. **Review StateDictionary**: Can it handle your use case?
4. **Set environment variables**: Especially for testing

### During Development

1. **Prefer composition over duplication**
2. **Add optional parameters rather than creating new methods**
3. **Use StateDictionary as the standard weight storage**
4. **Test frequently with environment variables set**

### Before Committing

1. **Remove TODO markers for completed work**
2. **Mark deprecated code with @deprecated tags**
3. **Ensure all tests pass with hardware acceleration enabled**
4. **Document any new patterns or breaking changes**

---

## Module-Specific Guidelines

For module-specific development notes, see:
- [ML Module](./ml/claude.md) - Machine learning models and layers
- [Graph Module](./graph/README.md) - Computation graph and layers
- [Collect Module](./collect/README.md) - Collection operations

---

## Common Patterns

### Loading Model Weights

```java
// Standard pattern for all models
StateDictionary stateDict = new StateDictionary(weightsDirectory);

// Access weights by HuggingFace key names
PackedCollection<?> embeddings = stateDict.get("model.embed_tokens.weight");
PackedCollection<?> wq = stateDict.get("model.layers.0.self_attn.q_proj.weight");

// Use helper methods for repeated patterns
private PackedCollection<?> getLayerWeight(StateDictionary dict, int layer, String name) {
    return dict.get(String.format("model.layers.%d.%s", layer, name));
}
```

### Building Transformer Layers

```java
// Generalize existing methods with optional parameters
Model transformer = new Model(shape(dim));

for (int i = 0; i < layerCount; i++) {
    // Load weights
    PackedCollection<?> wq = getLayerWeight(stateDict, i, "self_attn.q_proj.weight");
    // ...

    // Use generalized attention method
    transformer.add(attention(
        heads, kvHeads, headSize,
        wq, wk, wv, wo,
        qkNormQ, qkNormK,  // null if not using QK-Norm
        freqCis,
        requirements
    ));
}
```

---

## Testing

### Running Tests

Always set environment variables when running tests:

```bash
# Single module
export AR_HARDWARE_LIBS=/home/developer/.libs/ && \
export AR_HARDWARE_DRIVER=native && \
mvn test -pl ml

# Specific test
export AR_HARDWARE_LIBS=/home/developer/.libs/ && \
export AR_HARDWARE_DRIVER=native && \
mvn test -pl ml -Dtest=MyTest

# All modules
export AR_HARDWARE_LIBS=/home/developer/.libs/ && \
export AR_HARDWARE_DRIVER=native && \
mvn test
```

### Test Organization

- **Unit tests**: Test individual components in isolation
- **Integration tests**: Test component interactions
- **Synthetic tests**: Validate architecture with random weights
- **Validation tests**: Compare against reference implementations

---

## Questions or Issues?

If you encounter issues or have questions about these guidelines:
1. Check module-specific documentation
2. Review existing implementations for patterns
3. Ask for clarification before creating duplicate code
