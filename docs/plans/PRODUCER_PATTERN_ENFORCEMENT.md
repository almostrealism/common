# Producer Pattern Enforcement Plan

## Status: In Progress

## Goal

Ensure that all computation in the project is expressed as `CollectionProducer` composition
(the Producer pattern) rather than Java-side arithmetic. Java is the orchestration language
that builds computation graphs; the framework compiles those graphs to native GPU/CPU kernels.

## Problem

When code calls `.evaluate()`, `.toDouble()`, or uses Java loops to do math on
PackedCollections, it hides computation from the native compiler. The result:
- No hardware acceleration (each element access is a JNI call — ~1000x slower)
- No automatic differentiation (gradients cannot flow through host-side computation)
- No compilation/optimization (operations cannot be fused or parallelized)

## Deliverables

### 1. CLAUDE.md — "The Fundamental Rule" section
**Status: Complete**
Added a prominent section at the top of CLAUDE.md explaining that Java is orchestration,
not execution. Includes before/after examples and naming conventions.

### 2. Build-Time Enforcement Tool
**Status: In Progress**
Enhance `CodePolicyViolationDetector` with additional rules:
- `.evaluate()` in computation code (engine/ml/, studio/ source trees)
- `.toDouble()` in computation code
- Cell/Block naming enforcement (classes ending in Cell/Block must implement the interfaces)

### 3. GRUCell / GRUDecoder Fix
**Status: Blocked — Source Not Available**
GRUCell.java and GRUDecoder.java do not exist as source files in this repository.
Only compiled .class files exist in engine/ml/target/. The source must be created
or recovered before these can be converted to the Producer pattern.

### 4. Codebase Audit
**Status: Complete**
Audit results documented in `docs/PRODUCER_PATTERN_VIOLATIONS.md`.

### 5. Developer Guide
**Status: Complete**
Created `docs/COMPUTATION_MODEL.md` explaining the computation graph model,
common operations as Producers, and common mistakes.

## Architecture Notes

### Correct Pattern: Producer Composition
```java
// LayerFeatures.dense() — builds a matmul Producer
CollectionProducer result = matmul(p(weights), input).add(p(bias));

// AttentionFeatures.attention() — builds attention as Producer composition
// Q, K, V projections via dense(), softmax, matmul for attention scores
```

### Incorrect Pattern: Java-Side Math
```java
// CPU loop with toDouble/setMem — defeats GPU parallelism
for (int i = 0; i < size; i++) {
    result.setMem(i, weights.toDouble(i) * input.toDouble(i));
}
```

### Where .evaluate() IS Acceptable
- Test methods
- Main methods
- Pipeline boundaries (top of call stack)
- Step boundaries in autoregressive decode loops (between steps, not within)
