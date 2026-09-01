# Audio Library System

This document describes the audio library system used for managing, analyzing, and persisting audio sample collections.

## Overview

The audio library system provides:
- Asynchronous loading and analysis of audio files
- Frequency analysis and feature extraction
- Similarity computation between samples
- Protobuf-based persistence for fast loading of pre-computed data

## Key-Identifier Architecture

The system uses a **two-level identification scheme**:

| Concept | Method | Example | Purpose |
|---------|--------|---------|---------|
| **Key** | `WaveDataProvider.getKey()` | `/path/to/kick.wav` | File location (display, access) |
| **Identifier** | `WaveDataProvider.getIdentifier()` | `a1b2c3d4e5f6...` (MD5) | Content hash (deduplication, storage) |

### Why Two Identifiers?

1. **Content-based deduplication**: Same audio content = same identifier, even at different paths
2. **File movement detection**: If a file moves, the identifier stays the same
3. **Efficient storage**: Protobuf stores only the identifier; file paths are resolved at runtime

## Core Classes

### AudioLibrary

The main class for managing audio collections.

```java
// Create from directory
AudioLibrary library = new AudioLibrary(new File("/path/to/samples"), 44100);

// Analyze all files
library.refresh().thenRun(() -> {
    // Analysis complete
});

// Get details by identifier
WaveDetails details = library.get(identifier);

// Resolve identifier to file path
WaveDataProvider provider = library.find(identifier);
String filePath = provider.getKey();

// Or, equivalently, ask the library for the file backing the identifier
File file = library.fileFor(identifier);
```

**Internal data structures:**
- `identifiers` map: key (file path) → identifier (MD5 hash)
- `detailsCache`: a `FrequencyCache<String, WaveDetails>` keyed by identifier (bounded, LFU/LRU eviction)
- `completeIdentifiers`: a `Set<String>` tracking which identifiers have complete data (freqData + featureData)

### WaveDataProvider

Interface for audio data sources. Key methods:

| Method | Returns | Description |
|--------|---------|-------------|
| `getKey()` | File path | Location of the audio source |
| `getIdentifier()` | MD5 hash | Content-based identifier |
| `get()` | WaveData | The actual audio data |
| `getSampleRate()` | int | Native sample rate |

**Implementations:**
- `FileWaveDataProvider` - Loads from WAV files
- `DelegateWaveDataProvider` - Extracts a slice of another provider
- `SupplierWaveDataProvider` - Wraps a supplier function

### WaveDetails

Stores analyzed metadata for an audio sample:

- Basic metadata (sample rate, channels, frame count)
- Frequency analysis data (FFT results)
- Feature data (for similarity computation)
- Pre-computed similarity scores to other samples

The raw PCM audio is held by `WaveDetails.getData()` only when the sample
was loaded or computed; for sidecar files written by
`AudioLibraryPersistence.saveWaveDetails` the audio is *not* embedded (the
file sits beside the WAV it describes), and `loadWaveDetails` returns a
`WaveDetails` whose `getData()` is `null`. Call `releaseData()` to drop a
loaded sample's raw audio when only the analysis is needed — the underlying
memory is destroyed rather than left for the collector, since a decoded
collection reads directly out of the parsed message and would otherwise
keep the entire recording on the heap for the lifetime of the details.

### AudioLibraryPersistence

Handles saving/loading library data to Protocol Buffer format.

```java
// Save library to protobuf
AudioLibraryPersistence.saveLibrary(library, "/path/to/data/library");
// Creates: library_0.bin, library_1.bin, etc.

// Load library from protobuf + file tree
AudioLibrary library = new AudioLibrary(new File("/path/to/samples"), 44100);
AudioLibraryPersistence.loadLibrary(library, "/path/to/data/library");
```

## Common Operations

### Loading Pre-computed Data with File Paths

When loading from protobuf, you need BOTH the protobuf data AND a file tree to resolve file paths:

```java
// 1. Create library with file tree
AudioLibrary library = new AudioLibrary(new File("/path/to/samples"), 44100);

// 2. Load pre-computed data from protobuf
AudioLibraryPersistence.loadLibrary(library, "/path/to/data/library");

// 3. Now you can resolve identifiers to file paths
library.allDetails().forEach(details -> {
    String identifier = details.getIdentifier();

    // Find the provider in the file tree
    WaveDataProvider provider = library.find(identifier);

    if (provider != null) {
        String filePath = provider.getKey();
        System.out.println(filePath + ": " + details.getFrameCount() + " frames");
    }
});
```

### Resolving Identifiers to Files

When only the backing file is needed, `AudioLibrary.fileFor(identifier)` returns
it directly. The search goes by content identifier, not filename, so a sample
can be renamed for display without anything that references it having to know
the name. The result is `null` when the identifier is blank, unknown, or names
a file that is no longer present.

```java
File file = library.fileFor(identifier);
if (file != null) {
    System.out.println("Backing file: " + file.getAbsolutePath());
}
```

The `fileFor` path consults a `ContentIndex` the library holds; the index is
populated by `indexFiles()` and by the tree-refresh path, so resolving an
identifier that has been seen before does not walk the tree. Call `indexFiles`
to force the index to reflect the entire tree at once — useful before a batch
of lookups that have not yet populated the index:

```java
// Walk the tree once and cache every identifier → file mapping.
int indexed = library.indexFiles();

// Resolve through the index alone; never searches the tree.
File fast = library.indexedFileFor(identifier);

// How many identifiers the index currently holds; -1 means none.
int count = library.getIndexedFileCount();

// Generation counter; compare against the value held when a derivation was
// made to see whether the library has moved on. Increments every time the
// index is replaced — by indexFiles(), by clearFileIndex(), and by the
// tree-refresh path.
long generation = library.getIndexGeneration();

// Drop the index, returning fileFor() to walking the tree for every call
// it has not already cached.
library.clearFileIndex();
```

`indexedFileFor` exists for callers that cannot afford to search the tree —
a caller being asked which directory a group belongs to cannot search the
tree, because building the group directory depends on which groups belong in
it, and that depends on where each group's members are. The index is rebuilt
whenever the tree is refreshed, so callers that need the shortcut and the
ordinary `fileFor` path see the same answers for any identifier the tree
holds.

### Computing Similarities

```java
// Get similarities for a specific sample
Map<String, Double> similarities = library.getSimilarities(details);

// Similarities are stored bidirectionally
// If A is similar to B, both A.getSimilarities() and B.getSimilarities()
// will contain the relationship
```

### Building a Similarity Graph

```java
// Create graph from library (nodes are lightweight SimilarityNode instances,
// not full WaveDetails — only identifier + similarity map are retained)
AudioSimilarityGraph graph = library.toSimilarityGraph();

// Or from a collection of WaveDetails (constructor extracts lightweight nodes)
AudioSimilarityGraph graph = new AudioSimilarityGraph(detailsList);

// Use with graph algorithms (graph implements IndexedGraph<SimilarityNode>)
int[] communities = CommunityDetection.louvain(graph, 1.0);
double[] ranks = GraphCentrality.pageRank(graph, 0.85, 50);

// Access node identifiers from the graph
SimilarityNode node = graph.nodeAt(index);
String identifier = node.getIdentifier();
```

## Protobuf Schema

The library data is stored in `AudioLibraryData` protobuf messages:

```protobuf
message AudioLibraryData {
  map<string, WaveDetailData> info = 1;  // identifier -> details
}

message WaveDetailData {
  optional string identifier = 18;       // MD5 content hash
  int32 sample_rate = 2;
  int32 channel_count = 3;
  int32 frame_count = 4;
  CollectionData freq_data = 10;         // FFT results
  CollectionData feature_data = 16;      // Feature vectors
  map<string, double> similarities = 11; // Pre-computed similarities
}
```

**Note:** The protobuf does NOT store file paths. File paths are resolved at runtime by matching identifiers against the file tree.

## File Organization

```
audio/
├── src/main/java/org/almostrealism/audio/
│   ├── AudioLibrary.java              # Main library class
│   ├── data/
│   │   ├── WaveDataProvider.java      # Provider interface
│   │   ├── FileWaveDataProvider.java  # File-based provider
│   │   ├── WaveDetails.java           # Analyzed metadata
│   │   └── WaveDetailsFactory.java    # Creates WaveDetails
│   └── similarity/
│       ├── AudioSimilarityGraph.java  # Graph adapter (IndexedGraph<SimilarityNode>)
│       └── SimilarityNode.java        # Lightweight node: identifier + similarities only

compose/
├── src/main/proto/
│   └── audio.proto                    # Protobuf schema
└── src/main/java/org/almostrealism/audio/persistence/
    ├── AudioLibraryPersistence.java   # Save/load to protobuf
    └── LibraryDestination.java        # File path management
```

## See Also

- `AudioSimilarityGraph` - Graph adapter for similarity algorithms
- `CommunityDetection` - Louvain clustering algorithm
- `GraphCentrality` - PageRank and other centrality measures
