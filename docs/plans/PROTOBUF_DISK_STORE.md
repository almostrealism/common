# Protobuf Disk Store — Design Plan

## Overview

A general-purpose key-value store that persists records on disk as protobuf,
with automatic memory management via `FrequencyCache`. Designed for datasets
up to 10 GB with at most 500 MB in memory at any time.

## Module Placement

**Module**: `engine/ml` (ar-ml) — already has protobuf-maven-plugin, protobuf-java
dependency, and the `org.almostrealism.persistence` package with `CollectionEncoder`.

**Package**: `org.almostrealism.persist` (new package, separate from the existing
`org.almostrealism.persistence` which handles asset management).

**Proto file**: `engine/ml/src/main/proto/diskstore.proto`

---

## Data Layout on Disk

```
<store-root>/
├── index.bin          # Protobuf: DiskStoreIndex — maps record ID → batch + offset
├── batch_0000.bin     # Protobuf: DiskStoreBatch — contains N serialized records
├── batch_0001.bin
├── ...
└── batch_NNNN.bin
```

### Batch File Structure

Each batch file is a single `DiskStoreBatch` protobuf message containing:
- A list of `DiskStoreRecord` entries, each with `id` (string) and `payload` (bytes)
- A `batch_id` for identification

**Batch sizing**: Default target ~4 MB per batch file. This balances:
- Seek efficiency (fewer files to open for sequential scans)
- Memory footprint (loading one batch doesn't blow the memory budget)
- Write amplification (deletes mark records as tombstoned; compaction rewrites batches)

### Index File

The `DiskStoreIndex` message maps each record ID to its `RecordLocation`
(batch_id + offset within the batch's record list). The index is loaded
fully into memory on startup. For 10 GB of data with ~1 KB average records,
that's ~10M entries × ~40 bytes each = ~400 MB of index, which is significant.
To keep index memory low, the index stores only the batch_id per record —
individual record lookup within a batch is a linear scan of the batch's
records list (typically <1000 records per batch, so fast).

Simplified index: `Map<String, Integer>` — record ID → batch_id.

---

## Memory Management Strategy

### FrequencyCache Configuration

- **Cache key**: batch_id (Integer)
- **Cache value**: parsed `DiskStoreBatch` (the full batch of records)
- **Capacity**: `maxMemoryBytes / targetBatchSize` — e.g., 500 MB / 4 MB = 125 batches
- **Frequency bias**: 0.3 (favor recency slightly over frequency for scan workloads)
- **Eviction listener**: no-op (batches are read-only snapshots from disk)

### Why cache batches, not individual records?

1. Disk I/O granularity — reading one record requires reading the batch file anyway
2. Locality — records stored together are often accessed together
3. Simpler eviction — one cache entry = one file = one I/O operation

### Record-level access

`get(id)` → look up batch_id in index → `cache.computeIfAbsent(batchId, loadBatch)` → find record in batch by ID.

---

## API Design

### Interface

```java
public interface DiskStore<T> extends Closeable {
    void put(String id, T record);
    T get(String id);
    void delete(String id);
    void scan(Consumer<T> visitor);
    void pairwiseScan(BiConsumer<T, T> pairVisitor);
    int size();
}
```

### RecordCodec

```java
public interface RecordCodec<T> {
    byte[] encode(T record);
    T decode(byte[] data);
    int estimateSize(T record);
}
```

### Key Classes

| Class | Responsibility |
|-------|---------------|
| `DiskStore<T>` | Public interface |
| `RecordCodec<T>` | Serialization strategy (caller provides) |
| `ProtobufDiskStore<T>` | Main implementation with FrequencyCache |

---

## Strategy for Pairwise Scan

The `pairwiseScan(BiConsumer<T, T> pairVisitor)` operation must visit every
unordered pair `(A, B)` exactly once, where A ≠ B.

### Block-based algorithm

```
batches = listAllBatchIds()  // sorted
for i = 0 to batches.size - 1:
    batchA = loadBatch(batches[i])
    // Intra-batch pairs
    for a = 0 to batchA.records.size - 2:
        for b = a+1 to batchA.records.size - 1:
            pairVisitor.accept(decode(batchA[a]), decode(batchA[b]))
    // Inter-batch pairs
    for j = i+1 to batches.size - 1:
        batchB = loadBatch(batches[j])
        for each record rA in batchA:
            for each record rB in batchB:
                pairVisitor.accept(decode(rA), decode(rB))
```

### Why this works

- Each batch is loaded at most `N` times total (once as batchA, once for each
  earlier batchA iteration) — O(N) loads per batch across the full scan.
- The FrequencyCache holds recently loaded batches, so batchA stays cached
  while iterating through all batchB values.
- No record is decoded more times than necessary — each pair is visited exactly once.
- Memory stays bounded: at most 2 batches are actively needed at once.

---

## Test Plan

All tests in `org.almostrealism.persist.test.ProtobufDiskStoreTest`, extending `TestSuiteBase`.

| Test | What it verifies |
|------|-----------------|
| `putGetRoundTrip` | Insert a record, retrieve it, verify equality |
| `deleteRemovesRecord` | Insert, delete, verify get returns null |
| `persistenceAcrossRestart` | Write records, create fresh store instance, verify all records loadable |
| `memoryCap` | Insert records exceeding 500 MB budget, verify FrequencyCache size stays within capacity |
| `pairwiseScanVisitsAllPairs` | Insert N records, pairwise scan, verify N*(N-1)/2 pairs visited exactly once |
| `pairwiseScanDiskIO` | Insert records across multiple batches, instrument batch loads, verify no batch is loaded excessively |
| `scanVisitsAllRecords` | Insert N records, scan, verify all N visited |
| `multipleRecordsAcrossBatches` | Insert enough records to span multiple batch files, verify all retrievable |
