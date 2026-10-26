# ML Scripts

Scripts for extracting model weights and generating reference data for comparison tests.

## Prerequisites

```bash
pip install torch numpy protobuf            # torch only for the .ckpt extractors
pip install safetensors ml_dtypes pytest    # for the safetensors core + its tests
```

`ml_dtypes` is what lets numpy read `bfloat16` safetensors; without it, fp32/fp16
checkpoints still load but a bf16 checkpoint raises a clear error.

## Generated Files

The `collections_pb2.py` file is **not checked into git** because it's generated code.
Every script that writes or reads protobuf shards needs it, including the reference
dumpers (`dump_same_references.py`, `dump_same_resampling_weights.py`,
`dump_sa3_dit_reference.py`, `dump_t5gemma_reference.py`).
Generate it from the proto definition (or run `generate_protobuf_python.sh`):

```bash
protoc --python_out=. --proto_path=../src/main/proto collections.proto
```

Or copy from Maven build output after running `mvn compile`:
```bash
cp ../target/generated-sources/protobuf/python/collections_pb2.py .
```

## Scripts

### safetensors_extractor.py (generalized core)

Reusable, model-agnostic engine for `.safetensors` -> StateDictionary extraction.
Has **no torch dependency**, so it and its tests run without PyTorch or model
weights. It exposes:

- `load_safetensors(path)` -> `dict[str, ndarray]` (upcasts bf16/fp16 -> fp32)
- `remap(state, rules)` -> applies an ordered list of `dict -> dict` rules; build
  them with `select_prefix`, `strip_prefix`, `rename_prefix`, `rename_regex`,
  `rule(match, rename, transform)`, `fold_weight_norm`, and `check_shapes`
- `write_state_dictionary(state, out_dir)` / `read_state_dictionary(path)` -- the
  shared protobuf shard writer/reader (the torch-based extractors import the
  writer from here so there is a single implementation)
- `dump_reference_activations(stages, out_dir)` / `run_reference_stages(model, x)`
  -- per-stage reference dumps for the parity tests, written as the same protobuf
  shards through `write_state_dictionary` (so the `dump_*.py` scripts need the
  generated `collections_pb2.py` above); `read_state_dictionary` skips the `.json`
  metadata sidecars those scripts write beside the shards

A concrete extractor is a thin config that declares its remap rules and calls
`core.extract(...)` (see `extract_sa3_weights.py`).

### extract_sa3_weights.py

Stable Audio 3 config over the generalized core. Extracts the diffusion
transformer, the embedded/standalone SAME autoencoder, or the conditioner
tensors (the learned prompt padding embedding and the duration projection that
`StableAudio3` loads; the T5Gemma prompt encoder itself is extracted separately
by `extract_t5gemma_weights.py`).

```bash
# DiT weights (what DiffusionTransformer loads):
python extract_sa3_weights.py model.safetensors out_dir --target dit
# Embedded autoencoder (strip the pretransform.model. prefix, fold weight-norm):
python extract_sa3_weights.py model.safetensors out_dir --target ae
# Standalone SAME-S/SAME-L repo (bare keys):
python extract_sa3_weights.py same.safetensors out_dir --target ae --ae-mode standalone
# Conditioner tensors (padding embedding + duration projection):
python extract_sa3_weights.py model.safetensors out_dir --target conditioner
```

### Tests

```bash
# Core + SA3 config tests (synthetic only; no torch, no weights):
python -m pytest test_safetensors_extractor.py -v
# Best-effort validation of the SA3 remap config against the REAL released key
# set (fetches only the safetensors header, never the weights). Skips cleanly
# when no Hugging Face token / network is available:
python -m pytest test_sa3_real_keys.py -v
```

### extract_stable_audio_autoencoder.py

Primary extraction script. Downloads/loads a Stable Audio checkpoint and:
1. Exports weights to StateDictionary format (.pb files)
2. Generates layer-level reference outputs for encoder and decoder

```bash
python extract_stable_audio_autoencoder.py /path/to/model.ckpt /path/to/output_dir
```

**Outputs:**
- `weights/*.pb` - Model weights in StateDictionary format
- `reference/test_input.bin` - Test audio input
- `reference/encoder_output.bin` - Encoder output
- `reference/encoder_after_*.bin` - Encoder layer intermediates
- `reference/latent_input.bin` - Latent space input for decoder
- `reference/decoder_output.bin` - Decoder output
- `reference/decoder_after_*.bin` - Decoder layer intermediates

### generate_block1_from_pb.py

Generates Block 1 sub-component reference outputs using extracted .pb weights.
Run this after `extract_stable_audio_autoencoder.py`.

```bash
python generate_block1_from_pb.py
```

**Outputs:**
- `reference/decoder_block1_after_snake.bin`
- `reference/decoder_block1_after_upsample.bin`
- `reference/decoder_block1_after_residual_N.bin`

### generate_residual_block_references.py

Generates granular residual block sub-component references for debugging.
Run this after `extract_stable_audio_autoencoder.py`.

```bash
python generate_residual_block_references.py
```

**Outputs:**
- `reference/decoder_block1_resN_input.bin`
- `reference/decoder_block1_resN_after_snake1.bin`
- `reference/decoder_block1_resN_after_conv1.bin`
- `reference/decoder_block1_resN_after_snake2.bin`
- `reference/decoder_block1_resN_after_conv2.bin`
- `reference/decoder_block1_resN_output.bin`

## Regenerating Stable Audio 3 (SAME) reference dumps

`dump_same_references.py` produces per-stage reference tensors for the SAME autoencoder
(`[uint32 count][float32...]` `.bin` files) that `SAMEResamplingParityTest` compares against.
Regenerating them needs no GPU, no `transformers` and no T5Gemma stack:

```bash
# Environment: CPU-only torch. Always use the CPU wheel index; the default index pulls
# gigabytes of GPU wheels even on a CPU-only machine.
python3 -m venv /tmp/sa3-venv && source /tmp/sa3-venv/bin/activate
pip install torch torchaudio --index-url https://download.pytorch.org/whl/cpu
pip install numpy safetensors einops huggingface_hub ml_dtypes

# Clone the upstream source and point the script at it with --sa3-src.
# Do NOT pip install the stable_audio_3 package: it pins torch and pulls a large
# dependency set the dump does not need.
git clone https://github.com/Stability-AI/stable-audio-3 /tmp/sa3-src

# Gated weights: needs a Hugging Face token with access (HF_TOKEN, or the token file
# under ~/.cache/huggingface). Downloads are cached under $HF_HOME.
python -c "from huggingface_hub import hf_hub_download as d; \
  d('stabilityai/SAME-S','model_config.json'); d('stabilityai/SAME-S','model.safetensors')"

# Seeded forward pass and per-stage dump.
python engine/ml/scripts/dump_same_references.py \
  --sa3-src /tmp/sa3-src \
  --config  <hf-cache>/model_config.json \
  --weights <hf-cache>/model.safetensors \
  --seed 1234 --samples 24576
```

`--out` defaults to the git-ignored build directory
`engine/ml/target/test-classes/same-s-references`, one of the locations the parity test
searches. Never write the dumps to a tracked path such as `src/test/resources`: they are
regenerable and must not be committed. The model load dominates the cost of a rerun (a few
seconds), so regenerating many times is cheap once the environment and cache exist.

## Workflow for Comparison Tests

1. **Extract weights and generate high-level references:**
   ```bash
   python extract_stable_audio_autoencoder.py /path/to/stable-audio.ckpt ../test_data/stable_audio
   ```

2. **Generate block-level references (optional, for debugging):**
   ```bash
   python generate_block1_from_pb.py
   python generate_residual_block_references.py
   ```

3. **Run Java comparison tests:**
   ```bash
   # Uses MCP test runner - see ml/CLAUDE.md for details
   ```
