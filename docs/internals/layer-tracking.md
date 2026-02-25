# Layer IO Tracking

This document explains how `DefaultCellularLayer` tracks input and output data during the forward pass, and how this tracking is configured for training vs inference.

## What is Layer Tracking?

When a neural network layer executes its forward pass, the input data it receives may be overwritten by subsequent operations. Backpropagation needs access to the original input to compute gradients. **Input tracking** solves this by copying the input into a dedicated buffer before the forward computation runs.

Similarly, **output tracking** copies the forward cell's result into a stable output buffer so downstream consumers can read it after the forward pass completes.

## Entry/Exit Cell Architecture

`DefaultCellularLayer` wraps the core forward cell with two additional cells:

```
Input → [Entry Cell] → [Forward Cell] → [Exit Cell] → Output
```

| Cell | Purpose |
|------|---------|
| **Entry Cell** | Optionally copies input to a tracking buffer, then forwards to the forward cell |
| **Forward Cell** | The actual computation (e.g., matrix multiply for a dense layer) |
| **Exit Cell** | Copies the forward cell's output into the output buffer |

When `getForward()` is called on a `DefaultCellularLayer`, it returns a composite cell that pushes input through the entry cell and produces output from the output buffer.

### With Input Tracking (Training Mode)

```
Input → copy to input buffer → forward(input buffer) → copy to output buffer → Output
                 ↓
         BackPropagationCell reads input buffer during backward pass
```

### Without Input Tracking (Inference Mode)

```
Input → forward(input) → copy to output buffer → Output
```

The input copy is eliminated entirely. The entry cell becomes a simple pass-through that forwards its input directly to the forward cell.

## Training vs Inference Compilation

The tracking decision is made at compilation time in `CompiledModel.compile()`:

1. **Training** (`backprop=true`): Input tracking remains enabled (the default from `init()`). The `BackPropagationCell` reads from the input buffer during the backward pass.
2. **Inference** (`backprop=false`): `CompiledModel` calls `setInputTracking(false)` on every `DefaultCellularLayer` before building and optimizing the forward chain. This structurally rebuilds the entry cell as a pass-through.

### Why Construction-Time, Not Runtime

The entry cell must be structurally different between training and inference modes — not a runtime branch. This matters because:

- The computation graph optimizer sees a clean structure with no dead branches
- Expression tree optimization can fully inline the pass-through path
- No mutable state is read during graph evaluation
- The compiled operation is smaller and faster

A runtime `if (trackingEnabled)` branch inside the entry cell would prevent the optimizer from eliminating the tracking code path, even when it's never taken.

## `setInputTracking(boolean)`

`DefaultCellularLayer.setInputTracking(boolean)` performs a structural rebuild:

1. Updates the `inputTrackingEnabled` flag
2. Allocates or destroys the input buffer as needed
3. Clears the cached `fw` (composite forward cell)
4. Rebuilds the entry cell — either as a tracking cell or a pass-through
5. Re-wires the entry cell's receptor to the forward cell

This must be called **before** the computation graph is optimized. `CompiledModel.compile()` ensures this by calling `configureTracking()` before `forward.flatten().optimize()`.

## Performance Impact

In profiled Qwen models, the input copy operations account for approximately 18% of forward pass time. Disabling tracking for inference eliminates these copies, providing a measurable speedup.

## DefaultCellularLayer vs DefaultBlock

| Feature | `DefaultCellularLayer` | `DefaultBlock` |
|---------|----------------------|----------------|
| Input tracking | Yes (configurable) | No |
| Output buffer | Yes | No |
| Entry/exit cells | Yes | No |
| Weights | Yes | Optional |
| Backpropagation | Full support | Limited |
| Use case | Trainable layers | Stateless transformations |

Use `DefaultBlock` for reshape, scale, enumerate, and other operations that don't need tracked input for gradient computation. Use `DefaultCellularLayer` (via `LayerFeatures.layer()`) for dense, norm, convolution, and other trainable layers.
