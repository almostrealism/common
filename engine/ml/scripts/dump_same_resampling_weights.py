#!/usr/bin/env python3
"""Dump the SAME autoencoder resampling-block weights for the Block C2 Java parity test.

Reads the gated SAME-S ``.safetensors`` checkpoint, folds the PyTorch weight-norm
``(weight_g, weight_v)`` pairs on the ``mapping`` convolutions (reusing
:func:`safetensors_extractor.fold_weight_norm`), and writes every ``encoder.*`` and
``decoder.*`` tensor as a ``[uint32 count][float32 ...]`` file — the same simple
format the reference activations use (:func:`safetensors_extractor.save_reference_output`).
A companion ``weight_shapes.json`` records each tensor's shape so the Java test can
rebuild a ``StateDictionary`` (via its ``Map`` constructor) from the flat ``.bin`` files.

These weights are ~214MB, so the output directory is **NOT committed** — it is a
gated, local-only input to the parity test (the test skips when the directory is
absent, mirroring the project's other real-weight tests). Reference activations are
also NOT committed; regenerate them locally with dump_same_references.py.

Usage::

    python dump_same_resampling_weights.py \
        --weights /path/to/SAME-S/model.safetensors \
        --out     /workspace/same-weights
"""

import argparse
import json
import os
import sys

import numpy as np
from safetensors import safe_open

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import safetensors_extractor as core


def main():
    parser = argparse.ArgumentParser(
        description="Dump SAME resampling-block weights as raw .bin for the Block C2 parity test")
    parser.add_argument("--weights", required=True, help="Path to SAME-S model.safetensors")
    parser.add_argument("--out", required=True, help="Output directory for weight .bin files")
    args = parser.parse_args()

    reader = safe_open(args.weights, framework="numpy")
    state = {key: reader.get_tensor(key) for key in reader.keys()}

    # Fold weight-norm (weight_g, weight_v) -> weight for the mapping convolutions.
    state = core.fold_weight_norm()(state)

    os.makedirs(args.out, exist_ok=True)
    shapes = {}
    written = 0
    for key, array in state.items():
        if not (key.startswith("encoder.") or key.startswith("decoder.")):
            continue
        value = np.asarray(array, dtype=np.float32)
        core.save_reference_output(value, os.path.join(args.out, key + ".bin"))
        shapes[key] = list(value.shape)
        written += 1

    with open(os.path.join(args.out, "weight_shapes.json"), "w") as f:
        json.dump(shapes, f, indent=1, sort_keys=True)

    print("Wrote %d weight tensors to %s" % (written, args.out))


if __name__ == "__main__":
    main()
