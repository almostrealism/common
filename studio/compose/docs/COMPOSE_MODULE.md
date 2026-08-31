# Compose Module

This module provides audio persistence, discovery, and composition tools.

## Key Components

| Class | Purpose |
|-------|---------|
| `AudioLibraryPersistence` | Save/load AudioLibrary data to/from Protocol Buffer format |
| `AudioLayerGroupLibrary` | Persist `AudioLayerGroup`s as first-class library entries, content-addressed by MD5 |
| `AudioLayerPitch` | Single, authoritative accessor for the captured pitch of an `AudioLayer` |
| `LibraryDestination` | Manages batched protobuf file paths (PREFIX_0.bin, PREFIX_1.bin, etc.) |
| `PrototypeDiscovery` | Console app and reusable API for finding representative samples using graph algorithms |

## PrototypeDiscovery

A headless console application and reusable API for discovering prototypical audio samples from pre-computed library data using graph algorithms (community detection and centrality).

### CLI Usage

```bash
java -cp ... org.almostrealism.audio.discovery.PrototypeDiscovery \
  --data ~/.almostrealism/library --clusters 5
```

### Programmatic API

```java
// In-process usage (e.g., from a UI controller)
List<PrototypeDiscovery.PrototypeResult> results =
        PrototypeDiscovery.discoverPrototypes(library, 12, statusCallback);

// Each result has: identifier, centrality, communitySize, memberIdentifiers
PrototypeIndexData index = PrototypeDiscovery.buildIndex(results);
library.setPrototypeIndex(index);
```

### How It Works

1. Loads pre-computed features from protobuf files (CLI) or uses an existing AudioLibrary (API)
2. Submits similarity computation jobs via `library.submitSimilarityJobs()`
3. Builds a similarity graph using lightweight `SimilarityNode` instances (identifier + similarities only)
4. Runs Louvain community detection to find clusters
5. Computes PageRank centrality to find the most representative sample in each cluster
6. Outputs the prototype for each cluster

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
library.allDetails().forEach(details -> {
    String identifier = details.getIdentifier();  // MD5 hash

    // Find the provider in the file tree
    WaveDataProvider provider = library.find(identifier);

    if (provider != null) {
        String filePath = provider.getKey();  // Actual file path!
        System.out.println("File: " + filePath);
    }

    // Or, ask the library for the file backing the identifier directly:
    File file = library.fileFor(identifier);
    if (file != null) {
        System.out.println("File: " + file.getAbsolutePath());
    }
});
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
4. Use `library.find(identifier).getKey()` to get the provider's path string,
   or `library.fileFor(identifier)` when you want the backing `File` directly.
   Note that `fileFor` returns a nullable `File` (null when the identifier is
   blank, unknown, or names a file that is no longer present), so it is not a
   drop-in replacement for callers that need a string path or expect a
   non-null result.

```java
// In PrototypeDiscovery, to get the file path:
AudioLibrary library = new AudioLibrary(new File(samplesDir), 44100);
AudioLibraryPersistence.loadLibrary(library, dataPrefix);

// Later, when displaying a prototype:
WaveDetails details = ...;
WaveDataProvider provider = library.find(details.getIdentifier());
String filePath = provider != null ? provider.getKey() : details.getIdentifier();

// Or, when only the file is needed:
File file = library.fileFor(details.getIdentifier());
String filePath = file != null ? file.getAbsolutePath() : details.getIdentifier();
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

### Layer Groups

`AudioLayerGroupLibrary` persists `Audio.AudioLayerGroup`s as first-class
library entries saved beside their member WAVs. Each inline-audio member
is routed through the existing single-sample analysis path so it lands
in the main details store, findable by similarity like any other sample;
the layer is rewritten to carry its MD5 via `audio_ref` instead of the
bulky inline payload. The save is all-or-nothing: a mid-group failure
rolls back every WAV written and every index entry added.

A member's identity is its MD5, not its filename, so the
two-argument `includeGroup(group, wavSource)` writes members to
`<libraryRoot>/<md5>.wav`. The three-argument overload
`includeGroup(group, wavSource, targetSource)` lets the caller choose the
file each member is written to — the right place to put readable names,
since a library full of `a3f2…c1.wav` cannot be browsed. A member already
present in the library is reused where it lies and is not copied again;
`targetSource` is consulted only when the member is new.

The `AudioLayerPitch` helper is the single, authoritative accessor for
the captured pitch of an `AudioLayer`; it returns a `KeyPosition` (the
project's internal pitch type, strictly more general than MIDI), and reads
the live per-member name as `capturedNoteName(layer)` — used by stem
naming so a name and a rendered pitch can never disagree.

#### Where a group belongs, and which files it claims

The library reasons about one kind of thing: groups. A loose file (one
no saved group claims) is treated as a one-layer synthetic group, derived
on demand, never persisted:

```java
// Build the synthetic group standing for a loose file
Audio.AudioLayerGroup group = audioLayerGroupLibrary.syntheticGroup(file, identifier);

// True for a group that was synthesized for a loose file rather than saved
boolean loose = audioLayerGroupLibrary.isSynthetic(group);

// Every canonical path a saved group claims; loose files are not included.
// Resolved through the library's index — a tree still being built asks a
// claim for files it cannot resolve yet, so the claim is the indexed answer
// or nothing.
Set<String> claimed = audioLayerGroupLibrary.claimedPaths();

// Every member of a group, in layer order, as the files the library finds
List<File> members = audioLayerGroupLibrary.membersOf(group);

// Most specific directory holding every member that can be found —
// derived, not stored, so moving a member does not leave stale placement
File location = audioLayerGroupLibrary.locate(group);
```

The `matches(FilterOn, group)` and `filterValue(FilterOn, group)` accessors
let user-written `FileWaveDataProviderFilter`s apply to groups as well as
to the files they were originally written for, since a group carries its
own name and its own path.

### Sidecar WaveDetails Files

`AudioLibraryPersistence.saveWaveDetails` writes a sidecar file alongside
the audio it describes. The raw audio is **not** embedded — only the
analysis (frequency bins, feature vectors, similarity scores, metadata) —
because the audio is already on disk in the file beside it. The matching
`loadWaveDetails` accepts both modern files (no audio) and legacy files
that still carry it, and returns a `WaveDetails` whose `getData()` is
`null` either way. A `WaveDetails` that will only be read for display or
metadata can additionally call `releaseData()` to drop the raw audio and
free that memory.

## Choices File Robustness

Every scene save writes a whole-file JSON document (the `NoteAudioChoice`
list, scene settings, etc.); every scene load reads it back. The polymorphic
entries are tagged by class name, so a stored entry that cannot be
reconstructed — the class was renamed, moved, or removed since the file
was written, or the entry's no-arg constructor is unusable — would
otherwise abort the whole read and discard every other choice in the file.

`AudioSceneLoader.defaultMapper()` is configured to skip rather than fail
on these entries:

- Entries naming a class Jackson cannot resolve are dropped via a
  `DeserializationProblemHandler` and reported through the console.
- Entries naming a class that exists but cannot be instantiated fall
  through the same handler and the same report.
- The corresponding `setSources(...)` setter filters the `null`s out of
  the materialized list, so the rest of the choice survives.

Sources that are *derived* from library state — e.g. `GroupNoteSource`,
which is rebuilt from the saved group store on every assemble — are
excluded from the file entirely via `NoteAudioSource.isPersistent()` and
the matching `NoteAudioChoice.getPersistentSources()` getter.

## See Also

- [Audio Library Documentation](../../../engine/audio/docs/AUDIO_LIBRARY.md) - Core AudioLibrary system
- `AudioSimilarityGraph` - Graph adapter for similarity algorithms
- `CommunityDetection` - Louvain clustering algorithm
- `GraphCentrality` - PageRank and centrality measures
