# C2 Reference-Generation Capability Report

**Branch:** `feature/ml-same-autoencoder`
**Date:** 2026-06-19
**Scope:** Capability confirmation + efficiency characterization ONLY. No
`transformerResamplingBlock` implementation, no parity/comparison, no committed
binaries. The question answered here: *can a delegated agent job generate the
real SAME per-stage reference tensors fast and repeatably, so the real Block C2
loop (which iterates a Java implementation against these references) fits inside
the ~30-minute inactivity-timeout window?*

Companion: [`C2_POSTMORTEM.md`](C2_POSTMORTEM.md) (why the prior C2 attempt faked
parity with committed binaries) and [`PHASE_1_COMPONENT_PLAN.md`](PHASE_1_COMPONENT_PLAN.md)
§C2 (the deliverable the references serve).

---

## 1. TL;DR — verdict: CAPABILITY CONFIRMED (not blocked)

An agent job **can** generate the real SAME-S reference tensors end-to-end, on
the standard CPU-only aarch64 job node, with no GPU and no manual steps.

- **Full cold setup + first reference dump: ≈ 55 s of wall-clock compute.** Every
  phase ran to completion (torch install, gated-weight download, model load,
  seeded forward, 13-stage dump). Nothing was faked or stubbed.
- **Warm per-iteration cost (model load + forward + dump, weights cached): ≈ 2.7 s.**
  That is the cost the real C2 loop pays each time it regenerates references. It
  fits the 30-minute window with ~3 orders of magnitude to spare (~600+ reruns
  per window).
- The references are dumped to the git-ignored build-output path
  `engine/ml/target/test-classes/same-s-references/` (the script's default
  `--out`, wiped by `mvn clean`). **No binaries are committed** — and they must
  never be written into a tracked source path such as
  `src/test/resources/same-s-references`.
- One concrete, in-scope script improvement was applied (lazy protobuf import,
  §5) so the reference dump runs on a fresh transient checkout **without** a
  `grpcio-tools` / `protoc` generation step it never actually needed.

This is a delegatable, repeatable capability, not a manual human step.

---

## 2. Per-phase wall-clock timings (measured this session)

Node: aarch64 Linux (docker), 16 vCPU, 54 GiB RAM, **no GPU**. Input: fixed
seeded `torch.randn(1, 2, 24576)` (seed 1234) — 24 576 samples ≈ 0.56 s of
44.1 kHz stereo. All times are `time.time()` deltas captured by the driver
scripts under `/workspace/timings/`.

| Phase | What | Cold (s) | Warm rerun (s) |
|---|---|---:|---:|
| (a) | `python -m venv` create | 2.0 | n/a |
| (a) | `pip install torch` (**CPU index**) | 22.0 | n/a |
| (a) | `pip install numpy safetensors einops huggingface_hub ml_dtypes` | 6.7 | n/a |
| (a) | `pip install torchaudio` (CPU index) | 1.9 | n/a |
| (a) | `git clone` stable-audio-3 + checkout pin | 1.6 | n/a |
| **(a) total** | **environment + package setup** | **≈ 35** | n/a |
| (b) | gated `stabilityai/SAME-S` download (`model.safetensors` 433 MB + config) | **13.8** | ~0 (cache hit) |
| (c) | model construct + `load_state_dict` | 3.7 | **1.5** |
| (d) | seeded forward (encode+decode) + dump 13 stages | 1.1 | **1.1** |
| **(c)+(d)** | **per-iteration reference generation** | 4.8 | **≈ 2.7** |
| **Cold end-to-end** | (a)+(b)+(c)+(d) from a bare node | **≈ 55** | — |

Warm (c)+(d) measured over two back-to-back reruns: 2.69 s and 2.71 s (load
1.60/1.52 s, forward+dump 1.09/1.19 s). Steady-state per-iteration ≈ **2.7 s**.

**Does the warm rerun fit the ~30-min timeout?** Yes, overwhelmingly. At ~2.7 s
per regeneration, even a few hundred reference regenerations during C2 bring-up
cost a couple of minutes total. The 30-minute window is never the constraint;
the Java implementation/compile work is.

---

## 3. Persistence of the weight cache on the node

**Within a job: confirmed persistent.** After the cold download (phase b) the
433 MB checkpoint stays in the HF cache; the warm model-load (phase c) dropped
from 3.7 s to ~1.5 s as the OS page cache and the local file warmed. Re-downloads
do not recur within the job.

**Across jobs: should NOT be assumed to persist — and it does not need to.**
Evidence gathered this session:

- At job start the default HF cache (`/home/agent/.cache/huggingface/`) was
  **empty** (no `hub/`), and the prior sessions' `/workspace` artifacts
  (`sa3-venv`, `same-s`, `sa3-src`) were **gone** — the git checkout and home
  dir are transient per job.
- `mount` / `findmnt` show `/home/agent` and `/workspace` are both on the
  **same transient docker overlay**. The only real bind mounts from the host are
  `/agent-transcripts` and `/home/agent/.ssh` (read-only). There is **no
  dedicated persistent volume** mounted at the HF cache path.
- Therefore the HF cache lives on the container's writable overlay layer, which
  is normally discarded when the container is torn down. A prior memory claiming
  `/workspace` artifacts "survived restart" referred to a **within-job**
  inactivity-restart, not cross-job persistence.

Conclusion: treat the cold weight download (~14 s) as a per-job cost unless the
orchestration explicitly bind-mounts a host volume at `$HF_HOME`
(`/home/agent/.cache/huggingface`). **This is a non-issue regardless** — 14 s for
the weights plus ~35 s for the (also non-persistent) venv/torch install is the
entire cold tax, and it is paid once per job, not once per C2 iteration.

**Recommendation (infra, optional):** if you want the cold tax to vanish across
jobs, bind-mount a persistent host volume at `$HF_HOME` (weights) and optionally
cache the pip wheels (`$PIP_CACHE_DIR`) / the venv. Not required for C2 to run.

---

## 4. How the capability was exercised (reproducible recipe)

All steps are CPU-only and need no GPU, no `transformers`, no T5Gemma stack.

```bash
# (a) environment — CPU torch ONLY (see §5 for why the CPU index matters)
python3 -m venv /workspace/sa3-venv && source /workspace/sa3-venv/bin/activate
pip install torch torchaudio --index-url https://download.pytorch.org/whl/cpu
pip install numpy safetensors einops huggingface_hub ml_dtypes
git clone https://github.com/Stability-AI/stable-audio-3 /workspace/sa3-src
git -C /workspace/sa3-src checkout bccf5b7b75734c95a3049bb43bdbc7b3070a31bc

# (b) gated weights -> default (HF) cache; token from the `huggingface-token`
#     workspace secret rendered to ~/.cache/huggingface/token (and exported as
#     HF_TOKEN). hf_hub_download caches under $HF_HOME automatically.
python -c "from huggingface_hub import hf_hub_download as d; \
  d('stabilityai/SAME-S','model_config.json'); d('stabilityai/SAME-S','model.safetensors')"

# (c)+(d) load + seeded forward + per-stage dump (the existing script, unchanged).
# --out defaults to the git-ignored build dir engine/ml/target/test-classes/same-s-references
# (one of the locations SAMEResamplingParityTest searches). Do NOT pass a tracked source path
# such as src/test/resources/same-s-references — these dumps are regenerable and must not be committed.
python engine/ml/scripts/dump_same_references.py \
  --sa3-src /workspace/sa3-src \
  --config  <cached>/model_config.json \
  --weights <cached>/model.safetensors \
  --seed 1234 --samples 24576
```

The 13 stages produced (shapes verified against the architecture notes in the
workstream memories) — all written as `[uint32 count][float32...]` `.bin`:

```
test_input        [1, 2, 24576]      enc_resamp_input  [1, 512, 96]
enc_after_mapping [1, 768, 96]       enc_seg_input     [1, 102, 768]
enc_layer0_input  [3, 34, 768]       enc_layer0_output [3, 34, 768]
enc_resamp_output [1, 768, 6]        encoder_output    [1, 256, 6]
latent            [1, 256, 6]        dec_resamp_input  [1, 768, 6]
dec_layer0_input  [3, 34, 768]       dec_layer0_output [3, 34, 768]
dec_resamp_output [1, 512, 96]
```

The encoder is deterministic (`mask_noise=0`); the decoder's stochastic
`mask_noise` and the SoftNorm `noise_regularize` are disabled by the script for a
deterministic reference. (This report **does not** run any Java parity — that is
the real C2 job's work.)

---

## 5. Dump-script assessment & the one change applied

The two salvaged scripts (`dump_same_references.py`,
`dump_same_resampling_weights.py`) and the shared core
(`safetensors_extractor.py`) are **well parameterized and fit repeated reruns**:
explicit `--sa3-src/--config/--weights/--out/--seed/--samples`, small fixed
input, pure CPU-fp32 (no `.cuda()` anywhere), and a struct-based `.bin` dump.
No change is needed to make them rerun-friendly **except** the coupling fixed
below.

### Applied (in scope): lazy protobuf import in `safetensors_extractor.py`

**Problem.** `safetensors_extractor.py` imported the generated `collections_pb2`
protobuf bindings at **module load**. The reference-dump path
(`dump_reference_activations` → `save_reference_output`) is pure `struct`/`numpy`
and never touches protobuf, yet importing the module still required
`collections_pb2.py` — which is gitignored and must be generated with
`grpcio-tools`/`protoc`. Because the git checkout is **transient per job**, every
reference-dump rerun would otherwise have to install `grpcio-tools` and run the
generator first, for a dependency it does not use.

**Fix.** The `collections_pb2` import is now **lazy** (`_require_collections()`),
invoked only by the StateDictionary writer/reader functions
(`numpy_to_collection_data`, `make_entry`, `write_protobuf_file`,
`read_state_dictionary`). The reference-dump and remap/fold helpers import
cleanly with no protobuf present.

**Verified.**
- The driver ran the full load+forward+dump with `collections_pb2.py` **absent** —
  i.e. the reference path no longer needs protobuf.
- `engine/ml/scripts/test_safetensors_extractor.py` — **22 passed** (after
  generating `collections_pb2` for the writer tests), so the protobuf writer/reader
  path is unchanged.

This same fix also unblocks `dump_same_resampling_weights.py` (the ~214 MB
local-only weight dump, used once per checkpoint version), which likewise only
uses the struct serializer.

### Recommended (NOT applied — out of scope or infra)

- **CPU torch index is mandatory for efficiency.** The naive
  `pip install torch` on this aarch64 node pulls the **full CUDA stack** as
  dependencies (`triton` 185 MB, `nvidia_cublas` 542 MB, `cuda_bindings`, … >1 GB
  of GPU wheels) even though the node is CPU-only — a multi-minute, multi-GB
  download for nothing. Always use
  `--index-url https://download.pytorch.org/whl/cpu` (22 s vs minutes). The
  documented `dump_same_references.py` prerequisite block should say so. (Left as
  a doc note rather than editing the script's docstring, to keep this task's diff
  to the single load-bearing change above.)
- **Do not `pip install` the `stable_audio_3` package.** Its `pyproject.toml`
  pins `torch==2.7.1`/`torchaudio==2.7.1` and pulls `transformers`, `gradio`,
  etc. Cloning the source and adding it to `sys.path` (the script's `--sa3-src`
  flag) with a CPU torch + the five light deps is sufficient, lighter, and
  avoids the version pin/conflict. The autoencoder import needs only
  `torch, torchaudio, einops, safetensors, numpy` (the `flash_attn` warnings are
  graceful fallbacks).
- **Persistent caches (infra):** see §3 — optional `$HF_HOME` bind mount to skip
  the ~14 s cold download across jobs.
- **Heavy-loop micro-opt (only if ever needed):** at ~2.7 s/rerun the model load
  (~1.5 s) dominates. A long C2 session that regenerates references hundreds of
  times could load the model once and dump in a loop, but the complexity is not
  worth it at this cost. `bc` is **not installed** on the node — use
  `python3`/`awk` for any timing arithmetic in helper scripts.

---

## 6. What this does and does NOT establish

- **Does:** an agent job can produce the real SAME per-stage references quickly,
  deterministically, and repeatably; the warm per-iteration cost (~2.7 s) fits
  the 30-minute window trivially; the dump tooling is rerun-friendly after the
  one lazy-import fix; binaries stay local and gitignored.
- **Does NOT:** implement `transformerResamplingBlock`, run any Java/AR forward,
  or compare AR output to these references. Numerical parity remains the real
  Block C2 job's responsibility — this report only proves the *reference-supply*
  half of that loop is cheap and automatable.
