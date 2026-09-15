/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.ml.dsl;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.RotationFeatures;
import org.almostrealism.ml.midi.HeadGroupConfig;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Pins each tensor primitive that {@code attention.pdsl} is composed from, on its own,
 * through the single-primitive layers of {@code test_attention_primitives.pdsl}: the
 * split-half rotary layout and its inverse, row duplication, the cache write, rotary
 * embedding (shared table and per-head-group tables), attention scores, the causal mask,
 * row-wise softmax, the weighted sum of a value cache, configuration-time {@code sqrt}, and
 * a projection whose bias is bound to {@code null}.
 *
 * <p>Every expectation is computed on the host from values read back off the device, so a
 * primitive is checked against its definition rather than against another primitive. Head
 * counts of two and four are both used where the layout depends on them.</p>
 */
public class AttentionPrimitivesTest extends TestSuiteBase implements AttentionFeatures {

	/** Absolute tolerance between single-precision kernels and double-precision expectations. */
	private static final double TOLERANCE = 1e-5;

	/** Classpath location of the fixture program. */
	private static final String FIXTURE = "/pdsl/test_attention_primitives.pdsl";

	/**
	 * {@code split_half_rope} pairs element {@code f} of a head with element
	 * {@code f + head_size / 2}: output {@code [h, f, 0]} is the first half, {@code [h, f, 1]}
	 * the second.
	 */
	@Test(timeout = 120000)
	public void splitHalfRopePairsHalvesOfEachHead() {
		for (int heads : new int[] { 2, 4 }) {
			int headSize = 4;
			PackedCollection input = ramp(shape(1, heads * headSize));
			double[] x = input.toArray();
			double[] actual = run("half_rope_split", shape(1, heads * headSize),
					args("heads", heads, "head_size", headSize), input);

			double[] expected = new double[heads * headSize];
			for (int h = 0; h < heads; h++) {
				for (int f = 0; f < headSize / 2; f++) {
					expected[(h * (headSize / 2) + f) * 2] = x[h * headSize + f];
					expected[(h * (headSize / 2) + f) * 2 + 1] = x[h * headSize + headSize / 2 + f];
				}
			}
			assertClose("split heads=" + heads, expected, actual);
		}
	}

	/** {@code merge_half_rope} undoes {@code split_half_rope}. */
	@Test(timeout = 120000)
	public void mergeHalfRopeInvertsTheSplit() {
		for (int heads : new int[] { 2, 4 }) {
			int headSize = 6;
			PackedCollection input = ramp(shape(1, heads * headSize));
			double[] actual = run("half_rope_roundtrip", shape(1, heads * headSize),
					args("heads", heads, "head_size", headSize), input);
			assertClose("roundtrip heads=" + heads, input.toArray(), actual);
		}
	}

	/** {@code repeat_each(n)} places the {@code n} copies of a row next to each other. */
	@Test(timeout = 120000)
	public void repeatEachDuplicatesRowsConsecutively() {
		PackedCollection input = pack(shape(2, 3), 1.0, 2.0, 3.0, 4.0, 5.0, 6.0);
		double[] actual = run("repeat_rows", shape(2, 3),
				args("rows", 2, "size", 3, "n", 2), input);
		assertClose("repeat_each(2)", new double[] {
				1.0, 2.0, 3.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 4.0, 5.0, 6.0 }, actual);

		double[] single = run("repeat_rows", shape(2, 3),
				args("rows", 2, "size", 3, "n", 1), input);
		assertClose("repeat_each(1)", input.toArray(), single);
	}

	/**
	 * {@code cache_write} replaces row {@code position} of the caller-owned cache on every
	 * forward pass, keeps every other row, and passes its input through unchanged.
	 */
	@Test(timeout = 120000)
	public void cacheWriteRecordsOneRowPerPass() {
		PackedCollection cache = new PackedCollection(shape(4, 3));
		PackedCollection position = new PackedCollection(shape(1));
		Map<String, Object> args = args("size", 3, "position", p(position));
		args.put("row_cache", cache);
		CompiledModel model = compile("write_row", shape(1, 3), args);

		PackedCollection[] rows = {
				pack(shape(1, 3), 1.0, 2.0, 3.0),
				pack(shape(1, 3), 4.0, 5.0, 6.0),
				pack(shape(1, 3), 7.0, 8.0, 9.0) };
		for (int step = 0; step < rows.length; step++) {
			position.fill(step);
			double[] output = model.forward(rows[step]).toArray();
			assertClose("pass-through at " + step, rows[step].toArray(), output);
		}
		assertClose("cache after three passes", new double[] {
				1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 0.0, 0.0, 0.0 }, cache.toArray());

		position.fill(1);
		model.forward(pack(shape(1, 3), 10.0, 11.0, 12.0));
		assertClose("row 1 rewritten", new double[] {
				1.0, 2.0, 3.0, 10.0, 11.0, 12.0, 7.0, 8.0, 9.0, 0.0, 0.0, 0.0 }, cache.toArray());
	}

	/**
	 * {@code rope_rotation} rotates the pair {@code (x[f], x[f + head_size / 2])} of every
	 * head by the angle the frequency table holds for the current position, and only that
	 * position's row of the table.
	 */
	@Test(timeout = 180000)
	public void rotationUsesTheTableRowOfThePosition() {
		int headSize = 4;
		int seqLen = 4;
		PackedCollection freqCis = RotationFeatures.computeRopeFreqs(10000.0, headSize, seqLen).evaluate();
		double[] table = freqCis.toArray();

		for (int heads : new int[] { 2, 4 }) {
			PackedCollection position = new PackedCollection(shape(1));
			CompiledModel model = compile("rotate", shape(1, heads * headSize),
					args("heads", heads, "head_size", headSize, "freq_cis", freqCis, "position", p(position)));
			PackedCollection input = wave(shape(1, heads * headSize), 0.7);
			double[] x = input.toArray();

			for (int pos = 0; pos < seqLen; pos++) {
				position.fill(pos);
				double[] actual = model.forward(input).toArray();
				double[] expected = x.clone();
				for (int h = 0; h < heads; h++) {
					rotate(expected, h, headSize, table, pos);
				}
				assertClose("rotate heads=" + heads + " position " + pos, expected, actual);
			}
		}
	}

	/**
	 * {@code mra_rope_rotation} rotates each head group by its own frequency table at its own
	 * position.
	 */
	@Test(timeout = 180000)
	public void groupRotationUsesEachGroupsTableAndPosition() {
		int heads = 4;
		int headSize = 4;
		int seqLen = 6;
		PackedCollection[] positions = { new PackedCollection(shape(1)), new PackedCollection(shape(1)) };
		Producer<PackedCollection>[] positionProducers = new Producer[2];
		positionProducers[0] = p(positions[0]);
		positionProducers[1] = p(positions[1]);
		HeadGroupConfig[] groups = HeadGroupConfig.fromParams(new double[] { 10000.0, 500.0 },
				headSize, seqLen, new int[] { 1, 3 }, positionProducers);
		double[][] tables = { groups[0].freqCis.evaluate().toArray(), groups[1].freqCis.evaluate().toArray() };

		CompiledModel model = compile("rotate_groups", shape(1, heads * headSize),
				args("heads", heads, "head_size", headSize, "groups", groups));
		PackedCollection input = wave(shape(1, heads * headSize), 0.9);
		double[] x = input.toArray();

		int[][] groupPositions = { { 0, 5 }, { 3, 1 }, { 2, 2 } };
		for (int[] pair : groupPositions) {
			positions[0].fill(pair[0]);
			positions[1].fill(pair[1]);
			double[] actual = model.forward(input).toArray();
			double[] expected = x.clone();
			rotate(expected, 0, headSize, tables[0], pair[0]);
			for (int h = 1; h < heads; h++) {
				rotate(expected, h, headSize, tables[1], pair[1]);
			}
			assertClose("groups at " + pair[0] + "/" + pair[1], expected, actual);
		}
	}

	/**
	 * {@code attention_scores} is every head's query dotted with that head's slice of every
	 * cached row, without scaling.
	 */
	@Test(timeout = 180000)
	public void attentionScoresAreUnscaledDotProducts() {
		int headSize = 4;
		int seqLen = 4;
		for (int heads : new int[] { 2, 4 }) {
			int dim = heads * headSize;
			PackedCollection keys = wave(shape(seqLen, dim), 0.37);
			PackedCollection query = wave(shape(heads, headSize), 0.53);
			double[] k = keys.toArray();
			double[] q = query.toArray();
			double[] actual = run("scores", shape(heads, headSize),
					args("heads", heads, "head_size", headSize, "keys", keys), query);

			double[] expected = new double[heads * seqLen];
			for (int h = 0; h < heads; h++) {
				for (int s = 0; s < seqLen; s++) {
					for (int i = 0; i < headSize; i++) {
						expected[h * seqLen + s] += q[h * headSize + i] * k[s * dim + h * headSize + i];
					}
				}
			}
			assertClose("scores heads=" + heads, expected, actual);
		}
	}

	/**
	 * {@code causal_mask} followed by {@code softmax} gives every head a distribution over the
	 * positions up to and including the current one, with nothing on later positions, and the
	 * position is read on every pass.
	 */
	@Test(timeout = 180000)
	public void causalMaskLeavesOnlyPastAndPresentPositions() {
		int heads = 4;
		int seqLen = 4;
		PackedCollection position = new PackedCollection(shape(1));
		CompiledModel model = compile("masked_softmax", shape(heads, seqLen),
				args("heads", heads, "seq_len", seqLen, "position", p(position)));
		PackedCollection scores = wave(shape(heads, seqLen), 1.3);
		double[] s = scores.toArray();

		for (int pos : new int[] { 1, 3, 0 }) {
			position.fill(pos);
			double[] actual = model.forward(scores).toArray();
			double[] expected = new double[heads * seqLen];
			for (int h = 0; h < heads; h++) {
				double sum = 0;
				for (int t = 0; t <= pos; t++) {
					sum += Math.exp(s[h * seqLen + t]);
				}
				for (int t = 0; t <= pos; t++) {
					expected[h * seqLen + t] = Math.exp(s[h * seqLen + t]) / sum;
				}
			}
			assertClose("masked softmax at " + pos, expected, actual);
		}
	}

	/**
	 * {@code softmax()} normalizes each row separately and survives logits far too large
	 * for a naive exponential.
	 */
	@Test(timeout = 120000)
	public void softmaxIsPerRowAndStable() {
		PackedCollection input = pack(shape(3, 4),
				1.0, 2.0, 3.0, 4.0,
				1000.0, 1000.0, 999.0, 998.0,
				-5.0, 0.0, 5.0, 0.0);
		double[] x = input.toArray();
		double[] actual = run("row_softmax", shape(3, 4), args("rows", 3, "size", 4), input);

		double[] expected = new double[12];
		for (int row = 0; row < 3; row++) {
			double max = Double.NEGATIVE_INFINITY;
			for (int i = 0; i < 4; i++) max = Math.max(max, x[row * 4 + i]);
			double sum = 0;
			for (int i = 0; i < 4; i++) sum += Math.exp(x[row * 4 + i] - max);
			for (int i = 0; i < 4; i++) expected[row * 4 + i] = Math.exp(x[row * 4 + i] - max) / sum;
		}
		assertClose("row softmax", expected, actual);
	}

	/**
	 * {@code weighted_values} weights each head's slice of every cached row by that head's
	 * distribution and sums over the positions.
	 */
	@Test(timeout = 180000)
	public void weightedValuesSumOverTheSequence() {
		int headSize = 4;
		int seqLen = 4;
		for (int heads : new int[] { 2, 4 }) {
			int dim = heads * headSize;
			PackedCollection values = wave(shape(seqLen, dim), 0.41);
			PackedCollection weights = wave(shape(heads, seqLen), 0.29);
			double[] v = values.toArray();
			double[] w = weights.toArray();
			double[] actual = run("weighted", shape(heads, seqLen),
					args("heads", heads, "seq_len", seqLen, "values", values), weights);

			double[] expected = new double[dim];
			for (int h = 0; h < heads; h++) {
				for (int s = 0; s < seqLen; s++) {
					for (int i = 0; i < headSize; i++) {
						expected[h * headSize + i] += w[h * seqLen + s] * v[s * dim + h * headSize + i];
					}
				}
			}
			assertClose("weighted values heads=" + heads, expected, actual);
		}
	}

	/** {@code sqrt} is evaluated in configuration arithmetic. */
	@Test(timeout = 120000)
	public void sqrtEvaluatesInConfigurationArithmetic() {
		PackedCollection input = pack(shape(1, 4), 4.0, 8.0, -12.0, 0.0);
		double[] actual = run("scaled_by_inverse_root", shape(1, 4), args("size", 4, "n", 16), input);
		assertClose("scale(1 / sqrt(16))", new double[] { 1.0, 2.0, -3.0, 0.0 }, actual);
	}

	/**
	 * {@code rmsnorm} with {@code [heads, head_size]} weights takes its statistic over each
	 * head separately: a head an order of magnitude larger than its neighbour does not change
	 * the neighbour's normalization, and each head is scaled by its own row of weights.
	 */
	@Test(timeout = 120000)
	public void rmsnormWithPerHeadWeightsNormalizesEachHead() {
		int heads = 2;
		int headSize = 4;
		PackedCollection weights = pack(shape(heads, headSize), 0.5, 1.0, 1.5, 2.0, 2.0, 1.5, 1.0, 0.5);
		PackedCollection input = pack(shape(1, heads * headSize), 10.0, -20.0, 30.0, -40.0, 1.0, 2.0, -3.0, 4.0);
		double[] x = input.toArray();
		double[] scale = weights.toArray();
		double[] actual = run("head_norm", shape(1, heads * headSize),
				args("heads", heads, "head_size", headSize, "weights", weights), input);

		double[] expected = new double[heads * headSize];
		for (int h = 0; h < heads; h++) {
			double sumSq = 0;
			for (int i = 0; i < headSize; i++) sumSq += x[h * headSize + i] * x[h * headSize + i];
			double rms = Math.sqrt(sumSq / headSize + 1e-6);
			for (int i = 0; i < headSize; i++) {
				expected[h * headSize + i] = x[h * headSize + i] / rms * scale[h * headSize + i];
			}
		}
		assertClose("per-head rmsnorm", expected, actual);
	}

	/** {@code dense(w, b)} with {@code b} bound to {@code null} is the projection without a bias. */
	@Test(timeout = 120000)
	public void denseWithNullBiasProjectsWithoutBias() {
		PackedCollection w = pack(shape(3, 2), 1.0, 2.0, 3.0, 4.0, 5.0, 6.0);
		PackedCollection input = pack(shape(1, 2), 1.0, -1.0);
		Map<String, Object> args = args("in_size", 2, "w", w);
		args.put("b", null);
		double[] actual = run("projection", shape(1, 2), args, input);
		assertClose("dense(w, null)", new double[] { -1.0, -1.0, -1.0 }, actual);
	}

	/**
	 * Rotates head {@code head} of {@code v} in place in the split-half layout: element
	 * {@code f} pairs with element {@code f + headSize / 2}, and the pair is rotated by the
	 * {@code [cos, sin]} entry {@code table} holds for {@code position} and frequency {@code f}.
	 */
	private static void rotate(double[] v, int head, int headSize, double[] table, int position) {
		int half = headSize / 2;
		int base = head * headSize;
		for (int f = 0; f < half; f++) {
			double cos = table[(position * half + f) * 2];
			double sin = table[(position * half + f) * 2 + 1];
			double a = v[base + f];
			double b = v[base + half + f];
			v[base + f] = a * cos - b * sin;
			v[base + half + f] = b * cos + a * sin;
		}
	}

	/** Compiles one fixture layer on its own inside a {@link Model}. */
	private CompiledModel compile(String layer, TraversalPolicy inputShape, Map<String, Object> args) {
		PdslLoader loader = new PdslLoader();
		Block block = loader.buildLayer(loader.parse(fixture()), layer, inputShape, args);
		Model model = new Model(inputShape);
		model.add(block);
		return model.compile();
	}

	/** Runs one fixture layer over a single input and returns the output values. */
	private double[] run(String layer, TraversalPolicy inputShape, Map<String, Object> args,
						 PackedCollection input) {
		return compile(layer, inputShape, args).forward(input).toArray();
	}

	/** Reads the fixture program source from the test classpath. */
	private String fixture() {
		try (InputStream in = getClass().getResourceAsStream(FIXTURE)) {
			Assert.assertNotNull("Fixture " + FIXTURE + " missing from the test classpath", in);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to read " + FIXTURE, e);
		}
	}

	/** Builds an argument map from alternating names and values. */
	private static Map<String, Object> args(Object... namesAndValues) {
		Map<String, Object> args = new HashMap<>();
		for (int i = 0; i < namesAndValues.length; i += 2) {
			args.put((String) namesAndValues[i], namesAndValues[i + 1]);
		}
		return args;
	}

	/** A collection holding {@code 0, 1, 2, ...} in the given shape, produced on the device. */
	private PackedCollection ramp(TraversalPolicy shape) {
		return integers(0, shape.getTotalSize()).reshape(shape).evaluate();
	}

	/** A deterministic, non-degenerate collection: the sine of a scaled index ramp, produced on the device. */
	private PackedCollection wave(TraversalPolicy shape, double frequency) {
		return sin(integers(0, shape.getTotalSize()).multiply(frequency)).reshape(shape).evaluate();
	}

	/** Compares two vectors element-wise within {@link #TOLERANCE}. */
	private static void assertClose(String label, double[] expected, double[] actual) {
		Assert.assertEquals(label + " length", expected.length, actual.length);
		for (int i = 0; i < expected.length; i++) {
			Assert.assertEquals(label + " element " + i, expected[i], actual[i], TOLERANCE);
		}
	}
}
