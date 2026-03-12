# Protobuf Disk Store — Design Plan

## Overview

A general-purpose key-value store that persists **native protobuf messages**
on disk, with automatic memory management via `FrequencyCache`. Designed for
datasets up to 10 GB with at most 500 MB in memory at any time.

The store is generic over `T extends com.google.protobuf.Message`. Users
provide their own protobuf `Parser<T>` at construction time. Records are
written directly in protobuf wire format — **no wrapper messages, no double
serialization**.

## Module Placement

**Module**: `engine/ml` (ar-ml) — already has protobuf-maven-plugin, protobuf-java
dependency, and the `org.almostrealism.persistence` package with `CollectionEncoder`.

**Package**: `org.almostrealism.persist` (new package, separate from the existing
`org.almostrealism.persistence` which handles asset management).

**Proto file**: `engine/ml/src/main/proto/diskstore.proto` — contains only the
index messages (no record wrapper).

---

## Data Layout on Disk

```
<store-root>/
├── index.bin          # Protobuf: DiskStoreIndex — maps record ID → (batch, offset)
├── batch_0000.bin     # Sequential length-delimited records of type T
├── batch_0001.bin
├── ...
└── batch_NNNN.bin
```

### Batch File Structure

Each batch file contains **sequential length-delimited protobuf records** of
the user's own message type `T`. Records are written using `writeDelimitedTo`
(standard protobuf streaming format: varint length prefix + serialized bytes).

There is **no wrapper message**. The batch file is simply a concatenation of
length-delimited `T` records.

**Batch sizing**: Default target ~4 MB per batch file. This balances:
- Seek efficiency (fewer files to open for sequential scans)
- Memory footprint (loading one batch doesn't blow the memory budget)
- Write amplification (deletes remove from index only; compaction not yet implemented)

### Index File

The `DiskStoreIndex` protobuf message maps each record ID to its
`RecordLocation` (batch_id + byte_offset within the batch file). The index
is loaded fully into memory on startup.

For random access: seek to byte_offset in the batch file, call
`parser.parseDelimitedFrom(stream)` to read exactly one record.

For sequential scan: open batch file, repeatedly call
`parser.parseDelimitedFrom(stream)` until EOF (returns null).

---

## Memory Management Strategy

### FrequencyCache Configuration

- **Cache key**: batch_id (Integer)
- **Cache value**: `ParsedBatch<T>` — a map of record ID to deserialized `T`
- **Capacity**: `maxMemoryBytes / targetBatchSize` — e.g., 500 MB / 4 MB = 125 batches
- **Frequency bias**: 0.3 (favor recency slightly over frequency for scan workloads)
- **Eviction listener**: no-op (batches are read-only snapshots from disk)

### Why cache batches, not individual records?

1. Disk I/O granularity — reading one record still benefits from having nearby records cached
2. Locality — records stored together are often accessed together
3. Simpler eviction — one cache entry = one batch file = one I/O operation

### Record-level access

`get(id)` → look up (batch_id, byte_offset) in index → `cache.computeIfAbsent(batchId, loadBatch)` → find record in batch by ID.

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

### Key Classes

| Class | Responsibility |
|-------|---------------|
| `DiskStore<T>` | Public interface |
| `ProtobufDiskStore<T extends Message>` | Main implementation with FrequencyCache, uses `Parser<T>` |

The `Parser<T>` is the standard protobuf-generated parser — e.g.,
`MyRecord.parser()`. The store uses it to deserialize records from disk
without knowing anything about the schema.

### Construction

```java
public ProtobufDiskStore(File rootDir, Parser<T> parser, long maxMemoryBytes) { ... }
```

---

## Serialization — No Double Serialization

The prior design wrapped records in a `DiskStoreRecord { id, payload bytes }`
protobuf envelope. This caused double serialization when the user's record was
already a protobuf message.

The corrected design:
- **Write**: `record.writeDelimitedTo(outputStream)` — raw protobuf wire format
- **Read**: `parser.parseDelimitedFrom(inputStream)` — directly deserializes `T`
- **No wrapper message** — batch files contain only the user's message type
- **ID mapping** — the index file maps record IDs to (batch_id, byte_offset)

---

## Strategy for Pairwise Scan

The `pairwiseScan(BiConsumer<T, T> pairVisitor)` operation must visit every
unordered pair `(A, B)` exactly once, where A ≠ B.

### Block-based algorithm

```
batches = listAllBatchIds()  // sorted
for i = 0 to batches.size - 1:
    batchA = loadBatch(batches[i])
    recordsA = liveRecords(batchA)
    // Intra-batch pairs
    for a = 0 to recordsA.size - 2:
        for b = a+1 to recordsA.size - 1:
            pairVisitor.accept(recordsA[a], recordsA[b])
    // Inter-batch pairs
    for j = i+1 to batches.size - 1:
        batchB = loadBatch(batches[j])
        recordsB = liveRecords(batchB)
        for each record rA in recordsA:
            for each record rB in recordsB:
                pairVisitor.accept(rA, rB)
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

Tests use a real protobuf message type (`TestRecord` defined in
`engine/ml/src/test/proto/test_record.proto`).

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
