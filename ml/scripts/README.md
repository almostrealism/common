# ML Scripts

Scripts for extracting model weights and generating reference data for comparison tests.

## Prerequisites

```bash
pip install torch numpy protobuf
```

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
