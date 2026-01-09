#!/usr/bin/env python3
"""
Generate detailed sub-component reference outputs for Decoder Block 1's residual blocks.

This script generates intermediate outputs for each sub-component within
the residual blocks to help debug error accumulation.

Uses .pb weight files (from StateDictionary format) instead of checkpoint.

Usage:
    python generate_residual_block_references.py /path/to/weights_dir /path/to/test_data_dir
"""

import struct
import numpy as np
import torch
import torch.nn.functional as F
from pathlib import Path


def load_pb_file(filepath: str) -> torch.Tensor:
    """Load tensor from .pb file format (StateDictionary format)."""
    with open(filepath, 'rb') as f:
        ndim = struct.unpack('<I', f.read(4))[0]
        shape = []
        for _ in range(ndim):
            dim = struct.unpack('<I', f.read(4))[0]
            shape.append(dim)
        total_elements = 1
        for d in shape:
            total_elements *= d
        data = np.frombuffer(f.read(total_elements * 4), dtype=np.float32)
        return torch.from_numpy(data.reshape(shape) if shape else data.copy())


def load_reference_input(filepath: str) -> torch.Tensor:
    """Load binary reference file (simple format: count + float32s)."""
    with open(filepath, 'rb') as f:
        count = struct.unpack('<I', f.read(4))[0]
        data = np.frombuffer(f.read(count * 4), dtype=np.float32)
    return torch.from_numpy(data.copy())


def save_reference_output(tensor: torch.Tensor, filepath: str):
    """Save reference output (simple format: count + float32s)."""
    arr = tensor.detach().cpu().float().numpy().flatten()
    with open(filepath, 'wb') as f:
        f.write(struct.pack('<I', len(arr)))
        f.write(arr.tobytes())
    print(f"  Saved {filepath}: shape={list(tensor.shape)}, elements={len(arr)}")


def weight_norm_conv(weight_g: torch.Tensor, weight_v: torch.Tensor) -> torch.Tensor:
    """Apply weight normalization to get actual weights."""
    norm = weight_v.norm(dim=(1, 2), keepdim=True)
    weight = weight_g * weight_v / (norm + 1e-12)
    return weight


def snake_activation(x: torch.Tensor, alpha: torch.Tensor, beta: torch.Tensor) -> torch.Tensor:
    """Learnable Snake activation: x + (1/beta) * sin^2(alpha * x)."""
    return x + (1.0 / beta.unsqueeze(-1)) * torch.sin(alpha.unsqueeze(-1) * x) ** 2


def run_residual_block_with_intermediates(weights_dir: Path, x: torch.Tensor, prefix: str, block_name: str) -> tuple:
    """
    Run single residual block and capture ALL intermediate outputs.

    Returns:
      - final output
      - dict of intermediates with names like '{block_name}_input', '{block_name}_after_snake1', etc.
    """
    intermediates = {}
    residual = x.clone()
    intermediates[f'{block_name}_input'] = x.clone()

    # Snake 1
    alpha = load_pb_file(weights_dir / f"{prefix}.layers.0.alpha.pb")
    beta = load_pb_file(weights_dir / f"{prefix}.layers.0.beta.pb")
    x = snake_activation(x, alpha, beta)
    intermediates[f'{block_name}_after_snake1'] = x.clone()
    print(f"    {block_name} after snake1: min={x.min().item():.6f}, max={x.max().item():.6f}, mean={x.mean().item():.6f}")

    # Conv 1 (k=7, s=1, p=3)
    weight_g = load_pb_file(weights_dir / f"{prefix}.layers.1.weight_g.pb")
    weight_v = load_pb_file(weights_dir / f"{prefix}.layers.1.weight_v.pb")
    bias = load_pb_file(weights_dir / f"{prefix}.layers.1.bias.pb")
    weight = weight_norm_conv(weight_g, weight_v)
    x = F.conv1d(x, weight, bias, padding=3)
    intermediates[f'{block_name}_after_conv1'] = x.clone()
    print(f"    {block_name} after conv1:  min={x.min().item():.6f}, max={x.max().item():.6f}, mean={x.mean().item():.6f}")

    # Snake 2
    alpha = load_pb_file(weights_dir / f"{prefix}.layers.2.alpha.pb")
    beta = load_pb_file(weights_dir / f"{prefix}.layers.2.beta.pb")
    x = snake_activation(x, alpha, beta)
    intermediates[f'{block_name}_after_snake2'] = x.clone()
    print(f"    {block_name} after snake2: min={x.min().item():.6f}, max={x.max().item():.6f}, mean={x.mean().item():.6f}")

    # Conv 2 (k=1, s=1, p=0)
    weight_g = load_pb_file(weights_dir / f"{prefix}.layers.3.weight_g.pb")
    weight_v = load_pb_file(weights_dir / f"{prefix}.layers.3.weight_v.pb")
    bias = load_pb_file(weights_dir / f"{prefix}.layers.3.bias.pb")
    weight = weight_norm_conv(weight_g, weight_v)
    x = F.conv1d(x, weight, bias, padding=0)
    intermediates[f'{block_name}_after_conv2'] = x.clone()
    print(f"    {block_name} after conv2:  min={x.min().item():.6f}, max={x.max().item():.6f}, mean={x.mean().item():.6f}")

    # Residual add
    x = x + residual
    intermediates[f'{block_name}_output'] = x.clone()
    print(f"    {block_name} output:       min={x.min().item():.6f}, max={x.max().item():.6f}, mean={x.mean().item():.6f}")

    return x, intermediates


def main():
    weights_dir = Path("/workspace/project/common/ml/stable_audio_weights/weights")
    test_data_dir = Path("/workspace/project/common/ml/test_data/stable_audio")
    ref_dir = test_data_dir / "reference"

    # Load after_upsample output (input to Residual Block 0)
    print("Loading decoder_block1_after_upsample.bin (input to residual blocks)...")
    upsample_flat = load_reference_input(ref_dir / "decoder_block1_after_upsample.bin")

    # Reshape: (batch=1, channels=1024, length=33)
    channels = 1024
    seq_length = len(upsample_flat) // channels
    x = upsample_flat.reshape(1, channels, seq_length)
    print(f"Input shape: {x.shape}, stats: min={x.min().item():.6f}, max={x.max().item():.6f}, mean={x.mean().item():.6f}")

    # Block 1 prefix for residual blocks
    block1_prefix = "decoder.layers.1"
    all_intermediates = {}

    # Residual Block 0 (layers.2)
    print("\n--- Residual Block 0 (layers.2) ---")
    res0_prefix = f"{block1_prefix}.layers.2"
    x, res0_intermediates = run_residual_block_with_intermediates(weights_dir, x, res0_prefix, "res0")
    all_intermediates.update(res0_intermediates)

    # Residual Block 1 (layers.3) - this is where error jumps
    print("\n--- Residual Block 1 (layers.3) [ERROR JUMP LOCATION] ---")
    res1_prefix = f"{block1_prefix}.layers.3"
    x, res1_intermediates = run_residual_block_with_intermediates(weights_dir, x, res1_prefix, "res1")
    all_intermediates.update(res1_intermediates)

    # Residual Block 2 (layers.4)
    print("\n--- Residual Block 2 (layers.4) ---")
    res2_prefix = f"{block1_prefix}.layers.4"
    x, res2_intermediates = run_residual_block_with_intermediates(weights_dir, x, res2_prefix, "res2")
    all_intermediates.update(res2_intermediates)

    # Save all intermediates
    print(f"\n=== Saving {len(all_intermediates)} sub-component outputs ===")
    for name, tensor in sorted(all_intermediates.items()):
        filepath = ref_dir / f"decoder_block1_{name}.bin"
        save_reference_output(tensor, str(filepath))

    print("\n=== Done! Generated granular residual block references ===")
    print("\nGenerated files:")
    for name in sorted(all_intermediates.keys()):
        print(f"  decoder_block1_{name}.bin")


if __name__ == "__main__":
    main()
