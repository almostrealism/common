# Expression Evaluation and Compilation

## Overview

The Almost Realism framework uses an expression tree system to represent computations,
which are then compiled to native code (JNI, OpenCL, Metal) for execution.

## Expression Tree Hierarchy

```
Expression<T>
├── Constant<T>           - Literal values
├── Variable<T>           - Named variables
├── Index                 - Loop/kernel indices
├── Sum<T>                - Addition
├── Product<T>            - Multiplication
├── Quotient<T>           - Division
├── Conditional<T>        - Ternary conditional
├── CollectionExpression  - Collection operations
└── ... (many more)
```

## Compilation Pipeline

### 1. Producer Creation

```java
Producer<PackedCollection> producer = add(a, b);
```

Creates a `CollectionProducer` wrapping the computation graph.

### 2. Scope Generation

```java
Scope scope = producer.getScope();
```

Converts the producer tree into a `Scope` containing:
- Variable declarations
- Assignment statements
- Loop structures

### 3. Expression Simplification

Before generating code, expressions are simplified:

```java
Expression simplified = expression.simplify(context);
```

Simplification includes:
- Constant folding: `2 + 3` -> `5`
- Identity elimination: `x * 1` -> `x`
- Algebraic simplification: `x - x` -> `0`
- Series simplification: Recognize arithmetic sequences

**Configuration** (ScopeSettings.java):
- `isSeriesSimplificationTarget()` - Controls when series simplification applies
- `maxDepth` - Maximum expression tree depth
- `maxNodeCount` - Maximum nodes in expression tree

### 4. Code Generation

```java
String code = scope.generate(language);
```

Generates target language code (C, OpenCL, Metal).

### 5. Compilation

```java
CompiledOperation operation = Hardware.compile(scope);
```

Compiles to native executable or kernel.

### 6. Execution

```java
PackedCollection result = operation.evaluate(inputs);
```

Executes the compiled code with input data.

## Instruction Set Caching

Compiled operations are cached to avoid recompilation:

**Key class**: `ComputeCache` / `InstructionSetCache`

```java
// Enable/disable caching
ScopeSettings.enableInstructionSetReuse = true;  // default
```

**Cache key components**:
- Operation signature (via `signature()` method)
- Input shapes
- Hardware configuration

**Important**: Operations must have correct signatures for caching to work:
```java
@Override
public String signature() {
    String base = super.signature();
    if (base == null) return null;
    return base + "{myParam=" + myParam + "}";
}
```

## Expression Contexts

### KernelStructureContext

Provides information about the kernel being compiled:
- Kernel size (number of parallel work items)
- Index limits
- Optimization hints

### IndexValues

Provides concrete index values for expression evaluation:

```java
IndexValues values = new IndexValues();
values.put(kernelIndex, 42);
Number result = expression.value(values);
```

## Common Patterns

### Creating Custom Expressions

```java
public class MyExpression extends Expression<Double> {
    @Override
    public Expression<Double> simplify(KernelStructureContext context) {
        // Simplification logic
    }

    @Override
    public String generate(ExpressionLanguage lang) {
        // Code generation
    }
}
```

### Collection Expressions

```java
CollectionExpression.create(shape, index -> {
    return args[0].getValueAt(index).add(args[1].getValueAt(index));
});
```

## Provider vs Computation

**Provider**: Wraps existing memory, no computation needed
```java
Producer<PackedCollection> p = p(existingCollection);
// Computable.provider(p) returns true
```

**Computation**: Produces values through calculation
```java
Producer<PackedCollection> c = add(a, b);
// Computable.provider(c) returns false
```

This distinction affects:
- Whether short-circuit paths can be used
- How destinations are handled in assignments
- Memory allocation strategy

## TraversableExpression

Interface for expressions that can be accessed with computed indices:

```java
public interface TraversableExpression<T> {
    // Get value at dynamic index
    Expression<T> getValueAt(Expression<?> index);

    // Get value at offset from current position
    Expression<T> getValueRelative(Expression<?> offset);
}
```

Used extensively in:
- Dynamic indexing (`traverseEach`)
- Convolution operations
- Attention mechanisms

## Debugging Tips

1. **Enable expression warnings**:
   ```java
   ScopeSettings.enableExpressionWarnings = true;
   ```

2. **Enable expression review**:
   ```java
   ScopeSettings.enableExpressionReview = true;
   ```

3. **Check simplification stats**:
   ```java
   ScopeSettings.printStats();
   ```

4. **Examine generated code**: Enable logging to see compiled native code

5. **Verify signatures**: Incorrect signatures cause cache misses or incorrect caching

## Performance Considerations

- Expression depth affects compilation time
- Large expression trees may hit `maxNodeCount` limits
- Simplification is configurable via `AR_SCOPE_SIMPLIFICATION` environment variable
- Caching is configurable via `AR_SCOPE_CACHING` environment variable

## Related Files

- `ScopeSettings.java` - Configuration for expression handling
- `Expression.java` - Base expression class
- `Scope.java` - Scope containing statements
- `ComputeCache.java` - Instruction set caching
- `ProcessDetailsFactory.java` - Kernel count validation (see `kernel-count-propagation.md`)

## See Also

- [Kernel Count Propagation](kernel-count-propagation.md) - How counts flow through operations
- [Dynamic Indexing](dynamic-indexing.md) - Traversal and dynamic index handling
- [Assignment Optimization](assignment-optimization.md) - Short-circuit paths
