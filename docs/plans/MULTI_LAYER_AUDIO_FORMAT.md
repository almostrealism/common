# Multi-Layer Audio Protobuf Format — Schema Design Study

**Status**: Draft for review. The schema fragments below are a *proposal*; landing the
actual `.proto` change is the implementation task that follows this study.

**Workstream**: `feature/multi-layer-audio-format` (Common 0.74)

**Tracker task**: *Multi-layer PCM audio format with included metadata using protobuf*
(`50bb7bfd-849b-4704-af0d-0b75506cf06e`).

**First downstream consumer**: Rings 0.39
*"Store AudioUnit metadata along with recorded audio."*

---

## 1. Purpose and Scope

The format extends the existing `audio.proto` to carry:

1. **Raw PCM data** for one or more *layers* belonging to a logical recording, either
   embedded directly or referenced by a content hash so layers can live outside the
   binary.
2. **Layer relationships** — the link between a *dry* layer and the *echo* and *reverb*
   layers derived from it; a *room mic stereo* layer that accompanies a set of *mono
   close-mic* layers; a *synth* layer with its *FX* layer.
3. **Production lineage** — what produced each layer (microphone capture, AU capture,
   synthesizer, model generation, transform of another layer).
4. **Tool-specific parameter state** — AU parameter snapshots, synthesis genomes, model
   latents — enough to re-derive a layer if the producing tool is available.
5. **Provenance** — timestamps, source paths, hashes, framework version.

**Out of scope:**

- Re-rendering from stored recipes. The format records enough to make re-rendering
  possible; the engine that performs it is a separate effort (Rings 0.39 is storage
  only on its side as well).
- Streaming/network protocol. The format is a serialised file; transport over a
  network is solved at a higher layer.
- Display / catalog conventions ("which layer is shown by default"). Those belong in
  the consuming UI, not in the wire format.

**Anti-scope (do not do this in implementation):**

- Do not invent fields for hypothetical use cases the four enumerated examples do not
  cover. The format is a foundation, not a kitchen sink.
- Do not add `google.protobuf.Any` payloads. The Common repo does not use `Any`
  anywhere today, and consistency wins. See §6.3 for the chosen alternative.
- Do not add a new Maven module. The format lives in
  `common/studio/compose/src/main/proto/audio.proto`. The generated Java type already
  ships in the `studio/compose` jar via `org.almostrealism.audio.api`.

---

## 2. Starting Point — What `audio.proto` Already Defines

Read end-to-end: `common/studio/compose/src/main/proto/audio.proto`.

```
syntax = "proto3";
package almostrealism.audio;
option java_package = "org.almostrealism.audio.api";
import "collections.proto";

message AudioLibraryData { … }   // field numbers 1–4
message WaveRecording    { … }   // field numbers 1–6
message WaveDetailData   { … }   // field numbers 2–19, reserved 1
message PrototypeIndex   { … }
message PrototypeCommunity { … }
message SynthesizerModelData { … }
```

### 2.1 Field-number occupancy (must be respected)

| Message            | Used field numbers                                        | Reserved |
|--------------------|-----------------------------------------------------------|----------|
| `AudioLibraryData` | 1 (info), 2 (recordings), 3 (models), 4 (prototype_index) | —        |
| `WaveRecording`    | 1, 2, 3, 4, 5, 6                                          | —        |
| `WaveDetailData`   | 2–19 (sample_rate=2 … persistent=19; identifier=18)       | **1**    |
| `SynthesizerModelData` | 1–4                                                  | —        |
| `PrototypeIndex` / `PrototypeCommunity` | 1–3                                  | —        |

The next available field numbers are therefore:

- `AudioLibraryData`: 5+
- `WaveRecording`: 7+
- `WaveDetailData`: 20+
- `SynthesizerModelData`: 5+

This study **reserves** the following ranges (see §5 for definitions):

- `WaveDetailData` 20–29 — per-layer extension fields
- `WaveRecording` 7–14 — per-recording extension fields
- `AudioLibraryData` 5 — top-level container reference

### 2.2 Existing identifier convention

`WaveDetails.identifier` is a **lowercase MD5 hex string** of the source audio file's
bytes. This is documented in `AudioLibraryPersistence` (lines 60–66, 103, 627) and is
how `AudioLibrary.find(identifier)` resolves an identifier back to a file path on
disk.

The repo also has `KeyUtils.hash` which is **SHA-256 hex** for unrelated uses (random
keys, password hashes), and `Signature.md5` which is MD5 for compact configuration
signatures. The two coexist; nothing today migrates audio identifiers to SHA-256.

This study **does not reuse the audio MD5 identifier as a content hash for layer
references** (see §6.2 for the chosen hash format) — but it **does keep the existing
`identifier` field meaning unchanged** so that legacy data continues to round-trip
through `AudioLibraryPersistence.encode`/`decode` byte-for-byte.

### 2.3 Existing consumers (do not break)

| Class                                | Reads / Writes                              |
|--------------------------------------|---------------------------------------------|
| `AudioLibraryPersistence`            | encode/decode `WaveDetailData`, batched library files, `loadSingleDetail`, `loadRecording`, `listRecordings`. **The widest consumer.** |
| `AudioLibraryDataWriter`             | builds `WaveRecording` from a buffer of `WaveDetailData`, writes via `AudioLibraryPersistence.saveRecordings`. Live capture path. |
| `ProtobufWaveDetailsStore`           | DiskStore-backed indexed store keyed by identifier, parses `Audio.WaveDetailData`. |
| `GeneratedSourceLibrary`             | encodes `SynthesizerModelData`. |
| `LegacyLibraryMigrator`, `AudioLibraryMigration` | read old layouts and re-encode. |
| `PrototypeDiscovery`                 | reads `PrototypeIndex` / `PrototypeCommunity`. |
| `AudioLibraryStartupTest`, `ProtobufLoadNoRecomputeTest` | round-trip tests. |

Every existing field on `WaveDetailData` and `WaveRecording` is *populated* somewhere
on the write side and *read back* somewhere on the load side. The format below adds
new optional fields next to them rather than replacing any.

### 2.4 What the new format does NOT change

- All current field numbers in `WaveDetailData`, `WaveRecording`, `AudioLibraryData`,
  `SynthesizerModelData` remain bound to their current types and semantics.
- `WaveDetailData.identifier` continues to be the per-buffer MD5 identifier used by
  `AudioLibrary.find`.
- `WaveDetailData.data` continues to be inline `CollectionData` PCM. The new
  hash-reference path is an *alternative* (see §5.3), not a replacement.
- The default behaviour of `AudioLibraryPersistence.saveLibrary(library, prefix)` —
  which omits `data` — is unchanged.

---

## 3. Use Cases the Format Must Cover

These come straight from the tracker description. The schema is checked against them
in §7.

| # | Scenario                                                                  | What the format records                                        |
|---|---------------------------------------------------------------------------|-----------------------------------------------------------------|
| A | Stereo dry → matching stereo echo → matching stereo reverb                | Three layers; relationship: echo derives-from dry, reverb derives-from echo. Each layer has `producer_kind = TRANSFORM` and a `transform_kind` of `ECHO` or `REVERB`. |
| B | Mono close mics on each drum + stereo room mic                            | One *recording* with N+1 layers. Layers grouped by `mic_role` (`CLOSE`, `ROOM`); each close-mic layer is tagged with a `target` ("kick", "snare", …); room mic is stereo. |
| C | Mono synth + matching stereo FX                                           | Two layers; FX has `derived_from` referencing synth and `transform_kind = FX_BUS`; producer of synth is `SYNTHESIS` with a synthesis-genome payload. |
| D | AU-captured sample + AU parameter snapshot at capture time                | One layer; `producer_kind = AUDIO_UNIT`; `tool_state` carries `AudioUnitParameterState` (component description, parameter map, optional preset blob, midi context). |

The format must compose. *"The mono synth from C with the same FX from C, but
re-rendered through the AU plugin from D"* is expressible as a recording with the C
relationships plus a D-shaped tool_state on the synth layer.

---

## 4. Strategic Frame — Evolve, Don't Replace

The tracker says: *"The new format should evolve [`audio.proto`] rather than replace
it; existing serialized data needs to remain readable."*

Three plausible strategies, ranked:

1. **Add fields next to existing ones, in a new field-number range.** Existing readers
   ignore unknown fields by proto3 rules. New readers see both old and new fields and
   choose which to honour. This is the chosen strategy.
2. **Wrap old types in a new `MultiLayerRecording` container.** Bigger blast radius;
   every consumer has to learn the wrapper, including `AudioLibraryDataWriter`,
   `AudioLibraryPersistence.saveLibrary`, and `ProtobufWaveDetailsStore`. Rejected.
3. **Define a parallel `audio_v2.proto`.** No precedent in the repo; doubles the
   maintenance surface. Rejected.

The chosen strategy means:

- `WaveDetailData` gains optional fields starting at 20. A populated
  `WaveDetailData` from this branch is still parseable by an old build; the old
  build simply doesn't see the new fields.
- A new top-level message `MultiLayerRecording` is introduced as a
  *peer* of `WaveRecording`, addressing relationship-bearing recordings. It is added
  to `AudioLibraryData` at field 5 (the next free top-level slot). Existing
  callers continue to write `WaveRecording`s into field 2; new callers write
  `MultiLayerRecording`s into field 5. No migration of stored data is required.
- A reader that wants to operate uniformly can lift a legacy
  `WaveRecording` into a `MultiLayerRecording` in memory at load time (see §8).

This is consistent with the project's existing protobuf-evolution practice
(`reserved 1` in `WaveDetailData`, `optional` flags on additive fields).

---

## 5. Proposed Schema (Draft)

The fragment below is intended to be appended to `audio.proto`. **It is a draft for
review, not a final wire format.** The implementation task may renumber, rename, or
split messages — but the field-number assignments here are reserved against the
existing format above.

```protobuf
// =========================================================================
// Multi-layer audio extensions (Common 0.74)
// All additions are optional; existing serialised data remains parseable.
// =========================================================================

// ---- 5.1 Container ------------------------------------------------------

// A MultiLayerRecording is a peer of WaveRecording that adds layer
// relationships, production lineage and tool-specific state. Each layer is
// a WaveDetailData (existing message) augmented with the new fields in §5.4.
//
// A consumer that does not understand MultiLayerRecording can still read the
// recording's audio buffers by descending into layers[].buffer.
message MultiLayerRecording {
  // Stable identifier for this multi-layer recording. Distinct from the
  // per-buffer identifier on each WaveDetailData.
  string key = 1;

  // Optional grouping key (mirrors WaveRecording.group_key semantics).
  optional string group_key = 2;
  optional int32  group_order_index = 3;

  // Each layer is one named audio stream that participates in the recording.
  repeated AudioLayer layers = 4;

  // Top-level edges expressing relationships *between* layers when those
  // edges are not already implicit in derived_from / mic_role on the layer
  // itself. Most recordings will not need this — derived_from inside the
  // layer is the primary mechanism. Use named edges for relationships that
  // do not fit "derives from" (e.g., "is the room mic for", "is the L of").
  repeated LayerEdge edges = 5;

  // Production / capture context that applies to the recording as a whole
  // (precedence rules: see §6.5).
  optional Provenance provenance = 6;

  // Schema-evolution hint. Writers SHOULD set this. Readers MAY use it to
  // choose compatibility strategies. Format: integer minor schema revision
  // counted from this study (1 == the schema below).
  optional int32 schema_revision = 7;
}

// ---- 5.2 Layer ----------------------------------------------------------

// A single audio stream. Carries either inline PCM (via buffer.data) or a
// reference to PCM stored elsewhere (audio_ref). Exactly one of buffer or
// audio_ref SHOULD be populated; if both are present, audio_ref is the
// authoritative source and buffer is a cached convenience.
message AudioLayer {
  // Per-layer identifier. May equal WaveDetailData.identifier when buffer
  // is populated, but is independently meaningful when audio_ref is the
  // payload.
  string layer_id = 1;

  // Human-readable role for catalog/UI use only — semantic relationships
  // belong in derived_from / mic_role / edges. Examples: "dry", "echo",
  // "reverb", "kick close", "room L".
  optional string role = 2;

  // Inline audio. Re-uses the existing per-buffer message so that all of
  // freq_data, feature_data, similarities, identifier, etc. continue to be
  // available unmodified. SHOULD be omitted when audio_ref is set, except
  // when a writer wants to keep a small inlined cache.
  optional WaveDetailData buffer = 3;

  // External reference. See §6.2 for hash semantics.
  optional AudioReference audio_ref = 4;

  // What produced this layer. See §6.4.
  optional ProductionLineage lineage = 5;

  // Tool-specific parameter state captured at the moment this layer was
  // produced. Multiple are allowed (e.g., synth genome AND post-FX state
  // when the layer is "synth + bus FX rendered together"). See §6.3.
  repeated ToolState tool_state = 6;

  // Layers this layer was directly derived from (zero or more). For the
  // "dry → echo → reverb" chain, the echo layer's derived_from is [dry],
  // and the reverb layer's derived_from is [echo]. The transform that
  // produced this layer from the parents is on `lineage`.
  repeated LayerRef derived_from = 7;

  // Microphone role when the layer is a microphone capture; null otherwise.
  // Use cases: drum kit close vs room mic, stereo pair labelling.
  optional MicRole mic_role = 8;

  // Channel-pair role within a stereo capture. When absent, the layer is
  // assumed to be self-contained (mono or pre-paired stereo). When set,
  // signals that this layer is one half of a logical stereo pair whose
  // partner layer is identified by stereo_pair_layer_id.
  enum StereoSide { UNSPECIFIED = 0; LEFT = 1; RIGHT = 2; MID = 3; SIDE = 4; }
  optional StereoSide  stereo_side = 9;
  optional string      stereo_pair_layer_id = 10;
}

// ---- 5.3 Hash references ------------------------------------------------

// External payload reference. The pair (algorithm, digest) is the canonical
// identity of the bytes; path_hint is informational and may be stale.
message AudioReference {
  enum HashAlgorithm {
    HASH_UNSPECIFIED = 0;
    SHA256 = 1;
    MD5 = 2;          // Carrying legacy WaveDetails identifiers
  }

  HashAlgorithm algorithm = 1;
  bytes         digest    = 2;     // raw bytes; 32 for SHA-256, 16 for MD5

  // Container format of the referenced file (informational): "wav", "aiff",
  // "flac", "raw_pcm_f32", etc.
  optional string format = 3;

  // Best-effort relocatable hint: an asset-store key, a path relative to
  // the recording file, or a URI. NEVER authoritative — readers MUST verify
  // the digest of the resolved bytes.
  optional string path_hint = 4;

  // Total byte length of the referenced payload, when known. Lets readers
  // pre-allocate and detect partial fetches without computing the hash.
  optional int64 byte_length = 5;
}

message LayerRef {
  // layer_id of an AudioLayer that exists somewhere reachable. Most often
  // this is another layer in the same MultiLayerRecording; for cross-
  // recording references, audio_ref or recording_key is provided as well.
  string layer_id = 1;
  optional string recording_key = 2;
  optional AudioReference audio_ref = 3;
}

// ---- 5.4 New fields on the existing WaveDetailData ----------------------

// These extend WaveDetailData (existing message) at field numbers 20-29.
// They are added by *editing* the existing message, not by wrapping it.

// extend WaveDetailData {
//   optional ProductionLineage  lineage             = 20;
//   repeated ToolState          tool_state          = 21;
//   optional AudioReference     audio_ref           = 22;
//   optional Provenance         provenance          = 23;
//   reserved 24 to 29;   // for future per-buffer additions
// }
//
// (proto3 does not support `extend` outside of options; the implementation
// task adds these fields directly inside the existing WaveDetailData
// message body. The pseudo-extend block above is shown for clarity only.)

// ---- 5.5 Production lineage --------------------------------------------

message ProductionLineage {
  enum ProducerKind {
    PRODUCER_UNSPECIFIED  = 0;
    MICROPHONE_RECORDING  = 1;   // captured from a physical mic input
    AUDIO_UNIT            = 2;   // captured from an AU rendering
    SYNTHESIS             = 3;   // emitted by a software synthesizer
    MODEL_GENERATION      = 4;   // emitted by an ML model (Oobleck, etc.)
    TRANSFORM             = 5;   // produced by transforming another layer
    EXTERNAL_FILE         = 6;   // imported from a user-supplied file
  }

  ProducerKind kind = 1;

  // For TRANSFORM kind, what transform was applied. Catalog of well-known
  // transforms; freeform name in `transform_name` covers anything else.
  enum TransformKind {
    TRANSFORM_UNSPECIFIED = 0;
    ECHO       = 1;
    REVERB     = 2;
    EQ         = 3;
    COMPRESS   = 4;
    GAIN       = 5;
    PITCH      = 6;
    TIME       = 7;
    FX_BUS     = 8;   // catch-all "ran through an effects chain"
    SUM        = 9;   // mix of multiple parents
  }
  optional TransformKind transform_kind = 2;
  optional string        transform_name = 3;

  // Producer-instance identity (which mic, which AU, which model, which
  // genome, which transform pipeline). Free-form; consumers attach the
  // tool_state that names it.
  optional string producer_id = 4;
}

// ---- 5.6 Tool-specific state -------------------------------------------

// Carries a typed sub-message *or* a typed-bytes payload, by tool. We use
// typed sub-messages for the small, stable, well-known cases (AudioUnit,
// synthesis-genome, FX-chain) and a typed-bytes envelope for everything
// else. See §6.3 for rationale.
message ToolState {
  // Discriminator. SHOULD match the producer that emitted the layer.
  string kind = 1;

  oneof payload {
    AudioUnitParameterState au          = 2;
    SynthesisGenome         genome      = 3;
    ModelLatent             model       = 4;
    EffectChainState        fx_chain    = 5;
    OpaquePayload           opaque      = 6;   // escape hatch
  }
}

// AU parameter snapshot. Aligned with the Rings 0.39 AU storage proposal.
// Carried inside ToolState.au.
message AudioUnitParameterState {
  string component_description = 1;     // e.g. "aumu,Alch,Appl,0001"
  optional string display_name      = 2;
  optional string manufacturer_name = 3;

  // AUParameterAddress is uint64; protobuf forbids uint64 map keys, so the
  // address is encoded as a decimal string.
  map<string, float> parameters = 4;

  // AUAudioUnit.fullState binary plist. Optional because it can be MB-size.
  optional bytes preset_data = 5;

  // MIDI context at capture (note-on event).
  optional int32 midi_note     = 6;
  optional int32 midi_velocity = 7;
  optional int32 midi_channel  = 8;
}

// Genome / parameter set for a synthesizer (e.g. PDSL synthesis tree, the
// SynthesizerModelData equivalent for *captured* output). Concretely,
// the implementation should reuse SynthesizerModelData for the genome part
// where possible; this wrapper adds the runtime parameter overrides.
message SynthesisGenome {
  optional string genome_id = 1;
  optional SynthesizerModelData model = 2;
  map<string, double> overrides = 3;
}

// Latent-vector / configuration for a generative model (Oobleck, diffusion).
message ModelLatent {
  string model_id = 1;
  optional string model_version = 2;
  optional CollectionData latent = 3;
  map<string, string> config = 4;        // free-form, pattern matches ModelMetadata
}

// Generic FX chain: ordered list of named transforms with their parameters.
// Used when the producer is a transform pipeline rather than a single AU/
// synth.
message EffectChainState {
  message Stage {
    string name = 1;
    map<string, float> parameters = 2;
    // Nested AU state if a stage is itself a hosted AU.
    optional AudioUnitParameterState au = 3;
  }
  repeated Stage stages = 1;
}

// Escape hatch for tool-state types that don't have a typed sub-message
// yet. Bytes plus a content-type identifier; the implementation task may
// promote popular `format`s to typed sub-messages over time.
message OpaquePayload {
  string format = 1;     // e.g. "application/x.oobleck-config+json"
  bytes  content = 2;
  optional string version = 3;
}

// ---- 5.7 Edges ---------------------------------------------------------

// Generic named-edge relationship between two layers. Most relationships
// are expressible via derived_from / mic_role / stereo_pair, and writers
// SHOULD prefer those. Edges are the catch-all for anything else.
message LayerEdge {
  string from_layer_id = 1;
  string to_layer_id   = 2;
  string label         = 3;     // e.g. "is_room_mic_for", "is_send_of"
  map<string, string> attributes = 4;
}

// ---- 5.8 Microphone role ------------------------------------------------

message MicRole {
  enum Position {
    POS_UNSPECIFIED = 0;
    CLOSE = 1;
    ROOM  = 2;
    OVERHEAD = 3;
    AMBIENT = 4;
    DI = 5;        // direct input (no mic)
  }

  Position position = 1;

  // Free-form target name: "kick", "snare", "hihat-overhead", etc.
  optional string target = 2;

  // Capture device identifier (e.g., "USB Audio CODEC : 1") — provenance,
  // not authoritative.
  optional string device = 3;
}

// ---- 5.9 Provenance ----------------------------------------------------

message Provenance {
  // Unix epoch milliseconds. Aligned with ModelMetadata.created_timestamp
  // (model.proto), which is seconds — implementation chooses ms here for
  // sample-recording precision; document the choice in the message comment.
  optional int64 created_at_millis = 1;

  // Best-effort source-file path at the time of capture. NEVER
  // authoritative — verification against audio_ref.digest is the source of
  // truth.
  optional string source_path_hint = 2;

  // AR framework version that wrote this record. Mirrors
  // ModelMetadata.framework_version.
  optional string framework_version = 3;

  // Hashes of inputs that produced the output (parent layer digests, model
  // weight digests, sample-pack digests). Free-form.
  map<string, AudioReference> input_hashes = 4;

  // Free-form supplementary tags. Mirrors ModelMetadata.config style.
  map<string, string> tags = 5;
}
```

---

## 6. Design Decisions and Rationale

### 6.1 Layer-relationship representation — DAG with a default tree shape

Three options:

| Shape          | Can express                                            | Cannot express                                         |
|----------------|--------------------------------------------------------|--------------------------------------------------------|
| Tree           | Use case A (linear chain), Use case D (single layer).  | Use case B (room mic + N close mics share a recording but neither derives from the other), Use case C if both layers feed a sum bus. |
| DAG            | A, B (use named edges), C (stereo FX with two parents = sum).             | Cycles — there is no audio use case that needs them. |
| Named-edge graph | All of the above, plus future relationships we have not yet imagined. | Disciplined consumption — "who is the dry of X?" needs an edge-label index. |

**Decision: a DAG, primarily expressed as `derived_from` (parent edges) on each
layer, with a small `LayerEdge` escape hatch for non-derivation relationships.**

Why DAG and not pure tree: use case A's *reverb* layer technically *only* derives
from the *echo* (one parent), so a tree handles A. But a *summed wet/dry blend*
layer in a future version of A would have two parents (dry + wet), which a tree
cannot express. Use case C already brushes against this. Choosing DAG now avoids a
breaking change later.

Why DAG and not full named-edge graph as the *primary* representation: the named-
edge form is the most general but also the least discoverable. A reader that wants
to ask *"what produced layer X?"* should not have to scan an edge list. Putting
parent edges directly on the layer keeps the most common query fast. Named edges
remain available for relationships that aren't "derives from."

Why no cycles: there is no audio use case in scope where layer A derives from B and
B derives from A. Implementation should reject cycles at write time.

### 6.2 Hash reference format

**Decision: SHA-256 raw bytes (32 bytes) for new content, with `MD5` enum value
preserved so existing identifiers can be referenced without rehashing. Path hint is
optional and informational. `byte_length` lets readers detect partial fetches.**

- **Algorithm: SHA-256.** It is what `KeyUtils.hash` already provides in the repo,
  and it is the common content-addressed-storage default. MD5 is included as a
  legacy code-path for referencing pre-existing audio whose identifier is already
  the file's MD5. New writes use SHA-256. There is no universe where this format
  needs MD5 *only*.
- **Bytes, not hex.** Inline `bytes digest` is half the wire size of a hex string
  and matches protobuf's idiomatic representation for hashes. Hex is used at the
  Java layer (e.g., `KeyUtils.bytesToHex`) for human display only.
- **Path hint.** Optional `string path_hint` with the explicit warning that a
  reader MUST verify the digest of the resolved bytes. This handles relocatable
  references, asset-store keys, or bare URIs without picking a transport.
- **Failure mode.** When a referenced layer cannot be resolved, the reader returns
  a layer with `audio_ref` populated but `buffer` absent. It is **not** an error
  in the parser — it is a downstream UI concern whether to surface a missing layer
  as a warning, an error, or to skip it. The format does not mandate either.

### 6.3 Tool-specific parameter payloads — typed `oneof` with an opaque escape hatch

The three options the task description names:

| Option                                              | Verdict |
|-----------------------------------------------------|---------|
| Typed sub-messages per tool                         | **Chosen for the well-known tools.** Type-safe, discoverable, encodable as `WaveDetailData.tool_state[i].au` → typed reader. |
| `google.protobuf.Any`                               | Rejected. The Common repo uses `Any` *nowhere*; introducing it here departs from existing protobuf practice for one feature. The benefit (extensibility without schema change) is recovered via the `OpaquePayload` escape hatch. |
| Bytes + format string                               | Kept, but only as the `OpaquePayload` escape hatch under the `oneof`, not as the primary representation. |

`ToolState` is a `oneof` over a small set of typed sub-messages
(`AudioUnitParameterState`, `SynthesisGenome`, `ModelLatent`,
`EffectChainState`) plus an `OpaquePayload` carrying a content-type and bytes
for everything else.

Migration if a different shape proves needed: an `OpaquePayload` of format
`"application/x.foo+json"` is promoted to a typed sub-message
`FooState` by adding it to the `oneof`. Old data still parses as
`OpaquePayload`; new readers detect both. No field-number collision because the
`oneof` slot is separate.

### 6.4 Embedded vs referenced PCM — single layer message, two payload modes

A single `AudioLayer` carries both `buffer` (inline `WaveDetailData`) and
`audio_ref` (external reference) as optional fields with documented precedence:

- If `audio_ref` is set, it is authoritative. `buffer` may still be present as a
  resolved cache.
- If only `buffer` is set, the layer is fully self-contained.
- If neither is set, the layer is a metadata-only placeholder (rare; useful when a
  recording is being assembled and one layer hasn't been produced yet).

**Why one message, not two:** in practice consumers want to walk *layers* without
caring about storage mode. Splitting into `InlineLayer` and `ReferencedLayer` would
mean every iteration needs `instanceof` (or a `oneof` with two near-identical
shapes), and metadata like `lineage`, `tool_state`, `mic_role` would have to be
duplicated or pulled out into a third message. The two-mode single message keeps
the structure flat.

**Missing-file signalling:** when a referenced layer cannot be resolved, the
loader returns the `AudioLayer` with `audio_ref` set and `buffer` absent. No
exception. Callers that need the audio for playback check
`hasBuffer() || canResolve(audio_ref)`. Callers that only need metadata (lineage
inspection, recipe browsing) ignore the absence.

### 6.5 Per-layer vs per-recording metadata — both, with per-layer precedence

`Provenance` lives on both `MultiLayerRecording` and (via the new
`WaveDetailData.provenance` field) on each layer. When both are populated for the
same key, the per-layer value wins.

- **Recording-level use:** when a single recording session has uniform context —
  one date, one source path, one framework version — the writer sets `Provenance`
  once on `MultiLayerRecording` and omits it on each layer.
- **Layer-level use:** when layers were produced by different processes (e.g. the
  dry was recorded yesterday but the reverb was rendered today), each layer
  carries its own `Provenance`.
- **Precedence on conflict:** layer-level `Provenance.framework_version`,
  `created_at_millis`, etc. override the recording-level values for that layer.
  This is consistent with how `WaveDetailData.silent` / `WaveDetailData.persistent`
  already coexist with `WaveRecording.silent` (the layer field is more specific
  and takes precedence).

`ProductionLineage`, `ToolState`, `MicRole`, `derived_from`, `audio_ref` are
**per-layer only**. They cannot meaningfully apply to a whole recording.

### 6.6 Backward compatibility

All additions are optional. Concretely:

1. Old `WaveDetailData` records load unchanged into the new build — the new fields
   (20–29) are simply absent. `AudioLibraryPersistence.encode`/`decode` does not
   need to learn about them unless the writer wants to populate them.
2. New `WaveDetailData` records load into an old build — proto3 silently drops the
   unknown fields. Audio data, freq data, feature data, similarities, identifier
   are all preserved.
3. `MultiLayerRecording` is at field 5 of `AudioLibraryData`. Old builds don't
   know the field number and ignore it. The recording's audio is *not* visible to
   old builds — the writer must decide whether to also emit a flat
   `WaveRecording` for backward consumption (see §8.2).
4. `LegacyLibraryMigrator` is unaffected — the legacy formats it migrates from do
   not contain the new fields.

A small **migration helper** (Java-side, not in the wire format) is proposed in §8
to lift a flat `WaveRecording` into a single-layer `MultiLayerRecording` so that
new code can operate uniformly on both.

### 6.7 Versioning convention

The Common repo today uses two implicit versioning mechanisms:

- **Field-number reservations** (`reserved 1` in `WaveDetailData`).
- **A `framework_version` string in `ModelMetadata`** (model.proto), stamped by
  the writer.

This study follows both. New writes set `Provenance.framework_version` to the AR
framework version string. The optional `MultiLayerRecording.schema_revision`
integer is a simpler, format-local counter that lets readers branch on schema
generations without parsing a version string. Implementation chooses one or both;
this study recommends both because they answer different questions:

- `framework_version`: which AR build wrote this? (debugging, telemetry)
- `schema_revision`: which version of *this format* was emitted? (reader
  compatibility logic)

No new top-level versioning machinery (no separate "header" message, no envelope
type). Proto3's "unknown field is ignored" rule plus the two metadata fields above
covers the cases on the roadmap.

---

## 7. Cross-Check Against the Use Cases

| Use Case | Layers | Key fields populated |
|----------|--------|----------------------|
| **A** (dry → echo → reverb)              | 3 | layers[0]=dry, layers[1]=echo (derived_from=[dry], lineage.kind=TRANSFORM, lineage.transform_kind=ECHO), layers[2]=reverb (derived_from=[echo], TRANSFORM/REVERB). |
| **B** (drum kit + room)                  | N+1 | each close-mic layer: mic_role={CLOSE, target="kick"|...}; room layer: mic_role={ROOM} with stereo_side LEFT/RIGHT or pre-paired stereo buffer. lineage.kind=MICROPHONE_RECORDING on all. |
| **C** (synth + FX)                       | 2 | synth: lineage.kind=SYNTHESIS, tool_state[0].genome=…; fx: derived_from=[synth], lineage.kind=TRANSFORM (FX_BUS), tool_state[0].fx_chain=… |
| **D** (AU sample + AU snapshot)          | 1 | lineage.kind=AUDIO_UNIT, lineage.producer_id=component_description, tool_state[0].au=AudioUnitParameterState{…, midi_note, midi_velocity}. |
| **Compose** (D's plugin produced C's synth, with B's room mic alongside) | 1+N+1 | The "synth" layer has *both* lineage.kind=SYNTHESIS *and* a tool_state.au — this is fine; lineage records the high-level producer kind, tool_state lists every tool that contributed configuration. The mic layers are independent and live alongside via mic_role + edges if a "synth_room_mic_for_layer" relationship is wanted. |

No use case requires a field that the schema does not have. No use case requires
expressing more than one parent per layer except the speculative wet/dry-blend case
in §6.1, which is supported by `repeated derived_from`.

---

## 8. Implementation Phasing (Loose)

The implementation task that follows this study should land in this order. Each
step is independently mergeable.

### 8.1 Step 1 — Wire format

- Add the messages from §5 to `audio.proto` (no Java consumer changes).
- Generate the Java types.
- Add a round-trip test that builds a `MultiLayerRecording` with one inline layer
  and one referenced layer, serialises it, parses it back, and asserts equality.

### 8.2 Step 2 — Read path interop

- Add a static `MultiLayerRecording.fromLegacy(WaveRecording)` method that lifts
  a flat recording into a single-list-of-layers form for uniform consumption. This
  is a pure-conversion utility on the protobuf type, not a new file format.
- Update `AudioLibraryPersistence.loadLibrary` to also expose any
  `MultiLayerRecording`s present in `AudioLibraryData.field 5` (alongside the
  existing `WaveRecording`s).

### 8.3 Step 3 — Write path for AU consumer (Rings 0.39)

- Add a `setCurrentToolState(ToolState)` setter on `AudioLibraryDataWriter`.
- When the writer serialises a `WaveDetailData`, it sets the new
  `WaveDetailData.tool_state` field if a current state is set. Cleared after
  each sample, as in the Rings AU plan.
- This step is *additive*: existing AU-less recording paths see no change.

### 8.4 Step 4 — Write path for multi-layer

- Add a writer that produces `MultiLayerRecording` directly (e.g. from a
  multi-mic capture session).
- Provide a factory that registers layer relationships (`derived_from`, `edges`)
  given the producer pipeline.
- Out of scope for this study: deciding *which* recording flows in `studio/compose`
  switch to producing `MultiLayerRecording` by default. That is a downstream
  product decision, not a format decision.

### 8.5 Step 5 — Hash-reference resolver

- Add a small interface `AudioReferenceResolver` with `resolve(AudioReference) :
  PackedCollection`. Reuse the existing `AudioLibrary.find` pathway for MD5
  references; add a SHA-256 path that consults the same asset-store layer used by
  `ProtobufWaveDetailsStore`.
- Out of scope for this study: building a content-addressed asset-store from
  scratch. The resolver interface lets one be plugged in later.

---

## 9. Open Questions and Risks

1. **`ProductionLineage.kind` enum vs free-form**: an enum is discoverable but
   freezes the producer taxonomy. The proposal includes both — a small enum for
   the well-known kinds and free-form `producer_id` / `transform_name`. Risk: the
   enum could grow unwieldy as producers proliferate. Mitigation: keep the enum
   small (six values now); promote a free-form `producer_id` to an enum only when
   ≥3 consumers care.

2. **`schema_revision` vs `framework_version` redundancy**: see §6.7. Decision
   deferred to implementation; the two answer different questions but having both
   is a small wire-cost.

3. **`preset_data` size in `AudioUnitParameterState`**: a sample-based AU plugin's
   `fullState` plist can be megabytes. Inline storage bloats `WaveDetailData`. The
   AU consumer flagged this; a follow-up may add a `bytes_ref` variant of the
   field that points to a hashed preset blob. For 0.74 the inline form ships and
   the field stays optional — large presets are an opt-in rather than the norm.

4. **Cycle prevention in `derived_from`**: §6.1 forbids cycles, but the wire
   format cannot enforce that. Validation belongs in the writer-side helper. The
   reader should be defensive: a cycle is a malformed record; surface as an error
   rather than infinite-looping in graph traversal.

5. **Cross-recording references**: `LayerRef.recording_key` lets one
   `MultiLayerRecording` cite a layer in another. This is appealing
   (e.g. "this remix derives from that take") but turns the format into a graph
   *across* records. Risk: lookups need a recording-store. Mitigation: keep the
   field optional and design the resolver only when a consumer needs it. For
   0.74, treat cross-recording references as advisory.

6. **Field-number land grab**: this study reserves 20–29 on `WaveDetailData` and
   7–14 on `WaveRecording`. The implementation task should make these
   reservations explicit (`reserved 24 to 29;` etc.) so a future agent doesn't
   accidentally re-use them.

7. **Compatibility with `ProtobufWaveDetailsStore`'s HNSW index**: the index keys
   on `WaveDetailData.identifier` (the per-buffer MD5). New `MultiLayerRecording`s
   that use SHA-256 references but no inline buffer have no MD5, hence no HNSW
   key. Either (a) require an inline buffer for indexed entries, or (b) add a
   path that derives an indexable identifier from the SHA-256 reference. (a) is
   simpler for 0.74; (b) is a follow-up.

8. **AU consumer field-number alignment**: the Rings AU plan tentatively requested
   field 20 on `WaveDetailData` for `au_state` directly. This study instead places
   AU state under the more general `WaveDetailData.tool_state` (also at field 21
   in the proposal — adjust if 20 is preferred to honour the prior reservation).
   Coordinate with the Rings 0.39 implementer before the proto file is modified
   so that a single field-number assignment is shared.

---

## 10. Out-of-Scope Notes

- **No new Maven module.** The schema lives in `studio/compose`'s existing proto
  source set.
- **No new dependencies.** SHA-256 comes from `java.security.MessageDigest` (used
  by `KeyUtils`). MD5 likewise. Protobuf is already on the classpath.
- **No serialisation library beyond protobuf.** The codebase has no precedent for
  Apache Arrow or Avro in this domain; introducing one for one format is not
  warranted.
- **No "perfect" extensibility.** The `OpaquePayload` escape hatch covers the
  unknown; the typed `oneof` covers the known. The format does not try to be a
  universal audio metadata schema.
