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

### export_tokenizer.py

Converts a HuggingFace BPE `tokenizer.json` into the compact length-prefixed `ARTK` binary that
`engine/ml`'s `SentencePieceBPETokenizer` reads. `engine/ml` has no JSON parser on its classpath and
a 256k-token vocabulary is tens of megabytes of JSON, so the conversion happens once here, in
Python, into a format a `DataInputStream` can read with nothing else.

```bash
python export_tokenizer.py --tokenizer-dir /path/to/t5gemma-b-b-ul2 \
    --out /path/to/t5gemma-tokenizer.bin
```

`validate_pipeline` rejects any source tokenizer whose pipeline the Java reader does not reproduce
(an unsupported normalizer, pre-tokenizer, decoder or post-processor, BPE dropout, or an incomplete
byte-fallback vocabulary), so a mismatch fails here rather than silently producing wrong token ids
at inference time. See [Text Tokenization](../../../docs/internals/text-tokenization.md) for the
`ARTK` format and the full set of rules, and the module docstring for the byte-by-byte layout.

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

## CLAP2 audio-text weights and reference dumps

`extract_clap2_weights.py` converts a LAION CLAP2 checkpoint (`open_clip_model.safetensors`
from any of `laion/clap2-{xxs,xs,small,medium,large}-audioxl-s1B`) to the StateDictionary
`org.almostrealism.ml.clap.Clap2` loads. Besides renaming, it permutes the query/key rows of every
qkv projection from CLAP2's interleaved rotary pairing to the framework's split-half pairing,
swaps the halves of the audio feed-forward's gated projection, and appends the register token to
the patch embedding as one more input column (the module docstring explains each).
`test_extract_clap2_weights.py` covers the remap with synthetic tensors.

`dump_clap2_reference.py reference` loads a checkpoint through OpenCLIP and writes, under
`--out`, both the native weights and a reference dump of a musical probe set: NSynth one-shots
(two per instrument family, from a local copy of the NSynth test split, CC BY 4.0), synthesized
drum one-shots and a drum loop, and loops and stems sequenced from NSynth notes, each with its
32 kHz waveform and reference audio embedding, plus the token ids and reference text embeddings of
twenty tag captions and a `manifest.json` naming the tags that describe each clip.
`score_clap2_reference.py` reports the reference model's own tagging accuracy on a dump.

```bash
python3 -m venv /tmp/clap-venv
/tmp/clap-venv/bin/pip install open_clip_torch soundfile safetensors numpy tiktoken pyarrow protobuf
/tmp/clap-venv/bin/python engine/ml/scripts/dump_clap2_reference.py reference laion/clap2-xxs-audioxl-s1B \
    --nsynth /data/nsynth/test-00000-of-00001.parquet --out /data/clap2-xxs
/tmp/clap-venv/bin/python engine/ml/scripts/score_clap2_reference.py /data/clap2-xxs/references
```

`Clap2TaggingSpikeTest` reads the two directories through `AR_CLAP2_WEIGHTS` and
`AR_CLAP2_REFERENCES` (system property or environment variable), and the SAME-S weights for its
device-memory measurement through `AR_SAME_WEIGHTS`. Write the dumps outside the repository: they
hold audio and weights and must not be committed.

### CLAP2 caption tokenizer

`export_clap2_tokenizer.py export` validates the `r50k_base` tiktoken rank file a CLAP2 checkpoint
ships beside its weights (`open_clip_tiktoken/r50k_base.tiktoken`, named by the checkpoint's
`open_clip_config.json`) and copies it to the given path. The native
`org.almostrealism.ml.clap.Clap2Tokenizer` reads that file as it is (base64 token bytes and a rank
per line), so there is no conversion to another format; the validation checks the ranks are dense
and unique, every byte is a token, and the vocabulary ends below the text tower's control tokens.
`probe` writes the reference token ids of a set of captions (through OpenCLIP's own
`TikTokenTokenizer` over the same files) with the smallest rank file that encodes them exactly;
that pair is the committed resource under `engine/ml/src/test/resources/clap2/` that
`Clap2TaggingTest` checks tokenizer parity against in CI. `test_export_clap2_tokenizer.py` covers
the script with synthetic rank files.

```bash
python3 engine/ml/scripts/export_clap2_tokenizer.py export /path/to/hf/snapshot /data/clap2-small/r50k_base.tiktoken
/tmp/clap-venv/bin/python engine/ml/scripts/export_clap2_tokenizer.py probe /path/to/hf/snapshot \
    engine/ml/src/test/resources/clap2
```

`Clap2NativeTaggingTest` scores the probe set entirely natively, with the weights and dump above
and the exported rank file through `AR_CLAP2_TOKENIZER`.

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
