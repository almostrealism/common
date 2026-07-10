# Profiling Infrastructure

This document explains the AR profiling infrastructure for measuring operation execution times.

## Architecture Overview

The profiling system consists of these key classes:

| Class | Location | Purpose |
|-------|----------|---------|
| `OperationProfile` | `code` module | Base class for collecting timing metrics |
| `OperationProfileNode` | `code` module | Tree structure for hierarchical profiling |
| `TimingMetric` | `io` module | Stores timing entries with counts |
| `OperationSource` | `code` module | Stores generated kernel source code |
| `ProfileData` | `hardware` module | Aggregated run data for hardware operations |

### Data Flow

```
┌─────────────────────┐
│  OperationProfileNode  │  ← Root node, created per profiling session
└──────────┬──────────┘
           │
    ┌──────▼──────┐
    │   Children    │  ← One node per operation (by metadata key)
    └──────┬──────┘
           │
    ┌──────▼──────┐
    │ TimingMetric  │  ← Stores entries like "f_assignment_1047 compile"
    └──────┬──────┘    │                       "f_assignment_1047 run"
           │
    ┌──────▼──────────┐
    │ OperationSource   │  ← Generated kernel code + argument metadata
    └───────────────────┘
```

### Listener Pattern

Profiling data is collected through listeners:

| Listener | Method | Data Collected |
|----------|--------|----------------|
| `OperationTimingListener` | `getRuntimeListener()` | Kernel execution time |
| `CompilationTimingListener` | `getCompilationListener()` | Code generation + native compilation time |
| `ScopeTimingListener` | `getScopeListener()` | Pipeline stage timing details |

## Timing Categories

The profile XML stores timing data with suffixes that indicate the category:

### Compile vs Run

Each operation can have two timing entries:

| Entry Suffix | Meaning | Source |
|--------------|---------|--------|
| `" compile"` | Code generation + native compilation | `recordCompilation()` |
| `" run"` | Kernel execution on hardware | `getRuntimeListener()` |

**Example in profile XML:**
```xml
<void property="metricEntries">
  <object class="java.util.HashMap">
    <void method="put">
      <string>f_assignment_1047 compile</string>
      <double>0.045</double>  <!-- 45ms to compile -->
    </void>
    <void method="put">
      <string>f_assignment_1047 run</string>
      <double>0.002</double>  <!-- 2ms per execution -->
    </void>
  </object>
</void>
```

### Duration Types

| Method | Description |
|--------|-------------|
| `getMeasuredDuration()` | Direct wall-clock time from `measuredTime` metric |
| `getSelfDuration()` | Time from this node's own metric (excluding children) |
| `getChildDuration()` | Sum of all children's `getTotalDuration()` |
| `getTotalDuration()` | `getSelfDuration() + getChildDuration()` |

### Stage Detail Time

The `stageDetailTime` metric captures compilation pipeline stages:

| Stage | Description |
|-------|-------------|
| `generate` | Expression tree to source code generation |
| `compile` | Native compiler invocation |
| `link` | Library linking |

Access via `getStageDetailTime().getEntries()`.

## Usage

### Capturing a Profile (timing and generated source)

Assign the profile as the active profile **before** the operation is compiled. Assigning
installs the profile's compilation listener (`Hardware.assignProfile` →
`AbstractComputeContext.compilationTimingListener`), which records the generated kernel
source of every scope compiled while the profile is active. The `get(...)` call is what
triggers compilation, so it must happen *after* the assignment.

In a test (via `TestFeatures`), `initKernelMetrics(profile)` performs the assignment:

```java
import io.almostrealism.profile.OperationProfileNode;

OperationProfileNode profile = initKernelMetrics(new OperationProfileNode("my_profile"));

// get(...) compiles here, with the profile active, so kernel source is recorded
Runnable r = ((OperationList) op.optimize()).get(profile);
r.run();

profile.save(new File("results/my_profile.xml"));
```

Equivalently, the `profile(name, op)` convenience helper (on `CodeFeatures`) assigns,
compiles, runs, and clears in the correct order:

```java
profile("my_profile", (OperationList) op.optimize())
        .save(new File("results/my_profile.xml"));
```

**Source is recorded only for operations that actually compile while the profile is
active.** An operation compiled earlier — a warm-up run, or a prior run whose signature is
still in the JVM-wide instruction cache — is served from cache without recompiling, so no
source is recorded and `get_source` returns nothing for it. To force a fresh compile when a
warm-up is unavoidable, set `ScopeSettings.enableInstructionSetReuse = false` around the
profiled `get(...)`.

### Inspecting Source with ar-profile-analyzer

```
load_profile(path)                 # summary; total_available_sources > 0 means source was captured
search_operations(path, "sum")     # find the operation node key (has_source in the listing is unreliable)
get_source(path, node_key)         # generated kernel source; if empty, it lists available_source_keys
```

### Saving Profiles

```java
// Save to XML (path is relative to the module working directory)
profile.save("results/my_profile.xml");
```

### Loading and Analyzing

```java
// Load from XML
OperationProfileNode loaded = OperationProfileNode.load("utils/results/my_profile.xml");

// Print summary
loaded.print();

// Find slowest operations
loaded.all()
    .sorted(Comparator.comparingDouble(OperationProfileNode::getTotalDuration).reversed())
    .limit(10)
    .forEach(n -> System.out.println(n.getName() + ": " + n.getTotalDuration()));
```

### Using the CLI

The `ProfileAnalyzerCLI` provides JSON output for integration:

```bash
# Profile summary
mvn -q exec:java -pl tools \
  -Dexec.mainClass=org.almostrealism.ui.ProfileAnalyzerCLI \
  -Dexec.args="summary utils/results/my_profile.xml"

# Find slowest operations
mvn -q exec:java -pl tools \
  -Dexec.mainClass=org.almostrealism.ui.ProfileAnalyzerCLI \
  -Dexec.args="slowest utils/results/my_profile.xml 10"

# Search by name
mvn -q exec:java -pl tools \
  -Dexec.mainClass=org.almostrealism.ui.ProfileAnalyzerCLI \
  -Dexec.args="search utils/results/my_profile.xml matmul"

# Get compile vs run breakdown
mvn -q exec:java -pl tools \
  -Dexec.mainClass=org.almostrealism.ui.ProfileAnalyzerCLI \
  -Dexec.args="breakdown utils/results/my_profile.xml 1047"
```

### Using MCP Tools

The `ar-profile-analyzer` MCP server provides tools for AI agents:

```python
# List available profiles
mcp__ar-profile-analyzer__list_profiles(directory="utils/results")

# Load and summarize
mcp__ar-profile-analyzer__load_profile(path="utils/results/my_profile.xml")

# Find bottlenecks
mcp__ar-profile-analyzer__find_slowest(path="utils/results/my_profile.xml", limit=10)

# Get compile vs run breakdown
mcp__ar-profile-analyzer__get_timing_breakdown(path="utils/results/my_profile.xml", node_key="1047")
```

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `AR_PROFILE_METADATA_WARNINGS` | `false` | Log warnings for duplicate metadata keys |
| `AR_PROFILE_MULTIPLE_SOURCES` | `true` | Allow multiple source versions per operation |

## Interpreting Results

### Identifying Bottlenecks

1. **High compile time, low run time**: Operation compiled frequently or expensive to compile
   - Consider caching compiled operations
   - Check if operation parameters are changing unnecessarily

2. **Low compile time, high run time**: Kernel execution is slow
   - Profile hardware utilization
   - Consider algorithmic optimizations
   - Check memory access patterns

3. **High compile + high run**: Both phases need optimization
   - May indicate complex operation that should be broken down

### Common Patterns

| Pattern | Interpretation |
|---------|----------------|
| One operation dominates | Focus optimization efforts there |
| Many small operations | Consider operation fusion |
| High child duration vs self | Bottleneck is in child operations |
| Recompilation warnings | Operation being compiled multiple times |

## Related Documentation

- [ProfileAnalyzerCLI](../../tools/src/main/java/org/almostrealism/ui/ProfileAnalyzerCLI.java) - CLI implementation
- [OperationProfileFX](../../tools/src/main/java/org/almostrealism/ui/OperationProfileFX.java) - JavaFX UI
- [MCP Profile Analyzer](../../tools/mcp/profile-analyzer/README.md) - MCP server documentation
