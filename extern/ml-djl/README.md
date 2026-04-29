# ML-DJL Module (ar-ml-djl)

Provides SentencePiece tokenization via the [DJL (Deep Java Library)](https://djl.ai/) framework.

## Overview

This module contains a single adapter class that wraps DJL's SentencePiece tokenizer implementation, making it available through the AR framework's `Tokenizer` interface. SentencePiece is a language-independent subword tokenizer commonly used in models like T5, BERT, and audio generation models.

## Key Types

- **`SentencePieceTokenizer`** — implements `org.almostrealism.ml.Tokenizer`, wraps DJL's `SpTokenizer` and `SpVocabulary`. Provides `encode(String)` and `decode(int[])` methods plus vocabulary access.

## Usage

```java
// Load a SentencePiece model
InputStream modelStream = Files.newInputStream(Path.of("tokenizer.model"));
SentencePieceTokenizer tokenizer = new SentencePieceTokenizer(modelStream);

// Encode text to token IDs
int[] tokens = tokenizer.encode("Hello, world!");

// Decode token IDs back to text
String text = tokenizer.decode(tokens);

// Get vocabulary size
int vocabSize = tokenizer.getVocabulary().size();
```

## Dependencies

- **ar-ml** — provides the `Tokenizer` interface
- **ai.djl:sentencepiece** — DJL SentencePiece native binding

## Design

This module exists to isolate the DJL dependency from the rest of the framework. The `Tokenizer` interface is defined in ar-ml; this module provides the DJL-backed implementation. Applications choose their tokenizer implementation at configuration time without the core ML module needing to know about DJL.
