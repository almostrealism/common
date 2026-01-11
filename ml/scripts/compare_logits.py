#!/usr/bin/env python3
"""Compare Java vs PyTorch logits at position 1."""

import numpy as np
import struct
from pathlib import Path

def load_logits(path):
    """Load logits from binary file."""
    with open(path, 'rb') as f:
        size = struct.unpack('<i', f.read(4))[0]
        logits = np.array([struct.unpack('<f', f.read(4))[0] for _ in range(size)])
    return logits

# Load PyTorch logits for position 1
pytorch_path = Path("/workspace/project/common/ml/qwen3_reference/autoregressive/position_1_logits.bin")
pytorch_logits = load_logits(pytorch_path)

print("PyTorch position 1 logits:")
print(f"  Shape: {pytorch_logits.shape}")

# Top 10 predictions
top10_indices = np.argsort(pytorch_logits)[-10:][::-1]
print(f"\n  Top 10 predictions:")
for i, idx in enumerate(top10_indices):
    print(f"    {i+1}. Token {idx}: logit={pytorch_logits[idx]:.4f}")

# Compare with Java's top prediction
java_top_token = 14374  # From test output
print(f"\n  Java's top token 14374 has PyTorch logit: {pytorch_logits[14374]:.4f}")

# Expected token from PyTorch
print(f"\n  Token 40 ('I') has logit: {pytorch_logits[40]:.4f}")
print(f"  Token 14374 has logit: {pytorch_logits[14374]:.4f}")

# Let's also check position 0 and position 3 for sanity
print("\n" + "="*60)
print("Sanity check - position 0:")
pytorch_path0 = Path("/workspace/project/common/ml/qwen3_reference/autoregressive/position_0_logits.bin")
pytorch_logits0 = load_logits(pytorch_path0)
top5_0 = np.argsort(pytorch_logits0)[-5:][::-1]
print(f"  Top 5: {list(top5_0)}")
print(f"  Token 271 logit: {pytorch_logits0[271]:.4f}")

print("\n" + "="*60)
print("Position 3:")
pytorch_path3 = Path("/workspace/project/common/ml/qwen3_reference/autoregressive/position_3_logits.bin")
pytorch_logits3 = load_logits(pytorch_path3)
top5_3 = np.argsort(pytorch_logits3)[-5:][::-1]
print(f"  Top 5: {list(top5_3)}")
print(f"  Token 4460 logit: {pytorch_logits3[4460]:.4f}")

print("\nDone!")
