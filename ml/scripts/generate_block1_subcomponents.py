#!/usr/bin/env python3
"""
Generate Block 1 sub-component intermediate outputs for debugging.
Breaks down Block 1 (Snake -> WNConvTranspose1d -> 3 residual blocks) into individual steps.
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


def run_block1_with_subcomponents(sd: dict, x: torch.Tensor) -> dict:
    """Run Block 1 step by step and save each intermediate output."""
    prefix = "pretransform.model.decoder.layers.1"
    stride = 16
    intermediates = {}

    print(f"  Block 1 input shape: {x.shape}")
    print(f"  Block 1 input stats: min={x.min():.6f}, max={x.max():.6f}, mean={x.mean():.6f}")

    # Step 1: Snake activation
    alpha = get_f(sd, f"{prefix}.layers.0.alpha")
    beta = get_f(sd, f"{prefix}.layers.0.beta")
    print(f"  Snake alpha shape: {alpha.shape}, beta shape: {beta.shape}")
    print(f"  Snake alpha[0:5]: {alpha[:5].tolist()}")
    print(f"  Snake beta[0:5]: {beta[:5].tolist()}")
    x = snake_activation(x, alpha, beta)
    intermediates["block1_after_snake"] = x.clone()
    print(f"  After Snake: {x.shape}, stats: min={x.min():.6f}, max={x.max():.6f}, mean={x.mean():.6f}")

    # Step 2: WNConvTranspose1d upsample
    weight_g = get_f(sd, f"{prefix}.layers.1.weight_g")
    weight_v = get_f(sd, f"{prefix}.layers.1.weight_v")
    bias = get_f(sd, f"{prefix}.layers.1.bias")
    weight, _ = weight_norm_conv(weight_g, weight_v)
    print(f"  WNConvTranspose1d weight_g shape: {weight_g.shape}")
    print(f"  WNConvTranspose1d weight_v shape: {weight_v.shape}")
    print(f"  WNConvTranspose1d computed weight shape: {weight.shape}")
    print(f"  WNConvTranspose1d bias shape: {bias.shape}")

    k = weight.shape[2]
    p = (k - 1) // 2
    out_p = stride - 1
    print(f"  WNConvTranspose1d params: kernel={k}, stride={stride}, padding={p}, output_padding={out_p}")

    x = torch.nn.functional.conv_transpose1d(x, weight, bias, stride=stride, padding=p, output_padding=out_p)
    intermediates["block1_after_upsample"] = x.clone()
    print(f"  After WNConvTranspose1d: {x.shape}, stats: min={x.min():.6f}, max={x.max():.6f}, mean={x.mean():.6f}")

    # Step 3-5: 3 residual sub-blocks
    for sub_idx in range(3):
        x = run_residual_block(sd, x, f"{prefix}.layers.{sub_idx + 2}")
        intermediates[f"block1_after_residual_{sub_idx}"] = x.clone()
        print(f"  After Residual {sub_idx}: {x.shape}, stats: min={x.min():.6f}, max={x.max():.6f}, mean={x.mean():.6f}")

    return intermediates


def main():
    if len(sys.argv) < 3:
        print("Usage: python generate_block1_subcomponents.py /path/to/model.ckpt /path/to/test_data_dir")
        sys.exit(1)

    ckpt_path = sys.argv[1]
    data_dir = Path(sys.argv[2])
    ref_dir = data_dir / "reference"

    print(f"Loading checkpoint from {ckpt_path}...")
    ckpt = torch.load(ckpt_path, map_location='cpu', weights_only=False)
    sd = ckpt['state_dict']

    # Load the after_input_conv output as input to Block 1
    input_conv_path = ref_dir / "decoder_after_input_conv.bin"
    print(f"\nLoading input_conv output from {input_conv_path}...")
    input_conv_flat = load_reference_input(str(input_conv_path))

    # Reshape to (batch, channels, length) - 2048 channels after input conv
    batch_size = 1
    channels = 2048
    seq_length = len(input_conv_flat) // (batch_size * channels)
    input_conv_output = input_conv_flat.reshape(batch_size, channels, seq_length)
    print(f"Input conv output shape: {input_conv_output.shape}")

    # Run Block 1 with sub-component intermediates
    print("\nRunning Block 1 with sub-component saving...")
    intermediates = run_block1_with_subcomponents(sd, input_conv_output)

    # Save intermediates
    print(f"\nSaving {len(intermediates)} sub-component outputs...")
    for name, tensor in intermediates.items():
        filepath = ref_dir / f"decoder_{name}.bin"
        save_reference_output(tensor, str(filepath))
        print(f"  Saved decoder_{name}.bin: {tensor.shape}, size={tensor.numel()}")

    print("\nDone!")


if __name__ == "__main__":
    main()
