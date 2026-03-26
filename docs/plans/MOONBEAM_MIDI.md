# Moonbeam MIDI Foundation Model - Replication Plan

## Overview

Replicate the [Moonbeam MIDI Foundation Model](https://github.com/guozixunnicolas/Moonbeam-MIDI-Foundation-Model) using the existing Almost Realism Java ML infrastructure. Moonbeam is a ~200M parameter LLaMA-style transformer for symbolic music (MIDI) that introduces two novel ideas:

1. **Compound MIDI tokenization** with 6 parallel music attributes (onset, duration, octave, pitch class, instrument, velocity)
2. **Multidimensional Relative Attention (MRA)** where attention heads are partitioned into groups, each applying RoPE with attribute-specific position IDs and theta bases

This is NOT a Python port. All code uses the existing ar-ml transformer stack, protobuf weight format, and Java training/inference pipeline.

**Reference paper:** [arXiv:2505.15559](https://arxiv.org/pdf/2505.15559)
**Reference checkpoints:** [HuggingFace](https://huggingface.co/guozixunnicolas/moonbeam-midi-foundation-model)

---

## 1. Inventory of Existing ar-ml Capabilities

### What We Have

| Capability | Class/Interface | Location |
|---|---|---|
| **Transformer blocks** | `AttentionFeatures.transformer()` | `engine/ml/.../AttentionFeatures.java` |
| **GQA attention** | `AttentionFeatures.attention()` | Supports heads != kvHeads |
| **RoPE** | `RotationFeatures.ropeRotation()`, `computeRopeFreqs()` | `engine/ml/.../RotationFeatures.java` |
| **QK-Norm** | `AttentionFeatures.attention()` (qkNormQ/qkNormK params) | Optional per-head norm |
| **RMSNorm** | `LayerFeatures.rmsnorm()` | `domain/graph/.../LayerFeatures.java` |
| **SwiGLU FFN** | `AttentionFeatures.transformer()` (w1, w2, w3) | Part of transformer block |
| **Dense/Linear** | `LayerFeatures.dense()` | Standard projection layers |
| **Embedding lookup** | `LayerFeatures.embedding()` | Token embeddings |
| **Softmax** | `LayerFeatures.softmax()` | Output layer |
| **Causal masking** | Built into attention methods | Automatic for autoregressive |
| **Autoregressive generation** | `AutoregressiveModel` | `engine/ml/.../AutoregressiveModel.java` |
| **KV cache** | Managed by `CompiledModel` | Transparent caching |
| **Model composition** | `Model`, `Block`, `SequentialBlock` | `domain/graph/.../model/` |
| **LoRA** | `LoRALinear`, `LowRankAdapterSupport`, `AdapterConfig` | `domain/graph/.../layers/` |
| **Protobuf weights** | `StateDictionary`, `CollectionEncoder` | `engine/ml/.../StateDictionary.java` |
| **Model bundles** | `ModelBundle` (metadata + weights + adapter config) | `engine/ml/.../ModelBundle.java` |
| **Weight extraction** | `extract_qwen3_weights.py` pattern | `engine/ml/qwen/tools/` |
| **Training loops** | `ModelOptimizer` | `engine/optimize/.../ModelOptimizer.java` |
| **Adam optimizer** | `AdamOptimizer` | `engine/optimize/.../AdamOptimizer.java` |
| **Cross-entropy loss** | `NegativeLogLikelihood` | `engine/optimize/.../NegativeLogLikelihood.java` |
| **Tokenizer interface** | `Tokenizer`, `ByteLevelBPETokenizer` | `engine/ml/.../Tokenizer.java` |
| **BPE tokenization** | `ByteLevelBPETokenizer`, `RegexPreTokenizer` | `engine/ml/.../tokenization/` |
| **MIDI input/output** | `MidiInputListener`, `MidiDeviceManager` | `engine/audio/.../midi/` |
| **Music patterns** | `PatternElement`, `PatternNote`, `PatternLayer` | `studio/music/.../pattern/` |
| **Reference model** | `Qwen3` (complete LLaMA-style model) | `engine/ml/.../qwen3/Qwen3.java` |
| **Diffusion + LoRA** | `LoRADiffusionTransformer` | `engine/ml/.../audio/` |
| **Dataset interface** | `Dataset`, `FunctionalDataset` | `engine/optimize/` |

### Gaps to Fill

| Gap | Description | Effort |
|---|---|---|
| **Compound MIDI tokenizer** | 6-attribute tuple tokenization (onset, duration, octave, pitch class, instrument, velocity) | Medium |
| **Fundamental Music Embedding (FME)** | Sinusoidal continuous embedding for numerical attributes (like positional encoding but with learnable bias + projection) | Small |
| **Per-head-group RoPE** | Partition attention heads into 6 groups, each with its own RoPE theta and attribute-derived position IDs | Medium |
| **GRU decoder** | 4-layer GRU that autoregressively decodes 7 tokens per note from transformer hidden states | Medium |
| **Compound autoregressive model** | Modified `AutoregressiveModel` that handles compound input (6-tuple) and compound output (7-token GRU sequence) | Medium |
| **MIDI file parsing** | Read standard MIDI files into note events (pitch, onset, duration, velocity, instrument) | Small |
| **MIDI file writing** | Convert generated note sequences back to standard MIDI files | Small |
| **Weight extraction script** | Python script to convert HuggingFace Moonbeam checkpoints to AR protobuf format | Small |
| **Attention mask packing** | Block-diagonal attention mask for training on concatenated MIDI sequences | Small |

---

## 2. MIDI Tokenization

### Architecture

Moonbeam represents each MIDI note as a **compound token** -- a 6-tuple of discrete attributes:

| Index | Attribute | Type | Vocab Size | Source |
|---|---|---|---|---|
| 0 | Onset (timeshift) | Relative delta | 4099 | `onset[i] - onset[i-1]` in ticks (100 ticks/sec) |
| 1 | Duration | Absolute | 4099 | Note duration in ticks |
| 2 | Octave | Absolute | 13 | `pitch // 12` |
| 3 | Pitch class | Absolute | 14 | `pitch % 12` (+ 2 reserved for SOS/EOS) |
| 4 | Instrument | Absolute | 131 | MIDI program number (128 for drums, + 2 reserved) |
| 5 | Velocity | Absolute | 130 | MIDI velocity (+ 2 reserved) |

Special tokens: SOS = all -1, EOS = all -2, PAD = all -3 (handled by a separate `supplementary_embedding`).

### Implementation Plan

**New class: `MidiTokenizer`** in `studio/music/src/main/java/org/almostrealism/music/midi/`

This is NOT a subclass of `ByteLevelBPETokenizer` -- MIDI tokenization is fundamentally different from text BPE. It implements `Tokenizer` only for compatibility, but the primary API works with `MidiCompoundToken` arrays.

```java
/** A single compound MIDI token with 6 attributes. */
public class MidiCompoundToken {
    int onset;       // absolute onset in ticks
    int duration;    // duration in ticks
    int octave;      // 0-10
    int pitchClass;  // 0-11 (+ SOS/EOS)
    int instrument;  // 0-128
    int velocity;    // 0-127 (+ SOS/EOS)
}

/** Tokenizes MIDI files into compound token sequences. */
public class MidiTokenizer {
    static final int TIME_RESOLUTION = 100; // ticks per second

    MidiCompoundToken[] tokenize(MidiFile file);
    MidiFile detokenize(MidiCompoundToken[] tokens);

    /** Convert compound tokens to model input: (seqLen, 6) int array. */
    int[][] toModelInput(MidiCompoundToken[] tokens);

    /** Convert model output (flat decode vocab) to compound tokens. */
    MidiCompoundToken fromDecodeIndices(int[] indices);
}
```

**New class: `MidiFileReader`** in `studio/music/src/main/java/org/almostrealism/music/midi/`

Uses `javax.sound.midi` (standard JDK) to parse MIDI files into note events. No external dependency needed.

```java
/** Reads standard MIDI files into structured note events. */
public class MidiFileReader {
    List<MidiNoteEvent> read(File midiFile);
    void write(List<MidiNoteEvent> events, File output);
}
```

---

## 3. Embedding Layer

### Fundamental Music Embedding (FME)

Moonbeam uses a continuous sinusoidal embedding (not a lookup table) for 5 of the 6 attributes. This is mathematically identical to sinusoidal positional encoding but with:
- A learnable translation bias added before the sinusoidal transform
- A linear projection after the sin/cos encoding

For the instrument attribute (categorical, 131 values), a standard `nn.Embedding` lookup is used.

### Implementation Plan

**New class: `FundamentalMusicEmbedding`** -- implements `Block`

Each FME instance embeds a single attribute from an integer value to a vector of dimension `embeddingDim` (= hiddenSize / 6 = 320 for Moonbeam).

The sinusoidal formula is:
```
angle_rates = 1 / base^(2*(i//2) / dim)
encoding[2i]   = sin((value + bias) * angle_rates[i])
encoding[2i+1] = cos((value + bias) * angle_rates[i])
output = Linear(encoding)
```

This reuses the math from `RotationFeatures.computeRopeFreqs()` -- the frequency computation is identical, just with different base values.

**Compound embedding layer:**
```java
/** Embeds 6-attribute compound tokens into hidden_size vectors. */
public class CompoundMidiEmbedding implements Block {
    FundamentalMusicEmbedding onsetEmb;    // base=199999, dim=320
    FundamentalMusicEmbedding durationEmb; // base=1031, dim=320
    FundamentalMusicEmbedding octaveEmb;   // base=19, dim=320
    FundamentalMusicEmbedding pitchEmb;    // base=20, dim=320
    CellularLayer instrumentEmb;           // standard embedding, vocab=131, dim=320
    FundamentalMusicEmbedding velocityEmb; // base=131, dim=320

    // Output: concatenation of 6 embeddings = 6 * 320 = 1920
}
```

---

## 4. Attention Mechanism Extensions

### Multidimensional Relative Attention (MRA)

Standard LLaMA applies one RoPE embedding (with sequential position IDs) uniformly to all heads. Moonbeam partitions the 12 heads into 6 groups of 2, each applying RoPE with different theta and position IDs derived from the corresponding attribute values.

| Head Group | Heads | Attribute | Theta | Position ID |
|---|---|---|---|---|
| 0 | 0-1 | Onset | 199,999 | Raw onset ticks |
| 1 | 2-3 | Duration | 1,031 | Duration value |
| 2 | 4-5 | Octave | 19 | Octave number |
| 3 | 6-7 | Pitch class | 20 | Pitch class value |
| 4 | 8-9 | Instrument | 199,999 | Onset ticks (reused) |
| 5 | 10-11 | Velocity | 131 | Velocity value |

### How This Extends Existing Infrastructure

The existing `AttentionFeatures.attention()` method applies a single `freqCis` tensor to all heads uniformly via `ropeRotation()`. To support MRA:

**Option: Extend `attention()` with multi-RoPE support**

Add a new `attention()` overload in `AttentionFeatures` that accepts an array of `(freqCis, headCount)` pairs -- one per attribute group. Internally, after Q/K projections:

1. Split Q into 6 groups along the head dimension
2. Apply per-group RoPE with the corresponding `freqCis` (precomputed with the correct theta) and attribute-derived position IDs
3. Concatenate the rotated Q groups back together
4. Do the same for K
5. Proceed with standard scaled dot-product attention

This is a clean extension because:
- The existing `ropeRotation()` already handles arbitrary position values -- just pass the attribute value instead of a sequential index
- The existing `computeRopeFreqs()` already handles arbitrary theta -- just call it 6 times with different theta values
- The split/concat along heads is a reshape operation, no new math

**New method signature:**
```java
/**
 * Attention with per-head-group rotary embeddings (Multidimensional Relative Attention).
 *
 * @param headGroups Array of head group configs: (numHeads, freqCis, positionProducer) per group
 * @param kvHeads Number of KV heads (shared across all groups)
 */
Block attention(int totalHeads, int kvHeads,
                PackedCollection rmsAttWeight,
                PackedCollection wq, PackedCollection wk,
                PackedCollection wv, PackedCollection wo,
                HeadGroupConfig[] headGroups,
                ComputeRequirement... requirements);
```

Where `HeadGroupConfig` bundles:
```java
public class HeadGroupConfig {
    int headCount;                            // heads in this group (e.g., 2)
    PackedCollection freqCis;                 // precomputed RoPE frequencies for this theta
    Producer<PackedCollection<?>> position;   // attribute value as position
}
```

This approach:
- Does NOT duplicate the attention implementation
- Reuses existing RoPE, projection, softmax, and KV cache code
- Only changes how positions are applied to Q/K before the attention computation
- Cleanly encapsulates the MRA concept in a configuration object

---

## 5. GRU Output Decoder

### Architecture

Moonbeam does NOT use a standard `lm_head` linear projection. Instead, it uses a 4-layer GRU that autoregressively generates 7 output tokens per position:

```
hidden_state (1920) -> summary_projection (1920 -> 1536) -> GRU initial hidden
GRU steps: [sos_out, timeshift, duration, octave, pitch_class, instrument, velocity]
Each step: GRU(prev_output, hidden) -> lm_head(1536 -> 8487) -> sample token
```

The decode vocabulary is flat: 8487 tokens = concatenation of all attribute vocabs with offsets.

### Implementation Plan

**New class: `GRUDecoder`** in `studio/compose/src/main/java/org/almostrealism/studio/midi/`

```java
/**
 * GRU-based output decoder for compound MIDI token generation.
 * Takes transformer hidden states and autoregressively generates
 * 7 attribute tokens per position.
 */
public class GRUDecoder implements Block {
    int hiddenSize;         // 1536
    int numLayers;          // 4
    int decodeVocabSize;    // 8487
    int tokensPerNote;      // 7

    // Weights from StateDictionary:
    // decoder.weight_ih_l{0-3}, decoder.weight_hh_l{0-3}
    // decoder.bias_ih_l{0-3}, decoder.bias_hh_l{0-3}
    // summary_projection.weight, summary_projection.bias
    // lm_head.weight, lm_head.bias
    // decoder_embedding.weight
}
```

GRU is a simpler variant of LSTM with two gates (reset, update). The cell computation is:
```
r = sigmoid(W_ir @ x + b_ir + W_hr @ h + b_hr)    // reset gate
z = sigmoid(W_iz @ x + b_iz + W_hz @ h + b_hz)    // update gate
n = tanh(W_in @ x + b_in + r * (W_hn @ h + b_hn)) // new gate
h' = (1 - z) * n + z * h                           // new hidden state
```

This uses only `dense()`, `sigmoid()`, `tanh()`, and element-wise operations -- all available in `LayerFeatures`.

---

## 6. Weight Conversion

### From HuggingFace to AR Protobuf

Follow the existing `extract_qwen3_weights.py` pattern.

**New script: `extract_moonbeam_weights.py`** in `engine/ml/scripts/`

```python
# Load Moonbeam checkpoint from HuggingFace
model = load_checkpoint("guozixunnicolas/moonbeam-midi-foundation-model")

# Extract and convert weights to protobuf:
# 1. Encoder weights (LLaMA transformer)
#    model.layers.{i}.self_attn.{q,k,v,o}_proj.weight
#    model.layers.{i}.mlp.{gate,up,down}_proj.weight
#    model.layers.{i}.{input,post_attention}_layernorm.weight
#    model.norm.weight
#
# 2. Embedding weights
#    onset_embedding.{linear.weight, linear.bias, translation_bias}
#    duration_embedding.{...}
#    octave_embedding.{...}
#    pitch_embedding.{...}
#    instrument_embedding.weight  (standard nn.Embedding)
#    velocity_embedding.{...}
#    supplementary_embedding.weight
#    supplementary_mlp.{0,2}.{weight,bias}
#
# 3. Decoder weights (GRU)
#    decoder.weight_ih_l{0-3}, decoder.weight_hh_l{0-3}
#    decoder.bias_ih_l{0-3}, decoder.bias_hh_l{0-3}
#    summary_projection.weight, summary_projection.bias
#    lm_head.weight, lm_head.bias
#    decoder_embedding.weight
#
# 4. Per-attribute RoPE theta values and head assignments
#    (These are config, not weights, but include in metadata)
```

Weight key naming convention follows HuggingFace format for easy debugging, matching the Qwen3 pattern.

---

## 7. Model Assembly (MoonbeamMidi class)

### Implementation Plan

**New class: `MoonbeamMidi`** in `studio/compose/src/main/java/org/almostrealism/studio/midi/`

Follows the `Qwen3` pattern exactly:

```java
public class MoonbeamMidi implements AttentionFeatures {
    private MoonbeamConfig config;
    private StateDictionary stateDict;
    private MidiTokenizer tokenizer;

    public MoonbeamMidi(String weightsDir) {
        this.stateDict = new StateDictionary(weightsDir);
        this.config = MoonbeamConfig.defaultConfig(); // or infer from weights
        this.tokenizer = new MidiTokenizer();
        buildModel();
    }

    private void buildModel() {
        Model transformer = new Model(shape(1, config.hiddenSize));

        // 1. Compound embedding (handled outside Model, like Qwen3 token embeddings)
        CompoundMidiEmbedding embedding = new CompoundMidiEmbedding(stateDict, config);

        // 2. Per-attribute RoPE frequency tables
        HeadGroupConfig[] headGroups = buildHeadGroups(config);

        // 3. Build 15 transformer layers with MRA attention
        for (int i = 0; i < config.numLayers; i++) {
            String prefix = "model.layers." + i;
            transformer.add(transformer(
                config.numHeads, config.numKvHeads,
                stateDict.get(prefix + ".input_layernorm.weight"),
                stateDict.get(prefix + ".self_attn.k_proj.weight"),
                stateDict.get(prefix + ".self_attn.v_proj.weight"),
                stateDict.get(prefix + ".self_attn.q_proj.weight"),
                stateDict.get(prefix + ".self_attn.o_proj.weight"),
                headGroups,  // MRA: per-group RoPE
                stateDict.get(prefix + ".post_attention_layernorm.weight"),
                stateDict.get(prefix + ".mlp.gate_proj.weight"),
                stateDict.get(prefix + ".mlp.down_proj.weight"),
                stateDict.get(prefix + ".mlp.up_proj.weight"),
                /* position is per-attribute, handled by headGroups */
                requirements
            ));
        }

        // 4. Final RMSNorm
        transformer.add(rmsnorm(shape(1, config.hiddenSize),
            stateDict.get("model.norm.weight"), 1e-5));

        // 5. GRU decoder (replaces lm_head)
        GRUDecoder decoder = new GRUDecoder(stateDict, config);

        // 6. Compile and wrap in autoregressive model
        // Note: AutoregressiveModel needs extension for compound tokens
    }
}
```

### MoonbeamConfig

```java
public class MoonbeamConfig {
    int hiddenSize = 1920;
    int intermediateSize = 6720;
    int numLayers = 15;
    int numHeads = 12;
    int numKvHeads = 6;
    int headDim = 160;
    int decoderHiddenSize = 1536;
    int decoderLayers = 4;
    int decodeVocabSize = 8487;
    int maxSeqLen = 8192;
    double rmsNormEps = 1e-5;

    // Per-attribute RoPE config
    double[] ropeThetas = {199999, 1031, 19, 20, 199999, 131};
    int[] headsPerGroup = {2, 2, 2, 2, 2, 2};
}
```

---

## 8. Training Pipeline

### Pretraining (Future -- requires large MIDI dataset)

Uses existing `ModelOptimizer` with cross-entropy loss on the GRU decoder output. The training loop is NOT hand-written:

```java
ModelOptimizer optimizer = new ModelOptimizer(model, dataset);
optimizer.setLossFunction(new NegativeLogLikelihood());
optimizer.setParameterUpdate(new AdamOptimizer(3e-4, 0.9, 0.999));
optimizer.optimize(epochs);
```

### LoRA Fine-tuning

Uses existing `LoRALinear` and `LowRankAdapterSupport`:

```java
AdapterConfig adapterConfig = new AdapterConfig()
    .rank(8)
    .alpha(32.0)
    .targets(
        AdapterConfig.TargetLayer.SELF_ATTENTION_QKV,
        AdapterConfig.TargetLayer.SELF_ATTENTION_OUT
    );

// Apply LoRA to attention projections
moonbeam.applyLoRA(adapterConfig);

// Train only LoRA parameters + decoder
ModelOptimizer optimizer = new ModelOptimizer(model, dataset);
optimizer.optimize(epochs);

// Save adapter
ModelBundle.forAdapter(loraWeights, adapterConfig, "moonbeam-base")
    .save(Path.of("moonbeam-lora.pb"));
```

### Dataset

**New class: `MidiDataset`** implements `Dataset`

Reads MIDI files, tokenizes them into compound sequences, and provides batched training examples. Supports the "packing" strategy from Moonbeam where multiple MIDI files are concatenated into single sequences with separator tokens.

---

## 9. Inference Pipeline

### Autoregressive Generation

The generation loop differs from standard text LLMs because of the compound token structure:

1. **Input:** 6-attribute compound token per position
2. **Transformer forward:** produces hidden state (1920-dim)
3. **GRU decode:** autoregressively generates 7 output tokens from hidden state
4. **Map output:** convert 7 flat-vocab tokens back to 6 compound attributes
5. **Feed next:** use generated compound token as next input

**New class: `MidiAutoregressiveModel`** extends the pattern from `AutoregressiveModel`

```java
public class MidiAutoregressiveModel {
    // Instead of IntConsumer token (single token ID),
    // accepts a MidiCompoundToken (6 attributes)
    // and produces 7 decode tokens per step via GRU

    public MidiCompoundToken next();
    public void setPrompt(MidiCompoundToken[] prompt);
}
```

### End-to-end inference:

```java
MoonbeamMidi model = new MoonbeamMidi("/path/to/weights");
MidiTokenizer tokenizer = new MidiTokenizer();

// Encode prompt MIDI
MidiCompoundToken[] prompt = tokenizer.tokenize(MidiFileReader.read("prompt.mid"));

// Generate
model.setPrompt(prompt);
List<MidiCompoundToken> generated = new ArrayList<>();
for (int i = 0; i < maxLen; i++) {
    MidiCompoundToken next = model.next();
    if (next.isEOS()) break;
    generated.add(next);
}

// Write output MIDI
MidiFileReader.write(tokenizer.detokenize(generated), "output.mid");
```

---

## 10. Milestones (Ordered as Submittable Tasks)

### Milestone 1: MIDI Tokenization
**Goal:** Parse MIDI files and produce compound token sequences.

- [ ] `MidiNoteEvent` data class (pitch, onset, duration, velocity, instrument)
- [ ] `MidiFileReader` using `javax.sound.midi` (read + write)
- [ ] `MidiCompoundToken` data class (6-attribute tuple)
- [ ] `MidiTokenizer` (note events <-> compound tokens, with SOS/EOS/PAD)
- [ ] `MidiTokenizerTest` with round-trip test on a simple MIDI file
- [ ] `MoonbeamConfig` with all model hyperparameters

**Dependencies:** None (pure Java, uses JDK MIDI API)

### Milestone 2: Weight Conversion
**Goal:** Load pretrained Moonbeam weights into AR protobuf format.

- [ ] `extract_moonbeam_weights.py` following `extract_qwen3_weights.py` pattern
- [ ] Verify extracted weights load via `StateDictionary`
- [ ] Document weight key mapping (HF name -> AR name)

**Dependencies:** Milestone 1 (for config constants)

### Milestone 3: Fundamental Music Embedding
**Goal:** Implement the sinusoidal FME and compound embedding layer.

- [ ] `FundamentalMusicEmbedding` (sinusoidal with learnable bias + linear projection)
- [ ] `CompoundMidiEmbedding` (6 parallel embeddings, concat to hidden_size)
- [ ] Supplementary embedding for SOS/EOS tokens
- [ ] Test: verify embedding output shapes match reference

**Dependencies:** Milestone 2 (needs weights for linear projection)

### Milestone 4: Multidimensional Relative Attention
**Goal:** Extend `AttentionFeatures` with per-head-group RoPE.

- [ ] `HeadGroupConfig` data class
- [ ] New `attention()` overload accepting `HeadGroupConfig[]`
- [ ] Per-group RoPE frequency computation (6 different theta values)
- [ ] Per-group position ID routing (attribute values as positions)
- [ ] Test: verify attention output matches reference for a single layer

**Dependencies:** Milestone 2 (needs weights), Milestone 3 (needs embeddings for input)

### Milestone 5: GRU Decoder
**Goal:** Implement the 4-layer GRU output decoder.

- [ ] `GRUCell` implementation using existing `dense()`, `sigmoid()`, `tanh()`
- [ ] `GRUDecoder` with summary projection, 4-layer GRU, lm_head
- [ ] Decode vocabulary offset mapping (attribute vocabs -> flat 8487 vocab)
- [ ] Test: verify GRU output matches reference for a single position

**Dependencies:** Milestone 2 (needs decoder weights)

### Milestone 6: Model Assembly and Inference
**Goal:** Complete `MoonbeamMidi` class with end-to-end inference.

- [ ] `MoonbeamMidi` class following `Qwen3` pattern
- [ ] `MidiAutoregressiveModel` with compound token handling
- [ ] Wire: embedding -> 15 transformer layers (MRA) -> RMSNorm -> GRU decoder
- [ ] End-to-end inference test: load weights, process prompt, generate MIDI
- [ ] Compare generated output with reference Python implementation

**Dependencies:** Milestones 1-5

### Milestone 7: Training Pipeline
**Goal:** Enable LoRA fine-tuning on custom MIDI datasets.

- [ ] `MidiDataset` implementing `Dataset` with MIDI file loading
- [ ] Sequence packing (concatenate multiple MIDIs with separators)
- [ ] Cross-entropy loss on GRU decode vocabulary
- [ ] LoRA adapter application to attention projections + decoder
- [ ] `MidiTrainingConfig` with hyperparameters matching reference
- [ ] Training test: verify loss decreases on small dataset
- [ ] `ModelBundle` save/load for LoRA adapters

**Dependencies:** Milestone 6

### Milestone 8: Evaluation and Polish
**Goal:** Validate model quality and add usability features.

- [ ] Unconditional generation (from SOS token)
- [ ] Conditional generation (prompt completion)
- [ ] Temperature and top-p sampling controls
- [ ] MIDI file I/O integration (file in -> file out)
- [ ] Performance profiling with `OperationProfile`
- [ ] Documentation and examples

**Dependencies:** Milestone 7

---

## 11. File Organization

MIDI data types and I/O belong in the **music module** — they represent music concepts.
The Moonbeam model implementation belongs in the **compose module** — it combines ML and music.

```
studio/music/src/main/java/org/almostrealism/music/midi/
    MidiNoteEvent.java             # Raw MIDI event data class
    MidiFileReader.java            # MIDI file I/O (javax.sound.midi)
    MidiCompoundToken.java         # 6-attribute token data class
    MidiTokenizer.java             # Compound token encoding/decoding

studio/compose/src/main/java/org/almostrealism/studio/midi/
    MoonbeamMidi.java              # Main model class
    MoonbeamConfig.java            # Model configuration
    MidiAutoregressiveModel.java   # Compound-token generation loop
    FundamentalMusicEmbedding.java # Sinusoidal continuous embedding
    CompoundMidiEmbedding.java     # 6-parallel embedding layer
    HeadGroupConfig.java           # Per-group RoPE configuration
    GRUCell.java                   # Single GRU cell
    GRUDecoder.java                # 4-layer GRU output decoder
    MidiDataset.java               # Training dataset

studio/compose/src/test/java/org/almostrealism/studio/midi/test/
    MidiTokenizerTest.java
    FundamentalMusicEmbeddingTest.java
    MoonbeamMidiTest.java
    GRUDecoderTest.java

engine/ml/scripts/
    extract_moonbeam_weights.py    # HuggingFace -> AR protobuf
```

---

## 12. Risks and Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Per-head-group RoPE changes attention kernel compilation | High | Test with single-layer model first; fall back to 6 separate attention calls if needed |
| GRU adds sequential dependency (7 steps per position) | Medium | GRU is small (1536-dim); benchmark and optimize if bottleneck |
| HuggingFace checkpoint format differs from expected | Low | Reference code is open-source; can inspect exact format |
| Compound token handling in autoregressive loop | Medium | Build incrementally: first test with pre-embedded inputs, then add tokenizer |
| Training on large MIDI datasets requires significant compute | Low | LoRA fine-tuning on small datasets first; pretraining is optional (use pretrained weights) |

---

## 13. Non-Goals

- **No Python wrapper:** Everything runs in Java
- **No new dependencies:** Uses JDK MIDI API, existing ar-ml stack
- **No audio generation:** This is symbolic MIDI, not audio waveforms
- **No model distillation:** Use the full ~200M model
- **No custom CUDA kernels:** Use the existing hardware acceleration layer

---

## 14. Pattern-to-MIDI Bridge: PatternSystemManager MIDI Output

### What Exists

The `PatternSystemManager` in `studio/music` is a hierarchical, real-time audio composition system. Its core data flow is:

```
PatternSystemManager
  → PatternLayerManager (one per pattern slot, with channel/duration/melodic flag)
    → PatternLayer (linked-list hierarchy, each child at half granularity)
      → PatternElement (individual musical event)
        → ScaleTraversalStrategy (CHORD or SEQUENCE pitch selection)
          → RenderedNoteAudio (bridge to audio rendering)
```

**Key musical properties already present in the pattern system:**

| Property | Source | MIDI Equivalent |
|---|---|---|
| Position (measures) | `PatternElement.position` | Note onset (ticks) |
| Duration | `NoteDurationStrategy` + next-note distance | Note duration (ticks) |
| Pitch | `ScaleTraversalStrategy` → `KeyPosition` via `Scale` | MIDI pitch (0-127) |
| Repetitions | `PatternElement.repeatCount/repeatDuration` | Multiple note events |
| Instrument | `NoteAudioChoice` / channel assignment | MIDI program number |
| Automation | `PatternElement.automationParameters` | Could map to velocity |

**What's missing for MIDI output:**

1. **No explicit velocity.** `PatternNote` has no velocity field. Automation parameters (a `PackedCollection`) could be mapped to velocity, or a sensible default (e.g., 100) could be used with automation scaling.

2. **No MIDI export path.** The current flow always terminates at audio rendering (`PatternFeatures.render()` → `RenderedNoteAudio.getProducer()` → PCM audio). There is no branch that produces MIDI events.

3. **Pitch resolution.** `ScaleTraversalStrategy` resolves scale degrees to `KeyPosition<?>` objects, which represent keys on a keyboard tuning. The concrete pitch (as a MIDI note number 0-127) requires resolving the `KeyPosition` through a `KeyboardTuning` to get a frequency, then mapping frequency to MIDI note number. Alternatively, if the `Scale` is `WesternChromatic`-based, the `KeyPosition` index can map directly.

4. **Timing conversion.** Pattern positions are in measures (doubles). Converting to MIDI ticks requires a tempo mapping: `ticks = position * measuresPerBeat * beatsPerMinute * (TIME_RESOLUTION / 60.0)`. The `AudioSceneContext.frameForPosition()` does an analogous conversion for audio frames.

### What to Build

MIDI export is a behavior that belongs on the types that own the musical data. Do NOT create a separate exporter/converter class — add methods directly to the types in the pattern hierarchy:

**`PatternElement.toMidiEvents(AudioSceneContext, boolean melodic)`** → `List<MidiNoteEvent>`

Converts a single element's rendered notes to MIDI events:
1. Call `getNoteDestinations()` to get `List<RenderedNoteAudio>` (reuses existing traversal logic)
2. For each `RenderedNoteAudio`, extract: frame offset → onset ticks, frame count → duration ticks
3. Resolve `KeyPosition` → MIDI pitch via the scale's note index
4. Map automation level → MIDI velocity (0-127)
5. Map channel/choice → MIDI instrument

**`PatternLayerManager.toMidiEvents(AudioSceneContext)`** → `List<MidiNoteEvent>`

Iterates over all elements in its layer hierarchy and collects their MIDI events. Delegates to `PatternElement.toMidiEvents()` for each element.

**`PatternSystemManager.toMidiEvents(AudioSceneContext, double start, double end)`** → `List<MidiNoteEvent>`

Exports all patterns in the time range `[start, end)` measures. Iterates over its `PatternLayerManager` instances and collects their MIDI events.

The output `List<MidiNoteEvent>` feeds directly into the existing `MidiFileReader.write()` to produce a standard `.mid` file, or into `MidiTokenizer.tokenize()` for Moonbeam input.

**Effort:** Small-Medium. The hard musical logic (scale traversal, repetition, timing) already exists. This is primarily a data extraction and format conversion task.

---

## 15. Moonbeam Infilling Integration

### How Infilling Works in Moonbeam

The Moonbeam reference repository's `conditional_gen_commu` branch implements music infilling — the model fills in missing sections of a MIDI composition given surrounding context. The approach:

1. **Segmented input:** A MIDI sequence is divided into segments. Some segments are provided as context (prefix and/or suffix), while others are marked as "fill regions" to be generated.

2. **Special delimiter tokens:** Fill regions are demarcated by special tokens in the compound token vocabulary. The model sees: `[context tokens] [FILL_START] [FILL_END] [more context]` and generates the tokens that should go in the fill region.

3. **Bidirectional context:** Unlike unconditional generation (which only has left context), infilling provides both left and right context, letting the model generate musically coherent bridges between known sections.

4. **Autoregressive fill:** The model generates tokens for the fill region one at a time, conditioned on both the surrounding context and previously generated fill tokens.

### Integration Pipeline: Algorithmic Patterns + Moonbeam Infilling

The key insight: **algorithmically generated patterns provide structural scaffolding, and Moonbeam fills in the musical details.** This should produce better results than either approach alone — patterns give structure and harmonic correctness, while Moonbeam adds stylistic nuance and natural variation.

**Pipeline:**

```
1. PatternSystemManager generates a structured composition
   (chord progressions via ChordProgressionManager, melodic patterns
    via ScaleTraversalStrategy, rhythmic patterns via PatternLayer hierarchy)
        ↓
2. PatternSystemManager.toMidiEvents() converts to List<MidiNoteEvent>
        ↓
3. MidiTokenizer.tokenize() → List<MidiCompoundToken>
        ↓
4. Selective masking: replace certain regions with FILL tokens
   - Option A: Mask melodic sections, keep rhythm/harmony (Moonbeam adds melody)
   - Option B: Mask every N-th bar (Moonbeam adds variation to repetitive patterns)
   - Option C: Mask embellishment regions (Moonbeam adds fills/transitions)
        ↓
5. MidiAutoregressiveModel with infilling mode generates fill tokens
        ↓
6. MidiTokenizer.detokenize() → enhanced List<MidiNoteEvent>
        ↓
7. MidiFileReader.write() → final .mid file
```

### What to Implement

1. **Infilling token support in `MidiCompoundToken`:** Add `FILL_START` and `FILL_END` special token types alongside existing SOS/EOS/PAD. These need corresponding entries in the compound token vocabulary with sentinel values (e.g., -4, -5) and embedding support in `CompoundMidiEmbedding`.

2. **Masking strategies:** A `PatternMaskingStrategy` interface with implementations:
   - `BarMasking(int interval)` — mask every N-th bar
   - `TrackMasking(Set<Integer> instruments)` — mask specific instrument tracks
   - `RegionMasking(double startMeasure, double endMeasure)` — mask a time range
   - `DensityMasking(double keepRatio)` — randomly keep a fraction of notes

3. **Infilling generation mode in `MidiAutoregressiveModel`:** Extend `generate()` to accept a partially-masked token sequence and generate only the fill regions, respecting the right-context constraint.

4. **Attention mask modification:** For infilling, the attention mask must allow the model to attend to tokens on both sides of the fill region. This requires modifying the causal mask used in `MoonbeamMidi.forward()`.

**Key dependency:** The pretrained Moonbeam checkpoint may not support infilling out of the box — the `conditional_gen_commu` branch likely requires a specifically trained or fine-tuned checkpoint. We may need to fine-tune the base model on infilling examples first.

**Effort:** Medium-Large. The tokenization changes are small, but the attention mask modification and generation loop changes are non-trivial.

---

## 16. Synthetic Data Generation Pipeline

### Concept

Use the algorithmic pattern system to generate large volumes of structured MIDI, optionally enhance with Moonbeam infilling, then curate the best results via human ranking to create a high-quality fine-tuning dataset.

### Pipeline Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│  Stage 1: Algorithmic Generation                                      │
│                                                                        │
│  ChordProgressionManager → varied chord sequences (key, mode, depth)  │
│  PatternSystemManager → rhythmic/melodic patterns per chord           │
│  PatternSystemManager.toMidiEvents() → MIDI files                                     │
│                                                                        │
│  Parameters to vary:                                                   │
│  - Key (all 12 chromatic roots × major/minor/modes)                   │
│  - Tempo (60-180 BPM)                                                  │
│  - Time signature (4/4, 3/4, 6/8)                                     │
│  - Pattern density (sparse to dense via PatternLayer depth)            │
│  - ScaleTraversalStrategy (CHORD vs SEQUENCE)                          │
│  - Repetition patterns (repeatCount, repeatDuration)                  │
│  - Instrument combinations                                             │
│  - ChordProgressionManager chromosome values (genetic variation)       │
└──────────────────────────────────────────────────────────────────────┘
        ↓
┌──────────────────────────────────────────────────────────────────────┐
│  Stage 2: (Optional) Moonbeam Enhancement                             │
│                                                                        │
│  For each generated MIDI:                                              │
│  - Tokenize                                                            │
│  - Apply masking strategy (e.g., mask every other bar)                │
│  - Run Moonbeam infilling to add variation                            │
│  - Detokenize back to MIDI                                             │
│  - Produces N variants per base pattern                                │
│                                                                        │
│  This stage is optional — Stage 1 alone produces valid training data.  │
└──────────────────────────────────────────────────────────────────────┘
        ↓
┌──────────────────────────────────────────────────────────────────────┐
│  Stage 3: Human Curation (Ranking App)                                │
│                                                                        │
│  Web interface that:                                                   │
│  - Plays MIDI clips via embedded synthesizer (Web MIDI API or          │
│    server-side synthesis to audio)                                      │
│  - Presents pairs or batches for comparison                            │
│  - Collects ratings: keep / discard / favorite                         │
│  - Tracks metadata: which parameters produced good results             │
│                                                                        │
│  Output: curated MIDI dataset with quality labels                      │
└──────────────────────────────────────────────────────────────────────┘
        ↓
┌──────────────────────────────────────────────────────────────────────┐
│  Stage 4: Fine-tuning                                                  │
│                                                                        │
│  MidiDataset loads curated MIDI files                                  │
│  ModelOptimizer trains with LoRA adapters                              │
│  Produces fine-tuned checkpoint for higher-quality generation           │
└──────────────────────────────────────────────────────────────────────┘
```

### Batch Generation Implementation

Batch generation is orchestrated by `PatternSystemManager` itself — it already owns the relationship between `ChordProgressionManager` and the pattern hierarchy. Add a method:

**`PatternSystemManager.generateMidiCorpus(int count, File outputDir, SyntheticMidiConfig config)`** → `List<File>`

This method varies pattern parameters (key, tempo, mode, density, traversal strategy) and generates MIDI files via `toMidiEvents()` + `MidiFileReader.write()`.

The genetic algorithm infrastructure (`ProjectedChromosome`, `ProjectedGenome`) already built into `PatternSystemManager` and `ChordProgressionManager` is ideal for generating diverse patterns — different chromosome values produce different musical structures without any manual parameter tuning.

**Estimated yield:** With 12 keys × 2 modes × 5 tempos × 3 densities × 10 chromosome seeds = ~3,600 base patterns. With Moonbeam enhancement producing 3 variants each = ~10,800 clips. After human curation keeping ~30% = ~3,200 training examples. This is a reasonable fine-tuning dataset.

---

## 17. Human Ranking and Curation App

### Concept

A lightweight web application for listening to generated MIDI clips and rating them. The goal is to curate a high-quality fine-tuning dataset from algorithmically and model-generated MIDI.

### Architecture

```
┌─────────────────────────────────────────────┐
│  Frontend (Browser)                          │
│  - Embedded MIDI playback (Tone.js or        │
│    Web MIDI API with virtual synth)          │
│  - A/B comparison interface                  │
│  - Rating controls: 1-5 stars or keep/skip  │
│  - Batch mode: queue of clips to review      │
│  - Metadata display: key, tempo, parameters  │
└──────────────────┬──────────────────────────┘
                   │ REST API
┌──────────────────▼──────────────────────────┐
│  Backend (Java, using ar-utils-http)         │
│  - Serves MIDI files from generation output  │
│  - Stores ratings in SQLite or flat JSON     │
│  - Exports curated set as MIDI directory     │
│  - Tracks reviewer and clip metadata         │
└──────────────────┬──────────────────────────┘
                   │
┌──────────────────▼──────────────────────────┐
│  Storage                                     │
│  - /generated/  — raw MIDI from pipeline     │
│  - /curated/    — accepted clips             │
│  - /rejected/   — discarded clips            │
│  - ratings.json — rating data                │
└─────────────────────────────────────────────┘
```

### Key Design Decisions

1. **MIDI playback in browser:** Use [Tone.js](https://tonejs.github.io/) with `@tonejs/midi` for client-side MIDI rendering. This avoids server-side audio synthesis and gives instant playback. The SoundFont-based playback provides reasonable instrument sounds.

2. **A/B comparison mode:** Present two clips side by side. The reviewer picks the better one, or skips both. This is faster and more consistent than absolute rating scales. Can also be used for Elo-style ranking.

3. **Batch workflow:** Reviewer opens the app, gets a queue of unreviewed clips, rates them one at a time. Progress is saved per-reviewer. Multiple reviewers can work in parallel.

4. **Export:** A command exports all clips rated above a threshold to a clean directory that `MidiDataset` can load directly.

**Effort:** Medium. The backend is straightforward HTTP serving. The frontend requires Tone.js integration but is a standard web app. Could start with a minimal version (single-clip playback + keep/skip) and iterate.

---

## 18. Fine-Tuning Pipeline Readiness

### What Already Exists

| Component | Class | Status |
|---|---|---|
| Training loop | `ModelOptimizer` | Production-ready, used by other models |
| Adam optimizer | `AdamOptimizer` | Production-ready |
| Cross-entropy loss | `NegativeLogLikelihood` | Production-ready |
| LoRA support | `LoRALinear`, `LowRankAdapterSupport` | Production-ready, used in diffusion |
| LoRA config | `AdapterConfig` | Supports rank, alpha, target layers |
| Dataset interface | `MidiDataset` | Implemented: loads MIDI files, tokenizes, packs sequences |
| Training config | `MidiTrainingConfig` | Implemented: LR, batch size, LoRA rank/alpha, epochs |
| LoRA application | `MoonbeamMidi.createLoraConfig()` | Implemented |
| Adapter save/load | `MoonbeamMidi.saveLoraAdapter()`, `ModelBundle` | Implemented |

### Gaps to Close

1. **Training loop integration test.** `MidiDataset` and `ModelOptimizer` exist independently but have not been tested together with the Moonbeam model in an end-to-end training loop. The compound token structure and GRU decoder add complexity: the loss function must operate on the 7-token GRU output vocabulary, not a standard single-token vocabulary.

2. **GRU decoder gradient flow.** The GRU decoder runs autoregressively (7 steps per position) during training. Backpropagation through the GRU steps needs to work correctly with the AR framework's automatic differentiation. This is the highest-risk gap.

3. **Multi-attribute loss.** The current `NegativeLogLikelihood` computes loss on a single token prediction. For Moonbeam, the loss should be summed across all 7 decode steps per position. `MidiDataset.tokenToOneHot()` already encodes the target as a multi-hot vector over the 8487-token decode vocabulary, which is compatible — but the loss aggregation needs verification.

4. **Training data volume.** LoRA fine-tuning typically needs hundreds to low thousands of examples. The synthetic generation pipeline (Section 16) can produce this volume. For comparison, the Moonbeam paper's fine-tuning datasets ranged from ~200 to ~2000 pieces.

5. **Evaluation metrics.** No automated evaluation of generated MIDI quality exists. Options include:
   - **Token-level perplexity** on a held-out set (standard, easy to implement)
   - **Musical metrics:** pitch range, note density, repetition structure (custom, but straightforward)
   - **Human evaluation** via the ranking app (gold standard but slow)

### Estimated Effort to First Fine-Tuning Run

| Task | Effort | Dependency |
|---|---|---|
| Verify loss function with GRU decoder | Small | None |
| End-to-end training test (synthetic data, 1 epoch) | Small | Loss verification |
| Synthetic MIDI generation (Stage 1, no Moonbeam) | Medium | Pattern MIDI export methods |
| Run fine-tuning on synthetic data | Small | Training test + synthetic data |
| Evaluate fine-tuned model vs base | Small | Fine-tuning run |

The path to a first fine-tuning experiment is short — the infrastructure is substantially complete.

---

## 19. New Milestones: Pattern Integration and Enhancement

These milestones extend the original plan (Milestones 1-8) with the pattern integration, infilling, and fine-tuning directions.

### Milestone 9: Pattern-to-MIDI Export
**Goal:** Enable PatternSystemManager to output standard MIDI files.

- [ ] `PatternElement.toMidiEvents()` method
- [ ] `PatternLayerManager.toMidiEvents()` method
- [ ] `PatternSystemManager.toMidiEvents()` method
- [ ] `KeyPosition` → MIDI pitch resolution (via scale index mapping)
- [ ] Automation parameters → velocity mapping
- [ ] Channel/choice → MIDI instrument mapping
- [ ] Timing conversion: measure position → MIDI ticks (with configurable tempo)
- [ ] Integration test: generate pattern → export MIDI → play back / verify round-trip
- [ ] Verify output loads correctly in external MIDI software

**Dependencies:** Milestone 1 (MidiNoteEvent, MidiFileReader already exist)
**Value:** Unlocks all downstream pipelines. Patterns become MIDI, which feeds both direct playback and Moonbeam tokenization.

### Milestone 10: Synthetic MIDI Dataset Generation
**Goal:** Batch-generate diverse MIDI files from algorithmic patterns.

- [ ] `PatternSystemManager.generateMidiCorpus()` method for batch generation
- [ ] Parameter variation: key, tempo, mode, density, traversal strategy
- [ ] Chromosome-based diversity via genetic algorithm
- [ ] Output: directory of labeled MIDI files with metadata JSON
- [ ] Generate initial corpus (~1000+ files)

**Dependencies:** Milestone 9
**Value:** Provides training data for fine-tuning without requiring external MIDI datasets.

### Milestone 11: Fine-Tuning Verification
**Goal:** Verify the LoRA fine-tuning pipeline works end-to-end.

- [ ] Integration test: MidiDataset + ModelOptimizer + MoonbeamMidi with LoRA
- [ ] Verify loss decreases over epochs on synthetic data
- [ ] Verify GRU decoder gradient flow through autoregressive steps
- [ ] Multi-attribute loss aggregation validation
- [ ] Save and reload LoRA adapter checkpoint
- [ ] Generate MIDI from fine-tuned model and compare to base model output

**Dependencies:** Milestone 7 (training pipeline) + Milestone 10 (synthetic data)
**Value:** Proves the fine-tuning loop works before investing in data curation.

### Milestone 12: Infilling Support
**Goal:** Add music infilling capability to Moonbeam inference.

- [ ] `FILL_START` / `FILL_END` special tokens in `MidiCompoundToken`
- [ ] Embedding support for fill tokens in `CompoundMidiEmbedding`
- [ ] `PatternMaskingStrategy` interface + implementations (bar, track, region, density)
- [ ] Modified attention mask for bidirectional context in `MoonbeamMidi`
- [ ] Infilling generation mode in `MidiAutoregressiveModel`
- [ ] End-to-end test: pattern → MIDI → mask → infill → output MIDI
- [ ] Fine-tune base model on infilling examples if needed

**Dependencies:** Milestone 6 (inference) + Milestone 9 (pattern MIDI export)
**Value:** Enables the hybrid approach — algorithmic structure + neural detail.

### Milestone 13: Curation App
**Goal:** Web app for human review and rating of generated MIDI clips.

- [ ] Backend: HTTP server using `ar-utils-http` to serve MIDI files and collect ratings
- [ ] Frontend: Tone.js MIDI playback with keep/skip/favorite controls
- [ ] A/B comparison mode for pairwise ranking
- [ ] Rating storage and export to curated directory
- [ ] Batch workflow with progress tracking
- [ ] Export command: curated set → directory loadable by `MidiDataset`

**Dependencies:** Milestone 10 (synthetic data to review)
**Value:** Human-in-the-loop quality filtering. The curated dataset should produce significantly better fine-tuning results than unfiltered synthetic data.

### Milestone 14: Curated Fine-Tuning
**Goal:** Fine-tune Moonbeam on human-curated data and evaluate improvement.

- [ ] Fine-tune on curated dataset from Milestone 13
- [ ] Compare generation quality: base model vs synthetic-finetuned vs curated-finetuned
- [ ] Perplexity evaluation on held-out set
- [ ] Human evaluation via the curation app (rate outputs from each model variant)
- [ ] Document best hyperparameters and training recipe

**Dependencies:** Milestone 11 (verified fine-tuning) + Milestone 13 (curated data)
**Value:** The end goal — a Moonbeam model fine-tuned on curated musical content that generates higher-quality MIDI than the base pretrained model.

### Milestone Priority Order

```
Milestone 9  (Pattern MIDI Export)         — HIGH, unlocks everything
Milestone 10 (Synthetic Dataset)           — HIGH, creates training data
Milestone 11 (Fine-Tuning Verification)    — HIGH, proves the loop works
Milestone 12 (Infilling Support)           — MEDIUM, powerful but complex
Milestone 13 (Curation App)               — MEDIUM, improves data quality
Milestone 14 (Curated Fine-Tuning)        — MEDIUM, final quality target
```

Milestones 9-11 form the critical path and can be done in sequence relatively quickly. Milestones 12-14 are valuable but independent — infilling (12) can proceed in parallel with the curation pipeline (13-14).
