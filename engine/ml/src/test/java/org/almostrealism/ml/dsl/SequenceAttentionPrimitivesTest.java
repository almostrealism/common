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

package org.almostrealism.ml.dsl;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * Standalone tests of the basic blocks parallel self-attention is written with in
 * {@code /pdsl/sequence_attention.pdsl}: {@code permute}, the shaped form of {@code slice}, the
 * per-axis form of {@code scale}, {@code layernorm}, the bias form of {@code rmsnorm} and
 * {@code sequence_rope}. Each is exercised by its own single-primitive layer in
 * {@code /pdsl/test_sequence_attention_primitives.pdsl} and compared against a reference computed
 * directly on the host, so the primitives are pinned independently of the attention migration that
 * motivated them.
 */
public class SequenceAttentionPrimitivesTest extends TestSuiteBase implements AttentionFeatures {

	/** Classpath location of the primitive fixtures. */
	private static final String FIXTURE = "/pdsl/test_sequence_attention_primitives.pdsl";

	/** Epsilon the normalization fixtures pass. */
	private static final double EPSILON = 1e-6;

	/**
	 * Builds one fixture layer for {@code inputShape}, compiles it and forwards {@code input}.
	 *
	 * @param layer      the fixture layer name
	 * @param inputShape the layer's input shape
	 * @param args       the layer's arguments
	 * @param input      the input to forward
	 * @return the layer's output, flattened
	 */
	private double[] run(String layer, TraversalPolicy inputShape, Map<String, Object> args,
						 PackedCollection input) {
		PdslLoader loader = new PdslLoader();
		Model model = new Model(inputShape);
		model.add(loader.buildLayer(loader.parseResource(FIXTURE), layer, inputShape, args));
		try (CompiledModel compiled = model.compile(false)) {
			return compiled.forward(input).doubleStream().toArray();
		}
	}

	/**
	 * Asserts that building a fixture layer for {@code inputShape} is rejected with a
	 * {@link PdslParseException}.
	 *
	 * @param layer      the fixture layer name
	 * @param inputShape the layer's input shape
	 * @param args       the layer's arguments
	 * @param message    the failure message when the layer is not rejected
	 */
	private void assertRejected(String layer, TraversalPolicy inputShape, Map<String, Object> args,
								String message) {
		PdslLoader loader = new PdslLoader();
		try {
			loader.buildLayer(loader.parseResource(FIXTURE), layer, inputShape, args);
			Assert.fail(message);
		} catch (PdslParseException expected) {
			// expected
		}
	}

	/**
	 * Builds an argument map from alternating names and values.
	 *
	 * @param entries name, value, name, value, ...
	 * @return the argument map
	 */
	private static Map<String, Object> args(Object... entries) {
		Map<String, Object> args = new HashMap<>();
		for (int i = 0; i < entries.length; i += 2) {
			args.put((String) entries[i], entries[i + 1]);
		}
		return args;
	}

	/**
	 * {@code permute(0, 2, 1, 3)} exchanges the two middle axes: element {@code (0, i, j, k)} of a
	 * {@code [1, x, y, z]} input becomes element {@code (0, j, i, k)} of the {@code [1, y, x, z]}
	 * output. The values are moved, not computed, so they must match exactly.
	 */
	@Test(timeout = 120000)
	public void permuteExchangesTheMiddleAxes() {
		int x = 3;
		int y = 2;
		int z = 4;
		PackedCollection input = new PackedCollection(shape(1, x, y, z)).randnFill();
		double[] in = input.doubleStream().toArray();

		double[] actual = run("swap_axes", shape(1, x, y, z), args(), input);
		assertEquals(in.length, actual.length);
		for (int i = 0; i < x; i++) {
			for (int j = 0; j < y; j++) {
				for (int k = 0; k < z; k++) {
					assertEquals("element (" + i + ", " + j + ", " + k + ")",
							in[(i * y + j) * z + k], actual[(j * x + i) * z + k], 0.0);
				}
			}
		}
	}

	/**
	 * {@code permute} rejects an order that names an axis twice, and an order that names fewer axes
	 * than its input has.
	 */
	@Test(timeout = 60000)
	public void permuteRejectsAnOrderThatIsNotAPermutationOfTheInputAxes() {
		assertRejected("repeated_axis", shape(1, 3, 2, 4), args(),
				"permute() should reject an order that names an axis twice");
		assertRejected("short_order", shape(1, 3, 2, 4), args(),
				"permute() should reject an order naming fewer axes than the input has");
	}

	/**
	 * {@code slice([1, rows, 1, width], 0, 0, index, 0)} takes the {@code index}-th section of every
	 * row of a {@code [1, rows, sections, width]} input, the way a fused projection is separated
	 * into its queries, keys and values. The values are moved, not computed.
	 */
	@Test(timeout = 120000)
	public void shapedSliceTakesOneSectionOfEveryRow() {
		int rows = 3;
		int sections = 3;
		int width = 4;
		int index = 1;
		PackedCollection input = new PackedCollection(shape(1, rows, sections, width)).randnFill();
		double[] in = input.doubleStream().toArray();

		double[] actual = run("section", shape(1, rows, sections, width),
				args("rows", rows, "width", width, "index", index), input);
		assertEquals(rows * width, actual.length);
		for (int r = 0; r < rows; r++) {
			for (int d = 0; d < width; d++) {
				assertEquals("row " + r + " feature " + d,
						in[(r * sections + index) * width + d], actual[r * width + d], 0.0);
			}
		}
	}

	/**
	 * The shaped {@code slice} rejects a section that does not lie inside its input: one past the
	 * last section, and one with more rows than the input holds.
	 */
	@Test(timeout = 60000)
	public void shapedSliceRejectsASectionOutsideTheInput() {
		TraversalPolicy inputShape = shape(1, 3, 3, 4);
		assertRejected("section", inputShape, args("rows", 3, "width", 4, "index", 3),
				"slice() should reject a section beyond the last one");
		assertRejected("section", inputShape, args("rows", 4, "width", 4, "index", 0),
				"slice() should reject a section with more rows than the input");
	}

	/**
	 * {@code scale(factors, 2)} multiplies every element at position {@code n} of axis 2 by
	 * {@code factors[0, n]}, across the head and feature axes; a factor of zero clears the position.
	 */
	@Test(timeout = 120000)
	public void axisScaleMultipliesEachPositionByItsFactor() {
		int heads = 2;
		int positions = 4;
		int width = 3;
		PackedCollection factors = PackedCollection.of(1.0, 0.0, 0.5, -2.0).reshape(shape(1, positions));
		PackedCollection input = new PackedCollection(shape(1, heads, positions, width)).randnFill();
		double[] in = input.doubleStream().toArray();
		double[] f = factors.doubleStream().toArray();

		double[] actual = run("position_scale", shape(1, heads, positions, width),
				args("factors", factors), input);
		assertEquals(in.length, actual.length);
		for (int h = 0; h < heads; h++) {
			for (int n = 0; n < positions; n++) {
				for (int d = 0; d < width; d++) {
					int idx = (h * positions + n) * width + d;
					assertEquals("element " + idx, in[idx] * f[n], actual[idx], 1e-6);
				}
			}
		}
	}

	/**
	 * The per-axis {@code scale} rejects factors that are not one per batch entry and position of
	 * the axis, rather than letting the broadcast reinterpret them.
	 */
	@Test(timeout = 60000)
	public void axisScaleRejectsFactorsOfTheWrongCount() {
		assertRejected("position_scale", shape(1, 2, 4, 3),
				args("factors", PackedCollection.of(1.0, 0.0, 0.5).reshape(shape(1, 3))),
				"scale(factors, axis) should reject three factors for four positions");
	}

	/**
	 * The per-axis {@code scale} rejects factors whose total count matches but whose shape is not
	 * exactly {@code [batch, length(axis)]}, rather than letting the broadcast reshape them and
	 * silently assign factors to the wrong batch/position pairs. For a {@code [2, _, 4, _]} input
	 * the factors must be {@code [2, 4]}; a transposed {@code [4, 2]} or a flat {@code [8]} tensor
	 * has the same eight elements but a different layout and must be refused.
	 */
	@Test(timeout = 60000)
	public void axisScaleRejectsFactorsOfTheWrongShape() {
		assertRejected("position_scale", shape(2, 2, 4, 3),
				args("factors", PackedCollection.of(
						1.0, 0.0, 0.5, -2.0, 1.0, 0.0, 0.5, -2.0).reshape(shape(4, 2))),
				"scale(factors, axis) should reject transposed [4, 2] factors for a [2, 4] layout");
		assertRejected("position_scale", shape(2, 2, 4, 3),
				args("factors", PackedCollection.of(
						1.0, 0.0, 0.5, -2.0, 1.0, 0.0, 0.5, -2.0).reshape(shape(8))),
				"scale(factors, axis) should reject a flat [8] factor tensor for a [2, 4] layout");
	}

	/**
	 * {@code layernorm(w, b, eps)} normalizes every run of {@code len(w)} features to zero mean and
	 * unit variance (the population variance, with {@code eps} added), then scales by {@code w} and
	 * shifts by {@code b}.
	 */
	@Test(timeout = 120000)
	public void layernormNormalizesEveryRun() {
		assertNormalizes("row_layernorm", true, true);
	}

	/** {@code layernorm(w, null, eps)} normalizes and scales every run without shifting it. */
	@Test(timeout = 120000)
	public void layernormWithoutBiasesOnlyScales() {
		assertNormalizes("row_layernorm", true, false);
	}

	/**
	 * {@code rmsnorm(w, b, eps)} divides every run of {@code len(w)} features by its root mean square
	 * (with {@code eps} added to the mean square), then scales by {@code w} and shifts by {@code b}.
	 */
	@Test(timeout = 120000)
	public void rmsnormWithBiasesNormalizesEveryRunAndShiftsIt() {
		assertNormalizes("row_rmsnorm", false, true);
	}

	/**
	 * Runs a normalization fixture over a {@code [1, 2, 3, 4]} input with four weights and compares
	 * every run of four features to the host computation.
	 *
	 * @param layer     the fixture layer
	 * @param centered  true for LayerNorm (subtract the mean, divide by the standard deviation),
	 *                  false for RMSNorm (divide by the root mean square)
	 * @param withBias  whether the fixture is given biases, or {@code null}
	 */
	private void assertNormalizes(String layer, boolean centered, boolean withBias) {
		int width = 4;
		PackedCollection input = new PackedCollection(shape(1, 2, 3, width)).randnFill();
		PackedCollection weights = new PackedCollection(shape(width)).randnFill();
		PackedCollection biases = withBias ? new PackedCollection(shape(width)).randnFill() : null;
		double[] in = input.doubleStream().toArray();
		double[] w = weights.doubleStream().toArray();
		double[] b = withBias ? biases.doubleStream().toArray() : new double[width];

		double[] actual = run(layer, shape(1, 2, 3, width), args("w", weights, "b", biases), input);
		assertEquals(in.length, actual.length);
		for (int run = 0; run < in.length / width; run++) {
			double mean = 0;
			for (int d = 0; d < width; d++) {
				mean += in[run * width + d] / width;
			}
			double spread = 0;
			for (int d = 0; d < width; d++) {
				double deviation = centered ? in[run * width + d] - mean : in[run * width + d];
				spread += deviation * deviation / width;
			}
			for (int d = 0; d < width; d++) {
				double centeredValue = centered ? in[run * width + d] - mean : in[run * width + d];
				double expected = centeredValue / Math.sqrt(spread + EPSILON) * w[d] + b[d];
				assertEquals(layer + " run " + run + " feature " + d,
						expected, actual[run * width + d], 1e-4);
			}
		}
	}

	/**
	 * {@code sequence_rope(inv_freq)} with one inverse frequency per pair of an eight-feature row
	 * rotates every row of a {@code [1, heads, seq_len, 8]} input by its position: feature {@code i}
	 * is paired with feature {@code i + 4} and the pair is rotated by {@code p * inv_freq[i]}.
	 */
	@Test(timeout = 120000)
	public void sequenceRopeRotatesEveryPositionByItsIndex() {
		assertRotates(PackedCollection.of(1.0, 0.5, 0.25, 0.125));
	}

	/**
	 * {@code sequence_rope(inv_freq)} with fewer inverse frequencies than the row has pairs rotates
	 * only the leading {@code 2 * len(inv_freq)} features of each row (pairing feature {@code i}
	 * with {@code i + len(inv_freq)}) and passes the rest through, the partial rotation of the
	 * Stable Audio diffusion transformer.
	 */
	@Test(timeout = 120000)
	public void sequenceRopeRotatesOnlyTheLeadingFeaturesOfAWiderRow() {
		assertRotates(PackedCollection.of(0.3, 0.1));
	}

	/**
	 * Runs the rotation fixture over a {@code [1, 2, 5, 8]} input and compares it to the rotation
	 * computed on the host.
	 *
	 * @param invFreq the inverse frequencies
	 */
	private void assertRotates(PackedCollection invFreq) {
		int heads = 2;
		int seqLen = 5;
		int headDim = 8;
		PackedCollection input = new PackedCollection(shape(1, heads, seqLen, headDim)).randnFill();
		double[] in = input.doubleStream().toArray();
		double[] freq = invFreq.doubleStream().toArray();
		int half = freq.length;

		double[] actual = run("rotate", shape(1, heads, seqLen, headDim), args("inv_freq", invFreq), input);
		assertEquals(in.length, actual.length);
		for (int h = 0; h < heads; h++) {
			for (int p = 0; p < seqLen; p++) {
				int row = (h * seqLen + p) * headDim;
				for (int j = 0; j < headDim; j++) {
					double expected = in[row + j];
					if (j < 2 * half) {
						double angle = p * freq[j % half];
						double partner = j < half ? -in[row + j + half] : in[row + j - half];
						expected = in[row + j] * Math.cos(angle) + partner * Math.sin(angle);
					}
					assertEquals("head " + h + " position " + p + " feature " + j,
							expected, actual[row + j], 1e-5);
				}
			}
		}
	}

	/**
	 * {@code sequence_rope} rejects inverse frequencies for more feature pairs than a row holds, and
	 * an input that is not {@code [batch, heads, seq_len, head_dim]}.
	 */
	@Test(timeout = 60000)
	public void sequenceRopeRejectsMoreFrequenciesThanTheRowHolds() {
		assertRejected("rotate", shape(1, 2, 5, 8),
				args("inv_freq", PackedCollection.of(1.0, 0.5, 0.25, 0.125, 0.0625)),
				"sequence_rope() should reject five frequencies for an eight-feature row");
		assertRejected("rotate", shape(2, 5, 8), args("inv_freq", PackedCollection.of(1.0, 0.5)),
				"sequence_rope() should reject an input without a head axis");
	}
}
