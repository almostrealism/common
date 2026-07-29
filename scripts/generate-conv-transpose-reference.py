#!/usr/bin/env python3
"""Generates ConvTranspose1d reference data for ConvTranspose1dReferenceTest.

Each file is written in the native PackedCollection serialized format, so the
Java side reads it with PackedCollection.loadCollections and the shape travels
with the file:

    int32 (big-endian)  number of dimensions
    int32 (big-endian)  traversal axis (always 0 here)
    int64 (big-endian)  each dimension length
    float64 (big-endian) each value, in row-major order

Inputs are sequential values (1, 2, 3, ...) and weights are small sequential
multiples of 0.01, so outputs are easy to verify by hand. Expected outputs are
computed by PyTorch's conv_transpose1d.

Usage: python3 generate_reference.py
"""

import os
import struct

import torch
import torch.nn.functional as F

# Generated data is binary, so it lives outside the repository working tree
# (the invalid_files check forbids .bin files anywhere in the tree). The Java
# side (ConvTranspose1dReferenceTest) resolves the same location.
BASE_DIR = os.path.join(
    os.environ.get("AR_TEST_DATA", os.path.join(os.path.expanduser("~"), ".ar", "test_data")),
    "conv_transpose")

CASES = {
    # name: (in_channels, out_channels, seq_length, kernel, stride, padding, output_padding)
    "minimal": (1, 1, 2, 2, 2, 0, 0),
    "with_padding": (1, 1, 2, 4, 2, 1, 0),
    "multichannel_small": (2, 2, 2, 2, 2, 0, 0),
    "oobleck_like": (4, 2, 2, 4, 4, 1, 3),
    "stride16_tiny": (2, 1, 2, 16, 16, 7, 15),
}


def save_collection(path, tensor):
    """Writes a tensor as a serialized PackedCollection (shape header + float64 values)."""
    t = tensor.detach().to(torch.float64).contiguous()
    dims = list(t.shape)
    with open(path, "wb") as f:
        f.write(struct.pack(">ii", len(dims), 0))
        for d in dims:
            f.write(struct.pack(">q", d))
        values = t.flatten().tolist()
        f.write(struct.pack(">" + "d" * len(values), *values))


def sequential(*shape):
    """Returns a tensor of the given shape filled with 1, 2, 3, ..."""
    count = 1
    for d in shape:
        count *= d
    return torch.arange(1, count + 1, dtype=torch.float64).reshape(*shape)


def generate(name, in_ch, out_ch, seq, kernel, stride, padding, output_padding):
    case_dir = os.path.join(BASE_DIR, name)
    os.makedirs(case_dir, exist_ok=True)

    x = sequential(1, in_ch, seq)
    w = sequential(in_ch, out_ch, kernel) * 0.01
    b = torch.zeros(out_ch, dtype=torch.float64)

    y = F.conv_transpose1d(x, w, b, stride=stride, padding=padding,
                           output_padding=output_padding)

    save_collection(os.path.join(case_dir, "input.bin"), x)
    save_collection(os.path.join(case_dir, "weights.bin"), w)
    save_collection(os.path.join(case_dir, "bias.bin"), b)
    save_collection(os.path.join(case_dir, "expected_output.bin"), y)

    print(f"{name}: input{tuple(x.shape)} weights{tuple(w.shape)} -> output{tuple(y.shape)}")


def main():
    for name, params in CASES.items():
        generate(name, *params)


if __name__ == "__main__":
    main()
