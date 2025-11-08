# AR-Algebra Module

**Core algebraic types (Vector, Scalar, Pair) and matrix operations built on PackedCollection.**

## Overview

The `ar-algebra` module provides fundamental mathematical types and operations for the Almost Realism framework. All types are built on `PackedCollection` for hardware acceleration and memory efficiency.

## Core Types

### Vector (3D Vector)

```java
Vector v = new Vector(1.0, 2.0, 3.0);
double x = v.getX();
Vector sum = v1.add(v2);
double dot = v1.dotProduct(v2);
Vector cross = v1.crossProduct(v2);
```

### Scalar (Value + Certainty)

```java
Scalar s = new Scalar(5.0, 0.95);  // Value with 95% certainty
double value = s.getValue();
```

### Pair (Two-Element Tuple)

```java
Pair p = new Pair(3.0, 4.0);
double x = p.getX();
double y = p.getY();
```

## Operations

All operations return `CollectionProducer` for GPU compilation:

```java
CollectionProducer<Vector> normalized = normalize(v);
CollectionProducer<?> dot = dotProduct(a, b);
CollectionProducer<?> matrix = matmul(A, B);
```

## Dependencies

```xml
<dependency>
    <groupId>org.almostrealism</groupId>
    <artifactId>ar-algebra</artifactId>
    <version>0.72</version>
</dependency>
```
