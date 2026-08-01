# Collect Module (ar-collect)

## Current Status

This module currently contains no source code. It exists as a dependency waypoint in the build chain between ar-code and ar-hardware:

```
ar-meta → ar-io → ar-relation → ar-code → ar-collect → ar-hardware
```

The collection types that conceptually belong here — `Shape`, `Traversable`, `TraversalPolicy`, `CollectionExpression`, `CollectionProducerBase`, and ~40 related types — currently live in ar-code's `io.almostrealism.collect` package. They are hardware-agnostic and should belong here, but they are circularly coupled with ar-code's expression types (expressions reference `CollectionExpression`, collection expressions reference `Expression`). Resolving this requires breaking the expression/collection coupling, which is deferred.

The concrete collection implementation (`PackedCollection`) and the higher-level producer API (`CollectionProducer`, feature interfaces, computation implementations) live in compute/algebra's `org.almostrealism.collect` package, where they have access to hardware memory types.

## Intended Architecture

When the expression/collection coupling is resolved, this module should house:

- **Shape algebra**: `Shape`, `Traversable`, `TraversalPolicy`, `IndexSet`, `TraversalOrdering` — the pure multi-dimensional indexing and traversal framework
- **Collection interfaces**: `Collection`, `CollectionProducerBase`, `Algebraic` — contracts for shaped collections and their producers
- **Collection expressions**: `CollectionExpression` and its ~30 subclasses — the expression tree nodes for collection-level operations (sums, products, conditionals, subsets, etc.)

These types have no hardware dependency. They are pure algebra over shapes, indices, and expression trees.

## What Lives Where Today

| Location | Package | Contents | Hardware Dependency |
|----------|---------|----------|---------------------|
| base/code | `io.almostrealism.collect` | 44 types: Shape, TraversalPolicy, CollectionExpression hierarchy | None |
| compute/algebra | `org.almostrealism.collect` | PackedCollection, CollectionProducer, feature interfaces | Yes (MemoryData) |
| compute/algebra | `org.almostrealism.collect.computations` | 44 computation implementations | Yes |

## Dependencies

Depends on ar-code. Depended on by ar-hardware.
