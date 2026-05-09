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
| A | Stereo dry → matching stereo echo → matching stereo reverb                | Three layers; relationship: echo derives-from dry, reverb derives-from echo. Each derived layer has a `transform` whose `transform_kind` is `ECHO` or `REVERB`. |
| B | Mono close mics on each drum + stereo room mic                            | One group with N+1 layers. Close-mic layers carry `device_type = INSTRUMENT_MIC`; the room layer carries `device_type = ROOM_MIC`. Per-instrument labelling (kick / snare / …) is deferred — see §10. |
| C | Mono synth + matching stereo FX                                           | Two layers; FX has `derived_from` referencing synth and `transform.transform_kind = FX_BUS`. Synth-genome / FX-chain parameters are not yet stored — that arrives when the second producer's state is wired up. |
| D | AU-captured sample + AU parameter snapshot at capture time                | Two layers. Layer 1 is a `MidiPattern` carrying the note-on / note-off events sent to the AU (no `transform`, no `derived_from`, no `au_state`). Layer 2 is the audio rendering (`WaveDetailData`) with `derived_from = [Layer 1]` and `au_state` carrying `AudioUnitParameterState` (component description, parameter map, optional preset blob); `transform` is unset — the MIDI source plus populated `au_state` together signal that this layer is an AU rendering. |

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

// A layer carries either an audio rendering or a MIDI pattern via the
// `content` oneof. For audio layers the payload is a WaveDetailData; its
// `data` field may be inline or omitted, and either way the buffer's
// `identifier` (existing MD5 of file bytes) is the content-addressed
// reference the library uses to resolve missing-audio cases. For MIDI
// layers the payload is a MidiPattern carrying a structured event list
// modelled on MidiNoteEvent / PatternElement (see §5.7).
// An unset `content` oneof is permitted for metadata-only placeholders.
//
// A typical AU-capture writer emits two layers per take: a MidiPattern
// layer for the note events sent to the plugin and an audio layer for
// the rendering. Other metadata (transforms, derived layers, multi-mic
// captures) is optional.
message AudioLayer {
  // Per-layer identifier.
  string layer_id = 1;

  // Layer payload. A layer is either an audio rendering or a MIDI pattern.
  oneof content {
    // Audio payload. Re-uses the existing per-buffer message so that all
    // of freq_data, feature_data, similarities, identifier, etc. continue
    // to be available unmodified. `audio.data` may be omitted; the library
    // resolves audio bytes by `audio.identifier`.
    WaveDetailData audio = 2;

    // MIDI payload. See §5.7.
    MidiPattern midi = 3;
  }

  // Transform that produced this layer from its parents, when the layer
  // is a derivation of other layers. Source layers leave this unset.
  // See §6.2.
  optional TransformInfo transform = 4;

  // Layers this layer was directly derived from (zero or more). For the
  // "dry → echo → reverb" chain, the echo layer's derived_from is [dry],
  // and the reverb layer's derived_from is [echo]. The transform that
  // produced this layer from the parents is on `transform`.
  repeated LayerRef derived_from = 5;

  // Capture device class, when the layer is a microphone capture.
  optional DeviceType device_type = 6;

  // AU parameter snapshot at the moment this layer was produced. Populated
  // for AU-rendered audio layers. State for non-AU producers (synthesis,
  // model generation, FX chains) is deferred — see §6.3 and §10.
  optional AudioUnitParameterState au_state = 7;

  // Unix epoch milliseconds at which this layer was produced.
  optional int64 created_at_millis = 8;
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
// deferred (see §6.5 and §10). DEVICE_SOFTWARE covers software instruments
// (AU hosts, other virtual instruments); the physical-mic values cover
// hardware capture. See §6.5 for why software is its own value rather
// than a sub-flag of an existing one.
enum DeviceType {
  DEVICE_UNSPECIFIED = 0;
  DEVICE_SOFTWARE    = 1;
  VOCAL_MIC          = 2;
  INSTRUMENT_MIC     = 3;
  ROOM_MIC           = 4;
}

// ---- 5.5 Transform info ------------------------------------------------

// What transform produced this layer from its parent layers, when the
// layer is a derivation. Source layers (microphone captures, MIDI sources,
// AU renderings, etc.) leave this unset; the device, payload type, and
// `au_state` together identify the producer.
message TransformInfo {
  // Catalog of well-known transforms; freeform name in `transform_name`
  // covers anything else.
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
  optional TransformKind transform_kind = 1;
  optional string        transform_name = 2;
}

// ---- 5.6 AU parameter state --------------------------------------------

// AU parameter snapshot — a description of *what the AU plugin's settings
// were* at render time. The MIDI events that triggered the rendering live
// on a sibling MidiPattern layer (see §5.7 and §6.X), not here.
message AudioUnitParameterState {
  string component_description = 1;     // e.g. "aumu,Alch,Appl,0001"
  optional string display_name      = 2;
  optional string manufacturer_name = 3;

  // AUParameterAddress is uint64; protobuf forbids uint64 map keys, so the
  // address is encoded as a decimal string.
  map<string, float> parameters = 4;

  // AUAudioUnit.fullState binary plist. Optional because it can be MB-size.
  optional bytes preset_data = 5;
}

// ---- 5.7 MIDI pattern --------------------------------------------------

// MIDI payload for a layer whose `content` oneof selects `midi`. The
// shape mirrors the canonical in-memory MIDI representation in the music
// module — `org.almostrealism.music.midi.MidiNoteEvent` — which is the
// event type already consumed by both the SkyTNT V2 tokenizer
// (`SkyTntTokenizerV2`) and the Moonbeam tokenizer (`MidiTokenizer`), as
// well as the pattern-to-MIDI export path on `PatternElement`.
//
// Time is absolute ticks at a stated `ticks_per_quarter` (PPQ), matching
// `MidiNoteEvent.tick` and the standard MIDI File idiom. Tempo and
// time-signature changes ride inside the event stream as their own event
// types (mirroring `MidiNoteEvent.EventType.SET_TEMPO` /
// `TIME_SIGNATURE`); they are not re-stored as separate pattern-level
// scalars. See §6.X1 for the time-representation rationale and §6.X2 for
// the AU-host serialisation contract.
message MidiPattern {
  // PPQ for all `tick` and `duration_ticks` values in this pattern.
  // Defaults to 480 (the SkyTNT default and a standard SMF value) when
  // absent.
  optional int32 ticks_per_quarter = 1;

  // Events comprising this pattern, ordered by tick (writers SHOULD emit
  // them in tick order; readers MAY rely on this).
  repeated MidiEvent events = 2;
}

// One MIDI event — a single record carrying a discriminated payload.
// The single-class-with-discriminant shape mirrors `MidiNoteEvent` (one
// class, one `EventType` enum, one set of fields) rather than wrapping
// each event type in its own outer message.
message MidiEvent {
  // Absolute tick offset from the start of the pattern.
  int64 tick = 1;

  // MIDI track index (0-based). Defaults to 0 when absent.
  optional int32 track = 2;

  // MIDI channel (0–15). Relevant for `note`, `control_change`,
  // `pitch_bend`, `program_change`. Tempo / time-signature / key-signature
  // events ignore it.
  optional int32 channel = 3;

  // Event-type-specific payload. The set mirrors `MidiNoteEvent.EventType`
  // (NOTE, PATCH_CHANGE, CONTROL_CHANGE, SET_TEMPO, TIME_SIGNATURE,
  // KEY_SIGNATURE) plus pitch-bend, which `MidiSynthesizerBridge` handles
  // today but `MidiNoteEvent` does not yet enumerate.
  oneof payload {
    NoteEvent           note            = 4;
    ControlChangeEvent  control_change  = 5;
    PitchBendEvent      pitch_bend      = 6;
    ProgramChangeEvent  program_change  = 7;
    SetTempoEvent       set_tempo       = 8;
    TimeSignatureEvent  time_signature  = 9;
    KeySignatureEvent   key_signature   = 10;
  }
}

// A pitched note-on plus its matching note-off, encoded as a single record
// with a duration. This mirrors `MidiNoteEvent` (NOTE events carry
// `durationTicks`) and `PatternElement` (which carries an effective
// duration via `NoteDurationStrategy`). On AU export the duration is
// materialised as a deferred note-off at `tick + duration_ticks` (see
// §6.X2).
message NoteEvent {
  int32 pitch          = 1;  // 0–127
  int32 velocity       = 2;  // 0–127
  int64 duration_ticks = 3;  // tick units of the enclosing pattern
}

// Continuous-controller change.
message ControlChangeEvent {
  int32 controller = 1;  // 0–127
  int32 value      = 2;  // 0–127
}

// 14-bit pitch-bend, signed (-8192 .. +8191), `0` = no bend.
message PitchBendEvent {
  int32 value = 1;
}

// Program (patch) change. Mirrors `MidiNoteEvent.EventType.PATCH_CHANGE`.
message ProgramChangeEvent {
  int32 program = 1;  // 0–127
}

// Tempo change, in BPM. Mirrors `MidiNoteEvent.EventType.SET_TEMPO`
// (the `bpm` field). Microseconds-per-beat is recoverable as
// `60_000_000 / bpm`.
message SetTempoEvent {
  int32 bpm = 1;
}

// Time signature. Stored in the user-visible form (numerator + denominator
// as a power of two: 2, 4, 8, 16). Mirrors `MidiNoteEvent`'s `nn` /
// `dd` (which store `numerator − 1` / `log2(denominator) − 1` for the
// SkyTNT vocabulary); conversion is mechanical at the boundary.
message TimeSignatureEvent {
  int32 numerator   = 1;  // beats per bar
  int32 denominator = 2;  // 2, 4, 8, 16 (power of two)
}

// Key signature. Mirrors `MidiNoteEvent.EventType.KEY_SIGNATURE` with the
// SMF-native sharp/flat convention (negative = flats, positive = sharps,
// range -7..+7). `MidiNoteEvent.sf` stores `sharps_flats + 7`; conversion
// is mechanical at the boundary.
message KeySignatureEvent {
  int32 sharps_flats = 1;  // -7 (7 flats) .. +7 (7 sharps); 0 = C / a
  int32 mode         = 2;  // 0 = major, 1 = minor
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

### 6.2 Transform tracking — a small named-transform catalog

`TransformInfo` carries a `TransformKind` enum (the catalog of well-known
transforms — ECHO, REVERB, EQ, COMPRESS, GAIN, PITCH, TIME, FX_BUS, SUM)
with a freeform `transform_name` fallback. Source layers — microphone
captures, MIDI sources, AU renderings, external-file imports — leave
`transform` unset; the combination of `device_type`, the `content` payload
type, `au_state`, and `derived_from` is sufficient to identify what
produced the layer.

Notably, no `RENDER` / `MIDI_TO_AUDIO` value is introduced for AU
rendering: an audio layer with `derived_from` referencing a `MidiPattern`
source and a populated `au_state` already conveys that meaning, and
adding an enum value would duplicate the signal (see §10).

Richer producer-specific tracking — model versions, genome embed vectors,
specific AU plugin instance ids, transform-pipeline configurations — is
intentionally deferred. When a consumer actually needs one of these fields,
it is added as a dedicated typed field at that point, not pre-emptively.
Adding fields to `TransformInfo` is purely additive and does not break
existing data.

### 6.3 AU parameter state — a single direct field

`AudioUnitParameterState` is placed directly on `AudioLayer.au_state` (a
single optional field, not wrapped). State for non-AU producers (synthesis
genomes, model latents, FX-chain parameters) is deferred — see §10. When
a second producer's state actually needs to be stored, the field is
migrated into a `oneof` wrapper at that point: a small refactor under
proto3 evolution rules, not a breaking change.

### 6.4 Single payload mode for layer audio

An audio layer's payload is its `audio` (WaveDetailData) field within the
`content` oneof. The buffer's `data` field may be populated inline or
omitted. Either way the buffer's `identifier` (existing lowercase MD5 hex
of the file's bytes) is the content-addressed reference that
`AudioLibrary.find` and the resolver layer use to locate the bytes.

There is **no parallel `AudioReference` message**. The earlier draft proposed
one because the multi-layer schema appeared to need a content-addressed handle
distinct from `WaveDetailData`. It does not — the existing identifier is
already that handle, and adding a parallel mechanism would introduce two ways
to do the same thing.

When a referenced layer's bytes cannot be resolved, the loader returns the
`AudioLayer` with `audio` populated (metadata, identifier, channel count,
etc.) but `audio.data` absent. No exception. Callers that need audio for
playback check `audio.hasData() || canResolve(audio.identifier)`. Callers
that only need metadata (transform inspection, recipe browsing) ignore the
absence.

### 6.X Audio and MIDI as mutually exclusive content

`AudioLayer.content` is a `oneof` of `WaveDetailData audio` and
`MidiPattern midi`. A layer is either an audio rendering or a MIDI pattern
— never both, and the schema enforces that directly. An unset oneof is
permitted for metadata-only placeholders.

MIDI is in scope from day one because the first downstream consumer
(Rings 0.39 AU recording) inherently produces *both* a MIDI input and an
audio output: the AU plugin is driven by note events, and the rendered
audio is what gets stored. A previous shape buried the MIDI events inside
`AudioUnitParameterState` as `midi_note` / `midi_velocity` / `midi_channel`
fields, which was a category error — those events are the *input* to the
AU, not part of its parameter snapshot. Promoting MIDI to its own first-
class layer payload makes the AU-render relationship "audio derived from
MIDI source, with `au_state` describing the plugin" expressible without
adding new enum values or wrapping types (see §6.2 and §7 use case D).

#### `MidiPattern` is structured, not a bytes blob

The earlier draft made `MidiPattern` a thin envelope around an SMF byte
stream (`bytes data` + optional `string format`). That was a cop-out: once
the format ships, "we'll structure it later" never actually happens, and
every consumer that wants to inspect, edit, or generate a pattern would
have to drag in an SMF parser to do it. The first-consumer use case (AU
capture) is *exactly* one of those consumers — the AU host JSON protocol
takes structured `note_on` / `note_off` events, not opaque SMF.

The structured shape (§5.7) is grounded in the music module's existing
canonical event type, `org.almostrealism.music.midi.MidiNoteEvent`, which
is already the single representation consumed by the SkyTNT V2 tokenizer
(`SkyTntTokenizerV2`) and the Moonbeam tokenizer (`MidiTokenizer`), and
produced by the pattern-to-MIDI export path on `PatternElement.toMidiEvents`.
Three things follow from that grounding:

1. **Same event-type vocabulary.** `MidiEvent.payload`'s arms cover the
   six event types in `MidiNoteEvent.EventType` (NOTE, PATCH_CHANGE,
   CONTROL_CHANGE, SET_TEMPO, TIME_SIGNATURE, KEY_SIGNATURE) plus
   pitch-bend, which `engine/audio` already handles
   (`MidiSynthesizerBridge`) but `MidiNoteEvent` does not yet enumerate.
   Aftertouch and system messages remain deferred (see §10).
2. **Single record per event, with discriminator.** `MidiNoteEvent` is a
   single class with an `EventType` discriminator and per-type fields;
   `MidiEvent` mirrors that with one message and a `oneof payload`. This
   avoids the "wrap each event type in its own outer message" idiom,
   which would introduce a structural divergence between the wire format
   and the in-memory representation.
3. **Notes carry duration, not on/off pairs.** `MidiNoteEvent.NOTE`
   carries `durationTicks`; `PatternElement` carries an effective
   duration via `NoteDurationStrategy`. `NoteEvent.duration_ticks`
   matches both. AU export materialises the matching note-off at
   `tick + duration_ticks` (see §6.X2). On round-trip from SMF, paired
   note-on/note-off bytes collapse into one `NoteEvent`; on round-trip
   to SMF, each `NoteEvent` re-expands into the matched pair.

Features `PatternElement` carries that exceed the basic MIDI message set
(MAIN/WET voicing, scale-relative pitch via `ScaleTraversalStrategy`,
`automationParameters` as a `PackedCollection`, repeat structure,
`PatternDirection`) are intentionally *not* present in the §5.7 schema.
They survive a `PatternElement → structured MidiPattern → SMF` export
only by being expanded into concrete events at export time (each repeat
becomes its own NoteEvent; scale traversal resolves to a concrete pitch;
automation curves render as CC streams when wired up). The schema
provides the substrate they expand *into*; it does not yet carry the
unexpanded form. This is the deliberate "do less" cut described in §10.

#### 6.X1 Time representation — absolute ticks at a stated PPQ

`MidiPattern.ticks_per_quarter` plus per-event `tick` and per-`NoteEvent`
`duration_ticks` is the chosen time representation. It is grounded in
three observations from the codebase:

- `MidiNoteEvent.tick` is an absolute long-tick offset from the start of
  the sequence; `durationTicks` is in the same unit. The structured
  pattern uses the same convention to make the `MidiNoteEvent` ↔
  `MidiPattern` mapping field-for-field.
- `SkyTntMidi.DEFAULT_TICKS_PER_BEAT = 480` matches the most common SMF
  PPQ and is already the assumed rate for SkyTNT-produced patterns.
  `ticks_per_quarter` defaults to 480 when absent, so writers that don't
  care can omit it.
- Tempo and time-signature changes are first-class events
  (`SetTempoEvent`, `TimeSignatureEvent`), riding inside the event stream
  rather than being hoisted to pattern-level scalars. This matches
  `MidiNoteEvent.EventType.SET_TEMPO` / `TIME_SIGNATURE` (both already
  modelled as events, not as out-of-band metadata) and matches SMF
  semantics directly.

Fractional-beat time (the alternative the task surfaced) was rejected
because nothing in the codebase already represents MIDI events that way:
both tokenizers, the canonical `MidiNoteEvent`, and the SMF round-trip
path are tick-native. Going fractional-beats would require a translation
layer at every boundary; ticks are already the lingua franca. The mapping
to `PatternElement`'s measure-relative `position` is mechanical: at
export time, `position_in_measures × beats_per_measure ×
ticks_per_quarter` yields the absolute tick.

#### 6.X2 AU-host serialisation contract

The AU host wire protocol (`AUHostManager.noteOn` /
`AUHostManager.noteOff` in `audio-desktop/...auhost`) accepts JSON
commands of the form:

```json
{ "cmd": "midi", "id": "<pluginId>", "type": "note_on",  "note": 60, "velocity": 100 }
{ "cmd": "midi", "id": "<pluginId>", "type": "note_off", "note": 60, "velocity":   0 }
```

The structured `MidiPattern` serialises to this protocol by walking
`events` in tick order and emitting, for each event:

| Structured event              | AU host JSON command(s) |
|-------------------------------|-------------------------|
| `NoteEvent { pitch, velocity, duration_ticks }` | one `note_on` at `tick`, one `note_off` at `tick + duration_ticks` |
| `ControlChangeEvent { controller, value }`      | (TBD when AU host gains a CC command — currently dropped) |
| `PitchBendEvent { value }`                      | (TBD — currently dropped) |
| `ProgramChangeEvent { program }`                | (TBD — currently dropped) |
| `SetTempoEvent`, `TimeSignatureEvent`, `KeySignatureEvent` | not sent — AU host does not consume them |

For the *first-consumer* case (AU capture playback driving an instrument
plugin) only `NoteEvent` is needed, and the mapping is the trivial
duration-to-deferred-`note_off` materialisation above. As the AU host
gains CC / pitch-bend / program-change commands, the corresponding
`MidiEvent.payload` arms map directly without wire-format change. The
AU-host bridge is responsible for translating ticks to wall time using
the surrounding `SetTempoEvent` / `ticks_per_quarter`.

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

`DEVICE_SOFTWARE` is a peer of the physical-mic values rather than a
sub-flag on one of them. The first consumer (Rings 0.39 AU capture) and
the broader near-term capture path are dominated by software AUs and
other virtual instruments, not by physical microphones — `VOCAL_MIC`,
`INSTRUMENT_MIC`, and `ROOM_MIC` all assume a physical capture device,
and a software instrument is *not* a kind of microphone. Adding it as a
fourth physical-mic-flavoured value would conflate "what produced this
audio" with "what kind of microphone produced this audio." Sub-flagging
it (e.g. a `software: bool` boolean alongside `INSTRUMENT_MIC`) would
have the same effect: the consumer would have to read two fields to
decide whether the layer is mic or software. A peer enum value is the
straight expression. `DEVICE_SOFTWARE = 1` places it at the lowest
non-unspecified value, ahead of the physical-mic values, reflecting its
weight in the near-term producer mix.

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
| **D** (AU sample + AU snapshot)          | 2 | layers[0] = MIDI source: `content.midi = MidiPattern { ticks_per_quarter, events: [...] }`, `device_type = DEVICE_SOFTWARE`, no `transform`, no `au_state`, no `derived_from`. layers[1] = audio rendering: `content.audio = WaveDetailData{…}`, `derived_from = [{layer_id of layers[0]}]`, `device_type = DEVICE_SOFTWARE`, `au_state = AudioUnitParameterState{component_description, parameters, optional preset_data}`, `created_at_millis` populated, no `transform`. The MIDI-source-plus-`au_state` pair signals an AU rendering without needing a `RENDER` enum value. This is the first-consumer path and is fully expressible. |
| **Compose** (D's plugin produced C's synth, with B's room mic alongside) | 1+N+1 | The "synth" layer has lineage.kind=AUDIO_UNIT (the AU plugin produced it) plus au_state. The mic layers stand alongside the synth as siblings in the same AudioLayerGroup with their own device_type. A non-derivation "synth_room_mic_for_layer" relationship would need a future LayerEdge mechanism — out of scope for 0.74. |

Use cases A, B and D are fully expressible. Use case C stores the relationship
and producer kind but defers the producer-state payload (the synth genome and
the FX-chain parameters). This is an acknowledged gap that closes when the
second producer's state is wired up.

### 7.1 Worked example — single C-major chord captured through an AU

A capture of a single C-major triad (C4, E4, G4 played together for one
beat at 120 BPM through a software AU) is two layers in one
`AudioLayerGroup`:

```
AudioLayerGroup {
  key       = "take-2026-05-09-001"
  layers[0] = AudioLayer {                        // MIDI source
    layer_id    = "midi-001"
    content.midi = MidiPattern {
      ticks_per_quarter = 480
      events = [
        MidiEvent { tick = 0, channel = 0,
                    payload.set_tempo = SetTempoEvent { bpm = 120 } },
        MidiEvent { tick = 0, channel = 0,
                    payload.note      = NoteEvent { pitch = 60, velocity = 100,
                                                     duration_ticks = 480 } },
        MidiEvent { tick = 0, channel = 0,
                    payload.note      = NoteEvent { pitch = 64, velocity = 100,
                                                     duration_ticks = 480 } },
        MidiEvent { tick = 0, channel = 0,
                    payload.note      = NoteEvent { pitch = 67, velocity = 100,
                                                     duration_ticks = 480 } },
      ]
    }
    device_type        = DEVICE_SOFTWARE
    created_at_millis  = 1714000000000
  }
  layers[1] = AudioLayer {                        // AU rendering
    layer_id        = "audio-001"
    content.audio   = WaveDetailData { /* PCM, identifier=md5 of bytes */ }
    derived_from    = [ { layer_id = "midi-001" } ]
    device_type     = DEVICE_SOFTWARE
    au_state        = AudioUnitParameterState {
      component_description = "aumu,Alch,Appl,0001"
      parameters            = { /* AUParameterAddress -> float */ }
    }
    created_at_millis = 1714000000050
  }
}
```

The AU bridge serialises the MIDI-source layer to the AU-host JSON
protocol by walking `events` in tick order. The `SetTempoEvent` is
consumed by the bridge's tick→wall-time conversion (480 ticks/quarter at
120 BPM ⇒ 500 ms per quarter ⇒ ~1.04 ms/tick) and is *not* sent to the
host. Each `NoteEvent` produces two host commands: a `note_on` at its
tick and a deferred `note_off` at `tick + duration_ticks`. Concretely:

```json
// at t = 0 ms
{ "cmd": "midi", "id": "<plug>", "type": "note_on", "note": 60, "velocity": 100 }
{ "cmd": "midi", "id": "<plug>", "type": "note_on", "note": 64, "velocity": 100 }
{ "cmd": "midi", "id": "<plug>", "type": "note_on", "note": 67, "velocity": 100 }

// at t = 500 ms (tick 480 at 120 BPM, 480 PPQ)
{ "cmd": "midi", "id": "<plug>", "type": "note_off", "note": 60, "velocity": 0 }
{ "cmd": "midi", "id": "<plug>", "type": "note_off", "note": 64, "velocity": 0 }
{ "cmd": "midi", "id": "<plug>", "type": "note_off", "note": 67, "velocity": 0 }
```

A round-trip from the same content as an SMF file is lossless on the
basic message set: each note's tempo, channel, pitch, velocity, and
duration are recovered field-for-field; the three simultaneous notes
remain three NoteEvents at `tick = 0` with `duration_ticks = 480`.

### 7.2 Worked example — `PatternElement` exported through `MidiPattern`

A `PatternElement` placed at `position = 0.5` in 4/4 time, with
`scaleTraversalStrategy = CHORD`, `scalePositions = [0, 2, 4]` over a
C-major scale, `repeatCount = 2`, `repeatDuration = 1.0` (one measure
between repeats), `durationStrategy = FIXED`, `noteDuration = 0.25`
(quarter-beat), and `automationParameters` set to `[1.0]` (so velocity
defaults to 100), exports through `PatternElement.toMidiEvents` into the
following structured form (assuming `ticks_per_quarter = 480`,
`beats_per_measure = 4`):

```
MidiPattern {
  ticks_per_quarter = 480
  events = [
    // Repeat #0 — position 0.5 measures × 4 beats × 480 ticks = 960
    MidiEvent { tick = 960,  channel = 0,
                payload.note = NoteEvent { pitch = 60, velocity = 100,
                                           duration_ticks = 480 } },
    MidiEvent { tick = 960,  channel = 0,
                payload.note = NoteEvent { pitch = 64, velocity = 100,
                                           duration_ticks = 480 } },
    MidiEvent { tick = 960,  channel = 0,
                payload.note = NoteEvent { pitch = 67, velocity = 100,
                                           duration_ticks = 480 } },
    // Repeat #1 — position (0.5 + 1.0) × 4 × 480 = 2880
    MidiEvent { tick = 2880, channel = 0,
                payload.note = NoteEvent { pitch = 60, velocity = 100,
                                           duration_ticks = 480 } },
    MidiEvent { tick = 2880, channel = 0,
                payload.note = NoteEvent { pitch = 64, velocity = 100,
                                           duration_ticks = 480 } },
    MidiEvent { tick = 2880, channel = 0,
                payload.note = NoteEvent { pitch = 67, velocity = 100,
                                           duration_ticks = 480 } },
  ]
}
```

What survived the export:

- **Pitch**: scale traversal resolved `[0, 2, 4]` against C-major into
  C4 / E4 / G4, expressed as MIDI pitch numbers via the existing
  `KeyPosition.position() + 21` convention.
- **Time position**: `position` (in measures) × beats × ticks-per-quarter.
- **Duration**: `NoteDurationStrategy.FIXED` × `noteDurationSelection`
  collapsed to `duration_ticks`.
- **Velocity**: derived from `automationParameters[0] × 127` per the
  existing pattern→MIDI export convention; defaulting to 100 when no
  automation is present.
- **Repeat structure**: each of the two repetitions becomes its own
  triad of `NoteEvent`s at the corresponding tick. The structured form
  carries the *expanded* events; the unexpanded `repeatCount` /
  `repeatDuration` is dropped at this boundary.

What was deferred (intentionally — see §10):

- **Voicing (MAIN/WET)**: the wet-channel `PatternNote`, if present, is
  a separate audio source rather than a separate MIDI note; it does not
  survive a MIDI round-trip and is not represented in the structured
  pattern.
- **`ScaleTraversalStrategy`**: collapsed at export time into concrete
  pitches. Re-importing the structured pattern back into a
  `PatternElement` yields a chord with explicit pitches, not the
  unresolved `[0, 2, 4]` scale-relative form.
- **`automationParameters` as a continuous `PackedCollection`**: only
  the velocity-baking-in is preserved on note events. A continuous
  automation curve would render as a `ControlChangeEvent` stream once
  the export path supports it; until then the curve is dropped.
- **`PatternDirection`**: collapsed at export. Reverse/alternating
  directions resolve into the actual emitted event order.

The structured pattern is, in other words, lossless for everything that
can survive an SMF round-trip and explicit about the
`PatternElement` features that can't.

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

6. **Pattern-level vs layer-level tempo / time signature**: the structured
   `MidiPattern` carries `ticks_per_quarter` plus in-stream
   `SetTempoEvent` / `TimeSignatureEvent` records, mirroring SMF and
   `MidiNoteEvent`. This places the time base on the *pattern*, not on
   the enclosing `AudioLayer` or `AudioLayerGroup`. For a group whose
   layers are all rendered against the same wall-clock take, hoisting a
   shared tempo/time-signature to `AudioLayerGroup` would avoid
   per-pattern duplication. This is left as an open question for the
   implementing task: do consumers need a group-level "scene tempo," or
   is per-pattern sufficient? Default position is per-pattern, since
   that is what `MidiNoteEvent` already enforces in memory.

7. **CC / pitch-bend / program-change export to the AU host**: §6.X2
   notes that the AU-host JSON protocol presently exposes only `note_on`
   / `note_off`. The structured pattern can carry the other event types
   today; the bridge drops them on export. When the host gains CC /
   pitch-bend / program-change commands, the mapping is mechanical (one
   `MidiEvent` ⇒ one host command). No wire-format change is required.

8. **`pitch_bend` versus `MidiNoteEvent.EventType`**: the structured
   `MidiPattern` has a `PitchBendEvent` arm even though
   `MidiNoteEvent.EventType` does not enumerate pitch-bend.
   `MidiSynthesizerBridge` (in `engine/audio`) handles pitch-bend, but
   the canonical event class does not yet carry it. The implementing
   task should either (a) extend `MidiNoteEvent.EventType` with
   `PITCH_BEND` for full parity with the wire format, or (b) accept the
   asymmetry and document it. Default is (a) — the wire format and the
   in-memory event class should stay in sync.

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
- **Pattern-level features that exceed the basic MIDI message set.** The
  structured `MidiPattern` (§5.7) ships *with* the basic MIDI message set
  (note, control change, pitch bend, program change, set tempo, time
  signature, key signature). It does NOT yet ship the
  `PatternElement`-level features that go beyond MIDI: MAIN/WET voicing,
  unresolved scale-relative pitch via `ScaleTraversalStrategy`,
  `automationParameters` as a continuous `PackedCollection`,
  `repeatCount` / `repeatDuration` in unexpanded form, and
  `PatternDirection`. These collapse into concrete events at export
  (each repeat becomes its own `NoteEvent`; scale traversal resolves to
  a concrete pitch; an automation curve renders as a `ControlChangeEvent`
  stream once the export wires that up). When a consumer needs the
  unexpanded form, the schema grows by adding optional fields *to
  `MidiPattern`* (a `pattern_element` extension list), not by changing
  `MidiEvent`. The "do less" cut here is: the structured pattern is the
  expanded view; the unexpanded view stays in `PatternElement` until a
  cross-process consumer needs it.
- **MIDI message types beyond the basic set.** Channel aftertouch,
  polyphonic key pressure, and system messages (sysex, MTC,
  song-position pointer) are not in §5.7. None of the existing
  consumers (`MidiNoteEvent`, both tokenizers, `PatternElement`) carry
  them today. They are added as new `MidiEvent.payload` arms when a
  consumer needs them — additive, not breaking.
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
