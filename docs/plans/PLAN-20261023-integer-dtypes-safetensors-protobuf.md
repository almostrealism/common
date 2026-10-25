# Integer dtypes in SafetensorsReference and an integer payload in collections.proto

- **Tracker task:** `3e0043d3-c087-4e9b-b687-62931627b70e`, "Integer dtypes in
  SafetensorsReference and an integer payload in collections.proto"
- **Release:** the release currently under development (the one whose name matches the version
  in the root `pom.xml`).
- **Module:** `engine/ml` (package `org.almostrealism.persist.assets`, plus `StateDictionary`)

## Motivation

The connectome model organism (`CONNECTOME_MODEL_ORGANISM.md`, Phase 1 task 3) brings the MaleCNS
edge list in as safetensors with integer arrays, written by a one-time conversion script, and keeps
it in the project's own protobuf form (`CollectionLibraryData`). Neither reader can do that today:

- `SafetensorsReference.locate` rejects every `dtype` except BF16, F16, F32 and F64, so an `I32`
  or `I64` tensor fails with "only BF16, F16, F32 and F64 tensors can be read".
- `collections.proto` `CollectionData` carries only `repeated double data` and
  `repeated float data_32`. An index array saved through `StateDictionary.save` (FP32 by default)
  is rounded above 2^24, so index data cannot be kept exactly.

More generally, published checkpoints carry integer buffers (position ids, quantized indices) and
cannot be loaded while either reader refuses them.

## Scope

In scope:

1. `SafetensorsReference` reads `I32` and `I64` tensors (and, at no extra cost, `I8`, `U8`, `I16`
   — see open question 3), with exact values.
2. `collections.proto` `CollectionData` gains integer payload fields, readable by every existing
   read path: the parsed path (`ParsedCollectionDataMemory`), the mapped path
   (`CollectionDataReference.of` / `MappedCollectionDataMemory`), and
   `CollectionEncoder.decode(data, destination, offset)`.
3. `CollectionEncoder` can write a collection as an integer payload, and `StateDictionary.save`
   keeps an entry that was loaded from an integer source as an integer payload.
4. Confirm the Python stub still regenerates. `engine/ml/scripts/collections_pb2.py` is a generated
   artifact, not a committed file — it is gitignored (`engine/ml/.gitignore`,
   `engine/ml/scripts/.gitignore`) and produced on demand by `generate_protobuf_python.sh` from
   `collections.proto`. The committed change is the proto alone; the only check here is that the
   generator still succeeds against the updated proto and exposes the new fields.
5. Documentation of the exactness limit in `docs/internals/state-dictionary.md`.
6. Round-trip tests.

Out of scope (named so a reviewer can see they were considered):

- **Integer-exact device storage.** Where values must stay exact past 2^24 on an FP32 backend is
  the subject of the open task "Integer-exact index storage for gather and sparse addressing on FP32
  backends" (`2c0793a7`), which has not landed. This task delivers values exactly to the
  framework's host-side memory and documents the FP32 device limit, as the task description asks.
- The Feather → safetensors conversion script and the connectome loader (later Phase 1 tasks).
- Teaching `engine/ml/scripts/safetensors_extractor.py` to write integer arrays into the new fields
  (follow-up, see open question 4).
- The 2 GB-per-file `FileMapping` limit. The MaleCNS edge list (tens of millions of `int32` entries
  per array) fits under it.

## Approach

### How values reach the framework today

Both formats are served by `CollectionDataMemoryProvider` ("PROTOBUF"), a read-only format provider
of the kind `SETMEM_INGEST_LAYER.md` names as the preferred ingest design: a value is decoded by
`CollectionDataReference.valueAt(ByteBuffer, index)` (overridden by `SafetensorsReference`, whose
`Encoding` enum decodes each dtype) and served as FP64 through `getMem`. The data migrates to a
device only when a kernel first uses it (`HardwareOperator.reassignMemory`). Integer support plugs
into exactly this path: a new decode, no new provider, no staging array and no element-wise copy
loop in Java. This is the sanctioned host-ingest path.

### Exactness

- The provider serves FP64. Every `int32` is exact in a double, and an `int64` is exact up to
  2^53. An `I64` value outside ±2^53 cannot be represented exactly, so decoding it throws
  (`ArithmeticException` naming the value and position) rather than silently rounding. The check is
  one comparison per decoded value, on the path that already decodes each value.
- Once a collection migrates to an **FP32 device** (Metal), values are exact only up to 2^24
  (16,777,216), like every other value in an FP32 collection. This limit is documented in the
  Javadoc of the integer encodings and in `docs/internals/state-dictionary.md`, together with the
  pointer that integer-exact device storage is a separate concern. Tests that go through a device
  (materialization) stay below 2^24; host reads test the full range.
- When task `2c0793a7` lands, integer payloads are where an integer-exact index collection would be
  produced from; the encoding knows it is integral, so the hook is local to
  `CollectionEncoder.decode(reference, file, materialize)`. That wiring belongs to whichever of the
  two tasks lands second.

### One encoding type for both formats

`SafetensorsReference.Encoding` already describes "how values are stored and how one is decoded".
`CollectionDataReference` repeats the same idea with a `Precision` switch in its own `valueAt`. With
integers added to both, keeping two descriptions would duplicate the integer decoding. The encoding
is therefore promoted to a top-level type in `org.almostrealism.persist.assets` (working name
`ValueEncoding`) with constants `BF16, F16, F32, F64, I32, I64` (plus the narrow integers, if
accepted). Each constant carries:

- its byte width (an `int`, replacing the `Precision` "width" that `BF16` borrows from `FP16`;
  `Precision` has no integer constants and must not gain them for this),
- `decode(ByteBuffer, int at)`,
- its safetensors `dtype` name (the constant name),
- its `CollectionData` field number, or none (BF16 and F16 have no protobuf payload).

`CollectionDataReference` holds a `ValueEncoding` instead of a `Precision`, and its `valueAt`
delegates to it, so `SafetensorsReference` no longer needs to override `valueAt`.
`getPrecision()` callers are updated to use the encoding (`getValueLength` uses the width). The
nested `SafetensorsReference.Encoding` is removed and its callers (the test) updated — `engine/ml`
is the only module that refers to it.

### `SafetensorsReference`

- `locateTensor` resolves the `dtype` through `ValueEncoding` and its error message lists the
  readable types from the enum rather than a hard-coded list.
- Byte-range validation (`expected = size * width`) is unchanged and covers the new widths.

### `collections.proto`

```proto
message CollectionData {
  TraversalPolicyData traversal_policy = 1;
  repeated double data = 2;
  repeated float data_32 = 3;
  repeated sfixed32 data_i32 = 4;
  repeated sfixed64 data_i64 = 5;
}
```

Fixed-width (`sfixed32`/`sfixed64`), not varint (`int32`/`int64`/`sint64`): the mapped reader
addresses a value as `offset + index * width` inside a packed field, which only works when every
value has the same width. A varint payload would force a full parse of the field, defeating the
deferred reader. Protobuf writes fixed-width scalars little-endian, which matches
`CollectionDataMemoryProvider.VALUE_ORDER`. Both widths are offered so an `int32` index array (the
edge list) costs half the bytes of `int64`. Adding fields is wire-compatible: old files read
unchanged, and an old reader sees an integer collection as having no values. The fields are not
wrapped in a `oneof`, matching the existing `data`/`data_32` and keeping the generated Java API of
those fields unchanged; a collection with more than one payload is rejected on read.

### Reading `CollectionData`

- `CollectionDataReference`: field-number constants `DATA_I32_FIELD = 4`, `DATA_I64_FIELD = 5`.
  `of(EncodedMessage)` selects the payload by which field is present (exactly one of the four,
  otherwise `IllegalArgumentException`), and the existing "whole number of values" check uses the
  encoding's width.
- `ParsedCollectionDataMemory`: `length` and `valueAt` cover `getDataI32Count`/`getDataI64Count`,
  selecting the payload once in the constructor instead of re-testing per value.
- `CollectionEncoder.decode(data, destination, offset)` today stages through a `ByteBuffer` with a
  per-value `putDouble` loop per payload. Rather than adding two more loops, it builds the same
  deferred collection the other `decode` forms use (`ParsedCollectionDataMemory` wrapped with
  `Bytes.of`) and copies it into `destination.range(shape, offset)` with `setFrom`, the same copy
  `decode(..., materialize = true)` already does. This removes the existing loops as well.

### Writing `CollectionData`

- `CollectionEncoder.encode(PackedCollection, ValueEncoding)` is the general form;
  `encode(c, Precision)` remains and maps FP32/FP64 onto it. For `I32`/`I64` each value is checked
  to be integral and in range for the target width; a non-integral or out-of-range value throws
  `IllegalArgumentException` naming the index, rather than being truncated. Encoding is the egress
  side (writing a file the program asked for), which already streams values with `doubleStream()`;
  the integer form uses the same stream.
- `StateDictionary` records the source encoding of each entry it loads (from the
  `CollectionDataReference`/`SafetensorsReference` it located, or the payload it parsed), and
  `save`/`encode` write an entry loaded from an integer payload as that integer payload regardless
  of the `precision` argument, which continues to govern floating-point entries. This makes
  "safetensors with integer arrays → `StateDictionary` → protobuf" (the connectome conversion) keep
  its integers without the caller naming them. A caller that builds an index collection itself
  passes the encoding explicitly through an overload of `StateDictionary.encode` taking a
  per-key encoding map (see open question 2).

### Python stub

`engine/ml/scripts/collections_pb2.py` is generated by `generate_protobuf_python.sh` and is not
checked in (it is gitignored). Nothing has to be committed for it: after the proto changes, running
the generator reproduces the stub with the new fields. The only action is to confirm the generator
still succeeds against the updated `collections.proto`.

### Documentation

`docs/internals/state-dictionary.md`, "Safetensors checkpoints" and the protobuf section: list the
integer dtypes and payloads, the ±2^53 host limit for `I64`, and the 2^24 limit once data migrates to
an FP32 device.

## Success criteria

- `SafetensorsReferenceTest` gains tests (existing tests unchanged apart from the rename of the
  encoding type they reference):
  - an `I32` tensor holding negative values, zero, `Integer.MIN_VALUE` and `Integer.MAX_VALUE`
    reads back exactly through the mapped (deferred) collection;
  - an `I64` tensor holding values past 2^31 and up to ±2^53 reads back exactly;
  - an `I64` value past 2^53 is rejected when read;
  - materialized `I32`/`I64` tensors with values below 2^24 equal the mapped values on any backend;
  - an integer tensor whose byte range does not match its shape is rejected (width check).
- `CollectionDataReferenceTest` / `CollectionDataMemoryProviderTest` gain tests:
  - an integer `CollectionData` (both `data_i32` and `data_i64`) is encoded with
    `CollectionEncoder.encode(c, I32/I64)`, written, and read back exactly through the parsed path,
    the mapped path (`CollectionDataReference.of`) and `decode(data, destination, offset)`;
  - encoding a non-integral or out-of-range value as an integer payload throws;
  - a `CollectionData` with two payloads is rejected.
- A `StateDictionary` round trip: a dictionary loaded from a safetensors file with an `I32` tensor is
  saved and reloaded, and the entry is still an `sfixed32` payload with the same values.
- No new third-party dependency and no `pom.xml` change (protobuf and gson are already used here).
- No element-wise Java copy loop on the ingest path; `CodePolicyViolationDetector` and the build
  validator pass.

## Dependencies

- None blocking. The tracker lists no `blocked_by`.
- Related, not blocking: "Integer-exact index storage for gather and sparse addressing on FP32
  backends" (`2c0793a7`, open). Until it lands, the 2^24 FP32 limit is documented and device-side
  tests stay below it.

## Open questions

1. **Promoting the encoding type.** *Resolved in review (PR #726).* The first implementation added
   a standalone `ValueEncoding` enum. The reviewer rejected it as a duplicate of
   `io.almostrealism.code.Precision` and asked for one consistent representation of number systems.
   The encoding type was therefore removed in favour of a new `NumberSystem` enum in
   `io.almostrealism.code` that enumerates every storable/computable format; `Precision` became an
   interface it implements (the computational half), and a new `BinaryLayout` interface describes the
   storage half. The only part that stays in the ML module is the protobuf field binding,
   `CollectionDataPayload`, keyed off `NumberSystem`.
2. **How a caller asks for an integer payload when saving.** Proposed: entries keep the encoding
   they were loaded with, plus an explicit per-key map for collections built in memory. An
   alternative is a single "save integral collections as integers" flag, which would have to scan
   every value of every entry to decide. Which does the reviewer want?
3. **Narrow integers.** `I8`, `U8`, `I16` (and `U16`, `U32`, `BOOL`) cost one enum constant each in
   safetensors and appear in quantized checkpoints. The task asks only for `I32` and `I64`; the plan
   adds only those unless the reviewer asks for more. `U64` is not proposed (values past 2^63 cannot
   be represented at all).
4. **Python writer.** `safetensors_extractor.py` passes integer arrays through unchanged but writes
   every array into the float fields. Teaching it to write `data_i32`/`data_i64` is a small,
   separate change; it is left out of this task and can be filed as a follow-up if wanted.

## Branch status

- `FixedPatternCorrectnessTest#testTwoMinuteContinuousAudio` (`studio/compose`) timed out in CI on
  this branch at a head that predated master's change to the test's `writeAudioAt` helper. That
  helper placed each of ~600 notes through a view of the destination; a view's offset is part of
  the kernel signature, so every note compiled its own kernel, which is slow enough on the OpenCL
  lane to exceed the 120s budget. Master now passes the note position as a runtime argument
  (`subset` plus `CollectionSlotUpdateComputation`), so every placement shares one compiled kernel.
  The branch carries that change through its merge of `origin/master`. None of this branch's
  `engine/ml` changes are on the test's path. After the merge, the method passes locally in about
  13 seconds.

## Estimated complexity

Medium. The changes are confined to six classes in `engine/ml` (`SafetensorsReference`,
`CollectionDataReference`, `ParsedCollectionDataMemory`, `CollectionEncoder`, `StateDictionary`, the
new encoding type), the proto file (its Python stub is generated, not committed), one internals
page, and three test classes.
Nothing needs a specific machine: the tests read on the host and materialize below 2^24, so they pass
on any backend.
