# setMem Policy Enforcement — Eliminating Host→Device Transfers

## The end state — the target ingest contract (agreed 2026-07-30)

**The goal is that only a handful of narrow, named paths can move data from the JVM
host into device memory.** Every other value that ends up in device memory must be
**computed on the device** by a Producer/kernel. The final contract:

1. `pack(values...)` — literals, or up to 15 individual runtime scalar values
   (same allowance as `fill`; `pack` and `PackedCollection.of` are the same
   operation and share the same rule).
2. `PackedCollection.of(values...)` — same rule as `pack`.
3. `fill(values...)` — literals, or up to 15 individual runtime scalar values.
   The per-element host-compute overloads (`fill(DoubleSupplier)`,
   `fill(Function<int[], Double>)`) are removed.
4. `setMem(index, value)` — a **new single-value** form; the value must be a
   literal.
5. `setMem(index, value...)` — the multi-value indexed form is **removed**.
6. `setMem(values...)` — literal varargs only (unchanged).
7. **The system-boundary ingest API** — a named surface for data entering the JVM
   from outside the system: disk (protobuf weights, WAV, resource files), external
   runtimes (ONNX tensor outputs), shared memory, and database/network
   deserialization. This surface does not exist yet; creating it is the next
   phase of work.

There is no long-term "allowed `setMem`" beyond the above — the baseline
(`setmem-violation-baseline.tsv`) is a *temporary* burn-down ledger of sites
not yet migrated, not a set of blessed exceptions. It only ever shrinks.

Concretely, a site is "done correctly" only when it is one of:

- **A single on-device kernel that computes the whole buffer.** An index-derived buffer
  becomes one Producer over an index ramp, e.g. `signal[i] = sin(2πf·i/N)` →
  `sin(integers(0, N).multiply(2*Math.PI*f/N)).into(signal.each()).evaluate()`; random
  data → `rand(shape(N)).into(...)`; `exp(-i/10)·cos(πi/8)` →
  `exp(integers(0,N).multiply(-0.1)).multiply(cos(integers(0,N).multiply(Math.PI/8)))`.
- **A tiny constant via `fill`.** A scalar reset / single-slot write /
  buffer-touch is `dest.fill(value)` (or `fill` into a sub-range).
- **A device→device copy** (`setFrom`, `cp(src).into(dest)`) — no host values involved.
- **A reference to an existing device producer** — e.g. FIR coefficients come from
  `lowPassCoefficients(c(cutoff), sampleRate, order)`, never `c(hostComputedArray)`.

### Two failure modes that are NOT "done" (both regress the goal)

1. **Reverting to `setMem`.** Restoring a host→device `setMem` because the baseline
   grandfathers it moves *away* from the end state. The count of `setMem` references a
   migration reintroduces must be **zero**.
2. **Per-element constant kernels.** Rewriting `for (i) x.setMem(i, v)` as
   `for (i) a(cp(x.range(1,i)), c(v)).get().run()` compiles one kernel *per element* and
   exhausts the operator pool (`OperatorPoolExhausted` / `UnsupportedOperation`). This is
   the phase-0 disaster (see [SETMEM_ENFORCEMENT_POSTMORTEM.md](SETMEM_ENFORCEMENT_POSTMORTEM.md)),
   and it is what phase 10 reproduced. The whole buffer must be **one** kernel.

`c(value)` bakes a host double into the kernel body as a literal, keyed by value — so a
loop of distinct `c(v)` is a loop of distinct kernels. A single `c(smallLiteral)` used as
an invariant operand is fine; `c(hostComputedArray)` to materialize a buffer is not — that
is laundering, and it must become a real on-device computation.

## The problem (original detector gap)

`PackedCollectionDetector` flags element-wise host manipulation of a
`PackedCollection` — `setMem` inside a `for` loop, `setMem`+`toDouble` on one line,
`toArray`→`setMem` round trips. All of these key on the *collection* being touched
inside the loop. That leaves a laundering evasion: compute the values element-wise
into a plain `double[]` (a Java loop, `Arrays.fill`, `Arrays.setAll`, a stream) and
upload the finished array with a single `setMem(data)` call outside any loop. The
computation is identical; the detector sees nothing. Agent-written code on the
PDSL-defects branch used exactly this shape, in test fixtures and in one main-source
method, and review caught it rather than CI.

A second, related pattern: `setMem(new double[n])` / `setMem(new double[]{0.0})`
uploads of zeros to freshly allocated collections. These are pure no-ops —
`MemoryDataAdapter.init` already zeroes new allocations — that survive by being
copied from one test to the next.

Two structural facts make the gap wider than one detector rule:

- **Test sources are fully exempt** (`PackedCollectionDetector.scanFile` returns
  immediately for tests). Host-side *reference* data for assertions is legitimate
  there, but device-*input* construction is not distinguished — and both flagged
  sites were tests.
- **The sanctioned surface is built on the unsanctioned one.** The correct idioms —
  `PackedCollection.fill(value)`, `replace`, `clone` — and `MemoryDataAdapter.init`
  all call the array-accepting `setMem` overloads internally. So the array overloads
  cannot simply be deleted; they move behind the ingest API (item 7 of the contract).

## Current enforcement state (phases 1–10 merged to master)

Enforcement is **full-tree**: `SetMemLiteralsDetector` scans every module with three
rules — `SETMEM_NON_LITERAL_ARGUMENT`, `FILL_PACK_BEYOND_SCALAR_ALLOWANCE`, and
`PACKED_COLLECTION_OF_NON_LITERAL` — against two shrinking ledgers:

- **The grandfathered baseline** (`setmem-violation-baseline.tsv`): the inventory of
  violations that existed when full-tree enforcement was turned on. Entries match on
  exact source text, so editing a grandfathered line re-triggers enforcement for it.
  Module-level gating (`UNMIGRATED_MODULES`) is retired; the baseline is the only
  burn-down ledger.
- **`KNOWN_EXCLUSIONS`**: individually-acknowledged sites in enforced modules —
  framework-internal writes below the producer API, the randomness ingest primitive,
  genuine I/O ingest awaiting the narrowed API, and reference-data test ingest.

### The metric

Running `SetMemLiteralsDetector <root>` prints an exemption summary after every scan:
live grandfathered occurrences, stale ledger rows (fully migrated, eligible for
removal), and how many acknowledged exclusions remain in use. This is the number to
drive to zero. Snapshot 2026-07-29 (phases 11–13 branch, after fixing the detector's
fill/pack prefilter blind spot and regenerating the ledger): 678 live grandfathered
occurrences across 514 entries, all 21 exclusions live — **699 total exemptions**,
zero stale rows.

Snapshot 2026-07-30 (after the phase 14 remediation): 621 live grandfathered
occurrences across 465 entries + 21 exclusions — **642 total exemptions**.

Snapshot 2026-08-01 (phase 19 migration pass): 599 live grandfathered
occurrences across 444 entries + 17 exclusions — **616 total exemptions**.
The multi-value indexed `setMem` delta is migrated: full-buffer row writes
merged into offset-0 literal varargs, redundant zero indexes dropped
repo-wide (251 sites), consecutive-segment writes merged, the SphereTest ray
grids and closestBatch256 pairs became genuine device producers (index-ramp
kernel; pack + repeat), and AudioModelOutput's deserialized embedding moved
onto the ByteBuffer ingest sequence. Remaining indexed sites are deliberate:
the framework Memory-level API, MemoryDataViewWriteTest (exercises the write
surface itself), and the enforcement-test fixture — all resolved by the
detector flip. The non-scalar `pack`/`of` delta is empty: remaining matches
are the sanctioned creation surface, the ONNX ingest overload, and legal
single-scalar packs. By rule:
426 fill/pack beyond the scalar allowance (of which ~356 are `fill(pos -> ...)`
lambda calls), 176 non-literal `setMem`, 19 non-literal `PackedCollection.of`. By
module: engine/utils 311, engine/audio 120, engine/ml 98, studio/compose 54,
domain/graph 16, all others below 10.

### Measured delta to the target contract

Tightening from today's rules to the target contract makes currently-sanctioned
sites illegal. Measured 2026-07-30 (textual scan, so counts are approximate):

- **~159 multi-value indexed `setMem(idx, lit, lit, ...)` calls** in ~24 files
  (contract item 5). Concentrated in engine/utils geometry/layer tests:
  TransformMatrixTest 38, SphereTest 21, Conv1dCorrectnessTest 18, RayBatchTest 15.
- **~36 `pack`/`of` calls with array, call, or lambda arguments** in main and test
  code (Llama2Weights 12, delta-computation tests ~20, ArithmeticSequenceComputation 2).
  The other ~39 currently-hoisted `pack(runtimeScalar)` sites are *legal* under the
  agreed fill-parity rule and need no migration.
- **Zero new violations from `fill`** — the scalar allowance already matches the
  target; the work there is removing the two lambda overloads and migrating their
  ~356 call sites (already counted in the baseline).

Sequenced correctly (migrate first, flip the rules second), the enforcement flip
adds zero new baseline rows; flipped early it would add ~195.

### Use cases that do not fit the contract, and their resolutions

- **Host-computed reference data in tests** (FIR reference coefficients, host
  DFT/GRU reference math, torch-verified outputs): the reference exists to be
  independent of the device, so it must never be produced by a Producer. The
  resolution is to keep it out of device memory entirely — leave the reference a
  host `double[]`, read the device result back with `toArray()` (readback is
  sanctioned), and compare on the host. Reference *inputs* that must reach the
  device ship as resource files through the ingest API.
- **Call-varying host-parameterized data** (seed- or hash-derived tables,
  per-sample synthetic training pairs): cannot be a producer without baking the
  varying value as a `c()` constant — one compiled kernel per distinct value (F1
  in the postmortem; re-proven during phase 14 when a seed-baked producer timed
  out inside NativeCompiler). Needs either the signature-stable broadcast idiom
  (the scalar enters as provider *data*) or reclassification as host-side
  reference data per the previous bullet.
- **Device-random ingest at scale**: `rand`/`randn` producers are the sanctioned
  random path, but the random source allocates a device buffer on top of the
  destination, roughly doubling peak ingest memory — unsuitable for real-dimension
  weight tensors at constrained memory scale. Large synthetic weights remain bulk
  ingest until the ingest API takes them.
- **Framework internals below the producer API** (base/hardware writes that cannot
  import collect, the randomness primitive's own upload, mesh-intersection
  readback writes, the Tensor boxed-value bridge): these implement the sanctioned
  surface and stay exempt by construction, or migrate behind the ingest API.

## Impact census: removing `setMem(int, double[])`

1,222 call sites repo-wide (296 main, 926 test). Test side: ~517 already literal
varargs (unaffected), ~10 literal arrays (trivial rewrites), ~300 identifier
arguments (a mix of `MemoryData`→`MemoryData` copies, which are unaffected, and
staged arrays needing triage). Main side, by category:

1. Scalar/literal state setters (the majority) — unaffected.
2. `MemoryData`→`MemoryData` copies — unaffected (separate overloads).
3. **Genuine I/O ingest** that must keep a bulk host→device path: WAV decode
   (`WavFile`, `WaveData`), ONNX tensors (`OnnxFeatures`), protobuf weights
   (`CollectionEncoder`), shared-memory reads (`SharedMemoryAudioLine`).
4. Host-computed init tables (wavetables, mel filterbanks, RoPE frequencies,
   `EfxManager` choice tables, `TimeCell` reset arrays) — today's
   `LEGITIMATE_CPU_DOMAINS` whitelist territory.
5. **Framework internals** implementing the sanctioned surface itself
   (`PackedCollection.fill`/`replace`/`clone`, `MemoryDataAdapter.init`).

**Conclusion: narrow, don't delete the bulk path — but do delete the indexed
varargs.** Under the agreed contract `setMem(int, double...)` is removed in favor
of a single-value `setMem(index, literal)` (~159 call sites to migrate first,
almost all literal matrices in tests); `setMem(double...)` literal varargs stays.
The `double[]`-accepting bulk forms move behind the explicit, separately-named
ingest API (or become `protected` on `MemoryDataAdapter`), granted to the I/O
layer and the framework internals only. Laundering then dies at the compile
surface: no public method accepts a computed array.

## Roadmap to the target contract (agreed 2026-07-30)

The phase 11–14 remediation of the auto-generated migrations is complete (the
failure-mode catalog that governed it is in
[SETMEM_ENFORCEMENT_POSTMORTEM.md](SETMEM_ENFORCEMENT_POSTMORTEM.md)); phase 16
closes out the last three migrated sites. From there:

1. **Land phases 14/16 through CI** — baseline at 642 total exemptions.
2. **Build the system-boundary ingest API before tightening anything** (contract
   item 7, and the census conclusion below): a named surface for host→device bulk
   transfer, granted to the I/O layer and framework internals only. Migrate
   WavFile/WaveData, OnnxFeatures, CollectionEncoder/protobuf,
   SharedMemoryAudioLine, and graphpersist deserialization onto it; retire the
   corresponding `KNOWN_EXCLUSIONS` entries. Every later migration needs this
   destination to exist first.
3. **Migrate the target-contract deltas while they are still legal** — the ~159
   multi-value indexed `setMem` calls and ~36 non-scalar `pack`/`of` calls — then
   flip the detector to the target semantics, add the single-value
   `setMem(index, value)`, and physically remove `setMem(int, double...)` and the
   two `fill` lambda overloads. In this order the flip adds zero baseline rows.
4. **Burn down the baseline by bucket, not by module**: (a) the ~356
   `fill(pos -> ...)` sites → whole-buffer producers and `rand`/`randn`
   (mechanical; concentrated in engine/utils and engine/ml tests); (b)
   reference-data tests → host-side comparison via `toArray()` readback, so the
   upload is *eliminated*, not migrated; (c) wavetables, filterbanks, and init
   tables in engine/audio → producers or resource files through the ingest API;
   (d) the residue decides whether the signature-stable broadcast-scalar idiom
   earns a named home in the framework.
5. **Endgame**: the baseline reaches zero, the ledger machinery is deleted, and
   the detector remains as a pure regression gate on the narrowed surface.

Violation messages throughout should name the sanctioned idioms — `fill(value)`,
`pack(...)` within the scalar allowance, a single whole-buffer producer, literal
varargs, the ingest API — since the goal is to redirect the author at the moment
of writing.
