#!/usr/bin/env python3
"""
Extract Stable Audio Open autoencoder weights and generate reference outputs.

This script extracts the pretransform (autoencoder) weights from a Stable Audio
checkpoint and exports them in a format compatible with AR's StateDictionary.

It also generates reference encoder/decoder outputs for validation.

Usage:
    python extract_stable_audio_autoencoder.py /path/to/model.ckpt /path/to/output_dir
"""

import os
import sys
import struct
import torch
import numpy as np
from pathlib import Path

import collections_pb2 as collections


def numpy_to_collection_data(array):
    """Convert numpy array to CollectionData protobuf."""
    collection_data = collections.CollectionData()

    # Set traversal policy with shape dimensions
    traversal_policy = collections.TraversalPolicyData()
    for dim in array.shape:
        traversal_policy.dims.append(int(dim))
    traversal_policy.traversal_axis = 0
    collection_data.traversal_policy.CopyFrom(traversal_policy)

    # Use float32 for efficiency
    flattened = array.flatten().astype(np.float32)
    collection_data.data_32.extend(flattened.tolist())

    return collection_data


def write_protobuf_file(entries, file_path):
    """Serialize and write CollectionLibraryData to binary file."""
    library_data = collections.CollectionLibraryData()
    library_data.collections.extend(entries)

    with open(file_path, 'wb') as f:
        f.write(library_data.SerializeToString())

    print(f"  Wrote {len(entries)} weight tensors to {file_path}")


def save_reference_output(tensor: torch.Tensor, filepath: str):
    """Save reference output for validation (simple binary format)."""
    arr = tensor.detach().cpu().float().numpy().flatten()

    with open(filepath, 'wb') as f:
        f.write(struct.pack('<I', len(arr)))
        for val in arr:
            f.write(struct.pack('<f', val))


def extract_autoencoder_weights(ckpt_path: str, output_dir: str):
    """Extract encoder and decoder weights from checkpoint."""
    print(f"Loading checkpoint from {ckpt_path}...")
    ckpt = torch.load(ckpt_path, map_location='cpu', weights_only=False)
    sd = ckpt['state_dict']

    weights_dir = Path(output_dir) / "weights"
    weights_dir.mkdir(parents=True, exist_ok=True)

    # Extract encoder weights into protobuf format
    print("\nExtracting encoder weights...")
    encoder_entries = []
    encoder_keys = [k for k in sd.keys() if k.startswith('pretransform.model.encoder')]
    for key in encoder_keys:
        # Convert key to StateDictionary format (remove 'pretransform.model.' prefix)
        new_key = key.replace('pretransform.model.', '')
        tensor = sd[key]

        entry = collections.CollectionLibraryEntry()
        entry.key = new_key
        entry.collection.CopyFrom(numpy_to_collection_data(tensor.detach().cpu().float().numpy()))
        encoder_entries.append(entry)
        print(f"  {new_key}: {tensor.shape}")

    # Write encoder weights to single protobuf file
    encoder_path = weights_dir / "encoder_weights"
    write_protobuf_file(encoder_entries, str(encoder_path))

    # Extract decoder weights into protobuf format
    print("\nExtracting decoder weights...")
    decoder_entries = []
    decoder_keys = [k for k in sd.keys() if k.startswith('pretransform.model.decoder')]
    for key in decoder_keys:
        new_key = key.replace('pretransform.model.', '')
        tensor = sd[key]

        entry = collections.CollectionLibraryEntry()
        entry.key = new_key
        entry.collection.CopyFrom(numpy_to_collection_data(tensor.detach().cpu().float().numpy()))
        decoder_entries.append(entry)
        print(f"  {new_key}: {tensor.shape}")

    # Write decoder weights to single protobuf file
    decoder_path = weights_dir / "decoder_weights"
    write_protobuf_file(decoder_entries, str(decoder_path))

    print(f"\nTotal: {len(encoder_entries)} encoder + {len(decoder_entries)} decoder = {len(encoder_entries) + len(decoder_entries)} weight tensors")

    return sd


def generate_reference_outputs(sd: dict, output_dir: str, seq_length: int = 2048):
    """Generate reference encoder/decoder outputs using PyTorch."""
    print(f"\nGenerating reference outputs (seq_length={seq_length})...")

    ref_dir = Path(output_dir) / "reference"
    ref_dir.mkdir(parents=True, exist_ok=True)

    # Create a deterministic test input
    torch.manual_seed(42)
    batch_size = 1

    # Test input: stereo audio (batch, 2, length)
    test_input = torch.randn(batch_size, 2, seq_length)

    # Save test input
    save_reference_output(test_input, str(ref_dir / "test_input.bin"))
    print(f"  Saved test_input.bin: {test_input.shape}")

    # Build and run encoder manually layer by layer
    print("\n  Running encoder...")
    encoder_output = run_encoder_reference(sd, test_input)
    save_reference_output(encoder_output, str(ref_dir / "encoder_output.bin"))
    print(f"  Saved encoder_output.bin: {encoder_output.shape}")

    # Also save intermediate outputs for debugging
    intermediates = run_encoder_with_intermediates(sd, test_input)
    for name, tensor in intermediates.items():
        save_reference_output(tensor, str(ref_dir / f"encoder_{name}.bin"))
        print(f"  Saved encoder_{name}.bin: {tensor.shape}")

    # For decoder, use a small latent input
    latent_length = encoder_output.shape[2]
    torch.manual_seed(42)
    latent_input = torch.randn(batch_size, 64, latent_length)

    save_reference_output(latent_input, str(ref_dir / "latent_input.bin"))
    print(f"\n  Saved latent_input.bin: {latent_input.shape}")

    print("\n  Running decoder...")
    decoder_output = run_decoder_reference(sd, latent_input)
    save_reference_output(decoder_output, str(ref_dir / "decoder_output.bin"))
    print(f"  Saved decoder_output.bin: {decoder_output.shape}")

    # Also save decoder intermediate outputs for debugging
    decoder_intermediates = run_decoder_with_intermediates(sd, latent_input)
    for name, tensor in decoder_intermediates.items():
        save_reference_output(tensor, str(ref_dir / f"decoder_{name}.bin"))
        print(f"  Saved decoder_{name}.bin: {tensor.shape}")


def get_f(sd: dict, key: str) -> torch.Tensor:
    """Get tensor from state dict as float32."""
    return sd[key].float()


def weight_norm_conv(weight_g, weight_v, bias=None):
    """Apply weight normalization to get actual weights."""
    # Ensure float32
    weight_g = weight_g.float()
    weight_v = weight_v.float()
    if bias is not None:
        bias = bias.float()

    # weight_g: (out, 1, 1), weight_v: (out, in, k)
    # Normalize v along (in, k) dimensions
    norm = weight_v.norm(dim=(1, 2), keepdim=True)
    weight = weight_g * weight_v / (norm + 1e-12)
    return weight, bias


def snake_activation(x, alpha, beta):
    """Learnable Snake activation: x + (1/beta) * sin^2(alpha * x)"""
    alpha = alpha.float()
    beta = beta.float()
    return x + (1.0 / beta.unsqueeze(-1)) * torch.sin(alpha.unsqueeze(-1) * x) ** 2


def run_encoder_reference(sd: dict, x: torch.Tensor) -> torch.Tensor:
    """Run encoder forward pass using checkpoint weights."""
    prefix = "pretransform.model.encoder"

    # Layer 0: Input conv (2 -> 128, k=7)
    weight_g = get_f(sd, f"{prefix}.layers.0.weight_g")
    weight_v = get_f(sd, f"{prefix}.layers.0.weight_v")
    bias = get_f(sd, f"{prefix}.layers.0.bias")
    weight, _ = weight_norm_conv(weight_g, weight_v)
    x = torch.nn.functional.conv1d(x, weight, bias, padding=3)  # k=7, p=3

    # Encoder blocks (layers 1-5)
    strides = [4, 8, 8, 16, 16]
    for block_idx in range(1, 6):
        x = run_encoder_block(sd, x, block_idx, strides[block_idx - 1])

    # Layer 6: Final Snake activation
    alpha = get_f(sd, f"{prefix}.layers.6.alpha")
    beta = get_f(sd, f"{prefix}.layers.6.beta")
    x = snake_activation(x, alpha, beta)

    # Layer 7: Output conv (2048 -> 128, k=3)
    weight_g = get_f(sd, f"{prefix}.layers.7.weight_g")
    weight_v = get_f(sd, f"{prefix}.layers.7.weight_v")
    bias = get_f(sd, f"{prefix}.layers.7.bias")
    weight, _ = weight_norm_conv(weight_g, weight_v)
    x = torch.nn.functional.conv1d(x, weight, bias, padding=1)

    return x


def run_encoder_block(sd: dict, x: torch.Tensor, block_idx: int, stride: int) -> torch.Tensor:
    """Run single encoder block with 3 residual sub-blocks + downsample."""
    prefix = f"pretransform.model.encoder.layers.{block_idx}"

    # 3 residual sub-blocks
    for sub_idx in range(3):
        x = run_residual_block(sd, x, f"{prefix}.layers.{sub_idx}")

    # Snake activation before downsample
    alpha = get_f(sd, f"{prefix}.layers.3.alpha")
    beta = get_f(sd, f"{prefix}.layers.3.beta")
    x = snake_activation(x, alpha, beta)

    # Downsample conv
    weight_g = get_f(sd, f"{prefix}.layers.4.weight_g")
    weight_v = get_f(sd, f"{prefix}.layers.4.weight_v")
    bias = get_f(sd, f"{prefix}.layers.4.bias")
    weight, _ = weight_norm_conv(weight_g, weight_v)

    k = weight.shape[2]
    p = (k - 1) // 2
    x = torch.nn.functional.conv1d(x, weight, bias, stride=stride, padding=p)

    return x


def run_residual_block(sd: dict, x: torch.Tensor, prefix: str) -> torch.Tensor:
    """Run single residual block: Snake + Conv + Snake + Conv + Skip."""
    residual = x

    # Snake 1
    alpha = get_f(sd, f"{prefix}.layers.0.alpha")
    beta = get_f(sd, f"{prefix}.layers.0.beta")
    x = snake_activation(x, alpha, beta)

    # Conv 1 (k=7, s=1)
    weight_g = get_f(sd, f"{prefix}.layers.1.weight_g")
    weight_v = get_f(sd, f"{prefix}.layers.1.weight_v")
    bias = get_f(sd, f"{prefix}.layers.1.bias")
    weight, _ = weight_norm_conv(weight_g, weight_v)
    x = torch.nn.functional.conv1d(x, weight, bias, padding=3)

    # Snake 2
    alpha = get_f(sd, f"{prefix}.layers.2.alpha")
    beta = get_f(sd, f"{prefix}.layers.2.beta")
    x = snake_activation(x, alpha, beta)

    # Conv 2 (k=1, s=1) - skip/residual projection
    weight_g = get_f(sd, f"{prefix}.layers.3.weight_g")
    weight_v = get_f(sd, f"{prefix}.layers.3.weight_v")
    bias = get_f(sd, f"{prefix}.layers.3.bias")
    weight, _ = weight_norm_conv(weight_g, weight_v)
    x = torch.nn.functional.conv1d(x, weight, bias, padding=0)

    return x + residual


def run_encoder_with_intermediates(sd: dict, x: torch.Tensor) -> dict:
    """Run encoder and save intermediate outputs."""
    prefix = "pretransform.model.encoder"
    intermediates = {}

    # Layer 0: Input conv
    weight_g = get_f(sd, f"{prefix}.layers.0.weight_g")
    weight_v = get_f(sd, f"{prefix}.layers.0.weight_v")
    bias = get_f(sd, f"{prefix}.layers.0.bias")
    weight, _ = weight_norm_conv(weight_g, weight_v)
    x = torch.nn.functional.conv1d(x, weight, bias, padding=3)
    intermediates["after_input_conv"] = x.clone()

    # Save after first block
    strides = [4, 8, 8, 16, 16]
    for block_idx in range(1, 6):
        x = run_encoder_block(sd, x, block_idx, strides[block_idx - 1])
        intermediates[f"after_block_{block_idx}"] = x.clone()

    return intermediates


def run_decoder_with_intermediates(sd: dict, x: torch.Tensor) -> dict:
    """Run decoder and save intermediate outputs after each block."""
    prefix = "pretransform.model.decoder"
    intermediates = {}

    # Layer 0: Input conv (64 -> 2048, k=7)
    weight_g = get_f(sd, f"{prefix}.layers.0.weight_g")
    weight_v = get_f(sd, f"{prefix}.layers.0.weight_v")
    bias = get_f(sd, f"{prefix}.layers.0.bias")
    weight, _ = weight_norm_conv(weight_g, weight_v)
    x = torch.nn.functional.conv1d(x, weight, bias, padding=3)
    intermediates["after_input_conv"] = x.clone()

    # Decoder blocks (layers 1-5) - reversed strides
    strides = [16, 16, 8, 8, 4]
    for block_idx in range(1, 6):
        x = run_decoder_block(sd, x, block_idx, strides[block_idx - 1])
        intermediates[f"after_block_{block_idx}"] = x.clone()

    # Layer 6: Final Snake activation
    alpha = get_f(sd, f"{prefix}.layers.6.alpha")
    beta = get_f(sd, f"{prefix}.layers.6.beta")
    x = snake_activation(x, alpha, beta)
    intermediates["after_final_snake"] = x.clone()

    # Layer 7: Output conv (128 -> 2, k=7)
    weight_g = get_f(sd, f"{prefix}.layers.7.weight_g")
    weight_v = get_f(sd, f"{prefix}.layers.7.weight_v")
    weight, _ = weight_norm_conv(weight_g, weight_v)
    x = torch.nn.functional.conv1d(x, weight, None, padding=3)
    intermediates["final_output"] = x.clone()

    return intermediates


def run_decoder_reference(sd: dict, x: torch.Tensor) -> torch.Tensor:
    """Run decoder forward pass using checkpoint weights."""
    prefix = "pretransform.model.decoder"

    # Layer 0: Input conv (64 -> 2048, k=7)
    weight_g = get_f(sd, f"{prefix}.layers.0.weight_g")
    weight_v = get_f(sd, f"{prefix}.layers.0.weight_v")
    bias = get_f(sd, f"{prefix}.layers.0.bias")
    weight, _ = weight_norm_conv(weight_g, weight_v)
    x = torch.nn.functional.conv1d(x, weight, bias, padding=3)

    # Decoder blocks (layers 1-5) - reversed strides
    strides = [16, 16, 8, 8, 4]
    for block_idx in range(1, 6):
        x = run_decoder_block(sd, x, block_idx, strides[block_idx - 1])

    # Layer 6: Final Snake activation
    alpha = get_f(sd, f"{prefix}.layers.6.alpha")
    beta = get_f(sd, f"{prefix}.layers.6.beta")
    x = snake_activation(x, alpha, beta)

    # Layer 7: Output conv (128 -> 2, k=7)
    weight_g = get_f(sd, f"{prefix}.layers.7.weight_g")
    weight_v = get_f(sd, f"{prefix}.layers.7.weight_v")
    weight, _ = weight_norm_conv(weight_g, weight_v)
    # Note: output conv has no bias in the checkpoint
    x = torch.nn.functional.conv1d(x, weight, None, padding=3)

    return x


def run_decoder_block(sd: dict, x: torch.Tensor, block_idx: int, stride: int) -> torch.Tensor:
    """Run single decoder block: Snake + Upsample + 3 residual blocks."""
    prefix = f"pretransform.model.decoder.layers.{block_idx}"

    # Snake activation
    alpha = get_f(sd, f"{prefix}.layers.0.alpha")
    beta = get_f(sd, f"{prefix}.layers.0.beta")
    x = snake_activation(x, alpha, beta)

    # Upsample (transposed conv)
    weight_g = get_f(sd, f"{prefix}.layers.1.weight_g")
    weight_v = get_f(sd, f"{prefix}.layers.1.weight_v")
    bias = get_f(sd, f"{prefix}.layers.1.bias")
    weight, _ = weight_norm_conv(weight_g, weight_v)

    k = weight.shape[2]
    p = (k - 1) // 2
    out_p = stride - 1  # output padding for proper upsampling
    x = torch.nn.functional.conv_transpose1d(x, weight, bias, stride=stride, padding=p, output_padding=out_p)

    # 3 residual sub-blocks
    for sub_idx in range(3):
        x = run_residual_block(sd, x, f"{prefix}.layers.{sub_idx + 2}")

    return x


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)

    ckpt_path = sys.argv[1]
    output_dir = sys.argv[2]
    seq_length = int(sys.argv[3]) if len(sys.argv) > 3 else 2048

    if not os.path.exists(ckpt_path):
        print(f"Error: Checkpoint not found at {ckpt_path}")
        sys.exit(1)

    print(f"Extracting autoencoder from: {ckpt_path}")
    print(f"Output directory: {output_dir}")

    sd = extract_autoencoder_weights(ckpt_path, output_dir)
    generate_reference_outputs(sd, output_dir, seq_length)

    print("\nDone!")
    print(f"\nWeights saved to: {output_dir}/weights/")
    print(f"Reference outputs saved to: {output_dir}/reference/")


if __name__ == "__main__":
    main()
