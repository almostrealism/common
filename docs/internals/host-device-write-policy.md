# Host→Device Write Policy

Values that end up in device memory must be **computed on the device**. Java builds the
computation graph; it does not compute buffer contents and upload them. This page states
the contract for the few narrow paths by which data may still cross from the JVM heap into
device memory, how the contract is enforced, and the failure modes that past migrations
fell into. Read it before touching any `setMem`, `fill`, `pack`, `PackedCollection.of` or
`read(ByteBuffer)` call site.

## The contract

Only these paths move data from the host into device memory:

| Path | Allowed arguments |
|------|-------------------|
| `pack(values...)` / `PackedCollection.of(values...)` | Literals, or individual runtime scalars (fewer than 16 arguments when any is non-literal). Never an array, list, lambda or device read-back. |
| `fill(values...)` | Same rule as `pack`. |
| `setMem(index, value)` | A single literal at an index. Declared on `MemoryDataAdapter`, not `MemoryData`. |
| `setMem(values...)` | Literal varargs only (whole-content form, reachable only from the write surface itself). |
| `read(ByteBuffer)` / `read(InputStream)` / `read(byte[])` | The **ingest surface**, for data entering the process from outside it (deserialization, file, network, external runtimes). Only files on the ingest allowlist may call it. |
| `setFrom(...)`, `cp(src).into(dest).evaluate()` | Device→device copies; no host values involved. |

Everything else is a violation. In particular a host `double[]`/`float[]` and any value
Java code computed (a variable, cast, arithmetic expression, `Math.cos` table, …) may not
be written to device memory, no matter how it is packaged. The question is never where the
data came from, but whether Java computed it.

The multi-value indexed form `setMem(index, v1, v2, ...)` has been removed. Removing the
overload does not make the old shape a compile error: `setMem(i, 1.0, 2.0)` now binds to
the whole-content varargs form and writes `i` as data at offset 0, so the detector rejects
an index expression followed by more than one value.

### `fill` semantics

`PackedCollection.fill(double...)` replaces the **entire** contents of the collection,
cycling its varargs across every element. It is the right form for any write that
replaces the whole contents, however many values it carries, and never a substitute for a
partial or sparse write, where the cycling would overwrite elements meant to stay
untouched. Single-argument `setMem(v)` writes element 0 only; on a size-1 buffer it and
`fill(v)` coincide.

The per-element host-compute overloads `fill(DoubleSupplier)` and
`fill(Function<int[], Double>)` still exist on `PackedCollection` but are scheduled for
removal; do not add new callers.

## What a correct site looks like

A site is done correctly only when it is one of:

- **One on-device kernel computing the whole buffer.** An index-derived buffer becomes one
  producer over an index ramp: `signal[i] = sin(2πf·i/N)` →
  `sin(integers(0, N).multiply(2 * Math.PI * f / N)).into(signal.each()).evaluate()`;
  random data → `rand(shape(N)).into(...)`.
- **A tiny constant via `fill`** — a scalar reset, a single-slot state write.
- **A device→device copy** (`setFrom`, `cp(src).into(dest)`).
- **A reference to an existing device producer** — e.g. filter coefficients from
  `lowPassCoefficients(c(cutoff), sampleRate, order)`, never `c(hostComputedArray)`.

Before migrating a site, classify it:

1. **Storage-class internals** (`PackedCollection` subclasses such as `Pair`, `Vector`,
   and the memory providers) are the sanctioned write surface. Migrate their *callers*.
2. **A whole operation** → one `CollectionProducer` over the whole buffer.
3. **A short-circuit** ("don't compile a kernel" path) → `setFrom`, or delete the
   short-circuit. Never compile a kernel inside it.
4. **Genuine external ingest** → the ingest surface, from an allowlisted file.
5. **A tiny constant** → `fill(value)`.
6. **Anything else** → the value comes from the graph: `integers(a, b)` ramps, on-device
   math, `rand(...)`, existing producers, or device→device copies.

### Index-derived fills: read the shape

`pos[0]` in a `fill(pos -> ...)` lambda is the *outermost axis*, not the flat index. A fill
of `pos -> 1.0 + pos[0]` on `shape(1, size)` is uniformly `1.0`, and
`pos -> (pos[0] + 1) * 0.1` on `shape(4, 3)` is a per-row constant. Only genuinely 1-D
receivers convert directly to a flat `integers(0, N)` ramp. A value that varies along one
axis and repeats along another is `repeat(...)` over the ramp for that axis; a value derived
from a position within a shape is `index(...)` (`IndexOfPositionComputation`).

### Do not reach a multi-element write through `range`

Replacing `x.setMem(i, v)` with `x.range(shape(1), i).fill(v)` silences the detector and
improves nothing. At a multi-element site the write is usually the last statement of a
computation whose body is the surrounding host loop. The canonical example is
`FrequencyToAudioConverter.normalizeAudio`, which used to compute a max-abs reduction and
an elementwise scale in Java loops, costing `2 * length` reads and `length` writes across
JNI. It is one producer expression:
`cp(audio).multiply(c(0.9).divide(max(abs(cp(audio)))))`. Ask what the loop around the
write is computing, not how to spell the write.

## Enforcement

- **`SetMemLiteralsDetector`** (engine/utils) — no exemptions, applies to main and test
  sources, cannot be suppressed with comments. Rules:
  - `SETMEM_NON_LITERAL_ARGUMENT`, `PACKED_COLLECTION_OF_NON_LITERAL`;
  - `FILL_PACK_BEYOND_SCALAR_ALLOWANCE`;
  - `INGEST_OUTSIDE_SANCTIONED_SURFACE` — a `read(ByteBuffer)`/`read(InputStream)` call
    from a file not on the ingest allowlist;
  - `COMPUTED_VALUE_STAGED_FOR_INGEST` — a `ByteBuffer.allocate`/`wrap` buffer that
    receives `put*` calls and is then handed to `read`. This one applies inside
    allowlisted files too, so the evasion cannot be relocated into a deserializer.
  The scan runs on a comment- and string-masked copy of each file with balanced-paren
  argument extraction, so splitting a call across lines does not evade it.
- **`ingest-allowlist.txt`** (`engine/utils/src/main/resources/org/almostrealism/util/`) —
  the closed set of files allowed to call the ingest surface. Membership is by file, not
  by inspecting buffer contents, because a rule over contents can always be restructured
  around. The same file is read by the `.claude/hooks/block-computed-ingest.py` hook, so
  the edit-time block and the CI check cannot drift.
- **`PackedCollectionDetector`** — the older detector for element-wise host manipulation
  (`setMem` in a loop, `setMem` + `toDouble` round trips).
- **`setmem-violation-baseline.tsv`** — a *temporary* burn-down ledger of sites not yet
  migrated, plus the detector's `KNOWN_EXCLUSIONS`. It is not a list of blessed
  exceptions; it only shrinks, and when it reaches zero the ledger machinery is deleted.
  Running `SetMemLiteralsDetector <root>` prints the live/stale exemption summary.

## Failure modes seen during the migration

Every one of these happened, most more than once. They fall into three families.

**Laundering** — moving host values to the device in a shape the checker accepts:

- *Per-value constant kernels.* `x.setMem(i, v)` rewritten as `c(v).into(x).evaluate()`
  or `a(cp(x), c(v)).get().run()`. `c(value)` bakes the host double into the kernel body,
  so each distinct value is a distinct compiled kernel; in a loop this exhausts the JNI
  operator pool (`OperatorPoolExhausted`). A single `c(smallLiteral)` used as an invariant
  operand is fine; `c(hostComputedArray)` is laundering.
- *Host-array laundering.* Values computed element-wise into a `double[]` and uploaded in
  one call, via a bulk write, `c(array)` or the ingest surface.
- *Per-element kernels for whole operations.* An operation rewritten as a Java loop of
  one-element assignment kernels instead of one producer over the buffer.
- *Kernels inside short-circuits,* and *host-mode fallbacks* added to relieve pool
  pressure that laundering created. Back out the bad migration instead.

**Hollowing** — making checks pass by destroying what they verify:

- *Token substitution without reading intent.* A test whose scattered writes exist to make
  the allocator commit memory was "migrated" to a single-element touch: it passed and
  stopped testing anything. For every migrated test ask: *if this compiles and passes, is
  the test still verifying what it was written to verify?*
- *Permission-slip phrases* ("acceptable constant vector", "reference data") used to skip
  the analysis, and *curated-subset verification* (running a hand-picked subset instead of
  every modified class in CI's own configuration).
- *Crutches over defects.* When identical computations recompiled, a test helper was added
  instead of fixing the cause (missing value-bearing signatures disabling instruction
  reuse).

**Concealing** — infrastructure that silently repairs misbehaviour:

- *Silent repair inside infrastructure* — a rebind that quietly recompiled, an early
  return that skipped verification, a lazily created manager. Each hid a real defect. When
  infrastructure "helps" by fixing a missing precondition, throw instead.
- *Green checks as the deliverable,* and *blind checkers*: a detector prefilter that
  skipped files without `setMem` hid hundreds of `fill`/`pack` violations. A metric is only
  worth reporting if the instrument covers the whole tree.
- *Environment divergence:* test reference data moved outside the repository made local
  runs differ from CI. Host-side *assertion* references belong in the test itself, or in
  gitignored directories inside the repo.

## See also

- [packed-collection-examples.md](packed-collection-examples.md) — correct `PackedCollection` idioms
- [producer-evaluable-pattern.md](producer-evaluable-pattern.md)
- `SetMemLiteralsDetector` and `PackedCollectionDetector` javadoc (engine/utils)
