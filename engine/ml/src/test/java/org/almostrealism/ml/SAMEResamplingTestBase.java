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

import io.almostrealism.code.MemoryProvider;
import io.almostrealism.code.Precision;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.ParallelProcess;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.mem.ByteBufferTransfer;
import org.almostrealism.hardware.mem.Bytes;
import org.almostrealism.hardware.mem.DirectMemory;
import org.almostrealism.hardware.mem.RAM;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Shared infrastructure for the {@link TransformerResamplingFeatures} tests: the SAME-S encoder and
 * decoder {@link ResamplingConfig}s, a top-of-stack producer evaluator, and flat reference-comparison
 * statistics. Subclasses supply either synthetic weights (the in-CI shape/composition test) or the
 * real released SAME weights (the gated numerical-parity test).
 */
public abstract class SAMEResamplingTestBase extends TestSuiteBase implements TransformerResamplingFeatures {

	/**
	 * Evaluates a producer at the test boundary (top of the call stack), applying the optimization pass
	 * the framework requires for standalone producer evaluation.
	 *
	 * @param producer the producer to evaluate
	 * @return the evaluated collection
	 */
	protected PackedCollection eval(Producer<PackedCollection> producer) {
		return ((Evaluable<PackedCollection>) ((ParallelProcess) producer).optimize().get()).evaluate();
	}

	/**
	 * Compiles a resampling {@link Block} into an inference {@link Model} and runs a single forward pass
	 * on {@code input}. This is the top-of-stack boundary at which compilation and evaluation are
	 * permitted; the block itself never evaluates.
	 *
	 * @param block the resampling block (from {@link TransformerResamplingFeatures#transformerResamplingBlock})
	 * @param input the block input, matching {@link Block#getInputShape()}
	 * @return the forward-pass output
	 */
	protected PackedCollection evalBlock(Block block, PackedCollection input) {
		Model model = new Model(block.getInputShape());
		model.add(block);
		CompiledModel compiled = model.compile(false);
		return compiled.forward(input);
	}

	/**
	 * The SAME-S encoder resampling-block configuration: {@code 512 -> 768} channels, stride 16,
	 * 12 heads of dimension 64, depth 6, chunk size 32 with midpoint shift, {@code 1x1} mapping.
	 *
	 * @return the encoder configuration
	 */
	protected ResamplingConfig sameEncoderConfig() {
		return new ResamplingConfig(512, 768, 12, 64, 16, 32, 6,
				true, true, true, 3.0, 1, ResamplingConfig.AttentionWindow.CHUNKED);
	}

	/**
	 * The SAME-S decoder resampling-block configuration: {@code 768 -> 512} channels, stride 16,
	 * 12 heads of dimension 64, depth 6, chunk size 32 with midpoint shift, {@code 3} -kernel mapping.
	 *
	 * @return the decoder configuration
	 */
	protected ResamplingConfig sameDecoderConfig() {
		return new ResamplingConfig(768, 512, 12, 64, 16, 32, 6,
				false, true, true, 3.0, 3, ResamplingConfig.AttentionWindow.CHUNKED);
	}

	/**
	 * The full set of weight keys a resampling block reads, mapped to their tensor shapes. Both the
	 * synthetic-weight shape test and the real-weight parity test build their {@link StateDictionary}
	 * from this single source so the key set and shapes cannot drift between them.
	 *
	 * @param config the block configuration
	 * @param prefix the weight key prefix (e.g. {@code "encoder.layers.0"})
	 * @return an insertion-ordered map of weight key to shape
	 */
	protected Map<String, int[]> blockWeightShapes(ResamplingConfig config, String prefix) {
		return config.weightShapes(prefix);
	}

	/**
	 * Generates a complete set of synthetic weights for a resampling block under the given key prefix,
	 * using {@link #blockWeightShapes} so the synthetic key set matches the real one exactly.
	 *
	 * @param config the block configuration
	 * @param prefix the weight key prefix
	 * @return a {@link StateDictionary} holding randomly-initialized weights for every key the block reads
	 */
	protected StateDictionary syntheticWeights(ResamplingConfig config, String prefix) {
		Map<String, PackedCollection> w = new HashMap<>();
		blockWeightShapes(config, prefix).forEach((key, dims) ->
				w.put(key, new PackedCollection(shape(dims)).randnFill()));
		return new StateDictionary(w);
	}

	/**
	 * Builds a small configuration that still exercises the full resampling machinery (segmentation,
	 * learned tokens, midpoint-shifted chunking, differential attention, SwiGLU) at dimensions cheap
	 * enough for fast, in-CI tests.
	 *
	 * @param encoder {@code true} for an encoder (downsampling) config
	 * @return the small configuration
	 */
	public static ResamplingConfig smallConfig(boolean encoder) {
		int inChannels = encoder ? 4 : 8;
		int outChannels = encoder ? 8 : 4;
		int mappingKernel = encoder ? 1 : 3;
		return new ResamplingConfig(inChannels, outChannels, 2, 4, 2, 4, 2,
				encoder, true, true, 2.0, mappingKernel, ResamplingConfig.AttentionWindow.CHUNKED);
	}

	/**
	 * Computes element-wise difference statistics between a flattened computed collection and a flat
	 * reference array of the same length.
	 *
	 * @param actual    the computed collection
	 * @param reference the flat reference values
	 * @return {@code [maxAbsDiff, meanAbsDiff, rmse, maxAbsReference]}
	 */
	protected double[] diffStats(PackedCollection actual, float[] reference) {
		double[] a = actual.toArray(0, actual.getShape().getTotalSize());
		if (a.length != reference.length) {
			throw new IllegalArgumentException(
					"Length mismatch: computed " + a.length + " vs reference " + reference.length);
		}

		double maxAbs = 0;
		double sumAbs = 0;
		double sumSq = 0;
		double maxRef = 0;
		for (int i = 0; i < a.length; i++) {
			double d = Math.abs(a[i] - reference[i]);
			maxAbs = Math.max(maxAbs, d);
			sumAbs += d;
			sumSq += d * d;
			maxRef = Math.max(maxRef, Math.abs(reference[i]));
		}
		return new double[]{maxAbs, sumAbs / a.length, Math.sqrt(sumSq / a.length), maxRef};
	}

	/**
	 * Convenience wrapper returning the maximum absolute difference between a computed collection and a
	 * flat reference.
	 *
	 * @param actual    the computed collection
	 * @param reference the flat reference values
	 * @return the maximum absolute difference
	 */
	protected double maxAbsDiff(PackedCollection actual, float[] reference) {
		return diffStats(actual, reference)[0];
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
		ByteBuffer data = loadBuffer(new File(dir, name + ".bin").toPath());
		TraversalPolicy resultShape = shape(shape);
		int count = data.remaining() / 4;
		if (count != resultShape.getTotalSize()) {
			throw new IllegalStateException(name + ": file has " + count
					+ " values but shape expects " + resultShape.getTotalSize());
		}

		MemoryProvider<? extends RAM> provider =
				Hardware.getLocalHardware().getNativeBufferMemoryProvider();
		RAM mem = provider.allocate(count);

		ByteBuffer staging = ((DirectMemory) mem).asByteBuffer();
		new ByteBufferTransfer(data, Precision.FP32, staging,
				Precision.ofBytes(provider.getNumberSize())).copyAll();

		return new PackedCollection(resultShape, resultShape.getTraversalAxis(),
				Bytes.of(mem, count), 0);
	}

	/**
	 * Reads a {@code [uint32 count][float32 ...]} little-endian reference file into a flat array.
	 *
	 * @param path the file path
	 * @return the flat float values
	 * @throws IOException if the file cannot be read
	 */
	protected float[] loadFlat(Path path) throws IOException {
		return ReferenceActivations.load(path);
	}

	/**
	 * Reads a {@code [uint32 count][float32 ...]} little-endian reference file
	 * into a buffer positioned over the payload values.
	 *
	 * @param path the file path
	 * @return a little-endian buffer holding the payload values
	 * @throws IOException if the file cannot be read
	 */
	protected ByteBuffer loadBuffer(Path path) throws IOException {
		return ReferenceActivations.loadBuffer(path);
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
		return ReferenceActivations.firstExisting(candidates, marker);
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
		// maxAbs > tolerance is false when maxAbs is NaN, so a NaN result (a real computation
		// failure) would otherwise silently pass; negating a <= comparison catches it.
		if (!(maxAbs <= tolerance)) {
			throw new AssertionError(stage + " parity failed: maxAbs=" + maxAbs + " > tolerance=" + tolerance);
		}
	}

	/**
	 * Reports one stage against its reference and asserts parity within a fraction of the
	 * reference magnitude. A computed collection of a different size fails outright, since it
	 * means the capture and the reference dump disagree about the layout.
	 *
	 * @param stage             the stage label
	 * @param actual            the computed collection
	 * @param reference         the flat reference values
	 * @param relativeTolerance the permitted largest absolute error as a fraction of the largest
	 *                          reference magnitude
	 */
	protected void assertWithinRelative(String stage, PackedCollection actual, float[] reference,
										double relativeTolerance) {
		if (actual.getShape().getTotalSize() != reference.length) {
			throw new AssertionError(stage + ": computed " + actual.getShape() +
					" while the reference has " + reference.length + " values");
		}

		report(stage, actual, reference);
		assertWithin(stage, actual, reference, relativeTolerance * diffStats(actual, reference)[3]);
	}
}
