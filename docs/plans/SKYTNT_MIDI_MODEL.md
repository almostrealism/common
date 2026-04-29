# SkyTNT midi-model Replication Plan

**Branch:** `feature/skytnt-midi-model`  
**License:** Apache-2.0  
**Source:** https://github.com/SkyTNT/midi-model  
**HuggingFace:** https://huggingface.co/skytnt/midi-model-tv2o-medium  
**Training data:** Los Angeles MIDI Dataset  
**Status:** Approved — Implementation Complete

---

## 1. Executive Summary

SkyTNT midi-model is a symbolic MIDI generation model based on a **dual-transformer LLaMA
architecture**. Unlike Moonbeam (which uses a custom MRA transformer + GRU decoder), this
model uses two standard LLaMA-style transformers in a two-stage generation scheme. This is
architecturally much simpler to replicate in Java/PDSL than Moonbeam was, because all the
primitives already exist.

The planned target variant is **tv2o-medium** (V2 tokenizer, optimised MIDI, medium size):
~933 MB on disk, vocab 3406, max sequence 4096 events × 8 tokens/event.

---

## 2. Model Architecture

### 2.1 Dual-Transformer Design

The model contains **two independent LLaMA transformers** and one shared LM head:

| Component        | Role                                                                 |
|------------------|----------------------------------------------------------------------|
| `net`            | Main sequence transformer — encodes/extends the full event sequence  |
| `net_token`      | Token transformer — autoregressively generates tokens within an event|
| `lm_head`        | Shared Linear(1024 → 3406, no bias) used by `net_token`             |

### 2.2 Main Transformer (`net`) — Hyperparameters

| Parameter              | Value        | Notes                                  |
|------------------------|--------------|----------------------------------------|
| Layers                 | 12           |                                        |
| Hidden size            | 1024         |                                        |
| FFN intermediate size  | 4096         | SwiGLU (gate_proj + up_proj + down_proj)|
| Attention heads (Q)    | 16           | MHA — equal Q and KV heads             |
| Attention heads (KV)   | 16           | No Grouped Query Attention             |
| Head dimension         | 64           | 1024 / 16                              |
| Vocab size             | 3406         |                                        |
| Max sequence length    | 4096         | 4096 events (not tokens)               |
| Positional encoding    | RoPE         | Standard theta=10000                   |
| Normalization          | RMSNorm      | Pre-norm (input_layernorm + post_attention_layernorm) |
| Activation             | SiLU (SwiGLU)|                                        |

### 2.3 Token Transformer (`net_token`) — Hyperparameters

| Parameter              | Value        | Notes                                  |
|------------------------|--------------|----------------------------------------|
| Layers                 | 3            |                                        |
| Hidden size            | 1024         | Same as `net`                          |
| FFN intermediate size  | 1024         | Smaller than main (1:1 ratio)          |
| Attention heads (Q)    | 4            |                                        |
| Attention heads (KV)   | 4            | MHA                                    |
| Head dimension         | 256          | 1024 / 4                               |
| Vocab size             | 3406         | Shared vocabulary                      |
| Max sequence length    | 4096         |                                        |
| Positional encoding    | RoPE         | Standard theta=10000                   |
| Normalization          | RMSNorm      |                                        |

### 2.4 Layer Structure (per block, both transformers)

Each transformer block is a standard LLaMA block:
```
input
 ├─ input_layernorm (RMSNorm)
 ├─ self_attn: Q/K/V projections + RoPE + scaled dot-product + O projection
 ├─ residual add
 ├─ post_attention_layernorm (RMSNorm)
 ├─ SwiGLU FFN: gate_proj(SiLU) * up_proj → down_proj
 └─ residual add
```

No QK-norm (unlike Qwen3). No bias on any projection. Standard causal masking.

### 2.5 Forward Pass Logic

The generation loop is unique: each MIDI event is represented as up to 8 tokens, but the
main transformer sees only one "position" per event (via summed token embeddings). The token
transformer then generates the tokens for the next event one at a time.

**Encoding step (outer loop, per new event position):**
```
embeddings = net.embed_tokens(event_tokens)   # [seq_len, max_token_seq, 1024]
x = embeddings.sum(dim=-2)                    # [seq_len, 1024]  — KEY OPERATION
hidden = net.forward(x)                       # [seq_len, 1024]
hidden_t = hidden[:, -1]                      # last position: [1024]
```

**Token generation step (inner loop, up to 8 steps per event):**
```
# Step 0: pass main-transformer hidden as prefix to net_token
logits = net_token.forward_token(hidden_state=hidden_t, x=None)
# Apply validity mask (only event-type tokens + EOS valid at step 0)
event_id = sample_top_p_k(softmax(logits / temp) * mask)

# Steps 1..N: autoregressively generate parameters for the selected event type
for i in 1..len(params_for_event):
    logits = net_token.forward_token(hidden_state=None, x=partial_tokens, cache=cache2)
    # Apply validity mask (only valid param values for param_names[i-1])
    next_token = sample_top_p_k(softmax(logits / temp) * mask)

# Pad to max_token_seq=8 with pad_id
```

**What PDSL handles:** Both LLaMA transformer bodies (attention + FFN + norm + residual).

**What Java handles:** The outer generation loop, the inner token-generation loop, KV cache
management, validity mask application, sampling, and embedding sum (preprocessing).

### 2.6 Comparison with Moonbeam

| Aspect                    | Moonbeam MIDI                        | SkyTNT midi-model                   |
|---------------------------|--------------------------------------|--------------------------------------|
| Transformer architecture  | Custom MRA (Multidimensional RoPE)   | Standard LLaMA (two instances)       |
| Decoder                   | 4-layer GRU + lm_head                | 3-layer LLaMA transformer (`net_token`) |
| Positional encoding       | Per-head-group theta via HeadGroupConfig | Standard RoPE theta=10000         |
| PDSL fit                  | Required custom GRU and MRA          | Perfect fit — all primitives exist   |
| Tokenization              | Compound 6-attribute tuples (FME)    | Flat event-based tokens              |
| Output tokens/position    | 7 compound attributes                | Up to 8 plain token IDs              |
| Vocab size                | ~300 (compound attribute ranges)     | 3406 (flat ID space)                 |
| Java complexity           | High (HeadGroupConfig, GRU loop)     | Medium (dual transformer, sum embed) |

---

## 3. Tokenization Scheme (V2)

### 3.1 Vocabulary Layout

Total vocabulary: **3406 tokens**, allocated sequentially:

| Range      | Count | Meaning                             |
|------------|-------|-------------------------------------|
| 0          | 1     | PAD (padding)                       |
| 1          | 1     | BOS (beginning of sequence)         |
| 2          | 1     | EOS (end of sequence)               |
| 3–8        | 6     | Event type tokens                   |
| 9–136      | 128   | `time1` parameter values (0–127)    |
| 137–152    | 16    | `time2` parameter values (0–15)     |
| 153–2200   | 2048  | `duration` parameter values         |
| 2201–2328  | 128   | `track` parameter values            |
| 2329–2344  | 16    | `channel` parameter values          |
| 2345–2472  | 128   | `pitch` parameter values (MIDI 0–127) |
| 2473–2600  | 128   | `velocity` parameter values         |
| 2601–2728  | 128   | `patch` parameter values (GM 0–127) |
| 2729–2856  | 128   | `controller` parameter values       |
| 2857–2984  | 128   | `value` (CC value)                  |
| 2985–3368  | 384   | `bpm` parameter values (0–383)      |
| 3369–3384  | 16    | `nn` (time signature numerator −1)  |
| 3385–3388  | 4     | `dd` (time signature denominator −1)|
| 3389–3403  | 15    | `sf` (key signature +7 offset)      |
| 3404–3405  | 2     | `mi` (major=0/minor=1)              |

### 3.2 Event Types and Their Tokens

Each event is represented as a sequence of up to 8 token IDs, padded with PAD (0):

| Event type       | Token 0 | Tokens 1..7 (parameters)                       | Total tokens |
|------------------|---------|-------------------------------------------------|--------------|
| `note`           | event_id | time1, time2, track, channel, pitch, velocity, duration | 8 |
| `patch_change`   | event_id | time1, time2, track, channel, patch            | 6 (+ 2 PAD) |
| `control_change` | event_id | time1, time2, track, channel, controller, value | 7 (+ 1 PAD) |
| `set_tempo`      | event_id | time1, time2, track, bpm                       | 5 (+ 3 PAD) |
| `time_signature` | event_id | time1, time2, track, nn, dd                    | 6 (+ 2 PAD) |
| `key_signature`  | event_id | time1, time2, track, sf, mi                    | 6 (+ 2 PAD) |

BOS and EOS events are represented as `[bos_id=1, pad, pad, ..., pad]` and `[eos_id=2, pad, ...]`.

### 3.3 Timing Representation

- **time1** (0–127): coarse time in beats. Stored as delta from previous `time1`.
- **time2** (0–15): fine time within a beat (quantized to 1/16th beat units).
- **duration** (1–2047): note duration in 1/16th beat units.
- BPM range: 0–383, where value = BPM (clamped at 383).

Quantization: `t = round(16 * tick / ticks_per_beat)` → `time1 = t // 16`, `time2 = t % 16`.

### 3.4 Comparison with Moonbeam Tokenization

Moonbeam uses compound 6-attribute tuples (type, beat, pitch, duration, velocity, program)
with Fundamental Music Embedding (FME) — continuous sinusoidal embeddings summed across
attributes. SkyTNT V2 uses discrete flat tokens per attribute with summed integer embeddings.

Both approaches sum embeddings across the attribute dimension before the transformer, but:
- Moonbeam: continuous embeddings via sinusoidal FME, summed across 6 heads
- SkyTNT: discrete token lookup embeddings, summed across up to 8 token positions

### 3.5 Java Tokenizer Requirements

A new `SkyTntTokenizerV2` class is needed in `engine/ml/src/main/java/org/almostrealism/ml/midi/`.
It must:
1. Allocate token IDs sequentially in the order described in §3.1
2. Implement `tokenize(List<MidiNoteEvent>)` → `int[][]` (seq_len × 8)
3. Implement `detokenize(int[][])` → `List<MidiNoteEvent>`
4. Implement validity mask lookup: given event type at position 0, return valid token IDs for positions 1..N
5. Handle BOS/EOS injection

The tokenizer must reside in the `ml` module (not `studio/music`) since it is part of the
ML inference pipeline, not pattern composition. The `studio/music/midi/MidiNoteEvent` type
can be used as the intermediate representation at the boundary.

---

## 4. Weight Format and Conversion Plan

### 4.1 Weight Files

The HuggingFace repo (`skytnt/midi-model-tv2o-medium`) contains:
- `model.safetensors` — primary format (preferred for extraction)
- `pytorch_model.bin` — fallback PyTorch format
- `config.json` — full model and tokenizer configuration

### 4.2 Weight Key Names (LLaMA HuggingFace Convention)

**Main transformer (`net`):**
```
net.embed_tokens.weight                              [3406, 1024]
net.layers.{i}.self_attn.q_proj.weight               [1024, 1024]   i = 0..11
net.layers.{i}.self_attn.k_proj.weight               [1024, 1024]
net.layers.{i}.self_attn.v_proj.weight               [1024, 1024]
net.layers.{i}.self_attn.o_proj.weight               [1024, 1024]
net.layers.{i}.mlp.gate_proj.weight                  [4096, 1024]
net.layers.{i}.mlp.up_proj.weight                    [4096, 1024]
net.layers.{i}.mlp.down_proj.weight                  [1024, 4096]
net.layers.{i}.input_layernorm.weight                [1024]
net.layers.{i}.post_attention_layernorm.weight       [1024]
net.norm.weight                                      [1024]
```

**Token transformer (`net_token`):**
```
net_token.embed_tokens.weight                        [3406, 1024]
net_token.layers.{i}.self_attn.q_proj.weight         [1024, 1024]   i = 0..2
net_token.layers.{i}.self_attn.k_proj.weight         [1024, 1024]
net_token.layers.{i}.self_attn.v_proj.weight         [1024, 1024]
net_token.layers.{i}.self_attn.o_proj.weight         [1024, 1024]
net_token.layers.{i}.mlp.gate_proj.weight            [1024, 1024]
net_token.layers.{i}.mlp.up_proj.weight              [1024, 1024]
net_token.layers.{i}.mlp.down_proj.weight            [1024, 1024]
net_token.layers.{i}.input_layernorm.weight          [1024]
net_token.layers.{i}.post_attention_layernorm.weight [1024]
net_token.norm.weight                                [1024]
```

**Shared LM head:**
```
lm_head.weight                                       [3406, 1024]
```

**Total weight keys:** 3 + 12×10 + 1 + 3×10 = 154 tensors.

### 4.3 Weight Extraction Script

A Python script `engine/ml/src/main/python/extract_skytnt_weights.py` following the
pattern of the existing Moonbeam extraction script. It should:
1. Load from `model.safetensors` using the `safetensors` library
2. Export each tensor to a `.pb` protobuf file using the `StateDictionary` format
3. Handle bfloat16→float32 conversion if needed
4. Organize output into `net/`, `net_token/`, and `lm_head.pb`

### 4.4 StateDictionary Mapping

The Java `StateDictionary.get(key)` pattern used in `Qwen3.java` maps directly:
```java
stateDict.get("net.layers.0.self_attn.q_proj.weight")
stateDict.get("net_token.layers.0.self_attn.q_proj.weight")
stateDict.get("lm_head.weight")
```

No weight renaming is needed — the HuggingFace key names are used verbatim.

---

## 5. PDSL File Design

### 5.1 Overview

Both LLaMA transformers map perfectly to the existing PDSL primitives. The key primitives
already in `PdslInterpreter` that this model uses:
- `rmsnorm(weights, epsilon)` — for input_layernorm and post_attention_layernorm
- `attention(heads, ...)` — for self-attention with RoPE
- `dense(weights)` — for all linear projections
- `silu()` — for SwiGLU gate activation
- `product(blockA, blockB)` — for SwiGLU gate × up projection
- `accum { ... }` — for residual connections
- `embedding(table)` — for token embedding lookup

### 5.2 Proposed File: `skytnt_main_block.pdsl`

Location: `engine/ml/src/main/resources/pdsl/midi/skytnt_main_block.pdsl`

```pdsl
// SkyTNT Main Transformer Block
// Standard LLaMA block: RMSNorm + Attention + Residual + RMSNorm + SwiGLU FFN + Residual
// Used for the 12-layer main transformer (net).
// Input/output shape: [1, 1024]

layer skytnt_ffn(
    norm_weights: weight,
    gate_proj: weight,
    up_proj: weight,
    down_proj: weight,
    epsilon: float
) -> [1, dim] {
    rmsnorm(norm_weights, epsilon)
    product(
        { dense(gate_proj); silu() },
        { dense(up_proj) }
    )
    dense(down_proj)
}

layer skytnt_main_block(
    heads: int,
    rms_att_weight: weight,
    wq: weight,
    wk: weight,
    wv: weight,
    wo: weight,
    freq_cis: weight,
    position: scalar,
    rms_ffn_weight: weight,
    gate_proj: weight,
    up_proj: weight,
    down_proj: weight,
    epsilon: float
) -> [1, dim] {
    accum {
        attention(heads, rms_att_weight,
                  wk, wv, wq, wo,
                  freq_cis, position)
    }
    accum {
        skytnt_ffn(rms_ffn_weight, gate_proj, up_proj, down_proj, epsilon)
    }
}
```

### 5.3 Proposed File: `skytnt_token_block.pdsl`

Location: `engine/ml/src/main/resources/pdsl/midi/skytnt_token_block.pdsl`

Identical structure to `skytnt_main_block.pdsl` but used for the 3-layer token transformer.
The PDSL definition is the same; the difference is in the hyperparameter values passed at
load time (heads=4 instead of 16, smaller FFN weights). This may reuse the same `.pdsl`
file with different config, or be a separate file if it improves clarity.

### 5.4 Proposed File: `skytnt_lm_head.pdsl`

```pdsl
// Final norm + LM head projection
// Projects hidden state [1024] → logits [3406]

layer skytnt_output(
    norm_weights: weight,
    lm_head: weight,
    epsilon: float
) -> [1, vocab_size] {
    rmsnorm(norm_weights, epsilon)
    dense(lm_head)
}
```

### 5.5 Required DSL Extensions

**None are strictly required.** The standard LLaMA architecture maps to existing PDSL
primitives. This is the primary advantage over Moonbeam, which required new GRU primitives
(`lerp`, `concat_blocks`, `accum_blocks`, `slice`).

**One optional enhancement** would be useful but not required: a `sum_pool(axis)` primitive
for the embedding sum operation `x = embed_tokens(tokens).sum(dim=-2)`. Currently this must
be done in Java before passing to the PDSL-compiled block. If added, it would allow a full
"embedding → sum → transformer" pipeline to be expressed in a single PDSL model definition.

### 5.6 What Cannot Be Expressed in PDSL

The following must remain in Java:

1. **Embedding sum**: `x = embed_tokens(event_tokens).sum(dim=-2)` — reduces a [seq, 8, 1024]
   tensor to [seq, 1024]. No PDSL primitive for reduction over a non-batch dimension.

2. **Inner token generation loop**: The loop over up to 8 token positions with data-dependent
   control flow (which parameters are valid depends on which event type was selected at step 0).

3. **KV cache management**: Separate caches for `net` (cache1) and `net_token` (cache2),
   with cache2 reset on every new event position.

4. **Validity masking**: Per-step logical masking of the logit tensor based on which token
   IDs are valid for the current parameter position.

5. **Sampling (top-p + top-k)**: Probabilistic token selection. As in Moonbeam's GRU decoder,
   this is computed over `double[]` in Java.

---

## 6. Java Implementation Plan

### 6.1 New Classes (all in `engine/ml/src/main/java/org/almostrealism/ml/midi/`)

Following the `CLAUDE.md` module placement rule: MIDI model classes belong in `engine/ml`,
not `studio/music`. The `studio/music/midi/MidiNoteEvent` type is used as the boundary
between the pattern system and the model.

**`SkyTntConfig.java`**
```java
public class SkyTntConfig {
    public final int vocabSize = 3406;
    public final int netLayers = 12;
    public final int netHiddenSize = 1024;
    public final int netHeads = 16;
    public final int netIntermediateSize = 4096;
    public final int netTokenLayers = 3;
    public final int netTokenHeads = 4;
    public final int netTokenIntermediateSize = 1024;
    public final int maxEventSeqLen = 4096;
    public final int maxTokenSeq = 8;
    public final int padId = 0;
    public final int bosId = 1;
    public final int eosId = 2;
    public final double ropeTheta = 10000.0;
    public final double rmsNormEps = 1e-5;
}
```

**`SkyTntTokenizerV2.java`**
- Allocates token ID ranges matching the Python V2 tokenizer exactly
- `tokenize(List<MidiNoteEvent>, boolean addBosEos)` → `int[][]` (rows of 8 IDs each)
- `detokenize(int[][])` → `List<MidiNoteEvent>`
- `getValidTokenIds(int step, int eventTypeId)` → `int[]` for validity masking
- `getEventIds()` → map of event name to token ID
- `getParameterIds(String paramName)` → range of valid token IDs for parameter

**`SkyTntMidi.java`**
Main model class following the `Qwen3` + `MoonbeamMidi` pattern:
- Loads `StateDictionary` from weights directory
- Builds two PDSL-compiled models: `netModel` (12 blocks) and `netTokenModel` (3 blocks)
- Computes RoPE frequency tables for both transformers
- Holds embedding tables: `netEmbedTokens` and `netTokenEmbedTokens` as `PackedCollection`
- Provides `generate(int[][] prompt, int maxLen, double temp, double topP, int topK)` → `int[][]`
- Provides `generateFromMidiEvents(List<MidiNoteEvent>)` → `List<MidiNoteEvent>`

**Note on class responsibility:** `SkyTntMidi.java` orchestrates the generation loop but all
transformer math stays in PDSL. The `generateFromMidiEvents` method does:
1. Tokenize input events via `SkyTntTokenizerV2`
2. Run embedding + sum for context
3. Run `netModel` to get hidden states
4. For each new position: run inner token loop via `netTokenModel`
5. Detokenize output tokens

### 6.2 Reuse from Existing Infrastructure

| Existing class             | How it is reused                                              |
|----------------------------|---------------------------------------------------------------|
| `StateDictionary`          | Weight loading, identical to Qwen3/Moonbeam usage            |
| `AttentionFeatures`        | Via PDSL interpreter — attention built-in calls these methods |
| `RotationFeatures`         | Via PDSL interpreter — rope_rotation built-in                 |
| `PdslLoader` / `PdslInterpreter` | Load and compile PDSL files, identical to GRU decoder usage |
| `AutoregressiveModel`      | May be used for the outer generation loop, or bypassed if the dual-transformer coordination requires custom Java |
| `MoonbeamMidi` (reference) | Pattern for PDSL-based model assembly and weight loading       |
| `Qwen3` (reference)        | Pattern for LLaMA-style attention block assembly               |
| `studio/music/midi/MidiNoteEvent` | Input/output boundary type for PatternSystemManager integration |

### 6.3 What Does NOT Need to Be Built

- No new attention variant (standard MHA is already in `AttentionFeatures`)
- No new positional encoding (standard RoPE theta=10000 is already in `RotationFeatures`)
- No new normalization (RMSNorm already in PDSL)
- No new FFN type (SwiGLU already in `transformer_block.pdsl`)
- No new Java GRU or custom decoder

---

## 7. Inference Pipeline

### 7.1 Generation Algorithm (Java Pseudocode)

```java
// Outer loop: generate one MIDI event per iteration
while (curLen < maxLen) {
    // Step A: Encode context via main transformer
    // (embedding sum is done before passing to PDSL compiled model)
    PackedCollection summedEmbeds = embedAndSum(inputTokens, curLen);
    PackedCollection hidden = netModel.forward(summedEmbeds);      // [curLen, 1024]
    PackedCollection hiddenLast = hidden.range(shape(1, 1024), (curLen-1)*1024);

    // Step B: Inner loop — generate tokens for new event
    int[] nextTokenSeq = new int[MAX_TOKEN_SEQ];
    Arrays.fill(nextTokenSeq, PAD_ID);
    String eventName = null;
    netTokenCache.reset();

    for (int i = 0; i < MAX_TOKEN_SEQ; i++) {
        // Compute logits via net_token
        PackedCollection logits;
        if (i == 0) {
            logits = netTokenModel.forwardWithPrefix(hiddenLast);  // hidden as prefix
        } else {
            logits = netTokenModel.forward(nextTokenSeq[i-1]);     // cached
        }

        // Apply validity mask
        int[] validIds = tokenizer.getValidTokenIds(i, i == 0 ? -1 : eventTypeId);
        logits = applyMask(logits, validIds);                      // zero out invalid

        // Sample (top-p + top-k)
        int token = sampleTopPK(softmax(logits, temp), topP, topK);

        if (i == 0) {
            if (token == EOS_ID) break outerLoop;
            eventName = tokenizer.getEventName(token);
            eventTypeId = token;
        } else {
            if (i > tokenizer.paramCount(eventName)) {
                nextTokenSeq[i] = PAD_ID;
                continue;
            }
        }
        nextTokenSeq[i] = token;
    }

    // Append new event to sequence
    inputTokens = append(inputTokens, nextTokenSeq);
    curLen++;
}
```

### 7.2 Sampling Strategy

Following the Python implementation:
- **Temperature**: `temp=1.0` default (divide logits before softmax)
- **Top-p (nucleus)**: `top_p=0.98` default — keep tokens until cumulative probability ≥ p
- **Top-k**: `top_k=20` default — keep only top-20 tokens
- Applied jointly: sort by descending prob, mask both cumulative-p and k, renormalize, sample

### 7.3 KV Cache

Two separate KV caches:
- `cache1` — for `net` (main transformer). Grows across the full sequence. Only the new
  event position is passed to `net` on each step; prior positions are served from cache.
- `cache2` — for `net_token` (token transformer). Reset to empty on every new event position.
  Within an event, the 8-step inner loop builds cache2 step by step.

This matches `AutoregressiveModel`'s existing KV cache pattern.

---

## 8. Integration with PatternSystemManager

### 8.1 Integration Point

`PatternSystemManager.toMidiEvents(AudioSceneContext context)` returns
`List<org.almostrealism.music.midi.MidiNoteEvent>`. This is the output of the existing
algorithmic system and is the prompt input to SkyTNT.

The pipeline:
```
PatternSystemManager
  .toMidiEvents(context)           →  List<MidiNoteEvent>  (chords, rhythm, structure)
    ↓
SkyTntTokenizerV2
  .tokenize(events)                →  int[][]  (BOS + event tokens)
    ↓
SkyTntMidi
  .generate(tokens, maxLen, ...)   →  int[][]  (extended token sequence)
    ↓
SkyTntTokenizerV2
  .detokenize(outputTokens)        →  List<MidiNoteEvent>  (includes prompt + generated)
    ↓
Filter / post-process:
  Strip prompt events               →  generated-only events
  Assign to new track/channel       →  (leads, embellishments, counter-melodies)
    ↓
MidiFileReader
  .write(events, outFile)          →  .mid file output
```

### 8.2 Prompt Construction

The PatternSystemManager output should be converted to SkyTNT tokens with:
1. BOS token as first event
2. `patch_change` event for each instrument channel present in the pattern output
3. `note` events from `MidiNoteEvent` list, sorted by onset time, quantized to 1/16th beat
4. No EOS appended — the model continues from the end of the prompt

The model will then generate complementary material (leads, embellishments) on new channels
that are not occupied by the prompt.

### 8.3 Channel Assignment Strategy

The pattern system typically uses channels 0–3 for chord/rhythm instruments. SkyTNT can
be guided to generate on higher channels (4–9) either by:
- Truncating the prompt to only include specific channels
- Post-filtering generated events to keep only newly introduced channels

### 8.4 Future Integration Class

A future class `SkyTntPatternAugmenter` in `studio/compose` (not `engine/ml`) would own
the end-to-end integration logic. Per `CLAUDE.md` module placement: composition/arrangement
logic belongs in `studio/`, ML model logic belongs in `engine/ml/`.

---

## 9. Weight Extraction Script Design

```python
# engine/ml/src/main/python/extract_skytnt_weights.py
#
# Usage:
#   python extract_skytnt_weights.py skytnt/midi-model-tv2o-medium ./weights_output
#
# Output structure:
#   weights_output/
#     net/
#       embed_tokens.weight.pb
#       layers/
#         0/
#           self_attn.q_proj.weight.pb
#           ...
#         11/
#           ...
#       norm.weight.pb
#     net_token/
#       ... (same structure, 3 layers)
#     lm_head.weight.pb

from safetensors import safe_open
import struct, os

def save_pb(tensor, path):
    # Convert to float32, serialize to StateDictionary protobuf format
    ...

def main(model_id, output_dir):
    with safe_open(f"{model_id}/model.safetensors", framework="pt") as f:
        for key in f.keys():
            tensor = f.get_tensor(key).float()
            save_pb(tensor, os.path.join(output_dir, key.replace(".", "/") + ".pb"))
```

---

## 10. Ordered Implementation Milestones

### Milestone 1: Tokenizer (no model needed)
1. Implement `SkyTntTokenizerV2.java` with exact V2 token ID allocation
2. Test: `tokenize → detokenize` round-trip for all 6 event types
3. Test: BOS/EOS injection, padding to max_token_seq=8
4. Test: validity mask lookup for each parameter position

### Milestone 2: Weight Extraction Script
1. Write `extract_skytnt_weights.py`
2. Test locally: verify output `.pb` files load via `StateDictionary`
3. Verify weight shapes match §4.2 table

### Milestone 3: PDSL Files
1. Write `skytnt_main_block.pdsl` (12-layer LLaMA block)
2. Write `skytnt_token_block.pdsl` (3-layer LLaMA block) — may reuse same file
3. Write `skytnt_lm_head.pdsl`
4. Test: `PdslLoaderTest`-style synthetic test — construct both transformers with random weights, run forward pass, verify output shapes

### Milestone 4: Model Assembly
1. Implement `SkyTntConfig.java`
2. Implement `SkyTntMidi.java` — load weights, build both PDSL-compiled transformers
3. Test: synthetic test with random weights, verify the two-transformer forward pass produces correct output shapes without crashing

### Milestone 5: Generation Loop
1. Implement embedding sum preprocessing in `SkyTntMidi`
2. Implement inner token generation loop with validity masking and top-p/top-k sampling
3. Implement KV cache coordination between `net` (growing cache) and `net_token` (reset per event)
4. Test: unconditional generation from BOS, verify output is valid token sequence
5. Test: generation from MIDI prompt, verify generated tokens extend the prompt

### Milestone 6: Numerical Validation
1. Load real weights, generate from BOS with temp=1.0, top_p=0.98, top_k=20
2. Compare first N generated tokens against Python reference implementation output
3. Accept ≤ 5% token divergence at top-1 (sampling introduces variance; validate logits directly)

### Milestone 7: PatternSystemManager Integration
1. Test: take existing `PatternSystemManager.toMidiEvents()` output, tokenize, feed to model
2. Verify generated events decode to valid MIDI (no out-of-range parameters)
3. Write output to `.mid` file via `MidiFileReader`
4. Demo: listen to result — does it sound like a complementary layer over the pattern output?

---

## 11. Risks and Mitigations

### Risk 1: Two-transformer generation loop complexity
**Description:** The inner loop (net_token autoregressively generating up to 8 tokens per
event, with cache2 reset every event) is more complex than the Moonbeam GRU loop. The KV
cache for `net_token` must be managed carefully — it grows during the inner loop but resets
between events.

**Mitigation:** Model the inner loop closely on `GRUDecoder.runGruDecode()` and
`GRUDecoder.gruStep()`. The pattern is established. Validate each step independently.

### Risk 2: Embedding sum operation
**Description:** The `sum(embed_tokens(x), dim=-2)` operation reduces a [seq, 8, 1024]
tensor to [seq, 1024]. This is not a PDSL primitive and must be done in Java, requiring
careful tensor management via `PackedCollection`.

**Mitigation:** Implement as a tight Java loop over the 8 token positions using
`PackedCollection.add()` or equivalent. Since this is on integer embeddings (lookup table),
it is not an ML computation and does not violate the GPU memory model policy.
Mark it explicitly in comments.

### Risk 3: Model size and memory
**Description:** The full model is ~933 MB. Loading + compiling two LLaMA transformers (12
+ 3 layers) may exceed default memory limits.

**Mitigation:** Set `AR_HARDWARE_MEMORY_SCALE=6` (16 GB) for tests. The model is smaller
than Qwen3-4B (which was also successfully compiled), so this should be manageable.

### Risk 4: Token validity masking performance
**Description:** The Python implementation constructs a full-vocabulary mask tensor on every
step. In Java, this needs to be efficient.

**Mitigation:** Precompute all validity masks at tokenizer construction time. Each
(step, event_type) pair has a fixed valid set. Store as `int[][]` lookup table.

### Risk 5: head_size=256 for net_token
**Description:** The token transformer has only 4 attention heads with head_size=256.
This is unusually large and may require verification that `AttentionFeatures.attention()`
handles it correctly.

**Mitigation:** Confirm in synthetic test (Milestone 4). Head size is a parameter, not
hardcoded, so it should work.

### Risk 6: KV cache for net across the full sequence
**Description:** The main transformer (`net`) processes sequences up to 4096 events long
(each event = one position). With KV cache, this grows indefinitely during generation.

**Mitigation:** Implement a maximum context window cutoff (e.g., 2048 events) with sliding
window truncation if needed. For initial implementation, cap generation at 512 events.

---

## 12. What We Reuse vs. Build New

### Fully Reused (no changes needed)

| Component              | Location                        | Notes                                       |
|------------------------|---------------------------------|---------------------------------------------|
| `StateDictionary`      | `engine/ml`                     | Identical protobuf weight loading           |
| `AttentionFeatures`    | `engine/ml`                     | Standard MHA via PDSL `attention()` built-in|
| `RotationFeatures`     | `engine/ml`                     | Standard RoPE via PDSL `rope_rotation()`    |
| `PdslLoader`           | `engine/ml/dsl`                 | Unchanged                                   |
| `PdslInterpreter`      | `engine/ml/dsl`                 | All required primitives already exist       |
| `transformer_block.pdsl` | `engine/ml/resources/pdsl`    | Reference for new PDSL files                |
| `MidiFileReader`       | `engine/ml/midi`                | Used for final MIDI output                  |
| `studio/music/midi/MidiNoteEvent` | `studio/music`       | Input boundary type                         |

### Built New (this branch)

| Component                | Location                    | Notes                                        |
|--------------------------|-----------------------------|----------------------------------------------|
| `SkyTntConfig.java`      | `engine/ml/midi`            | Config constants                             |
| `SkyTntTokenizerV2.java` | `engine/ml/midi`            | New event-based tokenizer (not reusable from Moonbeam) |
| `SkyTntMidi.java`        | `engine/ml/midi`            | Model class: weight loading + generation loop|
| `skytnt_main_block.pdsl` | `engine/ml/resources/pdsl/midi` | 12-layer LLaMA block                     |
| `skytnt_token_block.pdsl`| `engine/ml/resources/pdsl/midi` | 3-layer LLaMA block (may reuse main)     |
| `skytnt_lm_head.pdsl`    | `engine/ml/resources/pdsl/midi` | Final norm + projection                  |
| `extract_skytnt_weights.py` | `engine/ml/src/main/python` | Weight extraction script                 |

### Not Reused (Moonbeam-specific, not applicable)

| Component                     | Why not reused                                            |
|-------------------------------|-----------------------------------------------------------|
| `CompoundMidiEmbedding`       | Moonbeam's 6-attribute FME embeddings — SkyTNT uses flat integer embeddings |
| `FundamentalMusicEmbedding`   | Moonbeam-specific continuous sinusoidal embedding        |
| `HeadGroupConfig`             | Moonbeam's MRA per-head RoPE — SkyTNT uses standard RoPE |
| `GRUDecoder` / `gru_*.pdsl`   | Moonbeam's GRU decoder — SkyTNT uses LLaMA `net_token`  |
| `MidiTokenizer` (Moonbeam)    | Completely different token scheme                        |
| `MoonbeamConfig`              | Moonbeam-specific hyperparameters                        |

---

## 13. Key Simplifications vs. Moonbeam

This replication is significantly simpler than Moonbeam for these reasons:

1. **Standard architecture**: No custom MRA, no GRU, no HeadGroupConfig. Both transformers
   are vanilla LLaMA blocks that map directly to `transformer_block.pdsl`.

2. **No new PDSL primitives required**: All needed operations (`rmsnorm`, `attention`,
   `product`, `silu`, `dense`) are already implemented in `PdslInterpreter`. Moonbeam
   required adding `lerp`, `slice`, `concat_blocks`, `accum_blocks`, `tanh_act`, `sigmoid`.

3. **Flat tokenization**: The V2 tokenizer assigns sequential integer IDs. No continuous
   sinusoidal embeddings (FME) to replicate. The Java tokenizer is straightforward.

4. **No LoRA required for initial replication**: Moonbeam needed LoRA fine-tuning because
   the base model was not strong for MIDI continuation. SkyTNT was trained directly on the
   LA MIDI Dataset for continuation/generation. Base weights should work well.

5. **Weight names are standard**: HuggingFace LLaMA naming convention maps cleanly to
   `StateDictionary.get()` calls. No renaming or reshaping required (unlike Moonbeam's
   stacked GRU weight matrices).

6. **safetensors format**: The primary weight file is `model.safetensors`, which is easier
   to load in Python than Moonbeam's format.

The main new complexity is the **dual-transformer coordination** (two separate compiled
models, two separate KV caches, inner token-generation loop). But this is well-understood
Java-side orchestration, not a new PDSL primitive challenge.

---

## 14. Open Questions

1. **net_token embedding reuse**: In the Python code, `net_token.embed_tokens` is a separate
   embedding table from `net.embed_tokens`. Are these tied in any checkpoint variant, or
   always independent? Needs verification from the saved weights.

2. **lm_head weight tying**: Is `lm_head.weight` tied to `net.embed_tokens.weight`? The
   Python code uses a separate `nn.Linear` for `lm_head`. Verify from the checkpoint.

3. **RoPE theta for net_token**: LlamaConfig defaults to theta=10000 and that is what
   `config.json` implies. Confirm that `net_token` uses the same theta as `net`.

4. **Sequence length in practice**: How many events does a typical MIDI file encode?
   Understanding typical sequence lengths will inform KV cache strategy and
   `AR_HARDWARE_MEMORY_SCALE` requirements.

5. **Batching**: The Python implementation supports batch_size > 1. The initial Java
   implementation will target batch_size=1 only.
