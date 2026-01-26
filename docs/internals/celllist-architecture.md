# CellList Architecture

## Overview

`CellList` is the primary container for building and executing audio processing pipelines
in Almost Realism. It extends `ArrayList<Cell<PackedCollection>>` and provides a fluent
API for constructing complex signal processing chains.

## Core Concepts

### Cell Hierarchy

```
Cell<T>
├── extends Transmitter<T>    - Can send data to downstream Receptors
├── extends Receptor<T>       - Can receive data via push(Producer)
└── extends Cellular          - Has reset() lifecycle method

CellList
├── extends ArrayList<Cell<PackedCollection>>
├── implements Cells          - Combines TemporalCellular, CellFeatures, Iterable
└── implements Destroyable    - Resource cleanup
```

### Data Flow Model

CellList uses a **push-based data flow** model:

1. **Roots** receive initial push signals (typically `c(0.0)`)
2. Cells process data and push results to connected Receptors
3. Data propagates through the graph via `setReceptor()` connections

```java
// Building a pipeline
cell1.setReceptor(cell2);  // cell1 -> cell2
cell2.setReceptor(cell3);  // cell2 -> cell3

// Data flows: push(root) -> cell1 -> cell2 -> cell3
```

## Internal Structure

### Key Fields

```java
public class CellList extends ArrayList<Cell<PackedCollection>> {
    private final List<CellList> parents;        // Parent lists (tick first)
    private final List<Receptor<PackedCollection>> roots;  // Entry points
    private final List<Setup> setups;            // Setup operations
    private final TemporalList requirements;     // Required temporals (tick last)
    private final List<Runnable> finals;         // Cleanup callbacks
    private List<PackedCollection> data;         // Lifecycle-managed data
}
```

### Parent-Child Relationship

The parent-child relationship determines **tick ordering**:

```
Parent CellList(s)  ->  tick FIRST
      |
Current CellList   ->  tick SECOND
      |
Requirements       ->  tick LAST
```

**CRITICAL INSIGHT**: If you want something to tick before the current list's cells,
put it in a parent CellList. This is the proper mechanism for ordering - do NOT
create custom fields for pre-tick operations.

## Tick Execution Order

### getAllTemporals() Collection Order

```java
public TemporalList getAllTemporals() {
    TemporalList all = new TemporalList();

    // 1. PARENTS FIRST - recursively collect all parent temporals
    parents.stream()
        .map(CellList::getAllTemporals)
        .flatMap(Collection::stream)
        .forEach(c -> append(all, c));

    // 2. THIS LIST'S CELLS - cells that implement Temporal
    stream()
        .filter(c -> c instanceof Temporal)
        .forEach(t -> append(all, t));

    // 3. REQUIREMENTS LAST - additional temporal dependencies
    requirements.forEach(c -> append(all, c));

    return all;
}
```

### tick() Method

```java
public Supplier<Runnable> tick() {
    OperationList tick = new OperationList("CellList Tick");

    // 1. Push to all roots first
    getAllRoots().stream()
        .map(r -> r.push(c(0.0)))
        .forEach(tick::add);

    // 2. Tick all temporals (in collection order above)
    tick.add(getAllTemporals().tick());

    return tick;
}
```

## Fluent API Methods

### Pipeline Construction

| Method | Purpose | Example |
|--------|---------|---------|
| `w(int, String)` | Create WaveCell from file | `w(0, "drums.wav")` |
| `d(IntFunction)` | Add delay | `d(i -> _250ms())` |
| `f(IntFunction)` | Add filter | `f(i -> hp(c(500), scalar(0.1)))` |
| `m(IntFunction)` | Add mixer/adapter | `m(i -> new ScaleFactor(0.8))` |
| `o(IntFunction)` | Set output file | `o(i -> new File("output.wav"))` |

### List Operations

| Method | Purpose |
|--------|---------|
| `and(CellList)` | Combine two lists |
| `sum()` | Sum all cell outputs |
| `map(IntFunction)` | Map cells to destinations |
| `branch(IntFunction...)` | Create multiple branches |

### Execution

| Method | Purpose |
|--------|---------|
| `sec(double)` | Get operation for N seconds |
| `min(double)` | Get operation for N minutes |
| `iter(int)` | Get operation for N iterations |
| `buffer(String)` | Create BufferedOutputScheduler |

## Buffer Methods

### buffer(String location)

Creates a `BufferedOutputScheduler` for real-time output:

```java
CellList cells = w(0, "input.wav").m(i -> scale(0.8));
BufferedOutputScheduler scheduler = cells.buffer("/dev/shm/audio");
scheduler.start();
```

### buffer(Producer destination)

Creates a `TemporalRunner` that writes to a destination:

```java
PackedCollection output = new PackedCollection(44100);
TemporalRunner runner = cells.buffer(p(output));
runner.get().run();
```

### toLineOperation()

Converts the CellList to an `AudioLineOperation`:

```java
AudioLineOperation op = cells.toLineOperation();
// Use with BufferedOutputScheduler.create()
```

## Setup and Lifecycle

### Setup Phase

```java
public Supplier<Runnable> setup() {
    OperationList setup = new OperationList("CellList Setup");
    getAllSetup().stream()
        .map(Setup::setup)
        .forEach(setup::add);
    return setup;
}
```

### getAllSetup() Collection Order

1. Parent setups (recursively)
2. Cells that implement Setup
3. Requirements that implement Setup
4. Explicit setups added via `addSetup()`

### Reset

```java
public void reset() {
    finals.forEach(Runnable::run);  // Run cleanup callbacks
    parents.forEach(CellList::reset);
    forEach(Cell::reset);
    requirements.reset();
}
```

### Destroy

```java
public void destroy() {
    Destroyable.super.destroy();
    if (data != null) {
        data.forEach(PackedCollection::destroy);
        data.clear();
    }
    parents.forEach(CellList::destroy);
    forEach(c -> {
        if (c instanceof Destroyable) ((Destroyable) c).destroy();
    });
}
```

## Common Patterns

### Simple Audio Pipeline

```java
CellList pipeline = w(0, "input.wav")   // Load wave file
    .f(i -> hp(c(500), scalar(0.1)))    // High-pass filter at 500Hz
    .m(i -> scale(0.8))                  // Scale to 80%
    .o(i -> new File("output.wav"));     // Output file

pipeline.sec(10).get().run();            // Process 10 seconds
```

### Parallel Processing

```java
CellList drums = w(0, "drums.wav");
CellList synth = w(0, "synth.wav");
CellList combined = drums.and(synth).sum();  // Mix together
```

### Delayed Routing

```java
CellList cells = w(0, "input.wav")
    .d(i -> _250ms())           // 250ms delay
    .d(i -> _500ms())           // 500ms delay (cumulative)
    .m(i -> scale(0.5));        // Reduce feedback
```

### Real-Time Streaming

```java
CellList source = w(0, "input.wav");
BufferedOutputScheduler scheduler = source.buffer("/dev/shm/output");
scheduler.start();

// Later...
scheduler.stop();
source.destroy();
```

## Hierarchical Execution

### Why Parents Matter

Consider this structure:

```java
CellList parent = new CellList();
parent.add(renderCell);  // Renders audio frames

CellList child = new CellList(parent);
child.add(outputCell);   // Writes rendered audio
```

When `child.tick()` is called:
1. `parent`'s temporals tick first (renderCell generates frames)
2. `child`'s temporals tick second (outputCell writes those frames)

This is how proper ordering is achieved WITHOUT special fields.

### Adding Requirements

For temporals that should tick AFTER the cells:

```java
CellList cells = w(0, "input.wav");
cells.addRequirement(postProcessor);  // Ticks after all cells
```

## Integration Points

### With OperationList

CellList methods return `Supplier<Runnable>` which can be `OperationList`:

```java
Supplier<Runnable> tick = cells.tick();
if (tick instanceof OperationList) {
    OperationList ops = (OperationList) tick;
    // Can be compiled to hardware kernel if all ops are Computations
}
```

### With TemporalRunner

```java
TemporalRunner runner = new TemporalRunner(cells, 1024);
runner.get().run();   // Setup + 1024 iterations
runner.getContinue(); // Skip setup, just tick
```

### With AudioScene

`AudioScene` uses CellList internally:

```java
AudioScene scene = new AudioScene(...);
CellList cells = scene.getCells();  // Get the processing pipeline
```

## Related Files

- `Cell.java` - Core cell interface
- `CellFeatures.java` - Static methods for cell construction
- `Cells.java` - Interface combining temporal and cell features
- `Temporal.java` - Time-stepped operations
- `TemporalList.java` - Collection of temporals
- `TemporalRunner.java` - Execution engine for temporal operations
- `BufferedOutputScheduler.java` - Real-time audio scheduling

## See Also

- [Expression Evaluation](expression-evaluation.md) - How operations compile
- [Assignment Optimization](assignment-optimization.md) - Short-circuit paths
