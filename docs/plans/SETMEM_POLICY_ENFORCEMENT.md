# setMem Policy Enforcement — Eliminating Host→Device Transfers

## The end state — what "done" means

**The goal is to remove `setMem` as a host→device path entirely.** When this effort
finishes, the *only* sanctioned way to move data from the JVM host onto the device is a
**tiny constant** written through `PackedCollection::fill` (a scalar or a handful of
literal values). Every other value that ends up in device memory must be **computed on
the device** by a Producer/kernel. There is no long-term "allowed `setMem`" — the
baseline (`setmem-violation-baseline.tsv`) is a *temporary* burn-down ledger of sites
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
  `PackedCollection.fill(value)`, `fill(pos -> ...)`, `replace`, `clone` — and
  `MemoryDataAdapter.init` all call the array-accepting `setMem` overloads
  internally. So the array overloads cannot simply be deleted.

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

**Conclusion: narrow, don't delete.** Keep `setMem(int, double...)` varargs and the
`MemoryData` overloads public; move the `double[]`-accepting bulk forms behind an
explicit, separately-named ingest API (or make them `protected` on
`MemoryDataAdapter`), granted to the I/O layer and the framework internals only.
Laundering then dies at the compile surface: no public method accepts a computed
array.

## Remaining work (phases 11–16)

1. **Remediate the auto-generated phase 11–13 migrations** (current branch). These were
   produced before the post-mortem existed and reproduce the constant-kernel and
   per-element-kernel failure modes — see the catalog in
   [SETMEM_ENFORCEMENT_POSTMORTEM.md](SETMEM_ENFORCEMENT_POSTMORTEM.md).
2. **Migrate the never-enforced modules**: `flowtree/graphpersist`, `studio/compose`,
   `studio/experiments` — their sites live in the grandfathered baseline.
3. **Burn down the baseline** module by module, regenerating the inventory
   (`--generate`) as stale rows accumulate, and retiring `KNOWN_EXCLUSIONS` entries as
   their categories gain real homes.
4. **API narrowing** (the durable fix, per the census above): move the
   `double[]`-accepting bulk `setMem` forms behind an explicit ingest API granted to
   the I/O layer and framework internals only. Enforcement then shrinks to "only
   literals reach `setMem`", with nothing left to game, and the remaining
   ingest-category exclusions migrate to the new API.

Violation messages throughout should name the sanctioned idioms — `fill(value)`,
`fill(pos -> ...)`, a single whole-buffer producer, literal varargs — since the goal
is to redirect the author at the moment of writing.
