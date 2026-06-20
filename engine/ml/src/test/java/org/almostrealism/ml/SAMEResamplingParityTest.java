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
import org.almostrealism.model.SequentialBlock;
import org.junit.Test;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Numerical-parity test for {@link TransformerResamplingFeatures} against the real released SAME-S
 * autoencoder. It runs the AR learned-resampling block on the genuine per-stage PyTorch activations
 * (captured by {@code engine/ml/scripts/dump_same_references.py} from a fixed seeded input) and asserts
 * the AR output matches each captured stage within an explicit tolerance.
 *
 * <p>This test is <b>gated</b>: it requires the gated SAME-S weights (dumped by
 * {@code dump_same_resampling_weights.py} to a local directory, ~214&nbsp;MB, never committed) and the
 * per-stage reference activations (dumped to the git-ignored
 * {@code src/test/resources/same-s-references} directory). When either is absent the test logs a skip
 * and returns &mdash; mirroring the project's other real-weight validation tests. Neither the weights
 * nor the references are checked into source control.</p>
 *
 * <p>Each stage is fed the <em>real upstream</em> PyTorch activation (not the AR output of the previous
 * stage), so a discrepancy localizes to exactly one stage. The full-block cases additionally chain all
 * stages from the block input to prove the composition (segmentation, midpoint-shifted chunking, and
 * extraction) is correct end-to-end.</p>
 */
public class SAMEResamplingParityTest extends SAMEResamplingTestBase {

	/** Candidate locations for the gated weight directory (first existing wins). */
	private static final String[] WEIGHT_DIRS = {
			System.getenv("AR_SAME_WEIGHTS"),
			"/workspace/same-weights"
	};

	/** Candidate locations for the per-stage reference directory (first existing wins). */
	private static final String[] REFERENCE_DIRS = {
			System.getenv("AR_SAME_REFERENCES"),
			"src/test/resources/same-s-references",
			"engine/ml/src/test/resources/same-s-references",
			"target/test-classes/same-s-references"
	};

	/** Tolerance (max absolute difference) for the channel-mapping stage. */
	private static final double TOL_MAPPING = 2e-3;
	/** Tolerance for the segmentation + learned-token stage. */
	private static final double TOL_SEGMENT = 2e-3;
	/** Tolerance for a single transformer layer. */
	private static final double TOL_LAYER = 2e-2;
	/** Tolerance for the full block (all stages chained). */
	private static final double TOL_BLOCK = 6e-2;

	/**
	 * Encoder parity: the channel mapping, the segmentation + learned tokens, one transformer layer
	 * (fed the real chunked activation), and the full block from the real block input.
	 *
	 * @throws IOException if a reference or weight file cannot be read
	 */
	@Test(timeout = 600000)
	public void encoderParity() throws IOException {
		File weightDir = firstExisting(WEIGHT_DIRS, "encoder.layers.0.mapping.weight.bin");
		File refDir = firstExisting(REFERENCE_DIRS, "enc_resamp_input.bin");
		if (weightDir == null || refDir == null) {
			log("skipping encoder parity; gated inputs absent (weights=" + weightDir + ", refs=" + refDir + ")");
			return;
		}

		ResamplingConfig config = sameEncoderConfig();
		String prefix = "encoder.layers.0";
		StateDictionary weights = loadBlockWeights(weightDir, config, prefix);

		PackedCollection blockInput = loadShaped(refDir, "enc_resamp_input", 1, 512, 96);
		PackedCollection afterMapping = loadShaped(refDir, "enc_after_mapping", 1, 768, 96);
		PackedCollection layerInput = loadShaped(refDir, "enc_layer0_input", 3, 34, 768);

		float[] refMapping = loadFlat(new File(refDir, "enc_after_mapping.bin").toPath());
		float[] refSegment = loadFlat(new File(refDir, "enc_seg_input.bin").toPath());
		float[] refLayer = loadFlat(new File(refDir, "enc_layer0_output.bin").toPath());
		float[] refOutput = loadFlat(new File(refDir, "enc_resamp_output.bin").toPath());

		// Stage 1: channel mapping (1x1 conv).
		PackedCollection mapping = eval(resamplingMapping(cp(blockInput), config, weights, prefix));
		report("encoder mapping", mapping, refMapping);
		assertWithin("encoder mapping", mapping, refMapping, TOL_MAPPING);

		// Stage 2: segmentation + learned tokens (fed the real mapping activation).
		PackedCollection segment = eval(resamplingSegment(cp(afterMapping), 1, config, weights, prefix));
		report("encoder segment", segment, refSegment);
		assertWithin("encoder segment", segment, refSegment, TOL_SEGMENT);

		// Stage 3: one transformer layer (fed the real chunked activation), via the materializing block.
		PackedCollection layer = evalBlock(resamplingLayerBlock(
				layerInput.getShape().length(0), config, weights, prefix + ".transformers.0"), layerInput);
		report("encoder layer0", layer, refLayer);
		assertWithin("encoder layer0", layer, refLayer, TOL_LAYER);

		// Stage 4: full block from the real block input.
		PackedCollection output = evalBlock(transformerResamplingBlock(1, 96, config, weights, prefix), blockInput);
		report("encoder block", output, refOutput);
		assertWithin("encoder block", output, refOutput, TOL_BLOCK);
	}

	/**
	 * Decoder parity, stage by stage so a discrepancy localizes to one stage: the segmentation +
	 * learned tokens (fed the real block input), the windowed transformer stack in isolation (fed the
	 * real post-segment activation), the learned-token extraction in isolation (fed the real
	 * post-stack activation), the {@code 3} -kernel mapping convolution in isolation (fed the real
	 * pre-mapping activation, built exactly as the full block builds it), one transformer layer (fed
	 * the real chunked activation), and the full decoder block from the real block input.
	 *
	 * @throws IOException if a reference or weight file cannot be read
	 */
	@Test(timeout = 600000)
	public void decoderParity() throws IOException {
		File weightDir = firstExisting(WEIGHT_DIRS, "decoder.layers.3.mapping.weight.bin");
		// TODO(review): gate marker only checks for dec_resamp_input.bin (from the original ref set);
		// dec_seg_input.bin, dec_poststack.bin, and dec_premap.bin are also required and were added
		// by the updated dump script. A stale ref dir missing these files yields IOException rather
		// than a clean skip. Consider expanding the marker or adding explicit file checks below.
		File refDir = firstExisting(REFERENCE_DIRS, "dec_resamp_input.bin");
		if (weightDir == null || refDir == null) {
			log("skipping decoder parity; gated inputs absent (weights=" + weightDir + ", refs=" + refDir + ")");
			return;
		}

		ResamplingConfig config = sameDecoderConfig();
		String prefix = "decoder.layers.3";
		StateDictionary weights = loadBlockWeights(weightDir, config, prefix);

		PackedCollection blockInput = loadShaped(refDir, "dec_resamp_input", 1, 768, 6);
		PackedCollection segInput = loadShaped(refDir, "dec_seg_input", 1, 102, 768);
		PackedCollection poststackInput = loadShaped(refDir, "dec_poststack", 1, 102, 768);
		PackedCollection premapInput = loadShaped(refDir, "dec_premap", 1, 768, 96);
		PackedCollection layerInput = loadShaped(refDir, "dec_layer0_input", 3, 34, 768);

		float[] refSegment = loadFlat(new File(refDir, "dec_seg_input.bin").toPath());
		float[] refPoststack = loadFlat(new File(refDir, "dec_poststack.bin").toPath());
		float[] refPremap = loadFlat(new File(refDir, "dec_premap.bin").toPath());
		// TODO(review): refMapping and refOutput intentionally load the same file. Mapping is the
		// last decoder stage, so its isolated output (Stage 4) equals the full block output (Stage 6).
		float[] refMapping = loadFlat(new File(refDir, "dec_resamp_output.bin").toPath());
		float[] refLayer = loadFlat(new File(refDir, "dec_layer0_output.bin").toPath());
		float[] refOutput = loadFlat(new File(refDir, "dec_resamp_output.bin").toPath());

		// Stage 1: segmentation + learned tokens (fed the real block input).
		PackedCollection segment = eval(resamplingSegment(cp(blockInput), 1, config, weights, prefix));
		report("decoder segment", segment, refSegment);

		// Stage 2: the windowed transformer stack in isolation (fed the real post-segment activation).
		SequentialBlock stackBlock = new SequentialBlock(shape(1, 102, 768));
		addResamplingTransformerStack(stackBlock, 1, config, weights, prefix);
		PackedCollection stack = evalBlock(stackBlock, segInput);
		report("decoder stack", stack, refPoststack);

		// Stage 3: learned-token extraction in isolation (fed the real post-stack activation).
		PackedCollection extract = eval(resamplingExtract(cp(poststackInput), 1, config));
		report("decoder extract", extract, refPremap);

		// Stage 4: the 3-kernel channel mapping in isolation, fed the real post-extract activation and
		// built exactly as transformerResamplingBlock builds it (the pad cell + projection cell), so the
		// kernel=3 path is verified independently of the transformer stack.
		SequentialBlock mappingBlock = new SequentialBlock(shape(1, 768, 96));
		addResamplingMapping(mappingBlock, config, weights, prefix, shape(1, 768, 96), shape(1, 512, 96));
		PackedCollection mapping = evalBlock(mappingBlock, premapInput);
		report("decoder mapping", mapping, refMapping);

		// Stage 5: one transformer layer (fed the real chunked activation).
		PackedCollection layer = evalBlock(resamplingLayerBlock(
				layerInput.getShape().length(0), config, weights, prefix + ".transformers.0"), layerInput);
		report("decoder layer0", layer, refLayer);

		// Stage 6: full decoder block from the real block input.
		PackedCollection output = evalBlock(transformerResamplingBlock(1, 6, config, weights, prefix), blockInput);
		report("decoder block", output, refOutput);

		assertWithin("decoder segment", segment, refSegment, TOL_SEGMENT);
		assertWithin("decoder stack", stack, refPoststack, TOL_LAYER);
		assertWithin("decoder extract", extract, refPremap, TOL_SEGMENT);
		assertWithin("decoder mapping", mapping, refMapping, TOL_MAPPING);
		assertWithin("decoder layer0", layer, refLayer, TOL_LAYER);
		assertWithin("decoder block", output, refOutput, TOL_BLOCK);
	}

	/**
	 * Loads every weight a resampling block reads from a directory of flat {@code .bin} tensors,
	 * shaping each according to the shared {@link #blockWeightShapes} map.
	 *
	 * @param dir    the weight directory
	 * @param config the block configuration
	 * @param prefix the weight key prefix
	 * @return a {@link StateDictionary} of the loaded weights
	 * @throws IOException if a weight file cannot be read
	 */
	protected StateDictionary loadBlockWeights(File dir, ResamplingConfig config, String prefix) throws IOException {
		Map<String, PackedCollection> w = new HashMap<>();
		for (Map.Entry<String, int[]> entry : blockWeightShapes(config, prefix).entrySet()) {
			w.put(entry.getKey(), loadShaped(dir, entry.getKey(), entry.getValue()));
		}
		return new StateDictionary(w);
	}

	/**
	 * Loads a flat {@code .bin} tensor into a {@link PackedCollection} of the given shape.
	 *
	 * @param dir   the directory containing {@code name.bin}
	 * @param name  the tensor name (without the {@code .bin} suffix)
	 * @param shape the target shape
	 * @return the loaded collection
	 * @throws IOException if the file cannot be read
	 */
	protected PackedCollection loadShaped(File dir, String name, int... shape) throws IOException {
		float[] data = loadFlat(new File(dir, name + ".bin").toPath());
		PackedCollection pc = new PackedCollection(shape(shape));
		if (data.length != pc.getShape().getTotalSize()) {
			throw new IllegalStateException(name + ": file has " + data.length
					+ " values but shape expects " + pc.getShape().getTotalSize());
		}
		pc.setMem(0, data, 0, data.length);
		return pc;
	}

	/**
	 * Reads a {@code [uint32 count][float32 ...]} little-endian reference file into a flat array.
	 *
	 * @param path the file path
	 * @return the flat float values
	 * @throws IOException if the file cannot be read
	 */
	protected float[] loadFlat(Path path) throws IOException {
		try (DataInputStream in = new DataInputStream(new FileInputStream(path.toFile()))) {
			byte[] header = new byte[4];
			in.readFully(header);
			int count = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt();
			byte[] payload = new byte[count * 4];
			in.readFully(payload);
			ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
			float[] values = new float[count];
			for (int i = 0; i < count; i++) {
				values[i] = buffer.getFloat(i * 4);
			}
			return values;
		}
	}

	/**
	 * Returns the first directory among {@code candidates} that exists and contains {@code marker}, or
	 * {@code null} if none do.
	 *
	 * @param candidates candidate directory paths (entries may be {@code null})
	 * @param marker     a file that must exist within the directory
	 * @return the resolved directory, or {@code null}
	 */
	protected File firstExisting(String[] candidates, String marker) {
		for (String candidate : candidates) {
			if (candidate == null) continue;
			File dir = new File(candidate);
			if (dir.isDirectory() && new File(dir, marker).exists()) {
				return dir;
			}
		}
		return null;
	}

	/**
	 * Logs the difference statistics for one stage.
	 *
	 * @param stage     the stage label
	 * @param actual    the computed collection
	 * @param reference the flat reference values
	 */
	protected void report(String stage, PackedCollection actual, float[] reference) {
		double[] stats = diffStats(actual, reference);
		log(String.format("%-18s maxAbs=%.3e meanAbs=%.3e rmse=%.3e (refMaxAbs=%.3e, n=%d)",
				stage, stats[0], stats[1], stats[2], stats[3], reference.length));
	}

	/**
	 * Asserts that the maximum absolute difference for one stage is within tolerance.
	 *
	 * @param stage     the stage label
	 * @param actual    the computed collection
	 * @param reference the flat reference values
	 * @param tolerance the maximum permitted absolute difference
	 */
	protected void assertWithin(String stage, PackedCollection actual, float[] reference, double tolerance) {
		double maxAbs = maxAbsDiff(actual, reference);
		if (maxAbs > tolerance) {
			throw new AssertionError(stage + " parity failed: maxAbs=" + maxAbs + " > tolerance=" + tolerance);
		}
	}
}
