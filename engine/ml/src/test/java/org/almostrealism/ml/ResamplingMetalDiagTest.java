/*
 * Copyright 2026 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.ml;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.CellularPropagation;
import org.almostrealism.layers.DefaultCellularLayer;
import org.almostrealism.model.Block;
import org.almostrealism.model.BranchBlock;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Temporary Metal localization diagnostic for the resampling midpoint-shift NaN. NOT a permanent test;
 * removed before completion.
 *
 * <p>TODO(review): Remove this class before merge — all four test methods unconditionally throw
 * AssertionError (by design, to surface the diagnostic report), which will fail CI.</p> Output monitoring hides the bug (its per-cell host read acts as a barrier),
 * so this instead forwards under plain inference (no monitoring) and, after a forward whose final output
 * is non-finite, reads each materialized {@link DefaultCellularLayer#getOutput()} buffer directly to find
 * the first cell whose output is already non-finite. Reading buffers after the forward completes does not
 * perturb the computation.
 */
public class ResamplingMetalDiagTest extends SAMEResamplingTestBase {

	/** Accumulated report. */
	private final List<String> report = new ArrayList<>();

	/** Number of forward passes to attempt before giving up on observing a non-finite output. */
	private static final int REPS = 30;

	/** The encoder shape-test configuration (matches TransformerResamplingShapeTest.smallConfig(true)). */
	private ResamplingConfig encConfig() {
		return new ResamplingConfig(4, 8, 2, 4, 2, 4, 2,
				true, true, true, 2.0, 1, ResamplingConfig.AttentionWindow.CHUNKED);
	}

	/** The decoder shape-test configuration (matches TransformerResamplingShapeTest.smallConfig(false)). */
	private ResamplingConfig decConfig() {
		return new ResamplingConfig(8, 4, 2, 4, 2, 4, 2,
				false, true, true, 2.0, 3, ResamplingConfig.AttentionWindow.CHUNKED);
	}

	/**
	 * Builds synthetic weights for the given config under the prefix.
	 *
	 * @param config the configuration
	 * @param prefix the key prefix
	 * @return the synthetic state dictionary
	 */
	private StateDictionary synth(ResamplingConfig config, String prefix) {
		Map<String, PackedCollection> w = new HashMap<>();
		blockWeightShapes(config, prefix).forEach((key, dims) ->
				w.put(key, new PackedCollection(shape(dims)).randnFill()));
		return new StateDictionary(w);
	}

	/**
	 * Counts the non-finite values in a collection.
	 *
	 * @param c the collection
	 * @return the count of NaN/infinite values
	 */
	private int nonFinite(PackedCollection c) {
		double[] v = c.toArray(0, c.getShape().getTotalSize());
		int bad = 0;
		for (double x : v) {
			if (!Double.isFinite(x)) bad++;
		}
		return bad;
	}

	/**
	 * Recursively collects every {@link DefaultCellularLayer} in the block tree in construction order.
	 *
	 * @param block the block (or child propagation) to descend into
	 * @param out   the accumulating list of layers
	 */
	private void collectLayers(Object block, List<DefaultCellularLayer> out) {
		if (block instanceof DefaultCellularLayer) {
			out.add((DefaultCellularLayer) block);
		} else if (block instanceof SequentialBlock) {
			for (Block b : ((SequentialBlock) block).getBlocks()) collectLayers(b, out);
		} else if (block instanceof BranchBlock) {
			for (CellularPropagation<PackedCollection> c : ((BranchBlock) block).getChildren()) collectLayers(c, out);
		}
	}

	/**
	 * Compiles a resampling block under inference compilation, forwards it until a non-finite final output
	 * is observed, then reports every materialized cell output that is non-finite (the earliest in the
	 * list is the source).
	 *
	 * @param label  the report label
	 * @param config the block configuration
	 * @param prefix the weight key prefix
	 * @param length the input sequence length
	 */
	private void pinpointBlock(String label, ResamplingConfig config, String prefix, int length,
							  boolean clearOutputs) {
		StateDictionary w = synth(config, prefix);
		Block block = transformerResamplingBlock(1, length, config, w, prefix);
		Model model = new Model(block.getInputShape());
		model.add(block);

		List<DefaultCellularLayer> layers = new ArrayList<>();
		for (Block b : model.getBlocks()) collectLayers(b, layers);

		CompiledModel compiled = model.compile(false);

		if (clearOutputs) {
			for (DefaultCellularLayer layer : layers) {
				if (layer.getOutput() != null) layer.getOutput().clear();
			}
		}

		int nanRuns = 0;
		int reportedRun = -1;
		for (int i = 0; i < REPS; i++) {
			PackedCollection in = new PackedCollection(shape(1, config.getInChannels(), length)).randnFill();
			int bad = nonFinite(compiled.forward(in));
			if (bad > 0) {
				nanRuns++;
				if (reportedRun < 0) {
					reportedRun = i;
					report.add(label + " run " + i + " finalNaN=" + bad + " cell outputs:");
					for (DefaultCellularLayer layer : layers) {
						PackedCollection out = layer.getOutput();
						if (out == null) continue;
						int n = nonFinite(out);
						if (n > 0) {
							report.add("  NaN " + layer.getName() + " " + out.getShape()
									+ " bad=" + n + "/" + out.getShape().getTotalSize());
						}
					}
				}
			}
		}
		report.add(label + ": nanRuns=" + nanRuns + "/" + REPS);
	}

	/**
	 * Pinpoints the first non-finite cell output in the encoder block under plain inference (no monitoring).
	 */
	@Test(timeout = 600000)
	public void pinpointEncoder() {
		pinpointBlock("encoder", encConfig(), "encP", 8, false);
		throw new AssertionError("DIAG pinpointEncoder\n" + String.join("\n", report));
	}

	/**
	 * Pinpoints the first non-finite cell output in the decoder block under plain inference (no monitoring).
	 */
	@Test(timeout = 600000)
	public void pinpointDecoder() {
		pinpointBlock("decoder", decConfig(), "decP", 4, false);
		throw new AssertionError("DIAG pinpointDecoder\n" + String.join("\n", report));
	}

	/**
	 * Tests whether zero-initialising each materialized cell output buffer once (after compile, before the
	 * forwards) eliminates the decoder NaN. Baseline (pinpointDecoder) reliably NaNs; if this drops to
	 * nanRuns=0 the under-written buffer is the materialized cell output and zero-init is the fix.
	 */
	@Test(timeout = 600000)
	public void decoderClearedOutputs() {
		pinpointBlock("decoderCleared", decConfig(), "decC", 4, true);
		throw new AssertionError("DIAG decoderClearedOutputs\n" + String.join("\n", report));
	}

	/**
	 * The encoder counterpart of {@link #decoderClearedOutputs()}.
	 */
	@Test(timeout = 600000)
	public void encoderClearedOutputs() {
		pinpointBlock("encoderCleared", encConfig(), "encC", 8, true);
		throw new AssertionError("DIAG encoderClearedOutputs\n" + String.join("\n", report));
	}
}
