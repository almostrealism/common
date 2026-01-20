# Compose Module

This module provides audio persistence, discovery, and composition tools.

## Key Components

| Class | Purpose |
|-------|---------|
| `AudioLibraryPersistence` | Save/load AudioLibrary data to/from Protocol Buffer format |
| `LibraryDestination` | Manages batched protobuf file paths (PREFIX_0.bin, PREFIX_1.bin, etc.) |
| `PrototypeDiscovery` | Console app for finding representative samples using graph algorithms |

## PrototypeDiscovery

A headless console application that discovers prototypical audio samples from pre-computed library data using graph algorithms (community detection and centrality).

### Usage

```bash
java -cp ... org.almostrealism.audio.discovery.PrototypeDiscovery \
  --data ~/.almostrealism/library --clusters 5
```

### How It Works

1. Loads pre-computed features from protobuf files
2. Builds a similarity graph from pre-computed similarity scores
3. Runs Louvain community detection to find clusters
4. Computes PageRank centrality to find the most representative sample in each cluster
5. Outputs the prototype for each cluster

### Resolving Identifiers to File Paths

**IMPORTANT**: The protobuf files store only the **identifier** (MD5 content hash), NOT the file path. To display or access the actual file, you must resolve the identifier to a file path.

**The Problem**: If you only have protobuf data, you only have identifiers like `a1b2c3d4e5f6...`, not paths like `/Users/samples/kick.wav`.

**The Solution**: Use `AudioLibrary` with a file tree to resolve identifiers:

```java
// 1. Create AudioLibrary with the directory containing audio files
File samplesDir = new File("/path/to/samples");
AudioLibrary library = new AudioLibrary(samplesDir, 44100);

// 2. Load pre-computed data from protobuf
AudioLibraryPersistence.loadLibrary(library, dataPrefix);

// 3. For each WaveDetails, resolve identifier to file path
for (WaveDetails details : library.getAllDetails()) {
    String identifier = details.getIdentifier();  // MD5 hash

    // Find the provider in the file tree
    WaveDataProvider provider = library.find(identifier);

    if (provider != null) {
        String filePath = provider.getKey();  // Actual file path!
        System.out.println("File: " + filePath);
    }
}
```

### Key-Identifier Architecture

The system uses two identifiers for different purposes:

| Concept | Method | Returns | Use Case |
|---------|--------|---------|----------|
| **Key** | `WaveDataProvider.getKey()` | File path | Display, file access |
| **Identifier** | `WaveDataProvider.getIdentifier()` | MD5 hash | Deduplication, storage |

**Why two identifiers?**
- Same audio content = same identifier, even at different paths
- Protobuf stores only identifier (content-based)
- File paths resolved at runtime via `library.find(identifier)`

### PrototypeDiscovery File Path Resolution

To make PrototypeDiscovery display file paths instead of identifiers, you need to:

1. Accept an additional `--samples` argument for the audio files directory
2. Create an `AudioLibrary` with that directory
3. Load protobuf data into the library
4. Use `library.find(identifier).getKey()` to get file paths

```java
// In PrototypeDiscovery, to get the file path:
AudioLibrary library = new AudioLibrary(new File(samplesDir), 44100);
AudioLibraryPersistence.loadLibrary(library, dataPrefix);

// Later, when displaying a prototype:
WaveDetails details = ...;
WaveDataProvider provider = library.find(details.getIdentifier());
String filePath = provider != null ? provider.getKey() : details.getIdentifier();
```

## AudioLibraryPersistence

Handles serialization/deserialization of `AudioLibrary` data to Protocol Buffer format.

### Saving

```java
AudioLibrary library = ...;
AudioLibraryPersistence.saveLibrary(library, "/path/to/library");
// Creates: library_0.bin, library_1.bin, etc.
```

### Loading

```java
// Option 1: Load with file tree (can resolve identifiers to paths)
AudioLibrary library = new AudioLibrary(new File("/path/to/samples"), 44100);
AudioLibraryPersistence.loadLibrary(library, "/path/to/library");

// Option 2: Load without file tree (identifiers only, no paths)
AudioLibrary library = AudioLibraryPersistence.loadLibrary(
    null, 44100, "/path/to/library");  // WARNING: Can't resolve file paths!
```

### What Gets Stored

- Identifier (MD5 content hash) - used as the key
- Audio metadata (sample rate, channels, frame count)
- Frequency analysis data (FFT results)
- Feature data (for similarity computation)
- Pre-computed similarity scores

**NOT stored**: File paths (resolved at runtime via `AudioLibrary.find()`)

## See Also

- [Audio Library Documentation](../../audio/docs/AUDIO_LIBRARY.md) - Core AudioLibrary system
- `AudioSimilarityGraph` - Graph adapter for similarity algorithms
- `CommunityDetection` - Louvain clustering algorithm
- `GraphCentrality` - PageRank and centrality measures
