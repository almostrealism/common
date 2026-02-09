# CellList Tick Ordering

## Overview

Understanding tick ordering in CellList is **critical** for building correct audio
processing pipelines. This document explains how tick order is determined and how
to control it properly.

## The Golden Rule

**If you want something to tick before the current list's cells, put it in a
parent CellList.**

Do NOT:
- Create custom fields (like "preTick") to control ordering
- Manually invoke tick operations in special order
- Add ad-hoc mechanisms to bypass the established hierarchy

## Tick Collection Algorithm

### getAllTemporals() Implementation

```java
public TemporalList getAllTemporals() {
    TemporalList all = new TemporalList();

    // 1. PARENTS FIRST
    parents.stream()
        .map(CellList::getAllTemporals)
        .flatMap(Collection::stream)
        .forEach(c -> append(all, c));

    // 2. THIS LIST'S CELLS
    stream()
        .filter(c -> c instanceof Temporal)
        .forEach(t -> append(all, t));

    // 3. REQUIREMENTS LAST
    requirements.forEach(c -> append(all, c));

    return all;
}
```

### Execution Order

1. **Parents' temporals** - Collected recursively, depth-first
2. **Current list's cells** - In list order (ArrayList iteration order)
3. **Requirements** - Added via `addRequirement(Temporal)`

## Visual Representation

```
    ┌─────────────────────────────────────────────┐
    │              CellList Hierarchy              │
    └─────────────────────────────────────────────┘

    ┌───────────────────┐
    │   Parent List A   │  ── ticks first
    │   [cell1, cell2]  │
    └────────┬──────────┘
             │
             v
    ┌───────────────────┐
    │   Parent List B   │  ── ticks second
    │   [cell3]         │
    └────────┬──────────┘
             │
             v
    ┌───────────────────┐
    │   Current List    │  ── ticks third
    │   [cell4, cell5]  │
    │                   │
    │   requirements:   │  ── ticks last
    │   [temporal1]     │
    └───────────────────┘

    Tick Order: cell1 → cell2 → cell3 → cell4 → cell5 → temporal1
```

## Common Scenarios

### Scenario 1: Render Before Output

**Problem**: A render cell must generate frames BEFORE an output cell writes them.

**Wrong Approach**:
```java
// DON'T DO THIS
CellList cells = new CellList();
cells.addPreTick(renderCell);  // <-- WRONG - no such mechanism exists
cells.add(outputCell);
```

**Correct Approach**:
```java
// Correct: Use parent hierarchy
CellList renderList = new CellList();
renderList.add(renderCell);

CellList outputList = new CellList(renderList);  // renderList is parent
outputList.add(outputCell);

// When outputList.tick() runs:
// 1. renderCell ticks first (from parent)
// 2. outputCell ticks second
```

### Scenario 2: Post-Processing

**Problem**: A processor must run AFTER all main cells have ticked.

**Solution**: Use `addRequirement()`

```java
CellList cells = new CellList();
cells.add(cell1);
cells.add(cell2);
cells.addRequirement(postProcessor);  // Ticks after cell1, cell2
```

### Scenario 3: Multiple Dependencies

**Problem**: Complex ordering with multiple sources.

**Solution**: Chain parents

```java
CellList sources = new CellList();
sources.add(source1);
sources.add(source2);

CellList processors = new CellList(sources);
processors.add(processor1);

CellList output = new CellList(processors);
output.add(outputCell);

// Tick order: source1 → source2 → processor1 → outputCell
```

## Full tick() Method

```java
public Supplier<Runnable> tick() {
    OperationList tick = new OperationList("CellList Tick");

    // Push to roots first (triggers data flow)
    getAllRoots().stream()
        .map(r -> r.push(c(0.0)))
        .forEach(tick::add);

    // Then tick all temporals in collected order
    tick.add(getAllTemporals().tick());

    return tick;
}
```

Note that `getAllRoots()` also follows the hierarchy:
1. Parents' roots first
2. Current list's roots

## Using the Fluent API

The fluent API automatically manages parent relationships:

```java
// This creates proper parent-child hierarchy
CellList pipeline = w(0, "input.wav")   // Creates base CellList
    .d(i -> _250ms())                    // Creates child with delay cell
    .f(i -> hp(c(500), scalar(0.1)))    // Creates child with filter cell
    .m(i -> scale(0.8));                 // Creates child with scale cell

// Equivalent hierarchy:
// CellList[WaveCell] (root)
//   └── CellList[DelayCell]
//         └── CellList[FilterCell]
//               └── CellList[ScaleCell]
```

## Debugging Tick Order

### Print Temporal Collection Order

```java
CellList cells = /* your cell list */;
TemporalList temporals = cells.getAllTemporals();

int i = 0;
for (Temporal t : temporals) {
    System.out.println(i++ + ": " + t.getClass().getSimpleName());
}
```

### Verify Parent Chain

```java
CellList current = /* your cell list */;
System.out.println("Parent chain:");
for (CellList parent : current.getParents()) {
    System.out.println("  - " + parent.size() + " cells");
    for (Cell c : parent) {
        System.out.println("      " + c.getClass().getSimpleName());
    }
}
```

## Anti-Patterns to Avoid

### 1. Manual Tick Ordering

```java
// WRONG: Don't manually control order
cell1.tick().get().run();
cell2.tick().get().run();

// CORRECT: Let CellList manage it
CellList cells = new CellList();
cells.add(cell1);
cells.add(cell2);
cells.tick().get().run();
```

### 2. Custom Pre-Tick Fields

```java
// WRONG: Don't invent custom ordering mechanisms
class MyCellList extends CellList {
    List<Temporal> preTick;  // NO!
}

// CORRECT: Use parent CellLists
CellList parent = new CellList();
parent.add(preTemporal);
CellList child = new CellList(parent);
```

### 3. Modifying Tick Order At Runtime

```java
// WRONG: Don't try to change tick order after setup
cells.tick();  // Tick order is already determined
cells.getParents().add(newParent);  // This won't work as expected

// CORRECT: Set up hierarchy before first tick
CellList parent = new CellList();
CellList child = new CellList(parent);
// Now tick
child.tick().get().run();
```

## Integration with AudioScene

`AudioScene` demonstrates proper use of parent hierarchy:

```java
// In AudioScene, pattern rendering uses proper parent relationships
CellList cells = new CellList();
// ... add cells ...

// Add requirements for post-processing
cells.addRequirement(postRenderer);

// Execute
cells.tick().get().run();
```

## Summary

| To achieve... | Use... |
|---------------|--------|
| Tick A before B | Make A's CellList a parent of B's CellList |
| Tick A after B | Add A via `addRequirement()` to B's CellList |
| Complex ordering | Chain multiple parent CellLists |
| Same-time execution | Add to same CellList (order = ArrayList order) |

## Related Files

- `CellList.java` - Main implementation
- `Cells.java` - Interface definition
- `TemporalList.java` - Temporal collection
- `AudioScene.java` - Real-world usage example

## See Also

- [CellList Architecture](celllist-architecture.md) - Full architecture overview
