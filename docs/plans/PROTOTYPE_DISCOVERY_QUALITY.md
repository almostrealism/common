# Prototype Discovery: Quality Improvements

## Status

The HNSW + Louvain pipeline behind the **Prototypes** tab in the Rings desktop
app is functionally correct after the May 2026 fixes — it produces 12 well-formed
communities covering ~99.7% of the indexed library and renders a prototype per
community in the UI. However, on a representative ~5,700-sample library the 12
displayed prototypes appear to all be drum-flavored, even though the library
contains substantial non-drum material. This document captures what was
investigated, what was fixed, and the suggested next steps for improving
prototype *quality* (diversity / categorical coverage) when this work is
resumed.

## Investigation timeline (May 3–4 2026)

The Prototypes tab was originally returning 12 communities of size 1 each.
Three rounds of investigation and fixes:

### Round 1 — singleton communities

**Symptom:** every prototype showed `(1 samples)`.

**Diagnostic added:** `HNSW diagnostics:` block in `PrototypeDiscovery.buildSparseGraph`
counting samples with no embedding, samples with empty HNSW results, edge
counts, similarity stats, and isolated nodes.

**Finding:** every HNSW query returned 0 neighbors (excluding self). HNSW had
only 18 entries against 5,709 library samples.

**Cause:** legacy data flow. `AudioLibraryPersistence.loadLibrary` and the
no-vector branch of `AudioLibrary.submitMigrationJob` both call
`store.put(id, details)` (no vector). `ProtobufDiskStore.put(String, T)` lines
200–202 actively *remove* the matching HNSW entry on every record write
(`hnswIndex.remove(id)`). Records on disk had feature data, but the
corresponding HNSW slots had been evicted at some earlier point. The desktop's
`AudioLibrary.computeDetails` only re-stores when the record is missing or its
features are null, so once feature data was present, the HNSW gap was never
refilled.

**Fix:** added a backfill pass in `PrototypeDiscovery.backfillEmbeddingsIfNeeded`
that runs *only* when the user clicks Prototypes (no startup cost), gated on
`indexed < 0.9 × totalDetails`. Walks `library.allDetails()`, computes embedding
from each record's existing feature data, and inserts into HNSW via a new
`WaveDetailsStore.insertEmbedding` API that touches the HNSW index only — no
record rewrite. Status reported through the existing `Consumer<String>`
callback to the Prototypes tab progress label.

### Round 2 — one-neighbor-per-query

**Symptom:** after the backfill, HNSW had 5,716 entries but every query was
returning ≈ 1 neighbor (`total directed neighbors stored: 5708` for 5,708
samples). Mean similarity 0.92, max 0.99.

**Cause:** the `HnswIndex.insert` early-return path. When called with an `id`
already present in the `nodes` map (even soft-deleted via `remove()`), the
method updates the cached vector and returns *without* rebuilding the layered
adjacency lists. Most of the 5,698 backfilled IDs had been previously inserted
when the index was sparse and then soft-deleted by no-vector `put` calls. The
backfill resurrected them with stale, near-empty edges.

**Fix:** in `HnswIndex.insert`, the soft-deleted branch now hard-removes the
node from the `nodes` map (via a private `hardRemove` helper that also handles
the entry-point case) before falling through to the normal full-insertion path.
Active nodes still get the cheap vector-only update — that's a real
optimization. Soft-deleted nodes get rebuilt against the current graph. Stale
adjacency entries pointing at the removed ID in *other* nodes' lists are
tolerated by `searchLayer`'s existing null-neighbor check.

**One-time manual step required after this fix:** the existing `hnsw.bin` file
held nodes that had been resurrected with stale edges by the previous
(broken) backfill. Because they were marked active, the backfill's
`hasEmbedding` skip would leave them in place. The fix self-heals only after
the user deletes `hnsw.bin` once. Path is
`SystemUtils.getLocalDestination("library-store")/hnsw.bin` —
`~/Library/Application Support/<app>/library-store/hnsw.bin` for the bundled
app, `./library-store/hnsw.bin` for dev runs.

### Round 3 — prototype diversity

After Rounds 1–2, the pipeline produces healthy graph statistics on a 5,709-
sample library:

- 5,704 of 5,709 nodes have at least one positive-similarity neighbor.
- 110,978 surviving edges (≈ 19.5 per node, matching K=20 minus self).
- Similarity range −0.34 to 0.99, mean 0.93.
- Louvain finds 24 communities; top 11 cover 99.7% of the library
  (sizes: 1234, 997, 686, 639, 635, 623, 353, 337, 95, 64, 33). The other
  13 are singletons.

But the 12 displayed prototypes appear to be all variations on drum samples to
the user, despite the library containing substantial non-drum material. This
is the **outstanding quality issue** that this document tracks.

## Hypotheses for low prototype diversity

In rough order of likelihood given the data:

1. **Embedding collapse.** The autoencoder used by `AutoEncoderFeatureProvider`
   produces embeddings whose mean pairwise cosine at K=20 is 0.93. That's a
   very tight cone of the unit sphere. Even genuinely different audio
   categories (drums, bass, pads, FX) end up with cosine similarities in the
   same `[0.85, 0.99]` band, which gives Louvain no signal to separate them by
   kind. This is a *feature-quality* problem, not a graph problem; no amount
   of post-processing on the graph or selection logic will recover information
   the embedding doesn't carry.
2. **Library composition.** Even if the user perceives "lots" of non-drum
   samples, the 5,709-sample library may be majority-drum by count, so the
   top-12-by-size selection would correctly surface drum subgenres. Cannot be
   confirmed without inspecting actual community membership.
3. **Centroid (PageRank) selection picks misleading prototypes.** PageRank
   chooses the most-connected node in each community. In a mixed community,
   the "most generic" sample (often a drum loop, since drums tend to be the
   most-similar-to-other-drums kind of sound) wins. A community of 600
   samples that's 60% drums and 40% pads might still pick a drum as its
   prototype, hiding the categorical mix from the UI.

## Suggested next steps when work resumes

In increasing order of effort:

### A. Confirm which hypothesis dominates (cheap, do first)

Add a one-shot diagnostic that, for each of the top N communities, logs the
filename of the prototype plus a random sample of, say, 10 member filenames.
Visual inspection by the user will distinguish:

- *Each community is genuinely 95%+ drums* → hypothesis 2 (library is more
  drum-dominated than perceived); selection is honest, no algorithm change
  needed.
- *Top community is, e.g., 60/40 drums/pads but prototype is a drum* →
  hypothesis 3; selection logic should change.
- *Community boundaries don't track audible categories at all* → hypothesis 1;
  embedding needs work.

This requires passing `library` reference into `findPrototypesFromGraph` so it
can resolve identifiers via `library.find(id).getKey()`. Few lines, gateable
under the same `ar.prototype.diagnostics` flag.

### B. Diversity-aware prototype selection (medium effort, helpful for hypothesis 3)

Replace the current `sort by communitySize then take top N` with a greedy
farthest-point selection over community prototype embeddings:

1. Pick the community whose prototype has highest centrality.
2. Repeatedly pick the community whose prototype embedding is *farthest* (in
   cosine distance) from the already-selected set, weighting by community size
   so we don't pick singletons over substantive clusters.

For hypothesis 1 this won't help — if all prototype embeddings live in the
same cone, even maximum-distance picks are still cone-adjacent. For hypothesis
2 and 3 it produces visibly more varied prototype sets.

Implementation lives entirely in `PrototypeDiscovery.findPrototypesFromGraph`;
no API or persistence-layer changes.

### C. Better community centroid (medium, helpful for hypothesis 3)

Try alternatives to PageRank centrality for prototype selection:

- *Geometric medoid* of the community's embeddings — the member whose mean
  cosine distance to other members is smallest. Likely picks more
  representative samples than PageRank in mixed communities.
- *Density peak* — the member with highest local density (count of members
  within some similarity threshold). More robust to "generic-but-not-typical"
  bias.

Both are local computations on the existing community membership; no graph
algorithm changes.

### D. Improve the embedding (large effort, only avenue for hypothesis 1)

If A confirms embedding collapse, options are:

- Train (or fine-tune) the autoencoder with a contrastive objective so the
  embedding cone spreads out. Requires training infrastructure.
- Concatenate hand-crafted features (spectral centroid, MFCCs, percussiveness,
  pitch-stability) with the autoencoder embedding before normalization. Cheap
  and likely to add categorical signal that the autoencoder is missing.
- Replace cosine with a metric that's less sensitive to magnitude (e.g.,
  correlation distance after centering), or normalize per-feature instead of
  per-vector before the dot product.

This is the only direction that can recover *categorical separability* if the
embedding fundamentally lacks it.

### E. Increase K and lower Louvain resolution (small experiment)

The current configuration uses K=20 (HNSW neighbors per node) and Louvain
resolution 1.0. With mean cosine 0.93, a node's "true" similar set may be
much larger than 20, leading to over-clustering. Worth a quick A/B with K=50,
resolution 0.5–0.8 to see whether the community structure changes.
Two-line change in `PrototypeDiscovery.DEFAULT_K_NEIGHBORS` and
`findPrototypesFromGraph`'s `louvain(graph, 1.0)` call.

## Re-enabling the diagnostic logging

The `HNSW diagnostics:` block and `Community size distribution:` block are
gated by a static `final boolean DIAGNOSTICS_ENABLED` in `PrototypeDiscovery`,
which reads `SystemUtils.isEnabled("AR_PROTOTYPE_DIAGNOSTICS")`. To turn them
back on, set the property/env var to `enabled` (the `SystemUtils` convention
intentionally rejects `true`/`false`):

```
AR_PROTOTYPE_DIAGNOSTICS=enabled java ...
# or
java -DAR_PROTOTYPE_DIAGNOSTICS=enabled ...
```

When disabled (default), the per-sample counter increments are skipped
entirely (not just the log emission) so there is no overhead in normal runs.

## What was *not* changed

The May 2026 fixes were scoped narrowly to making Prototypes work correctly
and to not introducing any startup-time work. Notably:

- No code paths were added in `AudioLibrary.refresh()`, `AudioLibrary`'s
  constructor, `AudioLibraryUI.setRoot`, or any other startup path. The
  backfill runs only inside `discoverPrototypesInBackground`, only when the
  user clicks the Prototypes tab.
- `ProtobufDiskStore.put(id, record)` was *not* changed despite being the
  origin of the original HNSW eviction. That change has wider-reaching
  semantics (every record write across the whole disk store), and the Round-1
  backfill plus the Round-2 `HnswIndex.insert` fix together prevent the
  symptom in the discovery path. Reconsider tightening this when revisiting
  prototype discovery, but only after the centroid / embedding work above —
  it's not on the critical path for prototype quality.
- The Louvain implementation in `CommunityFeatures.louvain` is the
  single-pass variant (no graph aggregation phase). If hypotheses 2 or 3 turn
  out to need finer control over community granularity, completing the
  multi-level version is one option, but selection-side changes (B, C) are
  simpler and likely sufficient.
