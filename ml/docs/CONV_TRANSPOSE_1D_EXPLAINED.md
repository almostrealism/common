# ConvTranspose1d Explained

## What is ConvTranspose1d?

ConvTranspose1d (also called "deconvolution" or "fractionally-strided convolution") is the
*gradient* of a regular convolution. It's commonly used for upsampling in neural networks.

Key insight: If `Conv1d` with stride=2 reduces sequence length by half, then `ConvTranspose1d`
with stride=2 doubles it.

---

## Simple Example: stride=2, kernelSize=4

### Setup

```
Input:      [a, b]           shape: (2,)
Kernel:     [w0, w1, w2, w3] shape: (4,)
Stride:     2
Output:     [?, ?, ?, ?, ?]  shape: (5,)
```

### Step 1: Upsample (insert zeros)

Place each input element `stride` positions apart:

```
Upsampled: [a, 0, b, 0]
            ^     ^
            |     |
            a     b (original positions)
```

### Step 2: Pad for full convolution

Pad with `kernelSize - 1 = 3` zeros on each side:

```
Padded: [0, 0, 0, a, 0, b, 0, 0, 0, 0]
         ^-----^           ^--------^
         left pad          right pad
```

### Step 3: Convolve (slide kernel across padded input)

```
Position 0: kernel [w0, w1, w2, w3] over [0, 0, 0, a]
            output[0] = 0*w0 + 0*w1 + 0*w2 + a*w3 = a*w3

Position 1: kernel [w0, w1, w2, w3] over [0, 0, a, 0]
            output[1] = 0*w0 + 0*w1 + a*w2 + 0*w3 = a*w2

Position 2: kernel [w0, w1, w2, w3] over [0, a, 0, b]
            output[2] = 0*w0 + a*w1 + 0*w2 + b*w3 = a*w1 + b*w3

Position 3: kernel [w0, w1, w2, w3] over [a, 0, b, 0]
            output[3] = a*w0 + 0*w1 + b*w2 + 0*w3 = a*w0 + b*w2

Position 4: kernel [w0, w1, w2, w3] over [0, b, 0, 0]
            output[4] = 0*w0 + b*w1 + 0*w2 + 0*w3 = b*w1
```

### Final Output

```
output = [a*w3, a*w2, a*w1 + b*w3, a*w0 + b*w2, b*w1]
```

---

## Multi-Channel Example

With multiple input channels and output channels:

```
Input shape:  (batch=1, inputChannels=2, seqLen=2)
Filter shape: (inputChannels=2, outputChannels=3, kernelSize=4)
Output shape: (batch=1, outputChannels=3, outLen=5)
```

### The Computation

For each output position `o` and output channel `oc`:

```
output[0, oc, o] = sum over ic=0..1, k=0..3 of:
                   upsampled_input[0, ic, o+k] * filter[ic, oc, k]
```

This is `inputChannels * kernelSize = 2 * 4 = 8` multiply-adds per output element.

---

## The Large Channel Problem

For the Oobleck decoder's first block:

```
Input shape:  (batch=1, inputChannels=2048, seqLen=2)
Filter shape: (inputChannels=2048, outputChannels=1024, kernelSize=16)
Output shape: (batch=1, outputChannels=1024, outLen=33)
```

For each of the `1024 * 33 = 33,792` output elements:

```
output[0, oc, o] = sum over ic=0..2047, k=0..15 of:
                   upsampled_input[0, ic, o+k] * filter[ic, oc, k]
```

This is **32,768 multiply-adds per output element**.

---

## Current Implementation Analysis

The current `weightedSum` approach does this:

```java
// For each output element, sum over inputChannels * kernelSize values
groupShape = shape(1, inputChannels, 1, kernelSize);  // 2048 * 16 = 32K elements

weightedSum("convTranspose1dFilter",
    inputPositions, filterPositions,
    groupShape, conv, filter)
```

**Problem**: `weightedSum` generates 32K unrolled operations per output element.

---

## Alternative: Split into Two Steps

### Approach

Step 1: For each (inputChannel, outputChannel, outputPosition), sum over just kernelSize:
```
intermediate[ic, oc, o] = sum over k=0..15 of:
                          upsampled_input[ic, o+k] * filter[ic, oc, k]
```
This is a small weightedSum (16 elements).

Step 2: Sum over inputChannels:
```
output[oc, o] = sum over ic=0..2047 of: intermediate[ic, oc, o]
```
This uses `sum()` which should be loop-based.

### The Shape Problem

After Step 1, intermediate shape is:
```
(batch=1, inputChannels=2048, outputChannels=1024, outLen=33)
= 68,878,336 elements
```

**Question**: Is the problem that generating 68M elements is slow, or is there
something else going on?

---

## Key Questions

1. Does `sum()` actually generate a loop? Or does it unroll for large inputs?

2. For the two-step approach, the intermediate tensor has 68M elements. Is that
   inherently problematic, or should the framework handle it efficiently?

3. Is there a way to avoid materializing the 68M intermediate tensor entirely?

---

## Matrix Multiplication View

ConvTranspose1d can be expressed as matrix multiplication using im2col:

```
# Extract sliding windows from upsampled input
windows[o, ic*k] = upsampled_input[ic, o+k]
# Shape: (outLen, inputChannels * kernelSize) = (33, 32768)

# Reshape filters
filters_flat[ic*k, oc] = filter[ic, oc, k]
# Shape: (inputChannels * kernelSize, outputChannels) = (32768, 1024)

# Matrix multiply
output = windows @ filters_flat
# Shape: (33, 1024)
```

This is exactly what efficient convolution implementations do (im2col + GEMM).
