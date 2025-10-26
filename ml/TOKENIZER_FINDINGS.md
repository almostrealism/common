# Tokenizer Investigation Findings (2025-10-26)

## Summary

The Qwen3 model runs end-to-end without crashing, but generates poor quality output. Investigation revealed the root cause is **incorrect tokenization**.

## Problem

**Expected behavior (PyTorch)**:
```
Input: "Hello"
Tokens: [9707]
Decoded: "Hello"
```

**Actual behavior (Our implementation)**:
```
Input: "Hello"  
Tokens: [1519, 654, 78]
Decoded: ['He', 'll', 'o']
```

## Root Cause

Our BPE tokenizer implementation is fundamentally incorrect:

### What We're Doing (Wrong)
1. Convert text to individual characters: "Hello" → ['H', 'e', 'l', 'l', 'o']
2. Apply GPT-2 byte encoding to each character
3. Look up each character in vocabulary
4. Apply BPE merges (but starting from wrong tokens)
5. Result: Partial merging only → ['He', 'll', 'o']

### What We Should Be Doing
1. **Pre-tokenization**: Split on regex pattern (words, punctuation, spaces)
2. **Byte-level encoding**: Convert to bytes, map to printable Unicode range
3. **Apply BPE merges**: Iteratively merge adjacent tokens based on learned vocabulary
4. **Result**: Single token for common words like "Hello"

## Technical Details

The Qwen tokenizer (from HuggingFace inspection):
```python
Tokenizer(
    pre_tokenizer=Sequence([
        Split(pattern=Regex("(?i:'s|'t|'re|'ve|'m|'ll|'d)|[^\r\n\p{L}\p{N}]?\p{L}+|\p{N}| ?[^\s\p{L}\p{N}]+[\r\n]*|\s*[\r\n]+|\s+..."), 
              behavior=Isolated),
        ByteLevel(add_prefix_space=False)
    ]),
    model=BPE(vocab={...}, merges=[...]),
    decoder=ByteLevel()
)
```

### Key Components Missing in Our Implementation

1. **Pre-tokenization regex**: Splits text intelligently before BPE
2. **Proper byte-level encoding**: Maps bytes to Unicode (U+0100 - U+017F range)
3. **Correct BPE merge algorithm**: Uses pre-tokenized segments, not individual characters

## Impact on Model

Because the tokenizer produces wrong tokens:
- Input to model is completely different from PyTorch
- Model sees "He" + "ll" + "o" instead of "Hello"
- Different embeddings are used
- Model generates completely different (incorrect) output

### Example
```
Prompt: "Hello"
PyTorch: [9707] → model processes 1 token
Our impl: [1519, 654, 78] → model processes 3 different tokens
Result: Completely different generation paths
```

## Logits Comparison Test Results

**Test**: `Qwen3LogitsTest#testLogitsForHello`

```
PyTorch Reference:
  Input: "Hello" → [9707]
  Top prediction: Token 271 ("\n\n") with logit 12.84

Our Implementation:
  Input: "Hello" → [1519, 654, 78]
  Top prediction: Token 27 ("<") 
  
Result: MISMATCH
```

## Solutions

### Option 1: Use HuggingFace Tokenizers Library (Recommended)
- Use `tokenizers` Rust library via JNI
- Guarantees compatibility with HuggingFace models
- Handles all edge cases correctly
- **Complexity**: Medium (requires JNI bindings)

### Option 2: Implement Proper Byte-Level BPE
- Study HuggingFace tokenizers source code
- Implement pre-tokenization regex
- Implement correct byte-level encoding
- Fix BPE merge algorithm
- **Complexity**: High (weeks of work)

### Option 3: Pre-tokenize with Python (Workaround)
- Use Python script to tokenize text
- Pass token IDs to Java model
- **Complexity**: Low (but not standalone)

### Option 4: Hybrid Approach
- Implement basic pre-tokenization (split on spaces/punctuation)
- Fix byte-level encoding to match GPT-2 spec
- Improve BPE merge algorithm
- **Complexity**: Medium-High

## Current Status

- ✅ Model architecture working (24 layers, GQA, QK-Norm)
- ✅ Weight loading correct (Q/K/V biases, all components validated)
- ✅ Generation runs without crashing
- ✅ BPE merges loading (151,291 rules)
- ❌ **Tokenizer produces incorrect tokens**
- ❌ Generation quality poor due to tokenizer

## Immediate Next Steps

1. **Document this finding** in PLAN.md ✓
2. **Choose tokenizer fix strategy** (recommend Option 1 or 3)
3. **Implement chosen solution**
4. **Re-test logits comparison**
5. **Validate generation quality**

## Test Files Created

- `/workspace/project/common/ml/generate_logits_reference.py` - PyTorch reference generator
- `/workspace/project/common/ml/src/test/java/.../Qwen3LogitsTest.java` - Logits comparison
- `/workspace/project/common/ml/src/test/java/.../Qwen3TokenizerDebugTest.java` - Tokenizer debug

## References

- HuggingFace Tokenizers: https://github.com/huggingface/tokenizers
- GPT-2 Byte-Level BPE: https://github.com/openai/gpt-2/blob/master/src/encoder.py
- Qwen2 Tokenizer: transformers.models.qwen2.tokenization_qwen2_fast.py
