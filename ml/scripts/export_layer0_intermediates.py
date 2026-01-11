#!/usr/bin/env python3
"""
Export intermediate values from Qwen3 layer 0 for debugging.

This script exports:
1. after_rmsnorm_pre_attn.bin - After pre-attention RMSNorm
2. after_q_proj.bin - Q projection (before reshape)
3. after_k_proj.bin - K projection (before reshape)
4. after_v_proj.bin - V projection (before reshape)
5. after_qk_norm_q.bin - Q after QK-Norm
6. after_qk_norm_k.bin - K after QK-Norm
7. after_rope_q.bin - Q after RoPE
8. after_rope_k.bin - K after RoPE
9. after_attention.bin - Attention output
10. after_o_proj.bin - Output projection
11. after_attn_residual.bin - After attention residual add
12. after_rmsnorm_pre_ffn.bin - After pre-FFN RMSNorm
13. after_gate_proj.bin - Gate projection
14. after_up_proj.bin - Up projection
15. after_silu.bin - SiLU activation
16. after_ffn.bin - FFN output (down projection)
17. after_ffn_residual.bin - After FFN residual add (layer 0 output)

Usage:
    python export_layer0_intermediates.py <model_path> <output_dir>

Example:
    python export_layer0_intermediates.py ./Qwen2.5-0.5B-Instruct ./qwen3_reference/layer0_intermediates
"""

import torch
import struct
import os
import sys
from pathlib import Path
from transformers import AutoModelForCausalLM, AutoTokenizer

def save_tensor(tensor: torch.Tensor, filepath: str):
    """Save tensor to binary file with size prefix."""
    data = tensor.detach().cpu().float().numpy().flatten()
    with open(filepath, 'wb') as f:
        f.write(struct.pack('<i', len(data)))
        for val in data:
            f.write(struct.pack('<f', val))
    print(f"  Saved {filepath}: shape={tensor.shape}, size={len(data)}")

def main():
    if len(sys.argv) < 3:
        print("Usage: python export_layer0_intermediates.py <model_path> <output_dir>")
        sys.exit(1)

    model_path = sys.argv[1]
    output_dir = Path(sys.argv[2])
    output_dir.mkdir(parents=True, exist_ok=True)

    print(f"Loading model from {model_path}...")
    model = AutoModelForCausalLM.from_pretrained(model_path, torch_dtype=torch.float32)
    tokenizer = AutoTokenizer.from_pretrained(model_path)
    model.eval()

    # Get layer 0
    layer = model.model.layers[0]

    # Token "Hello" = 9707 in Qwen tokenizer
    token_id = 9707

    # Get embedding
    hidden_states = model.model.embed_tokens(torch.tensor([[token_id]]))
    print(f"Input embedding: shape={hidden_states.shape}")

    with torch.no_grad():
        # ========== Pre-attention RMSNorm ==========
        residual = hidden_states
        hidden_states = layer.input_layernorm(hidden_states)
        save_tensor(hidden_states[0, 0], output_dir / "after_rmsnorm_pre_attn.bin")

        # ========== Q/K/V Projections ==========
        attn = layer.self_attn
        bsz, q_len, _ = hidden_states.size()

        query_states = attn.q_proj(hidden_states)
        save_tensor(query_states[0, 0], output_dir / "after_q_proj.bin")

        key_states = attn.k_proj(hidden_states)
        save_tensor(key_states[0, 0], output_dir / "after_k_proj.bin")

        value_states = attn.v_proj(hidden_states)
        save_tensor(value_states[0, 0], output_dir / "after_v_proj.bin")

        # Reshape to (batch, num_heads, seq_len, head_dim)
        query_states = query_states.view(bsz, q_len, attn.num_heads, attn.head_dim).transpose(1, 2)
        key_states = key_states.view(bsz, q_len, attn.num_key_value_heads, attn.head_dim).transpose(1, 2)
        value_states = value_states.view(bsz, q_len, attn.num_key_value_heads, attn.head_dim).transpose(1, 2)

        # ========== QK-Norm ==========
        query_states = attn.q_norm(query_states)
        save_tensor(query_states[0, :, 0, :], output_dir / "after_qk_norm_q.bin")

        key_states = attn.k_norm(key_states)
        save_tensor(key_states[0, :, 0, :], output_dir / "after_qk_norm_k.bin")

        # ========== RoPE ==========
        # Get rotary embeddings
        position_ids = torch.arange(q_len, device=hidden_states.device).unsqueeze(0)
        cos, sin = attn.rotary_emb(value_states, position_ids)

        # Apply RoPE
        def rotate_half(x):
            x1 = x[..., : x.shape[-1] // 2]
            x2 = x[..., x.shape[-1] // 2 :]
            return torch.cat((-x2, x1), dim=-1)

        def apply_rotary_pos_emb(q, k, cos, sin, unsqueeze_dim=1):
            cos = cos.unsqueeze(unsqueeze_dim)
            sin = sin.unsqueeze(unsqueeze_dim)
            q_embed = (q * cos) + (rotate_half(q) * sin)
            k_embed = (k * cos) + (rotate_half(k) * sin)
            return q_embed, k_embed

        query_states, key_states = apply_rotary_pos_emb(query_states, key_states, cos, sin)
        save_tensor(query_states[0, :, 0, :], output_dir / "after_rope_q.bin")
        save_tensor(key_states[0, :, 0, :], output_dir / "after_rope_k.bin")

        # ========== Attention ==========
        # For single token, attention is simple
        # Expand KV for GQA
        n_rep = attn.num_heads // attn.num_key_value_heads
        key_states = key_states[:, :, None, :, :].expand(-1, -1, n_rep, -1, -1).reshape(bsz, attn.num_heads, q_len, attn.head_dim)
        value_states = value_states[:, :, None, :, :].expand(-1, -1, n_rep, -1, -1).reshape(bsz, attn.num_heads, q_len, attn.head_dim)

        # Compute attention
        attn_weights = torch.matmul(query_states, key_states.transpose(2, 3)) / (attn.head_dim ** 0.5)
        # For single token, no masking needed
        attn_weights = torch.nn.functional.softmax(attn_weights, dim=-1)
        attn_output = torch.matmul(attn_weights, value_states)

        # Reshape back
        attn_output = attn_output.transpose(1, 2).contiguous().reshape(bsz, q_len, -1)
        save_tensor(attn_output[0, 0], output_dir / "after_attention.bin")

        # ========== Output Projection ==========
        attn_output = attn.o_proj(attn_output)
        save_tensor(attn_output[0, 0], output_dir / "after_o_proj.bin")

        # ========== Attention Residual ==========
        hidden_states = residual + attn_output
        save_tensor(hidden_states[0, 0], output_dir / "after_attn_residual.bin")

        # ========== Pre-FFN RMSNorm ==========
        residual = hidden_states
        hidden_states = layer.post_attention_layernorm(hidden_states)
        save_tensor(hidden_states[0, 0], output_dir / "after_rmsnorm_pre_ffn.bin")

        # ========== SwiGLU FFN ==========
        mlp = layer.mlp

        gate = mlp.gate_proj(hidden_states)
        save_tensor(gate[0, 0], output_dir / "after_gate_proj.bin")

        up = mlp.up_proj(hidden_states)
        save_tensor(up[0, 0], output_dir / "after_up_proj.bin")

        gate_activated = torch.nn.functional.silu(gate)
        save_tensor(gate_activated[0, 0], output_dir / "after_silu.bin")

        down = mlp.down_proj(gate_activated * up)
        save_tensor(down[0, 0], output_dir / "after_ffn.bin")

        # ========== FFN Residual ==========
        hidden_states = residual + down
        save_tensor(hidden_states[0, 0], output_dir / "after_ffn_residual.bin")

    print(f"\nAll intermediate values exported to {output_dir}")
    print("\nTo use these in Java tests, update the REFERENCE_DIR path in the test classes.")

if __name__ == "__main__":
    main()
