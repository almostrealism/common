# 06 — Risk & Open-Question Register

> The owner asked that the plan "cover all possible issues up front." This register names every
> hard problem the run-ahead-stream construct and the a1/a2/a3 migration must solve, with a
> **decided approach** or a **resolution step** for each. An item with neither is a blocker and
> is flagged as such. Severity: **S1** = could sink the approach; **S2** = significant; **S3** =
> manageable. Each item carries an ID so the migration plan (05) and design (03) can reference
> it.

## A. Substrate / runtime risks

### R1 (S1) — Metal serializes GPU encoding through one command runner
Multiple streams "running concurrently" cannot actually encode GPU work in parallel: the Metal
backend serializes all encoding through a single command-runner executor (`MetalDataContext`,
cited in `PatternRenderStream`'s javadoc). **Decided approach:** streams run *ahead in time*,
not *in parallel on the GPU*. Concurrency is between a stream's **Java orchestration** (note
gather, graph build, host marshalling) and another stream's **GPU dispatch**; the actual
kernels still serialize through the one runner. The design must not assume true GPU parallelism
across streams. **Resolution step:** Phase 1 measures how much of a2's cost is Java
orchestration vs GPU (the prior path claimed ~278ms/311ms was Java orchestration — re-measure,
do not inherit). If a2's cost is mostly Java, render-ahead on a thread already hides it; if it
is GPU, the gain comes from render-once reducing the *count* of dispatches.

### R2 (S1) — "Render-once" must hold under live genome swap
a1 can swap the element set (genome) live. If a2 caches rendered element audio, a swap must
invalidate exactly the affected elements and no more, while downstream rings may still hold
prior output. **Decided approach:** element identity + a generation/epoch stamp; a swap bumps
the epoch, elements whose definition is unchanged keep their cached audio, changed/removed
elements are evicted. Rings drain prior-epoch buffers naturally (they were already valid output
for their time window). **Open:** the exact element-identity key (must be content-derived, not
JVM-state-derived — see R6). Routed to 03 design + Phase 2.

### R3 (S2) — Ring/buffer memory lifecycle (a real crash happened here)
The prior attempt crashed caching `RenderedNoteAudio` across ticks because percussion sources
were per-gather `fit()` copies that were freed between ticks (dangling `srcRam`), while melodic
sources were stable raw refs. **Decided approach:** the stream's cached element audio must own
stable, non-freed backing storage for its lifetime (allocated in the element's render-once
step, owned by the stream, destroyed on eviction). No caching of views over transient copies.
`PackedCollection`/`Bytes` are `Destroyable`; lifetime is owned by the stream, not the gather.
**Resolution:** design the element-clip storage as stream-owned in 03; assert no use-after-free
via the ring discipline.

### R4 (S2) — Kernel compile spikes break run-to-run consistency
A first encounter of a new kernel shape costs ~tens of seconds to compile (the prior run showed
a ~26s spike). Absorbing it over a 2-minute run "passes" but violates G6 (consistency).
**Decided approach:** pre-warm the (few) kernel shapes in setup before the clock starts —
render-once with coarse shape buckets means a *small, enumerable* set of kernel shapes, which
makes pre-warm tractable. **Resolution:** enumerate the shape set in Phase 2; pre-compile in
setup; G6 asserts p100 (post-setup) under budget.

### R5 (S2) — Producer-valued args frozen at build time (live-swap staleness)
PDSL producer-valued arguments are sampled at model build and lag the genome until rebuild
(today's `wet_filter_coeffs`/`efx_filter_coeffs` staleness). A stream construct that rebuilds
sub-graphs on swap must define what is re-sampled when. **Decided approach:** automation/gene
values flow through per-buffer-refreshed argument *slots* (as a3 already does via
`automationRefresh`), not build-frozen producers, wherever live-swap must affect them. **Open:**
which gene reads can stay build-frozen vs must be slot-refreshed; routed to 03.

## B. Audio-correctness risks

### R6 (S1) — Aggregation/caching decisions must be JVM-state-independent
A foundational lesson from the aggregation debacle: a decision that depends on transient JVM
state (e.g. "this is currently on JVM memory" or "this buffer is small *right now*") is unsafe
because the same logical computation can be in different states across runs, breaking
instruction/kernel reuse and producing non-deterministic results. **Decided approach:** any
"render once and reuse" / aggregation decision keys only on **content-stable, compile-time
properties** of the element (its sources, durations, shape), never on runtime memory location
or mutable counts. This is an explicit design constraint on the stream cache key.

### R7 (S2) — Per-buffer (not per-frame) automation granularity
The PDSL path advances automation once per buffer, not per frame. Inaudible at production
envelope rates per prior claim — **UNVERIFIED**, routed to 01. **Decided approach:** keep
per-buffer for now; if parity (G8) reveals audible stepping on fast sweeps, the streams model
can carry a finer automation stream. Do not pre-optimize.

### R8 (S1) — Acoustic parity with the released CellList sound
The PDSL mixdown must stay perceptually faithful. History: a claimed-parity render "sounded
like mud / unlistenable"; coarse windowed-RMS ratios were wrongly presented as parity proof.
**Decided approach:** parity is judged by **owner listening** on an A/B pair from the pinned
scene, plus structural diffs, never by an aggregate level metric alone (G8). The known
PDSL-vs-CellList signal-path differences must be re-inventoried from source (the existing
`PDSL_DIFFERENCES.md` is an *unverified* starting point — see 01), not trusted.

### R9 (S2) — True stereo as a sink shape, not a second pipeline
Stereo must be one forward carrying both channels (per-channel pan in the PDSL mixdown), not the
pipeline run twice (that attempt was reverted as a cheat). **Decided approach:** stereo is a
property of the sink stream's shape; the render-once element clips are mono (or source-native)
and panned in the mixdown. **Open:** whether any element needs per-side rendering; routed to 03.

## C. Process / epistemic risks (these are what actually sank prior attempts)

### R10 (S1) — Inheriting the prior docs' contested measurements as fact
The prior docs (`STATE_OF_PLAY`, `PDSL_STREAMS`, `FEASIBILITY`, `A2_BATCHED_DISPATCH`,
`PDSL_DIFFERENCES`) are articulate and internally consistent but rest on possibly-false
measurements. **Mitigation:** the Prime Directive + the claim ledger (01) + T2/T3 instruments.
The most dangerous specific instance: accepting "5× needs a kernel redesign" or "the GPU render
is already ~24ms" without re-deriving. These are stamped UNVERIFIED until a session receipt
exists.

### R11 (S1) — Designing in the abstract without falsifying the premise
The plan's premise is "render-once makes a2 cheap." If that is false, the whole design is sand.
**Mitigation:** Phase 1 *measures* the render-once premise (build the T2 counter, measure the
re-render multiple, and prototype render-once for one element class to confirm the per-tick cost
collapses) **before** committing to the full construct build. The feasibility gate (04) is the
checkpoint.

### R12 (S2) — Incrementalism / tuning the wrong structure
History: 14 hours of verified local-gradient tweaks climbing the wrong hill. **Mitigation:** the
feasibility gate (04) is run first; a small local gradient next to a large gap is treated as a
STOP-and-rebuild signal, not a to-do.

## Open questions requiring an owner decision (surfaced, not assumed)

- **OQ1 — Parity scope.** Is acoustic parity (R8/G8) a deliverable of *this* plan, or tracked
  in parallel? (Asked in 00 §2.4.)
- **OQ2 — Migration vs. greenfield for a2.** Stand up the stream construct and port a1/a2/a3
  onto it incrementally behind a flag (safer, parity-checkable at each step) vs. build the new
  a2 render-once path alongside and cut over (cleaner). Recommendation pending the substrate
  map (03); default lean is *incremental behind a flag* given the integrity history.
- **OQ3 — Buffer size.** Confirm the production buffer size for the bar (8192 assumed from the
  budget math; 4096 also referenced). Affects G5.
