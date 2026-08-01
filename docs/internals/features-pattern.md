# The Features Mixin Pattern

## What It Is

A Features interface is a Java interface whose methods are all `default` -- it carries
no abstract contract and requires no implementation from the class that adopts it.
Adding `implements CollectionFeatures` to a class declaration immediately makes
40+ factory methods available as if they were instance methods.

```java
public class MyComputation implements CollectionFeatures {
    public void example() {
        CollectionProducer<?> data = c(10.0);
        CollectionProducer<?> result = add(data, c(5.0));
    }
}
```

Every method on a Features interface is a *factory* -- it constructs a
`CollectionProducer`, a `TraversalPolicy`, a `PackedCollection`, or another
framework object.  The implementing class gains these capabilities without
inheriting from a base class.

## Why This Pattern

Java does not support multiple inheritance of state, but it does allow a class
to implement many interfaces.  The Features pattern exploits this to provide
composable capability sets:

- **No deep hierarchies.** A class can mix in `CollectionFeatures`,
  `RGBFeatures`, and `CellFeatures` independently.
- **No delegation boilerplate.** Unlike composition with helper objects,
  the methods appear directly on the class.
- **Orthogonal concerns.** Arithmetic operations, shape manipulation,
  color construction, and ray math live in separate interfaces that
  compose freely.

## The Core Hierarchy

The collection-level Features interfaces form a linear chain.  Each level
extends the one below it, accumulating more operations:

```
ShapeFeatures                     shape(), position()
  ^
CollectionTraversalFeatures       traverse(), enumerate(), repeat()
  ^
CollectionCreationFeatures        pack(), cp(), c(), zeros(), concat()
  ^
SlicingFeatures                   subset(), reshape()
  ^
ArithmeticFeatures                add(), multiply(), minus(), exp(), log(),
                                  floor(), min(), max(), abs(), sigmoid()
  ^
AggregationFeatures               sum(), mean()
  ^
ComparisonFeatures                greaterThan(), lessThan(), conditional()
  ^
GradientFeatures                  gradient operations
  ^
CollectionFeatures                top-level entry point (200+ methods)
```

`CollectionFeatures` also extends `CollectionCreationFeatures` directly,
giving it the full set of creation methods alongside the chain above.

A class that implements `CollectionFeatures` inherits the entire stack.
A class that only needs shapes can implement `ShapeFeatures` alone.

## Key Methods

| Interface                    | Representative methods                              |
|------------------------------|-----------------------------------------------------|
| `ShapeFeatures`              | `shape(int... dims)`                                |
| `CollectionCreationFeatures` | `pack(double...)`, `c(double...)`, `cp(PackedCollection)` |
| `SlicingFeatures`            | `subset(TraversalPolicy, Producer, int)`, `reshape()`|
| `ArithmeticFeatures`         | `add()`, `multiply()`, `minus()`, `exp()`, `log()`, `floor()`, `min()`, `max()`, `abs()` |
| `AggregationFeatures`        | `sum()`, `mean()`                                   |
| `ComparisonFeatures`         | `greaterThan()`, `lessThan()`, `conditional()`      |
| `CollectionFeatures`         | All of the above plus `c(Producer)`, `assign()`, `random()`, `enumerate()`, `repeat()` |

### Scalar constant -- `c()`

`c(double...)` creates a constant `CollectionProducer` from literal values.
Multiple overloads accept a `TraversalPolicy`, an `Evaluable`, or another
`Producer`.

### Collection reference -- `cp()`

`cp(PackedCollection)` wraps an existing `PackedCollection` as a
`CollectionProducer`, making it usable inside computation graphs.

### Shape construction -- `shape()`

`shape(int... dims)` returns a `TraversalPolicy` describing the dimensionality
of a collection (e.g., `shape(3, 4)` is a 3x4 matrix layout).

### Packing -- `pack()`

`pack(double...)` creates a `PackedCollection` directly from literal values.
Overloads accept a `TraversalPolicy` to attach shape metadata.

## Domain-Specific Features

Beyond the core collection stack, domain modules provide their own Features
interfaces.  Each follows the same pattern -- an interface full of default
factory methods -- but targets a specific problem domain.

### RGBFeatures (color)

Extends `ScalarFeatures`.  Provides color construction and image I/O.

```java
public class MyShader implements RGBFeatures {
    public void shade() {
        CollectionProducer<?> red = rgb(1.0, 0.0, 0.0);
        CollectionProducer<?> mixed = rgb(rProducer, gProducer, bProducer);
        Supplier<Runnable> save = saveRgb("output.png", mixed);
    }
}
```

Key methods: `rgb()`, `white()`, `channels()`, `saveRgb()`, `attenuation()`.

### RayFeatures (geometry)

Extends `VectorFeatures`.  Provides ray construction and component extraction.

```java
public class MyTracer implements RayFeatures {
    public void trace() {
        CollectionProducer<?> r = ray(origin, direction);
        CollectionProducer<?> o = origin(r);
        CollectionProducer<?> d = direction(r);
        CollectionProducer<?> point = pointAt(r, t);
    }
}
```

Key methods: `ray()`, `origin()`, `direction()`, `pointAt()`.

### VectorFeatures (vector math)

Extends `ScalarFeatures`.  Provides 3D vector construction.

Key methods: `v(Vector)`, `vector(double, double, double)`, `value(Vector)`.

### CellFeatures (audio graph construction)

Extends `HeredityFeatures`, `TemporalFeatures`, and `CodeFeatures`.
Provides factory methods for building audio computation graphs using the
Cell/Receptor/Transmitter pattern.

## Naming Convention

Features interfaces follow a consistent naming scheme:

- **Interface name:** `*Features` (e.g., `CollectionFeatures`, `RayFeatures`,
  `ScalarFeatures`, `HeredityFeatures`).
- **Factory method names:** Short, often 1-3 characters for the most common
  operations (`c`, `cp`, `v`, `rgb`, `ray`).  Longer names describe the
  operation (`add`, `multiply`, `subset`, `pointAt`, `origin`, `direction`).
- **Value wrapping:** A method named `v(X)` or `value(X)` wraps a domain
  object as a `CollectionProducer`.

## How Classes Adopt Features

A class simply adds the desired Features interface to its `implements` clause.
Multiple Features interfaces can be combined:

```java
public class ScaleFactor implements Factor<PackedCollection>,
                                    ScalarFeatures,
                                    CollectionFeatures {
    // All factory methods from both interfaces are available
}
```

Real examples from the codebase:

| Class                        | Features mixed in                          |
|------------------------------|--------------------------------------------|
| `PackedCollection`           | `CollectionFeatures`                       |
| `AudioDiffusionGenerator`    | `CollectionFeatures`, `ConsoleFeatures`    |
| `ScaleFactor`                | `ScalarFeatures`, `CollectionFeatures`     |
| `BatchedCell`                | `CollectionFeatures`                       |
| `PatternAudioBuffer`         | `CollectionFeatures`                       |

Because these are interfaces with only default methods, there is no
constructor coupling, no super-call chains, and no diamond-problem
ambiguity (all paths lead to the same default method).

## Summary

| Aspect              | Detail                                                   |
|---------------------|----------------------------------------------------------|
| Pattern             | Interface with `default` methods only (mixin)            |
| Adoption            | `implements CollectionFeatures`                          |
| Composition         | Multiple Features interfaces on one class                |
| Naming              | `*Features` for interfaces; short names for factories    |
| Core entry point    | `CollectionFeatures` (200+ methods, full stack)          |
| Domain extensions   | `RGBFeatures`, `RayFeatures`, `VectorFeatures`, `CellFeatures` |
