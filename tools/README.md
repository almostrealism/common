# AR-Tools Module

**JavaFX and Swing-based UI tools for visualizing and analyzing operation profiling data.**

## Overview

The `ar-tools` module provides graphical user interfaces for exploring performance profiles of compiled operations in the Almost Realism framework. These tools help developers understand operation hierarchies, execution times, compilation details, and performance bottlenecks.

## Core Components

### OperationProfileUI (Swing)

Classic Swing-based profile viewer with dual-tree layout.

**Launch from command line:**
```bash
java -jar ar-tools.jar /path/to/profile.xml
```

**Programmatic usage:**
```java
OperationProfileUI ui = new OperationProfileUI();
OperationProfileNode profile = OperationProfileNode.load("profile.xml");
JFrame frame = ui.display(profile);
frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
```

### OperationProfileFX (JavaFX)

Modern JavaFX-based profile viewer (default when available).

**Features:**
- Responsive UI with modern styling
- Better rendering performance for large profiles
- Enhanced tree navigation

### ProfileTreeFeatures

Defines how the operation tree is structured and displayed.

#### Tree Structure Modes

**ALL** - Complete hierarchy
```
Shows every operation node in the profile, including intermediate
transformations and non-compiled operations.
```

**COMPILED_ONLY** - Compiled operations only
```
Skips intermediate nodes and shows only operations that were
actually compiled to native code.
```

**STOP_AT_COMPILED** - Expand until compiled
```
Expands the tree down to compiled operations, then stops.
Useful for seeing high-level structure without implementation details.
```

**SCOPE_INPUTS** - Dependency view
```
Shows operation dependencies based on program arguments,
revealing data flow between compiled operations.
```

## UI Features

### Dual Tree View

The main window displays two synchronized tree views:

**Left Panel:** Compiled operations only
- Shows high-level structure
- Focuses on performance-critical compiled code
- Easier to navigate for large profiles

**Right Panel:** Complete operation hierarchy
- Shows all operations including intermediate transforms
- Reveals full compilation pipeline
- Useful for understanding operation composition

**Synchronized Selection:**
Selecting a node in either tree automatically highlights and scrolls to the same operation in the other tree.

### Details Panel

The bottom panel shows detailed information when a node is selected:

**Timing Metrics:**
```
operationName - 5.2 seconds:
  database: 12 [3.1s tot | 258ms avg] 60%
  processing: 5 [1.5s tot | 300ms avg] 29%
  io: 3 [0.6s tot | 200ms avg] 11%
```

**Stage Details:**
```
Stage Details:
  compile: 1 [0.5s tot | 500ms avg] 50%
  execute: 10 [0.5s tot | 50ms avg] 50%
```

**Operation Arguments:**
```
Arguments:
  input: PackedCollection[shape=[1024], mem=4096]
  weight: PackedCollection[shape=[1024, 1024], mem=4194304]
  bias: PackedCollection[shape=[1024], mem=4096]
```

**Generated Source Code:**
```c
// Generated kernel source code
__kernel void operation_123(...) {
    // Implementation
}
```

## Common Workflows

### Performance Analysis

1. **Load profile:** Run your application with profiling enabled
2. **Open profile:** Launch the viewer with the generated profile XML
3. **Navigate to slow operations:** Sort tree by execution time (already sorted)
4. **Inspect timing details:** Select node to see breakdown
5. **Review source:** Check generated code for optimization opportunities

### Debugging Compilation Issues

1. **Use COMPILED_ONLY view:** See which operations were actually compiled
2. **Check arguments:** Verify operation inputs are as expected
3. **Review source:** Inspect generated code for correctness
4. **Compare trees:** Use dual view to understand compilation structure

### Understanding Operation Composition

1. **Use ALL view:** See complete operation hierarchy
2. **Expand slowly:** Understand how high-level operations decompose
3. **Use SCOPE_INPUTS:** View data dependencies between operations

## Creating Profiles

Profiles are automatically generated when profiling is enabled in the hardware module:

```java
// Enable profiling
HardwareFeatures.enableProfiling = true;
HardwareFeatures.profileName = "my_operation_profile";

// Run your code
myModel.forward(input);

// Profile is saved to my_operation_profile.xml
```

**Profile file location:**
Profiles are typically saved in the current working directory or a configurable output path.

## Dependencies

The tools module requires:

**JavaFX:** (v21)
- javafx-base
- javafx-controls
- javafx-graphics
- javafx-fxml
- javafx-web

**Other:**
- ar-ml module (provides profiling data structures)
- ControlsFX (enhanced JavaFX controls)

**Platform-specific classifiers:**
- `mac-aarch64` for Apple Silicon
- `linux` for Linux
- `win` for Windows

## System Requirements

- Java 11 or higher
- JavaFX 21+ runtime
- Graphical display environment

## Limitations

- Very large profiles (>10,000 nodes) may be slow to render
- Swing version is legacy but doesn't require JavaFX
- Some features require specific profile data that may not be available in all contexts

## Tips

**Performance:**
- Start with COMPILED_ONLY view for large profiles
- Use search/filter to find specific operations
- Collapse branches you're not interested in

**Navigation:**
- Double-click to expand/collapse nodes
- Use keyboard arrows for navigation
- Ctrl/Cmd+F for search (if implemented)

**Analysis:**
- Look for operations with high avg times but low counts
- Compare total time percentages to find bottlenecks
- Review compiled source for vectorization opportunities

## Maven Dependency

```xml
<dependency>
    <groupId>org.almostrealism</groupId>
    <artifactId>ar-tools</artifactId>
    <!-- Check pom.xml for current version -->
</dependency>
```

## License

Licensed under the Apache License, Version 2.0.
