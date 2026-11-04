---
status: completed
created: 2026-11-04
closed: 2026-11-04
tracker: [0b5eb918-14fa-4224-9848-151be0106c4d]
---

# Document text tokenization: from a string to the token ids a model consumes

**Category:** Documentation

## Motivation

Every language-model capability the platform has built — the self-hosted byte-level LM that
describes the platform, Qwen3 inference, and the Stable Audio 3 / T5Gemma text conditioning —
starts at the same boundary: a `String` becomes a sequence of integer token ids, and generated
ids become a `String` again. That boundary is the one place where a reader (or a model trained on
this corpus) must understand *bytes, Unicode, vocabularies and merge priorities* before any
computation graph is involved. It is also the subsystem that most recently changed:
`SentencePieceBPETokenizer` and its `export_tokenizer.py` exporter gained added-token support and
the reachable-byte-fallback relaxation for Gemma vocabularies (PR #820, merged 2026-11-03).

The reference material exists only as Javadoc and script docstrings. No connective documentation
explains the subsystem:

- **No internals page.** `docs/internals/` has 52 pages; none covers tokenization. The only
  Markdown that names `SentencePieceBPETokenizer` or `ByteTokenizer` is
  `training-a-language-model.md`, in passing.
- **`llms.txt` has no tokenization entry.** Its one tokenizer line is the `extern/ml-djl`
  module (DJL SentencePiece) — a *different* class from `engine/ml`'s `SentencePieceBPETokenizer`.
  A reader following the index finds the wrong tokenizer.
- **The package Javadoc is stale.** `org.almostrealism.ml.tokenization`'s `package-info.java`
  lists `ByteLevelEncoder`, `RegexPreTokenizer`, `PreTokenizer` and `ByteLevelBPETokenizer` and
  describes the package as "GPT-2, Qwen, Llama" byte-level BPE. It omits the two tokenizers the
  newest work depends on: `ByteTokenizer` (the self-hosted LM's 256-symbol vocabulary) and
  `SentencePieceBPETokenizer` (Gemma/T5Gemma, used by Stable Audio 3).
- **`engine/ml/README.md` contains code that does not compile.** Its "5. Tokenization" section
  calls `Qwen3Tokenizer.encodeAsInt(...)`, `decodeAsInt(...)` and `getBOSToken()`. `Qwen3Tokenizer`
  extends `ByteLevelBPETokenizer` but does not implement `org.almostrealism.ml.Tokenizer`, so it
  has no `encodeAsInt`/`decodeAsInt`, and `getBOSToken()`/`getEOSToken()` are `protected`. The same
  broken calls recur in the "Pattern 5: Manual Token Loop" section, which also calls
  `tokenizer.encodeAsInt(...)`, `decodeAsInt(...)` and `getEOSToken()`; the correction must cover
  both. (By contrast, the README's `ByteLevelBPETokenizer` API block and its "Pattern 4: Custom
  Tokenization" example are accurate — `ByteLevelBPETokenizer` is `abstract` with the shown
  `encode(String, boolean)`/`decode(int[])` and four abstract `protected` getters — though the API
  block still understates the richer class by omitting its `toSymbols`/`fromSymbols` seams.)
- **Two file formats, documented only at their readers.** The `ARTK` v2 length-prefixed binary
  (written by `export_tokenizer.py`, read by `SentencePieceBPETokenizer`) and Qwen3's
  `tokenizer.bin` + `merges.txt` pair are each specified only in one class's Javadoc or one script's
  docstring, with no page explaining why the exporter exists (no JSON parser on `engine/ml`'s
  classpath; BPE tokens are not line-safe) or how its validation protects the Java reader
  (reachable byte-fallback tokens, merge results present, reader size limits).

This is the recurring *reference-exists / connective-tissue-missing* situation the 2026-10-27,
2026-11-02 and 2026-11-03 documentation cycles resolved for the simulation substrate, the
connectome pipeline and the vector index — here applied to the input layer of every language model.
For the self-understanding aim it is foundational in a literal sense: a model that learns this
platform from its own corpus must first understand how the platform turns text into the symbols
it learns from, and why the self-hosted LM deliberately uses a vocabulary-free byte tokenizer.

## Scope

1. **New page `docs/internals/text-tokenization.md`** covering:
   - **The contract.** The `org.almostrealism.ml.Tokenizer` interface (`encodeAsLong`/
     `decodeAsLong`, the `int` defaults, the `PackedCollection` defaults that throw), and which
     classes implement it (`ByteTokenizer`, `SentencePieceBPETokenizer`, `extern/ml-djl`'s
     `SentencePieceTokenizer`) versus which do not (`Qwen3Tokenizer`, which declares its own
     `encode(String, boolean, boolean)` and inherits `decode(int[])` from `ByteLevelBPETokenizer`,
     rather than the interface's `encodeAsLong`/`decodeAsLong`).
   - **The family tree.** A table of every text tokenizer in the platform — class, module,
     vocabulary source, boundary scheme, unknown-character handling, special-token behaviour, and
     the models that use it (self-hosted LM, Qwen3, Stable Audio 3/T5Gemma, Llama2, DJL). This must
     include the Llama2 path, which is not a `Tokenizer` implementation: `Llama2.run` tokenizes
     through the static `BPE.encode(...)` utility over its loaded vocabulary and scores (greedy
     highest-score merge, no byte fallback) and prepends BOS itself. Disambiguate the two
     "SentencePiece" classes explicitly. Mention `MidiTokenizer`/`SkyTntTokenizerV2` (studio,
     compound MIDI tokens) only as a pointer: they tokenize MIDI, not text.
   - **`ByteTokenizer`.** The 256-symbol bijection, the UTF-8 encode/decode contract and its
     replacement behaviour, `isValidUtf8`, and why the self-hosted LM uses it (no external
     artifact, nothing to train, every byte reachable).
   - **The BPE pipeline in `ByteLevelBPETokenizer`.** Added-token splitting first
     (`splitAddedTokens`, normalized vs. raw added tokens), pre-tokenization (`PreTokenizer`,
     `RegexPreTokenizer`, `WHOLE_TEXT`), symbol formation (`toSymbols`), priority-ordered merges
     (`applyBPEMerges`, `getMergePriority`), vocabulary lookup, and decode (`fromSymbols`, special
     tokens skipped). Show where the two subclasses differ: GPT-2 byte-to-Unicode remapping
     (`ByteLevelEncoder`, Qwen3) versus the `U+2581` boundary and `<0xNN>` byte fallback
     (`SentencePieceBPETokenizer`, with `flushBytes` reassembling multi-byte characters).
   - **The exporter and the `ARTK` format.** Why `export_tokenizer.py` exists, the format layout
     (stated once here; the Javadoc and docstring remain authoritative and the page links them),
     what `validate_pipeline` accepts and rejects and why (normalizer/pre-tokenizer shapes, the
     reachable-byte rule including the always-unreachable `<0x20>` and added-token characters,
     `fuse_unk`), the reader-limit checks (`MAX_ENTRIES`, `MAX_STRING_BYTES`), and the
     post-processor rule. The causality runs from the reader to the exporter: the `ARTK` binary
     carries no post-processor and `SentencePieceBPETokenizer.encodeAsLong` adds no BOS/EOS, so
     `validate_pipeline` accepts a source post-processor only when it is a pass-through
     (`TemplateProcessing` whose single-sequence template is exactly `$A`) and rejects one that
     adds special tokens or repeats the sequence. A caller that needs BOS/EOS adds them itself.
   - **Where tokens meet the graph.** One short section on the host boundary: token ids are
     produced on the host and enter a model as a `PackedCollection` of ids (embedding lookup is a
     Producer). Cross-link `training-a-language-model.md`, `ml-inference-pipeline.md` and the
     open tracker task `490c98f2` (sanctioned host-to-device boundary for tokenizer output)
     without promising its outcome.
   - **Related files** table (paths only, no line numbers, per `docs/CLAUDE.md`).
2. **Drift-guard test `TextTokenizationExamplesTest`** in
   `engine/ml/src/test/java/org/almostrealism/ml/tokenization/`, extending `TestSuiteBase`, every
   `@Test` with a `timeout`, modeled on `VectorIndexExamplesTest` and
   `NeuronLevelConnectomeSimulationExamplesTest`. Every complete Java excerpt on the page is
   exercised by it, using only artifacts that need no download:
   - `ByteTokenizer` round-trip of a multi-byte string, and the documented id range.
   - `SentencePieceBPETokenizer` over `SentencePieceTokenizerFixture` (synthetic `ARTK` binary):
     `PROMPT` → `EXPECTED`, byte fallback of `UNKNOWN` into `<0xNN>` tokens and back, and
     `encodeAsLong` adding no special tokens.
   - `Qwen3Tokenizer.createTestTokenizer()` through the `encode(String, boolean, boolean)` /
     `decode(int[])` API the page shows.
   Assertions are exact (token ids, round-tripped strings); nothing is gated on an environment
   probe, and no curated model file is required.
3. **Corrections and cross-links:**
   - Rewrite `engine/ml/README.md` "5. Tokenization" **and** "Pattern 5: Manual Token Loop" so
     their code compiles against the real API (use the methods `Qwen3Tokenizer` actually exposes,
     or demonstrate the `Tokenizer` interface with a class that implements it), bring the
     `ByteLevelBPETokenizer` API block in line with the class, add `ByteTokenizer` and
     `SentencePieceBPETokenizer`, and link the new page.
   - Update `org.almostrealism.ml.tokenization`'s `package-info.java` to list `ByteTokenizer` and
     `SentencePieceBPETokenizer` and to describe both BPE schemes, not only byte-level GPT-2.
   - Add a tokenization entry to `llms.txt` pointing at the new page, and clarify the existing
     `ml-djl` line so it is not mistaken for the main SentencePiece path.
   - Link the page from `training-a-language-model.md` (the byte tokenizer choice) and from
     `engine/ml/scripts/README.md` (the exporter).

## Approach

1. Read every class in `org.almostrealism.ml.tokenization`, `org.almostrealism.ml.Tokenizer`,
   `Qwen3Tokenizer`, `extern/ml-djl`'s `SentencePieceTokenizer`, `export_tokenizer.py` and its
   `test_export_tokenizer.py`, and `SentencePieceBPETokenizerTest` / `ByteTokenizerTest` /
   `Qwen3TokenizerTest`. The page describes what the code does today, read off the source — not
   the plan's summary above. Where the plan and the code disagree, the code wins and the page says
   what the code does.
2. Write the drift-guard test first, so every excerpt the page shows is copied from a passing test.
3. Write the page, then the README / `package-info` / `llms.txt` / cross-link edits.
4. Run `TextTokenizationExamplesTest` plus `SentencePieceBPETokenizerTest`, `ByteTokenizerTest`
   and `Qwen3TokenizerTest` through `mcp__ar-test-runner__start_test_run` (one bounded selection),
   then `mcp__ar-build-validator__start_validation` (checkstyle, code policy, test timeouts,
   duplicate code).
5. If reading the code shows that `Qwen3Tokenizer` *should* implement `Tokenizer` (it has every
   method the interface needs under other names), that is a production API change and **out of
   scope**: document the class as it is and file a tracker task for the decision. Done — tracker
   task `0b5eb918-14fa-4224-9848-151be0106c4d` records this decision; the page documents the class
   as it is today.

## Success Criteria

- `docs/internals/text-tokenization.md` exists, covers every item in Scope 1, cites no source line
  numbers and references no `docs/plans/` file.
- `TextTokenizationExamplesTest` passes on any backend with no downloaded artifact, and every
  complete Java excerpt on the page appears in it.
- `engine/ml/README.md`'s tokenization code uses only methods that exist with the visibility shown;
  the README names `ByteTokenizer` and `SentencePieceBPETokenizer`.
- `package-info.java` lists all tokenizer classes in the package.
- `llms.txt` has a tokenization entry; the `ml-djl` line is unambiguous.
- Existing tokenizer tests still pass unchanged; the build validator reports no violations.

## Dependencies

None. The tokenizer work this documents (PR #820) has merged. Runs on any node — the fixture
tokenizers are synthetic, so no workstream settings file is needed.

## Open questions

Raised in review of this plan, for the person deciding whether it goes ahead.

1. **Should `Qwen3Tokenizer` implement `Tokenizer`, and does that decision come before the README
   rewrite?** The class already has the core capability the `Tokenizer` interface needs, but under
   different names and types: `encode(String, boolean, boolean)` / `decode(int[])` returning `int`
   arrays rather than the interface's `encodeAsLong` / `decodeAsLong`. (Special tokens are not part
   of the `Tokenizer` interface at all — its only members are `encodeAsLong` / `decodeAsLong`, the
   `int`-array defaults, and the `PackedCollection` defaults that throw. Qwen3Tokenizer exposes its
   special tokens as the public `BOS_TOKEN` / `EOS_TOKEN` constants, while the `getBOSToken()` /
   `getEOSToken()` it inherits from `ByteLevelBPETokenizer` are `protected`.) Making it implement
   `Tokenizer` is a production API change and is out
   of scope here (Approach step 5): the page documents the class as it is and the decision is tracked
   by task `0b5eb918-14fa-4224-9848-151be0106c4d`. But that decision changes how Scope 3's README rewrite should read — whether the
   corrected example calls the methods `Qwen3Tokenizer` exposes today, or demonstrates the `Tokenizer`
   contract through a class that implements it (`ByteTokenizer` or `SentencePieceBPETokenizer`). The
   approver may prefer to sequence the API decision first and write one README example against the
   final shape, rather than correct it now and correct it again when the class changes.

2. **How the corrected README illustrates special tokens.** The broken example displays BOS/EOS by
   calling `getBOSToken()` / `getEOSToken()`, which are `protected` and cannot appear in public
   example code — part of why the section does not compile. `Qwen3Tokenizer` exposes the same values
   as the public constants `BOS_TOKEN` (151643) and `EOS_TOKEN` (151645), so the rewrite can keep the
   special-token illustration by referencing those constants rather than dropping it.

## Estimated Complexity

Medium. One new internals page, one new test class, and four small documentation edits; no
production behaviour changes (the only Java production file touched is `package-info.java`).
