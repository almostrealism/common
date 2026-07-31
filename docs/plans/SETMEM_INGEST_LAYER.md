# The System-Boundary Ingest Layer (Phase 17)

This is the design and migration plan for item 7 of the target ingest contract in
[SETMEM_POLICY_ENFORCEMENT.md](SETMEM_POLICY_ENFORCEMENT.md): the named surface
through which data entering the JVM from outside the system reaches device memory.
Once these migrations land, the bulk `double[]`-accepting `setMem` forms retreat
behind this layer and no public method accepts a computed host array.

## Principle: external formats are their own device

For read-only external data, the preferred design is a dedicated
`MemoryProvider` per format, so the external medium acts as its own kind of
device. This is not new machinery — the framework already contains both halves:

- **Lazy migration at first use already works.** `HardwareOperator.reassignMemory`
  checks every kernel argument's provider at dispatch and reallocates the root
  reservation onto the operator's provider when it is foreign
  (`HardwareOperator.java`), while `MemoryDataAdapter.reallocate`/`reassign`
  keep per-provider `memVersions` so buffers hop between providers without
  redundant copies. A collection backed by a format provider migrates to the
  device exactly when a kernel first needs it — and never migrates if only the
  host ever reads it.
- **The precedent exists.** `LocalExternalMemoryProvider` ("DISK",
  `base/hardware/external`) is a file-backed FP64 provider with lazy reads and
  `reassign()` delegation. New format providers are variations on it.
- **Shared memory already ships this way.** `DataContext.sharedMemory(...)`
  produces `PackedCollection`s over shared-memory segments
  (`SharedMemoryAudioLine` consumes them); that case is complete and simply
  gets documented as part of this contract.

## The standard ingest mode: ByteBuffer (agreed 2026-07-31)

For sources that do not earn a dedicated format provider, **`ByteBuffer` is
the one standard mode of ingest**. There is no eager array-upload call: host
`double[]`/`float[]` staging arrays are exactly what encourages host-side
manipulation of data, so they do not appear anywhere in the ingest path.
The standard sequence is:

1. Create a `MemoryData` instance backed by a `ByteBuffer`.
2. Load the data into that `ByteBuffer` — or into a `FloatBuffer`/
   `DoubleBuffer` view of it, when that is more convenient for the decoder.
3. Return the `MemoryData` to the caller: as a `PackedCollection` directly,
   or as the delegate of one.
4. Let the system move the data off the `ByteBuffer` to a device whenever the
   device it needs to move to is discovered (a kernel call, etc.) — the same
   lazy migration every source provider relies on.

The buffer-backed `MemoryData` is the staging area; the decoder writes through
the buffer views, never through `setMem`, and after step 3 the data is
read-only until it migrates. Streaming decoders (WAV) and one-shot payloads
(database rows) both fit this shape, and it is also the surface the bulk
`double[]`-accepting `setMem` overloads retreat behind.

## Case decisions

| Source | Decision | Rationale |
| --- | --- | --- |
| Protobuf weights (`StateDictionary`/`CollectionEncoder`) | **Format provider** (first migration) | Read-only, per-tensor root allocations, hundreds of MB, loaded eagerly today whether or not used. Lazy per-tensor migration cuts startup and peak memory; `memVersions` caches across contexts. |
| ONNX tensors (`OnnxFeatures.pack(OnnxTensor)`) | **ByteBuffer staging** (done) | Tensor values are written through a view of a native buffer allocation (`Hardware.getNativeBufferMemoryProvider()`), wrapped with `Bytes.of`, and migrate at first kernel use. The staging copy decouples the collection from the tensor's lifetime (session results auto-close). |
| Shared memory (`SharedMemoryAudioLine`) | **Format provider — already done** | `DataContext.sharedMemory(...)` is the shipped design. |
| Resource / reference files | **Format provider — already done** | `LocalExternalMemoryProvider` ("DISK") covers file-backed vectors; roadmap step 4b (reference inputs as resources) rides on it. |
| WAV (`WavFile`/`WaveData`) | **ByteBuffer staging** | Streaming reader/writer with PCM scaling and de-interleave in the read path; decoded audio is consumed immediately (resample/FFT), so laziness buys little; the write direction is host-directed I/O. A read-only mmap WAV provider remains a later option for `AudioLibrary` scanning. |
| Database rows (graphpersist `GraphPersist.read`) | **ByteBuffer staging** | JDBC delivers a complete `byte[]`; there is nothing incremental to defer — "lazy" would mean deferring the query, a repository concern. `PackedCollection.read(byte[])` is already the choke point; it becomes a formal part of this surface. |

## Provider contract for read-only sources

- `getMem` decodes/reads from the external medium (lazily, per request).
- `setMem` **into** source-backed memory throws: these are sources; after
  migration, writes land on the device copy, and nothing writes back to the
  external medium. Migration is one-way by design. (The rejecting `setMem`
  and `allocate` forms are the `MemoryProvider` interface defaults, so a
  source provider implements only `getName`, `getNumberSize`, `getMem`,
  `deallocate`, and its own wrapping factory.)
- Source collections are root allocations (one per tensor/segment/file), since
  `reassignMemory` migrates whole roots and `reallocate` refuses non-zero
  offsets. Views delegate off the root as usual.
- `destroy()` releases the underlying handles (parsed file index, mmap,
  `OnnxTensor`s); owners (`StateDictionary`, session wrappers) already have
  lifecycle methods to hang this on.

## Known integration risks (verify during the first migration)

1. `Hardware.getDataContext(memory)` selects contexts by provider membership
   (`Hardware.java`); format providers belong to no `DataContext`. Verify the
   paths that consult it tolerate a foreign provider.
2. Device providers' `setMem(mem, offset, Memory source, ...)` must handle a
   foreign *source* via host readback (CL demonstrably does; confirm Metal and
   JNI) — this is the copy that `reallocate` performs at migration.
3. Aggregation (`MemoryReplacementManager`) copies small arguments through
   `ComputeContext.copy`, which reads through the source provider's `getMem` —
   expected to work unchanged; confirm with a small foreign-backed argument.
4. FP32 sources (ONNX, protobuf floats) pay fp32→fp64 conversion at read —
   the same cost as today, relocated into the provider.

## Migration order

1. **Protobuf weights provider** — done: `CollectionDataMemoryProvider` +
   `CollectionEncoder.decode(data, materialize)` (deferred by default), loaded by `StateDictionary` behind
   `enableDeferredWeights`.
2. **ONNX tensors** — done, via the standard ByteBuffer staging sequence
   rather than a dedicated provider.
3. **`WavFile`/`WaveData`** — migrate the decoded-frames → collection hop to
   the standard ByteBuffer staging sequence, and formalize
   `PackedCollection.read(byte[])` (graphpersist) as part of this surface;
   then move the bulk `double[]`-accepting `setMem` overloads behind it
   (or `protected` on `MemoryDataAdapter`) per the census conclusion.
4. **Retire the corresponding `KNOWN_EXCLUSIONS`** entries as each category
   gains its home, and regenerate the baseline.

Each step is verified by the tests that exercise the migrated path (ML weight
loading and inference tests for 1; ONNX inference tests for 2; audio I/O and
graphpersist tests for 3) plus the build validator, and each shrinks — never
grows — the exemption ledger.
