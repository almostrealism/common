#!/usr/bin/env python3
"""
Export attention intermediates at position 1 for debugging autoregressive generation.

Processes tokens [9707, 271] ("Hello\n\n") and exports attention values at position 1
where the model attends to both position 0 (9707) and position 1 (271).

This helps debug why Java's position 1 output differs from PyTorch.
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

def main():
    model_path = "Qwen/Qwen2.5-0.5B-Instruct"
    output_dir = Path("/workspace/project/common/ml/qwen3_reference/position1_debug")
    output_dir.mkdir(parents=True, exist_ok=True)

    print(f"Loading model from {model_path}...")
    model = AutoModelForCausalLM.from_pretrained(model_path, dtype=torch.float32)
    tokenizer = AutoTokenizer.from_pretrained(model_path)
    model.eval()

    config = model.config
    num_heads = config.num_attention_heads
    num_kv_heads = config.num_key_value_heads
    head_dim = config.hidden_size // num_heads

    print(f"Config: num_heads={num_heads}, num_kv_heads={num_kv_heads}, head_dim={head_dim}")

    # Get layer 0
    layer = model.model.layers[0]
    attn = layer.self_attn

    # Tokens: 9707 ("Hello") at position 0, 271 ("\n\n") at position 1
    token_ids = torch.tensor([[9707, 271]])
    print(f"Processing tokens: {token_ids[0].tolist()}")

    with torch.no_grad():
        # Get embeddings for both tokens
        hidden_states = model.model.embed_tokens(token_ids)
        print(f"Input embeddings shape: {hidden_states.shape}")  # [1, 2, 896]

        # ========== Pre-attention RMSNorm ==========
        residual = hidden_states
        hidden_states = layer.input_layernorm(hidden_states)
        save_tensor(hidden_states[0], output_dir / "hidden_after_rmsnorm.bin")

        # ========== Q/K/V Projections for both positions ==========
        bsz, q_len, _ = hidden_states.size()  # 1, 2, 896

        query_states = attn.q_proj(hidden_states)  # [1, 2, 896]
        key_states = attn.k_proj(hidden_states)    # [1, 2, 128]
        value_states = attn.v_proj(hidden_states)  # [1, 2, 128]

        save_tensor(query_states[0], output_dir / "q_proj_both_positions.bin")
        save_tensor(key_states[0], output_dir / "k_proj_both_positions.bin")
        save_tensor(value_states[0], output_dir / "v_proj_both_positions.bin")

        # Reshape to (batch, num_heads, seq_len, head_dim)
        query_states = query_states.view(bsz, q_len, num_heads, head_dim).transpose(1, 2)
        key_states = key_states.view(bsz, q_len, num_kv_heads, head_dim).transpose(1, 2)
        value_states = value_states.view(bsz, q_len, num_kv_heads, head_dim).transpose(1, 2)

        print(f"Query shape: {query_states.shape}")  # [1, 14, 2, 64]
        print(f"Key shape: {key_states.shape}")      # [1, 2, 2, 64]
        print(f"Value shape: {value_states.shape}")  # [1, 2, 2, 64]

        # ========== QK-Norm (Qwen2.5-0.5B doesn't use QK-Norm) ==========
        has_qk_norm = hasattr(attn, 'q_norm') and attn.q_norm is not None
        if has_qk_norm:
            query_states = attn.q_norm(query_states)
            key_states = attn.k_norm(key_states)
            save_tensor(query_states[0, :, 1, :], output_dir / "q_norm_pos1.bin")
            save_tensor(key_states[0], output_dir / "k_norm_both.bin")
        else:
            print("(Skipping QK-Norm - not present in this model)")

        # ========== RoPE ==========
        position_ids = torch.arange(q_len, device=hidden_states.device).unsqueeze(0)
        rotary_emb = model.model.rotary_emb
        cos, sin = rotary_emb(value_states, position_ids)

        query_states, key_states = apply_rotary_pos_emb(query_states, key_states, cos, sin)

        save_tensor(query_states[0, :, 1, :], output_dir / "q_rope_pos1.bin")
        save_tensor(key_states[0], output_dir / "k_rope_both.bin")

        # ========== GQA Expansion ==========
        n_rep = num_heads // num_kv_heads  # 14 / 2 = 7
        key_states_expanded = key_states[:, :, None, :, :].expand(-1, -1, n_rep, -1, -1)
        key_states_expanded = key_states_expanded.reshape(bsz, num_heads, q_len, head_dim)
        value_states_expanded = value_states[:, :, None, :, :].expand(-1, -1, n_rep, -1, -1)
        value_states_expanded = value_states_expanded.reshape(bsz, num_heads, q_len, head_dim)

        save_tensor(key_states_expanded[0], output_dir / "k_expanded_gqa.bin")
        save_tensor(value_states_expanded[0], output_dir / "v_expanded_gqa.bin")

        # ========== Attention Scores ==========
        # Q @ K^T / sqrt(d_k)
        attn_weights = torch.matmul(query_states, key_states_expanded.transpose(2, 3)) / (head_dim ** 0.5)
        print(f"Attention weights shape: {attn_weights.shape}")  # [1, 14, 2, 2]

        save_tensor(attn_weights[0], output_dir / "attn_scores_before_mask.bin")

        # Print attention scores for position 1 query
        print("\nAttention scores for position 1 query (before mask/softmax):")
        for head in range(num_heads):
            scores = attn_weights[0, head, 1, :].tolist()
            print(f"  Head {head}: pos0={scores[0]:.4f}, pos1={scores[1]:.4f}")

        # ========== Causal Mask ==========
        # Create causal mask
        causal_mask = torch.triu(torch.ones((q_len, q_len), dtype=torch.bool), diagonal=1)
        causal_mask = causal_mask.unsqueeze(0).unsqueeze(0)  # [1, 1, 2, 2]
        attn_weights_masked = attn_weights.masked_fill(causal_mask, float('-inf'))

        save_tensor(attn_weights_masked[0], output_dir / "attn_scores_after_mask.bin")

        print("\nAttention scores for position 1 query (after causal mask):")
        for head in range(num_heads):
            scores = attn_weights_masked[0, head, 1, :].tolist()
            print(f"  Head {head}: pos0={scores[0]:.4f}, pos1={scores[1]:.4f}")

        # ========== Softmax ==========
        attn_weights_softmax = torch.nn.functional.softmax(attn_weights_masked, dim=-1)
        save_tensor(attn_weights_softmax[0], output_dir / "attn_weights_softmax.bin")

        print("\nAttention weights after softmax (position 1 query):")
        for head in range(num_heads):
            weights = attn_weights_softmax[0, head, 1, :].tolist()
            print(f"  Head {head}: w0={weights[0]:.4f}, w1={weights[1]:.4f}")

        # ========== Attention Output ==========
        attn_output = torch.matmul(attn_weights_softmax, value_states_expanded)
        print(f"\nAttention output shape: {attn_output.shape}")  # [1, 14, 2, 64]

        # Save position 1 attention output (what we care about)
        save_tensor(attn_output[0, :, 1, :], output_dir / "attn_output_pos1.bin")

        # Reshape back
        attn_output = attn_output.transpose(1, 2).contiguous().reshape(bsz, q_len, -1)

        # ========== Output Projection ==========
        attn_output = attn.o_proj(attn_output)
        save_tensor(attn_output[0, 1], output_dir / "o_proj_pos1.bin")

        # ========== Full Model Output for Position 1 ==========
        print("\n" + "="*60)
        print("Running full forward pass for comparison...")
        outputs = model(token_ids)
        logits = outputs.logits[0, 1, :]  # Position 1 logits

        top5 = torch.topk(logits, 5)
        print("Position 1 top 5 predictions:")
        for i, (idx, val) in enumerate(zip(top5.indices, top5.values)):
            token_text = tokenizer.decode([idx])
            print(f"  {i+1}. Token {idx.item()}: {repr(token_text)} (logit={val.item():.4f})")

        save_tensor(logits, output_dir / "logits_pos1.bin")

    print(f"\nAll intermediates exported to {output_dir}")

if __name__ == "__main__":
    main()
