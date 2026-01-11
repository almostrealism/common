#!/usr/bin/env python3
"""
Generate layer 1 RoPE intermediate reference files.
This captures Q/K AFTER RoPE rotation by hooking into the model.
"""

import os
import struct
import math
import torch
from transformers import AutoModelForCausalLM
from transformers.models.qwen2.modeling_qwen2 import Qwen2Attention

MODEL_NAME = "Qwen/Qwen2.5-0.5B-Instruct"
OUTPUT_DIR = "/workspace/project/common/ml/qwen3_reference/layer_outputs"

def save_bin(data, filename):
    """Save numpy array to binary file."""
    filepath = os.path.join(OUTPUT_DIR, filename)
    with open(filepath, 'wb') as f:
        f.write(struct.pack('i', len(data)))
        for val in data:
            f.write(struct.pack('f', float(val)))
    print(f"Saved {filename}: {len(data)} values")

def print_stats(name, tensor):
    """Print statistics for a tensor."""
    t = tensor.float().flatten()
    print(f"  {name}: shape={list(tensor.shape)}, mean={t.mean():.6f}, std={t.std():.6f}, range=[{t.min():.4f}, {t.max():.4f}]")

# Storage for captured values
captured = {}

def main():
    print("=" * 70)
    print("  GENERATING LAYER 1 ATTENTION INTERMEDIATE REFERENCE DATA")
    print("=" * 70)

    print(f"\nLoading model {MODEL_NAME} (float32)...")
    model = AutoModelForCausalLM.from_pretrained(MODEL_NAME, torch_dtype=torch.float32)
    model.eval()

    token_id = 9707

    # Get config
    config = model.config
    dim = config.hidden_size  # 896
    heads = config.num_attention_heads  # 14
    kv_heads = config.num_key_value_heads  # 2
    head_size = dim // heads  # 64

    print(f"\nConfig: dim={dim}, heads={heads}, kv_heads={kv_heads}, head_size={head_size}")

    # Patch layer 1's attention forward to capture intermediates
    layer1_attn = model.model.layers[1].self_attn
    original_forward = layer1_attn.forward

    def patched_forward(hidden_states, attention_mask=None, position_ids=None,
                       past_key_value=None, output_attentions=False, use_cache=False,
                       cache_position=None, position_embeddings=None, **kwargs):
        bsz, q_len, _ = hidden_states.size()

        # Q, K, V projections
        query_states = layer1_attn.q_proj(hidden_states)
        key_states = layer1_attn.k_proj(hidden_states)
        value_states = layer1_attn.v_proj(hidden_states)

        captured['q_proj'] = query_states.detach().clone()
        captured['k_proj'] = key_states.detach().clone()
        captured['v_proj'] = value_states.detach().clone()

        # Reshape to (batch, heads, seq_len, head_dim)
        query_states = query_states.view(bsz, q_len, heads, head_size).transpose(1, 2)
        key_states = key_states.view(bsz, q_len, kv_heads, head_size).transpose(1, 2)
        value_states = value_states.view(bsz, q_len, kv_heads, head_size).transpose(1, 2)

        # Get position embeddings
        if position_embeddings is None:
            cos, sin = model.model.rotary_emb(value_states, position_ids)
        else:
            cos, sin = position_embeddings

        captured['cos'] = cos.detach().clone()
        captured['sin'] = sin.detach().clone()

        # Apply rotary embeddings (this is what Qwen2 does internally)
        def rotate_half(x):
            x1 = x[..., : x.shape[-1] // 2]
            x2 = x[..., x.shape[-1] // 2 :]
            return torch.cat((-x2, x1), dim=-1)

        # RoPE implementation from Qwen2
        cos = cos.unsqueeze(1)  # (bs, 1, seq_len, dim)
        sin = sin.unsqueeze(1)  # (bs, 1, seq_len, dim)

        query_states_rope = (query_states * cos) + (rotate_half(query_states) * sin)
        key_states_rope = (key_states * cos) + (rotate_half(key_states) * sin)

        captured['q_rope'] = query_states_rope.detach().clone()
        captured['k_rope'] = key_states_rope.detach().clone()
        captured['v'] = value_states.detach().clone()

        # GQA expansion - repeat K and V to match Q heads
        groups = heads // kv_heads  # 7
        key_states_expanded = key_states_rope.repeat_interleave(groups, dim=1)  # (1, 14, 1, 64)
        value_states_expanded = value_states.repeat_interleave(groups, dim=1)   # (1, 14, 1, 64)

        captured['k_expanded'] = key_states_expanded.detach().clone()
        captured['v_expanded'] = value_states_expanded.detach().clone()

        # Attention scores: Q @ K^T / sqrt(head_size)
        scale = 1.0 / math.sqrt(head_size)
        attn_weights = torch.matmul(query_states_rope, key_states_expanded.transpose(-2, -1)) * scale

        captured['attn_scores'] = attn_weights.detach().clone()

        # Apply causal mask if needed (for single token, just softmax)
        attn_probs = torch.softmax(attn_weights, dim=-1)

        captured['attn_probs'] = attn_probs.detach().clone()

        # Attention output
        attn_output = torch.matmul(attn_probs, value_states_expanded)

        captured['attn_output'] = attn_output.detach().clone()

        # Reshape back to (batch, seq_len, dim)
        attn_output = attn_output.transpose(1, 2).reshape(bsz, q_len, dim)

        # Output projection
        attn_output = layer1_attn.o_proj(attn_output)

        captured['o_proj'] = attn_output.detach().clone()

        return attn_output, None

    layer1_attn.forward = patched_forward

    # Run forward pass
    with torch.no_grad():
        input_ids = torch.tensor([[token_id]], dtype=torch.long)
        position_ids = torch.tensor([[0]], dtype=torch.long)
        _ = model(input_ids, position_ids=position_ids, output_hidden_states=True, return_dict=True)

    # Save all captured values
    print("\n=== Captured Intermediates ===\n")

    for name, tensor in captured.items():
        print_stats(name, tensor)
        flat = tensor.detach().squeeze().flatten().cpu().numpy()
        save_bin(flat, f"layer1_{name}.bin")

    print("\n" + "=" * 70)
    print("  Layer 1 attention intermediate data generated!")
    print("=" * 70)

if __name__ == "__main__":
    main()
