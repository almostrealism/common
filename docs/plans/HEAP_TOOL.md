# Offline Heap Dump Analyzer

## Problem

When a JVM crashes with OOM or produces a heap dump (`.hprof`), the existing ar-jmx tooling cannot help because it requires a live process. There is no built-in JDK tool for offline `.hprof` analysis (jhat was removed in JDK 9). Developers must install Eclipse MAT or VisualVM separately, and neither integrates with the existing MCP/CLI workflow.

## Proposal

Add offline `.hprof` analysis capability to the ar-jmx module (or a sibling module). The tool should produce the two most diagnostic views for OOM investigation:

1. **Class histogram** — top N classes by instance count and shallow size. This immediately tells you *what* is filling the heap (e.g., 4 million `WaveDetails` objects, 800K `byte[]` arrays).

2. **Dominator tree summary** — top N objects by retained size. This tells you *who is holding* the memory (e.g., a single `HashMap` retaining 6 GB because cleanup loaded every entry).

## Interface

### CLI

```bash
# Class histogram (default top 30)
java -jar ar-heap-analyzer.jar histogram java_pid17905.hprof
java -jar ar-heap-analyzer.jar histogram --top 50 java_pid17905.hprof

# Dominator tree
java -jar ar-heap-analyzer.jar dominators java_pid17905.hprof

# Summary (both views + heap metadata)
java -jar ar-heap-analyzer.jar summary java_pid17905.hprof
```

### MCP integration

Add tools to the ar-jmx MCP server so Claude can analyze dumps directly:

- `analyze_heap_dump(path, mode="summary")` — runs the analyzer and returns structured results
- Reuses the existing histogram diffing/formatting from `histogram_parser.py`

## Implementation Options

The HPROF binary format is documented ([HPROF spec](https://hg.openjdk.org/jdk/jdk/file/tip/src/hotspot/share/services/heapDumper.cpp)). The key records are:

- `HEAP_DUMP_SEGMENT` containing `INSTANCE_DUMP`, `OBJECT_ARRAY_DUMP`, `PRIMITIVE_ARRAY_DUMP`
- `LOAD_CLASS` / `STRING` records for resolving class names
- Object reference graphs for computing retained sizes

### Option A: Java module using NetBeans Profiler libraries

NetBeans Profiler's heap walker (`org.netbeans.lib.profiler.heap`) is on Maven Central and can parse HPROF files. It handles the binary format, class resolution, and GC root traversal. Build a thin CLI wrapper that extracts histogram and dominator data.

Pros: Mature, handles large heaps, Java-native (fits the repo)
Cons: Retained-size computation can be slow on very large dumps

### Option B: Python parser extending ar-jmx

Write a streaming HPROF parser in Python that reads the binary format incrementally. For histograms this is straightforward (single pass). Dominator trees require building the object graph, which is memory-intensive for large dumps.

Pros: Integrates directly into the existing MCP server, histograms are cheap
Cons: Dominator computation in Python on an 8 GB dump may be impractical

### Option C: Hybrid — Java parser, Python MCP integration

Java module does the heavy parsing and writes JSON results. The ar-jmx MCP server invokes it as a subprocess and presents the results. This gets the performance of Java for graph traversal with the MCP integration of Python.

### Recommendation

**Option C** (hybrid) is the best fit. The Java side handles the binary parsing and graph algorithms efficiently, while the Python MCP side provides the Claude-facing interface. The Java module lives in `tools/` alongside the existing CLI tools, and the MCP server gains a new `analyze_heap_dump` tool that shells out to it.

For an MVP, the class histogram alone (single-pass, no graph needed) covers 80% of OOM diagnosis. Dominator tree computation can be added as a follow-up.
