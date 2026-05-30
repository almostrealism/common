# Process Optimization System

## Overview

The Process optimization system is a hierarchical framework for transforming computational
work trees to improve execution efficiency. It operates on four key principles:

1. **Composability**: Processes form tree structures with parent-child dependencies
2. **Optimization**: Trees are recursively analyzed and restructured for efficiency
3. **Isolation**: Child processes can be wrapped to execute independently
4. **Context-Awareness**: Optimization decisions depend on parallelism, memory, and hardware requirements

## Core Architecture

```
Process<P, T>
├── optimize()                  - Entry point for optimization
├── isolate()                   - Wraps for independent execution
├── isIsolationTarget()         - Determines if isolation needed
└── getChildren()               - Returns child processes

ParallelProcess<P, T> extends Process
├── getParallelism()            - Returns parallelism count
├── optimize(context, child)    - Optimizes individual child
└── isUniform()                 - Checks child parallelism equality

ComputableParallelProcess<P, T> extends ParallelProcess
├── createContext()             - Returns ComputableProcessContext
└── isIsolationTarget()         - Checks compute requirements
```

## Process Hierarchy

### Process Interface

The fundamental abstraction for composable computational work.

**Key Methods:**
- `optimize()` / `optimize(ProcessContext)` - Entry point for optimization
- `isolate()` - Wraps process for independent execution via `Process.of(supplier)`
- `isIsolationTarget(ProcessContext)` - Returns `true` if process needs isolation
- `getOutputSize()` - Returns memory footprint for cost analysis

**Static Configuration:**
```java
// Add explicit isolation targets
Process.explicitIsolationTargets.add(p -> p instanceof MyHeavyComputation);

// Check isolation permission
if (Process.isolationPermitted(process)) {
    process = process.isolate();
}
```

### ParallelProcess

Extends Process with parallel execution semantics.

**Key Methods:**
- `getParallelism()` - Returns number of parallel work items (from `Countable`)
- `optimize(ProcessContext ctx, Process<P,T> process)` - Optimizes child, decides isolation
- `isUniform()` - Checks if all children have identical parallelism

**Isolation Decision Logic:**
```java
// In ParallelProcess.optimize(context, process)
if (Process.isExplicitIsolation()) {
    // Use explicit isolation targets
    if (Process.isolationPermitted(process)) {
        return process.isolate();
    }
} else {
    // Use process's own isolation target logic
    if (process.isIsolationTarget(ctx)) {
        return process.isolate();
    }
}
return process;
```

### ComputableParallelProcess

Adds compute requirement handling for hardware-specific operations.

**Isolation Based on Hardware Mismatch:**
```java
@Override
public boolean isIsolationTarget(ProcessContext ctx) {
    if (ctx instanceof ComputableProcessContext) {
        List<ComputeRequirement> contextReqs = ((ComputableProcessContext) ctx).getRequirements();
        List<ComputeRequirement> processReqs = getComputeRequirements();

        // Isolation needed if requirements don't match
        if (!contextReqs.containsAll(processReqs)) {
            return true;
        }
    }
    return super.isIsolationTarget(ctx);
}
```

## Context Hierarchy

Contexts encapsulate optimization state during tree traversal.

```
ProcessContext
├── getOptimizationStrategy()   - Returns current strategy
└── getDepth()                  - Returns nesting level (0 = root)

ParallelProcessContext extends ProcessContext
├── parallelism                 - Work items at current level
├── aggregationCount            - Inputs per output
└── fixed                       - Compile-time determined

ComputableProcessContext extends ParallelProcessContext
└── requirements                - List<ComputeRequirement>
```

### Context Propagation

```java
// Creating root context
ProcessContext root = ProcessContext.base();

// Creating parallel context from process
ParallelProcessContext ctx = ParallelProcessContext.of(parent, childProcess, aggregationCount);

// For single-child: inherits parent unchanged
// For multi-child: uses max parallelism and aggregation
```

## Optimization Flow

```
1. User calls process.optimize() or process.optimize(context)
   ↓
2. ParallelProcess.optimize(context) is invoked
   ├── If no children: return this (unchanged)
   ├── Create ParallelProcessContext from parent context
   ├── For each child:
   │   ├── Recursively call child.optimize(context)
   │   ├── Call optimize(context, optimizedChild) to decide isolation
   │   └── Result is either child or child.isolate()
   └── Delegate to context.getOptimizationStrategy().optimize(...)
   ↓
3. Strategy analyzes parallelism, memory, and tree structure
   ├── Extract parallelism and output size from each child
   ├── Calculate current score vs. isolated score
   ├── Apply strategy-specific rules
   └── Return optimized process (children isolated or not)
   ↓
4. Result is recursively optimized up the tree
```

## Critical Usage Pattern

**Always call optimize() before get():**

```java
// WRONG: Expressions embedded, potential exponential growth
OperationList ops = model.getForward().push(input);
Runnable r = ops.get();  // May timeout or OOM

// CORRECT: Optimization breaks up expression trees
OperationList ops = model.getForward().push(input);
ops = (OperationList) ops.optimize();  // <-- Critical step
Runnable r = ops.get();  // Properly isolated
```

## Isolation Mechanics

### When Isolation Happens

A process becomes isolated when:

1. **Explicit Targets**: Process matches a predicate in `Process.explicitIsolationTargets`
2. **Hardware Mismatch**: Process has compute requirements not in current context
3. **Strategy Decision**: Optimization strategy determines isolation improves performance
4. **Depth Limit**: Expression tree depth exceeds configured threshold
5. **Parallelism Diversity**: Children have vastly different parallelism than parent

### How Isolation Works

```java
// Process.isolate() creates an IsolatedProcess wrapper
Process isolated = process.isolate();  // Returns Process.of(() -> process.get())

// IsolatedProcess does NOT implement TraversableExpression
// When parent calls getValueAt() on an isolated child:
if (child instanceof TraversableExpression) {
    return ((TraversableExpression) child).getValueAt(index);
} else {
    return null;  // Forces compilation boundary
}
```

### Breaking Expression Embedding

Without isolation, expressions get embedded:

```
Parent Expression
├── Child Expression (embedded)
│   ├── Grandchild (embedded)
│   └── Grandchild (embedded)
└── Child Expression (embedded)
    └── ... (keeps growing)
```

With isolation, trees are bounded:

```
Parent Expression
├── [Isolated: compiled separately]
└── [Isolated: compiled separately]
```

## Scoring System

The `ParallelismSettings` class provides scoring utilities:

```java
// Logarithmic value of parallelism (higher is better)
double parallelismValue(count) = 1 + 4096 * log2(count);

// Super-linear memory cost (lower is better)
double memoryCost(size) = size^1.5 / 4096;

// Combined score (higher is better)
double score(parallelism, size) = parallelismValue - memoryCost;
```

**Interpretation:**
- Positive score: Good (parallelism benefits outweigh memory costs)
- Negative score: Problematic (memory dominates)
- Strategies compare current vs. isolated scores to make decisions

## Configuration Points

### Global Strategy

```java
// Set default strategy for all contexts
ProcessContextBase.setDefaultOptimizationStrategy(
    new CascadingOptimizationStrategy(
        new AggregationDepthTargetOptimization(12),
        new ParallelismDiversityOptimization(),
        new ParallelismTargetOptimization()
    )
);
```

### Parallelism Thresholds

```java
ParallelismTargetOptimization.minCount = 256;        // Minimum parallelism
ParallelismTargetOptimization.targetCount = 131072;  // Target threshold
ParallelismTargetOptimization.maxCount = 1048576;    // Maximum before skip
ParallelismTargetOptimization.enableNarrowMax = true;
ParallelismTargetOptimization.enableContextualCount = false;
```

### Debug Monitoring

```java
// Log optimization decisions
ProcessOptimizationStrategy.listeners.add(parent ->
    System.out.println("Optimizing: " + parent));

// Log isolation decisions
ParallelProcess.isolationFlags.add(p -> p instanceof DebugTarget);

// Enable verbose optimization logging
ComputableParallelProcess.enableOptimizationLog = true;
```

## File Locations

| File | Purpose |
|------|---------|
| `relation/.../Process.java` | Core interface, isolation control |
| `relation/.../ParallelProcess.java` | Parallel execution, recursive optimization |
| `relation/.../ProcessContext.java` | Base context interface |
| `relation/.../ProcessContextBase.java` | Default strategy holder |
| `relation/.../ParallelProcessContext.java` | Parallelism tracking |
| `code/.../ComputableProcessContext.java` | Hardware requirement tracking |
| `code/.../ComputableParallelProcess.java` | Compute-requirement isolation |
| `relation/.../ProcessOptimizationStrategy.java` | Strategy interface |
| `relation/.../ParallelismTargetOptimization.java` | Default strategy |
| `relation/.../ParallelismSettings.java` | Scoring utilities |

## See Also

- [Optimization Strategies](optimization-strategies.md) - Detailed strategy documentation
- [Isolation Patterns](isolation-patterns.md) - When and how to use isolation
- [Expression Evaluation](expression-evaluation.md) - How expressions are compiled
- [Kernel Count Propagation](kernel-count-propagation.md) - How counts flow through operations
