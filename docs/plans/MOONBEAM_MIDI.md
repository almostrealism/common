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

**New class: `MidiTokenizer`** in `engine/ml/src/main/java/org/almostrealism/ml/midi/`

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

**New class: `MidiFileReader`** in `engine/ml/src/main/java/org/almostrealism/ml/midi/`

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

**New class: `GRUDecoder`** in `engine/ml/src/main/java/org/almostrealism/ml/midi/`

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

**New class: `MoonbeamMidi`** in `engine/ml/src/main/java/org/almostrealism/ml/midi/`

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

All new code goes in `engine/ml/src/main/java/org/almostrealism/ml/midi/`:

```
engine/ml/src/main/java/org/almostrealism/ml/midi/
    MoonbeamMidi.java              # Main model class
    MoonbeamConfig.java            # Model configuration
    MidiTokenizer.java             # Compound token encoding/decoding
    MidiCompoundToken.java         # 6-attribute token data class
    MidiNoteEvent.java             # Raw MIDI event data class
    MidiFileReader.java            # MIDI file I/O (javax.sound.midi)
    MidiAutoregressiveModel.java   # Compound-token generation loop
    FundamentalMusicEmbedding.java # Sinusoidal continuous embedding
    CompoundMidiEmbedding.java     # 6-parallel embedding layer
    HeadGroupConfig.java           # Per-group RoPE configuration
    GRUCell.java                   # Single GRU cell
    GRUDecoder.java                # 4-layer GRU output decoder
    MidiDataset.java               # Training dataset

engine/ml/src/test/java/org/almostrealism/ml/midi/test/
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
