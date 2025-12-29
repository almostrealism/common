#!/usr/bin/env python3
"""
Generate decoder intermediate outputs for debugging.
Uses the existing latent_input.bin and decoder weights.
"""

import os
import sys
import struct
import torch
import numpy as np
from pathlib import Path


def load_reference_input(filepath: str) -> torch.Tensor:
    """Load binary reference file."""
    with open(filepath, 'rb') as f:
        count = struct.unpack('<I', f.read(4))[0]
        data = np.array([struct.unpack('<f', f.read(4))[0] for _ in range(count)], dtype=np.float32)
    return torch.from_numpy(data)


def save_reference_output(tensor: torch.Tensor, filepath: str):
    """Save reference output for validation (simple binary format)."""
    arr = tensor.detach().cpu().float().numpy().flatten()
    with open(filepath, 'wb') as f:
        f.write(struct.pack('<I', len(arr)))
        for val in arr:
            f.write(struct.pack('<f', val))


def get_f(sd: dict, key: str) -> torch.Tensor:
    """Get tensor from state dict as float32."""
    return sd[key].float()


def weight_norm_conv(weight_g, weight_v, bias=None):
    """Apply weight normalization to get actual weights."""
    weight_g = weight_g.float()
    weight_v = weight_v.float()
    if bias is not None:
        bias = bias.float()
    norm = weight_v.norm(dim=(1, 2), keepdim=True)
    weight = weight_g * weight_v / (norm + 1e-12)
    return weight, bias


def snake_activation(x, alpha, beta):
    """Learnable Snake activation: x + (1/beta) * sin^2(alpha * x)"""
    alpha = alpha.float()
    beta = beta.float()
    return x + (1.0 / beta.unsqueeze(-1)) * torch.sin(alpha.unsqueeze(-1) * x) ** 2


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

    # Conv 2 (k=1, s=1)
    weight_g = get_f(sd, f"{prefix}.layers.3.weight_g")
    weight_v = get_f(sd, f"{prefix}.layers.3.weight_v")
    bias = get_f(sd, f"{prefix}.layers.3.bias")
    weight, _ = weight_norm_conv(weight_g, weight_v)
    x = torch.nn.functional.conv1d(x, weight, bias, padding=0)

    return x + residual


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
    out_p = stride - 1
    x = torch.nn.functional.conv_transpose1d(x, weight, bias, stride=stride, padding=p, output_padding=out_p)

    # 3 residual sub-blocks
    for sub_idx in range(3):
        x = run_residual_block(sd, x, f"{prefix}.layers.{sub_idx + 2}")

    return x


def run_decoder_with_intermediates(sd: dict, x: torch.Tensor) -> dict:
    """Run decoder and save intermediate outputs after each block."""
    prefix = "pretransform.model.decoder"
    intermediates = {}

    print(f"  Input shape: {x.shape}")

    # Layer 0: Input conv (64 -> 2048, k=7)
    weight_g = get_f(sd, f"{prefix}.layers.0.weight_g")
    weight_v = get_f(sd, f"{prefix}.layers.0.weight_v")
    bias = get_f(sd, f"{prefix}.layers.0.bias")
    weight, _ = weight_norm_conv(weight_g, weight_v)
    x = torch.nn.functional.conv1d(x, weight, bias, padding=3)
    intermediates["after_input_conv"] = x.clone()
    print(f"  After input conv: {x.shape}, stats: min={x.min():.6f}, max={x.max():.6f}, mean={x.mean():.6f}")

    # Decoder blocks (layers 1-5) - reversed strides
    strides = [16, 16, 8, 8, 4]
    for block_idx in range(1, 6):
        x = run_decoder_block(sd, x, block_idx, strides[block_idx - 1])
        intermediates[f"after_block_{block_idx}"] = x.clone()
        print(f"  After block {block_idx}: {x.shape}, stats: min={x.min():.6f}, max={x.max():.6f}, mean={x.mean():.6f}")

    # Layer 6: Final Snake activation
    alpha = get_f(sd, f"{prefix}.layers.6.alpha")
    beta = get_f(sd, f"{prefix}.layers.6.beta")
    x = snake_activation(x, alpha, beta)
    intermediates["after_final_snake"] = x.clone()
    print(f"  After final snake: {x.shape}, stats: min={x.min():.6f}, max={x.max():.6f}, mean={x.mean():.6f}")

    # Layer 7: Output conv (128 -> 2, k=7)
    weight_g = get_f(sd, f"{prefix}.layers.7.weight_g")
    weight_v = get_f(sd, f"{prefix}.layers.7.weight_v")
    weight, _ = weight_norm_conv(weight_g, weight_v)
    x = torch.nn.functional.conv1d(x, weight, None, padding=3)
    intermediates["final_output"] = x.clone()
    print(f"  Final output: {x.shape}, stats: min={x.min():.6f}, max={x.max():.6f}, mean={x.mean():.6f}")

    return intermediates


def main():
    if len(sys.argv) < 3:
        print("Usage: python generate_decoder_intermediates.py /path/to/model.ckpt /path/to/test_data_dir")
        sys.exit(1)

    ckpt_path = sys.argv[1]
    data_dir = Path(sys.argv[2])
    ref_dir = data_dir / "reference"

    print(f"Loading checkpoint from {ckpt_path}...")
    ckpt = torch.load(ckpt_path, map_location='cpu', weights_only=False)
    sd = ckpt['state_dict']

    # Load latent input
    latent_path = ref_dir / "latent_input.bin"
    print(f"\nLoading latent input from {latent_path}...")
    latent_flat = load_reference_input(str(latent_path))

    # Reshape to (batch, channels, length) - 64 channels
    batch_size = 1
    channels = 64
    latent_length = len(latent_flat) // (batch_size * channels)
    latent_input = latent_flat.reshape(batch_size, channels, latent_length)
    print(f"Latent input shape: {latent_input.shape}")

    # Run decoder with intermediates
    print("\nRunning decoder with intermediate saving...")
    intermediates = run_decoder_with_intermediates(sd, latent_input)

    # Save intermediates
    print(f"\nSaving {len(intermediates)} intermediate outputs...")
    for name, tensor in intermediates.items():
        filepath = ref_dir / f"decoder_{name}.bin"
        save_reference_output(tensor, str(filepath))
        print(f"  Saved decoder_{name}.bin: {tensor.shape}")

    print("\nDone!")


if __name__ == "__main__":
    main()
