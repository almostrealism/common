# PackedCollection GPU Operations - Detailed Examples

This document contains detailed code examples for working with `PackedCollection`.
See the main [CLAUDE.md](../../CLAUDE.md) for the rules.

## The Producer Pattern

Use `CollectionProducer` for GPU-accelerated computation:

```java
// WRONG: CPU loop defeats GPU parallelism
for (int i = 0; i < size; i++) {
    result.setMem(i, source.toDouble(i) * 2);  // Round-trip per element!
}

// CORRECT: GPU-accelerated computation
CollectionProducer result = cp(source).multiply(2.0);
PackedCollection evaluated = result.evaluate();  // Runs on GPU
```

```java
// WRONG: toArray() + manipulation + setMem() forces CPU round-trip
double[] data = collection.toArray();  // GPU -> CPU
for (int i = 0; i < data.length; i++) { data[i] *= 2; }
result.setMem(data);  // CPU -> GPU

// CORRECT: Chained operations stay on GPU
CollectionProducer result = cp(x)
        .subtract(cp(modelOutput).multiply(t))
        .multiply(1.0 - tPrev)
        .add(cp(noise).multiply(tPrev));
return result.evaluate();
```

## Common Operations Reference

| Task | WRONG | CORRECT |
|------|-------|---------|
| Multiply by scalar | `for (i) result.setMem(i, x.toDouble(i) * 2)` | `cp(x).multiply(2.0).evaluate()` |
| Add two collections | `for (i) result.setMem(i, a.toDouble(i) + b.toDouble(i))` | `cp(a).add(cp(b)).evaluate()` |
| Clamp values | `for (i) result.setMem(i, Math.max(min, x.toDouble(i)))` | `max(cp(x), c(min)).evaluate()` |
| Fill with noise | `for (i) result.setMem(i, random.nextGaussian())` | `new PackedCollection(shape).randnFill(random)` |

## Red Flag Patterns

These indicate you are bypassing the hardware abstraction:

- `System.arraycopy` anywhere near `PackedCollection`
- `Arrays.copyOf` with `PackedCollection`
- `for` loops that call `setMem(i, ...)` in a tight loop
- Direct `.toArray()` followed by manipulation followed by `.setMem()`
- Any assumption that `PackedCollection` data is "just there" in JVM memory
