# CollectionProducer Fluent API

`CollectionProducer` is the core interface for building computational graphs over
`PackedCollection` data. Every operation returns a new `CollectionProducer`, enabling
method chaining. Nothing executes until you explicitly evaluate the graph.

## Entering the Graph

Two entry points turn data into a `CollectionProducer`:

```java
// cp() — wraps an existing PackedCollection by REFERENCE.
// Changes to the underlying collection are visible to the graph.
PackedCollection weights = new PackedCollection(shape(64, 128));
CollectionProducer w = cp(weights);

// c() — captures a VALUE as a compile-time constant.
// The values are baked into the graph; later mutations are not seen.
CollectionProducer bias = c(0.1, 0.2, 0.3);
CollectionProducer scalar = c(2.0);
CollectionProducer shaped = c(shape(3, 4), new double[]{...});

// p() — wraps an object as a Producer reference (lower-level).
Producer<PackedCollection> ref = p(weights);

// zeros() / rand() / randn() — create filled producers directly.
CollectionProducerComputation z = zeros(shape(10));
Random r = rand(shape(3, 4));
Random n = randn(shape(3, 4));
```

## Element-wise Arithmetic

All arithmetic operations are element-wise and return a new `CollectionProducer`.

```java
CollectionProducer a = cp(dataA);
CollectionProducer b = cp(dataB);

// Basic arithmetic
a.add(b)              // element-wise addition
a.add(5.0)            // add scalar to all elements
a.subtract(b)         // element-wise subtraction
a.subtract(3.0)       // subtract scalar
a.multiply(b)         // element-wise multiplication (also: .mul(b))
a.multiply(0.5)       // scale by scalar (also: .mul(0.5))
a.divide(b)           // element-wise division
a.divide(2.0)         // divide by scalar

// Unary operations
a.minus()             // negate all elements
a.abs()               // absolute value
a.sq()                // square (a * a)
a.sqrt()              // square root
a.exp()               // e^x for each element
a.log()               // natural logarithm
a.pow(3.0)            // raise to a power
a.pow(b)              // element-wise power
a.reciprocal()        // 1/x (equivalent to .pow(c(-1.0)))
a.mod(3.0)            // modulo operation
a.sigmoid()           // 1 / (1 + e^(-x))

// Chaining example — normalize to [0, 1]
CollectionProducer minVal = a.min();
CollectionProducer maxVal = a.max();
CollectionProducer normalized = a.subtract(minVal).divide(maxVal.subtract(minVal));
```

## Reductions and Aggregations

Reduction operations collapse elements along an axis or over the entire collection.
They produce a collection with fewer elements.

```java
CollectionProducer data = cp(matrix); // shape (10, 5)

// Global reductions — collapse to a single value
data.sum()            // sum of all elements -> shape (1)
data.mean()           // arithmetic mean -> shape (1)
data.variance()       // population variance -> shape (1)
data.max()            // maximum element -> shape (1)
data.magnitude()      // L2 norm: sqrt(sum(x^2)) -> shape (1)

// Axis reductions — collapse along one axis using traverse
data.sum(0)           // sum along axis 0 -> shape (5)
data.mean(1)          // mean along axis 1 -> shape (10)
data.variance(0)      // variance along axis 0
data.max(1)           // max along axis 1
data.magnitude(0)     // L2 norm along axis 0

// Centering
data.subtractMean()   // subtract the global mean from every element
data.subtractMean(0)  // subtract the axis-0 mean

// Index of maximum
data.indexOfMax()     // index of the largest element
```

## Shape Operations

Shape operations rearrange or select data without changing element values.

```java
CollectionProducer x = cp(input); // shape (2, 3, 4)

// reshape — reinterpret the same data with different dimensions
x.reshape(6, 4)                   // -> shape (6, 4)
x.reshape(shape(24))              // -> shape (24)

// traverse — set the traversal axis (controls how kernels iterate)
x.traverse(1)                     // traverse along axis 1

// transpose — swap dimensions (2D)
x.transpose()                     // swap rows and columns
x.transpose(1)                    // transpose at a specific axis

// subset — extract a sub-region
x.subset(shape(1, 2, 4), 0, 1, 0) // extract shape (1,2,4) starting at position (0,1,0)

// repeat — tile the collection
x.repeat(3)                       // repeat 3 times along axis 0
x.repeat(1, 5)                    // repeat 5 times along axis 1

// enumerate — sliding window / strided views
x.enumerate(4)                    // windows of length 4
x.enumerate(0, 3)                 // along axis 0, window length 3
x.enumerate(0, 3, 1)             // axis 0, length 3, stride 1

// permute — reorder dimensions
x.permute(2, 0, 1)               // (2,3,4) -> (4,2,3)

// pad — add zero-padding to each dimension
x.pad(1, 0, 2)                   // pad 1 on dim-0, 0 on dim-1, 2 on dim-2
                                  // (2,3,4) -> (4,3,8)

// consolidate — collapse traversal into the shape
x.traverse(1).consolidate()

// map / reduce — apply a function element-wise or reduce
x.map(elem -> elem.multiply(c(2.0)))   // map a function over items
x.reduce(elem -> elem.sum())           // reduce each item to a scalar
```

## Comparisons and Conditionals

Comparison operations produce 1.0 (true) or 0.0 (false) element-wise. They can
also select between two values based on the comparison result.

```java
CollectionProducer x = cp(dataX);
CollectionProducer y = cp(dataY);

// Simple boolean masks (1.0 or 0.0)
x.greaterThan(y)              // 1.0 where x > y
x.greaterThanOrEqual(y)       // 1.0 where x >= y
x.lessThan(y)                 // 1.0 where x < y
x.lessThanOrEqual(y)          // 1.0 where x <= y
x.and(y)                      // 1.0 where both are non-zero

// Conditional selection — choose between trueValue and falseValue
CollectionProducer trueVal = c(1.0);
CollectionProducer falseVal = c(0.0);
x.greaterThan(y, trueVal, falseVal)  // trueVal where x > y, falseVal elsewhere
x.and(y, trueVal, falseVal)          // trueVal where both non-zero

// Masking pattern — zero out negative elements (ReLU-like)
CollectionProducer mask = x.greaterThan(c(0.0));
CollectionProducer relu = x.multiply(mask);
```

## Matrix Operations

Matrix multiplication is available through `MatrixFeatures.matmul()`, not as a
method on `CollectionProducer` directly.

```java
// matmul is a static-style method from MatrixFeatures
CollectionProducer result = matmul(matrix, vector);
// matrix: shape (M, N), vector: shape (N) or (N, K) -> result: shape (M) or (M, K)

// Transpose is available directly on CollectionProducer
CollectionProducer transposed = matrix.transpose();
```

## Executing the Graph

A `CollectionProducer` is a lazy description of computation. Three patterns trigger
actual execution:

```java
CollectionProducer graph = cp(a).multiply(cp(b)).add(c(1.0));

// 1. get().evaluate() — compile, then run with no external arguments
PackedCollection result = graph.get().evaluate();

// 2. get().evaluate(args...) — compile, then run with runtime inputs
//    Used when the graph contains input placeholders (v(...))
Evaluable<PackedCollection> compiled = graph.get();
PackedCollection result = compiled.evaluate(inputA, inputB);

// 3. get().into(destination).evaluate() — write result into pre-allocated memory
//    Avoids allocation overhead for repeated execution
PackedCollection buffer = new PackedCollection(shape(10));
graph.get().into(buffer).evaluate();
```

Calling `get()` compiles the graph into an `Evaluable`. Call `get()` once and reuse
the `Evaluable` when running the same graph repeatedly.

## Composition Patterns

Build complex computations by composing simple operations.

### Softmax

```java
// softmax(x) = exp(x - max(x)) / sum(exp(x - max(x)))
CollectionProducer x = cp(logits);          // shape (N)
CollectionProducer shifted = x.subtract(x.max());  // numerical stability
CollectionProducer exps = shifted.exp();
CollectionProducer result = exps.divide(exps.sum());
```

### Mean Squared Error Loss

```java
// MSE = mean((predicted - target)^2)
CollectionProducer predicted = cp(preds);
CollectionProducer target = cp(targets);
CollectionProducer mse = predicted.subtract(target).sq().mean();
```

### Layer Normalization

```java
// layernorm(x) = (x - mean(x)) / sqrt(variance(x) + eps)
CollectionProducer x = cp(input);
CollectionProducer centered = x.subtractMean();
CollectionProducer norm = centered.divide(x.variance().add(1e-5).sqrt());
```

### ReLU Activation (via masking)

```java
CollectionProducer x = cp(input);
CollectionProducer mask = x.greaterThan(c(0.0));
CollectionProducer activated = x.multiply(mask);
```

## Shape Compatibility Rules

Element-wise operations (`add`, `subtract`, `multiply`, `divide`, `pow`) require
operands to have the same total size, or one operand must be a scalar (size 1).

- **Same size**: `shape(3, 4)` with `shape(3, 4)` -- operates element-wise.
- **Scalar**: `shape(3, 4)` with `shape(1)` -- the scalar applies to every element.
- **Mismatched sizes**: Throws `IllegalArgumentException`. Use `reshape`, `repeat`,
  or `traverse` to make shapes compatible before the operation.

Reductions (`sum`, `mean`, `max`, `variance`) replace the item shape with `shape(1)`,
preserving the traversal structure. The axis argument controls which dimension
collapses by setting the traversal axis before reduction.
