# AR-IO Module

**Core I/O, logging, and monitoring infrastructure for the Almost Realism framework.**

## Overview

The `ar-io` module provides essential infrastructure used throughout all Almost Realism modules:

- **Hierarchical Logging System** - Console-based logging with timestamps, listeners, and filters
- **Performance Metrics** - Built-in timing and distribution tracking
- **Alert System** - Pluggable alert delivery for notifications
- **Lifecycle Management** - Lazy initialization and resource cleanup
- **Output Utilities** - File redirection and output formatting

## Core Components

### Logging

#### Console

The `Console` class is the foundation of the logging system:

```java
Console console = Console.root();
console.println("Hello, world!");
console.warn("Something might be wrong");
```

**Key Features:**
- Automatic timestamping (format: `[HH:mm.ss]`)
- Hierarchical console structure (parent/child relationships)
- Extensible listeners for capturing output
- Output filtering and transformation
- Built-in metrics support

**File Output:**
```java
Console.root().addListener(OutputFeatures.fileOutput("/path/to/log.txt"));
Console.root().println("This goes to both console and file");
```

**Child Consoles:**
```java
Console parent = Console.root();
Console child = parent.child();
child.println("This propagates to parent");
```

#### ConsoleFeatures

Interface providing convenient logging methods with automatic class name prefixes:

```java
public class MyProcessor implements ConsoleFeatures {
    public void process() {
        log("Starting processing");
        // Output: "[HH:mm.ss] MyProcessor: Starting processing"

        warn("Low memory");
        // Output: "[HH:mm.ss] WARN: MyProcessor: Low memory"
    }
}
```

**Custom Console:**
```java
public class MyClass implements ConsoleFeatures {
    private Console myConsole = Console.root().child();

    @Override
    public Console console() {
        return myConsole;
    }
}
```

#### OutputFeatures

Utilities for directing console output to files:

```java
// Common pattern in tests
@Test
public void myTest() {
    Console.root().addListener(
        OutputFeatures.fileOutput("/workspace/project/test_output/results.txt"));

    log("Test starting...");
    // Output goes to both console and file
}
```

### Performance Metrics

#### TimingMetric

Measure and track execution times:

```java
TimingMetric timing = Console.root().timing("myOperation");

timing.measure("database", () -> queryDatabase());
timing.measure("processing", () -> processData());
timing.measure("io", () -> writeResults());

Console.root().println(timing.summary());
```

**Output:**
```
myOperation - 5.2 seconds:
  database: 12 [3.1s tot | 258ms avg] 60%
  processing: 5 [1.5s tot | 300ms avg] 29%
  io: 3 [0.6s tot | 200ms avg] 11%
```

#### DistributionMetric

Track distributions of numeric values:

```java
DistributionMetric metric = Console.root().distribution("sizes");
metric.addEntry("small", 10.5);
metric.addEntry("medium", 25.3);
metric.addEntry("large", 100.7);

Console.root().println(metric.summary());
```

### Alert System

#### Alert

Alerts provide structured notifications with severity levels:

```java
Console console = Console.root();

// Simple alerts
console.alert("Operation completed");  // INFO severity
console.alert(Alert.Severity.WARNING, "Low memory");

// Alert from exception
try {
    riskyOperation();
} catch (Exception ex) {
    console.alert("Operation failed", ex);  // ERROR severity
}
```

#### Custom Alert Delivery

```java
console.addAlertDeliveryProvider(alert -> {
    if (alert.getSeverity() == Alert.Severity.ERROR) {
        sendEmailAlert(alert.getMessage());
        notifySlack(alert.getMessage());
    }
});
```

### Lifecycle Management

#### SuppliedValue

Lazy initialization with validation and cleanup:

```java
SuppliedValue<ExpensiveResource> resource =
    new SuppliedValue<>(() -> new ExpensiveResource());

// Resource created only when first accessed
ExpensiveResource r = resource.getValue();

// Clean up when done
resource.destroy();
```

**With Validation:**
```java
SuppliedValue<Connection> conn = new SuppliedValue<>(() -> openConnection());
conn.setValid(c -> c.isConnected());  // Recreate if connection drops

// If connection becomes invalid, getValue() will create a new one
Connection c = conn.getValue();
```

**Custom Cleanup:**
```java
SuppliedValue<FileHandle> handle = new SuppliedValue<>(() -> openFile());
handle.setClear(h -> h.close());  // Custom cleanup logic

handle.clear();  // Calls custom cleanup
```

#### ThreadLocalSuppliedValue

Thread-local version for concurrent access:

```java
ThreadLocalSuppliedValue<ThreadContext> context =
    new ThreadLocalSuppliedValue<>(() -> new ThreadContext());

// Each thread gets its own instance
ThreadContext ctx = context.getValue();
```

### Utilities

#### Describable

Interface for self-describing objects:

```java
public class MyOperation implements Describable {
    @Override
    public String describe() {
        return "MyOperation[input=" + input + ", output=" + output + "]";
    }
}

// Safe description even for null objects
String desc = Describable.describe(myObject);
```

#### SystemUtils

System property and environment variable utilities:

```java
Optional<Boolean> enabled = SystemUtils.isEnabled("AR_FEATURE_FLAG");
```

## Environment Variables

- `AR_IO_SYSOUT=false` - Disable System.out output (only listeners receive output)

## Common Patterns

### Test Output Logging

**Recommended pattern for tests:**

```java
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;

public class MyTest implements ConsoleFeatures {
    @Test
    public void myTest() throws Exception {
        // Set up file logging BEFORE any output
        String logFile = "/workspace/project/common/io/test_output/my_test_results.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("=== My Test ===");
        log("Result: " + someValue);

        // Output goes to BOTH console AND file
    }
}
```

**Benefits:**
- Test output saved to files for later review
- No need to capture stdout/stderr with bash redirects
- Output available even if test crashes
- Easy to compare outputs across multiple test runs

### Performance Monitoring

```java
public class DataProcessor implements ConsoleFeatures {
    private final TimingMetric timing = Console.root().timing("DataProcessor");

    public void process(List<Data> data) {
        timing.measure("loading", () -> loadData(data));
        timing.measure("validation", () -> validateData(data));
        timing.measure("transformation", () -> transformData(data));
        timing.measure("saving", () -> saveData(data));

        log(timing.summary());
    }
}
```

### Duplicate Filtering

Prevent log spam from repeated messages:

```java
Console console = Console.root();
console.addFilter(ConsoleFeatures.duplicateFilter(1000));  // 1 second interval

// Only logged once within 1 second window
for (int i = 0; i < 100; i++) {
    console.println("Status: Processing");
    Thread.sleep(10);
}
```

## Dependencies

The io module has minimal dependencies:
- Java 11+ standard library
- `io.almostrealism.lifecycle.Destroyable` (from lifecycle package)

## Usage in Other Modules

The io module is used extensively throughout the Almost Realism framework:

- **ML Module**: Test output logging, timing metrics for model inference
- **Hardware Module**: Performance metrics, operation compilation logging
- **Graph Module**: Computation graph debugging, execution timing
- **All Modules**: General logging via `ConsoleFeatures`

## Maven Dependency

```xml
<dependency>
    <groupId>org.almostrealism</groupId>
    <artifactId>ar-io</artifactId>
    <version>0.72</version>
</dependency>
```

## License

Licensed under the Apache License, Version 2.0.
