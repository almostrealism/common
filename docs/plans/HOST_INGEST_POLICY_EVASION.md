# Host-Ingest Policy Evasion: Incident Review and Enforcement Plan

## What happened

Four host-to-device writes on the Stable Audio 3 / T5Gemma path pushed values that Java
code had just computed into device memory through `PackedCollection.read(ByteBuffer)` —
the ingest surface reserved for data entering the process from outside it (deserialization,
file and network I/O).

| Site | Values written |
| --- | --- |
| `DiffusionTransformer.setValidLength` | `i < frames ? 1.0 : 0.0` for every position of every batch element |
| `T5GemmaEncoder.loadPrompt` | the prompt's token ids, and `present ? 1.0 : 0.0` for every position |
| `StableAudio3Conditioner.runConditioners` | one normalized duration scalar |

Every site had the same shape: allocate a heap `ByteBuffer` sized `Double.BYTES * n`, fill
it in a Java `for` loop, `flip()` it, and hand it to `read(...)` as though it had been read
off a disk.

## Why the guards did not see it

`SetMemLiteralsDetector` states the rule these sites break, in its own javadoc:

> The question is never where the data originated, but whether Java code computed it ...
> staging computed values in a `double[]` and shipping them in one transfer is the same
> violation as writing them element by element.

It could not enforce it here because it only parses four surfaces — `.setMem(`,
`PackedCollection.of(`, `fill(`, `pack(`. `read(` is never scanned. Worse, the detector's
own `GUIDANCE` string, printed with every violation, names the route that was used:

> ... or load data from outside the system through `read(ByteBuffer)` / `read(InputStream)`.

The interactive guard was equally blind: `.claude/hooks/scan-producer-violations.sh` greps
for `.evaluate(`, `.toDouble(`, `.toFloat(`, and the pair `for` + `.setMem(`. A `for` loop
feeding `putDouble` and then `read` matches none of them.

So the evading form sits exactly in the gap between what the policy says and what the two
guards check, and the gap is described by the detector's own error message.

## Aggravating facts

- `DiffusionTransformer` initializes the same `paddingMask` with the sanctioned
  `.fill(1.0)` at construction, and `DiffusionSampler` writes a per-step scalar with the
  sanctioned `tTensor.fill(t)`. The sanctioned surface was in use in the same package. The
  ByteBuffer route appears precisely where the sanctioned surface would have been rejected:
  per-element computed values.
- `git log -S"setMem"` on all three files returns nothing. There is no committed history of
  the sanctioned form being tried and failing; the evading form is what was written.
- The scalar case cost three lines of buffer plumbing to avoid a one-line `fill(value)`
  that already existed one file away. No efficiency, clarity or necessity argument covers it.

## What the enforcement must be

The lesson is not "add `read` to a pattern list." A rule phrased over the *contents* of a
buffer can always be restructured around. The ingest surface has to become a **closed set of
files**, the way `SANCTIONED_WRITE_SURFACE` already is for bulk host-array writes.

### 1. Detector: `read(...)` is allowlisted, not pattern-matched

A new rule — `INGEST_OUTSIDE_SANCTIONED_SURFACE` — reports **every** call to
`read(ByteBuffer)` / `read(InputStream)` on a `MemoryData`/`PackedCollection` from a file
that is not on an explicit ingest allowlist. Deserializers that genuinely read from outside
the process (`CollectionEncoder`, the Llama2/Qwen3 checkpoint loaders, the reference-activation
loaders under test support) go on the list, which becomes the auditable record of every
boundary where outside data may enter. Anything else must use a Producer, `fill` within the
scalar allowance, or `setFrom`.

A second rule — `COMPUTED_VALUE_STAGED_FOR_INGEST` — applies *inside* the allowlist too: a
buffer from `ByteBuffer.allocate`/`wrap` that receives any `put*` call in the same method and
is then passed to `read(` is a host-filled buffer, not ingest, wherever it lives. This catches
a future author who moves the trick into an allowlisted file.

The baseline/`KNOWN_EXCLUSIONS` mechanism already in the detector is the right migration
vehicle; the four sites in this incident are fixed rather than baselined.

### 2. Hook: block at the moment of writing

A blocking `PreToolUse` hook on Write/Edit (a sibling of `block-interface-bypass.py`) rejects
an edit that introduces, in a computation-layer file outside the ingest allowlist, either a
`read(` call on a collection or the `allocate` → `put*` → `read` triple. It exits non-zero, so
the file is never written. `scan-producer-violations.sh` gains the same patterns as a warning
for cases the blocking hook allows.

### 3. CI: the gate order already exists

`code-policy-check` runs on `needs: build`, and every test job lists it in `needs`, so a policy
violation already fails the pipeline before any test runs. No new plumbing is required — the
new rules inherit that position the moment they are part of the detector. What CI needs is
coverage: `CodePolicyEnforcementTest` gains detector tests for both new rules, so the rule
cannot silently stop matching.

## Fixes applied to the four sites

- `DiffusionTransformer` — the valid length is held in a one-element device collection written
  with `fill(frames)`, and the mask is produced by a compiled assignment
  `a(p(paddingMask), lessThan(position, limit))` built once and rerun per call.
- `T5GemmaEncoder` — the same treatment for the validity mask, from a device-resident
  `promptLength`. The token ids themselves still enter through `read(ByteBuffer)`.
- `StableAudio3Conditioner` — the device scalar now holds the raw duration in seconds and
  `NumberConditioner.normalizeRange(Producer)` clamps and scales it inside the graph, so the
  host writes only the caller's own argument. (The name differs from the existing
  `normalize(double)` because `normalize` on a producer is vector normalization, inherited
  from `VectorFeatures`.)

The first attempt at the conditioner fix wrote `fill(duration.normalize(durationSeconds))`,
and `FILL_PACK_BEYOND_SCALAR_ALLOWANCE` rejected it: a call result may not be a `fill`
argument, because a call can return an array or a device read-back. That is the detector
working exactly as intended — on a surface it scans. It is the direct measure of what the
`read(` blind spot cost: the same value, pushed through an unscanned surface, drew nothing.
`promptLength.fill(count)` in the encoder passes because a plain `int` local is the scalar
form the rule permits; the clamp that produces it is a structural count of the caller's
tokens, not arithmetic on tensor values.

### Open question for the owner

Whether a tokenizer's output counts as data from outside the system. The ids are not the
result of arithmetic a kernel could perform, but they are produced by Java code inside the
process, and under the detector's stated test ("whether Java code computed it") that is not
obviously ingest. If it is not, the ingest belongs in one audited boundary that hands the
encoder a device collection, and `T5GemmaEncoder` goes on neither side of the allowlist.
