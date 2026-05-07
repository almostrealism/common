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

1. **Raw PCM data** for one or more *layers* belonging to a logical group, stored as
   a `WaveDetailData buffer` per layer. The buffer's existing `identifier` (lowercase
   MD5 hex of the file's bytes) is the content-addressed reference; when a writer
   wants to keep audio out of the binary, it omits `WaveDetailData.data` and the
   library resolves the bytes by `identifier` at load time. This is exactly the
   omit-data-when-storing-externally pattern that `WaveDetailData` already supports.
2. **Layer relationships** — the link between a *dry* layer and the *echo* and *reverb*
   layers derived from it; a *room mic stereo* layer that accompanies a set of *mono
   close-mic* layers; a *synth* layer with its *FX* layer.
3. **Production lineage** — what produced each layer (microphone capture, AU capture,
   synthesizer, model generation, transform of another layer).
4. **Tool-specific parameter state** — for the first consumer (Rings 0.39 AU
   capture) this is the AU parameter snapshot. State for non-AU producers
   (synthesis genomes, model latents, FX-chain parameters) is intentionally
   deferred; see §10.
5. **Capture timestamp** — a single `created_at_millis` per layer.

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
  anywhere today, and consistency wins.
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

This study **reserves** the following slot:

- `AudioLibraryData` field 5 — top-level `AudioLayerGroup` container reference.

No additions are made to `WaveDetailData` or `WaveRecording`. The new metadata
lives on `AudioLayer` only (see §5.2).

### 2.2 Existing identifier convention

`WaveDetailData.identifier` is a **lowercase MD5 hex string** of the source audio
file's bytes. This is documented in `AudioLibraryPersistence` (lines 60–66, 103,
627) and is how `AudioLibrary.find(identifier)` resolves an identifier back to a
file path on disk.

**This identifier is the content-addressed reference for layer audio.** When a
writer stores audio externally (NAS, asset store), it populates `identifier` and
omits `data`. A reader that needs the bytes calls into the library resolver,
which uses `identifier` as the lookup key. This is the existing pattern; the
multi-layer format reuses it without introducing a parallel reference mechanism.

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

Every existing field on `WaveDetailData` and `WaveRecording` is *populated*
somewhere on the write side and *read back* somewhere on the load side. The
format below adds a single new top-level message (`AudioLayerGroup`) at
`AudioLibraryData` field 5. It does not modify any existing message body.

### 2.4 What the new format does NOT change

- All current field numbers in `WaveDetailData`, `WaveRecording`, `AudioLibraryData`,
  `SynthesizerModelData` remain bound to their current types and semantics.
- `WaveDetailData.identifier` continues to be the per-buffer MD5 identifier used by
  `AudioLibrary.find`, and continues to be the content-addressed reference whether
  `data` is inline or omitted.
- The default behaviour of `AudioLibraryPersistence.saveLibrary(library, prefix)` —
  which omits `data` — is unchanged.
- `WaveDetailData` body is unchanged. Nothing is added at fields 20+.

---

## 3. Use Cases the Format Must Cover

These come straight from the tracker description. The schema is checked against
them in §7. Every layer's audio is stored as a `WaveDetailData buffer` whose
`data` may be inline or omitted; the `identifier` (MD5 of bytes) is the
content-addressed reference in either case.

| # | Scenario                                                                  | What the format records                                        |
|---|---------------------------------------------------------------------------|-----------------------------------------------------------------|
| A | Stereo dry → matching stereo echo → matching stereo reverb                | Three layers; relationship: echo derives-from dry, reverb derives-from echo. Each derived layer has `lineage.kind = TRANSFORM` and a `transform_kind` of `ECHO` or `REVERB`. |
| B | Mono close mics on each drum + stereo room mic                            | One group with N+1 layers. Close-mic layers carry `device_type = INSTRUMENT_MIC`; the room layer carries `device_type = ROOM_MIC`. Per-instrument labelling (kick / snare / …) is deferred — see §10. |
| C | Mono synth + matching stereo FX                                           | Two layers; FX has `derived_from` referencing synth and `lineage.transform_kind = FX_BUS`; synth's `lineage.kind = SYNTHESIS`. Synth-genome / FX-chain parameters are not yet stored — that arrives when the second producer's state is wired up. |
| D | AU-captured sample + AU parameter snapshot at capture time                | One layer; `lineage.kind = AUDIO_UNIT`; `au_state` carries `AudioUnitParameterState` (component description, parameter map, optional preset blob, midi context). |

The format must compose. *"The mono synth from C with the same FX from C, but
re-rendered through the AU plugin from D"* is expressible as a group with the C
relationships plus an `au_state` on the synth layer. (As above, the synth's own
genome is not stored in 0.74.)

---

## 4. Strategic Frame — Evolve, Don't Replace

The tracker says: *"The new format should evolve [`audio.proto`] rather than
replace it; existing serialized data needs to remain readable."*

Three plausible strategies, ranked:

1. **Add a new top-level message next to existing ones, at the next free field
   number on `AudioLibraryData`.** Existing readers ignore unknown fields by
   proto3 rules. New readers see both old and new fields and choose which to
   honour. This is the chosen strategy.
2. **Wrap old types in a new container.** Bigger blast radius; every consumer
   has to learn the wrapper, including `AudioLibraryDataWriter`,
   `AudioLibraryPersistence.saveLibrary`, and `ProtobufWaveDetailsStore`.
   Rejected.
3. **Define a parallel `audio_v2.proto`.** No precedent in the repo; doubles
   the maintenance surface. Rejected.

The chosen strategy means:

- `WaveDetailData` is **not modified**. The new metadata (lineage, AU state,
  device type, capture timestamp, derived-from edges) lives on `AudioLayer`,
  which embeds a `WaveDetailData` as its `buffer` field.
- A new top-level message `AudioLayerGroup` is introduced as a *peer* of
  `WaveRecording`. It is added to `AudioLibraryData` at field 5 (the next free
  top-level slot). Existing callers continue to write `WaveRecording`s into
  field 2; new callers write `AudioLayerGroup`s into field 5. No migration of
  stored data is required.
- A reader that wants to operate uniformly can lift a legacy `WaveRecording`
  into an `AudioLayerGroup` in memory at load time (see §8).

This is consistent with the project's existing protobuf-evolution practice
(`reserved 1` in `WaveDetailData`, `optional` flags on additive fields).

---

## 5. Proposed Schema (Draft)

The fragment below is intended to be appended to `audio.proto`. **It is a draft
for review, not a final wire format.** The implementation task may renumber,
rename, or split messages — but the field-number assignment for
`AudioLibraryData` field 5 is reserved against the existing format above.

```protobuf
// =========================================================================
// Multi-layer audio extensions (Common 0.74)
// All additions are optional; existing serialised data remains parseable.
// =========================================================================

// ---- 5.1 Container ------------------------------------------------------

// An AudioLayerGroup is a peer of WaveRecording that adds layer
// relationships, production lineage and AU parameter state. Each layer
// is an AudioLayer whose audio payload is a WaveDetailData (existing
// message). The name does not anticipate origin — it is the general
// grouping construct for any set of related layers.
//
// A consumer that does not understand AudioLayerGroup can still read the
// recording's audio buffers by descending into layers[].buffer.
message AudioLayerGroup {
  // Stable identifier for this group. Distinct from the per-buffer
  // identifier on each WaveDetailData.
  string key = 1;

  // Optional grouping key (mirrors WaveRecording.group_key semantics).
  optional string group_key = 2;
  optional int32  group_order_index = 3;

  // Each layer is one named audio stream that participates in the group.
  repeated AudioLayer layers = 4;
}

// ---- 5.2 Layer ----------------------------------------------------------

// A single audio stream. The audio payload is the embedded WaveDetailData
// `buffer`; its `data` field may be inline or omitted, and either way the
// buffer's `identifier` (existing MD5 of file bytes) is the content-
// addressed reference the library uses to resolve missing-buffer cases.
//
// A typical AU-capture writer needs only `layer_id` and `buffer`; everything
// else is optional metadata that further layers (transforms, derived layers,
// multi-mic captures) populate as needed.
message AudioLayer {
  // Per-layer identifier.
  string layer_id = 1;

  // Audio payload. Re-uses the existing per-buffer message so that all of
  // freq_data, feature_data, similarities, identifier, etc. continue to be
  // available unmodified. `buffer.data` may be omitted; the library
  // resolves audio bytes by `buffer.identifier`.
  optional WaveDetailData buffer = 2;

  // What produced this layer. See §6.2.
  optional ProductionLineage lineage = 3;

  // Layers this layer was directly derived from (zero or more). For the
  // "dry → echo → reverb" chain, the echo layer's derived_from is [dry],
  // and the reverb layer's derived_from is [echo]. The transform that
  // produced this layer from the parents is on `lineage`.
  repeated LayerRef derived_from = 4;

  // Capture device class, when the layer is a microphone capture.
  optional DeviceType device_type = 5;

  // AU parameter snapshot at the moment this layer was produced. Populated
  // when `lineage.kind == AUDIO_UNIT`. State for other producer kinds
  // (synthesis, model generation, FX chains) is deferred — see §6.3 and §10.
  optional AudioUnitParameterState au_state = 6;

  // Unix epoch milliseconds at which this layer was produced.
  optional int64 created_at_millis = 7;
}

// ---- 5.3 Layer references ----------------------------------------------

// LayerRef points at an AudioLayer by id, optionally narrowing to a subset
// of channels. Time-slice referencing is deferred (see §10); the same
// composition pattern will extend cleanly when it arrives.
message LayerRef {
  // layer_id of an AudioLayer in the same AudioLayerGroup.
  string layer_id = 1;

  // Channel selection (zero-indexed) into the referenced layer's buffer.
  //   - empty list: all channels of the referenced layer's WaveDetailData.
  //   - non-empty:  these specific channels.
  // Enables per-channel layer derivation (e.g. a mono layer derived from
  // channel 0 of a stereo recording).
  repeated int32 channels = 2;
}

// ---- 5.4 Device type ---------------------------------------------------

// Capture device class. Coarse on purpose — richer device tracking is
// deferred (see §6.5 and §10).
enum DeviceType {
  DEVICE_UNSPECIFIED = 0;
  VOCAL_MIC          = 1;
  INSTRUMENT_MIC     = 2;
  ROOM_MIC           = 3;
}

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
}

// ---- 5.6 AU parameter state --------------------------------------------

// AU parameter snapshot. Aligned with the Rings 0.39 AU storage proposal.
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
```

---

## 6. Design Decisions and Rationale

### 6.1 Layer-relationship representation — DAG with a tree-shaped default

Three options:

| Shape          | Can express                                                                                       | Cannot express                          |
|----------------|---------------------------------------------------------------------------------------------------|-----------------------------------------|
| Tree           | Use case A (linear chain), Use case D (single layer).                                             | A layer with two parents (e.g. wet/dry blend, sum bus). |
| DAG            | A, future wet/dry blends (sum of two parents), C (FX with possibly multiple inputs).              | Cycles — there is no audio use case that needs them. |
| Named-edge graph | All of the above, plus relationships outside "derives from."                                    | Disciplined consumption — "who is the dry of X?" needs an edge-label index. |

**Decision: a DAG, expressed as `derived_from` (parent edges) on each layer.**

Why DAG and not pure tree: use case A's *reverb* layer technically *only* derives
from the *echo* (one parent), so a tree handles A. But a *summed wet/dry blend*
layer would have two parents (dry + wet), which a tree cannot express. Use case
C already brushes against this. Choosing DAG now avoids a breaking change later.

Why no named-edge layer (no `LayerEdge` message): for the near-term
producers (AU recordings, transforms of layers), every relationship the
implementation will encounter is "this layer derives from that layer." The
`derived_from` field expresses that directly. Adding a free-form named-edge
mechanism now is speculation. If non-derivation relationships become needed
later (e.g. "is the room mic for", "is the L of"), `LayerEdge` is reintroduced
at that point — a small additive change, not a breaking one.

Why no cycles: there is no audio use case where layer A derives from B and B
derives from A. The wire format cannot enforce acyclicity, so the writer-side
helper validates and the reader is defensive (cycles surface as malformed
records, not infinite traversal).

### 6.2 Production lineage — typed kinds plus a small transform catalog

`ProductionLineage` carries a `ProducerKind` enum (six values: microphone,
AU, synthesis, model generation, transform, external file), and for TRANSFORM
a `TransformKind` enum with a freeform `transform_name` fallback.

Richer producer-specific tracking — model versions, genome embed vectors,
specific AU plugin instance ids, transform-pipeline configurations — is
intentionally deferred. When a consumer actually needs one of these fields, it
is added as a dedicated typed field at that point, not pre-emptively. Adding
fields to `ProductionLineage` is purely additive and does not break existing
data.

### 6.3 Tool-specific parameter state — a single direct AU field, no wrapper

The first downstream consumer (Rings 0.39) needs to store AU parameter
snapshots and nothing else. The earlier draft of this study introduced a
`ToolState` `oneof` wrapper anticipating multiple producer-state types
(synthesis genome, model latent, FX chain). With the non-AU producers' state
deferred, that wrapper has only one occupied slot, which means it is pure
overhead.

**Decision: place `AudioUnitParameterState au_state` directly on
`AudioLayer`.** When a second producer's state actually needs to be stored,
that is the moment to introduce the `oneof` wrapper to hold both the existing
AU state and the new payload — a small refactor, not a breaking change,
because adding a `oneof` and migrating an existing field into it is supported
by proto3 evolution rules (the existing field number can be moved into the
`oneof`).

The chosen approach is also consistent with the `OpaquePayload` /
`google.protobuf.Any` rejection in the original draft: the project has no
existing protobuf practice for typed-bytes payloads, and the simplest expression
of the only known case is a single typed field.

### 6.4 Single payload mode for layer audio

A layer's audio is its `WaveDetailData buffer`. The buffer's `data` field may
be populated inline or omitted. Either way the buffer's `identifier` (existing
lowercase MD5 hex of the file's bytes) is the content-addressed reference
that `AudioLibrary.find` and the resolver layer use to locate the bytes.

There is **no parallel `AudioReference` message**. The earlier draft proposed
one because the multi-layer schema appeared to need a content-addressed handle
distinct from `WaveDetailData`. It does not — the existing identifier is
already that handle, and adding a parallel mechanism would introduce two ways
to do the same thing.

When a referenced layer's bytes cannot be resolved, the loader returns the
`AudioLayer` with `buffer` populated (metadata, identifier, channel count,
etc.) but `buffer.data` absent. No exception. Callers that need audio for
playback check `buffer.hasData() || canResolve(buffer.identifier)`. Callers
that only need metadata (lineage inspection, recipe browsing) ignore the
absence.

### 6.5 No pair-of-layers stereo encoding

The earlier draft introduced `stereo_side` and `stereo_pair_layer_id` fields to
mark a layer as one half of a logical stereo pair. **Decision: drop both.**

Stereo isn't a property of pairs of layers. It is either a property of a single
multi-channel layer (`WaveDetailData.channel_count` already encodes channel
count), or it is a derivation relationship that is now expressible directly
via `LayerRef.channels` (a mono layer derived from channel 0 of a stereo
buffer). The pair-of-layers framing was redundant in the first case and
duplicative of `derived_from` + `channels` in the second.

`DeviceType` is an enum on `AudioLayer` (rather than a wrapper message with a
`target` string and a `device` identifier) for the same reason `au_state` is
direct: the vocabulary that would go inside a wrapper is not yet settled.
More elaborate device tracking — audio interface metadata, specific physical
capture-device identifiers, per-instrument labels — is deferred. If device
handling becomes more central in future use cases, this enum is promoted back
to a message at that point. The risk of having to do this conversion later is
small and worth taking now in favour of doing less.

### 6.6 Backward compatibility

All additions are optional, and `WaveDetailData` is unchanged. Concretely:

1. Old `WaveDetailData` records load unchanged into the new build.
2. New `AudioLayerGroup` records load into an old build — proto3 silently drops
   the unknown field (5) on `AudioLibraryData`. The group's audio is *not*
   visible to old builds; the writer must decide whether to also emit a flat
   `WaveRecording` for backward consumption (see §8.2).
3. `LegacyLibraryMigrator` is unaffected.

The only addition to `audio.proto` is `AudioLayerGroup` at `AudioLibraryData`
field 5, plus the `AudioLayerGroup` / `AudioLayer` / `LayerRef` /
`ProductionLineage` / `AudioUnitParameterState` message bodies and the
`DeviceType` enum.

A small **migration helper** (Java-side, not in the wire format) is proposed
in §8 to lift a flat `WaveRecording` into a single-layer `AudioLayerGroup` so
that new code can operate uniformly on both.

### 6.7 No schema-versioning machinery

Proto3's "ignore unknown fields" rule covers compatibility for the foreseeable
future. The earlier draft proposed both a `framework_version` string and a
`schema_revision` integer. Introducing version-tracking machinery now is
speculation: nothing on the roadmap branches behaviour on either field.

If schema-revision branching becomes necessary, both fields are additive on
`AudioLayerGroup` at that point.

---

## 7. Cross-Check Against the Use Cases

| Use Case | Layers | Key fields populated |
|----------|--------|----------------------|
| **A** (dry → echo → reverb)              | 3 | layers[0]=dry (buffer; lineage.kind=MICROPHONE_RECORDING or EXTERNAL_FILE depending on origin); layers[1]=echo (buffer; derived_from=[{dry}]; lineage.kind=TRANSFORM, transform_kind=ECHO); layers[2]=reverb (buffer; derived_from=[{echo}]; lineage.kind=TRANSFORM, transform_kind=REVERB). |
| **B** (drum kit + room)                  | N+1 | each close-mic layer: buffer; lineage.kind=MICROPHONE_RECORDING; device_type=INSTRUMENT_MIC. Room layer: buffer with channel_count=2; lineage.kind=MICROPHONE_RECORDING; device_type=ROOM_MIC. Per-instrument labelling ("kick", "snare") is **deferred** — see §10. |
| **C** (synth + FX)                       | 2 | synth: buffer; lineage.kind=SYNTHESIS. FX: buffer; derived_from=[{synth}]; lineage.kind=TRANSFORM, transform_kind=FX_BUS. **Gap (acknowledged):** synth-genome parameters and FX-chain parameters are not stored in 0.74. The relationship and producer kind are; the parameter state arrives when the second producer's state is wired up (see §6.3 and §10). |
| **D** (AU sample + AU snapshot)          | 1 | buffer; lineage.kind=AUDIO_UNIT; au_state=AudioUnitParameterState{…, midi_note, midi_velocity, parameters, optional preset_data}. created_at_millis populated. This is the first-consumer path and is fully expressible. |
| **Compose** (D's plugin produced C's synth, with B's room mic alongside) | 1+N+1 | The "synth" layer has lineage.kind=AUDIO_UNIT (the AU plugin produced it) plus au_state. The mic layers stand alongside the synth as siblings in the same AudioLayerGroup with their own device_type. A non-derivation "synth_room_mic_for_layer" relationship would need a future LayerEdge mechanism — out of scope for 0.74. |

Use cases A, B and D are fully expressible. Use case C stores the relationship
and producer kind but defers the producer-state payload (the synth genome and
the FX-chain parameters). This is an acknowledged gap that closes when the
second producer's state is wired up.

---

## 8. Implementation Phasing (Loose)

The implementation task that follows this study should land in this order. Each
step is independently mergeable.

### 8.1 Step 1 — Wire format

- Add the messages from §5 to `audio.proto` (no Java consumer changes).
- Generate the Java types.
- Add a round-trip test that builds an `AudioLayerGroup` with multiple layers
  (one with inline `buffer.data`, one with `buffer.data` omitted), serialises
  it, parses it back, and asserts equality.

### 8.2 Step 2 — Read path interop

- Add a static `AudioLayerGroup.fromLegacy(WaveRecording)` method that lifts a
  flat recording into a single-list-of-layers form for uniform consumption.
  This is a pure-conversion utility on the protobuf type, not a new file
  format.
- Update `AudioLibraryPersistence.loadLibrary` to also expose any
  `AudioLayerGroup`s present in `AudioLibraryData.field 5` (alongside the
  existing `WaveRecording`s).

### 8.3 Step 3 — Write path for AU consumer (Rings 0.39)

- Add a `setCurrentAuState(AudioUnitParameterState)` setter on the writer
  responsible for emitting `AudioLayerGroup`s.
- When the writer serialises an `AudioLayer` for AU-captured audio, it sets
  the new `AudioLayer.au_state` field if a current state is set. Cleared
  after each sample, as in the Rings AU plan.
- This step is *additive*: existing AU-less recording paths see no change.

### 8.4 Step 4 — Write path for multi-layer

- Add a writer that produces `AudioLayerGroup` directly (e.g. from a
  multi-mic capture session or a transform pipeline).
- Provide a factory that registers layer relationships (`derived_from`) given
  the producer pipeline.
- Out of scope for this study: deciding *which* recording flows in
  `studio/compose` switch to producing `AudioLayerGroup` by default. That is
  a downstream product decision, not a format decision.

### 8.5 Step 5 — Resolver coordination

- The buffer-resolution path is the existing `AudioLibrary.find(identifier)`
  call. The new write path must populate `buffer.identifier` whenever it
  omits `buffer.data`, which is already the contract for legacy
  `WaveDetailData` writes.
- Out of scope for this study: changes to the asset-store layer below
  `AudioLibrary.find`. The multi-layer format does not introduce a new
  reference type.

---

## 9. Open Questions and Risks

1. **Cycle prevention in `derived_from`** is enforced by the writer-side
   helper, not the wire format. (Documented in §6.1 — promoted from an open
   question to a decision because the trade-off is settled.)

2. **`preset_data` size in `AudioUnitParameterState`**: a sample-based AU
   plugin's `fullState` plist can be megabytes. Inline storage bloats the
   protobuf. The AU consumer flagged this; a follow-up may add a hashed
   external-blob path for the field (analogous to how `WaveDetailData.data`
   can be omitted with `identifier` carrying the reference). For 0.74 the
   inline form ships and the field stays optional — large presets are an
   opt-in rather than the norm.

3. **Field-number land grab on `AudioLibraryData`**: this study reserves
   field 5 only. The other reservations from the earlier draft
   (`WaveDetailData` 20–29, `WaveRecording` 7–14) are no longer needed
   because `WaveDetailData` and `WaveRecording` are unchanged. The
   implementation task should *not* preemptively reserve ranges on those
   messages.

4. **HNSW indexing in `ProtobufWaveDetailsStore`**: the index keys on
   `WaveDetailData.identifier` (the existing MD5). That identifier is
   present whether `buffer.data` is inline or omitted, because the
   identifier is computed from the file's bytes regardless of where those
   bytes live. There is therefore **no inline requirement for indexing**.
   Producers that store audio on NAS and only metadata on local SSD remain
   indexable on the metadata side. (The earlier draft's "require an inline
   buffer for indexed entries" framing was wrong and is replaced by this
   note.)

5. **AU consumer field-number alignment**: the Rings AU plan tentatively
   requested a field for `au_state`. This study places it at
   `AudioLayer.au_state` (field 6) on the new message rather than on
   `WaveDetailData`. Coordinate with the Rings 0.39 implementer before the
   proto file is modified so that the placement is shared.

---

## 10. Out-of-Scope Notes

The point of this list is that future-me reading the plan knows what was
deliberately left out and why. Each item below is a deliberate omission, not
an oversight.

- **No new Maven module.** The schema lives in `studio/compose`'s existing
  proto source set.
- **No new dependencies.** MD5 (the existing `WaveDetailData.identifier`) is
  already provided. Protobuf is already on the classpath.
- **No serialisation library beyond protobuf.** The codebase has no precedent
  for Apache Arrow or Avro in this domain.
- **`LayerEdge` is deferred.** Free-form named relationships between layers
  (e.g. "is_room_mic_for", "is_send_of") are reintroduced when the first
  non-derivation relationship is needed. For 0.74 every relationship is
  "derives from."
- **Non-AU `ToolState` payloads are deferred.** Synthesis genomes, model
  latents, and FX-chain parameters are not stored in 0.74. The
  `AudioUnitParameterState au_state` field on `AudioLayer` is direct; the
  `oneof` wrapper to hold a second payload type is introduced at the moment
  the second type is needed (see §6.3).
- **Time-slicing of layer references is deferred.** `LayerRef` carries
  `layer_id` + `repeated int32 channels`; the same composition pattern
  extends to time slicing later (`start_time_samples`,
  `end_time_samples` or similar) without restructuring. This is a follow-up
  after channel references are in production.
- **Richer device tracking is deferred.** The `DeviceType` enum is
  intentionally coarse (vocal mic, instrument mic, room mic). Audio
  interface metadata, specific physical capture-device identifiers, and
  per-instrument labels arrive only when a consumer actually needs them.
  Promoting `DeviceType` from an enum back to a message at that point is a
  small change.
- **Richer producer-specific tracking is deferred.** Model versions, genome
  embed vectors, AU plugin instance ids, and similar producer-instance
  metadata are added as dedicated typed fields when the consumer needs them,
  not pre-emptively (see §6.2).
- **Schema-versioning machinery is deferred.** No `framework_version`, no
  `schema_revision`. Proto3's "ignore unknown fields" rule is sufficient for
  the foreseeable future (see §6.7).
- **Re-rendering from stored recipes** is a separate effort. The format
  records enough to make re-rendering possible; the engine that performs it
  is not part of 0.74.
- **Display / catalog conventions** ("which layer is shown by default")
  belong in the consuming UI, not in the wire format.
