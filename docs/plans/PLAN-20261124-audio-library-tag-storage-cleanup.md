---
status: proposed
created: 2026-11-24
closed:
tracker: [0a4a20e3-a804-4753-86be-fa4cb4d063db]
---

# Per-model, per-vocabulary tag storage and cleanup API in the audio library

Plans tracker task **0a4a20e3-a804-4753-86be-fa4cb4d063db** — *"Per-model,
per-vocabulary tag storage and cleanup API in the audio library"* — in release
**Common 0.78** (the release this repository's `master` is building).

## Motivation

The audio library already stores **similarity features** with model provenance:
each `WaveDetails` holds a `WaveFeatures` per feature model, keyed by model
identifier, and the library reports and deletes feature data belonging to models
it no longer uses (`AudioLibrary.otherModelFeatures()` →
`WaveFeatureCleanup.remove()`). That discipline exists so that when the feature
model changes, the old data stays until it is deliberately cleaned up, and a
user who reverts to an older application version keeps working data.

Computed **tags** (labels a tagging model assigns a sample from a controlled
vocabulary) need the same treatment, but with a finer key: a tag set depends
both on *which tagging model* produced it and on *which vocabulary version* it
drew its labels from. A vocabulary revision alone — new label, renamed label,
re-weighted label — invalidates the tags just as a model change does. Without
per-(model, vocabulary-version) storage and a stale-data cleanup, a vocabulary
or model change would either silently mix incompatible tags or force
destructive overwrites that break version reverts.

This is a general library capability, independent of any particular tagging
model, which is why it is framework work in Common and separable from the model
itself.

## Scope

All within Common. Three conceptual phases (one commit at implementation time).

**Phase 1 — in-memory storage and cleanup API (`engine/audio`):**

1. A new `WaveTags` type (package `org.almostrealism.audio.data`) holding one
   tag set's provenance — `modelIdentifier`, `vocabularyVersion` — and its tag
   payload.
2. A small immutable composite key identifying a (model, vocabulary-version)
   pair, used as the map key under which tag sets are stored.
3. Tag storage and queries on `WaveDetails`: write a tag set, read the tags for
   a given (model, vocabulary-version), enumerate the keys present, test for /
   count / remove tag sets from any other (model, vocabulary-version).
4. A new `WaveTagCleanup` type (package `org.almostrealism.audio`, beside
   `WaveFeatureCleanup`) that reports the items carrying stale tags and deletes
   exactly those, re-persisting the kept data.

**Phase 2 — persistence round-trip (`studio/compose`):**

5. A `TagSet` protobuf message and a `repeated tag_sets` field on
   `WaveDetailData` in `audio.proto` (additive; next free field number).
6. Encode/decode of tag sets in `AudioLibraryPersistence`, so tags survive a
   store round-trip exactly as feature sets do.

**Phase 3 — test:**

7. A `TagProvenanceTest` in `studio/compose` (beside `FeatureProvenanceTest`)
   covering write, read-current, enumerate-stale, delete, and the encode/decode
   round-trip.

**Explicitly out of scope** (named as dependencies / open questions below): any
concrete tagging model; a tag *provider* interface analogous to
`WaveDataFeatureProvider`; and an `AudioLibrary.otherModelTags()` convenience
accessor mirroring `otherModelFeatures()` (see *Dependencies and open
questions* — `AudioLibrary.java` is at the file-length hard limit).

## Approach

The work is a near-exact structural mirror of the per-model feature machinery,
with two deliberate differences: the key is a (model, vocabulary-version) pair
rather than a bare model identifier, and tags are small string/number data
rather than GPU-resident tensors (so there is no `Destroyable` native-memory
release and no legacy unkeyed slot to preserve).

### Tag payload representation

`WaveTags` carries a `Map<String, Double>` from tag label to score, mirroring
`WaveFeatures.getSimilarities()`. This covers both score-bearing taggers and
label-only taggers (score `1.0` for a present label), persists directly as a
protobuf `map<string, double>`, and keeps the library model-agnostic. The
alternative payload shapes are raised as an open question.

### `WaveTags` (new, `engine/audio` `data` package)

Immutable provenance (`modelIdentifier`, `vocabularyVersion`) plus the mutable
tag map, following `WaveFeatures`. Unlike `WaveFeatures` it is **not**
`Destroyable`: it holds no `PackedCollection`, so there is no native memory to
release. Accounting that belongs on the type (a tag count) lives here, as
`byteLength()` lives on `WaveFeatures`.

### Composite key (new, `engine/audio` `data` package)

A small immutable record — e.g. `TagModelVersion(String modelIdentifier,
String vocabularyVersion)` — provides `equals`/`hashCode` for use as a map key.
Placing it on the type that owns the concept (tag storage) keeps the key out of
caller code. The exact name is an open question.

### `WaveDetails` tag storage (`engine/audio`)

Add a `Map<TagModelVersion, WaveTags> tagSets` field beside `featureSets`, and
methods mirroring the feature accessors — tags have no legacy slot and no
active-model fallback, so these are simpler than their feature counterparts:

- `putTags(WaveTags)` — store/replace a tag set under its (model, version) key
  (mirrors `putFeatures`).
- `getTags(String modelIdentifier, String vocabularyVersion)` — the **read-current**
  query: the tag set for the given key, or `null` (mirrors `getFeatures`). The
  caller passes the model/vocabulary it currently uses.
- `getTagModelVersions()` — the keys present (mirrors
  `getFeatureModelIdentifiers`).
- `hasOtherModelTags(keepModel, keepVersion)` / `otherModelTagCount(keepModel,
  keepVersion)` — the **enumerate-stale** predicate and count, over every key
  that is not the kept one (mirrors `hasOtherModelFeatures` /
  `otherModelByteLength`, reporting a count rather than bytes because tags are
  not native memory).
- `removeOtherModelTags(keepModel, keepVersion)` — drop every tag set whose key
  is not the kept one (mirrors `removeOtherModelFeatures`, without the
  `destroy()`/legacy-slot handling).

### `WaveTagCleanup` (new, `engine/audio` package, beside `WaveFeatureCleanup`)

A record `WaveTagCleanup(AudioLibrary library, String keepModelIdentifier,
String keepVocabularyVersion, int itemCount, int staleTagSetCount)` with a
static `report(library, keepModel, keepVersion)` that scans
`library.allDetails()` and a `remove()` that, for each affected item, calls
`details.removeOtherModelTags(...)` then `library.persist(details)` — exactly
the shape of `WaveFeatureCleanup`. Because it drives everything through the
existing `AudioLibrary.allDetails()` and `AudioLibrary.persist(WaveDetails)`,
**no new method on `AudioLibrary` is required**, which matters given that class
is at the file-length hard limit (see open questions). The "current" (model,
vocabulary-version) to keep is passed in explicitly, mirroring
`WaveFeatureCleanup.report(library, keepModelIdentifier)`.

### Persistence (`studio/compose`)

`audio.proto` currently defines `FeatureSet` and `repeated feature_sets = 21`
on `WaveDetailData` (with `rank = 20`). Add, additively:

```protobuf
message TagSet {
  string model_identifier = 1;
  string vocabulary_version = 2;
  map<string, double> tags = 3;
}
```

and `repeated TagSet tag_sets = 22;` on `WaveDetailData` (the next free field
number; `reserved 1` unchanged). Additivity gives the same backward
compatibility feature sets have: a previous release parses and ignores the new
field, and because `encode` builds a fresh message, re-saving by an old release
drops only the derived, recomputable tag data.

In `AudioLibraryPersistence`, add `encodeTagSet`/`decodeTagSet` private helpers
and extend `encode`/`decode` to round-trip `tag_sets`, exactly as
`encodeFeatureSet`/`decodeFeatureSet` handle `feature_sets`. This is the single
serialization point, also used by `ProtobufWaveDetailsStore`, so the store
round-trips tags without further change.

### Module boundaries

`engine/audio` keeps no protobuf dependency: `WaveTags`, the key record,
`WaveDetails` and `WaveTagCleanup` are plain Java, exactly as `WaveFeatures`
and `WaveFeatureCleanup` are. The proto message and its encode/decode live in
`studio/compose`, matching where `FeatureSet` and `AudioLibraryPersistence`
already live.

## Success criteria

A reviewer can confirm the task is done when:

1. **Round-trip.** A `WaveDetails` carrying tag sets under two or more (model,
   vocabulary-version) keys survives `AudioLibraryPersistence.encode` →
   `decode` with every key and tag map intact (and, through
   `ProtobufWaveDetailsStore`, a store put/get round-trip).
2. **Read-current.** `getTags(model, version)` returns exactly the tag set
   written under that key and `null` for a key never written.
3. **Enumerate-stale.** `WaveTagCleanup.report(library, keepModel, keepVersion)`
   counts exactly the items (and tag sets) whose keys differ from the kept one
   — differing by model, by vocabulary version, or both — and nothing under the
   kept key.
4. **Delete.** `remove()` deletes exactly the stale tag sets, leaving the kept
   key's tags intact, and a subsequent `report` reads zero. The removal
   survives a reload (it is re-persisted).
5. **Backward compatibility.** A record written with tag sets is still read
   correctly by the existing feature/legacy decode path, and the new field does
   not disturb existing `feature_sets`/legacy round-trips (existing
   `FeatureProvenanceTest` continues to pass).

The proving tests: a new `TagProvenanceTest` in `studio/compose` covering
criteria 1–4 (mirroring `FeatureProvenanceTest`'s structure, including a
two-key "stale" scenario where one key differs only by vocabulary version), and
the unchanged `FeatureProvenanceTest` for criterion 5. Run targeted:
`TagProvenanceTest` and `FeatureProvenanceTest` (studio/compose), plus the
build validator (checkstyle/code_policy).

## Dependencies and open questions

- **`AudioLibrary.java` is at the file-length hard limit (1599/1600 lines).**
  Adding an `otherModelTags()` convenience accessor to mirror
  `otherModelFeatures()` would cross the checkstyle `FileLength` hard limit and
  fail the build. This plan therefore makes `WaveTagCleanup.report(...)` the
  entry point and adds no `AudioLibrary` method. **Decision needed:** accept the
  static entry point as the public surface, or schedule a separate extraction
  refactor of `AudioLibrary` (splitting a cohesive collaborator out to bring the
  file back under the ~1500-line recommendation) *before* adding a mirrored
  `otherModelTags()` method. The refactor is deliberately **not** bundled into
  this task; if the mirrored accessor is wanted it should be its own tracked
  change.
- **Tag payload shape.** This plan proposes `Map<String, Double>` (label →
  score). If a tagging model needs richer per-tag structure (e.g. provenance
  per label, or ordered/ranked tags), the payload type should be settled before
  the proto field is committed, since the on-disk schema is the hard-to-change
  part. Confirm `Map<String, Double>` is sufficient for the first tagging model
  that will consume this API.
- **Is "current" caller-supplied or provider-derived?** Features derive the
  kept model from a configured `WaveDataFeatureProvider` via
  `AudioLibrary.currentModelId()`. Tags have no such provider in scope here, so
  "current" is passed in explicitly. If a tagging *provider* abstraction is
  later introduced, a provider-derived convenience method can be layered on top
  without changing this storage API — noted as a possible follow-up, not part of
  this task.
- **Composite key naming.** `TagModelVersion` is a placeholder; the reviewer may
  prefer another name for the (model, vocabulary-version) key record.

No machine dependency: this work is plain string/number storage, protobuf, and
in-memory maps, with no kernel computation, so it runs on any FlowTree node and
needs no `requiredLabels` settings file.

## Estimated complexity

**Medium.** The structure is fully determined by the existing per-model feature
machinery, so there is little design risk, but the change spans two modules
(`engine/audio` data/cleanup classes and the `studio/compose` proto +
persistence), adds a protobuf field that must stay backward-compatible, and
needs a multi-criterion provenance test. It is larger than a single-class change
but well short of large; the only real judgment calls are the open questions
above.
