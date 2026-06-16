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
Generate it from the proto definition:

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
  -- per-stage reference `.bin` dumps for the SAME parity tests

A concrete extractor is a thin config that declares its remap rules and calls
`core.extract(...)` (see `extract_sa3_weights.py`).

### extract_sa3_weights.py

Stable Audio 3 config over the generalized core. Extracts either the diffusion
transformer or the embedded/standalone SAME autoencoder.

```bash
# DiT weights (what DiffusionTransformer loads):
python extract_sa3_weights.py model.safetensors out_dir --target dit
# Embedded autoencoder (strip the pretransform.model. prefix, fold weight-norm):
python extract_sa3_weights.py model.safetensors out_dir --target ae
# Standalone SAME-S/SAME-L repo (bare keys):
python extract_sa3_weights.py same.safetensors out_dir --target ae --ae-mode standalone
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
