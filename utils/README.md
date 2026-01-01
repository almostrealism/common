# AR-Utils Module

**Testing framework, model training utilities, and helper classes for the Almost Realism framework.**

## Overview

The `ar-utils` module provides cross-cutting infrastructure for:
- **Testing** - Core test infrastructure used across all modules
- **Model Training** - ML model training and optimization support  
- **Hardware Testing** - Kernel testing and performance profiling
- **Utilities** - Charts, authentication, imagery, and misc helpers

## Core Components

### TestSuiteBase

Base class for all Almost Realism tests. Extend this to get automatic test depth filtering and parallel test execution support.

```java
public class MyTest extends TestSuiteBase {
    @Test
    public void basicTest() {
        // Runs at any depth
        assertEquals(expected, actual);
    }

    @Test
    @TestDepth(2)
    public void expensiveTest() {
        // Only runs if AR_TEST_DEPTH >= 2
    }
}
```

### TestDepth Annotation

Control which tests run based on depth level:

| Depth | Usage |
|-------|-------|
| No annotation | Basic smoke tests (always run) |
| `@TestDepth(1)` | Medium complexity tests |
| `@TestDepth(2)` | Comprehensive tests |
| `@TestDepth(3)` | Heavy/expensive tests |
| `@TestDepth(10)` | Very expensive tests |

### TestFeatures

Interface providing comprehensive test utilities. Implemented by TestSuiteBase.

```java
// Assertions
assertEquals(expected, actual);
assertTrue(condition);
assertSimilar(collection1, collection2, tolerance);

// Kernel testing
kernelTest(operation);

// Hardware metrics
initKernelMetrics();
logKernelMetrics();
```

### TestSettings

Global test configuration via environment variables:

```bash
# Test depth and filtering
export AR_TEST_DEPTH=2          # Test thoroughness level (default: 9)
export AR_TEST_PROFILE=pipeline # Test profile (enables all tests)

# Test behavior
export AR_LONG_TESTS=true       # Enable long-running tests
export AR_TRAIN_TESTS=true      # Enable training tests
export AR_KNOWN_ISSUES=true     # Include tests for known issues

# Parallel execution (CI)
export AR_TEST_GROUP=0          # Which group to run (0-3)
export AR_TEST_GROUPS=4         # Total number of groups
```

### ModelTestFeatures

ML-specific testing utilities:

```java
public class ModelTest implements ModelTestFeatures {
    @Test
    public void trainModel() {
        // Generate dataset
        PackedCollection<?> data = generateDataset(...);
        
        // Train model
        Model model = createModel();
        trainModel(model, data, epochs, learningRate);
        
        // Profile performance
        logOptimizationMetrics(model);
    }
}
```

### Utilities

**Chart** - ASCII visualization:
```java
Chart chart = new Chart();
chart.addValue(metric);
chart.display();  // ASCII chart
```

**KeyUtils** - Cryptographic utilities:
```java
String uuid = KeyUtils.generateUUID();
String hash = KeyUtils.sha256(data);
```

**ProcessFeatures** - Execute external processes:
```java
String output = executeProcess("command", args);
```

## Parallel Test Execution

Tests can be split across multiple VMs for faster CI execution using hash-based grouping:

```bash
# Run tests in parallel across 4 VMs
VM1: mvn test -DAR_TEST_GROUP=0 -DAR_TEST_GROUPS=4
VM2: mvn test -DAR_TEST_GROUP=1 -DAR_TEST_GROUPS=4
VM3: mvn test -DAR_TEST_GROUP=2 -DAR_TEST_GROUPS=4
VM4: mvn test -DAR_TEST_GROUP=3 -DAR_TEST_GROUPS=4
```

Each test class is deterministically assigned to a group based on its class name hash.
Tests not extending `TestSuiteBase` will run in all groups.

## Testing Patterns

### Kernel Testing

```java
@Test
public void testOperation() implements TestFeatures {
    // Define operation
    Producer<?> op = createOperation();
    
    // Test kernel execution
    kernelTest(op);
    
    // Verify results
    assertEquals(expected, op.get().evaluate());
}
```

### Hardware Metrics

```java
@Test
public void profilePerformance() implements TestFeatures {
    initKernelMetrics();
    
    // Run operation
    operation.get().evaluate();
    
    logKernelMetrics();  // Logs timing, memory usage
}
```

## Dependencies

```xml
<dependency>
    <groupId>org.almostrealism</groupId>
    <artifactId>ar-utils</artifactId>
    <!-- Check pom.xml for current version -->
</dependency>
```

## License

Licensed under the Apache License, Version 2.0.
