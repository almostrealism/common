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
 * Standalone tests of the {@code scaled_dot_product}, {@code key_mask} and
 * {@code sequence_causal_mask} PDSL primitives, each exercised by its own small fixture layer and
 * compared against a pure-Java reference. Operands the caller binds are exercised by
 * {@code /pdsl/test_sdpa_primitives.pdsl}; operands a full-sequence attention layer derives from
 * its own input (a branch as the operand of {@code scaled_dot_product}, and the causal mask of the
 * full score tensor) by {@code /pdsl/test_sdpa_sequence_primitives.pdsl}. These pin the primitives
 * independently of the scaled dot-product attention migration that motivated them, so they remain
 * meaningful whatever the {@code sdpa} asset does.
 */
public class SdpaPrimitivesTest extends TestSuiteBase implements AttentionFeatures {

	/** Classpath location of the primitive fixtures whose operands the caller binds. */
	private static final String FIXTURE = "/pdsl/test_sdpa_primitives.pdsl";

	/**
	 * Classpath location of the primitive fixtures whose operands come from the layer itself: a
	 * branch as the operand of {@code scaled_dot_product}, and the full-sequence causal mask.
	 */
	private static final String SEQUENCE_FIXTURE = "/pdsl/test_sdpa_sequence_primitives.pdsl";

	/** Batch size the primitives are exercised at. */
	private static final int BATCH = 1;

	/** Head count of the fixtures. */
	private static final int HEADS = 2;

	/** Query length of the fixtures. */
	private static final int QUERIES = 3;

	/** Key length of the fixtures. */
	private static final int KEYS = 4;

	/** Dimension per head of the fixtures. */
	private static final int DIM = 5;

	/** The factor the branch fixtures scale their input by to form the product's operand. */
	private static final double BRANCH_FACTOR = 2.0;

	/**
	 * Runs one primitive layer of {@link #FIXTURE} over {@code input} with a single tensor argument.
	 *
	 * @param layer      the fixture layer name
	 * @param inputShape the layer's input shape
	 * @param argName    the layer's parameter name
	 * @param argValue   the bound argument
	 * @param input      the input tensor to forward
	 * @return the layer's output, flattened
	 */
	private double[] run(String layer, TraversalPolicy inputShape,
						 String argName, PackedCollection argValue, PackedCollection input) {
		return run(FIXTURE, layer, inputShape, argMap(argName, argValue), input);
	}

	/**
	 * {@code scaled_dot_product(other, true)} computes {@code Q Kᵀ}: for a
	 * {@code [batch, heads, queries, dim]} input and a {@code [batch, heads, keys, dim]} operand,
	 * the result is {@code sum_d input[b,h,i,d] other[b,h,j,d]} at {@code [b,h,i,j]}.
	 */
	@Test(timeout = 120000)
	public void scaledDotProductTransposeIsQKt() {
		PackedCollection input = new PackedCollection(shape(BATCH, HEADS, QUERIES, DIM)).randnFill();
		PackedCollection other = new PackedCollection(shape(BATCH, HEADS, KEYS, DIM)).randnFill();
		double[] in = input.doubleStream().toArray();
		double[] o = other.doubleStream().toArray();

		double[] expected = new double[HEADS * QUERIES * KEYS];
		for (int h = 0; h < HEADS; h++) {
			for (int i = 0; i < QUERIES; i++) {
				for (int j = 0; j < KEYS; j++) {
					double dot = 0;
					for (int d = 0; d < DIM; d++) {
						dot += in[(h * QUERIES + i) * DIM + d] * o[(h * KEYS + j) * DIM + d];
					}
					expected[(h * QUERIES + i) * KEYS + j] = dot;
				}
			}
		}

		double[] actual = run("sdp_transpose", shape(BATCH, HEADS, QUERIES, DIM), "other", other, input);
		assertEquals(expected.length, actual.length);
		for (int idx = 0; idx < expected.length; idx++) {
			assertEquals("Q Kᵀ element " + idx, expected[idx], actual[idx], 1e-4);
		}
	}

	/**
	 * {@code scaled_dot_product(other, false)} computes {@code A V}: for a
	 * {@code [batch, heads, queries, keys]} input and a {@code [batch, heads, keys, dim]} operand,
	 * the result is {@code sum_j input[b,h,i,j] other[b,h,j,d]} at {@code [b,h,i,d]}.
	 */
	@Test(timeout = 120000)
	public void scaledDotProductPlainIsAv() {
		PackedCollection input = new PackedCollection(shape(BATCH, HEADS, QUERIES, KEYS)).randnFill();
		PackedCollection other = new PackedCollection(shape(BATCH, HEADS, KEYS, DIM)).randnFill();
		double[] in = input.doubleStream().toArray();
		double[] o = other.doubleStream().toArray();

		double[] expected = new double[HEADS * QUERIES * DIM];
		for (int h = 0; h < HEADS; h++) {
			for (int i = 0; i < QUERIES; i++) {
				for (int d = 0; d < DIM; d++) {
					double sum = 0;
					for (int j = 0; j < KEYS; j++) {
						sum += in[(h * QUERIES + i) * KEYS + j] * o[(h * KEYS + j) * DIM + d];
					}
					expected[(h * QUERIES + i) * DIM + d] = sum;
				}
			}
		}

		double[] actual = run("sdp_plain", shape(BATCH, HEADS, QUERIES, KEYS), "other", other, input);
		assertEquals(expected.length, actual.length);
		for (int idx = 0; idx < expected.length; idx++) {
			assertEquals("A V element " + idx, expected[idx], actual[idx], 1e-4);
		}
	}

	/**
	 * {@code key_mask(mask)} adds {@code (mask[b,j] - 1) * MASKED_LOGIT_PENALTY} to every score at
	 * key {@code j}: zero for a valid key (mask 1) and the large penalty for a masked one (mask 0),
	 * broadcast across the head and query axes.
	 */
	@Test(timeout = 120000)
	public void keyMaskBiasesMaskedKeys() {
		PackedCollection input = new PackedCollection(shape(BATCH, HEADS, QUERIES, KEYS)).randnFill();
		PackedCollection mask = new PackedCollection(shape(BATCH, KEYS));
		int validKeys = 2;
		lessThan(integers(0, KEYS), c((double) validKeys), c(1.0), c(0.0))
				.into(mask.traverseEach()).evaluate();

		double[] in = input.doubleStream().toArray();
		double[] m = mask.doubleStream().toArray();

		double[] expected = new double[HEADS * QUERIES * KEYS];
		for (int h = 0; h < HEADS; h++) {
			for (int i = 0; i < QUERIES; i++) {
				for (int j = 0; j < KEYS; j++) {
					int idx = (h * QUERIES + i) * KEYS + j;
					expected[idx] = in[idx] + (m[j] - 1.0) * MASKED_LOGIT_PENALTY;
				}
			}
		}

		double[] actual = run("km", shape(BATCH, HEADS, QUERIES, KEYS), "mask", mask, input);
		assertEquals(expected.length, actual.length);
		for (int idx = 0; idx < expected.length; idx++) {
			// Masked scores are near -1e9, where single precision loses the sub-unit input term;
			// tolerate that with a magnitude-relative bound while pinning valid scores tightly.
			double tolerance = Math.max(1e-3, Math.abs(expected[idx]) * 1e-5);
			assertEquals("key_mask element " + idx, expected[idx], actual[idx], tolerance);
		}
	}

	/**
	 * {@code key_mask(mask)} rejects a mask whose shape does not match the score shape's batch and
	 * key extents rather than letting the broadcast silently reshape it: a rank other than two, a
	 * mismatched batch, and a mismatched key count are each rejected.
	 */
	@Test(timeout = 60000)
	public void keyMaskRejectsMismatchedMaskShape() {
		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parseResource(FIXTURE);
		TraversalPolicy scoresShape = shape(BATCH, HEADS, QUERIES, KEYS);

		try {
			loader.buildLayer(program, "km", scoresShape,
					argMap("mask", new PackedCollection(shape(BATCH, KEYS, 1))));
			Assert.fail("key_mask should reject a mask that is not [batch, keys]");
		} catch (PdslParseException expected) {
			// expected
		}

		try {
			loader.buildLayer(program, "km", scoresShape,
					argMap("mask", new PackedCollection(shape(BATCH + 1, KEYS))));
			Assert.fail("key_mask should reject a mask whose batch does not match the score shape");
		} catch (PdslParseException expected) {
			// expected
		}

		try {
			loader.buildLayer(program, "km", scoresShape,
					argMap("mask", new PackedCollection(shape(BATCH, KEYS + 1))));
			Assert.fail("key_mask should reject a mask whose key count does not match the score shape");
		} catch (PdslParseException expected) {
			// expected
		}
	}

	/**
	 * {@code scaled_dot_product(other, transpose)} rejects an operand whose batch, head, or
	 * contracted extent is incompatible with the input rather than reinterpreting it: a mismatched
	 * head count (transpose) and a mismatched contracted key count (plain) are each rejected.
	 */
	@Test(timeout = 60000)
	public void scaledDotProductRejectsIncompatibleOperand() {
		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parseResource(FIXTURE);

		try {
			loader.buildLayer(program, "sdp_transpose", shape(BATCH, HEADS, QUERIES, DIM),
					argMap("other", new PackedCollection(shape(BATCH, HEADS + 1, KEYS, DIM))));
			Assert.fail("scaled_dot_product(true) should reject an operand with a mismatched head count");
		} catch (PdslParseException expected) {
			// expected
		}

		try {
			loader.buildLayer(program, "sdp_plain", shape(BATCH, HEADS, QUERIES, KEYS),
					argMap("other", new PackedCollection(shape(BATCH, HEADS, KEYS + 1, DIM))));
			Assert.fail("scaled_dot_product(false) should reject an operand with a mismatched key count");
		} catch (PdslParseException expected) {
			// expected
		}
	}

	/**
	 * {@code scaled_dot_product(keys, true)} reads its operand from a branch of the same layer: the
	 * fixture splits a keys branch {@code K = 2 Q} off its {@code [batch, heads, n, dim]} input and
	 * the result is {@code Q Kᵀ}, {@code sum_d q[h,i,d] * 2 q[h,j,d]} at {@code [b,h,i,j]}.
	 */
	@Test(timeout = 120000)
	public void scaledDotProductReadsAKeysBranch() {
		PackedCollection input = new PackedCollection(shape(BATCH, HEADS, QUERIES, DIM)).randnFill();
		double[] q = input.doubleStream().toArray();

		double[] expected = new double[HEADS * QUERIES * QUERIES];
		for (int h = 0; h < HEADS; h++) {
			for (int i = 0; i < QUERIES; i++) {
				for (int j = 0; j < QUERIES; j++) {
					double dot = 0;
					for (int d = 0; d < DIM; d++) {
						dot += q[(h * QUERIES + i) * DIM + d] * BRANCH_FACTOR * q[(h * QUERIES + j) * DIM + d];
					}
					expected[(h * QUERIES + i) * QUERIES + j] = dot;
				}
			}
		}

		double[] actual = run(SEQUENCE_FIXTURE, "sdp_branch_keys", shape(BATCH, HEADS, QUERIES, DIM),
				factorArgument(), input);
		assertEquals(expected.length, actual.length);
		for (int idx = 0; idx < expected.length; idx++) {
			assertEquals("Q Kᵀ against the keys branch, element " + idx, expected[idx], actual[idx], 1e-4);
		}
	}

	/**
	 * {@code scaled_dot_product(values, false)} reads its operand from a branch of the same layer: the
	 * fixture splits a values branch {@code V = 2 A} off its square {@code [batch, heads, n, n]}
	 * input and the result is {@code A V}, {@code sum_j a[h,i,j] * 2 a[h,j,d]} at {@code [b,h,i,d]}.
	 */
	@Test(timeout = 120000)
	public void scaledDotProductReadsAValuesBranch() {
		PackedCollection input = new PackedCollection(shape(BATCH, HEADS, KEYS, KEYS)).randnFill();
		double[] a = input.doubleStream().toArray();

		double[] expected = new double[HEADS * KEYS * KEYS];
		for (int h = 0; h < HEADS; h++) {
			for (int i = 0; i < KEYS; i++) {
				for (int d = 0; d < KEYS; d++) {
					double sum = 0;
					for (int j = 0; j < KEYS; j++) {
						sum += a[(h * KEYS + i) * KEYS + j] * BRANCH_FACTOR * a[(h * KEYS + j) * KEYS + d];
					}
					expected[(h * KEYS + i) * KEYS + d] = sum;
				}
			}
		}

		double[] actual = run(SEQUENCE_FIXTURE, "sdp_branch_values", shape(BATCH, HEADS, KEYS, KEYS),
				factorArgument(), input);
		assertEquals(expected.length, actual.length);
		for (int idx = 0; idx < expected.length; idx++) {
			assertEquals("A V against the values branch, element " + idx, expected[idx], actual[idx], 1e-4);
		}
	}

	/**
	 * A backward pass carries the product's gradient into the branch that supplied its operand.
	 * For {@code S = Q Kᵀ} with the keys branch {@code K = c Q} and an output gradient {@code G},
	 * the input receives the gradient of both paths: {@code c (G Q + Gᵀ Q)} for each head — the
	 * first term along the query path and the second back through the keys branch. A product whose
	 * operand was a constant copy of the keys would deliver the first term alone.
	 */
	@Test(timeout = 300000)
	public void branchOperandCarriesTheGradientIntoTheBranch() {
		TraversalPolicy inputShape = shape(BATCH, HEADS, QUERIES, DIM);
		PackedCollection input = new PackedCollection(inputShape).randnFill();
		PackedCollection outputGradient = new PackedCollection(shape(BATCH, HEADS, QUERIES, QUERIES)).randnFill();
		double[] q = input.doubleStream().toArray();
		double[] g = outputGradient.doubleStream().toArray();

		double[] expected = new double[HEADS * QUERIES * DIM];
		for (int h = 0; h < HEADS; h++) {
			for (int r = 0; r < QUERIES; r++) {
				for (int d = 0; d < DIM; d++) {
					double sum = 0;
					for (int j = 0; j < QUERIES; j++) {
						sum += g[(h * QUERIES + r) * QUERIES + j] * q[(h * QUERIES + j) * DIM + d];
						sum += g[(h * QUERIES + j) * QUERIES + r] * q[(h * QUERIES + j) * DIM + d];
					}
					expected[(h * QUERIES + r) * DIM + d] = BRANCH_FACTOR * sum;
				}
			}
		}

		PdslLoader loader = new PdslLoader();
		Model model = new Model(inputShape);
		model.add(loader.buildLayer(loader.parseResource(SEQUENCE_FIXTURE), "sdp_branch_keys",
				inputShape, factorArgument()));
		try (CompiledModel compiled = model.compile(true, true)) {
			compiled.forward(input);
			double[] actual = compiled.backward(outputGradient).doubleStream().toArray();
			assertEquals(expected.length, actual.length);
			for (int idx = 0; idx < expected.length; idx++) {
				assertEquals("input gradient element " + idx, expected[idx], actual[idx], 1e-4);
			}
		}
	}

	/**
	 * A branch is an operand of {@code scaled_dot_product} only when its output has the
	 * {@code [batch, heads, seq, dim]} shape the product contracts: a branch reshaped to three axes
	 * is rejected rather than reinterpreted.
	 */
	@Test(timeout = 60000)
	public void scaledDotProductRejectsABranchOfTheWrongShape() {
		Map<String, Object> args = new HashMap<>();
		args.put("heads", HEADS);
		args.put("rows", QUERIES);
		args.put("dim", DIM);

		PdslLoader loader = new PdslLoader();
		try {
			loader.buildLayer(loader.parseResource(SEQUENCE_FIXTURE), "sdp_branch_wrong_shape",
					shape(BATCH, HEADS, QUERIES, DIM), args);
			Assert.fail("scaled_dot_product should reject a branch whose output has three axes");
		} catch (PdslParseException expected) {
			// expected
		}
	}

	/**
	 * {@code sequence_causal_mask()} adds {@code -MASKED_LOGIT_PENALTY} to every score whose key
	 * comes after its query ({@code j > i}) and leaves every other score unchanged, on a rectangular
	 * {@code [batch, heads, queries, keys]} score tensor whose last key is after every query.
	 */
	@Test(timeout = 120000)
	public void sequenceCausalMaskPenalizesEveryLaterKey() {
		PackedCollection input = new PackedCollection(shape(BATCH, HEADS, QUERIES, KEYS)).randnFill();
		double[] in = input.doubleStream().toArray();

		double[] expected = new double[HEADS * QUERIES * KEYS];
		for (int h = 0; h < HEADS; h++) {
			for (int i = 0; i < QUERIES; i++) {
				for (int j = 0; j < KEYS; j++) {
					int idx = (h * QUERIES + i) * KEYS + j;
					expected[idx] = j > i ? in[idx] - MASKED_LOGIT_PENALTY : in[idx];
				}
			}
		}

		double[] actual = run(SEQUENCE_FIXTURE, "causal_scores", shape(BATCH, HEADS, QUERIES, KEYS),
				new HashMap<>(), input);
		assertEquals(expected.length, actual.length);
		for (int idx = 0; idx < expected.length; idx++) {
			// Masked scores are near -1e9, where single precision loses the sub-unit input term;
			// tolerate that with a magnitude-relative bound while pinning unmasked scores tightly.
			double tolerance = Math.max(1e-6, Math.abs(expected[idx]) * 1e-5);
			assertEquals("sequence_causal_mask element " + idx, expected[idx], actual[idx], tolerance);
		}
	}

	/**
	 * Followed by a softmax, {@code sequence_causal_mask()} gives each query a distribution over the
	 * keys up to and including its own position: every later key receives no weight, and the
	 * earlier keys share the weight a softmax over them alone would give.
	 */
	@Test(timeout = 120000)
	public void sequenceCausalMaskLeavesNoWeightOnLaterKeys() {
		PackedCollection input = new PackedCollection(shape(BATCH, HEADS, KEYS, KEYS)).randnFill();
		double[] in = input.doubleStream().toArray();

		double[] expected = new double[HEADS * KEYS * KEYS];
		for (int row = 0; row < HEADS * KEYS; row++) {
			int i = row % KEYS;
			double max = Double.NEGATIVE_INFINITY;
			for (int j = 0; j <= i; j++) {
				max = Math.max(max, in[row * KEYS + j]);
			}
			double total = 0;
			for (int j = 0; j <= i; j++) {
				total += Math.exp(in[row * KEYS + j] - max);
			}
			for (int j = 0; j <= i; j++) {
				expected[row * KEYS + j] = Math.exp(in[row * KEYS + j] - max) / total;
			}
		}

		double[] actual = run(SEQUENCE_FIXTURE, "causal_weights", shape(BATCH, HEADS, KEYS, KEYS),
				new HashMap<>(), input);
		assertEquals(expected.length, actual.length);
		for (int idx = 0; idx < expected.length; idx++) {
			assertEquals("causal attention weight " + idx, expected[idx], actual[idx], 1e-5);
		}
	}

	/**
	 * {@code sequence_causal_mask()} takes no arguments and masks only a
	 * {@code [batch, heads, queries, keys]} score tensor: an argument, and a score shape without four
	 * axes, are each rejected.
	 */
	@Test(timeout = 60000)
	public void sequenceCausalMaskRejectsArgumentsAndScoresWithoutFourAxes() {
		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parseResource(SEQUENCE_FIXTURE);

		try {
			loader.buildLayer(program, "causal_with_argument", shape(BATCH, HEADS, QUERIES, KEYS), new HashMap<>());
			Assert.fail("sequence_causal_mask should reject an argument");
		} catch (PdslParseException expected) {
			// expected
		}

		try {
			loader.buildLayer(program, "causal_scores", shape(HEADS, QUERIES, KEYS), new HashMap<>());
			Assert.fail("sequence_causal_mask should reject a score shape without four axes");
		} catch (PdslParseException expected) {
			// expected
		}
	}

	/**
	 * Runs one layer of a fixture over {@code input} with the given arguments.
	 *
	 * @param fixture    classpath location of the fixture that declares the layer
	 * @param layer      the fixture layer name
	 * @param inputShape the layer's input shape
	 * @param args       the layer's arguments
	 * @param input      the input tensor to forward
	 * @return the layer's output, flattened
	 */
	private double[] run(String fixture, String layer, TraversalPolicy inputShape,
						 Map<String, Object> args, PackedCollection input) {
		PdslLoader loader = new PdslLoader();
		Model model = new Model(inputShape);
		model.add(loader.buildLayer(loader.parseResource(fixture), layer, inputShape, args));
		try (CompiledModel compiled = model.compile(false)) {
			return compiled.forward(input).doubleStream().toArray();
		}
	}

	/**
	 * The argument of the branch fixtures: the factor their branch scales the input by.
	 *
	 * @return the argument map
	 */
	private static Map<String, Object> factorArgument() {
		Map<String, Object> args = new HashMap<>();
		args.put("factor", BRANCH_FACTOR);
		return args;
	}

	/**
	 * Builds a single-entry argument map for a fixture layer.
	 *
	 * @param name  the layer's parameter name
	 * @param value the bound argument
	 * @return the argument map
	 */
	private static Map<String, Object> argMap(String name, PackedCollection value) {
		Map<String, Object> args = new HashMap<>();
		args.put(name, value);
		return args;
	}
}
