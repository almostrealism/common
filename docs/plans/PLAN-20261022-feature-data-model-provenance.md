# Record which model produced a library item's feature data

**Tracker task:** `add9061c-8c30-4571-b321-a5cfb0246aed` — *Record which model produced a library item's feature data*
**Release:** Common 0.76 (project "Common")
**Estimated complexity:** Large

---

## Motivation

A library item's feature data (`WaveDetails.featureData`) is a single latent
vector with no record of which model produced it. `AudioLibrary` computes it
only when it is absent, and persistence stores it in one unkeyed slot
(`WaveDetailData.feature_*`, fields 12–16) with its derived similarities in a
second unkeyed slot (`WaveDetailData.similarities`, field 11).

The consequence is that an application cannot change its feature model safely:

- old features are silently treated as current, because "has features" is the
  only test;
- similarities computed from different models are mixed in one map;
- if new features overwrite the single slot, an older application version
  reading the same library sees features it cannot interpret.

The task is to give feature data provenance — to record the model that produced
it, store features (and the similarities derived from them) keyed by that model,
and let features from a new model sit beside features from an old one, so that
switching models neither corrupts existing data nor is blocked by it.

---

## Scope

Concretely, the following will change.

**`engine/audio` (the feature/library model):**

1. `WaveDataFeatureProvider` gains a model identifier — the string the
   application supplies for the model it loaded (e.g. the model asset name).
2. `WaveDetails` gains feature data and similarities **keyed by model
   identifier**, while continuing to carry the existing single unkeyed feature
   slot and unkeyed similarity map unchanged (these represent legacy on-disk
   data that must stay exactly where it is).
3. `WaveDetailsFactory` writes computed features under the current provider's
   model identifier, and reads only that model's features for
   similarity/`batchSimilarity`.
4. `AudioLibrary` treats an item as missing features when it has none **for the
   current provider's model identifier**, computes them, and leaves other
   models' data untouched. Similarity and the mean-pooled embedding use only the
   current model's features.
5. `AudioLibrary` exposes (a) a report of how much feature data belongs to
   models other than the current one — a count of items and a byte total,
   including the legacy unkeyed slot — and (b) an operation that deletes exactly
   that data, modelled on `LegacyLibraryMigrator.Result`.

**`studio/compose` (persistence):**

6. `WaveDetailData` in `audio.proto` gains an additive, model-keyed field for
   feature sets (feature data + its similarities), with fields 11 and 12–16 left
   in place and unchanged.
7. `AudioLibraryPersistence.encode/decode` (the single serialization point, also
   used by `ProtobufWaveDetailsStore`) reads and writes the keyed field while
   preserving the legacy unkeyed fields verbatim on round-trip.

**Out of scope (named as dependencies / open questions, not widened into):**

- Changing the similarity *algorithm* or the HNSW embedding scheme.
- Migrating the single AutoEncoder model the product ships today to multiple
  concurrent models — this task makes the library *capable* of holding several
  models' data; which models an application loads is the application's concern.
- Any reference to, or coupling to, a specific downstream product.

---

## Approach

### 1. Model identifier on the provider

Add `String getModelIdentifier()` to
`WaveDataFeatureProvider` (`engine/audio/.../audio/data/WaveDataFeatureProvider.java`).
The interface is the contract every feature provider must satisfy, so this is a
required method, not a default — a provider that cannot name its model has no
provenance to record. `AutoEncoderFeatureProvider`
(`studio/compose/.../studio/ml/AutoEncoderFeatureProvider.java`) takes the
identifier from its constructor (the application passes the model asset name it
loaded). The two test-support providers,
`HarmonicEnergyFeatureProvider` and `LogSpectrumFeatureProvider`
(`studio/compose/.../persistence/test/support/`), gain an identifier the same
way; they are test fixtures, so updating them is in scope.

### 2. Keyed feature storage on `WaveDetails`

Introduce a value type that bundles one model's feature analysis — proposed
`WaveFeatures` in `engine/audio/.../audio/data/` — carrying the model
identifier, the four feature-metadata values (`featureSampleRate`,
`featureChannelCount`, `featureBinCount`, `featureFrameCount`), the feature
`PackedCollection`, and the similarity map derived from it. Its own behaviour
lives on it (e.g. a `byteLength()` that reports the feature data's memory size),
so the accounting in step 5 is a method on the type that owns the data, not a
helper reaching into it.

`WaveDetails` gains a `Map<String, WaveFeatures>` keyed by model identifier, and
retains its existing `featureData`/`feature*`/`similarities` fields as the
**legacy unkeyed slot** — the data an older application wrote with no
provenance. New methods: look up / store a `WaveFeatures` by model identifier,
list the model identifiers present, and report/remove the data belonging to
models other than a given "keep" identifier (legacy unkeyed slot included).

To keep the many read-only consumers of the no-argument `getFeatureData()` and
`getSimilarities()` correct without threading a model identifier through every
call site, `WaveDetails` tracks an **active model identifier**, set by the
library when it resolves or computes an item for the configured provider. The
no-argument accessors return the active model's `WaveFeatures` (feature data and
similarity map), so similarity graphs, prototype discovery, and the embedding
path transparently see only the current model's data and never the legacy slot.
The legacy unkeyed fields are reachable only through dedicated
legacy-named accessors used by the persistence layer and the cleanup report.

*(Alternative considered — see open questions — explicitly passing the current
model identifier into every reader instead of storing active state on
`WaveDetails`.)*

### 3. Factory writes and reads by model

In `WaveDetailsFactory.forExisting`
(`engine/audio/.../audio/data/WaveDetailsFactory.java`), compute features when
the item has none **for the configured provider's model identifier** (rather
than when the single slot is null), and store the result as a `WaveFeatures`
under that identifier, marking it active. `similarity`, `cachedProductSimilarity`
and `batchSimilarity` read the current model's `WaveFeatures`. Because the
factory owns the provider, it is the natural place that knows the current model
identifier.

### 4. Library completeness, similarity, embedding

In `AudioLibrary` (`engine/audio/.../audio/AudioLibrary.java`):

- `isComplete` and the recompute guard in `computeDetails` test for the current
  model's features (falling back to the legacy behaviour when no feature
  provider is configured, so a library opened without a model still reports the
  same completeness it does today).
- `computeSimilarities`/`computeEmbeddingVector` operate on the current model's
  `WaveFeatures`, so a similarity is only ever computed between two items' data
  for the same model.

### 5. Other-model accounting and cleanup

Add to `AudioLibrary` a method returning a report of the feature data held for
models other than the current one — modelled on `LegacyLibraryMigrator.Result`:
a `record` carrying the item count and total bytes (summed from each
`WaveFeatures.byteLength()` plus each legacy unkeyed slot), with a method that
performs the deletion (removing every non-current model's `WaveFeatures` and
clearing the legacy unkeyed fields) and re-persists through the store. An
application offers this as a cleanup exactly the way it offers
`LegacyLibraryMigrator.Result`.

**File-length constraint:** `AudioLibrary.java` is already 1585 of its 1600-line
hard limit. The report/cleanup concept should therefore be carried by its own
collaborator (a named report type plus the per-item logic on `WaveDetails`/
`WaveFeatures`), keeping `AudioLibrary`'s addition to a thin entry point, and
some existing extraction may be required to stay under the limit. This is called
out as an open question because it may justify a small, mechanical refactor
alongside the feature work.

### 6. Proto and serialization

In `audio.proto` (`studio/compose/src/main/proto/audio.proto`), leave field 11
and fields 12–16 exactly as they are and add an additive, repeated, model-keyed
field at the next free number (21), e.g.:

```protobuf
message FeatureSet {
  string model_identifier = 1;
  double feature_sample_rate = 2;
  int32 feature_channel_count = 3;
  int32 feature_bin_count = 4;
  int32 feature_frame_count = 5;
  CollectionData feature_data = 6;
  map<string, double> similarities = 7;
}
// in WaveDetailData, after rank = 20:
repeated FeatureSet feature_sets = 21;
```

In `AudioLibraryPersistence.encode/decode`
(`studio/compose/.../persistence/AudioLibraryPersistence.java`): encode each
`WaveFeatures` into a `FeatureSet` entry **and** write the preserved legacy
unkeyed fields unchanged; decode the keyed entries into the `WaveFeatures` map
and the legacy fields into the legacy slot. Because this is the only
serialization point and `ProtobufWaveDetailsStore` delegates to it, the store
path is covered by the same change.

### Backward compatibility with the previous release

The previous release reads only fields 11 and 12–16. Proto3 forward
compatibility means it parses a new `WaveDetailData` without error, ignoring
`feature_sets`. Because the new code never overwrites the legacy unkeyed fields,
an older application keeps reading its own features unchanged.

`AudioLibraryPersistence.encode` builds a **fresh** `WaveDetailData` from the
`WaveDetails` (it does not merge the original message), so when the previous
release re-saves an item it rebuilds the record from its own model and the keyed
`feature_sets` it never loaded are dropped. **Decision:** this is acceptable and
will be documented. The keyed feature data is derived, recomputable data — the
cost of the loss is recomputation on the next launch of the newer application,
not the loss of anything irreplaceable; the legacy unkeyed slot the old release
*does* understand survives intact. The alternative (teaching the old release to
preserve unknown fields) would require changing the previous release, which is
out of this task's reach. This reasoning will be recorded in the javadoc of the
new proto field and the encode path, and in `engine/audio/docs/AUDIO_LIBRARY.md`.

---

## Success criteria

A reviewer will know the task is done when the following tests pass (feature/
persistence tests belong in `studio/compose` because they exercise encode/decode
with concrete providers; the two test-support providers gain model identifiers):

1. **Features sit beside each other.** Starting from an item whose only feature
   data is in the legacy unkeyed slot (an "old model"), configure the library
   with a different current model, compute, and assert: the current model's
   features are now present and keyed; the legacy unkeyed slot still reads back
   byte-for-byte; both survive an encode→decode round-trip.
2. **Similarity never mixes models.** With two models' feature sets present on
   the same items, assert that a computed similarity map contains only pairs
   derived from the current model's features, and that switching the current
   model yields the other model's (separate) similarity map — no entry is ever
   computed across models.
3. **Cleanup reports and removes exactly the other models' data.** Assert the
   report's count and byte total equal exactly the non-current `WaveFeatures`
   plus the legacy unkeyed slot; after the delete, only the current model's
   feature data and similarities remain, and the report then reports zero.
4. **Previous-release load.** Assert that a `WaveDetailData` written by the new
   encode, read through only fields 11 and 12–16 (simulating the previous
   release's reader), yields the preserved legacy features; and that a
   simulated old-release rebuild (fresh encode from only the legacy fields)
   drops `feature_sets`, matching the documented behaviour.

Existing tests must stay green, in particular `DiskStoreAudioLibraryTest`,
`AudioLibraryMigrationTest`, `DrawingFeaturePipelineTest`,
`PrototypeDiscoveryPersistenceTest`, `AudioLibraryCacheTest`,
`WaveDetailsSidecarTest`, and `WaveDetailsRankTest`.

The build validator (`checkstyle`, `code_policy`, `test_timeouts`,
`duplicate_code`) must pass, and `AudioLibrary.java` must remain under the
file-length limit.

---

## Dependencies and open questions

1. **`AudioLibrary` file length.** The file is at 1585/1600 lines. Adding a
   cleanup entry point may require a small extraction to stay under the limit.
   Approver to confirm a mechanical refactor (e.g. moving a cohesive block to a
   collaborator) is acceptable within this task, or whether it should be a
   separate preparatory change.
2. **Active-model state vs. explicit threading.** The plan stores an "active
   model identifier" on `WaveDetails` so the no-argument `getFeatureData()`/
   `getSimilarities()` keep working for read-only consumers (`AudioSimilarityGraph`,
   `PrototypeDiscovery`, `ApproximateSimilarityIndex`). The alternative is to
   pass the current model identifier explicitly into every reader. The former is
   far less invasive; the latter avoids putting view-state on a data holder.
   Approver to confirm the active-model approach is acceptable.
3. **Legacy slot as a reserved key?** An implementation could represent the
   legacy unkeyed slot as a `WaveFeatures` under a reserved key, routed back to
   fields 12–16/11 on encode, to unify the in-memory logic. This is an
   implementation choice left to the build; the plan keeps them distinct for
   clarity.
4. **Default model identifier for existing data.** Items whose features exist
   only in the legacy unkeyed slot have no model identifier. They are treated as
   "not the current model" (so the current model recomputes beside them) and are
   counted as other-model data by the cleanup. Confirm this is the intended
   treatment of pre-provenance data.
5. **No new Maven module and no `pom.xml` change** are required; all new types
   land in existing modules (`engine/audio` for the model, `studio/compose` for
   persistence). The proto change regenerates `Audio.*` through the existing
   build.

This task is large but is a single coherent change and need not be split; if the
approver prefers, the proto + persistence half (`studio/compose`) and the
model half (`engine/audio`) could land as two reviews, with the `engine/audio`
model change first since `studio/compose` depends on it.
