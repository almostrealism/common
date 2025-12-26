# AR-Utils Module

**Testing framework, model training utilities, and helper classes for the Almost Realism framework.**

## Overview

The `ar-utils` module provides cross-cutting infrastructure for:
- **Testing** - Core test infrastructure used across all modules
- **Model Training** - ML model training and optimization support  
- **Hardware Testing** - Kernel testing and performance profiling
- **Utilities** - Charts, authentication, imagery, and misc helpers

## Core Components

### TestFeatures

Primary testing interface providing comprehensive test utilities.

```java
public class MyTest implements TestFeatures {
    @Test
    public void test() {
        // Assertions
        assertEquals(expected, actual);
        assertTrue(condition);
        
        // Collection comparison
        assertSimilar(collection1, collection2, tolerance);
        
        // Kernel testing
        kernelTest(operation);
        
        // Hardware metrics
        initKernelMetrics();
        logKernelMetrics();
    }
}
```

### TestSettings

Global test configuration via environment variables:

```bash
export AR_LONG_TESTS=true       # Enable long-running tests
export AR_TRAIN_TESTS=true      # Enable training tests
export AR_TEST_DEPTH=2          # Test thoroughness level
export AR_TEST_PROFILE=pipeline # Test profile
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
