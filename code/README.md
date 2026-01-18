# Code Module (ar-code)

The `ar-code` module provides core code generation, expression building, and traversal abstractions for the Almost Realism computational framework. It is the foundation for dynamic code generation and computation graph construction across all backend targets (OpenCL, Metal, JNI).

## Overview

This module provides:
- **Expression System** - Typed expression trees for mathematical and logical operations
- **Scope Management** - Hierarchical code organization for generated programs
- **Traversal Policies** - Multi-dimensional collection shape and traversal definitions
- **Kernel Abstractions** - Index sequence and kernel structure management
- **Code Generation** - Language-agnostic code printing and generation

## Package Structure

| Package | Purpose |
|---------|---------|
| `io.almostrealism.code` | Core computation, code generation, and expression management abstractions |
| `io.almostrealism.expression` | Mathematical expressions and operators (arithmetic, trigonometric, logical) |
| `io.almostrealism.collect` | Collection traversal policies and collection expressions |
| `io.almostrealism.scope` | Code scope management, variables, and statement organization |
| `io.almostrealism.kernel` | Kernel structure, indexing, and matrix operations |
| `io.almostrealism.lang` | Language operations abstractions for code generation backends |
| `io.almostrealism.compute` | Computation requirements and optimization strategies |
| `io.almostrealism.profile` | Performance profiling and operation metadata tracking |
| `io.almostrealism.html` | HTML and JavaScript code generation utilities |
| `io.almostrealism.util` | Utility classes (caching, sequences, formatting) |
| `io.almostrealism.concurrent` | Concurrency primitives (semaphores) |

## Key Concepts

### Expression System

The expression system represents computations as typed expression trees that can be simplified, analyzed, and compiled into executable code.

```java
// Building expressions
Expression<Double> a = new DoubleConstant(3.14);
Expression<Double> b = new DoubleConstant(2.0);
Expression<Double> result = a.multiply(b).add(new DoubleConstant(1.0));

// Expressions can be simplified
Expression<Double> simplified = result.getSimplified();

// Generate code for target language
String code = result.getExpression(languageOps);
```

**Key Expression Classes:**
- `Expression<T>` - Abstract base class for all expressions
- `Sum`, `Product`, `Quotient`, `Difference` - Arithmetic operations
- `Sine`, `Cosine`, `Tangent`, `Exp`, `Logarithm` - Mathematical functions
- `Conditional`, `Equals`, `Greater`, `Less` - Comparisons and conditionals
- `IntegerConstant`, `DoubleConstant`, `BooleanConstant` - Constant values
- `Cast` - Type conversions

### Scope System

`Scope` is the container for executable code elements including statements, variables, methods, and nested scopes.

```java
// Create a scope
Scope<Double> scope = new Scope<>("myFunction", metadata);

// Add variables and statements
scope.declareDouble("x");
scope.assign(new ExpressionAssignment<>("x", expression));

// Generate code
CodePrintWriter writer = new CPrintWriter(...);
scope.write(writer);
```

**Key Scope Classes:**
- `Scope<T>` - Primary container for code elements
- `Variable<T, V>` - Named variable with optional expression
- `ArrayVariable<T>` - Array-typed variable with indexing
- `Argument<T>` - Scope argument with usage expectations
- `Cases` - Conditional branching (if-else chains)
- `Statement<T>` - Executable code statement

### TraversalPolicy

`TraversalPolicy` defines how a sequence of elements should be traversed to form a multidimensional collection. It specifies dimensions and traversal rates for transforming between output space (collection indices) and input space (natural element order).

#### Fixed vs Variable Count

`TraversalPolicy` supports two modes of operation:

**Fixed-Count (default)**:
```java
// Always processes exactly 100 elements
TraversalPolicy fixed = new TraversalPolicy(100);

// Multi-dimensional: 10x20 matrix, always 200 elements
TraversalPolicy matrix = new TraversalPolicy(10, 20);
```
- Dimensions are predetermined
- Size cannot change at runtime
- Most efficient for known-size data structures

**Variable-Count**:
```java
// Processes N elements where N is determined at runtime
TraversalPolicy variable = new TraversalPolicy(false, false, 1);

// Multi-dimensional with variable size
TraversalPolicy variableMatrix = new TraversalPolicy(false, false, 10, 20);
```
- Dimensions can adapt to runtime input sizes
- Enables flexible operations on varying-sized collections
- Created with: `new TraversalPolicy(false, false, dims...)`

### Kernel and Index Sequences

The kernel package provides abstractions for parallel computation patterns.

```java
// Create an arithmetic sequence
IndexSequence seq = ArithmeticIndexSequence.of(0, 1, 100); // 0, 1, 2, ..., 99

// Generate expression for the sequence
Expression<Integer> expr = seq.getExpression(index);

// Create index sequences from expressions
IndexSequence evaluated = ArrayIndexSequence.of(expression, indexValues, length);
```

**Key Kernel Classes:**
- `IndexSequence` - Sequence of numeric index values
- `ArithmeticIndexSequence` - Efficient arithmetic progression
- `ArrayIndexSequence` - General-purpose array-backed sequence
- `KernelStructureContext` - Context for kernel simplification

### Code Generation

The `lang` package provides abstractions for generating code in different target languages.

```java
// Create a language-specific writer
PrintWriter output = new PrintWriter(...);
CodePrintWriter writer = new CPrintWriter(output, "myFunction", Precision.FP32, true, false);

// Write scope to output
scope.write(writer);
writer.flush();
```

**Key Code Generation Classes:**
- `CodePrintWriter` - Interface for code output
- `CodePrintWriterAdapter` - Base implementation for C-like languages
- `LanguageOperations` - Language-specific expression rendering
- `ScopeEncoder` - Scope-to-code encoding

### Computation Framework

The computation framework provides the structure for building hardware-accelerated operations.

```java
// Implement a computation
public class MyComputation extends ComputationBase<Double> {
    @Override
    public Scope<Double> getScope(NameProvider provider) {
        Scope<Double> scope = new Scope<>("compute", getMetadata());
        // Build scope...
        return scope;
    }
}
```

**Key Computation Classes:**
- `Computation<T>` - Interface for computations that produce scopes
- `ComputationBase<T>` - Abstract base with lifecycle support
- `ComputableBase<T>` - Foundation for computable operations
- `ScopeLifecycle` - Lifecycle hooks for scope preparation

## Collection Expressions

Collection expressions represent multi-dimensional data that can be accessed by index.

```java
// Create a collection expression
CollectionExpression collection = CollectionExpression.create(
    shape(10, 20),
    index -> someExpression.getValueAt(index)
);

// Access values
Expression<Double> value = collection.getValueAt(flatIndex);

// Stream all values
collection.stream().forEach(expr -> ...);
```

**Key Collection Classes:**
- `CollectionExpression` - Index-accessible value collection
- `TraversableExpression<T>` - Functional interface for value access
- `Shape<T>` - Multi-dimensional shape interface
- `GroupExpression` - Grouped/batched operations

## Dependencies

- `ar-relation` (v0.72) - Provides core relation and computation abstractions
- `ar-io` (v0.72) - Provides I/O and console utilities

## Common Patterns

### Creating Collection Shapes

```java
// 1D vector (100 elements, fixed)
shape(100)

// 2D matrix (10 rows x 20 columns, fixed)
shape(10, 20)

// Variable-size 1D collection
new TraversalPolicy(false, false, 1)

// Variable-size 2D collection
new TraversalPolicy(false, false, new long[]{10, 20})
```

### Reshape Operations

```java
TraversalPolicy original = shape(100);
TraversalPolicy reshaped = original.reshape(10, 10);  // 100 elements as 10x10 matrix
```

### Traversal

```java
TraversalPolicy matrix = shape(10, 20);  // 10 rows x 20 columns
TraversalPolicy row = matrix.traverse(0);  // Traverse along first axis (rows)
```

### Expression Simplification

```java
Expression<Double> complex = a.multiply(b).add(a.multiply(c));
Expression<Double> simplified = complex.getSimplified();
// May simplify to: a * (b + c)
```

## See Also

- `ar-relation` module - Provides `Computable`, `Evaluable`, `Producer` abstractions
- `ar-io` module - Provides `Console`, `ConsoleFeatures`, I/O utilities
- `ar-hardware` module - Hardware acceleration using this code generation system
