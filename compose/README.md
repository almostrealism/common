# AR-Compose Module

Audio persistence, discovery, and composition tools for the Almost Realism framework.

## Key Components

| Class | Package | Purpose |
|-------|---------|---------|
| `AudioLibraryPersistence` | `o.a.audio.persistence` | Save/load library to Protocol Buffer |
| `LibraryDestination` | `o.a.audio.persistence` | Batched protobuf file management |
| `PrototypeDiscovery` | `o.a.audio.discovery` | Find representative samples via graph algorithms |

## Key-Identifier Architecture

**CRITICAL**: Protobuf stores only the **identifier** (MD5 hash), NOT file paths.

To resolve an identifier to a file path, you need BOTH:
1. The protobuf data (contains identifiers and features)
2. A file tree (directory of audio files)

```java
// Create library with file tree
AudioLibrary library = new AudioLibrary(new File("/path/to/samples"), 44100);

// Load protobuf data
AudioLibraryPersistence.loadLibrary(library, "/path/to/library");

// Resolve identifier to file path
WaveDataProvider provider = library.find(identifier);
String filePath = provider.getKey();  // Actual file path!
```

### Why This Matters

| What You Have | Method | Returns |
|---------------|--------|---------|
| WaveDetails from protobuf | `details.getIdentifier()` | MD5 hash (e.g., `a1b2c3d4...`) |
| WaveDataProvider from library | `provider.getKey()` | File path (e.g., `/samples/kick.wav`) |

**To convert identifier → file path**: `library.find(identifier).getKey()`

## PrototypeDiscovery

Console app for finding representative samples using graph algorithms.

```bash
java -cp ... org.almostrealism.audio.discovery.PrototypeDiscovery \
  --data ~/.almostrealism/library --clusters 5
```

**Note**: To display file paths (not just identifiers), PrototypeDiscovery needs access to the original audio files directory. Use `AudioLibrary.find(identifier).getKey()` to resolve identifiers to paths.

## See Also

- [Detailed Compose Documentation](docs/COMPOSE_MODULE.md)
- [Audio Library Documentation](../audio/docs/AUDIO_LIBRARY.md)
