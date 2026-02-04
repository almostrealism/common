# Profiling Infrastructure - Examples

This document contains detailed examples for using the profiling infrastructure.
See the main [profiling.md](profiling.md) for architecture details.

## Correct Patterns

### Basic Profiling Setup

```java
// CORRECT: Create profile, attach to hardware, run operations, save
OperationProfileNode profile = new OperationProfileNode("training_profile");
Hardware.getLocalHardware().getCompileScope().setProfile(profile);

// Run your operations...
model.forward(input);

// Save when done
profile.save("utils/results/training_profile.xml");

// Detach profile
Hardware.getLocalHardware().getCompileScope().setProfile(null);
```

### Analyzing Compile vs Run Time

```java
// CORRECT: Use the breakdown command to understand timing categories
// CLI command:
// mvn -q exec:java -pl tools \
//   -Dexec.mainClass=org.almostrealism.ui.ProfileAnalyzerCLI \
//   -Dexec.args="breakdown utils/results/my_profile.xml 1047"

// Returns JSON with compile_time, run_time, compile_count, run_count
```

### Finding Performance Bottlenecks

```java
// CORRECT: Systematic approach
// 1. Get overall slowest operations
OperationProfileNode profile = OperationProfileNode.load("my_profile.xml");

List<OperationProfileNode> slowest = profile.all()
    .filter(n -> n.getMeasuredDuration() > 0)
    .sorted(Comparator.comparingDouble(OperationProfileNode::getMeasuredDuration).reversed())
    .limit(10)
    .collect(Collectors.toList());

// 2. For each slow operation, check compile vs run
for (OperationProfileNode node : slowest) {
    TimingMetric metric = node.getMetric();
    if (metric != null) {
        Map<String, Double> entries = metric.getEntries();
        double compileTime = entries.entrySet().stream()
            .filter(e -> e.getKey().endsWith(" compile"))
            .mapToDouble(Map.Entry::getValue)
            .sum();
        double runTime = entries.entrySet().stream()
            .filter(e -> e.getKey().endsWith(" run"))
            .mapToDouble(Map.Entry::getValue)
            .sum();

        System.out.println(node.getName() + ": compile=" + compileTime + "s, run=" + runTime + "s");
    }
}
```

### Interpreting Stage Details

```java
// CORRECT: Check stage detail time for compilation pipeline breakdown
OperationProfileNode node = profile.getProfileNode("1047").orElseThrow();
TimingMetric stageDetail = node.getStageDetailTime();

if (stageDetail != null) {
    Map<String, Double> stages = stageDetail.getEntries();
    // stages may contain: "generate", "compile", "link"
    stages.forEach((stage, time) ->
        System.out.println("  " + stage + ": " + time + "s"));
}
```

## Wrong Patterns (DO NOT USE)

### Ignoring Compile Time

```java
// WRONG: Only looking at total duration misses compile time issues
List<OperationProfileNode> slowest = profile.all()
    .sorted(Comparator.comparingDouble(OperationProfileNode::getTotalDuration).reversed())
    .limit(10)
    .collect(Collectors.toList());

// This doesn't tell you if the time is in compile or run!
// An operation might have high compile time but fast execution.
```

### Not Detaching Profile

```java
// WRONG: Leaving profile attached impacts performance
OperationProfileNode profile = new OperationProfileNode("test");
Hardware.getLocalHardware().getCompileScope().setProfile(profile);

// ... run operations ...
// Profile still attached - subsequent operations still being profiled!
// This adds overhead even when you don't want profiling.
```

```java
// CORRECT: Always detach when done
try {
    Hardware.getLocalHardware().getCompileScope().setProfile(profile);
    // ... run operations ...
} finally {
    Hardware.getLocalHardware().getCompileScope().setProfile(null);
}
```

### Profiling in Production

```java
// WRONG: Profiling has overhead, don't leave it enabled
public class MyService {
    private OperationProfileNode profile = new OperationProfileNode("always_on");

    public void process() {
        Hardware.getLocalHardware().getCompileScope().setProfile(profile);  // WRONG!
        // ... processing ...
    }
}
```

```java
// CORRECT: Profile only when explicitly requested
public class MyService {
    public void process(boolean enableProfiling) {
        OperationProfileNode profile = enableProfiling ?
            new OperationProfileNode("debug_profile") : null;

        try {
            if (profile != null) {
                Hardware.getLocalHardware().getCompileScope().setProfile(profile);
            }
            // ... processing ...
        } finally {
            Hardware.getLocalHardware().getCompileScope().setProfile(null);
            if (profile != null) {
                profile.save("debug_profile.xml");
            }
        }
    }
}
```

### Misinterpreting Duration Types

```java
// WRONG: Confusing measuredDuration with totalDuration
double duration = node.getTotalDuration();  // Includes children!

// If you want the operation's own time, use:
double selfTime = node.getSelfDuration();       // Metric entries only
double measured = node.getMeasuredDuration();    // Direct wall-clock time
```

## Common Debugging Workflows

### "Why is my model slow?"

1. Enable profiling and run the model
2. Use `find_slowest` to identify top bottlenecks
3. For each bottleneck, use `breakdown` to check compile vs run
4. If compile-heavy: check for unnecessary recompilation
5. If run-heavy: optimize the kernel or data access patterns

### "Why is compilation taking so long?"

1. Enable profiling and run a single forward pass
2. Look for operations with high " compile" times
3. Check `stageDetailTime` to see which compilation stage is slow
4. Look for recompilation warnings in output

### "Why does first run take longer?"

1. First run includes all compilation time
2. Profile first run and subsequent runs separately
3. Compare compile times - first run should have high compile, later runs near-zero
4. If later runs still have compile time, operations are being recompiled
