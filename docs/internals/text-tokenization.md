# Text Tokenization: from a String to the token ids a model consumes

Every language-model capability the platform has — the self-hosted byte-level LM that trains on
this documentation, Qwen3 and Llama2 inference, and the Stable Audio 3 / T5Gemma text conditioning
— begins at the same boundary: a `String` becomes a sequence of integer token ids, and generated
ids become a `String` again. This is the one place a reader must understand *bytes, Unicode,
vocabularies and merge priorities* before any computation graph is involved. Everything downstream
— embedding lookup, attention, the output projection — operates on ids, not text.

This page describes what the tokenizers do today, read off the source. Every complete Java excerpt
below is copied from `TextTokenizationExamplesTest` in `engine/ml`, which asserts the behavioral
claim each example makes; it runs on any backend with no downloaded artifact.

## The contract: the `Tokenizer` interface

`org.almostrealism.ml.Tokenizer` is the common interface for turning text into ids and back. Its
two abstract methods work in `long`; everything else is a default:

```java
public interface Tokenizer {
    long[] encodeAsLong(String text);
    String decodeAsLong(long[] tokens);

    default int[] encodeAsInt(String text) { /* narrows encodeAsLong */ }
    default String decodeAsInt(int[] tokens) { /* widens to decodeAsLong */ }

    default PackedCollection encode(String text) { throw new UnsupportedOperationException(); }
    default String decode(PackedCollection tokens) { throw new UnsupportedOperationException(); }
}
```

A single implementation of `encodeAsLong` / `decodeAsLong` gives a tokenizer both the `long` and
the `int` APIs. The `PackedCollection` defaults throw: producing a GPU-resident tensor of ids is
not something any tokenizer in the tree does today (see "Where tokens meet the computation graph").
The interface has no notion of special tokens — BOS/EOS live on the concrete classes.

Which classes implement the interface, and which do not, is deliberate and worth stating plainly:

- `ByteTokenizer` **implements** `Tokenizer`.
- `SentencePieceBPETokenizer` **implements** `Tokenizer`.
- `extern/ml-djl`'s `SentencePieceTokenizer` **implements** `Tokenizer`.
- `Qwen3Tokenizer` **does not** implement `Tokenizer`. It extends `ByteLevelBPETokenizer` and
  exposes its own `encode(String, boolean, boolean)` and the inherited `decode(int[])`, returning
  `int` arrays rather than the interface's `encodeAsLong` / `decodeAsLong`. Its special tokens are
  the public constants `BOS_TOKEN` (151643) and `EOS_TOKEN` (151645); the `getBOSToken()` /
  `getEOSToken()` it inherits are `protected`. Whether it *should* implement `Tokenizer` is an open
  API decision tracked separately (tracker task `0b5eb918`); this page documents the class as it is.
- `Llama2` tokenizes through the static `BPE.encode(...)` utility, not through an instance of any
  tokenizer class at all (see the family tree).

## The family tree

Every text tokenizer in the platform, what it reads, how it draws boundaries, how it treats an
unknown character, and which models use it:

| Class / path | Module | Vocabulary source | Boundary scheme | Unknown character | Special tokens | Used by |
|---|---|---|---|---|---|---|
| `ByteTokenizer` (`org.almostrealism.ml.tokenization`) | `engine/ml` | none — fixed 256 byte values | none (every byte is a token) | impossible — every byte is in the vocabulary | none | self-hosted LM (`CausalLanguageModel`) |
| `SentencePieceBPETokenizer` (`org.almostrealism.ml.tokenization`) | `engine/ml` | `ARTK` binary from `export_tokenizer.py` | `U+2581` marks a word boundary | one `<0xNN>` token per UTF-8 byte | carried in the binary; `encodeAsLong` adds none | Gemma / T5Gemma, so Stable Audio 3 |
| `Qwen3Tokenizer` (`org.almostrealism.ml.qwen3`) | `engine/ml` | `tokenizer.bin` + `merges.txt` | GPT-2 byte-to-Unicode remapping (`ByteLevelEncoder`) | byte-level remap (no character is out of vocabulary) | public `BOS_TOKEN` / `EOS_TOKEN` constants; `encode` adds on request | Qwen3 |
| `BPE.encode(...)` + `Llama2` (`org.almostrealism.ml`, `org.almostrealism.ml.llama2`) | `engine/ml` | llama2.c `tokenizer.bin` (score + token per entry), loaded by `Llama2` | highest-score greedy merge; a leading space is stripped from the first generated token on decode | none — a character missing from the vocabulary throws | `Llama2` prepends BOS (id 1) itself before the encoded ids | Llama2 |
| `SentencePieceTokenizer` (`org.almostrealism.ml`) | `extern/ml-djl` | a SentencePiece `.model` file, via DJL `SpTokenizer` | `U+2581`, replaced with a space on decode | delegated to DJL | delegated to DJL | DJL-backed paths |

Two classes named for SentencePiece sit in different modules and must not be confused: the
`engine/ml` `SentencePieceBPETokenizer` reads the platform's own `ARTK` binary and implements the
SentencePiece BPE scheme in Java; the `extern/ml-djl` `SentencePieceTokenizer` is a thin wrapper
over DJL's native `SpTokenizer` and a `.model` file. The `llms.txt` index entry for `ml-djl` names
the DJL one; this page is about the `engine/ml` path.

`MidiTokenizer` and `SkyTntTokenizerV2` (in the studio modules) are *not* text tokenizers: they
turn MIDI events into compound token sequences. They are mentioned here only so a reader searching
for "tokenizer" is not surprised by them.

## `ByteTokenizer`

`ByteTokenizer` is the simplest tokenizer in the tree and the one the self-hosted LM uses. Its
vocabulary is the 256 byte values, fixed; there is nothing to train and no external artifact to
load. Text is encoded as its UTF-8 bytes, and every byte becomes one token whose id is the unsigned
byte value (`0..255`). The mapping byte ↔ id is a bijection over all 256 values, with no sign
extension.

```java
ByteTokenizer tokenizer = new ByteTokenizer();

String text = "café";
long[] ids = tokenizer.encodeAsLong(text);

// "café" is five UTF-8 bytes: 'c', 'a', 'f', and two for 'é'.
Assert.assertEquals(5, ids.length);
for (long id : ids) {
    Assert.assertTrue(id >= 0 && id < ByteTokenizer.VOCAB_SIZE);
}
Assert.assertEquals(text, tokenizer.decodeAsLong(ids));
```

Encoding uses standard UTF-8, including its replacement behaviour: an unpaired surrogate in the
input becomes `'?'` (`0x3F`). The `String` round-trip therefore holds for every well-formed UTF-16
string, and only for those. Decoding reads the bytes as UTF-8 with standard replacement — one
`U+FFFD` per malformed input sequence — so the *byte*-level mapping is lossless but the `String`
API is not, for a malformed byte sequence. `isValidUtf8(int[])` reports, under a strict decoder,
whether a sequence of ids is well-formed UTF-8, which distinguishes a byte-level model emitting a
partial multi-byte sequence from real text. Any id outside `0..255` passed to a decode method is
rejected with an `IllegalArgumentException` rather than narrowed onto a valid byte.

Why a byte tokenizer for the self-hosted LM? It owes nothing to an external artifact, there is
nothing to train, every byte is reachable, and the model that trains on this corpus can therefore
describe its own input layer without reference to a vocabulary file it does not ship.

## The BPE pipeline in `ByteLevelBPETokenizer`

`ByteLevelBPETokenizer` is the abstract base for the two learned-vocabulary tokenizers in
`engine/ml` (`Qwen3Tokenizer` and `SentencePieceBPETokenizer`). Its `encode(String, boolean)`
drives the full pipeline, in this order:

1. **Added-token splitting first.** `splitAddedTokens` scans the raw text left to right and carves
   out any *added token* (a control token or other entry of the source tokenizer's added
   vocabulary) atomically, so its content becomes its single id wherever it appears, before any
   other step runs. Raw added tokens are matched before normalized ones. The match is
   leftmost-longest, indexed by first character, so a long accepted token does not make a
   non-matching input scan quadratically.
2. **Pre-tokenization.** Each remaining span is split into segments by a `PreTokenizer` —
   `RegexPreTokenizer` (the GPT-2/Qwen regex) for the byte-level subclass, or `PreTokenizer.WHOLE_TEXT`
   (no split) for the SentencePiece subclass.
3. **Symbol formation (`toSymbols`).** This is the step where the two families diverge (below).
4. **Priority-ordered merges (`applyBPEMerges`, `getMergePriority`).** At each step the
   highest-priority applicable merge (lowest index in the merge list) is applied, until none
   remains.
5. **Vocabulary lookup.** Each final symbol is mapped to its id via `vocabMap`; a symbol with no id
   falls back to `getUNKToken()`.
6. **Decode (`fromSymbols`).** Ids map back to their vocabulary strings, special tokens skipped
   (`isSpecialToken`), and the subclass reassembles text.

`encode(String, boolean)` optionally brackets the result with BOS/EOS (when the subclass defines
them); the merge algorithm, the vocabulary lookup and the special-token handling are shared, so a
subclass customizes only `toSymbols` / `fromSymbols`.

**Where the two subclasses differ.** The byte-level subclass (`Qwen3Tokenizer`) maps each segment
through `ByteLevelEncoder` — the GPT-2 byte-to-Unicode remapping — so every byte becomes a printable
character and no character is ever out of vocabulary. The SentencePiece subclass
(`SentencePieceBPETokenizer`) instead marks a word boundary with `U+2581` and, for a character the
vocabulary does not contain, falls back to one `<0xNN>` token per UTF-8 byte; `flushBytes`
reassembles a run of byte tokens into the multi-byte characters they encode on decode.

The merge/boundary behaviour, exercised against `SentencePieceTokenizerFixture` (a tiny synthetic
`ARTK` binary): the two single characters merge, then the result merges with the boundary that
precedes it, and decoding restores the spaces.

```java
SentencePieceTokenizerFixture fixture = new SentencePieceTokenizerFixture();
SentencePieceBPETokenizer tokenizer = fixture.tokenizer();

long[] ids = tokenizer.encodeAsLong(SentencePieceTokenizerFixture.PROMPT);

Assert.assertArrayEquals(SentencePieceTokenizerFixture.EXPECTED, ids);
Assert.assertEquals(SentencePieceTokenizerFixture.PROMPT, tokenizer.decodeAsLong(ids));
```

The byte fallback: a character absent from the vocabulary becomes one `<0xNN>` token per UTF-8
byte, and decoding folds those tokens back into the character.

```java
SentencePieceTokenizerFixture fixture = new SentencePieceTokenizerFixture();
SentencePieceBPETokenizer tokenizer = fixture.tokenizer();

long[] ids = tokenizer.encodeAsLong(SentencePieceTokenizerFixture.UNKNOWN);

// '€' (U+20AC) is three UTF-8 bytes, so it falls back to three byte tokens.
int byteCount = SentencePieceTokenizerFixture.UNKNOWN.getBytes(StandardCharsets.UTF_8).length;
Assert.assertEquals(byteCount, ids.length);
Assert.assertEquals(SentencePieceTokenizerFixture.UNKNOWN, tokenizer.decodeAsLong(ids));
```

`Qwen3Tokenizer` is driven through its own API — `encode(String, boolean, boolean)` for
`(text, addBos, addEos)` and the inherited `decode(int[])` — not the `Tokenizer` interface. Its
special tokens are the public `BOS_TOKEN` / `EOS_TOKEN` constants, and the control tokens are
skipped on decode:

```java
Qwen3Tokenizer tokenizer = Qwen3Tokenizer.createTestTokenizer();

int[] plain = tokenizer.encode("Hello World!", false, false);
int[] bracketed = tokenizer.encode("Hello World!", true, true);

Assert.assertEquals(plain.length + 2, bracketed.length);
Assert.assertEquals(Qwen3Tokenizer.BOS_TOKEN, bracketed[0]);
Assert.assertEquals(Qwen3Tokenizer.EOS_TOKEN, bracketed[bracketed.length - 1]);
Assert.assertEquals(tokenizer.decode(plain), tokenizer.decode(bracketed));
```

## The exporter and the `ARTK` format

`SentencePieceBPETokenizer` does not read `tokenizer.json`. A 256k-token vocabulary is tens of
megabytes of JSON, and `engine/ml` has no JSON parser on its classpath, so the conversion happens
once, in Python, through `engine/ml/scripts/export_tokenizer.py`, into a compact length-prefixed
binary a `DataInputStream` can read with nothing else. Length prefixes rather than delimiters are
required because the vocabulary contains tokens that are runs of newlines and runs of `U+2581`,
which no newline- or space-delimited encoding survives.

The format is `ARTK` version 2: a magic (`0x4152544B`), a version, the vocabulary in token-id order
(each a length-prefixed UTF-8 string), the merge list in priority order (each merge a pair of
length-prefixed strings), the four control ids (`bos`, `eos`, `pad`, `unk`, each `-1` when absent),
and the added tokens (each an id, a normalized flag, a special flag, and its content). The layout
is specified authoritatively in the `export_tokenizer.py` module docstring and the
`SentencePieceBPETokenizer` Javadoc; this page states it once and links them rather than
duplicating the byte-by-byte spec.

**What `validate_pipeline` accepts and rejects, and why.** The exporter refuses a source tokenizer
whose pipeline the Java reader does not reproduce, so a mismatch fails in Python rather than
silently producing wrong ids at inference time. It requires a BPE model with `byte_fallback`;
rejects BPE `dropout`, `ignore_merges`, and subword prefix/suffix decoration (each changes the ids);
requires the normalizer to be exactly the space→`U+2581` replacement and nothing else; requires the
pre-tokenizer to be a no-op after that substitution; and requires the decoder to be
`Replace(boundary → space)` then `ByteFallback` (an optional trailing `Fuse` is allowed).

- **Reachable byte fallback.** Byte fallback applies per character: HuggingFace BPE expands a
  character outside the vocabulary into its UTF-8 byte tokens only when *all* of them exist, and the
  Java reader reproduces exactly that and rejects anything else. So every `<0xNN>` token a fallback
  *can reach* must be present. `_unreachable_byte` identifies the ones that need not be: bytes
  `0xC0`, `0xC1` and `0xF5`–`0xFF` never occur in UTF-8 at all, and an ASCII byte is unreachable
  when its character never reaches fallback as a standalone symbol — because it is its own
  vocabulary token, an added token, or the literal space the normalizer has already replaced with
  the boundary marker. The space's byte `<0x20>` is therefore *always* unreachable: `validate_pipeline`
  unconditionally adds the space to the set of present characters before testing.
- **`fuse_unk`.** It only ever merges consecutive unknown tokens. Because every reachable byte token
  is required, no unknown token is ever produced, so `fuse_unk` has no observable effect and is
  accepted.
- **Reader limits.** `MAX_ENTRIES` (`1 << 24`) caps the vocabulary, merge and added-token counts;
  `MAX_STRING_BYTES` (`1 << 20`) caps any single serialized string. The reader checks these before
  allocating, turning a corrupt count into a controlled `IOException` rather than a
  `NegativeArraySizeException` or `OutOfMemoryError`; the exporter mirrors the same bounds so every
  binary it writes is loadable.
- **The post-processor rule.** The causality runs from the reader to the exporter. The `ARTK` binary
  carries no post-processor, and `SentencePieceBPETokenizer.encodeAsLong` adds no BOS/EOS, so
  `validate_pipeline` accepts a source post-processor only when it is a pass-through: a
  `TemplateProcessing` whose single-sequence template is exactly `$A` (the input ids, once). A
  post-processor that adds a special token, or repeats the sequence (e.g. `[$A, $A]`), is rejected,
  because the Java reader would never reproduce it.

Because `encodeAsLong` adds no special tokens, a caller that wants BOS/EOS adds them itself, or uses
`encode(String, boolean)` directly:

```java
SentencePieceTokenizerFixture fixture = new SentencePieceTokenizerFixture();
SentencePieceBPETokenizer tokenizer = fixture.tokenizer();

long[] plain = tokenizer.encodeAsLong(SentencePieceTokenizerFixture.PROMPT);
int[] bracketed = tokenizer.encode(SentencePieceTokenizerFixture.PROMPT, true);

Assert.assertArrayEquals(SentencePieceTokenizerFixture.EXPECTED, plain);
Assert.assertEquals(plain.length + 2, bracketed.length);
Assert.assertEquals(SentencePieceTokenizerFixture.BOS, bracketed[0]);
Assert.assertEquals(SentencePieceTokenizerFixture.EOS, bracketed[bracketed.length - 1]);
```

The reader applies the same defensiveness at load time: a merge whose concatenated result is not in
the vocabulary, a control id that is neither `-1` nor a valid index, an added token whose id is out
of range or whose content disagrees with the vocabulary at that id, and a wrong magic or version
are each rejected with a descriptive `IOException`, rather than loading and emitting an id no
embedding row exists for later.

## Where tokens meet the computation graph

Tokenization is host code: the loops over characters, the hash-map lookups, and the merges all run
in the JVM, producing a `long[]` or `int[]` of ids. Those ids are the boundary between orchestration
and execution. A model consumes them by looking up embedding rows — in this platform, an embedding
is a Producer that gathers rows of a weight table (`LayerFeatures.embedding`, the PDSL `embedding`
built-in), and from that point on everything is a computation graph. The ids themselves arrive as
plain host arrays or, for an autoregressive loop, one id at a time.

The `Tokenizer.encode(String)` / `decode(PackedCollection)` defaults throw precisely because no
tokenizer in the tree produces a GPU-resident tensor of ids directly; the sanctioned host-to-device
boundary for tokenizer output is an open design question (tracker task `490c98f2`). See
[ml-inference-pipeline.md](ml-inference-pipeline.md) for how loaded weights become generated tokens,
and [training-a-language-model.md](training-a-language-model.md) for how `ByteTokenizer` ids feed
`NextTokenDataset` when the self-hosted LM trains on this documentation.

## Related files

| Path | Role |
|---|---|
| `engine/ml/src/main/java/org/almostrealism/ml/Tokenizer.java` | The `encodeAsLong` / `decodeAsLong` contract and its defaults |
| `engine/ml/src/main/java/org/almostrealism/ml/tokenization/ByteTokenizer.java` | The 256-symbol byte tokenizer |
| `engine/ml/src/main/java/org/almostrealism/ml/tokenization/ByteLevelBPETokenizer.java` | The shared BPE pipeline and added-token splitting |
| `engine/ml/src/main/java/org/almostrealism/ml/tokenization/ByteLevelEncoder.java` | GPT-2 byte-to-Unicode remapping |
| `engine/ml/src/main/java/org/almostrealism/ml/tokenization/RegexPreTokenizer.java` | The GPT-2/Qwen pre-tokenization regex |
| `engine/ml/src/main/java/org/almostrealism/ml/tokenization/SentencePieceBPETokenizer.java` | The `ARTK` reader and the `U+2581` / byte-fallback scheme |
| `engine/ml/src/main/java/org/almostrealism/ml/qwen3/Qwen3Tokenizer.java` | The Qwen3 byte-level tokenizer and its own encode/decode API |
| `engine/ml/src/main/java/org/almostrealism/ml/BPE.java` | The static greedy-merge encoder Llama2 uses |
| `engine/ml/src/main/java/org/almostrealism/ml/llama2/Llama2.java` | Loads the llama2.c tokenizer and prepends BOS |
| `extern/ml-djl/src/main/java/org/almostrealism/ml/SentencePieceTokenizer.java` | The DJL-backed SentencePiece tokenizer (a different class) |
| `engine/ml/scripts/export_tokenizer.py` | Exports a HuggingFace BPE tokenizer to the `ARTK` binary |
| `engine/ml/src/test/java/org/almostrealism/ml/tokenization/TextTokenizationExamplesTest.java` | The drift guard for this page's examples |
| `engine/ml/src/test/java/org/almostrealism/ml/tokenization/SentencePieceTokenizerFixture.java` | Writes the synthetic `ARTK` binary the examples use |
