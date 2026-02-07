#!/usr/bin/env python3
"""
Generate Block 1 sub-component intermediate outputs using extracted .pb weights.
Uses PyTorch for efficient computation.
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


def weight_norm_conv(weight_g: torch.Tensor, weight_v: torch.Tensor) -> torch.Tensor:
    """Apply weight normalization to get actual weights."""
    norm = weight_v.norm(dim=(1, 2), keepdim=True)
    weight = weight_g * weight_v / (norm + 1e-12)
    return weight


def snake_activation(x: torch.Tensor, alpha: torch.Tensor, beta: torch.Tensor) -> torch.Tensor:
    """Learnable Snake activation: x + (1/beta) * sin^2(alpha * x)."""
    return x + (1.0 / beta.unsqueeze(-1)) * torch.sin(alpha.unsqueeze(-1) * x) ** 2


def run_residual_block(weights_dir: Path, x: torch.Tensor, prefix: str) -> torch.Tensor:
    """Run single residual block: Snake + Conv + Snake + Conv + Skip."""
    residual = x.clone()

    # Snake 1
    alpha = load_pb_file(weights_dir / f"{prefix}.layers.0.alpha.pb")
    beta = load_pb_file(weights_dir / f"{prefix}.layers.0.beta.pb")
    x = snake_activation(x, alpha, beta)

    # Conv 1 (k=7, s=1, p=3)
    weight_g = load_pb_file(weights_dir / f"{prefix}.layers.1.weight_g.pb")
    weight_v = load_pb_file(weights_dir / f"{prefix}.layers.1.weight_v.pb")
    bias = load_pb_file(weights_dir / f"{prefix}.layers.1.bias.pb")
    weight = weight_norm_conv(weight_g, weight_v)
    x = F.conv1d(x, weight, bias, padding=3)

    # Snake 2
    alpha = load_pb_file(weights_dir / f"{prefix}.layers.2.alpha.pb")
    beta = load_pb_file(weights_dir / f"{prefix}.layers.2.beta.pb")
    x = snake_activation(x, alpha, beta)

    # Conv 2 (k=1, s=1, p=0)
    weight_g = load_pb_file(weights_dir / f"{prefix}.layers.3.weight_g.pb")
    weight_v = load_pb_file(weights_dir / f"{prefix}.layers.3.weight_v.pb")
    bias = load_pb_file(weights_dir / f"{prefix}.layers.3.bias.pb")
    weight = weight_norm_conv(weight_g, weight_v)
    x = F.conv1d(x, weight, bias, padding=0)

    return x + residual


def main():
    weights_dir = Path("/workspace/project/common/ml/stable_audio_weights/weights")
    test_data_dir = Path("/workspace/project/common/ml/test_data/stable_audio/reference")

    # Load input_conv output (input to Block 1)
    print("Loading decoder_after_input_conv.bin...")
    input_conv_flat = load_reference_input(test_data_dir / "decoder_after_input_conv.bin")

    # Reshape: (batch=1, channels=2048, length=2)
    channels = 2048
    seq_length = len(input_conv_flat) // channels
    x = input_conv_flat.reshape(1, channels, seq_length)
    print(f"Input shape: {x.shape}, stats: min={x.min().item():.6f}, max={x.max().item():.6f}, mean={x.mean().item():.6f}")

    # Block 1 prefix
    prefix = "decoder.layers.1"
    stride = 16

    # Step 1: Snake activation
    print("\n--- Step 1: Snake Activation ---")
    alpha = load_pb_file(weights_dir / f"{prefix}.layers.0.alpha.pb")
    beta = load_pb_file(weights_dir / f"{prefix}.layers.0.beta.pb")
    print(f"Alpha shape: {alpha.shape}, first 5: {alpha[:5].tolist()}")
    print(f"Beta shape: {beta.shape}, first 5: {beta[:5].tolist()}")

    x = snake_activation(x, alpha, beta)
    print(f"After Snake: {x.shape}, stats: min={x.min().item():.6f}, max={x.max().item():.6f}, mean={x.mean().item():.6f}")
    save_reference_output(x, test_data_dir / "decoder_block1_after_snake.bin")
    print("Saved: decoder_block1_after_snake.bin")

    # Step 2: WNConvTranspose1d upsample
    print("\n--- Step 2: WNConvTranspose1d ---")
    weight_g = load_pb_file(weights_dir / f"{prefix}.layers.1.weight_g.pb")
    weight_v = load_pb_file(weights_dir / f"{prefix}.layers.1.weight_v.pb")
    bias = load_pb_file(weights_dir / f"{prefix}.layers.1.bias.pb")
    print(f"weight_g shape: {weight_g.shape}")
    print(f"weight_v shape: {weight_v.shape}")
    print(f"bias shape: {bias.shape}")

    weight = weight_norm_conv(weight_g, weight_v)
    print(f"Computed weight shape: {weight.shape}")

    k = weight.shape[2]
    p = (k - 1) // 2  # padding = 7
    out_p = stride - 1  # output_padding = 15
    print(f"ConvTranspose params: kernel={k}, stride={stride}, padding={p}, output_padding={out_p}")

    x = F.conv_transpose1d(x, weight, bias, stride=stride, padding=p, output_padding=out_p)
    print(f"After ConvTranspose: {x.shape}, stats: min={x.min().item():.6f}, max={x.max().item():.6f}, mean={x.mean().item():.6f}")
    save_reference_output(x, test_data_dir / "decoder_block1_after_upsample.bin")
    print("Saved: decoder_block1_after_upsample.bin")

    # Step 3-5: 3 residual sub-blocks
    for sub_idx in range(3):
        print(f"\n--- Step {3 + sub_idx}: Residual Block {sub_idx} ---")
        x = run_residual_block(weights_dir, x, f"{prefix}.layers.{sub_idx + 2}")
        print(f"After Residual {sub_idx}: {x.shape}, stats: min={x.min().item():.6f}, max={x.max().item():.6f}, mean={x.mean().item():.6f}")
        save_reference_output(x, test_data_dir / f"decoder_block1_after_residual_{sub_idx}.bin")
        print(f"Saved: decoder_block1_after_residual_{sub_idx}.bin")

    print("\n=== Done! Generated Block 1 sub-component references ===")


if __name__ == "__main__":
    main()
